const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {pathToFileURL} = require('node:url');
const directory = path.resolve(__dirname, '../app/src/main/assets/guest/dsh-sugar-recovery');

async function check() {
  const {apply} = await import(pathToFileURL(path.join(directory,'index.js')).href);
  class Registry {
    items = [];
    async initializeDefault() { return this.items[0] ?? (this.items[0] = '/workspace/deepseek-harness/default-workspace'); }
    async create(name) { this.items.push(name); }
    async delete(name) { this.items = this.items.filter(item => item !== name); }
  }
  const registry = new Registry();
  const normal = new Registry();
  const cleanup = [];
  const services = {};
  const context = {workspaceRegistry:registry, effect:fn => cleanup.push(fn()),
    provide(name,value) { services[name] = value; cleanup.push(() => delete services[name]); }};
  apply(context);
  const home = await registry.initializeDefault();
  await assert.rejects(registry.create('/workspace/second'), /single fixed workspace/);
  await assert.rejects(registry.delete(home), /single fixed workspace/);
  assert.equal(await registry.initializeDefault(),home);
  assert.deepEqual(registry.items,[home]);
  await normal.create('unrelated');
  assert.deepEqual(normal.items,['unrelated']);
  assert.deepEqual(services.directoryPicker.capability(),{kind:'unavailable'});

  let plugin;
  const window = new EventTarget();
  window.__ModuleLoader__ = {load:entry => {plugin = entry.factory();}};
  vm.runInNewContext(fs.readFileSync(path.join(directory,'client.js'),'utf8'), {window,Event});
  class Access { source(kind,id,action) { return {getSnapshot:() => [kind,id,action]}; } }
  const access = new Access();
  const store = value => {
    const listeners = new Set();
    return {getSnapshot:() => value, subscribe:fn => { listeners.add(fn); return () => listeners.delete(fn); },
      set(next) { value = next; for (const fn of listeners) fn(); }};
  };
  const generation = store({});
  const listing = (...rows) => ({phase:'ready',byId:Object.fromEntries(rows.map((r,i) => [i,r]))});
  const running = {running:true}, child = {running:true,origin:'subagent'};
  const list = store(listing(running,child));
  plugin.apply({...context,remoteAccess:access,sessions:{list},connection:{generation}});
  assert.equal(window.SugarRecoveryTurnActive,true,'running at attach');
  list.set(listing(child,running,running));
  list.set(listing(child,running));
  assert.equal(window.SugarRecoveryTurnActive,true,'remaining active root');
  generation.set(undefined);
  assert.equal(window.SugarRecoveryTurnActive,false,'disconnected');
  generation.set({});
  assert.equal(window.SugarRecoveryTurnActive,true,'reconnected');
  list.set(listing(child));
  assert.equal(window.SugarRecoveryTurnActive,false,'no active root');
  assert.equal(access.source('workspace',home,'workspace/delete').getSnapshot(),false);
  assert.deepEqual(access.source('workspace',home,'workspace/rename').getSnapshot(),['workspace',home,'workspace/rename']);
  assert.deepEqual(access.source('session','one','workspace/archiveSession').getSnapshot(),['session','one','workspace/archiveSession']);
  while (cleanup.length) cleanup.pop()();
  assert.equal(window.SugarRecoveryTurnActive,undefined);
  assert.equal(Object.hasOwn(access,'source'),false);
  assert.equal(Object.hasOwn(registry,'create'),false);
  assert.equal(Object.hasOwn(registry,'delete'),false);
  assert.equal(services.directoryPicker,undefined);
  await registry.delete(home);
  assert.deepEqual(registry.items,[]);
}

check().then(() => console.log('Recovery workspace initialization, mutation guards, picker, menu and disposal passed.'))
  .catch(error => { console.error(error); process.exitCode = 1; });
