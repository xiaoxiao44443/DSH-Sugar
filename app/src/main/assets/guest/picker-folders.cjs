'use strict';

const fs = require('node:fs');
const http = require('node:http');
const { randomBytes, timingSafeEqual } = require('node:crypto');
const { performance } = require('node:perf_hooks');
const { isWorkspaceRoot } = require('./workspace-root.cjs');

const ticketLifetime = 120_000;
const maxTickets = 8;
const maxRequest = 32 * 1024;
const reserved = '/workspace/deepseek-harness/default-workspace';
const expired = '目录已变化或确认已过期，请重新选择。';
const nonempty = '文件夹里还有内容。只能删除空文件夹。';
class FolderError extends Error {}

function contains(parent, child) {
  return child === parent || child.startsWith(parent + '/');
}

function inspectPath(path, registry) {
  if (!isWorkspaceRoot(path) || contains(reserved, path) ||
      /^\/workspace\/(?:phone|local)-[a-f0-9]{64}(?:\/|$)/.test(path)) {
    throw new FolderError('此路径受保护，不能删除。');
  }
  if (registry.list().some(row => contains(row.path, path) || contains(path, row.path))) {
    throw new FolderError('该文件夹与已登记的工作区重叠，不能在这里删除。');
  }
  const parts = path.slice(1).split('/');
  const identity = [];
  let current = '';
  for (const part of parts) {
    current += '/' + part;
    const entry = fs.lstatSync(current, { bigint:true });
    if (!entry.isDirectory()) throw new FolderError('请选择实际目录。路径中不能含有符号链接。');
    identity.push(entry.dev + ':' + entry.ino);
    if (current === path) identity.push(String(entry.ctimeNs));
  }
  return identity.join('/');
}

function requireEmpty(path) {
  const directory = fs.opendirSync(path);
  try {
    if (directory.readSync() !== null) throw new FolderError(nonempty);
  } finally {
    directory.closeSync();
  }
}

// Each proof pins the inspected directory's inode until confirmation, cancellation by expiry, or eviction.
// The workspace service serializes both checks and rmdir with registration/removal of workspaces.
function createOperations(currentRegistry, management) {
  const tickets = new Map();
  function discard(key) {
    const proof = tickets.get(key);
    if (!proof) return;
    tickets.delete(key);
    clearTimeout(proof.timer);
    fs.closeSync(proof.fd);
  }
  function operate({op, path, ticket}) {
    const registry = currentRegistry();
    if (!registry) throw new FolderError('工作区尚未就绪，请稍后重试。');
    return registry.enqueueOperation(async () => {
      if (registry !== currentRegistry()) throw new FolderError(expired);
      if (op === 'inspect') {
        const identity = inspectPath(path, registry);
        const archive = await management({op:'inspectRetainedFolder',root:path});
        if (archive.empty === false) throw new FolderError(nonempty);
        const fd = fs.openSync(path, fs.constants.O_RDONLY | fs.constants.O_DIRECTORY | fs.constants.O_NOFOLLOW);
        let retained = false;
        try {
          requireEmpty(path);
          const pinned = fs.fstatSync(fd, {bigint:true});
          if (!identity.endsWith('/' + pinned.dev + ':' + pinned.ino + '/' + pinned.ctimeNs) ||
              inspectPath(path, registry) !== identity) throw new FolderError(expired);
          if (tickets.size >= maxTickets) discard(tickets.keys().next().value);
          const key = randomBytes(32).toString('base64url');
          const timer = setTimeout(() => discard(key), ticketLifetime);
          timer.unref();
          tickets.set(key, {path, identity, fd, timer, registry, until:performance.now() + ticketLifetime});
          retained = true;
          return {ticket:key};
        } finally {
          if (!retained) fs.closeSync(fd);
        }
      }
      if (op !== 'remove') throw new FolderError('未知的文件夹操作。');
      const proof = tickets.get(ticket);
      if (!proof) throw new FolderError(expired);
      try {
        if (proof.path !== path || proof.registry !== registry || proof.until <= performance.now()) {
          throw new FolderError(expired);
        }
        if (inspectPath(path, registry) !== proof.identity) throw new FolderError(expired);
        const retained = await management({op:'inspectRetainedFolder',root:path});
        if (retained.empty === false) throw new FolderError(nonempty);
        if (inspectPath(path, registry) !== proof.identity) throw new FolderError(expired);
        // rmdir itself checks emptiness, including hidden files, at the instant of removal.
        fs.rmdirSync(path);
        if (retained.id) await management({op:'forgetEmptyFolder',root:path,id:retained.id});
        return {};
      } finally {
        discard(ticket);
      }
    });
  }
  return {operate, close() { for (const key of tickets.keys()) discard(key); }};
}

function message(error) {
  if (error instanceof FolderError) return error.message;
  if (error?.code === 'ENOTEMPTY' || error?.code === 'EEXIST') return nonempty;
  if (['ENOENT','ENOTDIR','ELOOP'].includes(error?.code)) return expired;
  return '无法完成文件夹操作，请刷新目录后重试。';
}

async function start(currentRegistry, register, petReplies, sessionStorage) {
  const operations = createOperations(currentRegistry, register);
  const token = randomBytes(32).toString('base64url');
  const authorization = Buffer.from('Bearer ' + token);
  const server = http.createServer({requestTimeout:6000, headersTimeout:5000, maxHeaderSize:8192}, async (req, res) => {
    const reply = (status, body) => {
      res.writeHead(status, {'Content-Type':'application/json; charset=utf-8', 'Cache-Control':'no-store', Connection:'close'});
      res.end(JSON.stringify(body));
    };
    const key = Buffer.from(req.headers.authorization || '');
    const pet = req.url === '/pet-reply' && typeof petReplies === 'function';
    const sessions = req.url === '/session-storage' && typeof sessionStorage === 'function';
    if (req.method !== 'POST' || (!pet && !sessions && req.url !== '/folder') || req.headers.origin !== undefined ||
        key.length !== authorization.length || !timingSafeEqual(key, authorization)) {
      reply(403, {ok:false,error:{message:'未授权。'}});
      return;
    }
    const size = Number(req.headers['content-length']);
    if (!Number.isInteger(size) || size <= 0 || size > (pet ? 900 * 1024 : maxRequest) ||
        req.headers['content-type']?.split(';')[0] !== 'application/json') {
      reply(400, {ok:false,error:{message:'文件夹请求无效。'}});
      return;
    }
    try {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const request = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      if (pet && size > maxRequest && request?.op !== 'voiceTranscribe') throw Error('回复超过长度限制。');
      if (pet && request?.op === 'voiceTranscribe') req.socket.setTimeout(46_000);
      if (sessions) req.socket.setTimeout(180_000);
      const result = await (sessions ? sessionStorage(request) : pet ? petReplies(request) : operations.operate(request));
      reply(200,{ok:true,result});
    } catch (error) {
      reply(200,{ok:false,error:{message:sessions || pet ? String(error?.message || '操作失败。').slice(0, 240) : message(error)}});
    }
  });
  server.maxConnections = 16;
  server.setTimeout(6000);
  server.once('close', operations.close);
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => { server.off('error',reject); resolve(); });
  });
  try {
    await register({op:'pickerReady', port:server.address().port, token});
    server.unref();
    return server;
  } catch (error) {
    server.close();
    throw error;
  }
}

module.exports = {start};
