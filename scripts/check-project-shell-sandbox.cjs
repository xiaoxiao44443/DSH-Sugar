'use strict';

// Node 24+. This host check exercises DSH's real provider and executor with
// injected platform probe results. Android exercises the ARM64 probe binary.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { registerHooks } = require('node:module');
const { createHash } = require('node:crypto');

async function main() {
  const packages = path.resolve(process.argv[2]);
  const assets = process.argv[3] || path.join(__dirname, '../app/src/main/assets/guest');
  const recipes = require(path.join(assets, 'project-hooks.json'));
  assert.ok(!Object.hasOwn(recipes, 'dsh-sandbox-local/lib/index.js'));
  const filename = name => pathToFileURL(path.join(packages, name, 'lib/index.js')).href;
  const target = filename('dsh-subprocess-local');
  registerHooks({load(url, ctx, next) {
    const original = next(url, ctx);
    if (url !== target) return original;
    const recipe = recipes['dsh-subprocess-local/lib/index.js'];
    let source = Buffer.from(original.source).toString('utf8');
    assert.equal(createHash('sha256').update(source).digest('hex'), recipe.hash);
    for (const [before, after] of recipe.edits) {
      assert.equal(source.split(before).length, 2);
      source = source.replace(before, () => after.replaceAll('SUGAR_HOOK', 'globalThis.sugarTest'));
    }
    return {...original, source};
  }});

  const { LocalSandboxProvider } = await import(filename('dsh-sandbox-local'));
  const { LocalSubprocessRuntime } = await import(target);
  const { SandboxBashExecutor } = await import(filename('dsh-bash-sandbox'));
  const { grantArgs } = await import(filename('node-addon-system'));
  const launcher = '/test/landlock-run';
  const project = {id:'test-project'};
  const command = 'pwd && ls "/workspace/a b"';
  const bash = ['bash', '-c', command];
  const forwarded = [];
  const ctx = {
    reflect:{provide(name, svc) { ctx[name] = svc; }}, effect() {}, inject() {},
    sandboxPolicy:{defaultMode:'workspace-write'}
  };
  globalThis.sugarTest = {
    current: () => project,
    spawn(ep, spec, terminal) {
      assert.equal(ep, project);
      assert.equal(terminal, false);
      assert.equal(spec.cwd, '/workspace');
      forwarded.push([...spec.argv]);
      const reader = {readFrom: () => ({text:'', nextOffset:0, lossy:false})};
      return {collected:{stdout:reader, stderr:reader}, done:Promise.resolve({exitCode:0, signal:null})};
    }
  };
  const probes = [];
  function configure(verdict) {
    const provider = new LocalSandboxProvider(ctx, {runnerCommand:[], runnerFailureSignatures:[], probeTimeoutMs:2000});
    provider.internals = {
      platform:'linux', landlockLauncher:launcher,
      probeBwrap: () => { probes.push('bwrap'); return false; },
      probeLandlock: binary => { probes.push(binary); return verdict; }
    };
  }
  configure('partial');
  ctx.subprocess = Object.create(LocalSubprocessRuntime.prototype);
  const setting = {get:() => 1024};
  const shell = new SandboxBashExecutor(ctx, {maxSpillBytes:setting, maxOutputBytes:setting, graceMs:setting});
  const run = async mode => (await shell.execute({command, workdir:'/workspace', stdoutMaxBytes:1024,
    onExpiry:'none', sandboxPolicy:{mode, workspaceRoot:'/workspace'}})).result();
  for (const [mode, write] of [['read-only',['/dev/null']], ['workspace-write',['/dev/null','/tmp','/workspace']]]) {
    assert.deepEqual((await run(mode)).sandbox, {mode, denied:false, enforcement:'partial'});
    assert.deepEqual(forwarded.pop(), [launcher, ...grantArgs({readOnly:['/'], readWrite:write}), '--', ...bash]);
  }
  assert.deepEqual(probes, ['bwrap', launcher]);
  await run('danger-full-access');
  assert.deepEqual(forwarded.pop(), bash);
  configure('unusable');
  await assert.rejects(run('workspace-write'), {code:'SANDBOX_UNAVAILABLE'});
  assert.deepEqual(forwarded, []);
  console.log('Original DSH Linux provider and project transport: probe order, exact Landlock argv and unavailable runner passed.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
