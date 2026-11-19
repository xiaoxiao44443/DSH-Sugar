'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const v8=require('node:v8');
const {AsyncLocalStorage}=require('node:async_hooks');
const {pipeline}=require('node:stream/promises');
const {Readable}=require('node:stream');
const assets=process.env.SUGAR_PLUGIN_TEST_ASSETS || path.resolve(__dirname,'../app/src/main/assets/guest');
const {create}=require(path.join(assets,'plugin-files.cjs'));
const {createBackend}=require(path.join(assets,'plugin-files-worker.cjs'));
const root=fs.mkdtempSync(path.join(os.tmpdir(),'sugar-plugin-files-'));
const dirs={a:path.join(root,'a'),b:path.join(root,'b')};
Object.values(dirs).forEach(dir=>fs.mkdirSync(dir));
const context=new AsyncLocalStorage();
const A={id:'a',envId:'env-a'},B={id:'b',envId:'env-b'};
const descriptors=new Map();let next=1;
function target(ep,name){assert(name==='/workspace'||name.startsWith('/workspace/'));return path.join(dirs[ep.id],name.slice('/workspace'.length));}
function serialize(value){return v8.deserialize(v8.serialize(value));}
function typed(value){
  if(value instanceof fs.Stats||value&&typeof value.isFile==='function') return {$type: value instanceof fs.Stats?'Stats':'Dirent',kind:value.isFile()?'file':value.isDirectory()?'directory':value.isSymbolicLink()?'symlink':'other',...Object.fromEntries(Object.keys(value).map(key=>[key,value[key]]))};
  return Array.isArray(value)?value.map(typed):value;
}
function requestSync(ep,input){
  input=serialize(input);
  const {method,handle}=input;const args=input.args.slice();let result;
  if(handle){
    const record=descriptors.get(handle);assert(record);assert.equal(record.ep.id,ep.id);const fd=record.fd;
    if(method==='read'){const buffer=Buffer.alloc(args[0]);const bytesRead=fs.readSync(fd,buffer,0,buffer.length,args[1]);return{bytesRead,buffer:buffer.subarray(0,bytesRead)};}
    if(method==='write')return{bytesWritten:fs.writeSync(fd,args[0],0,args[0].length,args[1])};
    if(method==='readv'){const buffers=args[0].map(size=>Buffer.alloc(size));return{bytesRead:fs.readvSync(fd,buffers,args[1]),buffers};}
    if(method==='writev')return{bytesWritten:fs.writevSync(fd,args[0],args[1])};
    const name=({stat:'fstat',chmod:'fchmod',chown:'fchown',utimes:'futimes',truncate:'ftruncate',sync:'fsync',datasync:'fdatasync'})[method]||method;
    result=fs[name+'Sync'](fd,...args);if(method==='close')descriptors.delete(handle);
  }else{
    const index=method==='symlink'?1:0;args[index]=target(ep,args[index]);
    if(['rename','link','copyFile','cp'].includes(method))args[1]=target(ep,args[1]);
    if(method==='open'){const fd=fs.openSync(...args),handle=String(next++);descriptors.set(handle,{fd,ep});return{handle};}
    result=fs[method+'Sync'](...args);
  }
  return serialize(typed(result));
}
const facade=create({current:()=>context.getStore(),request:async(ep,input)=>{await new Promise(resolve=>setTimeout(resolve,1));return requestSync(ep,input);},requestSync});
function callback(name,...args){return new Promise((resolve,reject)=>facade[name](...args,(err,...values)=>err?reject(err):resolve(values)));}
async function main(){
  assert.equal(facade.existsSync('/workspace'),false);
  assert.throws(()=>facade.mkdirSync('/workspace'),{code:'EACCES'});
  await assert.rejects(facade.promises.writeFile('/workspace/no.png','x'),{code:'EACCES'});
  await assert.rejects(facade.promises.readFile('/workspace/no.png'),{code:'ENOENT'});
  await Promise.all([A,B].map(ep=>context.run(ep,async()=>{
    await facade.promises.mkdir('/workspace/images',{recursive:true});
    const image=Buffer.from([0,1,128,255,17,22]);
    await facade.promises.writeFile('/workspace/images/test.png',image);
    await facade.promises.writeFile('owner.txt',ep.id);
    assert.deepEqual(await facade.promises.readFile(Buffer.from('/workspace/images/test.png')),image);
    assert.deepEqual(await facade.promises.readFile(new URL('file:///workspace/images/test.png')),image);
    assert.equal(facade.readFileSync('owner.txt','utf8'),ep.id);
    assert.equal(facade.statSync('/workspace/images/test.png').isFile(),true);
    assert.equal(facade.statSync('/workspace/images/test.png').mtime instanceof Date,true);
    assert.equal((await facade.promises.readdir('/workspace/images',{withFileTypes:true}))[0].isFile(),true);
    await callback('appendFile','owner.txt','!');
    assert.equal((await callback('readFile','owner.txt','utf8'))[0],ep.id+'!');
    await assert.rejects(facade.promises.rename('/workspace/owner.txt',path.join(root,'renamed')),{code:'EXDEV'});
    const handle=await facade.promises.open('/workspace/handle.bin','w+');
    assert.equal((await handle.write('abc',0,'utf8')).bytesWritten,3);
    assert.equal((await handle.write(Buffer.from('de'),0,2,3)).bytesWritten,2);
    const bytes=Buffer.alloc(5);assert.equal((await handle.read(bytes,0,5,0)).bytesRead,5);assert.equal(bytes.toString(),'abcde');
    assert.equal((await handle.stat()).size,5);await handle.truncate(3);assert.equal((await handle.stat()).size,3);
    await handle.close();await handle.close();
    const fd=facade.openSync('/workspace/sync.bin','w+');
    assert.equal(facade.writevSync(fd,[Buffer.from('ab'),Buffer.from('cd')],0),4);
    const parts=[Buffer.alloc(2),Buffer.alloc(2)];assert.equal(facade.readvSync(fd,parts,0),4);assert.equal(Buffer.concat(parts).toString(),'abcd');
    const tail=[Buffer.alloc(3,120),Buffer.alloc(3,120)];assert.equal(facade.readvSync(fd,tail,0),4);assert.equal(Buffer.concat(tail).toString(),'abcdxx');
    facade.ftruncateSync(fd,2);assert.equal(facade.fstatSync(fd).size,2);facade.closeSync(fd);
    const output=facade.createWriteStream('/workspace/stream.bin');await pipeline(Readable.from([Buffer.from('chunk1'),Buffer.from('chunk2')]),output);
    const chunks=[];for await(const chunk of facade.createReadStream('/workspace/stream.bin'))chunks.push(chunk);
    assert.equal(Buffer.concat(chunks).toString(),'chunk1chunk2');
    const host=path.join(root,ep.id+'.png');await facade.promises.copyFile('/workspace/images/test.png',host);
    assert.deepEqual(fs.readFileSync(host),image);await facade.promises.copyFile(host,'/workspace/from-host.png',fs.constants.COPYFILE_EXCL);
    await assert.rejects(facade.promises.copyFile(host,'/workspace/from-host.png',fs.constants.COPYFILE_EXCL),{code:'EEXIST'});
    facade.copyFileSync(host,'/workspace/from-host-sync.png');assert.deepEqual(facade.readFileSync('/workspace/from-host-sync.png'),image);
    assert.throws(()=>facade.watch('/workspace'),{code:'ENOTSUP'});
  })));
  assert.equal(fs.readFileSync(path.join(dirs.a,'owner.txt'),'utf8'),'a!');
  assert.equal(fs.readFileSync(path.join(dirs.b,'owner.txt'),'utf8'),'b!');
  const held=await context.run(A,()=>facade.promises.open('/workspace/owner.txt','r'));
  await context.run(B,()=>assert.rejects(held.readFile(),{code:'EACCES'}));
  await assert.rejects(held.readFile(),{code:'EACCES'});
  await context.run(A,()=>held.close());
  await context.run({...A,writable:false},async()=>{
    assert.equal(await facade.promises.readFile('owner.txt','utf8'),'a!');
    await assert.rejects(facade.promises.writeFile('owner.txt','blocked'),{code:'EACCES'});
    assert.throws(()=>facade.openSync('/workspace/new','w'),{code:'EACCES'});
    assert.throws(()=>facade.accessSync('/workspace',fs.constants.W_OK),{code:'EACCES'});
  });
  assert.equal(descriptors.size,0);
  console.log('plugin fs: callbacks/promises/sync, images, streams, handles, concurrent workspaces, no-context denial and cross-copy passed');
  const backends={a:createBackend({root:dirs.a}),b:createBackend({root:dirs.b})};
  const view=create({current:()=>context.getStore(),request:(ep,input)=>backends[ep.id].execute(serialize(input)),requestSync:()=>{throw Error('unexpected synchronous call in backend integration');}});
  try {
    await Promise.all([A,B].map(ep=>context.run({...ep,writable:true},async()=>{
      const data=Buffer.from([137,80,78,71,0,255,128,42]);
      await view.promises.writeFile('/workspace/generated.png',data);
      assert.deepEqual(await view.promises.readFile('/workspace/generated.png'),data);
      assert.equal(await view.promises.realpath('/workspace/generated.png'),'/workspace/generated.png');
      const info=await view.promises.stat('/workspace/generated.png');assert.equal(info.isFile(),true);assert.equal(info instanceof fs.Stats,true);assert.equal(info.mtime instanceof Date,true);
      assert.equal(typeof(await view.promises.stat('/workspace/generated.png',{bigint:true})).size,'bigint');
      const entries=await view.promises.readdir('/workspace',{withFileTypes:true});assert(entries.some(item=>item.name==='generated.png'&&item.isFile()));
      const handle=await view.promises.open('/workspace/actual-handle.bin','w+');
      await handle.writev([Buffer.from('12'),Buffer.from('34')],0);
      const target=[Buffer.alloc(3,120),Buffer.alloc(3,120)];assert.equal((await handle.readv(target,0)).bytesRead,4);assert.equal(Buffer.concat(target).toString(),'1234xx');
      await handle.close();
      const streamHandle=await view.promises.open('/workspace/actual-stream.bin','w');await pipeline(Readable.from([data,data]),streamHandle.createWriteStream());assert.equal(streamHandle.fd,-1);await streamHandle.close();
      const result=[];for await(const part of view.createReadStream('/workspace/actual-stream.bin'))result.push(part);assert.deepEqual(Buffer.concat(result),Buffer.concat([data,data]));
      await view.promises.copyFile('/workspace/generated.png',path.join(root,ep.id+'-actual.png'));
      await view.promises.copyFile(path.join(root,ep.id+'-actual.png'),'/workspace/copied.png',fs.constants.COPYFILE_EXCL);
      await assert.rejects(view.promises.copyFile(path.join(root,ep.id+'-actual.png'),'/workspace/copied.png',fs.constants.COPYFILE_EXCL),{code:'EEXIST'});
      await view.promises.mkdir('/workspace/deep/nested',{recursive:true});await view.promises.writeFile('/workspace/deep/nested/a','value');await view.promises.rename('/workspace/deep/nested/a','/workspace/deep/nested/b');await view.promises.rm('/workspace/deep',{recursive:true});
      const writableHandle=await view.promises.open('/workspace/actual-handle.bin','r+');
      await context.run({...ep,writable:false},()=>assert.rejects(writableHandle.write('blocked'),{code:'EACCES'}));
      await writableHandle.close();
    })));
    assert.equal(backends.a.handleCount,0);assert.equal(backends.b.handleCount,0);
    console.log('plugin fs: integrated project worker passed binary images, bigint stats, streams, FileHandle lifecycle, copy exclusivity, mutations and read-only policy');
  } finally {await Promise.all(Object.values(backends).map(backend=>backend.close()));}
}
main().finally(()=>{
  for(const {fd} of descriptors.values())try{fs.closeSync(fd);}catch{}
  fs.rmSync(root,{recursive:true,force:true});
}).catch(error=>{console.error(error);process.exitCode=1;});
