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
  const api = {
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
  const rows = [{workspaceId:'original-project',sessionIds:[session]}, {workspaceId:'other-project',sessionIds:[]}];
  const ctx = {
    locale:{register() { return () => {}; }, bind:() => key => key},
    remote:{workspaceFiles:api}, remoteAccess:{whenAccess:(_scope, init) => init()},
    slots:{inject() {}}, workspaces:{list:{getSnapshot:() => ({items:rows})}},
    contextMenus:{
      active:{getSnapshot:() => null, subscribe:() => () => {}}, notify() {},
      register(provider) { providers.set(provider.target,provider); return () => providers.delete(provider.target); }
    },
    effect(fn) { effects.push(fn()); }
  };
  const win = {
    __ModuleLoader__:{load:pkg => pkg.factory(() => ({})).apply(ctx)},
    __dshSugarRevealFolder(id,relative) { tree.push([id,relative]); return true; },
    async __dshSugarExportBlob(mode, factory, name) {
      const source = await factory();
      const hash = createHash('sha256');
      for (let start = 0; start < source.size; start += 131072) {
        hash.update(Buffer.from(await source.slice(start, Math.min(start + 131072,source.size)).arrayBuffer()));
      }
      exports.push({mode,name,digest:hash.digest('hex')});
    }
  };
  const document = {head:{append() {}}, createElement:() => ({remove() {}})};
  vm.runInNewContext(fs.readFileSync(file,'utf8'), {window:win, document, AbortController, Uint8Array});
  const fileMenu = providers.get('file');
  const resolve = () => fileMenu.resolve(target,controller.signal);
  const byId = (actions,id) => actions.find(action => action.id === id);
  const actions = await resolve();
  await byId(actions,'download').run();
  assert.deepEqual(exports,[{mode:'download',name:'原件.bin',digest:createHash('sha256').update(bytes).digest('hex')}]);
  assert.equal(reads.length,Math.ceil(bytes.length/(512*1024)));
  await byId(actions,'reveal').run();
  assert.deepEqual(tree,[['original-project','项目/原件.bin']]);
  rows[0].sessionIds = [];
  assert.throws(() => byId(actions,'reveal').run());
  info.version = 'v2';
  await assert.rejects(byId(actions,'open').run(), /changed|unavailable/);
  info.bytes = 0;
  const empty = await resolve();
  await byId(empty,'open').run();
  assert.equal(exports.at(-1).digest,createHash('sha256').digest('hex'));
  effects.reverse().forEach(dispose => dispose?.());
  assert.equal(providers.size,0);
  console.log('Android file actions passed: 33 MiB, empty file, version/ownership checks and plugin disposal.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
