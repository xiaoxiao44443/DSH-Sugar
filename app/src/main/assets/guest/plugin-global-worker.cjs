'use strict';

const http = require('node:http');
const { timingSafeEqual } = require('node:crypto');
const { readdir } = require('node:fs/promises');

/** The native execution has an absent /workspace provider, inherited by every child. */
function createService(secret, processes, { idleLimit = 30 * 60 * 1000, idleInterval = 30000,
  census = () => readdir('/proc') } = {}) {
  if (typeof secret !== 'string' || secret.length < 32) throw Error('Missing global plugin capability');
  const auth = Buffer.from('Bearer ' + secret);
  let requests = 0, lastActivity = Date.now(), checkingIdle = false;
  const server = http.createServer(async (req, res) => {
    const supplied = Buffer.from(req.headers.authorization || '');
    if (supplied.length !== auth.length || !timingSafeEqual(supplied, auth)) {
      res.writeHead(403).end(); return;
    }
    const route = (req.url || '').split('?')[0];
    if (req.method !== 'POST' || !['/process/start', '/process/op'].includes(route)) {
      res.writeHead(404).end(); return;
    }
    requests++;
    res.once('close', () => { requests--; lastActivity = Date.now(); });
    try {
      await processes.handle(req, res, route);
    } catch (error) {
      if (res.destroyed) return;
      if (res.headersSent) res.destroy();
      else res.writeHead(422, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
        .end(JSON.stringify({ error: { code: error.code || 'PROCESS_IO_ERROR',
          message: String(error.message || 'Global plugin process failed') } }));
    }
  });
  server.headersTimeout = 15000;
  server.requestTimeout = 30000;
  const idle = setInterval(async () => {
    const now = Date.now();
    if (requests || processes.hasActive()) lastActivity = now;
    if (now - lastActivity < idleLimit || checkingIdle) return;
    checkingIdle = true;
    try {
      // SUCRUNE_PROCESS_SCOPE=project gives this execution its own descendant census,
      // including daemonized children whose original HTTP request has completed.
      const pids = await census();
      if (pids.some(name => /^\d+$/.test(name) && name !== String(process.pid))) {
        lastActivity = Date.now();
      } else if (!requests && !processes.hasActive() && Date.now() - lastActivity >= idleLimit) {
        clearInterval(idle);
        server.close();
        server.closeIdleConnections();
      }
    } catch {
      // An unavailable census is not evidence that a global model has stopped.
    } finally {
      checkingIdle = false;
    }
  }, idleInterval);
  idle.unref();
  server.once('close', () => clearInterval(idle));
  return server;
}

function main() {
  const secret = process.env.SUGAR_PLUGIN_GLOBAL_TOKEN;
  delete process.env.SUGAR_PLUGIN_GLOBAL_TOKEN;
  if (!process.argv.includes('--sugar-plugin-global')) throw Error('Missing global plugin worker mode');
  const processes = require('./project-process-worker.cjs');
  const server = createService(secret, processes);
  server.listen(0, '127.0.0.1', () =>
    process.stdout.write('SUGAR_PLUGIN_GLOBAL_PORT=' + server.address().port + '\n'));
}

exports.createService = createService;
if (require.main === module) {
  try { main(); }
  catch { process.stderr.write('Could not start global plugin process service\n'); process.exitCode = 1; }
}
