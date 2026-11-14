'use strict';

// The upstream web profile announces its authenticated URL through console.log.
// Deliver that one line directly to this launch's private manager handoff file
// before Sucrune's persistent stdout capture can see the process token.
const fs = require('node:fs');
const { isMainThread } = require('node:worker_threads');
// Node also runs --require in worker threads and forked Node entrypoints.
if (!isMainThread || process.argv[1] !== '/opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js') return;
const handoff = process.env.SUGAR_HANDOFF;
if (!/^\/tmp\/sugar-auth-[0-9a-f]{32}\.url$/.test(handoff || '')) {
  throw new Error('DSH Sugar requires a per-launch browser handoff');
}
delete process.env.SUGAR_HANDOFF;
const log = console.log.bind(console);
console.log = (...args) => {
  if (args.length !== 1 || typeof args[0] !== 'string' || !args[0].startsWith('dsh web: http')) {
    return log(...args);
  }
  const authUrl = args[0].slice('dsh web: '.length);
  const match = /^http:\/\/127\.0\.0\.1:([1-9]\d{0,4})\/\?token=[A-Za-z0-9_-]{43}$/.exec(authUrl);
  if (!match || Number(match[1]) > 65535) throw new Error('Unexpected Harness browser handoff format');
  const stage = handoff + '.tmp';
  fs.writeFileSync(stage, authUrl, { flag: 'wx', mode: 0o600 });
  fs.renameSync(stage, handoff);
  log('dsh web: local browser session ready');
};
