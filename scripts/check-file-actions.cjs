'use strict';
// Exercise the packaged plugin using its public DSH services and Android transfer contract.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createHash } = require('node:crypto');
const file = path.resolve(__dirname, '../app/src/main/assets/guest/dsh-sugar-file-actions/client.js');
async function main() {
  const providers = new Map();
  const effects = [];
  const tree = [];
  const exports = [];
  const reads = [];
  const bytes = Buffer.alloc(33 * 1024 * 1024 + 31);
  for (let i = 0; i < bytes.length; i++) bytes[i] = (i ^ (i >>> 17)) & 255;
  const info = {absolutePath:'/workspace/项目/原件.bin', bytes:bytes.length, version:'v1'};
  const session = 'original-session';
  const target = {sessionId:session, path:'项目/原件.bin'};
  const controller = new AbortController();
  const listings = [];
  let listAllowed = true;
  let activeTarget = null, activeChanged;
  const stockAttributes = new Set();
  const stock = {disabled:true, textContent:'menu.reveal',
    setAttribute:name => stockAttributes.add(name), removeAttribute:name => stockAttributes.delete(name)};
  const mobile = {disabled:false, textContent:'reveal'};
  const copy = {disabled:false, textContent:'copyDirectoryPath'};
  const menu = {
    querySelectorAll:() => [stock,mobile,copy],
    querySelector:selector => selector === '[data-sugar-file-action="reveal"]' ? {closest:() => mobile} : null
  };
  class MutationObserver { observe() {} disconnect() {} }

  const api = {
    async list(id, p) {
      assert.equal(id,session);
      listings.push(p);
      return listAllowed ? {ok:true,value:{path:p === '.' || p === '/workspace' ? '' : p.replace(/^\/workspace\//,''),entries:[],truncated:false}}
        : {ok:false,error:{code:'workspace-file/not-found'}};
    },
    async stat(id, p) {
      assert.equal(id, session); assert.equal(p,target.path);
      return {ok:true, value:{...info}};
    },
    async readBytes(id,p,{range:{offset,length}}) {
      assert.equal(id,session); assert.equal(p,target.path); assert.ok(length <= 512 * 1024);
      reads.push(offset);
      return {ok:true, value:{...info, offset, data:bytes.subarray(offset,offset+length), eof:offset+length === info.bytes}};
    }
  };
  const rows = [{workspaceId:'original-project',path:'/workspace/手机生图模型',sessionIds:[session]}, {workspaceId:'other-project',sessionIds:[]}];
  const ctx = {
    locale:{register() { return () => {}; }, bind:() => key => key},
    remote:{workspaceFiles:api}, remoteAccess:{whenAccess:(_scope, init) => init()},
    slots:{inject() {}}, workspaces:{list:{getSnapshot:() => ({items:rows})}},
    contextMenus:{
      active:{getSnapshot:() => activeTarget, subscribe:listener => {activeChanged=listener; return () => {}; }}, notify() {},
      register(provider) { providers.set(provider.target,provider); return () => providers.delete(provider.target); }
    },
    effect(fn) { effects.push(fn()); }
  };
  const win = {
    __ModuleLoader__:{load:pkg => pkg.factory(() => ({})).apply(ctx)},
    __dshSugarRevealFolder(id,relative,kind) { tree.push(kind ? [id,relative,kind] : [id,relative]); return true; },
    async __dshSugarExportBlob(mode, factory, name) {
      const source = await factory();
      const hash = createHash('sha256');
      for (let start = 0; start < source.size; start += 131072) {
        hash.update(Buffer.from(await source.slice(start, Math.min(start + 131072,source.size)).arrayBuffer()));
      }
      exports.push({mode,name,digest:hash.digest('hex')});
    }
  };
  const document = {body:{},head:{append() {}},createElement:() => ({remove() {}}),querySelector:() => menu};
  vm.runInNewContext(fs.readFileSync(file,'utf8'), {window:win, document, AbortController, Uint8Array, MutationObserver});
  const fileMenu = providers.get('file');
  const resolve = () => fileMenu.resolve(target,controller.signal);
  const byId = (actions,id) => actions.find(action => action.id === id);
  const actions = await resolve();
  await byId(actions,'download').run();
  assert.deepEqual(exports,[{mode:'download',name:'原件.bin',digest:createHash('sha256').update(bytes).digest('hex')}]);
  assert.equal(reads.length,Math.ceil(bytes.length/(512*1024)));
  await byId(actions,'reveal').run();
  assert.deepEqual(tree,[['original-project','项目/原件.bin']]);
  const directoryMenu = providers.get('directory');
  assert.ok(directoryMenu, 'Directory menus must have an Android reveal contribution');
  // Directory reveal does not depend on the file-export bridge or regular-file stat.
  const exportFile = win.__dshSugarExportBlob;
  delete win.__dshSugarExportBlob;
  const directory = await directoryMenu.resolve({sessionId:session,path:'localdream-pack/dsh-localdream'},controller.signal);
  assert.equal(directory.length,1);
  assert.equal(directory[0].id,'reveal');
  directory[0].run();
  assert.deepEqual(tree.at(-1),['original-project','localdream-pack/dsh-localdream','directory']);
  for (const root of ['/workspace/手机生图模型','/workspace','.','']) {
    const actions = await directoryMenu.resolve({sessionId:session,path:root},controller.signal);
    actions[0].run();
    assert.deepEqual(tree.at(-1),['original-project','','directory']);
  }
  assert.deepEqual(listings.slice(1),['.','/workspace','.','.']);
  const absolute = await directoryMenu.resolve({sessionId:session,path:'/workspace/images'},controller.signal);
  absolute[0].run();
  assert.deepEqual(tree.at(-1),['original-project','images','directory']);
  listAllowed = false;
  assert.equal((await directoryMenu.resolve({sessionId:session,path:'missing'},controller.signal)).length,0);
  listAllowed = true;
  const canceled = new AbortController(); canceled.abort();
  await assert.rejects(directoryMenu.resolve({sessionId:session,path:'images'},canceled.signal));
  const reveal = win.__dshSugarRevealFolder;
  win.__dshSugarRevealFolder = () => false;
  assert.throws(() => directory[0].run(),/unavailable/);
  delete win.__dshSugarRevealFolder;
  assert.equal((await directoryMenu.resolve({sessionId:session,path:'images'},controller.signal)).length,0);
  win.__dshSugarRevealFolder = reveal;
  win.__dshSugarExportBlob = exportFile;
  activeTarget = {kind:'directory'}; activeChanged();
  assert(stockAttributes.has('data-sugar-stock-action'), 'Hide the disabled stock reveal beside the working Android action');
  assert.equal(copy.disabled,false);
  activeTarget = null; activeChanged();
  assert.equal(stockAttributes.size,0);
  assert.equal(stock.disabled,true);
  rows[0].sessionIds = [];
  assert.throws(() => byId(actions,'reveal').run());
  assert.throws(() => directory[0].run());
  assert.equal((await directoryMenu.resolve({sessionId:session,path:'images'},controller.signal)).length,0);
  info.version = 'v2';
  await assert.rejects(byId(actions,'open').run(), /changed|unavailable/);
  info.bytes = 0;
  const empty = await resolve();
  await byId(empty,'open').run();
  assert.equal(exports.at(-1).digest,createHash('sha256').digest('hex'));
  effects.reverse().forEach(dispose => dispose?.());
  assert.equal(providers.size,0);
  assert.throws(() => absolute[0].run(),/unavailable/);
  console.log('Android file actions passed: 33 MiB, empty file, directory/root reveal, stock-row replacement, cancellation, version/ownership checks and plugin disposal.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
