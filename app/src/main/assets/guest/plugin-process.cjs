'use strict';
const native=require('node:child_process');
const path=require('node:path');
const {promisify}=require('node:util');
const {fileURLToPath}=require('node:url');
exports.create=({current,globalEndpoint,shim=path.join(__dirname,'plugin-child.cjs'),runtime=process.execPath})=>{
  const unsupported=()=>Object.assign(Error('Plugin process requires a workspace; unsupported process options cannot run in the DSH host'),{code:'ENOTSUP'});
  function prepare(file,args,options={}){
    const ep=current()??globalEndpoint?.();if(!ep)throw Object.assign(Error('Plugin process has no associated workspace'),{code:'EACCES'});
    if(ep.writable===false)throw Object.assign(Error('Plugin processes are disabled in a read-only session'),{code:'EACCES'});
    if(options.detached||options.uid!==undefined||options.gid!==undefined||options.serialization||options.windowsVerbatimArguments)throw unsupported();
    const stdio=options.stdio??'pipe';
    const descriptors=Array.isArray(stdio)?stdio:[stdio,stdio,stdio];
    if(descriptors.length>3||descriptors.some(s=>!['pipe','ignore','inherit',null,undefined,0,1,2].includes(s)))throw unsupported();
    const base=ep.global?'/home/sugar':'/workspace';
    let cwd=options.cwd instanceof URL?fileURLToPath(options.cwd):options.cwd??base;
    cwd=path.posix.resolve(base,cwd);
    if(ep.global&&(cwd==='/workspace'||cwd.startsWith('/workspace/')))throw Object.assign(Error('Global plugin processes have no workspace'),{code:'EACCES'});
    if(typeof file!=='string'||!Array.isArray(args)||!args.every(a=>typeof a==='string'))throw new TypeError('Invalid plugin process arguments');
    // The worker owns PATH/HOME and the device grant. Never forward host secrets.
    const env={};
    for(const [key,value]of Object.entries(options.env??{})){
      if(/^(SUGAR_|SUCRUNE_|NODE_OPTIONS|DSH_)/.test(key))continue;
      if(value!==undefined)env[key]=String(value);
    }
    env.PWD=cwd;
    let argv=[file,...args];
    if(options.shell){const shell=typeof options.shell==='string'?options.shell:'/bin/sh';argv=[shell,'-c',[file,...args].join(' ')];}
    const spec={argv,cwd,env,graceMs:1000,stdio:{stdin:'pipe',stdout:'pipe',stderr:'pipe'}};
    const data=Buffer.from(JSON.stringify({endpoint:{url:ep.url,token:ep.token},spec}));
    const length=Buffer.alloc(4);length.writeUInt32BE(data.length);
    return {input:Buffer.concat([length,data]),descriptors};
  }
  function normal(args,options){return Array.isArray(args)?[args,options??{}]:[[],args??{}];}
  function spawn(file,args,options){
    [args,options]=normal(args,options);const spec=prepare(file,args,options);
    const child=native.spawn(runtime,[shim],{stdio:['pipe',spec.descriptors[1]??'pipe',spec.descriptors[2]??'pipe'],
      signal:options.signal,timeout:options.timeout,killSignal:options.killSignal,windowsHide:true});
    child.spawn=()=>{throw unsupported();};
    const stdin=child.stdin;stdin.on('error',()=>{});stdin.write(spec.input);
    if(spec.descriptors[0]==='ignore'){stdin.end();child.stdin=null;child.stdio[0]=null;}
    else if(spec.descriptors[0]==='inherit'||spec.descriptors[0]===0){process.stdin.pipe(stdin);child.stdin=null;child.stdio[0]=null;}
    return child;
  }
  function spawnSync(file,args,options){
    [args,options]=normal(args,options);const spec=prepare(file,args,options);
    if(spec.descriptors[0]==='inherit'||spec.descriptors[0]===0)throw unsupported();
    const input=options.input===undefined?Buffer.alloc(0):Buffer.from(options.input,options.encoding==='buffer'?undefined:options.encoding);
    return native.spawnSync(runtime,[shim],{input:Buffer.concat([spec.input,input]),encoding:options.encoding,
      stdio:['pipe',spec.descriptors[1]??'pipe',spec.descriptors[2]??'pipe'],timeout:options.timeout,
      maxBuffer:options.maxBuffer,killSignal:options.killSignal,windowsHide:true});
  }
  function execFile(file,args,options,callback){
    if(typeof args==='function'){callback=args;args=[];options={};}
    else if(typeof options==='function'){callback=options;options={};}
    [args,options]=normal(args,options);
    const child=spawn(file,args,{...options,stdio:'pipe'});child.stdin.end();
    const chunks=[[],[]],sizes=[0,0];let error;const limit=options.maxBuffer??1024*1024;
    [child.stdout,child.stderr].forEach((stream,i)=>stream.on('data',data=>{sizes[i]+=data.length;if(sizes[i]>limit){error=Object.assign(Error('Plugin process maxBuffer exceeded'),{code:'ERR_CHILD_PROCESS_STDIO_MAXBUFFER'});child.kill();}else chunks[i].push(data);}));
    child.once('error',e=>{error=e;});
    child.once('close',(code,signal)=>{
      const decode=i=>{const value=Buffer.concat(chunks[i]);return options.encoding==='buffer'?value:value.toString(options.encoding??'utf8');};
      if(code!==0&&!error)error=Object.assign(Error('Plugin process failed ('+file+')'),{code,signal});
      callback?.(error??null,decode(0),decode(1));
    });return child;
  }
  function exec(command,options,callback){if(typeof options==='function'){callback=options;options={};}return execFile(command,[],{...options,shell:options?.shell??true},callback);}
  function execFileSync(file,args,options){[args,options]=normal(args,options);const result=spawnSync(file,args,options);if(result.error||result.status!==0)throw Object.assign(result.error??Error('Plugin process failed ('+file+')'),result);return result.stdout;}
  function execSync(command,options){return execFileSync(command,[],{...options,shell:options?.shell??true});}
  for(const fn of [execFile,exec])Object.defineProperty(fn,promisify.custom,{value:(...args)=>{let child;const promise=new Promise((resolve,reject)=>{child=fn(...args,(error,stdout,stderr)=>error?reject(Object.assign(error,{stdout,stderr})):resolve({stdout,stderr}));});promise.child=child;return promise;}});
  function ChildProcess(){throw unsupported();}
  ChildProcess.prototype=native.ChildProcess.prototype;
  return {...native,ChildProcess,spawn,spawnSync,execFile,exec,execFileSync,execSync,fork(){throw unsupported();}};
};
