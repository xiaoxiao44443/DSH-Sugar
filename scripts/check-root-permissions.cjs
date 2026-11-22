'use strict';
const assert = require('node:assert/strict');
const {pathToFileURL} = require('node:url');
const path = require('node:path');
const {readFile} = require('node:fs/promises');

async function main() {
  const {authorizeRoot} = await import(pathToFileURL(path.resolve(__dirname,
    '../app/src/main/assets/guest/dsh-sugar-adb/root-permissions.js')));
  const events = [{type:'turn/start'}];
  const agent = {id:'original',session:{get seq(){return events.length;},eventAt:i=>events[i]}};
  let preset = 'workspace-write', asked = [], answer = 'allowed-once';
  const ctx = {
    permissionPresets:{current(session){assert.equal(session,agent.session);return preset;}},
    approval:{async request(request){asked.push(request);return typeof answer === 'function' ? answer(request) : answer;}}
  };
  const command = "printf '%s\\n' 'first'\nprintf '%s\\n' 'second'";
  const run = (signal = new AbortController().signal, manual = false) => authorizeRoot(ctx,agent,command,signal,manual);
  await run();
  assert.equal(asked.length,1);
  assert.equal(asked[0].agent,agent);
  assert.equal(asked[0].toolName,'sugar-root');
  assert(asked[0].reason.endsWith(command));
  for (const outcome of ['rejected','cancelled','unavailable',undefined]) {
    answer = outcome;
    await assert.rejects(run());
  }
  preset = 'danger-full-access';
  const count = asked.length;
  await run();
  assert.equal(asked.length,count);
  // Auto's sandbox can also be danger-full-access. It is a different preset,
  // and a delegated Auto/never session's rejection is still final.
  preset = 'auto'; answer = 'rejected';
  await assert.rejects(run(),/未获批准/);
  assert.equal(asked.length,count+1);
  preset = 'custom'; answer = 'allowed-once';
  await run();
  preset = 'workspace-write';
  let resolveApproval;
  answer = () => new Promise(resolve => {resolveApproval = resolve;});
  let finished = false;
  const waiting = run().then(() => {finished = true;});
  await new Promise(resolve=>setTimeout(resolve,20));
  assert.equal(finished,false);
  resolveApproval('allowed-once'); await waiting;
  answer = () => {preset = 'read-only';return 'allowed-once';};
  await assert.rejects(run(),/权限已改变/);
  preset = 'workspace-write';
  answer = () => {events.push({type:'turn/end'});return 'allowed-once';};
  await assert.rejects(run(),/轮次或权限/);
  await assert.rejects(run(),/仍在进行/);
  const manualCount = asked.length;
  await run(new AbortController().signal,true);
  assert.equal(asked.length,manualCount);
  const cancelled = new AbortController(); cancelled.abort(Error('cancelled'));
  await assert.rejects(run(cancelled.signal,true),/cancelled/);
  events.push({type:'turn/start'});
  const during = new AbortController();
  answer = () => {during.abort(Error('cancelled'));return 'allowed-once';};
  await assert.rejects(run(during.signal),/cancelled/);
  // Source bindings protect the approval-time admission boundary; Native's gate
  // tests cover the generation and active-command behavior itself.
  const cli = await readFile(path.resolve(__dirname,
    '../app/src/main/kotlin/app/dsh/sugar/runtime/ProjectAdbCli.kt'), 'utf8');
  const rootAdmission = cli.indexOf('val rootAdmission = if (op == "root-exec") MobileUseTasks.captureRootAdmission() else null');
  const uiAdmission = cli.indexOf('val uiAdmission = if (inspection?.action != null) MobileUseTasks.captureUiAdmission() else null');
  const authorize = cli.indexOf('val identity = authorize(');
  assert(rootAdmission >= 0 && rootAdmission < authorize, 'Root admission is captured before approval');
  assert(uiAdmission >= 0 && uiAdmission < authorize, 'ADB UI admission is captured before approval');
  assert.match(cli, /output\.exit\(MobileUseTasks\.withRootCommand\(checkNotNull\(rootAdmission\)\)\s*\{\s*rootAccess\.execOut\(/);
  assert.match(cli, /if \(op == "root-status"\) \{\s*stdout\.write\(\(rootAccess\.status\(\)/);
  assert.match(cli, /MobileUseTasks\.withAdbUiAdmitted\(checkNotNull\(uiAdmission\), actor, identity\.optJSONObject\("route"\), ui, shellCommand, ::execute\)/);
  assert.doesNotMatch(cli, /MobileUseTasks\.withAdbUi\(/);
  // The service is a trusted host integration. These tests deliberately do not
  // claim resistance to a host plugin replacing ctx or editing shared .dsh.
  console.log('PASS Root exact-command approval, wait, deny/cancel/unavailable, full vs Auto, turn/preset changes, manual terminal and early Root/ADB takeover admission');
}
main().catch(error=>{console.error(error);process.exitCode=1;});
