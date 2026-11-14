'use strict';
// Called by check-project-paths.cjs before that test installs module rewriting hooks.
// DSH owns the session store, agent registry, tools and turn driver. The model stream
// is gated locally so a long thinking-only turn can be tested without network access.
const assert = require('node:assert/strict');
const {createRequire} = require('node:module');
const path = require('node:path');
const {setImmediate: settle} = require('node:timers/promises');
const {mock} = require('node:test');

module.exports = async function checkProjectTurns(packages, assets, router, projects, activity) {
  const from = createRequire(path.join(packages, '..', '__fixture__.cjs'));
  const pkg = name => from('@deepseek-ai/' + name);
  const ctx = new (pkg('cordis').Context)();
  const workspace = require(path.join(assets, 'dsh-sugar-workspace', 'index.js'));
  assert.deepEqual(workspace.inject, ['agents']);
  const requests = new Map();
  const completions = new Map();
  let toolCalls = 0;
  async function* model(request) {
    const gate = Promise.withResolvers();
    requests.set(request.sessionId, gate);
    await gate.promise;
    yield {type:'text-delta', index:0, text:'OK'};
    yield {type:'finish', reason:{kind:'stop'}};
  }
  ctx.provide('llm', {prepareCall: async config => ({config, stream:model})});
  for (const name of ['dsh-session','dsh-session-projection','dsh-agent','dsh-system-prompt',
    'dsh-tools','dsh-tool-executor-native','dsh-agent-loop']) {
    const module = pkg(name);
    await ctx.plugin(module.default ?? module, {});
  }
  ctx.on('session/event', (session, event) => {
    if (event.type === 'turn/end') completions.set(session.id, event.data.reason.kind);
    if (event.type === 'tool/call') toolCalls++;
  });
  const create = async (id, project, parentAgent) => (await ctx.agents.create({
    sessionId:id, meta:{cwd:project.path, ...(parentAgent ? {origin:'subagent', parentSession:parentAgent.id} : {})}, agentOptions:{provider:'fixture',model:'paused'},
  })).agent;
  const a = await create('alpha', projects[0]);
  const b = await create('beta', projects[1]);
  const sibling = await create('sibling', projects[0]);
  const child = await create('child', projects[1], a);
  const turn = async agent => {
    requests.delete(agent.id);
    completions.delete(agent.id);
    agent.followup(pkg('dsh-llm').createUserMessage({source:{kind:'user'},content:[{type:'text',text:'hello'}]}));
    await settle();
    assert.ok(requests.get(agent.id), 'turn reached the model stream');
  };
  const tick = async () => {
    activity.length = 0;
    mock.timers.tick(300000);
    await settle();
    return [...activity].sort();
  };
  const finish = async (agent, fail = false) => {
    const request = requests.get(agent.id);
    if (fail) request.reject(Error('fixture model failed'));
    else request.resolve();
    await agent.whenIdle();
    assert.equal(completions.get(agent.id), fail ? 'error' : 'completed');
  };
  await router.within(projects[0].path, () => {});
  mock.timers.enable({apis:['setInterval']});
  try {
    // Mount mid-turn, just as after Cordis reloads the plugin.
    await turn(a);
    const mount = ctx.plugin(workspace);
    await mount;
    await settle();
    assert.deepEqual(activity.splice(0), ['one']);
    await turn(child);
    await turn(b); // The second project is still cold; thinking must not start its worker.
    assert.deepEqual(await tick(), ['one']);
    await router.within(projects[1].path, () => {});
    await turn(sibling);
    assert.deepEqual(await tick(), ['one','two']);
    await finish(a);
    assert.deepEqual(await tick(), ['one','two']);
    await finish(b, true);
    assert.deepEqual(await tick(), ['one']); // The only remaining root is sibling.
    await finish(sibling);
    assert.deepEqual(await tick(), []);
    await finish(child);
    await turn(a);
    a.cancel({kind:'user'});
    requests.get(a.id).resolve();
    await a.whenIdle();
    assert.equal(completions.get(a.id), 'aborted');
    assert.deepEqual(await tick(), []);
    await router.touchActiveWorkspace(projects[1].path, a.id);
    assert.deepEqual(activity, [], 'a session pinned to another project cannot keep this worker warm');
    await turn(a);
    await mount.dispose();
    assert.deepEqual(await tick(), []);
    await finish(a);
    assert.equal(toolCalls, 0);
    console.log('Project turn keepalive passed: real DSH model turns, existing worker isolation, concurrency, failure, abort, reload and disposal.');
  } finally {
    mock.timers.reset();
    await ctx.fiber.dispose();
  }
};
