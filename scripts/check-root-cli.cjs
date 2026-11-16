'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const {spawn} = require('node:child_process');
const {once} = require('node:events');

function frame(type, data) {
  const header = Buffer.alloc(5);
  header[0] = type;
  header.writeUInt32BE(data.length,1);
  return Buffer.concat([header,data]);
}
function exit(code) {
  const data = Buffer.alloc(4); data.writeInt32BE(code);
  return frame(3,data);
}
async function main() {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(),'sugar-root-cli-'));
  const assets = path.join(__dirname,'../app/src/main/assets/guest');
  await fs.copyFile(path.join(assets,'sugar-adb.cjs'),path.join(directory,'adb'));
  await fs.copyFile(path.join(assets,'sugar-root.cjs'),path.join(directory,'sugar-root'));
  // Observe actual timer lifecycle without keeping the child alive. IPC only
  // emulates signal delivery on Windows, where kill() forcibly terminates Node.
  const guard = path.join(directory,'signal-guard.cjs');
  await fs.writeFile(guard,`
const assert = require('node:assert/strict');
const intervals = new Set(), schedule = global.setInterval, clear = global.clearInterval;
global.setInterval = (...args) => { const timer=schedule(...args); intervals.add(timer); return timer; };
global.clearInterval = timer => { intervals.delete(timer); return clear(timer); };
process.on('message',message=>{
  if(message.inspect) process.send({count:intervals.size,referenced:[...intervals].some(timer=>timer.hasRef())});
  else if(['SIGINT','SIGTERM','SIGHUP'].includes(message.signal)) process.emit(message.signal);
});
process.channel.unref();
process.once('beforeExit',()=>{
  assert.equal(intervals.size,0,'Root request left a wake timer after completion');
  for(const signal of ['SIGINT','SIGTERM','SIGHUP']) assert.equal(process.listenerCount(signal),0,'Root left a signal listener');
  if(process.connected) process.disconnect();
});
`);
  let received, handler;
  const server = net.createServer(socket => {
    socket.on('error',()=>{});
    let header = '';
    socket.on('data',data=>{
      header += data.toString();
      if (!header.endsWith('\n')) return;
      received = JSON.parse(header);
      try {
        assert.equal(received.token,'test-ticket');
        handler(socket,received);
      } catch (error) { socket.destroy(error); }
    });
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const environment = {...process.env,SUGAR_ADB_PORT:String(server.address().port),SUGAR_ADB_TOKEN:'test-ticket'};
  const clients = new Set();
  function call(args, env = environment) {
    const child = spawn(process.execPath,['--require',guard,path.join(directory,'sugar-root'),...args],{cwd:directory,env,stdio:['ignore','pipe','pipe','ipc']});
    clients.add(child);
    const stdout = [],stderr = [];
    child.stdout.on('data',part=>stdout.push(part));
    child.stderr.on('data',part=>stderr.push(part));
    const result = once(child,'close').then(([code,signal])=>{
      clients.delete(child);
      return {code,signal,stdout:Buffer.concat(stdout).toString(),stderr:Buffer.concat(stderr).toString()};
    });
    return {child,result};
  }
  try {
    assert.match((await call(['--help'],{}).result).stdout,/\/system\/bin\/sh/);
    assert.match((await call(['-c','id'],{}).result).stderr,/设备通道/);
    handler = socket => socket.end(Buffer.concat([frame(1,Buffer.from('{"enabled":true}\n')),exit(0)]));
    const status = await call(['--status']).result;
    assert.equal(status.code,0); assert.equal(received.op,'root-status');
    assert.equal(JSON.parse(status.stdout).enabled,true);
    handler = socket => socket.end(Buffer.concat([frame(1,Buffer.from('root stdout')),frame(2,Buffer.from('root stderr')),exit(23)]));
    const command = "printf '%s\\n' 'hello'\nid";
    const executed = await call(['-c',command]).result;
    assert.equal(received.op,'root-exec'); assert.equal(received.command,command);
    assert.equal(executed.code,23); assert.equal(executed.stdout,'root stdout'); assert.equal(executed.stderr,'root stderr');
    const script = path.join(directory,'phone.sh');
    await fs.writeFile(script,'\uFEFF#!/system/bin/sh\r\nid\r\n');
    let receivedScript;
    const scriptArrived = new Promise(resolve=>{receivedScript=resolve;});
    handler = (socket,request) => {
      receivedScript({socket,request});
    };
    const fromFile = call(['--script','phone.sh']);
    const snapshot = await scriptArrived;
    await fs.writeFile(script,'echo changed after approval started');
    assert.equal(snapshot.request.command,'#!/system/bin/sh\nid\n');
    snapshot.socket.end(exit(0));
    assert.equal((await fromFile.result).code,0);
    await fs.writeFile(script,'x'.repeat(16385));
    assert.match((await call(['--script','phone.sh']).result).stderr,/16 KiB/);
    await fs.writeFile(script,Buffer.from([0xff,0xfe]));
    assert.notEqual((await call(['--script','phone.sh']).result).code,0);
    assert.match((await call(['--script','.']).result).stderr,/普通文件/);
    for (const signal of ['SIGTERM','SIGINT','SIGHUP']) {
      let accepted;
      const connected = new Promise(resolve=>{accepted=resolve;});
      handler = socket => accepted(socket);
      const pending = call(['-c','sleep 30']);
      const peer = await connected;
      const closed = new Promise(resolve=>peer.once('close',resolve));
      const inspected = once(pending.child,'message');
      pending.child.send({inspect:true});
      assert.deepEqual((await inspected)[0],{count:1,referenced:false});
      if(process.platform === 'win32') pending.child.send({signal});
      else assert(pending.child.kill(signal));
      let timeout;
      const [result] = await Promise.race([
        Promise.all([pending.result,closed]),
        new Promise((_,reject)=>{timeout=setTimeout(()=>reject(Error(signal+' did not close the Root peer')),3000);})
      ]).finally(()=>clearTimeout(timeout));
      assert.equal(result.signal,null);
      assert.equal(result.code,1);
      assert.match(result.stderr,/^sugar-root: Root 操作已取消。\s*$/);
      assert.equal(result.stdout,'');
    }
    if (process.platform === 'win32') console.log('Windows: SIGTERM/SIGINT/SIGHUP handlers delivered through test IPC; real signal wake requires Linux/device validation.');
    console.log('PASS Root CLI status, multiline command, stdout/stderr/exit, immutable UTF-8/CRLF script, size, cancellation and wake-timer cleanup');
  } finally {
    for (const child of clients) child.kill();
    await new Promise(resolve=>server.close(resolve));
    assert.equal(path.dirname(directory),path.resolve(os.tmpdir()));
    assert(path.basename(directory).startsWith('sugar-root-cli-'));
    await fs.rm(directory,{recursive:true,force:true});
  }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
