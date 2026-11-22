'use strict';

const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const os=require('node:os');
const {AsyncLocalStorage}=require('node:async_hooks');
const {Worker,isMainThread,parentPort,workerData}=require('node:worker_threads');
const {PassThrough}=require('node:stream');
const {EventEmitter}=require('node:events');
const {serialize}=require('node:v8');
const assets=process.env.SUGAR_PLUGIN_TEST_ASSETS||path.resolve(__dirname,'../app/src/main/assets/guest');

if(!isMainThread){
  const {createBackend}=require(path.join(workerData.assets,'plugin-files-worker.cjs'));
  const backends=Object.fromEntries(['a','b'].map(id=>[id,createBackend({root:path.join(workerData.fixture,id)})]));
  const server=require('node:http').createServer((req,res)=>{
    const backend=backends[req.headers.authorization?.replace('Bearer ','')];
    if(!backend){res.writeHead(401).end();return;}
    void backend.handle(req,res);
  });
  server.listen(0,'127.0.0.1',()=>parentPort.postMessage({url:'http://127.0.0.1:'+server.address().port+'/'}));
  parentPort.on('message',async message=>{
    if(message==='counts')parentPort.postMessage({counts:Object.values(backends).map(b=>b.handleCount)});
    if(message==='close'){
      server.closeAllConnections();await new Promise(resolve=>server.close(resolve));
      await Promise.all(Object.values(backends).map(backend=>backend.close()));
      parentPort.postMessage({closed:true});parentPort.close();
    }
  });
}else void main().catch(error=>{console.error(error);process.exitCode=1;});

async function main(){
  const base=path.resolve(os.tmpdir()),fixture=fs.mkdtempSync(path.join(base,'sugar-plugin-directories-'));
  const roots=Object.fromEntries(['a','b','outside','host'].map(id=>[id,path.join(fixture,id)]));
  for(const dir of Object.values(roots))fs.mkdirSync(dir);
  for(const id of ['a','b']){
    fs.mkdirSync(path.join(roots[id],'images/nested'),{recursive:true});
    fs.writeFileSync(path.join(roots[id],id+'.txt'),id);
    fs.writeFileSync(path.join(roots[id],'images/画.png'),'image');
    fs.writeFileSync(path.join(roots[id],'images/nested/deep.txt'),'deep');
  }
  fs.writeFileSync(path.join(roots.outside,'private.txt'),'private');
  const worker=new Worker(__filename,{workerData:{fixture,assets}});
  const message=()=>new Promise((resolve,reject)=>{worker.once('message',resolve);worker.once('error',reject);});
  const ready=await message();
  const context=new AsyncLocalStorage();
  const A={id:'a',envId:'env-a',url:ready.url,token:'a'},B={id:'b',envId:'env-b',url:ready.url,token:'b'};
  const transport=require(path.join(assets,'plugin-file-transport.cjs')).create();
  const {create}=require(path.join(assets,'plugin-files.cjs'));
  const view=create({current:()=>context.getStore(),request:transport.request,requestSync:transport.requestSync});
  const cb=(object,name,...args)=>new Promise((resolve,reject)=>object[name](...args,(err,value)=>err?reject(err):resolve(value)));
  const counts=async()=>{const reply=message();worker.postMessage('counts');return (await reply).counts;};
  const checkClosed=async dir=>{assert.throws(()=>dir.readSync(),{code:'ERR_DIR_CLOSED'});await assert.rejects(dir.read(),{code:'ERR_DIR_CLOSED'});};
  const collect=async dir=>{const values=[];for await(const entry of dir)values.push(entry);return values;};
  const syncCollect=dir=>{const values=[];for(let entry;(entry=dir.readSync())!==null;)values.push(entry);return values;};
  const virtualNames=entries=>entries.map(e=>path.posix.join(Buffer.isBuffer(e.parentPath)?e.parentPath.toString():e.parentPath,Buffer.isBuffer(e.name)?e.name.toString():e.name)).sort();
  let backend,limited,idle;
  try{
    assert.throws(()=>view.opendirSync('/workspace'),{code:'ENOENT'});
    await assert.rejects(view.promises.opendir('/workspace'),{code:'ENOENT'});
    await assert.rejects(cb(view,'opendir','/workspace'),{code:'ENOENT'});
    await Promise.all([A,B].map(ep=>context.run(ep,async()=>{
      const dir=view.opendirSync('/workspace');
      assert(dir instanceof view.Dir);assert(dir instanceof fs.Dir);assert.equal(dir.path,'/workspace');
      const values=syncCollect(dir);assert(values.every(entry=>entry instanceof fs.Dirent));
      assert.deepEqual(values.map(entry=>entry.name).sort(),[ep.id+'.txt','images']);
      assert(values.every(entry=>entry.parentPath==='/workspace'));
      dir.closeSync();await checkClosed(dir);assert.throws(()=>dir.closeSync(),{code:'ERR_DIR_CLOSED'});
      dir[Symbol.dispose]();await dir[Symbol.asyncDispose]();
      const callbackDir=await cb(view,'opendir','images',{bufferSize:1});
      assert.equal(callbackDir.path,'images');assert(await cb(callbackDir,'read'));
      await cb(callbackDir,'close');await checkClosed(callbackDir);
      const recursive=await view.promises.opendir('/workspace',{recursive:true,bufferSize:1});
      assert.deepEqual(virtualNames(await collect(recursive)),[
        '/workspace/'+ep.id+'.txt','/workspace/images','/workspace/images/nested',
        '/workspace/images/nested/deep.txt','/workspace/images/画.png'].sort());
      await checkClosed(recursive);
      const buffered=view.opendirSync(Buffer.from('/workspace/images'),{encoding:'buffer',recursive:true});
      assert(Buffer.isBuffer(buffered.path));
      const bufferEntries=syncCollect(buffered);assert(bufferEntries.every(e=>Buffer.isBuffer(e.name)&&Buffer.isBuffer(e.parentPath)));
      buffered.closeSync();
      const urlDir=await view.promises.opendir(new URL('file:///workspace/images'));
      assert.equal(urlDir.path,'/workspace/images');await urlDir.close();
      const hexDir=await view.promises.opendir('/workspace/images',{recursive:true,encoding:'hex'});
      const hexNames=(await collect(hexDir)).map(e=>Buffer.from(e.name,'hex').toString());
      assert.deepEqual(hexNames.sort(),['deep.txt','nested','画.png'].sort());
      const partial=await view.promises.opendir('/workspace',{recursive:true});
      for await(const entry of partial){assert(entry);break;}
      await checkClosed(partial);
      const queued=await view.promises.opendir('/workspace');
      const first=queued.read(),second=queued.read();
      assert.throws(()=>queued.readSync(),{code:'ERR_DIR_CONCURRENT_OPERATION'});
      assert.throws(()=>queued.closeSync(),{code:'ERR_DIR_CONCURRENT_OPERATION'});
      const close=queued.close();await assert.rejects(queued.read(),{code:'ERR_DIR_CLOSED'});
      assert.notEqual((await first).name,(await second).name);await close;
      await checkClosed(queued);
      await assert.rejects(view.promises.opendir('/workspace/missing'),{code:'ENOENT'});
      await assert.rejects(view.promises.opendir('/workspace/'+ep.id+'.txt'),{code:'ENOTDIR'});
      await assert.rejects(view.promises.opendir('/workspace',{bufferSize:0}),{code:'ERR_OUT_OF_RANGE'});
      await assert.rejects(view.promises.opendir('/workspace',{encoding:'not-an-encoding'}),{code:'ERR_INVALID_ARG_VALUE'});
      assert.throws(()=>view.opendir('/workspace'),{code:'ERR_INVALID_ARG_TYPE'});
    })));
    assert.deepEqual(await counts(),[0,0]);
    await context.run(A,async()=>{
      const defaults=view.opendirSync('/workspace',{bufferSize:undefined});defaults.closeSync();
      for(const [values,code] of [
        [[null,'32',{},false,1n],'ERR_INVALID_ARG_TYPE'],
        [[NaN,Infinity,1.1,0,-1,4294967296],'ERR_OUT_OF_RANGE'],
      ])for(const bufferSize of values){
        assert.throws(()=>view.opendirSync('/workspace',{bufferSize}),{code});
        await assert.rejects(view.promises.opendir('/workspace',{bufferSize}),{code});
        await assert.rejects(cb(view,'opendir','/workspace',{bufferSize}),{code});
      }
    });
    const held=await context.run(A,()=>view.promises.opendir('/workspace'));
    await context.run(B,()=>assert.rejects(held.read(),{code:'EACCES'}));
    await assert.rejects(held.close(),{code:'EACCES'});
    await context.run({...A,writable:false},async()=>{assert(await held.read());await held.close();});
    await context.run({...A,writable:false},async()=>{const dir=await view.promises.opendir('/workspace');assert(await dir.read());await dir.close();});
    const linkType=process.platform==='win32'?'junction':'dir';
    fs.symlinkSync(roots.outside,path.join(roots.a,'outside-link'),linkType);
    await context.run(A,async()=>{
      await assert.rejects(view.promises.opendir('/workspace/outside-link'),{code:'EACCES'});
      const dir=await view.promises.opendir('/workspace',{recursive:true});
      const entries=await collect(dir);assert(entries.some(e=>e.name==='outside-link'&&e.isSymbolicLink()));
      assert(!entries.some(e=>e.name==='private.txt'));
    });
    assert.deepEqual(await counts(),[0,0]);

    // Root traversal crosses into the caller's project, never the host workspace.
    fs.mkdirSync(path.join(roots.host,'workspace'));fs.mkdirSync(path.join(roots.host,'allowed'));
    fs.writeFileSync(path.join(roots.host,'workspace/host-only.txt'),'host');
    fs.writeFileSync(path.join(roots.host,'allowed/public.txt'),'public');
    const hostLocation=name=>name==='/'?roots.host:name.startsWith('/')&&!name.startsWith('/workspace')?path.join(roots.host,name):name;
    const hostFs={...fs,promises:{...fs.promises,opendir:(name,opts)=>fs.promises.opendir(hostLocation(name),opts)},
      opendirSync:(name,opts)=>fs.opendirSync(hostLocation(name),opts)};
    const rootView=create({current:()=>context.getStore(),request:transport.request,requestSync:transport.requestSync,hostFs});
    const hidden=await rootView.promises.opendir('/',{recursive:true});
    assert.deepEqual((await collect(hidden)).map(e=>e.name).sort(),['allowed','public.txt']);
    await context.run(A,async()=>{
      const visible=await rootView.promises.opendir('/',{recursive:true});
      const names=(await collect(visible)).map(e=>e.name);
      assert(names.includes('a.txt'));assert(!names.includes('b.txt'));assert(!names.includes('host-only.txt'));
    });

    const {createBackend}=require(path.join(assets,'plugin-files-worker.cjs'));
    backend=createBackend({root:roots.a});
    const opened=await backend.execute({method:'opendir',args:['/workspace',{bufferSize:1}]});
    assert.equal((await backend.execute({method:'readDir',handle:opened.handle,args:[]})).$type,'Dirent');
    await assert.rejects(backend.execute({method:'readFile',handle:opened.handle,args:[]}),{code:'EBADF'});
    await backend.execute({method:'closeDir',handle:opened.handle,args:[]});
    await assert.rejects(backend.execute({method:'readDir',handle:opened.handle,args:[]}),{code:'EBADF'});
    await assert.rejects(backend.execute({method:'opendir',args:['/workspace',{recursive:true}]}),{code:'EINVAL'});
    limited=createBackend({root:roots.a,maxHandles:1});
    const capped=await limited.execute({method:'opendir',args:['/workspace']});
    await assert.rejects(limited.execute({method:'open',args:['/workspace/a.txt','r']}),{code:'EMFILE'});
    await limited.execute({method:'closeDir',handle:capped.handle,args:[]});
    idle=createBackend({root:roots.a,idleMs:80});
    const expired=await idle.execute({method:'opendir',args:['/workspace']});
    const childDir=await idle.execute({method:'opendir',args:['/workspace/images']});
    for(let i=0;i<6;i++){
      await idle.execute({method:'readDir',handle:childDir.handle,args:[]});
      await new Promise(resolve=>setTimeout(resolve,20));
    }
    assert.equal(idle.handleCount,2,'active descendants keep recursive ancestor cursors alive');
    await idle.execute({method:'readDir',handle:expired.handle,args:[]});
    await new Promise(resolve=>setTimeout(resolve,200));assert.equal(idle.handleCount,0);
    await assert.rejects(idle.execute({method:'readDir',handle:expired.handle,args:[]}),{code:'EBADF'});
    const req=new PassThrough();req.method='POST';req.url='/plugin-fs';
    const res=new EventEmitter();res.destroyed=true;
    const pending=backend.handle(req,res);req.end(serialize({method:'opendir',args:['/workspace']}));await pending;
    assert.equal(backend.handleCount,0,'a canceled open cannot leak a directory handle');
    await backend.execute({method:'opendir',args:['/workspace']});await backend.close();assert.equal(backend.handleCount,0);
    console.log('PASS plugin directories: callback/Promise/sync/iterators, native streaming, recursive traversal, contexts, root filtering, symlinks, read-only, lifecycle/caps/idle/cancellation');
  }finally{
    await transport.close();await Promise.all([backend?.close(),limited?.close(),idle?.close()]);
    const closing=message();worker.postMessage('close');await closing;await worker.terminate();
    assert.equal(path.dirname(path.resolve(fixture)),base);assert(path.basename(fixture).startsWith('sugar-plugin-directories-'));
    fs.rmSync(fixture,{recursive:true,force:true});
  }
}
