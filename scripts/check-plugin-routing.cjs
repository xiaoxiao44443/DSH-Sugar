'use strict';
const {Worker,isMainThread,parentPort,workerData}=require('node:worker_threads');
const fs=require('node:fs');
const path=require('node:path');
const os=require('node:os');
const http=require('node:http');
const {once}=require('node:events');
const {registerHooks,createRequire}=require('node:module');
const {pathToFileURL}=require('node:url');
const {AsyncLocalStorage}=require('node:async_hooks');
const assert=require('node:assert/strict');
const assets=path.resolve(process.argv[2]||path.join(__dirname,'../app/src/main/assets/guest'));
if(!isMainThread){
  const backends=new Map();
  for(const id of ['a','b'])backends.set(id,require(path.join(workerData.assets,'plugin-files-worker.cjs')).createBackend({root:path.join(workerData.root,id)}));
  const server=http.createServer((req,res)=>{
    const id=req.headers.authorization?.slice(7),backend=backends.get(id);
    if(!backend){res.writeHead(403).end();return;}
    backend.handle(req,res);
  });
  server.listen(0,'127.0.0.1',()=>parentPort.postMessage('http://127.0.0.1:'+server.address().port));
  parentPort.once('message',async()=>{server.close();server.closeAllConnections();await Promise.all([...backends.values()].map(b=>b.close()));parentPort.close();});
}else{
  async function main(){
    const root=fs.mkdtempSync(path.join(os.tmpdir(),'sugar-plugin-routing-'));
    for(const dir of ['a','b','plugins','shared'])fs.mkdirSync(path.join(root,dir));
    // A cached shared CJS helper already captured raw fs before plugin hooks.
    const sharedCjs=path.join(root,'shared','cached.cjs');
    fs.writeFileSync(sharedCjs,"module.exports=require('node:fs');");
    const hostSharedCjs=require(sharedCjs);
    fs.writeFileSync(path.join(root,'shared','cached.mjs'),"import fs from 'node:fs';export default fs;");
    fs.writeFileSync(path.join(root,'shared','fresh.cjs'),"module.exports=require('node:fs');");
    const server=new Worker(__filename,{workerData:{root,assets}});
    const [url]=await once(server,'message');
    const transport=require(path.join(assets,'plugin-file-transport.cjs')).create();
    const active=new AsyncLocalStorage();
    const pluginRoot=path.join(root,'plugins').replaceAll('\\','/');
    const modules=require(path.join(assets,'plugin-module-view.cjs')).create({current:()=>active.getStore(),transport,pluginRoots:[pluginRoot]});
    const rawRead=fs.readFileSync;
    const hook=registerHooks({resolve:modules.resolve,load:modules.load});
    try{
      fs.writeFileSync(path.join(root,'plugins','index.mjs'),`
        import fs, {writeFileSync, readFileSync} from 'node:fs';
        import {readFile,writeFile,mkdir} from 'node:fs/promises';
        import child from 'node:child_process';
        import helper from './helper.cjs';
        export {fs,writeFileSync,readFileSync,readFile,writeFile,mkdir,child,helper};
      `);
      fs.writeFileSync(path.join(root,'plugins','helper.cjs'),"module.exports=require('fs');");
      fs.writeFileSync(path.join(root,'plugins','cached-cjs.mjs'),"export {default} from '../shared/cached.cjs';");
      fs.writeFileSync(path.join(root,'plugins','cached-esm.mjs'),"export {default} from '../shared/cached.mjs';");
      fs.writeFileSync(path.join(root,'plugins','fresh.mjs'),"export {default} from '../shared/fresh.cjs';");
      const hostSharedEsm=await import(pathToFileURL(path.join(root,'shared','cached.mjs')).href);
      assert.equal(hostSharedCjs,fs);assert.equal(hostSharedEsm.default,fs);
      await assert.rejects(import(pathToFileURL(path.join(root,'plugins','cached-cjs.mjs')).href),{code:'ENOTSUP'});
      await assert.rejects(import(pathToFileURL(path.join(root,'plugins','cached-esm.mjs')).href),{code:'ENOTSUP'});
      const fresh=await import(pathToFileURL(path.join(root,'plugins','fresh.mjs')).href);
      const plugin=await import(pathToFileURL(path.join(root,'plugins','index.mjs')).href);
      assert.equal(fs.readFileSync,rawRead,'Host fs must remain untouched');
      assert.notEqual(plugin.fs,fs);assert.equal(plugin.helper,plugin.fs);
      assert.equal(fresh.default,plugin.fs,'An uncached external dependency still receives the plugin view');
      const sdk={url:pathToFileURL(path.join(root,'node_modules','cordis','index.js')).href};
      assert.equal(modules.resolve('cordis',{parentURL:pathToFileURL(path.join(root,'plugins','index.mjs')).href},()=>sdk),sdk,'Cordis SDK host services remain importable');
      assert.equal(plugin.fs.existsSync('/workspace'),false);
      assert.throws(()=>plugin.writeFileSync('/workspace/no.png','x'),{code:'EACCES'});
      await assert.rejects(plugin.mkdir('/workspace',{recursive:true}),{code:'EACCES'});
      assert.throws(()=>plugin.child.spawn('node',[]),{code:'EACCES'});
      const a={id:'a',envId:'a',url,token:'a',writable:true},b={...a,id:'b',envId:'b',token:'b'};
      const image=Buffer.from([137,80,78,71,13,10,26,10,0,255,1,128]);
      let background;
      await active.run(a,async()=>{
        plugin.writeFileSync('/workspace/image.png',image);
        assert(Buffer.isBuffer(plugin.readFileSync('/workspace/image.png')));
        const handle=await plugin.fs.promises.open('/workspace/handle.png','w+');
        await handle.write(image);await handle.close();
        const stream=plugin.fs.createWriteStream('/workspace/stream.png');stream.end(image);await once(stream,'finish');
        background=new Promise(resolve=>setTimeout(()=>{plugin.writeFileSync('/workspace/background.txt','a');resolve();},25));
      });
      await Promise.all([
        active.run(a,()=>plugin.writeFile('/workspace/concurrent.txt','a')),
        active.run(b,()=>plugin.helper.promises.writeFile('/workspace/concurrent.txt','b')),
        active.run(b,async()=>{assert.equal(plugin.fs.existsSync('/workspace/image.png'),false);await background;})
      ]);
      for(const file of ['image.png','handle.png','stream.png'])assert.deepEqual(fs.readFileSync(path.join(root,'a',file)),image);
      assert.equal(fs.readFileSync(path.join(root,'a','concurrent.txt'),'utf8'),'a');
      assert.equal(fs.readFileSync(path.join(root,'b','concurrent.txt'),'utf8'),'b');
      assert.equal(fs.readFileSync(path.join(root,'a','background.txt'),'utf8'),'a');
      assert.equal(fs.existsSync(path.join(root,'b','background.txt')),false);
      await active.run({...a,writable:false},async()=>{
        assert.deepEqual(await plugin.readFile('/workspace/image.png'),image);
        assert.throws(()=>plugin.writeFileSync('/workspace/readonly.png',image),{code:'EACCES'});
      });
      console.log('Plugin routing passed: real ESM/CJS imports, raw PNG, sync/async/stream/handles, concurrent workspaces, background identity, no workspace, read-only, Host fs unchanged; cached non-SDK shared helpers rejected, private/fresh helpers and SDK allowed.');
    }finally{
      hook.deregister();await transport.close();server.postMessage('close');await once(server,'exit');
      const resolved=path.resolve(root);if(!resolved.startsWith(path.resolve(os.tmpdir())+path.sep+'sugar-plugin-routing-'))throw Error('Unsafe fixture cleanup');
      fs.rmSync(resolved,{recursive:true,force:true});
    }
  }
  main().catch(error=>{console.error(error);process.exitCode=1;});
}
