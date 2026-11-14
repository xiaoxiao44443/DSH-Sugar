'use strict';
// Node 24+. Run :app:prepareProjectFilesystemAssets first.
const assert = require('node:assert/strict');
const { randomBytes } = require('node:crypto');
const { spawn } = require('node:child_process');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { once } = require('node:events');
const { createInterface } = require('node:readline');

async function check(project, moduleFile) {
  const assets = path.join(project,'app/src/main/assets/guest');
  const dir = await fs.mkdtemp(path.join(os.tmpdir(),'sugar-worker-test-'));
  let child;
  const token = randomBytes(32).toString('hex');
  try {
    await fs.copyFile(path.join(assets,'project-worker.cjs'),path.join(dir,'project-worker.cjs'));
    await fs.copyFile(moduleFile,path.join(dir,'project-fs.mjs'));
    // Process transport is tested separately. This probe also checks that modules installed
    // after startup and the children they launch cannot inherit the management capability.
    await fs.writeFile(path.join(dir,'project-process-worker.cjs'),
      "if (process.env.SUGAR_PROJECT_TOKEN) throw Error('leaked worker credential');exports.hasActive=()=>!!global.testActive;");
    await fs.writeFile(path.join(dir,'clock.cjs'),`
      const now=Date.now, repeat=setInterval; let elapsed=0, sweep;
      Date.now=()=>now()+elapsed;
      global.setInterval=(fn,ms,...args)=>{if(ms===30000)sweep=fn;return repeat(fn,ms,...args)};
      const fs = require('node:fs/promises'), readdir = fs.readdir;
      let detached = false;
      fs.readdir = (name,...args)=> name === '/proc' ? Promise.resolve(
        ['self',String(process.pid),...(detached ? ['34567890'] : [])]) : readdir(name,...args);
      process.on('message', async msg=>{
        elapsed+=msg.advance||0;
        if ('active' in msg) global.testActive=msg.active;
        if ('detached' in msg) detached = msg.detached;
        if (msg.sweep) await sweep();
        process.send('done');
      });
    `);
    child = spawn(process.execPath,['--require','./clock.cjs','project-worker.cjs'],{
      cwd:dir,env:{...process.env,SUGAR_PROJECT_TOKEN:token},stdio:['ignore','pipe','inherit','ipc']
    });
    const signal = AbortSignal.timeout(20000);
    const lines = createInterface({input:child.stdout});
    const [status] = await Promise.race([
      once(lines,'line',{signal}),once(child,'exit',{signal}).then(() => { throw Error('worker startup failed'); })
    ]);
    assert.match(status,/^SUGAR_PROJECT_PORT=\d+$/);
    const url = 'http://127.0.0.1:' + status.split('=')[1];
    const rpc = (method,args,key=token) => fetch(url+'/fs/'+method,{
      signal,method:'POST',headers:{Authorization:'Bearer '+key,'Content-Type':'application/json'},body:JSON.stringify(args)
    });
    async function value(method,args) {
      const res = await rpc(method,args), data = await res.json();
      assert.equal(res.status,200,JSON.stringify(data.error));
      return data.result;
    }
    const fixture = path.join(dir,'fixture.txt');
    const target = await value('resolve',[fixture,{}]);
    assert.equal((await rpc('stat',[target],'invalid')).status,403);
    const text = 'Sucrune 中文 🪐\n';
    const attempts = await Promise.all([text,text].map(content => rpc('writeText',[target,content,{kind:'createIfAbsent'}])));
    assert.deepEqual(attempts.map(result => result.status).sort(),[200,422]);
    assert.equal(await value('readText',[target]),text);
    assert.equal(await (await rpc('streamText',[target])).text(),text);
    assert.equal((await value('listDir',[await value('resolve',[dir,{}])])).filter(x=>x.name==='fixture.txt').length,1);
    const bytes = Buffer.from(text);
    assert.deepEqual(Buffer.from(await (await rpc('readBytes',[target,null,bytes.length])).arrayBuffer()),bytes);
    assert.deepEqual(Buffer.from(await (await rpc('readByteRange',[target,{offset:0,length:3}])).arrayBuffer()),bytes.subarray(0,3));
    assert.equal((await (await rpc('readByteRange',[target,{offset:0,length:0}])).arrayBuffer()).byteLength,0);
    await fs.writeFile(fixture,Buffer.from([0,1,2]));
    const bad = await rpc('streamText',[target]);
    assert.equal((await bad.json()).error.code,'FS_NOT_TEXT');
    assert.equal(await value('resolveExecutable',[process.execPath,null]),process.execPath);
    for (const invalid of ['/tmp/.dsh-created-plugins/99','/workspace/.dsh-created-plugins/../other',
      '/workspace/.dsh-created-plugins/26cddaf5-44f9-4fe2-ad17-911cb4802a20/','/workspace/not-a-creator']) {
      assert.equal((await rpc('prepareCreator',[invalid])).status,422);
    }
    async function clock(message) {
      const ack=once(child,'message',{signal});
      child.send(message);
      await ack;
    }
    await clock({advance:31*60000,active:true,sweep:true});
    assert.equal((await rpc('stat',[target])).status,200);
    await clock({advance:31*60000,active:false,detached:true,sweep:true});
    assert.equal((await rpc('stat',[target])).status,200);
    await clock({advance:29*60000,detached:false});
    assert.equal((await fetch(url+'/activity',{method:'POST',headers:{Authorization:'Bearer '+token}})).status,204);
    await clock({advance:2*60000,sweep:true});
    assert.equal((await rpc('stat',[target])).status,200);
    await clock({advance:31*60000});
    const passive = await fetch(url+'/fs/stat',{method:'POST',signal,headers:{
      Authorization:'Bearer '+token,'X-Sugar-Watch':'1','Content-Type':'application/json'
    },body:JSON.stringify([target])});
    assert.equal(passive.status,200);
    await passive.arrayBuffer();
    await clock({sweep:true});
    await assert.rejects(()=>rpc('stat',[target]));
    console.log('Project worker: authentication, file I/O, executable and Creator paths, resident process liveness and passive watch idle timeout passed.');
  } finally {
    if (child && child.exitCode === null) {
      const exited = once(child,'exit');
      child.kill();
      await exited;
    }
    await fs.rm(dir,{recursive:true,force:true});
  }
}
const project = path.resolve(process.argv[2] || path.join(__dirname,'..'));
const moduleFile = process.argv[3] || path.join(project,'app/build/generated/projectFilesystemAssets/guest/project-fs.mjs');
check(project,moduleFile).catch(error=>{console.error(error);process.exitCode=1;});
