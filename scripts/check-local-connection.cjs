#!/usr/bin/env node
'use strict';

// Run against the exact client.browser.js extracted from the candidate rootfs.zip.
// The complete upstream loader and ConnectionController run unchanged in a VM;
// only the browser's network events and the Host generation source are simulated.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const input = process.argv[2];
if (!input) {
  console.error('Usage: node scripts/check-local-connection.cjs <packaged client.browser.js>');
  process.exit(2);
}
const source = fs.readFileSync(path.resolve(input), 'utf8');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(message, predicate, timeout = 1500) {
  const deadline = Date.now() + timeout;
  do {
    if (predicate()) return;
    await delay(5);
  } while (Date.now() < deadline);
  assert.ok(predicate(), message);
}

function createHost(url, online, readyOnAttempt = () => true) {
  const listeners = new Map();
  const browser = {
    location: new URL(url), navigator: {onLine: online},
    addEventListener(type, fn) {
      if (!listeners.has(type)) listeners.set(type, new Set());
      listeners.get(type).add(fn);
    },
    removeEventListener(type, fn) { listeners.get(type)?.delete(fn); },
  };
  const logs = [];
  let exports;
  browser.__ModuleLoader__ = {load(module) {
    assert.equal(module.id, '@deepseek-ai/dsh-client-connection');
    exports = module.factory(name => { throw Error('Unexpected loader dependency: ' + name); });
  }};
  vm.runInNewContext(source, {
    window: browser, location: browser.location, URL, AbortController, setTimeout, clearTimeout,
    console: {warn: (...args) => logs.push(args.join(' ')), error: (...args) => logs.push(args.join(' '))},
  }, {filename: input, timeout: 5000});
  assert.equal(typeof exports?.installConnection, 'function', 'Real upstream connection export is required');

  let connection;
  exports.installConnection({provide(name, value) {
    assert.equal(name, 'connection'); connection = value;
  }}, {
    location: browser.location,
    transport: {rpc: {}},
    recovery: {backoffBaseMs: 10, backoffMaxMs: 20, backoffFactor: 2,
      generationReadyWarnMs: 30, generationReadyTimeoutMs: 90},
  });
  const attempts = [], states = [];
  let active = 0, maxActive = 0;
  const unregister = connection.registerGenerationSource((signal, ready) => new Promise(resolve => {
    const attempt = {signal, aborted: false, end: null};
    attempts.push(attempt); active++; maxActive = Math.max(maxActive, active);
    let ended = false;
    const end = () => {
      if (ended) return;
      ended = true; active--; signal.removeEventListener('abort', abort); resolve();
    };
    const abort = () => {attempt.aborted = true; end();};
    attempt.end = end;
    signal.addEventListener('abort', abort, {once: true});
    if (signal.aborted) abort();
    else if (readyOnAttempt(attempts.length)) ready({home: '/home/sugar'});
  }));
  const running = connection.start({onStateChange: state => states.push(state)});
  return {
    connection, attempts, states, logs,
    get maxActive() { return maxActive; },
    get state() { return connection.state.getSnapshot(); },
    network(value) {
      browser.navigator.onLine = value;
      for (const fn of [...(listeners.get(value ? 'online' : 'offline') || [])]) fn();
    },
    async close() {
      running.stop(); unregister();
      await until('Stopped source must release resources', () => active === 0);
      assert.equal(connection.generation.getSnapshot(), undefined);
      assert.equal([...listeners.values()].reduce((sum, set) => sum + set.size, 0), 0,
        'Browser listeners must be removed when the connection stops');
      assert.ok(maxActive <= 1, 'Reconnects must never overlap source generations');
    },
  };
}

async function check(name, run) {
  await run(); console.log('PASS: ' + name);
}

async function main() {
  await check('Local Host connects with an initially offline browser', async () => {
    const h = createHost('http://127.0.0.1:39801/', false);
    try {
      await until('Local offline startup must reach connected', () => h.state === 'connected');
      assert.equal(h.attempts.length, 1);
      h.network(true); h.network(false);
      await delay(60);
      assert.equal(h.state, 'connected');
      assert.equal(h.attempts.length, 1, 'Network events must not tear down a healthy local generation');
      assert.equal(h.attempts[0].aborted, false);
    } finally { await h.close(); }
  });

  await check('Real local connection loss retries while the browser remains offline', async () => {
    const h = createHost('http://127.0.0.1:39801/', false);
    try {
      await until('Initial local connection', () => h.state === 'connected');
      const generation = h.connection.generation.getSnapshot().id;
      h.attempts[0].end();
      await until('Actual local loss must automatically reconnect', () =>
        h.state === 'connected' && h.connection.generation.getSnapshot()?.id > generation);
      assert.equal(h.attempts.length, 2);
      assert.ok(h.states.includes('connecting'), 'Real failures must still be visible');
    } finally { await h.close(); }
  });

  await check('Local readiness timeout cancels the failed source then recovers', async () => {
    const h = createHost('http://127.0.0.1:39801/', false, attempt => attempt > 1);
    try {
      await until('Handshake deadline must allow a replacement source', () => h.state === 'connected');
      assert.equal(h.attempts.length, 2);
      assert.equal(h.attempts[0].aborted, true);
      assert.ok(h.logs.some(line => line.includes('cancelling generation')), 'Actual handshake timeout must be logged');
    } finally { await h.close(); }
  });

  await check('Explicit reconnect still replaces the local generation', async () => {
    const h = createHost('http://127.0.0.1:39801/', false);
    try {
      await until('Initial local connection', () => h.state === 'connected');
      const generation = h.connection.generation.getSnapshot().id;
      h.connection.reconnect();
      await until('Manual reconnect must complete', () =>
        h.state === 'connected' && h.connection.generation.getSnapshot()?.id > generation);
      assert.equal(h.attempts[0].aborted, true);
      assert.equal(h.attempts.length, 2);
    } finally { await h.close(); }
  });

  await check('An unavailable local Host never reports a healthy connection', async () => {
    const h = createHost('http://127.0.0.1:39801/', false, () => false);
    try {
      await until('Unavailable local Host must keep retrying', () => h.attempts.length >= 3);
      assert.equal(h.state, 'connecting');
      assert.equal(h.connection.generation.getSnapshot(), undefined);
      assert.ok(!h.states.includes('connected'), 'Ignoring browser offline must not fake Host readiness');
    } finally { await h.close(); }
  });

  await check('Remote Host pauses offline and resumes automatically online', async () => {
    const h = createHost('https://dsh.example.test/', false);
    try {
      await delay(40);
      assert.equal(h.state, 'disconnected'); assert.equal(h.attempts.length, 0);
      h.network(true);
      await until('Online remote Host must connect', () => h.state === 'connected');
      h.network(false);
      await until('Remote offline event must abort the active source', () => h.attempts[0].aborted);
      assert.equal(h.state, 'disconnected');
      assert.equal(h.connection.generation.getSnapshot(), undefined);
      await delay(70);
      assert.equal(h.attempts.length, 1, 'Offline remote Host must not spin retries');
      h.network(true);
      await until('Remote Host must recover when online', () => h.state === 'connected' && h.attempts.length === 2);
    } finally { await h.close(); }
  });

  await check('The local exception is confined to Sugar HTTP 127.0.0.1 pages', async () => {
    for (const url of ['https://127.0.0.1:39801/', 'http://localhost:39801/',
      'http://127.0.0.10:39801/', 'http://127.0.0.1.evil:39801/', 'http://192.168.1.2:39801/']) {
      const h = createHost(url, false);
      try {
        await delay(25);
        assert.equal(h.state, 'disconnected', url);
        assert.equal(h.attempts.length, 0, url);
      } finally { await h.close(); }
    }
  });
}
main().catch(error => { console.error(error.stack); process.exitCode = 1; });
