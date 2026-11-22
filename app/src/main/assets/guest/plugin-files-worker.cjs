'use strict';

// Runs in the project worker, after its existing bearer-token authentication.
// The protocol carries virtual /workspace paths, never Android or coordinator paths.
const fs = require('node:fs');
const fsp = fs.promises;
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { serialize, deserialize } = require('node:v8');

const MAX_BYTES = 32 * 1024 * 1024;
const MAX_IO_BYTES = MAX_BYTES - 4096;
const VIRTUAL_ROOT = '/workspace';
const mutating = new Set(['writeFile', 'appendFile', 'mkdir', 'rm', 'rmdir', 'unlink',
  'rename', 'copyFile', 'cp', 'chmod', 'chown', 'utimes', 'truncate', 'mkdtemp', 'symlink', 'link']);
const handleAliases = { fstat: 'stat', fsync: 'sync', fdatasync: 'datasync',
  ftruncate: 'truncate', fchmod: 'chmod', fchown: 'chown', futimes: 'utimes' };
const handleMutating = new Set(['write', 'writev', 'writeFile', 'appendFile', 'truncate',
  'chmod', 'chown', 'utimes']);
const pathMethods = new Set(['readFile', 'writeFile', 'appendFile', 'stat', 'lstat',
  'readdir', 'realpath', 'access', 'mkdir', 'rm', 'rmdir', 'unlink', 'rename', 'copyFile',
  'cp', 'chmod', 'chown', 'utimes', 'truncate', 'mkdtemp', 'readlink', 'symlink', 'link', 'open', 'opendir']);

function failure(code, message, name, dest) {
  return Object.assign(new Error(message), { code, ...(name === undefined ? {} : { path: name }),
    ...(dest === undefined ? {} : { dest }) });
}
function kind(info) {
  for (const [name, method] of [['file', 'isFile'], ['directory', 'isDirectory'],
    ['symlink', 'isSymbolicLink'], ['blockDevice', 'isBlockDevice'],
    ['characterDevice', 'isCharacterDevice'], ['fifo', 'isFIFO'], ['socket', 'isSocket']]) {
    if (info[method]()) return name;
  }
  return 'unknown';
}
function stats(info) {
  return { ...info, atime: info.atime, mtime: info.mtime, ctime: info.ctime,
    birthtime: info.birthtime, $type: 'Stats', kind: kind(info) };
}
function optionsObject(value) { return value && typeof value === 'object' ? value : {}; }
function writingFlags(flags) {
  if (flags === undefined) return false;
  if (typeof flags === 'number') return !!(flags &
    (fs.constants.O_WRONLY | fs.constants.O_RDWR | fs.constants.O_CREAT |
      fs.constants.O_TRUNC | fs.constants.O_APPEND | fs.constants.O_EXCL));
  if (typeof flags !== 'string') throw failure('EINVAL', 'Invalid file open flags');
  if (!/^(?:r[sa]?\+?|w[x]?\+?|a[xs]?\+?)$/.test(flags)) {
    // Let Node validate the complete flag vocabulary, without accidentally granting writes.
    if (!['rs', 'rs+', 'sr', 'sr+', 'wx', 'wx+', 'xw', 'xw+', 'ax', 'ax+', 'xa', 'xa+', 'as', 'as+', 'sa', 'sa+'].includes(flags))
      throw failure('EINVAL', 'Invalid file open flags');
  }
  return /[wa+]/.test(flags);
}
function byteBuffer(value) {
  if (Buffer.isBuffer(value)) return value;
  if (ArrayBuffer.isView(value)) return Buffer.from(value.buffer, value.byteOffset, value.byteLength);
  if (value instanceof ArrayBuffer) return Buffer.from(value);
  throw failure('EINVAL', 'Expected file bytes');
}
function checkedLength(value) {
  if (!Number.isSafeInteger(value) || value < 0 || value > MAX_IO_BYTES)
    throw failure('EFBIG', 'Plugin file I/O exceeds the 32 MiB limit');
  return value;
}
function checkedPosition(value) {
  if (value == null) return null;
  if (typeof value === 'bigint') {
    if (value < 0n || value > BigInt(Number.MAX_SAFE_INTEGER)) throw failure('EINVAL', 'Invalid file position');
    return Number(value);
  }
  if (!Number.isSafeInteger(value) || value < 0) throw failure('EINVAL', 'Invalid file position');
  return value;
}

function createBackend({ root = VIRTUAL_ROOT, maxHandles = 128, idleMs = 60000 } = {}) {
  if (!Number.isInteger(maxHandles) || maxHandles < 1 || maxHandles > 128)
    throw failure('EINVAL', 'Invalid plugin file handle limit');
  if (!Number.isFinite(idleMs) || idleMs < 10) throw failure('EINVAL', 'Invalid file handle idle timeout');
  const backingRoot = path.resolve(root);
  const handles = new Map();
  let rootIdentity, pendingOpens = 0, closed = false;

  const withinRoot = name => {
    const relative = path.relative(rootIdentity, name);
    return relative === '' || (!relative.startsWith('..' + path.sep) && relative !== '..' && !path.isAbsolute(relative));
  };
  async function currentRoot() {
    const actual = await fsp.realpath(backingRoot);
    if (rootIdentity && actual !== rootIdentity) throw failure('ESTALE', 'The original workspace is unavailable', VIRTUAL_ROOT);
    rootIdentity = actual;
    return actual;
  }
  function virtual(name) {
    const relative = path.relative(rootIdentity || backingRoot, name);
    return relative ? VIRTUAL_ROOT + '/' + relative.split(path.sep).join('/') : VIRTUAL_ROOT;
  }
  function canonical(name) {
    if (typeof name !== 'string' || name.includes('\0') || name.includes('\\') ||
      !name.startsWith('/') || Buffer.byteLength(name) > 4095)
      throw failure('EINVAL', 'Expected an absolute workspace path', typeof name === 'string' ? name : undefined);
    const normalized = path.posix.normalize(name);
    const clean = normalized.length > 1 ? normalized.replace(/\/+$/, '') : normalized;
    if (clean !== VIRTUAL_ROOT && !clean.startsWith(VIRTUAL_ROOT + '/'))
      throw failure('EACCES', 'Plugin file access is limited to /workspace', name);
    return clean;
  }
  async function contained(absolute) {
    // Resolve links component by component, including dangling links whose future target
    // is outside the workspace. realpath alone cannot validate a new output file.
    let target = path.resolve(absolute);
    for (let links = 0; links <= 40; links++) {
      if (!withinRoot(target)) throw failure('EACCES', 'A symbolic link leaves the workspace');
      const relative = path.relative(rootIdentity, target);
      const parts = relative ? relative.split(path.sep) : [];
      let current = rootIdentity, redirected = false;
      for (let index = 0; index < parts.length; index++) {
        current = path.join(current, parts[index]);
        let info;
        try { info = await fsp.lstat(current); }
        catch (error) {
          if (error.code === 'ENOENT') return target;
          throw error;
        }
        if (info.isSymbolicLink()) {
          const link = await fsp.readlink(current);
          target = path.resolve(path.dirname(current), link, ...parts.slice(index + 1));
          if (!withinRoot(target)) throw failure('EACCES', 'A symbolic link leaves the workspace');
          redirected = true;
          break;
        }
      }
      if (!redirected) return target;
    }
    throw failure('ELOOP', 'Too many symbolic links in workspace path');
  }
  async function locate(name, { protectRoot = false } = {}) {
    await currentRoot();
    const normalized = canonical(name);
    if (protectRoot && normalized === VIRTUAL_ROOT)
      throw failure('EBUSY', 'The workspace root is managed by Sugar', normalized);
    const physical = path.join(rootIdentity, ...normalized.slice(VIRTUAL_ROOT.length).split('/').filter(Boolean));
    await contained(physical);
    return physical;
  }
  function requireWritable(request) {
    if (request.writable !== true) throw failure('EACCES', 'This conversation has read-only file access');
  }
  function errorEnvelope(error) {
    const clean = value => {
      if (typeof value !== 'string') return value;
      for (const prefix of new Set([rootIdentity, backingRoot].filter(Boolean))) value = value.replaceAll(prefix, VIRTUAL_ROOT);
      return value;
    };
    return { error: { message: clean(error?.message || 'Plugin file operation failed'),
      code: error?.code || 'EIO', ...(error?.syscall ? { syscall: error.syscall } : {}),
      ...(error?.path !== undefined ? { path: clean(error.path) } : {}),
      ...(error?.dest !== undefined ? { dest: clean(error.dest) } : {}) } };
  }
  async function boundedRead(file, options) {
    const chunks = [];
    let total = 0;
    for (;;) {
      const buffer = Buffer.allocUnsafe(Math.min(65536, MAX_IO_BYTES + 1 - total));
      const { bytesRead } = await file.read(buffer, 0, buffer.length, null);
      if (!bytesRead) break;
      total += bytesRead;
      if (total > MAX_IO_BYTES) throw failure('EFBIG', 'Plugin file result exceeds the 32 MiB limit');
      chunks.push(buffer.subarray(0, bytesRead));
    }
    const result = Buffer.concat(chunks, total);
    const encoding = typeof options === 'string' ? options : optionsObject(options).encoding;
    return encoding ? result.toString(encoding) : result;
  }
  async function closeRecord(id, record) {
    if (record.closing) return record.closing;
    handles.delete(id);
    record.closing = record.tail.then(() => record.file.close());
    return record.closing;
  }
  const sweep = setInterval(() => {
    const cutoff = Date.now() - idleMs;
    for (const [id, record] of handles) {
      if (!record.pending && record.touched <= cutoff) void closeRecord(id, record).catch(() => {});
    }
  }, Math.max(10, Math.min(idleMs, 10000)));
  sweep.unref?.();

  async function handleCall(request, method, args) {
    if (typeof request.handle !== 'string') throw failure('EBADF', 'Missing plugin file handle');
    const record = handles.get(request.handle);
    if (!record) throw failure('EBADF', 'Plugin file handle is closed or expired');
    method = handleAliases[method] || method;
    if (record.type === 'directory') {
      if (method === 'closeDir') return closeRecord(request.handle, record);
      if (method !== 'readDir') throw failure('EBADF', 'Operation requires a file handle');
      record.pending++;
      record.touched = Date.now();
      // A recursive facade keeps parent cursors open while visiting a child.
      // Child progress keeps those ancestors alive; fully idle walks still expire.
      for (const ancestor of handles.values()) if (ancestor.type === 'directory' &&
          record.location.startsWith(ancestor.location + path.sep)) ancestor.touched = record.touched;
      const task = record.tail.then(async () => {
        await currentRoot();
        await contained(record.location);
        const entry = await record.file.read();
        return entry === null ? null : {$type:'Dirent',kind:kind(entry),name:entry.name,
          parentPath:virtual(record.location),path:virtual(record.location)};
      });
      record.tail = task.catch(() => {});
      try { return await task; }
      finally { record.pending--; record.touched = Date.now(); }
    }
    if (method === 'closeDir' || method === 'readDir') throw failure('EBADF', 'Operation requires a directory handle');
    if (method === 'close') return closeRecord(request.handle, record);
    if (handleMutating.has(method)) {
      requireWritable(request);
      if (!record.writable) throw failure('EBADF', 'File was not opened for writing');
    }
    record.pending++;
    record.touched = Date.now();
    const task = record.tail.then(async () => {
      const file = record.file;
      switch (method) {
        case 'stat': return stats(await file.stat(args[0]));
        case 'read': {
          const buffer = Buffer.allocUnsafe(checkedLength(args[0]));
          const { bytesRead } = await file.read(buffer, 0, buffer.length, checkedPosition(args[1]));
          return { bytesRead, buffer: buffer.subarray(0, bytesRead) };
        }
        case 'write': {
          const buffer = byteBuffer(args[0]); checkedLength(buffer.byteLength);
          const { bytesWritten } = await file.write(buffer, 0, buffer.length, checkedPosition(args[1]));
          return { bytesWritten };
        }
        case 'readv': {
          if (!Array.isArray(args[0]) || args[0].length > 1024) throw failure('EINVAL', 'Invalid read buffers');
          checkedLength(args[0].reduce((total, length) => total + checkedLength(length), 0));
          const buffers = args[0].map(length => Buffer.alloc(length));
          const { bytesRead } = await file.readv(buffers, checkedPosition(args[1]));
          return { bytesRead, buffers };
        }
        case 'writev': {
          if (!Array.isArray(args[0]) || args[0].length > 1024) throw failure('EINVAL', 'Invalid write buffers');
          const buffers = args[0].map(byteBuffer);
          checkedLength(buffers.reduce((total, buffer) => total + buffer.length, 0));
          const { bytesWritten } = await file.writev(buffers, checkedPosition(args[1]));
          return { bytesWritten };
        }
        case 'readFile': return boundedRead(file, args[0]);
        case 'writeFile': await file.writeFile(...args); return;
        case 'appendFile': await file.appendFile(...args); return;
        case 'sync': await file.sync(); return;
        case 'datasync': await file.datasync(); return;
        case 'truncate': await file.truncate(...args); return;
        case 'chmod': await file.chmod(...args); return;
        case 'chown': await file.chown(...args); return;
        case 'utimes': await file.utimes(...args); return;
        default: throw failure('ENOTSUP', 'Unsupported plugin file handle operation: ' + method);
      }
    });
    record.tail = task.catch(() => {});
    try { return await task; }
    finally { record.pending--; record.touched = Date.now(); }
  }
  async function inspectTree(start, dereference, visited = new Set()) {
    const resolved = await contained(start);
    const info = await fsp.lstat(start);
    if (info.isSymbolicLink() && !dereference) return;
    const actual = info.isSymbolicLink() ? await fsp.stat(start) : info;
    if (!actual.isDirectory()) return;
    const key = resolved;
    if (visited.has(key)) throw failure('ELOOP', 'Recursive symbolic link in workspace copy');
    visited.add(key);
    try {
      for (const entry of await fsp.readdir(start)) await inspectTree(path.join(start, entry), dereference, visited);
    } finally { visited.delete(key); }
  }
  async function execute(request) {
    if (closed) throw failure('EBADF', 'Plugin file backend is closed');
    if (!request || typeof request !== 'object' || Array.isArray(request) ||
      typeof request.method !== 'string' || !Array.isArray(request.args))
      throw failure('EINVAL', 'Invalid plugin file request');
    // Keep direct test/backend callers subject to the same bound as HTTP callers.
    if (serialize(request).byteLength > MAX_BYTES) throw failure('EFBIG', 'Plugin file request exceeds the 32 MiB limit');
    const method = request.method;
    const args = request.args.slice();
    let result;
    if (request.handle !== undefined) result = await handleCall(request, method, args);
    else {
      if (!pathMethods.has(method)) throw failure('ENOTSUP', 'Unsupported plugin file operation: ' + method);
      if (mutating.has(method) || (method === 'access' && ((args[1] ?? 0) & fs.constants.W_OK))) requireWritable(request);
      if (method === 'open' || method === 'opendir') {
        const directory = method === 'opendir';
        // Recursive traversal is performed by the facade, opening each child
        // through this checked path rather than Node's unchecked recursive Dir.
        if (directory && optionsObject(args[1]).recursive)
          throw failure('EINVAL', 'Recursive directory traversal requires checked child opens');
        const write = !directory && writingFlags(args[1]);
        if (write) requireWritable(request);
        if (handles.size + pendingOpens >= maxHandles) throw failure('EMFILE', 'Too many open plugin files');
        pendingOpens++;
        let file;
        try {
          const location = await locate(args[0], { protectRoot: write });
          file = directory ? await fsp.opendir(location, args[1]) : await fsp.open(location, args[1] ?? 'r', args[2]);
          if (closed) throw failure('EBADF', 'Plugin file backend is closed');
          const id = randomUUID();
          handles.set(id, { file, type:directory ? 'directory' : 'file', location, writable: !directory && request.writable === true,
            pending: 0, touched: Date.now(), tail: Promise.resolve(), closing: null });
          file = null;
          result = { handle: id };
        } finally { pendingOpens--; await file?.close(); }
      } else if (method === 'symlink') {
        const linkName = await locate(args[1], { protectRoot: true });
        if (typeof args[0] !== 'string' || args[0].includes('\0') || args[0].includes('\\'))
          throw failure('EINVAL', 'Invalid workspace symbolic-link target');
        const target = args[0].startsWith('/') ? await locate(args[0])
          : path.resolve(path.dirname(linkName), args[0]);
        await contained(target);
        await fsp.symlink(args[0].startsWith('/') ? target : args[0], linkName, args[2]);
      } else {
        const originalName = args[0];
        const protectRoot = mutating.has(method) && method !== 'mkdir';
        args[0] = await locate(originalName, { protectRoot });
        if (['rename', 'copyFile', 'cp', 'link'].includes(method)) args[1] = await locate(args[1], { protectRoot: true });
        switch (method) {
          case 'readFile': {
            const options = optionsObject(args[1]);
            if (writingFlags(options.flag ?? 'r')) requireWritable(request);
            const file = await fsp.open(args[0], options.flag ?? 'r');
            try { result = await boundedRead(file, args[1]); }
            finally { await file.close(); }
            break;
          }
          case 'stat': case 'lstat': result = stats(await fsp[method](...args)); break;
          case 'readdir': {
            result = await fsp.readdir(...args);
            if (optionsObject(args[1]).withFileTypes) result = result.map(entry => ({
              $type: 'Dirent', kind: kind(entry), name: entry.name,
              parentPath: virtual(entry.parentPath || entry.path || args[0]),
              path: virtual(entry.parentPath || entry.path || args[0])
            }));
            break;
          }
          case 'realpath': {
            const actual = await fsp.realpath(args[0]);
            if (!withinRoot(actual)) throw failure('EACCES', 'A symbolic link leaves the workspace', originalName);
            result = virtual(actual);
            if (optionsObject(args[1]).encoding === 'buffer' || args[1] === 'buffer') result = Buffer.from(result);
            break;
          }
          case 'readlink': {
            const raw = await fsp.readlink(args[0]);
            result = path.isAbsolute(raw) ? virtual(raw) : raw.split(path.sep).join('/');
            const encoding = typeof args[1] === 'string' ? args[1] : optionsObject(args[1]).encoding;
            if (encoding === 'buffer') result = Buffer.from(result);
            else if (encoding && encoding !== 'utf8' && encoding !== 'utf-8') result = Buffer.from(result).toString(encoding);
            break;
          }
          case 'cp': {
            await inspectTree(args[0], !!optionsObject(args[2]).dereference);
            try { await inspectTree(args[1], false); }
            catch (error) { if (error.code !== 'ENOENT') throw error; }
            result = await fsp.cp(...args);
            break;
          }
          case 'mkdtemp': {
            const encoding = typeof args[1] === 'string' ? args[1] : optionsObject(args[1]).encoding;
            result = virtual(await fsp.mkdtemp(args[0], { ...optionsObject(args[1]), encoding: 'utf8' }));
            if (encoding === 'buffer') result = Buffer.from(result);
            else if (encoding && encoding !== 'utf8' && encoding !== 'utf-8') result = Buffer.from(result).toString(encoding);
            break;
          }
          case 'mkdir': {
            result = await fsp.mkdir(...args);
            if (result !== undefined) result = virtual(result);
            break;
          }
          default: result = await fsp[method](...args);
        }
      }
    }
    if (serialize({ result }).byteLength > MAX_BYTES) throw failure('EFBIG', 'Plugin file result exceeds the 32 MiB limit');
    return result;
  }
  async function close() {
    if (closed) return;
    closed = true;
    clearInterval(sweep);
    await Promise.allSettled([...handles].map(([id, record]) => closeRecord(id, record)));
  }
  async function discardOpen(result) {
    if (result?.handle && handles.has(result.handle)) await closeRecord(result.handle, handles.get(result.handle)).catch(() => {});
  }
  async function handle(req, res) {
    if (req.method !== 'POST' || req.url?.split('?')[0] !== '/plugin-fs') {
      res.writeHead(404).end(); return;
    }
    let opened, responded = false;
    const discard = () => { if (!responded) void discardOpen(opened); };
    res.once('close', discard);
    try {
      const body = await new Promise((resolve, reject) => {
        const chunks = [];
        let size = 0, excessive = false;
        req.on('data', chunk => {
          size += chunk.length;
          if (size > MAX_BYTES) { excessive = true; chunks.length = 0; }
          else if (!excessive) chunks.push(chunk);
        });
        req.once('end', () => excessive ? reject(failure('EFBIG', 'Plugin file request exceeds the 32 MiB limit'))
          : resolve(Buffer.concat(chunks, size)));
        req.once('error', reject);
        req.once('aborted', () => reject(failure('ECANCELED', 'Plugin file request was canceled')));
      });
      const request = deserialize(body);
      const result = await execute(request);
      if (['open','opendir'].includes(request.method) && request.handle === undefined) opened = result;
      if (req.aborted || res.destroyed) { await discardOpen(opened); return; }
      const bytes = serialize({ result });
      res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': bytes.length, 'Cache-Control': 'no-store' });
      res.end(bytes, error => {
        if (error) void discardOpen(opened);
        else responded = true;
        res.off('close', discard);
      });
    } catch (error) {
      await discardOpen(opened);
      if (res.destroyed) return;
      const bytes = serialize(errorEnvelope(error));
      res.writeHead(error?.code === 'EFBIG' ? 413 : 200,
        { 'Content-Type': 'application/octet-stream', 'Content-Length': bytes.length, 'Cache-Control': 'no-store' });
      res.end(bytes);
    }
  }
  return { execute, handle, close, errorEnvelope, get handleCount() { return handles.size; } };
}

let singleton;
exports.createBackend = createBackend;
exports.MAX_BYTES = MAX_BYTES;
exports.handle = (req, res) => (singleton ??= createBackend()).handle(req, res);
exports.close = () => singleton?.close();
