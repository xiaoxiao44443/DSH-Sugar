// A real CLI subprocess talks to a deliberately fragmented loopback peer.
'use strict';
const assert = require('node:assert/strict');
const net = require('node:net');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { once } = require('node:events');
const { spawn } = require('node:child_process');
const { randomBytes } = require('node:crypto');
const cli = path.resolve(__dirname, '../app/src/main/assets/guest/sugar-adb.cjs');

function frame(type, payload) {
  const head = Buffer.alloc(5);
  head[0] = type;
  head.writeUInt32BE(payload.length, 1);
  return Buffer.concat([head, payload]);
}
function exitFrame(code) {
  const status = Buffer.alloc(4);
  status.writeInt32BE(code);
  return frame(3, status);
}

async function test() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'sugar-adb-cli-'));
  const data = randomBytes(1_048_607);
  let peerError;
  const server = net.createServer(socket => {
    socket.on('error', () => {});
    (async () => {
      let header = Buffer.alloc(0), request, count = 0;
      for await (const chunk of socket) {
        let bytes = chunk;
        if (!request) {
          header = Buffer.concat([header, chunk]);
          const newline = header.indexOf(10);
          if (newline < 0) continue;
          request = JSON.parse(header.subarray(0, newline));
          assert.equal(request.token, 'test-only');
          bytes = header.subarray(newline + 1);
        }
        let reply;
        if (request.op === 'push') {
          assert(bytes.equals(data.subarray(count, count + bytes.length)));
          count += bytes.length;
          if (count < request.size) continue;
          assert.equal(count, data.length);
          reply = exitFrame(0);
        } else if (request.op === 'pull') {
          const first = request.path === '/empty' ? Buffer.alloc(0) : data;
          const last = request.path === '/broken' ? Buffer.from([3, 0]) : exitFrame(request.path === '/denied' ? 17 : 0);
          reply = Buffer.concat([frame(1, first), last]);
        } else if (request.op === 'get-state') {
          reply = Buffer.concat([frame(1, Buffer.from('device\n')), exitFrame(0)]);
        } else if (request.op === 'devices') {
          reply = Buffer.concat([frame(1, Buffer.from('List of devices attached\nsugar-local\tdevice\n\n')), exitFrame(0)]);
        } else if (request.op === 'exec-out' && request.command.includes('uiautomator dump')) {
          assert.match(request.command, /mktemp \/data\/local\/tmp\/sugar-ui-X+/);
          assert.match(request.command, /trap 'rm -f "\$ui_file"' 0/);
          assert.doesNotMatch(request.command, /\/sdcard|\/emulated\/0/);
          const mode = request.command.match(/(?:^|\n)uiautomator dump(?: (--verbose|--compressed))? "\$ui_file" >\/dev\/null/);
          assert(mode);
          reply = Buffer.concat([frame(1, Buffer.from('<hierarchy mode="' + (mode[1] || 'default') + '"/>')), exitFrame(0)]);
        } else if (request.command === "screencap '-p'") {
          reply = Buffer.concat([frame(1, data), exitFrame(0)]);
        } else if (request.command === 'uiautomator dump /data/local/tmp/explicit.xml') {
          reply = exitFrame(0);
        } else if (request.op === 'shell') {
          assert.equal(request.command, 'printf %s hi there | cat');
          reply = Buffer.concat([frame(2, Buffer.from('diagnostic\n')),frame(1, Buffer.from('text')),exitFrame(23)]);
        } else {
          assert.equal(request.command, "example 'a b' '' 'a'\\''b'");
          reply = Buffer.concat([frame(1, data), exitFrame(0)]);
        }
        // Split both headers and payloads; coalesce the last few frames in a single write.
        for (const part of [reply.subarray(0, 2), reply.subarray(2, 7), reply.subarray(7)]) {
          if (part.length) await new Promise((resolve,reject) => socket.write(part, e => e ? reject(e) : resolve()));
        }
        socket.end();
        break;
      }
    })().catch(e => { peerError = e; socket.destroy(); });
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const environment = {...process.env, SUGAR_ADB_PORT:String(server.address().port), SUGAR_ADB_TOKEN:'test-only'};
  async function call(args, env=environment) {
    const child = spawn(process.execPath, [cli,...args], {cwd:dir,env,stdio:['pipe','pipe','pipe']});
    const out = [], err = [];
    child.stdout.on('data', v=>out.push(v));
    child.stderr.on('data', v=>err.push(v));
    // Agent command runners may leave a non-TTY stdin pipe open during the entire call.
    const [code] = await once(child,'close');
    if (peerError) throw peerError;
    return {code,stdout:Buffer.concat(out),stderr:Buffer.concat(err).toString()};
  }
  try {
    assert.match((await call(['devices'])).stdout.toString(),/sugar-local\tdevice/);
    assert.equal((await call(['-s','sugar-local','get-state'])).stdout.toString(),'device\n');
    const shell = await call(['shell','-T','printf','%s','hi there','|','cat']);
    assert.equal(shell.code,23);
    assert.equal(shell.stdout.toString(),'text');
    assert.equal(shell.stderr,'diagnostic\n');
    const binary = await call(['exec-out','example','a b','','a\'b']);
    assert.equal(binary.code,0);
    assert(binary.stdout.equals(data));
    const screen = await call(['exec-out','screencap','-p']);
    assert(screen.stdout.equals(data));
    for (const [args,mode] of [
      [['ui-dump'],'default'],
      [['ui-dump','--verbose'],'--verbose'],
      [['shell','-T','--','uiautomator','dump'],'default'],
      [['shell','/system/bin/uiautomator dump --compressed'],'--compressed'],
      [['exec-out','uiautomator','dump','--compressed'],'--compressed'],
    ]) {
      const xml = await call(args);
      assert.equal(xml.code,0,xml.stderr);
      assert.equal(xml.stdout.toString(),'<hierarchy mode="' + mode + '"/>');
    }
    assert.equal((await call(['ui-dump','unexpected'])).code,1);
    assert.equal((await call(['shell','uiautomator','dump','/data/local/tmp/explicit.xml'])).code,0);
    await fs.writeFile(path.join(dir,'src'),data);
    assert.equal((await call(['push','src','/remote/file'])).code,0);
    for (const remote of ['/denied','/broken']) {
      const result = await call(['pull',remote,'src']);
      assert.notEqual(result.code,0);
      assert((await fs.readFile(path.join(dir,'src'))).equals(data));
      assert.deepEqual(await fs.readdir(dir),['src']);
    }
    assert.equal((await call(['pull','/new-file','.'])).code,0);
    assert((await fs.readFile(path.join(dir,'new-file'))).equals(data));
    assert.equal((await call(['pull','/empty'])).code,0);
    assert.equal((await fs.stat(path.join(dir,'empty'))).size,0);
    assert.equal((await call(['shell'])).code,1);
    assert.equal((await call(['pull','relative'])).code,1);
    assert.equal((await call(['push','absent','/file'])).code,1);
    assert.match((await call(['devices'],{...environment,SUGAR_ADB_TOKEN:''})).stderr,/android-adb/);
  } finally {
    await new Promise(resolve=>server.close(resolve));
    await fs.rm(dir,{recursive:true,force:true});
  }
  console.log('Guest ADB CLI streaming, framing, status, binary files and failed-pull rollback passed.');
}
test().catch(error => {console.error(error);process.exitCode=1;});
