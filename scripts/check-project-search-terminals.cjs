'use strict';

// Exercise rc.3 workspace search and the shared user/agent terminal launch path with
// the production rewrite installed and only the native project endpoints replaced.
const assert = require('node:assert/strict');
const {AsyncLocalStorage} = require('node:async_hooks');
const {createHash} = require('node:crypto');
const {registerHooks} = require('node:module');
const path = require('node:path');
const {Readable} = require('node:stream');
const {pathToFileURL} = require('node:url');

async function check(packages) {
  const assets = path.join(__dirname, '../app/src/main/assets/guest');
  const recipes = require(path.join(assets, 'project-hooks.json'));
  const targets = new Map(['dsh-api-workspace-files', 'dsh-util-ripgrep', 'dsh-api-terminal-controller'].map(pkg => {
    const file = pkg+'/lib/index.js';
    return [pathToFileURL(path.join(packages,file)).href, recipes[file]];
  }));
  const patches = registerHooks({load(url, ctx, next) {
    const original = next(url,ctx), recipe = targets.get(url);
    if (!recipe) return original;
    let source = Buffer.from(original.source).toString();
    assert.equal(createHash('sha256').update(source).digest('hex'), recipe.hash);
    for (const [from,to] of recipe.edits) {
      assert.equal(source.split(from).length, 2);
      source = source.replace(from, () => to.replaceAll('SUGAR_HOOK', 'globalThis.sugarProjectProbe'));
    }
    return {...original,source};
  }});
  const project = new AsyncLocalStorage(), owners = new AsyncLocalStorage();
  globalThis.sugarProjectProbe = {
    within:(root, run, sessionId) => project.run({root,sessionId},run),
    sessionRoot:() => '/workspace', current:() => project.getStore(),
    deviceProcess:(agent, manual, run) => owners.run({agent,manual}, run),
  };
  try {
    const load = pkg => require(path.join(packages,pkg,'lib/index.js'));
    const {WorkspaceFiles} = load('dsh-api-workspace-files');
    const {TerminalController} = load('dsh-api-terminal-controller');
    const scope = {workspaceRoot:'/home/sugar/project-a',sessionId:'a'};
    const signal = AbortSignal.timeout(5000);
    const checkOwner = () => assert.deepEqual(project.getStore(), {root:scope.workspaceRoot, sessionId:scope.sessionId});
    const files = Object.create(WorkspaceFiles.prototype);
    const fileSystem = {
      resolve(root) { checkOwner(); assert.equal(root, '/workspace'); return root; },
      stat() { checkOwner(); return {type:'directory'}; }, processPath:target => target,
    };
    const processService = {spawn(spec) {
      checkOwner();assert.equal(spec.cwd, '/workspace');assert.deepEqual(spec.argv.slice(0,3), ['/usr/bin/rg','--no-config','--files']);
      return {stdout:Readable.from([Buffer.from('src/needle.html\0other-file.txt\0')]),collected:{},
        done:Promise.resolve({exitCode:0,signal:null}),waitForExit:async()=>{}};
    }};
    Object.defineProperties(files, {ctx:{value:{fs:fileSystem,get:()=>processService}},
      config:{value:{search:{maxResults:20,maxEntries:100,timeoutMs:4500,stderrMaxBytes:1024,graceMs:15}}}});
    const result = await files.search(scope,{query:'needle',excludedPaths:[],respectGitignore:true},signal);
    assert.deepEqual(result.matches,[{path:'src/needle.html',type:'file'}]);

    const controller = Object.create(TerminalController.prototype);
    const agent = {id:scope.sessionId,session:{header:{cwd:scope.workspaceRoot}}};
    const stop = Error('stop after inspecting the native PTY request');
    Object.defineProperty(controller,'config',{value:{disposeGraceMs:50}});
    for (const origin of ['user','agent']) {
      controller.execution = () => ({subprocess:{spawnTerminal(spec) {
        checkOwner(); assert.equal(spec.cwd,'/workspace');
        assert.deepEqual(owners.getStore(),{agent,manual:origin === 'user'});throw stop;
      }}});
      const prepare = () => {checkOwner();return {info:{origin,cwd:'/workspace',cols:80,rows:24},argv:['bash'],env:{}};};
      await assert.rejects(controller.spawn(agent,{},origin,prepare,signal),error=>error===stop);
    }
    console.log('Workspace filename search and user/agent PTY routing passed.');
  } finally { patches.deregister();delete globalThis.sugarProjectProbe; }
}
if (process.argv.length !== 3) throw Error('Pass the unpacked node_modules/@deepseek-ai directory');
check(path.resolve(process.argv[2])).catch(error => { console.error(error); process.exitCode=1; });
