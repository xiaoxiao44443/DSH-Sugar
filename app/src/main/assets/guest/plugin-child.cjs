'use strict';

// The bootstrap is sent on stdin, not argv/environment. Capabilities must never
// be copied into a plugin's child or a process listing.
const http=require('node:http');
const {randomUUID}=require('node:crypto');
const {once}=require('node:events');
async function main() {
  const input=process.stdin[Symbol.asyncIterator](); let prefix=Buffer.alloc(0), size;
  while (size===undefined || prefix.length<size+4) {
    const next=await input.next(); if(next.done) throw Error('Missing plugin process bootstrap');
    prefix=Buffer.concat([prefix,next.value]);
    if(prefix.length>=4) size=prefix.readUInt32BE(0);
    if(size>1024*1024) throw Error('Plugin process bootstrap is too large');
  }
  const {endpoint,spec}=JSON.parse(prefix.subarray(4,size+4));
  const initial=prefix.subarray(size+4), id=randomUUID();
  const url=new URL(endpoint.url);
  if(url.protocol!=='http:' || url.hostname!=='127.0.0.1' || url.username || url.password) throw Error('Invalid project endpoint');
  function post(path,body) {
    const data=Buffer.from(JSON.stringify(body));
    return new Promise((resolve,reject)=>{
      const req=http.request(new URL(path,url),{method:'POST',headers:{Authorization:'Bearer '+endpoint.token,
        'Content-Type':'application/json','Content-Length':data.length}},res=>{
          req.setTimeout(0);
          if(res.statusCode!==200){res.resume();reject(Error('Project process request failed ('+res.statusCode+')'));} else resolve(res);
        });
      req.setTimeout(15000,()=>req.destroy(Error('Project process request timed out')));
      req.once('error',reject);req.end(data);
    });
  }
  async function op(body){const res=await post('/process/op',{id,...body});for await(const ignored of res){} }
  const output=await post('/process/start',{id,spec,terminal:false});
  let started=false, exited=false, code=1;
  function stop(){
    setTimeout(()=>{output.destroy();process.exit(143);},1500).unref();
    if(started&&!exited)op({op:'terminate'}).catch(()=>output.destroy());else output.destroy();
  }
  process.once('SIGTERM',stop);
  process.once('SIGINT',stop);
  async function send(bytes){for(let i=0;i<bytes.length;i+=65536)await op({op:'data',name:'stdin',data:bytes.subarray(i,i+65536).toString('base64')});}
  async function pump(){
    try{if(initial.length)await send(initial);for(;;){const next=await input.next();if(next.done)break;await send(next.value);}if(!exited)await op({op:'end',name:'stdin'});}
    catch(error){if(!exited){output.destroy(error);}}
  }
  let text='';
  for await(const bytes of output){
    text+=bytes.toString('utf8');if(text.length>2*1024*1024)throw Error('Project process frame is too large');
    for(let end;(end=text.indexOf('\n'))>=0;){
      const frame=JSON.parse(text.slice(0,end));text=text.slice(end+1);
      if(frame.type==='start'){started=true;void pump();}
      else if(frame.type==='data'){
        const dest=frame.name==='stdout'?process.stdout:frame.name==='stderr'?process.stderr:null;
        if(dest&&!dest.write(Buffer.from(frame.data,'base64')))await once(dest,'drain');
      }else if(frame.type==='exit'){exited=true;code=frame.outcome.exitCode??1;}
      else if(frame.type==='error')throw Error(frame.message);
      else if(frame.type==='range'){process.stdin.destroy();process.exitCode=code;return;}
    }
  }
  if(!exited)throw Error('Project process connection closed before exit');
  process.stdin.destroy();process.exitCode=code;
}
main().catch(error=>{process.stderr.write('Sugar plugin process: '+error.message+'\n');process.stdin.destroy();process.exitCode=1;});
