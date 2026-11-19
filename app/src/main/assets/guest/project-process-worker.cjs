'use strict';

const path = require('node:path');
const { execFile } = require('node:child_process');
const { once } = require('node:events');
const { setTimeout: delay } = require('node:timers/promises');
const live = new Map();
const library = process.argv.includes('--sugar-plugin-global')
  ? '/opt/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/lib'
  : path.join(__dirname, 'node_modules/@deepseek-ai/dsh-subprocess-local/lib');
const terminalRuntime = { internals: {}, terminals: new Set(), selectContainmentMode: () => 'fallback' };

// The manager supplies the same pinned launcher image to this worker and its children.
// Probe once, even with concurrent first commands. On an unknown result the launcher
// keeps its normal diagnostics. The emulated ABI is fixed for the worker's lifetime.
const sandboxLauncher = process.argv[2];
let probeResult;
function probeEnforcement() {
  return new Promise(resolve => {
    execFile(sandboxLauncher, ['--probe'], { timeout: 3000, killSignal: 'SIGKILL', maxBuffer: 4096 },
      (error, stdout) => {
        const status = stdout.trim();
        const partial = !error && status === 'landlock: partially enforced (older ABI)';
        if (partial || (!error && status === 'landlock: fully enforced')) {
          process.stderr.write('[Sugar] ' + status + '\n');
        }
        resolve(partial);
      });
  });
}

async function start(req, res, request) {
  const { id, spec, terminal } = request;
  if (typeof id !== 'string' || !/^[0-9a-f-]{36}$/.test(id) || live.has(id)) throw Error('Invalid or reused process ID');
  if (!Array.isArray(spec?.argv) || !spec.argv.length || !spec.argv.every(a => typeof a === 'string') ||
      typeof spec.cwd !== 'string' || !spec.cwd.startsWith('/')) throw Error('Invalid project process specification');
  const session = { terminal, handle: null, stopping: false };
  live.set(id, session);
  let finished = false;
  const stop = () => {
    if (!finished && session.handle) { session.stopping = true; session.handle.terminateForHostExit(); }
  };
  res.once('close', stop);
  try {
    if (spec.argv[0] === sandboxLauncher && spec.argv.includes('--')) {
      probeResult ??= probeEnforcement();
      if (await probeResult) spec.argv.splice(1, 0, '--sugar-reported-partial');
      if (res.destroyed) return;
    }
    const handle = terminal
      ? await require(path.join(library, 'index.js')).LocalSubprocessRuntime.prototype.spawnTerminal.call(terminalRuntime, spec)
      : require(path.join(library, 'runner-launch-CKTiL8M8.js')).T(spec);
    session.handle = handle;
    if (res.destroyed) { stop(); return; }
    res.writeHead(200, { 'Content-Type': 'application/x-ndjson', 'Cache-Control': 'no-store' });
    const frame = event => res.write(JSON.stringify(event) + '\n');
    frame({ type: 'start', pid: handle.pid });
    const output = terminal ? ['output'] : ['stdout', 'stderr', 'control'];
    // Respect socket backpressure independently for each readable pipe. The default pipe
    // buffer remains bounded even while a client is suspended by Android.
    for (const name of output) {
      const stream = handle[name];
      if (!stream) continue;
      stream.on('data', chunk => {
        if (res.destroyed || res.writableEnded) return;
        if (!frame({ type: 'data', name, data: Buffer.from(chunk).toString('base64') })) {
          stream.pause();
          res.once('drain', () => stream.resume());
        }
      });
      let ended = false;
      const end = () => {
        if (!ended && !res.destroyed && !res.writableEnded) frame({ type: 'end', name });
        ended = true;
      };
      stream.once('end', end);
      stream.once('close', end);
      stream.on('error', end);
    }
    const outcome = await handle.done;
    if (!res.destroyed) frame({ type: 'exit', outcome });
    // An interactive shell may exit with background children still doing project work.
    if (terminal && spec.shellActivity) {
      while (!res.destroyed && (await handle.inspectActivity()).state !== 'idle') await delay(500);
    }
    if (terminal) await handle.terminate();
    else if (session.stopping) await handle.waitForExit();
    else {
      // The shell has exited. Its surviving jobs now belong to this project,
      // and may outlive the HTTP exchange (including the initiating chat).
      for (const stream of [handle.stdin, handle.stdout, handle.stderr, handle.control]) stream?.destroy();
    }
    finished = true;
    if (!res.destroyed) res.end(JSON.stringify({ type: 'range' }) + '\n');
  } catch (error) {
    stop();
    if (!res.headersSent) throw error;
    if (!res.destroyed) res.end(JSON.stringify({ type: 'error', message: error.message, code: error.code }) + '\n');
  } finally {
    res.off('close', stop);
    live.delete(id);
  }
}

async function operation({ id, op, name, data, args = [] }) {
  const entry = live.get(id);
  if (!entry?.handle) throw Error('Project process has exited');
  const h = entry.handle;
  if (op === 'terminate' || op === 'kill') entry.stopping = true;
  if (op === 'terminate') return h.terminate();
  if (op === 'kill') return h.terminateForHostExit();
  if (entry.terminal) {
    if (op === 'inspectActivity' || op === 'inspectForeground') return h[op]();
    if (op === 'write' && typeof args[0] === 'string') return h.write(args[0]);
    if (op === 'resize' && args.length === 2 && args.every(i => Number.isInteger(i) && i > 0)) return h.resize(...args);
    if (op === 'signalForeground' && ['SIGINT', 'SIGTSTP', 'SIGHUP', 'SIGTERM', 'SIGKILL'].includes(args[0])) return h.signalForeground(args[0]);
  } else if ((op === 'data' || op === 'end') && (name === 'stdin' || name === 'control')) {
    const stream = h[name];
    if (!stream || stream.destroyed || stream.writableEnded) throw Error('Project pipe is closed');
    if (op === 'end') { stream.end(); return; }
    if (!stream.write(Buffer.from(data, 'base64'))) await once(stream, 'drain');
    return;
  }
  throw Error('Unsupported project process operation');
}

exports.hasActive = () => live.size > 0;
exports.handle = async (req, res, pathname) => {
  if (req.method !== 'POST' || !['/process/start', '/process/op'].includes(pathname)) { res.writeHead(404).end(); return; }
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 4 * 1024 * 1024) { res.writeHead(413).end(); return; }
    chunks.push(chunk);
  }
  const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  if (pathname === '/process/start') return start(req, res, body);
  const result = await operation(body);
  res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({result}));
};
