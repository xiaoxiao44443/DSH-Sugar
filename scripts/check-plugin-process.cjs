'use strict';
const assert=require('node:assert/strict'),{Worker}=require('node:worker_threads'),{once}=require('node:events');
const {promisify}=require('node:util'),path=require('node:path');
async function main(){
 const assets=path.resolve(__dirname,'../app/src/main/assets/guest');
 const server=new Worker(path.join(__dirname,'plugin-process-fixture.cjs'),{workerData:{assets}});
 const [endpoint]=await once(server,'message');let current=endpoint;
 const proc=require(path.join(assets,'plugin-process.cjs')).create({current:()=>current});
 const transport=require(path.join(assets,'plugin-file-transport.cjs')).create();
 const results=[];
 async function check(name,fn){try{await fn();results.push({name,ok:true});console.log('PASS',name);}catch(e){results.push({name,ok:false,error:e.stack});console.log('FAIL',name,e.stack);}}
 try{
  await check('transport async raw image bytes',async()=>{const b=Buffer.from([137,80,78,71,0,255]);await transport.request(endpoint,{method:'writeFile',args:['/workspace/image.png',b],writable:true});assert.deepEqual(await transport.request(endpoint,{method:'readFile',args:['/workspace/image.png']}),b);});
  await check('transport sync Worker returns bytes and errors',()=>{assert.deepEqual(transport.requestSync(endpoint,{method:'readFile',args:['/workspace/image.png']}),Buffer.from([137,80,78,71,0,255]));assert.throws(()=>transport.requestSync(endpoint,{method:'writeFile',args:['/workspace/no','no'],writable:false}),{code:'EACCES'});});
  await check('spawn bootstrap and stdin separation',async()=>{const c=proc.spawn(process.execPath,['-e','let s="";process.stdin.on("data",x=>s+=x);process.stdin.on("end",()=>{process.stdout.write(s.toUpperCase());process.stderr.write("err");});']);const out=[],err=[];c.stdout.on('data',x=>out.push(x));c.stderr.on('data',x=>err.push(x));c.stdin.end('hello');assert.equal((await once(c,'close'))[0],0);assert.equal(Buffer.concat(out).toString(),'HELLO');assert.equal(Buffer.concat(err).toString(),'err');});
  await check('execFile promisify preserves stdout stderr',async()=>{const result=await promisify(proc.execFile)(process.execPath,['-e','process.stdout.write("out");process.stderr.write("err")']);assert.deepEqual(result,{stdout:'out',stderr:'err'});});
  await check('execFileSync output and exit status',()=>{assert.equal(proc.execFileSync(process.execPath,['-e','process.stdout.write("sync")'],{encoding:'utf8'}),'sync');assert.throws(()=>proc.execFileSync(process.execPath,['-e','process.exit(7)']),e=>e.status===7);});
  await check('exec and execSync shell mode',async()=>{assert.equal((await promisify(proc.exec)('echo shell-ok')).stdout.trim(),'shell-ok');assert.equal(proc.execSync('echo sync-shell',{encoding:'utf8'}).trim(),'sync-shell');});
  await check('spawnSync combines input after bootstrap',()=>{const result=proc.spawnSync(process.execPath,['-e','process.stdin.pipe(process.stdout)'],{input:'hello-sync',encoding:'utf8'});assert.equal(result.status,0);assert.equal(result.stdout,'hello-sync');});
  await check('async timeout terminates shim',async()=>{const t=Date.now();const c=proc.spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{timeout:400});c.stdin.end();await once(c,'close');assert(Date.now()-t<3500);});
  await check('spawnSync timeout returns',()=>{const t=Date.now();const result=proc.spawnSync(process.execPath,['-e','setInterval(()=>{},1000)'],{timeout:400});assert.equal(result.error?.code,'ETIMEDOUT');assert(Date.now()-t<3500);});
  await check('AbortSignal settles once and closes process',async()=>{const controller=new AbortController();let count=0;const result=new Promise(resolve=>{proc.execFile(process.execPath,['-e','setInterval(()=>{},1000)'],{signal:controller.signal},error=>{count++;resolve(error);});});setTimeout(()=>controller.abort(),250);const error=await result;assert.equal(error.name,'AbortError');await new Promise(r=>setTimeout(r,100));assert.equal(count,1);});
  await check('maxBuffer reports bounded output error',async()=>{const result=new Promise(resolve=>proc.execFile(process.execPath,['-e','process.stdout.write("a".repeat(200000));setInterval(()=>{},1000)'],{maxBuffer:1024},(error,stdout)=>resolve({error,stdout})));const value=await result;assert.equal(value.error.code,'ERR_CHILD_PROCESS_STDIO_MAXBUFFER');assert(value.stdout.length<=1024);});
  await check('bad file endpoint rejects then sync worker remains usable',async()=>{await assert.rejects(transport.request({...endpoint,url:'https://example.com'},{method:'readFile',args:['/workspace/image.png']}),/Invalid/);assert.throws(()=>transport.requestSync({...endpoint,url:'https://example.com'},{method:'readFile',args:['/workspace/image.png']}),/Invalid/);assert.equal(transport.requestSync(endpoint,{method:'readFile',args:['/workspace/image.png']}).length,6);});
  await check('missing and read-only workspace rejected',()=>{current=undefined;assert.throws(()=>proc.spawn(process.execPath,[]),{code:'EACCES'});current={...endpoint,writable:false};assert.throws(()=>proc.spawn(process.execPath,[]),{code:'EACCES'});current=endpoint;});
  await check('global programs retain a background execution channel',async()=>{current=undefined;const globalProc=require(path.join(assets,'plugin-process.cjs')).create({current:()=>current,globalEndpoint:()=>({...endpoint,global:true})});const value=await promisify(globalProc.execFile)(process.execPath,['-e','process.stdout.write("global")']);assert.equal(value.stdout,'global');assert.throws(()=>globalProc.spawn(process.execPath,[],{cwd:'/workspace'}),{code:'EACCES'});current=endpoint;});
  await check('ignored stdin keeps the public Node interface',async()=>{const child=proc.spawn(process.execPath,['-e',''],{stdio:'ignore'});assert.equal(child.stdin,null);assert.equal((await once(child,'close'))[0],0);});
  await check('fd and unsupported options fail closed',()=>{assert.throws(()=>proc.spawn(process.execPath,[],{detached:true}),{code:'ENOTSUP'});assert.throws(()=>new proc.ChildProcess(),{code:'ENOTSUP'});});
 }finally{await transport.close();server.postMessage('close');await once(server,'exit');}
 console.log(JSON.stringify({passed:results.filter(r=>r.ok).length,total:results.length,failures:results.filter(r=>!r.ok)},null,2));
 if(results.some(r=>!r.ok))process.exitCode=1;
}
main().catch(e=>{console.error(e);process.exitCode=1;});
