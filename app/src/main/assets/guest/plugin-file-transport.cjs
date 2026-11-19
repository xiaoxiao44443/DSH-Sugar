'use strict';

// A separate I/O thread lets synchronous Node fs calls wait for the project
// worker without blocking the network client that will deliver their response.
const {Worker, isMainThread, parentPort, MessageChannel, receiveMessageOnPort} = require('node:worker_threads');
const {serialize, deserialize} = require('node:v8');
const LIMIT = 32 * 1024 * 1024;
const TIMEOUT = 30000;

function failure(error) {
  return {message: String(error?.message || 'Plugin file operation failed'),
    code: error?.code || 'EIO', syscall: error?.syscall, path: error?.path, dest: error?.dest};
}
function raise(detail) { return Object.assign(new Error(detail.message), detail); }
async function request(endpoint, operation, management=false) {
  const bytes = management ? Buffer.from(JSON.stringify(operation)) : serialize(operation);
  if (bytes.length > LIMIT) throw Object.assign(Error('Plugin file request is too large; use a stream'), {code:'EFBIG'});
  const url = new URL(endpoint.url);
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || url.username || url.password || !endpoint.token)
    throw Error('Invalid project file endpoint');
  const response = await fetch(management?url:new URL('/plugin-fs', url), {method:'POST', redirect:'error',
    headers:{Authorization:'Bearer '+endpoint.token, 'Content-Type':management?'application/json':'application/vnd.sugar.node-fs'},
    body:bytes, signal:AbortSignal.timeout(TIMEOUT)});
  let size=0; const chunks=[];
  for await (const chunk of response.body) {
    size += chunk.length;
    if (size > LIMIT) throw Object.assign(Error('Plugin file response is too large; use a stream'), {code:'EFBIG'});
    chunks.push(chunk);
  }
  if (response.status !== 200) throw Object.assign(Error('Project file service unavailable ('+response.status+')'), {code:'EIO'});
  const result=management?JSON.parse(Buffer.concat(chunks,size).toString('utf8')):deserialize(Buffer.concat(chunks,size));
  if (result.error) throw raise(result.error);
  return result.result;
}

if (!isMainThread && require('node:worker_threads').workerData?.sugarPluginFiles) {
  parentPort.on('message', async ({endpoint,operation,port,state,management}) => {
    let result;
    try { result={result:await request(endpoint,operation,management)}; }
    catch(error) { result={error:failure(error)}; }
    try { port.postMessage(serialize(result)); }
    finally { Atomics.store(state,0,1); Atomics.notify(state,0); port.close(); }
  });
} else {
  exports.create = () => {
    let worker;
    function requestSync(endpoint,operation,management=false) {
      if(!worker){
        const created=new Worker(__filename,{workerData:{sugarPluginFiles:true},execArgv:[]});
        // An unavailable worker must fail the operation, never crash the Host.
        created.on('error',()=>{if(worker===created)worker=undefined;});
        created.on('exit',()=>{if(worker===created)worker=undefined;});
        worker=created;
      }
      worker.unref();
      const {port1,port2}=new MessageChannel();
      const state=new Int32Array(new SharedArrayBuffer(4));
      try {
        worker.postMessage({endpoint:{url:endpoint.url,token:endpoint.token},operation,port:port2,state,management},[port2]);
        const deadline=Date.now()+TIMEOUT+5000;
        while (Date.now()<deadline) {
          const packet=receiveMessageOnPort(port1);
          if (packet) {
            const result=deserialize(packet.message);
            if (result.error) throw raise(result.error);
            return result.result;
          }
          Atomics.wait(state,0,0,Math.min(100,Math.max(1,deadline-Date.now())));
          if (Atomics.load(state,0)===1) Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0,1);
        }
        // Never retry a write after losing its response: it may have completed.
        throw Object.assign(Error('Project file operation timed out; its result is unknown'),{code:'ETIMEDOUT'});
      } finally { port1.close(); }
    }
    return {request,requestSync,managementSync:(endpoint,operation)=>requestSync(endpoint,operation,true),close(){const current=worker;worker=undefined;return current?.terminate();}};
  };
}
