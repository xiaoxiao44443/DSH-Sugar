'use strict';
// Exercise the trusted DSH router, subprocess framing and verifier over real loopback HTTP.
const assert = require('node:assert/strict');
const {createHash,createHmac,randomUUID} = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const {createRequire} = require('node:module');
const assets = path.join(__dirname,'../app/src/main/assets/guest');
const guestRequire = createRequire(path.join(assets,'project-routing.cjs'));
const source = name => fs.readFileSync(path.join(assets,name),'utf8');
const files = new Map();
const projects = [{id:'one',path:'/workspace/aaa'},{id:'two',path:'/workspace/bbb'}];
const mainToken = 'host-secret';
let registration, onStart, workerPort, legacyAdb;
const revokes = new Set();

function adapter(name) {
  if (name === 'node:module') return {registerHooks:()=>{}};
  if (name === './picker-folders.cjs') return {start:async()=>{}};
  if (name === 'node:fs') return {
    readFileSync(file) {
      if (file === '/opt/sugar/workspace-bridge.json') return '{"url":"http://127.0.0.1:9000/workspace"}';
      if (files.has(file)) return files.get(file);
      throw Object.assign(Error(),{code:'ENOENT'});
    },
    unlinkSync(){}, mkdirSync(){}, writeFileSync:(f,v)=>files.set(f,v),
    renameSync:(a,b)=>{files.set(b,files.get(a));files.delete(a);}, promises:{readdir:async()=>[]}
  };
  if (name === './project-process.cjs') {
    const mod = {};
    new Function('require','exports',source('project-process.cjs'))(s => s.includes('runner-launch-') ?
      {C:(_spec,child)=>child,E:()=>{}} : guestRequire(s), mod);
    return mod;
  }
  return guestRequire(name);
}
async function management(_url,options) {
  assert.equal(options.headers.Authorization,'Bearer ' + mainToken);
  const body = JSON.parse(options.body);
  let result;
  if (body.op === 'adbCliReady') {
    registration = body;
    result = {port:41234};
  } else if (body.op === 'adbCliRevoke') { revokes.add(body.token); result = {}; }
  else if (body.op === 'registry') result = {};
  else if (body.op === 'adbShell') { legacyAdb = body; result = {exitCode:0}; }
  else if (body.op === 'mobileTask') result = {ok:true,taskId:body.taskId,phase:'active'};
  else if (body.op === 'worker') result = {id:body.id,root:body.root,envId:'env-'+body.id,timeZone:'Asia/Kathmandu',
    token:'project-'+body.id,url:'http://127.0.0.1:'+workerPort};
  else throw Error('Unexpected host operation ' + body.op);
  return Response.json({ok:true,result});
}
async function check(env,needGuide,valid=true,mobile=false,ui=false) {
  const nonce = randomUUID();
  const text = JSON.stringify({token:env.SUGAR_ADB_TOKEN,needGuide,nonce,...(mobile ? {mobile:true} : {}),...(ui ? {ui:true} : {})});
  const sign = input => createHmac('sha256',registration.token).update(input).digest('hex');
  const reply = await fetch('http://127.0.0.1:'+registration.port+'/authorize',{
    method:'POST',headers:{'content-type':'application/json',authorization:'Sugar-HMAC ' +
      (valid ? sign('request\n'+text) : createHash('sha256').update('wrong').digest('hex'))},
    body:text,signal:AbortSignal.timeout(4000)
  });
  const result = await reply.text();
  if (!valid) {
    assert.equal(reply.status,403);
    assert.equal(reply.headers.get('X-Sugar-Validator-Proof'),null);
  } else {
    assert.equal(reply.status,200);
    assert.equal(reply.headers.get('X-Sugar-Validator-Proof'),sign('response\n'+nonce+'\n'+result));
  }
  return JSON.parse(result);
}
async function main() {
  const server = http.createServer(async (req,res) => {
    assert.equal(req.url,'/process/start');
    const chunks=[];
    for await (const chunk of req) chunks.push(chunk);
    const job=JSON.parse(Buffer.concat(chunks));
    assert.equal(req.headers.authorization,'Bearer project-one');
    res.writeHead(200,{'Content-Type':'application/x-ndjson'});
    res.write('{"type":"start","pid":123}\n');
    onStart({env:job.spec.env,exit() {
      res.end('{"type":"exit","outcome":{"code":0}}\n{"type":"range"}\n');
    },breakStream:()=>res.destroy()});
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  workerPort=server.address().port;
  try {
    new Function('require','process','fetch',source('project-routing.cjs'))(adapter,{
      argv:['node','/opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js'],env:{SUGAR_MANAGEMENT_TOKEN:mainToken}
    },management);
    const router=globalThis[Symbol.for('dsh.sugar.projects')];
    await router.setRegistry({list:()=>projects});
    const read=new WeakSet();
    const stop=router.registerAdbGuide(session=>read.has(session));
    const agent=id=>{
      const events=[];
      return {id,events,session:{header:{cwd:projects[0].path},get seq(){return events.length;},eventAt:i=>events[i]}};
    };
    const turn=(agent,text)=>{
      if(agent.events.length) agent.events.push({type:'turn/end'});
      agent.events.push({type:'turn/start'},{type:'user/message',data:{role:'user',source:{kind:'user'},content:[{type:'text',text}]}});
    };
    const first=agent('a'), other=agent('b');
    async function spawn(who,manual,terminal,scoped=true,env={}) {
      const started=new Promise((resolve,reject)=>{
        const timer=setTimeout(() => reject(Error('Project worker did not receive the spawn request')),5000);
        onStart=job=>{clearTimeout(timer);resolve(job);};
      });
      const run=()=>router.spawn(router.current(),{argv:['/bin/bash'],cwd:'/workspace',
        env:{SUGAR_ADB_TOKEN:'forged',SUGAR_ADB_PORT:'80',DSH_SESSION_ID:first.id,...env},stdio:{stdin:'ignore'}},terminal);
      const launched=scoped ? router.deviceProcess(who,manual,run) : router.within(who.session.header.cwd,run,who.id);
      const [handle,job]=await Promise.all([launched,started]);
      return {...job,done:terminal ? handle.done : handle.direct};
    }
    const firstProc=await spawn(first,false,false);
    assert.equal(firstProc.env.TZ,'Asia/Kathmandu');
    assert.match(firstProc.env.SUGAR_ADB_TOKEN,/^[A-Za-z0-9_-]{43}$/);
    assert.equal(firstProc.env.SUGAR_ADB_PORT,'41234');
    assert(!Object.values(firstProc.env).includes(mainToken));
    assert(!Object.values(firstProc.env).includes(registration.token));
    await check(firstProc.env,true,false);
    assert.equal((await check(firstProc.env,false)).result.id,'one');
    assert.match((await check(firstProc.env,true)).error.message,/android-adb/);
    read.add(first.session);
    assert.deepEqual((await check(firstProc.env,true)).result,{id:'one',root:projects[0].path});
    const command = 'monkey -p com.example.calculator 1';
    const cliRoute=async()=> (await check(firstProc.env,true,true,false,true)).result.route;
    // Both ADB entry points carry the turn identity. Screen choices come from the device task.
    for (const text of ['打开计算器','用虚拟屏打开计算器','用 ADB 打开计算器']) {
      const start=first.events.length ? first.events.length+1 : 0;
      turn(first,text);
      await router.adbShell(first,command);
      assert.equal(legacyAdb.conversation,first.id);
      assert.deepEqual(legacyAdb.route,{turn:start});
      assert.deepEqual(await cliRoute(),legacyAdb.route);
    }
    first.events.push({type:'turn/end'});
    assert.equal((await cliRoute()).turn,-1);
    const otherProc=await spawn(other,false,true,true,{TZ:'UTC'});
    assert.equal(otherProc.env.TZ,'UTC');
    const taskId=randomUUID();
    await router.mobileTask(first,'begin',{taskId,screen:'main'});
    const authorized=await spawn(first,false,false);
    assert.deepEqual((await check(authorized.env,false,true,true)).result,
      {id:'one',root:projects[0].path,conversation:'a',manual:false,taskId});
    assert.equal((await check(firstProc.env,false,true,true)).result.taskId,'');
    assert.equal((await check(otherProc.env,false,true,true)).result.conversation,'b');
    assert.equal((await check(otherProc.env,false,true,true)).result.taskId,'');
    authorized.exit(); await authorized.done;
    assert.match((await check(otherProc.env,true)).error.message,/android-adb/);
    const manual=await spawn(other,true,true);
    assert((await check(manual.env,true)).ok);
    const manuallySigned=(await check(manual.env,true,true,false,true)).result;
    assert.equal(manuallySigned.manual,true);
    assert.equal(manuallySigned.route,undefined);
    const unbound=await spawn(other,false,false,false);
    assert.equal(unbound.env.SUGAR_ADB_TOKEN,undefined);
    assert.equal(unbound.env.SUGAR_ADB_PORT,undefined);
    unbound.exit(); await unbound.done;
    firstProc.exit(); await firstProc.done;
    assert.equal((await check(firstProc.env,true)).ok,false);
    assert(revokes.has(firstProc.env.SUGAR_ADB_TOKEN));
    manual.breakStream(); await assert.rejects(manual.done);
    assert(!(await check(manual.env,true)).ok);
    assert(revokes.has(manual.env.SUGAR_ADB_TOKEN));
    assert(!revokes.has(otherProc.env.SUGAR_ADB_TOKEN));
    other.session.header.cwd=projects[1].path;
    assert(!(await check(otherProc.env,false)).ok);
    otherProc.exit(); await otherProc.done;
    stop();
    console.log('PASS ADB CLI HMAC, project/session binding, forged env, manual vs agent PTY, guide and revocation');
  } finally { server.closeAllConnections(); server.close(); }
}
main().catch(err=>{console.error(err);process.exitCode=1;});
