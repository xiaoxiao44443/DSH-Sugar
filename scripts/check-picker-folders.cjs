'use strict';

// Exercise the signed Host handler through its real loopback API, with guest paths under one disposable directory.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const {createRequire} = require('node:module');
const {once} = require('node:events');

async function main() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(),'dsh-picker-'));
  const source = process.argv[2] || path.join(__dirname,'../app/src/main/assets/guest/picker-folders.cjs');
  const local = guest => {
    assert.ok(typeof guest === 'string' && guest.startsWith('/') && !guest.split('/').includes('..'));
    return path.join(root, guest.slice(1));
  };
  const mapped = new Set(['openSync','lstatSync','opendirSync','rmdirSync']);
  const filesystem = new Proxy(fs,{get(target,key) {
    return mapped.has(key) ? (name,...args) => target[key](local(name),...args) : target[key];
  }});
  const mod = {exports:{}};
  const requireHost = createRequire(path.resolve(source));
  vm.compileFunction(fs.readFileSync(source,'utf8'),['module','require'])(mod, name => name === 'node:fs' ? filesystem : requireHost(name));
  let records = [];
  let jobs = Promise.resolve();
  const registry = {list:() => records, enqueueOperation(fn) {
    const job = jobs.then(fn);
    jobs = job.catch(() => {});
    return job;
  }};
  let address;
  let server;
  const retained = new Map();
  try {
    fs.mkdirSync(local('/workspace'),{recursive:true});
    fs.mkdirSync(local('/home/sugar'),{recursive:true});
    server = await mod.exports.start(() => registry, async info => {
      if (info.op === 'pickerReady') address = info;
      if (info.op === 'inspectRetainedFolder') return retained.get(info.root) || {};
      if (info.op === 'forgetEmptyFolder') {
        assert.equal(retained.get(info.root).id,info.id);
        retained.delete(info.root);
      }
      return {};
    });
    assert.equal(address.op,'pickerReady');
    assert.match(address.token,/^[\w-]{43}$/);
    const call = async (op, name, ticket, auth = address.token, headers={}) => {
      const response = await fetch('http://127.0.0.1:' + address.port + '/folder',{
        method:'POST', headers:{Authorization:'Bearer '+auth,'Content-Type':'application/json',...headers},
        body:JSON.stringify({op,path:name,ticket})
      });
      return response.json();
    };
    const inspect = async name => {
      const result = await call('inspect',name);
      assert.equal(result.ok,true,result.error?.message);
      return result.result.ticket;
    };
    const sample = '/workspace/sample';
    fs.mkdirSync(local(sample));
    retained.set(sample,{id:'archived',empty:false});
    assert.match((await call('inspect',sample)).error.message,/还有内容/);
    retained.set(sample,{id:'archived',empty:true});
    const emptyArchive = await inspect(sample);
    assert.equal((await call('remove',sample,emptyArchive)).ok,true);
    assert.equal(retained.has(sample),false);
    fs.mkdirSync(local(sample));
    assert.equal((await call('inspect',sample,undefined,'incorrect')).ok,false);
    assert.equal((await call('inspect',sample,undefined,address.token,{Origin:'http://127.0.0.1'})).ok,false);
    for (const denied of ['/','/home','/home/sugar','/home/.cache','/home/sugar/.dsh','/workspace','/workspace/foo/../sample','/workspace/./sample','/workspace//sample',
      '/home/sugar/.dsh/check','/workspace/deepseek-harness','/workspace/deepseek-harness/default-workspace/x',
      '/workspace/phone-' + 'f'.repeat(64),'/workspace/local-' + 'f'.repeat(64) + '/child','/tmp/folder']) {
      assert.match((await call('inspect',denied)).error.message,/受保护/);
    }
    for (const bound of [sample,sample+'/nested','/workspace']) {
      records = [{path:bound}];
      assert.match((await call('inspect',sample)).error.message,/重叠/);
    }
    records = [];
    const homeFolder = '/home/新建项目';
    fs.mkdirSync(local(homeFolder));
    assert.deepEqual(await call('remove', homeFolder, await inspect(homeFolder)), {ok:true,result:{}});
    assert.equal(fs.existsSync(local(homeFolder)), false);
    const changed = await inspect(sample);
    fs.renameSync(local(sample),local('/workspace/old'));
    fs.mkdirSync(local(sample));
    assert.match((await call('remove',sample,changed)).error.message,/变化/);
    assert.ok(fs.statSync(local(sample)).isDirectory());
    const adopted = await inspect(sample);
    await registry.enqueueOperation(() => { records = [{path:sample}]; });
    assert.match((await call('remove',sample,adopted)).error.message,/重叠/);
    records = [];
    const beforeWrite = await inspect(sample);
    fs.writeFileSync(local(sample+'/.hidden'),'keep');
    assert.equal((await call('remove',sample,beforeWrite)).ok,false);
    assert.match((await call('inspect',sample)).error.message,/还有内容/);
    assert.equal(fs.readFileSync(local(sample+'/.hidden'),'utf8'),'keep');
    fs.mkdirSync(local('/home/sugar/保留'));
    fs.symlinkSync(local('/home/sugar'),local('/workspace/link'),process.platform === 'win32' ? 'junction' : 'dir');
    assert.match((await call('inspect','/workspace/link/保留')).error.message,/符号链接/);
    const unicode = '/home/sugar/临时 空目录';
    fs.mkdirSync(local(unicode));
    const mismatch = await inspect(unicode);
    assert.equal((await call('remove',sample,mismatch)).ok,false);
    assert.equal((await call('remove',unicode,mismatch)).ok,false);
    const confirmed = await inspect(unicode);
    assert.deepEqual(await call('remove',unicode,confirmed),{ok:true,result:{}});
    assert.equal(fs.existsSync(local(unicode)),false);
    fs.mkdirSync(local(unicode));
    assert.equal((await call('remove',unicode,confirmed)).ok,false);
    assert.ok(fs.existsSync(local(unicode)));
    console.log('Picker loopback checks passed for authentication, protected and registered paths, changed identity, hidden files, symlinks and one-use confirmation.');
  } finally {
    if (server) {
      const closed = once(server,'close');
      server.close();
      await closed;
    }
    fs.rmSync(root,{recursive:true,force:true});
  }
}
main().catch(error=>{ console.error(error); process.exitCode=1; });
