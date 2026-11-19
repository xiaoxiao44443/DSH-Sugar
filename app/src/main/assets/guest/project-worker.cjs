'use strict';

const http = require('node:http');
const { timingSafeEqual } = require('node:crypto');
const { Readable } = require('node:stream');
const { pipeline } = require('node:stream/promises');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const { readdir, stat, access, mkdir, realpath } = require('node:fs/promises');
const { constants: {X_OK} } = require('node:fs');
const allow = new Set(['resolve', 'stat', 'lstat', 'readText', 'streamText', 'readBytes', 'readByteRange',
  'listDir', 'writeText', 'editText', 'patchText', 'deleteFile', 'moveFile', 'resolveExecutable', 'prepareCreator']);

async function prepareCreator(directory,signal) {
  if (typeof directory !== 'string' || directory.length > 4095 ||
      !directory.startsWith('/workspace/') || directory.endsWith('/') || path.posix.normalize(directory) !== directory ||
      /[\\\x00-\x1f\x7f]/.test(directory) ||
      path.posix.basename(path.posix.dirname(directory)) !== '.dsh-created-plugins' ||
      !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(path.posix.basename(directory))) {
    throw Error('Invalid Creator source directory');
  }
  const parent = path.dirname(directory), base = path.dirname(parent);
  if (await realpath(base) !== base) throw Error('Creator parent contains a symbolic link');
  for (const name of [parent,directory]) {
    signal.throwIfAborted();
    try { await mkdir(name,{mode:0o700}); }
    catch (error) { if (error.code !== 'EEXIST') throw error; }
    if (await realpath(name) !== name || !(await stat(name)).isDirectory()) throw Error('Invalid Creator directory');
  }
  return {path:directory};
}

async function resolveExecutable(command, searchPath, signal) {
  if (typeof command !== 'string' || !command || (!path.isAbsolute(command) && command.includes('/'))) {
    throw Error('Expected an absolute executable path or a bare name');
  }
  const candidates = path.isAbsolute(command) ? [command] : (searchPath ?? process.env.PATH ?? '/usr/bin:/bin')
    .split(':').map(dir => path.resolve(dir, command));
  for (const candidate of candidates) {
    signal.throwIfAborted();
    try {
      if (!(await stat(candidate)).isFile()) continue;
      await access(candidate, X_OK);
      return candidate;
    } catch { /* continue PATH lookup */ }
  }
  return null;
}

async function main() {
  const secret = process.env.SUGAR_PROJECT_TOKEN;
  delete process.env.SUGAR_PROJECT_TOKEN;
  if (!secret || secret.length < 32) throw Error('Missing project capability');
  const auth = Buffer.from('Bearer ' + secret);
  const { LocalFileSystem } = await import(pathToFileURL(path.join(__dirname, 'project-fs.mjs')).href);
  const provider = new LocalFileSystem(null, { cwd: '/workspace', diffBasisMaxBytes: 1 << 20 });
  const processes = require('./project-process-worker.cjs');
  const pluginFiles = require('./plugin-files-worker.cjs');
  const idleLimit = 30 * 60 * 1000;
  let requests = 0, lastActivity = Date.now();
  const server = http.createServer(async (req, res) => {
    const token = Buffer.from(req.headers.authorization || '');
    if (token.length !== auth.length || !timingSafeEqual(token, auth)) {
      res.writeHead(403).end(); return;
    }
    const passive = req.headers['x-sugar-watch'] === '1' && (req.url === '/fs/stat' || req.url === '/fs/listDir');
    if (!passive) requests++;
    const abort = new AbortController();
    res.once('close', () => {
      if (!passive) { requests--; lastActivity = Date.now(); }
      abort.abort();
    });
    try {
      const route = (req.url || '').split('?')[0];
      if (req.method === 'POST' && route === '/activity') {
        res.writeHead(204).end(); return;
      }
      if (route === '/plugin-fs') { await pluginFiles.handle(req,res); return; }
      if (route.startsWith('/process/')) {
        await processes.handle(req, res, route);
        return;
      }
      const method = route.slice('/fs/'.length);
      if (req.method !== 'POST' || !route.startsWith('/fs/') || !allow.has(method)) {
        res.writeHead(404).end(); return;
      }
      const chunks = [];
      let size = 0;
      for await (const chunk of req) {
        size += chunk.length;
        if (size > 32 * 1024 * 1024) { res.writeHead(413).end(); return; }
        chunks.push(chunk);
      }
      const args = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      if (!Array.isArray(args)) { res.writeHead(400).end(); return; }
      if (method === 'resolve' || method === 'lstat') args[1] = { ...args[1], signal: abort.signal };
      if (method === 'readBytes') args[1] = abort.signal;
      else if (method !== 'resolve') args.push(abort.signal);
      let result;
      if (method === 'resolveExecutable') result = await resolveExecutable(...args);
      else if (method === 'prepareCreator') result = await prepareCreator(...args);
      else result = await provider[method](...args);
      if (method === 'streamText') {
        const stream = result[Symbol.asyncIterator]();
        const first = await stream.next();
        res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
        const text = async function* () {
          if (!first.done) { yield first.value; yield* stream; }
        };
        await pipeline(Readable.from(text()), res, { signal: abort.signal }); return;
      }
      if (result instanceof Uint8Array) {
        res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': result.length }).end(result);
      } else res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({result}));
    } catch (error) {
      if (res.headersSent) res.destroy();
      else res.writeHead(422, { 'Content-Type': 'application/json' }).end(JSON.stringify({
        error: { code: error.code || 'FS_IO_ERROR', message: String(error.message || 'Project operation failed') }
      }));
    }
  });
  let checkingIdle = false;
  const idle = setInterval(async () => {
    const now = Date.now();
    if (requests || processes.hasActive()) lastActivity = now;
    if (now - lastActivity < idleLimit || checkingIdle) return;
    checkingIdle = true;
    try {
      // Detached shell children outlive their request. /proc is scoped by Sucrune to this project.
      const pids = await readdir('/proc');
      if (pids.some(name => /^\d+$/.test(name) && name !== String(process.pid))) {
        lastActivity = Date.now();
      } else if (!requests && !processes.hasActive() && Date.now() - lastActivity >= idleLimit) {
        clearInterval(idle);
        server.close();
        server.closeIdleConnections();
      }
    } catch {
      // Retry the census before retiring a worker whose process state is unavailable.
    } finally {
      checkingIdle = false;
    }
  }, 30000);
  idle.unref();
  server.listen(0, '127.0.0.1', () => process.stdout.write('SUGAR_PROJECT_PORT=' + server.address().port + '\n'));
}
main().catch(() => { process.stderr.write('Could not start project service\n'); process.exitCode = 1; });
