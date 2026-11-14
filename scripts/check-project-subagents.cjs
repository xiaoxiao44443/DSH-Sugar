'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const {registerHooks} = require('node:module');
const {pathToFileURL} = require('node:url');

// Run after check-project-paths has installed the real Sugar loader. Only the worker's
// directory contents, persistence sink, and model stream are fixtures.
module.exports = async function checkProjectSubagents(packages, router, projects) {
  const pkg = name => require(path.join(packages, name, 'lib/index.js'));
  const subagentUrl = pathToFileURL(path.join(packages, 'dsh-subagent/lib/index.js')).href;
  // The production resolver runs under Linux. On Windows path.resolve('/workspace', '.')
  // would inject a drive letter before the router sees the requested directory.
  const guestPath = process.platform === 'win32' && registerHooks({resolve(name, ctx, next) {
    return next(name === 'node:path' && ctx.parentURL === subagentUrl ? 'node:path/posix' : name, ctx);
  }});
  const ctx = new (pkg('cordis').Context)();
  const contents = new Map(projects.map(project => [project.id, new Set([
    '/workspace', '/workspace/src', '/workspace/aaa', '/workspace/aaa/aaa', '/workspace/only-in-' + project.id,
  ])]));
  const prepared = new Map();
  const fs = {
    async resolve(value, {cwd = '/workspace'} = {}) {
      return path.posix.resolve(cwd, value);
    },
    async stat(target) {
      const worker = router.current();
      assert.ok(worker, 'child directory inspection must run inside a project worker');
      return contents.get(worker.id)?.has(target) ? {type:'directory'} : undefined;
    },
    processPath: target => target,
  };
  const gates = new Map();
  ctx.provide('fs', fs);
  ctx.provide('sessionPersistence', {create: async () => ({
    append: async () => {}, flush: async () => {}, close: async () => {},
  })});
  ctx.provide('llm', {prepareCall: async config => ({config, async *stream(request) {
    const gate = gates.get(request.sessionId);
    if (gate) await gate.promise;
    yield {type:'text-delta', index:0, text:'OK'};
    yield {type:'finish', reason:{kind:'stop'}};
  }})});
  const mount = async (name, options = {}) => {
    const plugin = pkg(name);
    await ctx.plugin(plugin.default ?? plugin, options);
  };
  const signal = AbortSignal.timeout(30000);
  try {
    for (const name of ['dsh-session', 'dsh-session-projection', 'dsh-session-query', 'dsh-agent', 'dsh-system-prompt',
      'dsh-tools', 'dsh-tool-executor-native', 'dsh-agent-loop', 'dsh-working-directory', 'dsh-subagent']) {
      await mount(name);
    }
    for (const [provider, toolName] of [['spawn', 'subagent'], ['fork', 'subagent_fork']]) {
      await mount('dsh-subagent-' + provider + '-in-process');
      const backend = ctx.subagents.getProvider(provider);
      const prepare = backend.prepareContinuable.bind(backend);
      backend.prepareContinuable = request => {
        prepared.set(request.sessionId, {cwd:request.cwd, parentId:request.parent.id});
        return prepare(request);
      };
      await mount('dsh-tool-subagent', {provider, toolName});
    }
    ctx.subagents.registerProvider({
      name:'external', capabilities:{depthLimit:true}, inheritsParentContext:false,
      async start(request) {
        const id = 'external-' + request.parent.id;
        prepared.set(id, {cwd:request.cwd, parentId:request.parent.id});
        return {id, result:Promise.resolve({output:[], stopReason:'completed'}), dispose:async () => {}};
      },
    });
    await mount('dsh-tool-subagent', {provider:'external', toolName:'subagent_external'});
    ctx.on('session/event', (session, event) => {
      if (session.header.origin === 'subagent' && event.type === 'subagent/descriptor') {
        gates.set(session.id, Promise.withResolvers());
      }
    });
    for (const project of projects) {
      const parent = (await ctx.agents.create({sessionId:'parent-' + project.id,
        meta:{cwd:project.path}, agentOptions:{provider:'fixture',model:'paused'}})).agent;
      const start = (toolName, cwd) => ctx.tools.get(toolName, parent).execute({
        description:'Inspect child working directory', prompt:'OK', ...(cwd === undefined ? {} : {cwd}),
      }, {agent:parent, signal});
      const verify = async (toolName, cwd, expected) => {
        const receipt = await start(toolName, cwd);
        const childId = await ctx.subagents.resolveName(parent, receipt.name, signal);
        assert.deepEqual(prepared.get(childId), {cwd:expected, parentId:parent.id}, toolName + ' provider request');
        const child = ctx.agents.get(childId);
        assert.ok(child, 'delegation must return a live child');
        assert.equal(child.session.header.parentSession, parent.id);
        assert.equal(child.session.header.cwd, project.path);
        assert.equal(await ctx.workingDirectory.ensure(child, signal), expected);
        const view = ctx.sessionProjections.snapshot(child.session, ['workingDirectory']);
        assert.equal(view.values.workingDirectory, expected);
        await router.within(child.session.header.cwd, () => assert.equal(router.current().id, project.id), child.id);
        gates.get(child.id).resolve();
        await child.whenIdle();
      };
      await verify('subagent', undefined, '/workspace');
      await verify('subagent_fork', 'src', '/workspace/src');
      await ctx.workingDirectory.set(parent, 'src', signal);
      await verify('subagent', undefined, '/workspace/src');
      await verify('subagent', '/workspace', '/workspace');
      // Project A is registered as /workspace/aaa. Both Linux workers also contain
      // an ordinary directory named aaa directly below their own /workspace.
      await verify('subagent_fork', '/workspace/aaa', '/workspace/aaa');
      await ctx.workingDirectory.set(parent, '/workspace/aaa', signal);
      await verify('subagent', undefined, '/workspace/aaa');
      await verify('subagent_fork', 'aaa', '/workspace/aaa/aaa');
      const external = await start('subagent_external', 'aaa');
      assert.deepEqual(prepared.get(await ctx.subagents.resolveName(parent, external.name, signal)), {cwd:'/workspace/aaa/aaa', parentId:parent.id});
      const marker = '/workspace/only-in-' + project.id;
      const other = projects.find(candidate => candidate.id !== project.id);
      await verify('subagent', marker, marker);
      await assert.rejects(start('subagent', '/workspace/only-in-' + other.id), /directory does not exist/);
      await assert.rejects(start('subagent', '/workspace/missing'), /directory does not exist/);
      await assert.rejects(start('subagent', '/tmp'), /parent workspace/);
    }
    console.log('Real spawn/fork and external provider dispatch passed for guest cwd, parent identity and project-specific directories.');
  } finally {
    for (const gate of gates.values()) gate.resolve();
    await ctx.fiber.dispose();
    if (guestPath) guestPath.deregister();
  }
};
