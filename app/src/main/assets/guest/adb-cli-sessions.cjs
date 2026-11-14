'use strict';

const http = require('node:http');
const {createHmac, randomBytes, timingSafeEqual} = require('node:crypto');
const capability = () => randomBytes(32).toString('base64url');

// Only the DSH control process holds the callback credential and conversation objects.
// Project children receive one bearer for the lifetime of their Bash or PTY.
module.exports.create = function create(management, authorizeOwner) {
  const tickets = new Map();
  let service, starting, retryAfter = 0;

  async function listen() {
    const key = capability();
    const mac = text => createHmac('sha256',key).update(text).digest('hex');
    const server = http.createServer({requestTimeout:5000, headersTimeout:4000, maxHeaderSize:4096}, async (req, res) => {
      const reply = (code, body, nonce) => {
        const text = JSON.stringify(body);
        const headers = {'Content-Type':'application/json', 'Cache-Control':'no-store', Connection:'close'};
        if (nonce) headers['X-Sugar-Validator-Proof'] = mac('response\n' + nonce + '\n' + text);
        res.writeHead(code, headers);
        res.end(text);
      };
      const auth = req.headers.authorization || '';
      if (req.method !== 'POST' || req.url !== '/authorize' || req.headers.origin !== undefined ||
          !/^Sugar-HMAC [0-9a-f]{64}$/.test(auth)) {
        return reply(403,{ok:false});
      }
      const size = Number(req.headers['content-length']);
      if (!Number.isInteger(size) || size < 1 || size > 512 || req.headers['transfer-encoding'] ||
          req.headers['content-type']?.split(';')[0] !== 'application/json') return reply(400,{ok:false});
      let nonce;
      try {
        const chunks = [];
        for await (const chunk of req) chunks.push(chunk);
        const text = Buffer.concat(chunks).toString('utf8');
        if (!timingSafeEqual(Buffer.from(auth.slice(11)), Buffer.from(mac('request\n' + text)))) return reply(403,{ok:false});
        const request = JSON.parse(text);
        if (typeof request.nonce !== 'string' ||
            !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(request.nonce)) return reply(400,{ok:false});
        nonce = request.nonce;
        const {token, needGuide} = request;
        const ticket = tickets.get(token);
        if (!ticket || typeof needGuide !== 'boolean') throw Error('本次终端的 ADB 凭据已失效。');
        const ui = request.ui === true;
        const route = authorizeOwner(ticket.owner, ticket.project, needGuide, ui);
        ticket.used = true;
        const result = {id:ticket.project.id,root:ticket.project.root};
        if (ui) result.route = route;
        if (request.mobile === true || ui) {
          result.conversation = ticket.owner.agent.id;
          result.manual = ticket.owner.manual === true;
          result.taskId = ticket.taskId;
        }
        reply(200,{ok:true,result},nonce);
      } catch (error) {
        reply(200,{ok:false,error:{message:String(error.message).slice(0,200)}},nonce);
      }
    });
    server.maxConnections = 32;
    server.setTimeout(5000);
    await new Promise((resolve,reject) => {
      server.once('error',reject);
      server.listen(0,'127.0.0.1',() => { server.off('error',reject); resolve(); });
    });
    try {
      const {port} = await management({op:'adbCliReady',port:server.address().port,token:key}, AbortSignal.timeout(5000));
      if (!Number.isInteger(port) || port < 1 || port > 65535) throw Error('Invalid ADB broker port');
      server.unref();
      service = {server,port};
    } catch (error) { server.close(); throw error; }
  }
  function start() {
    if (service || Date.now() < retryAfter) return Promise.resolve();
    if (!starting) starting = listen().catch(() => {
      retryAfter = Date.now() + 5000;
      console.warn('DSH Sugar: cannot register the ADB CLI session verifier');
    }).finally(() => { starting = undefined; });
    return starting;
  }
  function issue(owner, project, taskId = '') {
    if (!service || !owner || tickets.size >= 2048) return undefined;
    const token = capability();
    const ticket = {owner,project,taskId,used:false};
    ticket.revoke = () => {
      if (!tickets.delete(token) || !ticket.used) return;
      // Unused leases never reached an Android stream. A used lease can have
      // an in-flight transfer even when the native client closes abruptly.
      const notify = () => management({op:'adbCliRevoke',token}, AbortSignal.timeout(3000));
      notify().catch(() => notify().catch(() => {}));
    };
    tickets.set(token,ticket);
    return {env:{SUGAR_ADB_PORT:String(service.port), SUGAR_ADB_TOKEN:token}, revoke:ticket.revoke};
  }
  function revokeProject(id) {
    for (const ticket of tickets.values()) if (ticket.project.id === id) ticket.revoke();
  }
  return {start, issue, revokeProject};
};
