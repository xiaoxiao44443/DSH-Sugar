'use strict';
const {parentPort,workerData}=require('node:worker_threads');
const http=require('node:http'), cp=require('node:child_process'), fs=require('node:fs'), os=require('node:os'), path=require('node:path');
const {serialize,deserialize}=require('node:v8');
const root=fs.mkdtempSync(path.join(os.tmpdir(),'sugar-plugin-proc-review-'));
const backend=require(path.join(workerData.assets,'plugin-files-worker.cjs')).createBackend({root});
const live=new Map(),audit=[];
const server=http.createServer(async(req,res)=>{
 try {
  if(req.headers.authorization!=='Bearer review-only'){res.writeHead(403).end();return;}
  const chunks=[];for await(const c of req)chunks.push(c);const bytes=Buffer.concat(chunks);
  if(req.url==='/plugin-fs'){
   let out;try{out={result:await backend.execute(deserialize(bytes))};}catch(e){out={error:{message:e.message,code:e.code,path:e.path}};}
   res.writeHead(200).end(serialize(out));return;
  }
  const input=JSON.parse(bytes);
  if(req.url==='/process/start'){
   const {id,spec}=input; audit.push({kind:'start',spec});
   const argv=[...spec.argv];let command=argv.shift();
   if(command==='/bin/sh'&&process.platform==='win32'){command='powershell.exe';argv.splice(0,1,'-NoProfile','-Command');}
   const c=cp.spawn(command,argv,{cwd:root,env:{...process.env,...spec.env},stdio:'pipe',windowsHide:true});
   live.set(id,c);let ended=false;
   res.on('close',()=>{if(!ended)c.kill();});
   const frame=x=>{if(!res.destroyed)res.write(JSON.stringify(x)+'\n');};
   c.once('spawn',()=>{res.writeHead(200,{'Content-Type':'application/x-ndjson'});frame({type:'start',pid:c.pid});});
   c.stdout.on('data',d=>frame({type:'data',name:'stdout',data:d.toString('base64')}));
   c.stderr.on('data',d=>frame({type:'data',name:'stderr',data:d.toString('base64')}));
   c.once('error',e=>{res.writeHead(500).end(JSON.stringify({error:{message:e.message,code:e.code}}));});
   c.once('close',(exitCode,signal)=>{ended=true;live.delete(id);frame({type:'exit',outcome:{exitCode,signal}});frame({type:'range'});res.end();});
  } else if(req.url==='/process/op'){
   const c=live.get(input.id);audit.push({kind:'op',op:input.op});
   if(!c){res.writeHead(500).end();return;}
   if(input.op==='data')c.stdin.write(Buffer.from(input.data,'base64'));
   else if(input.op==='end')c.stdin.end();
   else if(input.op==='terminate'||input.op==='kill')c.kill();
   else throw Error('bad op');
   res.writeHead(200).end(JSON.stringify({result:null}));
  }else res.writeHead(404).end();
 }catch(e){res.writeHead(500).end(JSON.stringify({error:{message:e.message}}));}
});
server.listen(0,'127.0.0.1',()=>parentPort.postMessage({url:'http://127.0.0.1:'+server.address().port,token:'review-only',root}));
parentPort.on('message',async m=>{
 if(m==='audit')parentPort.postMessage({audit});
 if(m==='close'){for(const c of live.values())c.kill();server.close();server.closeAllConnections();await backend.close();if(!path.resolve(root).startsWith(path.resolve(os.tmpdir())+path.sep+'sugar-plugin-proc-review-'))throw Error('unsafe cleanup');fs.rmSync(root,{recursive:true,force:true});parentPort.close();}
});
