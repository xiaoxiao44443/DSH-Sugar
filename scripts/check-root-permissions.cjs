'use strict';
const assert = require('node:assert/strict');
const {pathToFileURL} = require('node:url');
const path = require('node:path');

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
  // The service is a trusted host integration. These tests deliberately do not
  // claim resistance to a host plugin replacing ctx or editing shared .dsh.
  console.log('PASS Root exact-command approval, wait, deny/cancel/unavailable, full vs Auto, turn/preset changes and manual terminal');
}
main().catch(error=>{console.error(error);process.exitCode=1;});
