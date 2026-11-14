'use strict';

const http = require('node:http');
const { randomUUID } = require('node:crypto');
const { PassThrough, Writable, Duplex } = require('node:stream');
const { once } = require('node:events');
const path = require('node:path').posix;
const { C: bind, E: validate } = require('/opt/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/lib/runner-launch-CKTiL8M8.js');

// The project gets its own HOME/PATH from its worker. Upstream plugins may supply
// an entire host env as an explicit override (normally merged *after* DSH's scrub).
// Forward session/PTY facts intentionally; capability tokens and host configuration
// must never appear on the wire, even in an explicit spawn environment.
const sessionKeys = new Set(['DSH_SESSION_ID', 'DSH_PTY_SESSION_ID', 'DSH_SHELL']);
function projectEnv(cwd, entries) {
  const result = Object.create(null);
  for (const [key, value] of Object.entries(entries ?? {})) {
    const upper = key.toUpperCase();
    if (/KEY|PASSWORD|SECRET|TOKEN|CREDENTIAL/.test(upper) || upper.startsWith('SUGAR_') || upper.startsWith('SUCRUNE_') ||
        (upper.startsWith('DSH_') && !sessionKeys.has(upper))) continue;
    if (value == null) continue;
    if (typeof value !== 'string') throw Error('Expected a string in project environment');
    if (upper === 'PWD') continue;
    if (upper === 'OLDPWD' || upper === 'INIT_CWD') {
      const dir = path.resolve('/', value);
      if (value.startsWith('/') && (dir === '/workspace' || dir.startsWith('/workspace/'))) result[key] = dir;
    } else result[key] = value;
  }
  result.PWD = cwd;
  return result;
}

function deferred() {
  const result = Promise.withResolvers();
  result.promise.catch(() => {});
  return result;
}

// The peer runs inside a writable project. Enforce limits before parsing any
// JSON, including responses from a replaced or corrupt helper.
async function responseJson(response) {
  const chunks = [];
  let size = 0;
  for await (const chunk of response) {
    size += chunk.length;
    if (size > 128 * 1024) throw Error('Project response exceeded its limit');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks, size));
}

async function* events(response) {
  let chunks = [], size = 0;
  for await (const chunk of response) {
    let start = 0;
    while (start < chunk.length) {
      const end = chunk.indexOf(10, start);
      const part = chunk.subarray(start, end < 0 ? chunk.length : end);
      size += part.length;
      if (size > 1024 * 1024) throw Error('Project process frame exceeded its limit');
      chunks.push(part);
      if (end < 0) break;
      yield JSON.parse(Buffer.concat(chunks, size));
      chunks = []; size = 0; start = end + 1;
    }
  }
  if (size) throw Error('Incomplete project process frame');
}

function post(endpoint, pathname, body) {
  const bytes = Buffer.from(JSON.stringify(body));
  let request;
  const response = new Promise((resolve, reject) => {
    request = http.request(new URL(pathname, endpoint.url), { method: 'POST', headers: {
      Authorization: 'Bearer ' + endpoint.token, 'Content-Type': 'application/json', 'Content-Length': bytes.length
    }}, resolve);
    request.on('error', reject);
    request.end(bytes);
  }).then(async res => {
    if (res.statusCode === 200) return res;
    let detail;
    try { detail = (await responseJson(res)).error; } catch {}
    throw Object.assign(Error(detail?.message || 'Project process request failed (' + res.statusCode + ')'), { code: detail?.code });
  });
  return { request, response };
}

function open(endpoint, spec, terminal, lease) {
  const id = randomUUID();
  const ready = deferred(), direct = deferred(), range = deferred();
  const streams = Object.create(null);
  let controlOutput;
  let completed = false, exited = false;
  const remoteSpec = { argv: spec.argv, cwd: spec.cwd, env: projectEnv(spec.cwd, spec.env), graceMs: spec.graceMs };
  if (endpoint.timeZone && remoteSpec.env.TZ === undefined) remoteSpec.env.TZ = endpoint.timeZone;
  // Inject after scrubbing caller-controlled env. Only this process's device lease is forwarded.
  if (lease) Object.assign(remoteSpec.env, lease.env);
  if (terminal) Object.assign(remoteSpec, { cols: spec.cols, rows: spec.rows, terminalType: spec.terminalType, shellActivity: spec.shellActivity });
  else remoteSpec.stdio = { stdin: spec.stdio.stdin === 'ignore' ? 'ignore' : 'pipe',
    stdout: 'pipe', stderr: 'pipe', control: spec.stdio.control };
  const transport = post(endpoint, '/process/start', {id, spec: remoteSpec, terminal});
  async function op(body) {
    await ready.promise;
    const response = await post(endpoint, '/process/op', { id, ...body }).response;
    return (await responseJson(response)).result;
  }
  async function send(name, bytes) {
    for (let offset = 0; offset < bytes.length; offset += 65536) {
      await op({ op: 'data', name, data: bytes.subarray(offset, offset + 65536).toString('base64') });
    }
  }
  function input(name) {
    const options = {
      write(chunk, encoding, cb) { send(name, Buffer.from(chunk, encoding)).then(() => cb(), cb); },
      final(cb) { op({ op:'end', name }).then(() => cb(), cb); }
    };
    const stream = new Writable(options);
    // A remote child is allowed to close its stdin/control pipe at any time.
    stream.on('error', () => {});
    return stream;
  }
  if (terminal) streams.output = new PassThrough();
  else {
    if (spec.stdio.stdin !== 'ignore') streams.stdin = input('stdin');
    for (const name of ['stdout', 'stderr']) {
      streams[name] = new PassThrough();
      if (spec.stdio[name] === 'inherit') streams[name].pipe(process[name], {end:false});
    }
    if (spec.stdio.control === 'pipe') {
      controlOutput = new PassThrough();
      streams.control = Duplex.from({ writable:input('control'), readable:controlOutput });
      streams.control.on('error', () => {});
    }
  }
  const end = name => {
    const stream = streams[name];
    if (!stream || stream.destroyed) return;
    if (name === 'control') { if (!controlOutput.writableEnded) controlOutput.end(); }
    else if (!stream.writableEnded) stream.end();
  };
  const close = () => {
    for (const name of ['stdout', 'stderr', 'output', 'control']) end(name);
  };
  async function listen() {
    const res = await transport.response;
    try {
      for await (const f of events(res)) {
        if (f.type === 'start') ready.resolve(f.pid);
        else if (f.type === 'data') {
          if (!['output','stdout','stderr','control'].includes(f.name)) throw Error('Unknown project pipe');
          const stream = f.name === 'control' ? controlOutput : streams[f.name];
          const bytes = Buffer.from(f.data, 'base64');
          if (!stream || stream.destroyed) continue;
          if (!stream.write(bytes)) await once(stream, 'drain');
        } else if (f.type === 'end') end(f.name);
        else if (f.type === 'exit') { lease?.revoke(); exited = true; direct.resolve(f.outcome); }
        else if (f.type === 'range') { completed = true; close(); range.resolve(true); return; }
        else if (f.type === 'error') throw Object.assign(Error(f.message), { code: f.code });
      }
      throw Error('Project process connection closed');
    } finally {
      res.destroy();
    }
  }
  listen().catch(error => {
    completed = true;
    exited = true;
    close();
    ready.reject(error); direct.reject(error); range.reject(error);
  }).finally(() => lease?.revoke());
  return { streams, ready:ready.promise, direct:direct.promise, range:range.promise, op,
    get completed() { return completed; }, get exited() { return exited; },
    disconnect() { lease?.revoke(); transport.request.destroy(); }
  };
}

exports.spawn = (endpoint, spec, lease) => {
  validate(spec);
  const p = open(endpoint, spec, false, lease);
  return bind(spec, {
    stdin: p.streams.stdin ?? null, stdout:p.streams.stdout, stderr:p.streams.stderr, control:p.streams.control,
    direct:p.direct, owner: {
      waitForExit:() => p.range,
      signal(signal) {
        lease?.revoke();
        if (!p.completed) p.op({ op: signal === 'SIGKILL' ? 'kill' : 'terminate' }).catch(() => {});
      },
      terminateForHostExit: () => p.disconnect()
    }
  });
};

exports.spawnTerminal = async (endpoint, spec, lease) => {
  spec.signal?.throwIfAborted();
  const p = open(endpoint, spec, true, lease);
  const abort = () => p.disconnect();
  spec.signal?.addEventListener('abort', abort, {once:true});
  let pid;
  try { pid = await p.ready; }
  finally { spec.signal?.removeEventListener('abort', abort); }
  const op = (name, ...args) => p.op({op:name, args});
  let writes = Promise.resolve();
  function ordered(name, ...args) {
    const next = writes.then(() => op(name, ...args));
    writes = next.catch(() => {});
    return next;
  }
  return { pid, output:p.streams.output, done:p.direct, get running() { return !p.exited; },
    write: data => ordered('write', data), resize: (cols, rows) => ordered('resize', cols, rows),
    inspectActivity: () => p.completed ? Promise.resolve({state:'idle',revision:Number.MAX_SAFE_INTEGER}) : op('inspectActivity'),
    inspectForeground: () => p.completed ? Promise.resolve(undefined) : op('inspectForeground'),
    signalForeground: signal => op('signalForeground', signal),
    async terminate() { lease?.revoke(); if (!p.completed) await op('terminate').catch(err => { if (!p.exited) throw err; }); await p.range; },
    terminateForHostExit: () => p.disconnect()
  };
};
