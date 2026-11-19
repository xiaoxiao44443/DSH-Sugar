'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const http = require('node:http');
const { PassThrough } = require('node:stream');
const { EventEmitter } = require('node:events');
const { serialize, deserialize } = require('node:v8');
const { createBackend, MAX_BYTES } = require('../app/src/main/assets/guest/plugin-files-worker.cjs');

(async () => {
  const base = path.resolve(os.tmpdir());
  const fixture = await fs.mkdtemp(path.join(base, 'sugar-plugin-files-worker-'));
  const first = path.join(fixture, 'first'), second = path.join(fixture, 'second');
  const outside = path.join(fixture, 'outside');
  await Promise.all([first, second, outside].map(dir => fs.mkdir(dir)));
  const backend = createBackend({ root: first });
  const peer = createBackend({ root: second });
  const call = (method, ...args) => backend.execute({ method, args, writable: true });
  const readonly = (method, ...args) => backend.execute({ method, args, writable: false });
  const handle = (id, method, args = [], writable = true) => backend.execute({ method, args, handle: id, writable });
  const denied = (promise, code = 'EACCES') => assert.rejects(promise, error => error.code === code);
  const checks = [];
  let server, limited, idle;
  try {
    const image = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10, 0, 255, 13, 128, 0]);
    await call('mkdir', '/workspace/images', { recursive: true });
    await call('writeFile', '/workspace/images/generated.png', image);
    assert.deepEqual(await call('readFile', '/workspace/images/generated.png'), image);
    assert.deepEqual(await fs.readFile(path.join(first, 'images/generated.png')), image);
    await peer.execute({ method: 'writeFile', args: ['/workspace/same.txt', 'B'], writable: true });
    await call('writeFile', '/workspace/same.txt', 'A');
    assert.equal(await call('readFile', '/workspace/same.txt', 'utf8'), 'A');
    assert.equal(await peer.execute({ method: 'readFile', args: ['/workspace/same.txt', 'utf8'] }), 'B');
    checks.push('binary image and separate workspaces');

    const meta = await call('stat', '/workspace/images/generated.png');
    assert.equal(meta.$type, 'Stats'); assert.equal(meta.kind, 'file'); assert.equal(meta.size, image.length);
    assert(meta.mtime instanceof Date);
    const bigMeta = deserialize(serialize(await call('stat', '/workspace/images/generated.png', { bigint: true })));
    assert.equal(typeof bigMeta.size, 'bigint');
    const entries = await call('readdir', '/workspace/images', { withFileTypes: true });
    assert.equal(entries[0].$type, 'Dirent'); assert.equal(entries[0].name, 'generated.png');
    assert.equal(entries[0].parentPath, '/workspace/images');
    assert.equal(await call('realpath', '/workspace/images/generated.png'), '/workspace/images/generated.png');
    const directory = await call('mkdtemp', '/workspace/generated-', { encoding: 'buffer' });
    assert(Buffer.isBuffer(directory)); assert(directory.toString().startsWith('/workspace/generated-'));
    checks.push('Stats, Dirent and virtual path results');

    const opened = await call('open', '/workspace/fd.bin', 'w+');
    assert.equal((await handle(opened.handle, 'write', [Buffer.from('abcdef'), 0])).bytesWritten, 6);
    const portion = await handle(opened.handle, 'read', [3, 2]);
    assert.equal(portion.bytesRead, 3); assert.equal(portion.buffer.toString(), 'cde');
    const vector = await handle(opened.handle, 'readv', [[2, 3], 1]);
    assert.equal(vector.bytesRead, 5); assert.equal(Buffer.concat(vector.buffers).toString(), 'bcdef');
    await handle(opened.handle, 'writev', [[Buffer.from('XY'), Buffer.from('Z')], 0]);
    assert.equal((await handle(opened.handle, 'stat')).size, 6);
    await handle(opened.handle, 'sync'); await handle(opened.handle, 'datasync');
    await handle(opened.handle, 'truncate', [3]);
    assert.equal(await call('readFile', '/workspace/fd.bin', 'utf8'), 'XYZ');
    await denied(handle(opened.handle, 'write', [Buffer.from('!'), 0], false));
    await handle(opened.handle, 'close');
    await denied(handle(opened.handle, 'read', [1, 0]), 'EBADF');
    checks.push('FileHandle byte I/O, vectors, metadata and explicit close');

    await denied(readonly('writeFile', '/workspace/read-only.txt', 'no'));
    await denied(readonly('mkdir', '/workspace/read-only'));
    await denied(readonly('open', '/workspace/fd.bin', 'r+'));
    await denied(readonly('access', '/workspace/fd.bin', 2));
    assert.deepEqual(await readonly('readFile', '/workspace/images/generated.png'), image);
    await denied(call('writeFile', '/workspace/../../escape.txt', 'no'));
    await denied(call('writeFile', '/etc/escape.txt', 'no'));
    await denied(call('writeFile', '/workspace-other/file', 'no'));
    await denied(call('rm', '/workspace', { recursive: true }), 'EBUSY');
    checks.push('read-only, traversal and managed-root protection');

    await fs.writeFile(path.join(outside, 'private.txt'), 'outside');
    await fs.symlink(outside, path.join(first, 'outside-link'), process.platform === 'win32' ? 'junction' : 'dir');
    await denied(call('readFile', '/workspace/outside-link/private.txt'));
    await denied(call('writeFile', '/workspace/outside-link/escape.txt', 'no'));
    await denied(call('symlink', '../../outside', '/workspace/images/bad-link', 'dir'));
    await call('symlink', 'images', '/workspace/inside-link', process.platform === 'win32' ? 'junction' : 'dir');
    assert.deepEqual(await call('readFile', '/workspace/inside-link/generated.png'), image);
    assert.equal(await call('realpath', '/workspace/inside-link'), '/workspace/images');
    await denied(call('cp', '/workspace', '/workspace/copy', { recursive: true }), 'EBUSY');
    await call('mkdir', '/workspace/tree');
    await fs.symlink(outside, path.join(first, 'tree/outside'), process.platform === 'win32' ? 'junction' : 'dir');
    await denied(call('cp', '/workspace/tree', '/workspace/tree-copy', { recursive: true, dereference: true }));
    assert.equal(await fs.readFile(path.join(outside, 'private.txt'), 'utf8'), 'outside');
    await assert.rejects(fs.stat(path.join(outside, 'escape.txt')), error => error.code === 'ENOENT');
    checks.push('symlink targets and recursive-copy containment');

    limited = createBackend({ root: first, maxHandles: 1 });
    const one = await limited.execute({ method: 'open', args: ['/workspace/fd.bin', 'r'] });
    await denied(limited.execute({ method: 'open', args: ['/workspace/fd.bin', 'r'] }), 'EMFILE');
    await limited.execute({ method: 'close', args: [], handle: one.handle });
    assert.equal(limited.handleCount, 0);
    idle = createBackend({ root: first, idleMs: 20 });
    const expired = await idle.execute({ method: 'open', args: ['/workspace/fd.bin', 'r'] });
    await new Promise(resolve => setTimeout(resolve, 70));
    assert.equal(idle.handleCount, 0);
    await denied(idle.execute({ method: 'read', args: [1, 0], handle: expired.handle }), 'EBADF');
    checks.push('file descriptor cap and idle cleanup');

    await denied(call('writeFile', '/workspace/too-large.bin', Buffer.alloc(MAX_BYTES)), 'EFBIG');
    await fs.writeFile(path.join(first, 'large.bin'), '');
    await fs.truncate(path.join(first, 'large.bin'), MAX_BYTES + 1);
    await denied(call('readFile', '/workspace/large.bin'), 'EFBIG');
    checks.push('bounded request and file-result size');

    server = http.createServer((req, res) => { void backend.handle(req, res); });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const request = async envelope => {
      const response = await fetch('http://127.0.0.1:' + server.address().port + '/plugin-fs',
        { method: 'POST', body: serialize(envelope) });
      return deserialize(Buffer.from(await response.arrayBuffer()));
    };
    assert.deepEqual((await request({ method: 'readFile', args: ['/workspace/images/generated.png'] })).result, image);
    const refusal = await request({ method: 'writeFile', args: ['/workspace/http-denied', 'no'], writable: false });
    assert.equal(refusal.error.code, 'EACCES');
    const abortedRequest = new PassThrough();
    abortedRequest.method = 'POST'; abortedRequest.url = '/plugin-fs';
    const abortedResponse = new EventEmitter(); abortedResponse.destroyed = true;
    const complete = backend.handle(abortedRequest, abortedResponse);
    abortedRequest.end(serialize({ method: 'open', args: ['/workspace/fd.bin', 'r'] }));
    await complete;
    assert.equal(backend.handleCount, 0);
    checks.push('v8 HTTP transport and canceled-open cleanup');

    console.log('PASS plugin file worker: ' + checks.length + ' checks');
    for (const name of checks) console.log('  ' + name);
  } finally {
    server?.closeAllConnections();
    if (server) await new Promise(resolve => server.close(resolve));
    await Promise.all([backend.close(), peer.close(), limited?.close(), idle?.close()]);
    // Only remove this test's freshly-created, direct child of the OS temp directory.
    assert.equal(path.dirname(path.resolve(fixture)), base);
    assert(path.basename(fixture).startsWith('sugar-plugin-files-worker-'));
    await fs.rm(fixture, { recursive: true, force: true });
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
