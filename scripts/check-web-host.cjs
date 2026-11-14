'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const {test} = require('node:test');

test('only the web host loads Sugar hooks when its CLI is reinvoked', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'sugar-web-host-'));
  t.after(() => fs.rmSync(root, {recursive: true, force: true}));
  const sugar = path.join(root, 'sugar');
  const dsh = path.join(root, 'dsh/node_modules/@deepseek-ai/dsh');
  fs.mkdirSync(sugar);
  fs.mkdirSync(path.join(dsh, 'lib'), {recursive: true});
  const launcher = path.join(sugar, 'web-host.cjs');
  fs.copyFileSync(path.join(__dirname, '../app/src/main/assets/guest/web-host.cjs'), launcher);
  fs.writeFileSync(path.join(sugar, 'browser-handoff.cjs'), `
    if (!process.env.QA_HANDOFF) throw new Error('missing web handoff');
    delete process.env.QA_HANDOFF;
    globalThis.handoffLoaded = true;
  `);
  fs.writeFileSync(path.join(sugar, 'project-routing.cjs'), 'globalThis.routingLoaded = true;');
  fs.writeFileSync(path.join(dsh, 'package.json'), '{"type":"module"}');
  fs.writeFileSync(path.join(dsh, 'lib/bin.js'), `
    import assert from 'node:assert/strict';
    import {fileURLToPath} from 'node:url';
    import {spawnSync} from 'node:child_process';
    import {Worker} from 'node:worker_threads';
    import {once} from 'node:events';
    assert.equal(process.argv[1], fileURLToPath(import.meta.url));
    assert.equal(import.meta.main, true);
    assert.deepEqual(process.execArgv, ['--no-warnings']);
    assert.equal(process.env.QA_HANDOFF, undefined);
    if (process.argv[2] === 'plugin') {
      assert.equal(globalThis.handoffLoaded, undefined);
      assert.equal(globalThis.routingLoaded, undefined);
      console.log('child ok');
    } else {
      assert.deepEqual(process.argv.slice(2), ['web', '--no-open']);
      assert.equal(globalThis.handoffLoaded, true);
      assert.equal(!!globalThis.routingLoaded, process.env.QA_MODE === 'main');
      const child = spawnSync(process.execPath, [...process.execArgv, process.argv[1], 'plugin'], {encoding: 'utf8'});
      assert.equal(child.status, 0, child.stderr);
      assert.equal(child.stdout.trim(), 'child ok');
      const worker = new Worker("require('node:worker_threads').parentPort.postMessage(!!globalThis.handoffLoaded)", {eval: true});
      assert.deepEqual(await once(worker, 'message'), [false]);
      console.log('host ok');
    }
  `);
  for (const mode of ['main', 'recovery']) {
    const args = ['--no-warnings', launcher, mode, 'web', '--no-open'];
    const env = {...process.env, QA_MODE: mode, QA_HANDOFF: 'once'};
    const result = spawnSync(process.execPath, args, {env, encoding: 'utf8'});
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout.trim(), 'host ok');
    delete env.QA_HANDOFF;
    const missing = spawnSync(process.execPath, args, {env, encoding: 'utf8'});
    assert.equal(missing.status, 1);
    assert.match(missing.stderr, /missing web handoff/);
  }
});
