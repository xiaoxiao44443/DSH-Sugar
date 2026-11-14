// Loopback-only Messages endpoint used by the on-device recovery smoke test.
const http = require('node:http');
const { randomUUID } = require('node:crypto');

async function respond(request, response) {
  if (request.method !== 'POST' || request.url !== '/v1/messages' ||
      request.headers['x-api-key'] !== 'sugar-local-qa') {
    response.writeHead(403).end();
    return;
  }
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 2 * 1024 * 1024) {
      response.writeHead(413).end();
      return;
    }
    chunks.push(chunk);
  }
  const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  const user = body.messages.filter(message => message.role === 'user').at(-1);
  const content = typeof user?.content === 'string' ? user.content :
    (user?.content || []).map(part => part.text || '').join(' ');
  const match = /SUGAR_DIAG_QA_([12])/.exec(content);
  const reply = match ? 'SUGAR_DIAG_REPLY_' + match[1] : 'Diagnostic session';

  response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' });
  function send(type, fields = {}) {
    response.write('event: ' + type + '\ndata: ' + JSON.stringify({ type, ...fields }) + '\n\n');
  }
  send('message_start', { message: {
    id: 'msg_' + randomUUID(), type: 'message', role: 'assistant', model: body.model, content: [],
    stop_reason: null, usage: { input_tokens: 4, output_tokens: 0 },
  } });
  send('content_block_start', { index: 0, content_block: { type: 'text', text: '' } });
  send('content_block_delta', { index: 0, delta: { type: 'text_delta', text: reply } });
  send('content_block_stop', { index: 0 });
  send('message_delta', { delta: { stop_reason: 'end_turn', stop_sequence: null }, usage: { output_tokens: 4 } });
  send('message_stop');
  response.end();
}

async function start(port = 0) {
  const server = http.createServer((request, response) => {
    respond(request, response).catch(() => {
      if (!response.headersSent) response.writeHead(400);
      response.end();
    });
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => {
      server.off('error', reject);
      resolve();
    });
  });
  return {
    port: server.address().port,
    close: () => new Promise((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve());
      server.closeIdleConnections();
    }),
  };
}

module.exports = { start };
if (require.main === module) {
  start(Number(process.argv[2] || 47189)).then(({ port }) => {
    console.log('Recovery test model listening on loopback port', port);
  }).catch(error => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
