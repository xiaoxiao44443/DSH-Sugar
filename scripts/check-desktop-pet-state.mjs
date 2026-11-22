import assert from 'node:assert/strict';
import { setTimeout as wait } from 'node:timers/promises';
import { apply } from '../app/src/main/assets/guest/dsh-sugar-notifications/index.js';
const make = id => ({ session: { id, header: id === 'child' ? {origin:'subagent'} : {} } });
const a = make('a'), b = make('b'), child = make('child');
const events = new Map(), cleanup = [], trace = [];
let lastSeq = 0, lastReported;
const subscriptions = () => ({
  agents: {list:() => [a,b,child]},
  logger: {warn:message => { throw Error(message); }},
  get: () => undefined,
  on: (key, fn) => events.set(key, fn),
  effect: fn => cleanup.push(fn()),
});
const deliveries = [];
const bridge = {petState: async (state, sessionId, result, phase, caption) => {
  trace.push(state); lastReported = state; deliveries.push({state, sessionId, result, phase, caption});
}, agentNotification: async () => {}};
globalThis[Symbol.for('dsh.sugar.projects')] = bridge;
apply(subscriptions());
const send = (who, type, data) => events.get('session/event')(who.session, {type, data, seq: ++lastSeq});
const start = who => send(who,'turn/start',{});
const end = who => send(who,'turn/end',{reason:{kind:'aborted'}});
const tool = (who,name) => send(who,'assistant/message',{message:{content:[{type:'tool-call', name}]}});
const ack = async (expected, t = 130) => { await wait(t); assert.equal(lastReported,expected); };
start(child); tool(child,'edit'); end(child); await ack(undefined);
start(a); await ack('thinking');
tool(a,'read'); await ack('working');
start(b); await ack('working');
tool(b,'edit'); await ack('coding');
tool(a,'mobile_use'); await ack('coding');
end(b); await ack('working');
send(a,'step/start',{}); await ack('thinking');
const pending = (kind, agent, question) => {
  let resolve;
  const operation = events.get(kind)({agent, questions:[{question}]}, () => new Promise(ok => { resolve=ok; }));
  return async () => { resolve('done'); await operation; };
};
const q1 = pending('approval/request',a, 'Sensitive approval details'), q2 = pending('user-questions/request',b,'Which screen?');
await ack('asking',360);
assert.equal(deliveries.at(-1).sessionId, 'a');
assert.equal(deliveries.at(-1).caption, undefined, 'approval details stay inside the app');
tool(a,'apply_patch'); await ack('asking');
await q1(); await ack('asking');
assert.equal(deliveries.at(-1).sessionId, 'b', 'asking retargets to the remaining request');
assert.equal(deliveries.at(-1).caption, 'Which screen?', 'show the pending question even before the note opens');
await q2(); await ack('coding');
assert.equal(deliveries.at(-1).caption, undefined, 'clear the prompt when the question settles');
tool(a,'mobile_use'); await ack('working');
end(a); await ack('idle');
const instant = await events.get('approval/request')({agent:a},()=>'cached'); assert.equal(instant,'cached');
await ack('idle',350);
start(b); await ack('thinking');
const q3 = pending('user-questions/request',b); await ack('asking',360);
cleanup.pop()(); await ack('idle'); await q3();
assert.deepEqual(trace,['thinking','working','coding','working','thinking','asking','asking','coding','working','idle','thinking','asking','idle']);
console.log('PASS root/child filtering, overlapping root turns, multiple requests/resolution, cleanup and mobile_use without execution. Emitted: '+trace.join(' → '));

// Simulate a live plugin reload while its last bridge request is in flight.
let releaseOld;
let first = true;
bridge.petState = async state => {
  trace.push(state);
  lastReported = state;
  if (first) {
    first = false;
    await new Promise(resolve => { releaseOld = resolve; });
  }
};
apply(subscriptions());
start(a); await ack('thinking');
cleanup.pop()();
apply(subscriptions());
tool(b,'edit'); await wait(160);
assert.equal(trace.at(-1),'thinking','new sender must await the previous bridge request');
releaseOld();
await ack('coding',180);
await ack('coding',180);
console.log('PASS overlapping plugin reload: bridge calls serialized, final state '+lastReported);
cleanup.pop()();
await ack('idle',150);
let fail = [];
bridge.petState = async state => {fail.push(state); throw new Error('lost reply');};
apply(subscriptions()); start(a); await wait(150); cleanup.pop()();
await wait(2800);
assert.deepEqual(fail,['thinking','idle']);
console.log('PASS failed active report on disposal attempts one reset and schedules no further retries');

const reports = [];
bridge.petState = async (state, sessionId, result) => reports.push({state, sessionId, result});
apply(subscriptions());
start(a);
const call = (who, callId, name) => send(who, 'tool/call', {callId, name});
const result = (who, toolCallId, content, isError = false) =>
  send(who, 'tool/result', {message:{role:'tool', toolCallId, content, isError}});
const text = value => ({type:'text', text:value});
call(a, 'same', 'read');
start(b);
call(b, 'same', 'write');
result(a, 'same', [text('private file contents')]);
await wait(140);
assert.deepEqual(reports.at(-1), {state:'coding', sessionId:'b', result:undefined},
  'reading a file does not announce an operation completion or interrupt the visible session');
result(b, 'same', [text('Saved note')]);
await wait(140);
assert.equal(reports.at(-1).result.text, 'Saved note', 'same call ID in two sessions stays isolated');
end(b);
tool(a,'write');
call(a,'123','write');
const beforeOperation = Number(process.hrtime.bigint() / 1000n);
result(a,'123',[text('Saved example.html')]);
const afterOperation = Number(process.hrtime.bigint() / 1000n);
const operationId = 'tool-' + lastSeq;
await wait(140);
assert.deepEqual(reports.at(-1), {state:'coding',sessionId:'a',
  result:{sessionId:'a',id:operationId,kind:'operation',text:'Saved example.html',atMicros:reports.at(-1).result.atMicros}});
assert.ok(reports.at(-1).result.atMicros >= beforeOperation && reports.at(-1).result.atMicros <= afterOperation,
  'a tool result keeps its original event time across bridge batching');
call(a,'124','grep');
const published = reports.filter(item => item.result).length;
result(a,'124',[text('short private matching line')]);
await wait(140);
assert.equal(reports.filter(item => item.result).length, published, 'grep matches do not create result bubbles');
result(a,'123',[text('private data from an unknown tool')]);
await wait(140);
assert.equal(reports.filter(item => item.result).length, published, 'already consumed or unknown call IDs do not create result bubbles');
call(a,'125','Bash');
result(a,'125',[text('sensitive but short command output')]);
await wait(140);
assert.equal(reports.filter(item => item.result).length, published, 'unreviewed tool output stays inside the conversation');
start(b);
const complete = who => send(who,'turn/end',{reason:{kind:'completed'}});
const explanation = '后面的判断也要读到。'.repeat(38);
send(b,'assistant/message',{message:{content:[
  {type:'thinking', thinking:'internal reasoning'}, text('The answer is 42.\n\n' + explanation),
  text('```sh\nprivate code\n```\n结论见[结果](file:///secret).'), {type:'image',data:'private bytes'}] }});
const beforeFinal = Number(process.hrtime.bigint() / 1000n);
complete(b);
const afterFinal = Number(process.hrtime.bigint() / 1000n);
const answerId = 'turn-' + lastSeq;
await wait(140);
assert.deepEqual(reports.at(-1), {state:'coding',sessionId:'a',
  result:{sessionId:'b',id:answerId,kind:'answer',text:'The answer is 42.',atMicros:reports.at(-1).result.atMicros}});
assert.ok(reports.at(-1).result.atMicros >= beforeFinal && reports.at(-1).result.atMicros <= afterFinal,
  'a completion keeps its event time while a phone screen finishes closing');
end(a);
await wait(140);
assert.deepEqual(reports.at(-1), {state:'idle',sessionId:undefined,result:undefined});
start(a);
tool(a,'write');
call(a,'126','write');
const beforeFailedEdit = reports.filter(item => item.result).length;
result(a,'126',[text('Permission denied')],true);
await wait(140);
assert.equal(reports.filter(item => item.result).length, beforeFailedEdit,
  'an editing failure neither invents a failure bubble nor echoes its error body as a receipt');
complete(a);
await wait(140);
assert.equal(reports.at(-1).result.text, undefined, 'a new turn cannot reuse text from an earlier answer');

const phases = [];
bridge.petState = async (state, sessionId, result, phase) => phases.push({state, sessionId, result, phase});
start(a);
tool(a, 'write');
call(a, 'short', 'write');
result(a, 'short', [text('Saved')]);
send(a, 'step/start', {});
await wait(140);
assert.deepEqual(phases.at(-1), {state:'thinking', sessionId:'a', phase:String(lastSeq), result:undefined},
  'a new thinking step supersedes tool success even inside a coalesced update');
const firstPhase = phases.at(-1).phase;
send(a, 'step/start', {});
await wait(140);
assert.notEqual(phases.at(-1).phase, firstPhase, 'new progress in the same pose reaches the overlay');

const progress = [];
bridge.petState = async (state, sessionId, result, phase, caption, phoneStep, captionAt, turnStartedAt) =>
  progress.push({state, sessionId, result, caption, phoneStep, captionAt, turnStartedAt});
const described = (name, callId, args) =>
  send(a, 'tool/call', {name, callId, arguments:JSON.stringify(args)});
const longStep = '先确认我们看见的画面，再决定下一步。'.repeat(6);
described('bash', 'long-step', {description:longStep});
await wait(140);
assert.equal(progress.at(-1).caption, longStep, 'phone actions retain the end of the description');
described('bash', 'tap', {description:'点击「货币战争」入口', command:'private command'});
await wait(140);
assert.equal(progress.at(-1).caption, '点击「货币战争」入口');
const entryTime = progress.at(-1).captionAt;
const entryTurn = progress.at(-1).turnStartedAt;
assert.ok(Number.isSafeInteger(entryTime) && entryTime > 0);
assert.ok(Number.isSafeInteger(entryTurn) && entryTurn > 0 && entryTurn <= entryTime);
result(a, 'tap', [text('private shell output')]);
send(a, 'step/start', {});
await wait(140);
assert.equal(progress.at(-1).caption, '点击「货币战争」入口', 'a brief tool call remains legible during inference');
described('read_image', 'screen', {file_path:'/private/path'});
result(a, 'screen', [{type:'image'}]);
described('read_image', 'check-frame', {file_path:'/private/next'});
result(a, 'check-frame', [{type:'image'}]);
send(a, 'step/start', {});
await wait(140);
assert.equal(progress.at(-1).phoneStep, undefined);
assert.equal(progress.at(-1).caption, '点击「货币战争」入口', 'repeated frame reads preserve the current action description');
assert.equal(progress.at(-1).captionAt, entryTime);
described('job_output', 'poll', {description:'poll a running job'});
await wait(140);
assert.equal(progress.at(-1).captionAt, entryTime, 'polling retains the originating tool time');
assert.equal(progress.at(-1).turnStartedAt, entryTurn, 'observations and polling retain the active turn boundary');
for (const [name, args] of [
  ['grep', {pattern:'private contents'}], ['bash', {command:'private command'}],
  ['adb_shell', {description:'unsafe `private command`'}], ['bash', {description:'unsafe\nprivate line'}],
]) {
  described(name, 'no-new-description-' + lastSeq, args);
  await wait(140);
  assert.equal(progress.at(-1).caption, '点击「货币战争」入口', 'a tool without a safe description preserves the meaningful caption');
  assert.equal(progress.at(-1).captionAt, entryTime, 'transport activity cannot make old progress look fresh');
  assert.equal(progress.at(-1).turnStartedAt, entryTurn);
}
send(a, 'tool/call', {name:'bash', callId:'malformed-description', arguments:'{bad json'});
await wait(140);
assert.equal(progress.at(-1).captionAt, entryTime, 'malformed arguments preserve the previous semantic time');
described('bash', 'again', {description:'点击「货币战争」入口'});
await wait(140);
assert.ok(progress.at(-1).captionAt > entryTime, 'a new tool may have exactly the same description');
described('bash', 'fail', {description:'检查画面'});
result(a, 'fail', [text('private traceback\n[exit code: 1]')]);
send(a, 'step/start', {});
await wait(140);
assert.equal(progress.at(-1).phoneStep, undefined, 'a nonzero exit does not become a phone error');
assert.equal(progress.at(-1).result, undefined, 'a nonzero exit does not create pet speech');
assert.equal(progress.at(-1).caption, '检查画面', 'the last agent description remains available');
assert.equal(progress.at(-1).turnStartedAt, entryTurn);
const failedAt = progress.at(-1).captionAt;

described('read_image', 'after-error', {file_path:'/private/screen'});
await wait(140);
assert.equal(progress.at(-1).phoneStep, undefined);
assert.equal(progress.at(-1).captionAt, failedAt, 're-observing does not claim fresh progress');
assert.equal(progress.at(-1).caption, '检查画面');
described('bash', 'retry', {description:'重新查看入口'});
await wait(140);
assert.equal(progress.at(-1).phoneStep, undefined);
assert.equal(progress.at(-1).caption, '重新查看入口');
described('bash', 'clean', {command:'private command'});
result(a, 'clean', [text('error example\n[exit code: 1]\nan ordinary output line')]);
await wait(140);
assert.equal(progress.at(-1).phoneStep, undefined, 'an example in stdout is not a failure trailer');
assert.ok(!JSON.stringify(progress).includes('private'));
end(a);
start(a);
await wait(140);
assert.equal(progress.at(-1).caption, undefined, 'new turn starts without previous phone progress');
assert.equal(progress.at(-1).phoneStep, undefined);
assert.ok(progress.at(-1).turnStartedAt > entryTurn);
assert.equal(progress.at(-1).captionAt, progress.at(-1).turnStartedAt, 'an empty new turn has an explicit invalidation time');
const beforeSpeech = Number(process.hrtime.bigint() / 1000n);
send(a,'assistant/message',{message:{content:[
  {type:'thinking',thinking:'private thought'}, text('我看到第一回合结束了。'), {type:'tool-call',name:'bash'}
]}});
const afterSpeech = Number(process.hrtime.bigint() / 1000n);
const publicId = 'say-' + lastSeq;
described('bash', 'pause', {description:'等到下一次观察'});
described('read_image', 'next-frame', {file_path:'/private/path'});
send(a,'step/start',{});
await wait(140);
assert.equal(progress.at(-1).phoneStep, undefined);
assert.equal(progress.at(-1).caption, undefined, 'coalescing delivers speech before the newer action can mask it');
const {atMicros, ...publicReply} = progress.at(-1).result;
assert.ok(atMicros >= beforeSpeech && atMicros <= afterSpeech, 'speech is dated before bridge batching');
assert.equal(progress.at(-1).captionAt, atMicros, 'the first speech delivery must not contain a newer progress timestamp');
assert.deepEqual(publicReply, {sessionId:'a',id:publicId,kind:'reply',text:'我看到第一回合结束了。'},
  'a reply survives a tool call and an immediate observation while the pose updates');
const speechTurn = progress.at(-1).turnStartedAt;
await wait(140);
assert.equal(progress.at(-1).result, undefined, 'the latest pending action follows without replaying the speech');
assert.equal(progress.at(-1).caption, '等到下一次观察');
assert.ok(progress.at(-1).captionAt > atMicros, 'the action keeps its true original time for post-reading selection');
assert.equal(progress.at(-1).turnStartedAt, speechTurn);
send(a,'assistant/message',{message:{content:[text('看清了，我再观察一下。')]}});
send(a,'step/start',{});
await wait(140);
assert.equal(progress.at(-1).result?.kind, 'reply', 'pure public speech before another step also reaches the pet');
assert.ok(!JSON.stringify(progress).includes('private thought'));

// The reported image-generation case: a useful reply is newer than the launch
// description. Output polling carries neither a replacement speech nor a fresh time.
described('bash', 'generate-image', {description:'按缩小尺寸后台出图', command:'private command'});
await wait(140);
const generationAt = progress.at(-1).captionAt;
const generationTurn = progress.at(-1).turnStartedAt;
send(a, 'assistant/message', {message:{content:[text('这次是 576×768，正在跑第 12/24 步～')]}});
await wait(140);
const generationReply = progress.at(-1).result;
assert.ok(generationReply.atMicros > generationAt);
for (let index = 0; index < 2; index++) {
  described('job_output', 'generation-poll-' + index, {description:'正在处理', jobId:'private job'});
  result(a, 'generation-poll-' + index, [text('private output')]);
  send(a, 'step/start', {});
  await wait(140);
  assert.equal(progress.at(-1).result, undefined, 'a poll sends no replacement speech');
  assert.equal(progress.at(-1).caption, '按缩小尺寸后台出图');
  assert.equal(progress.at(-1).captionAt, generationAt, 'the old description stays older than the held reply');
  assert.equal(progress.at(-1).turnStartedAt, generationTurn);
}
described('bash', 'save-image', {description:'图已经生成，保存到工作区'});
await wait(140);
assert.ok(progress.at(-1).captionAt > generationReply.atMicros, 'a genuinely newer action can replace the earlier reply');
call(a, 'failed-edit', 'edit');
result(a, 'failed-edit', [text('Could not save')], true);
send(a, 'step/start', {});
await wait(140);
assert.equal(progress.at(-1).result, undefined, 'non-phone errors also stay out of pet speech');
assert.equal(progress.at(-1).phoneStep, undefined);
assert.equal(progress.at(-1).caption, '图已经生成，保存到工作区');
send(a, 'assistant/message', {message:{content:[text('保存失败了，我先检查目录。')]}});
await wait(140);
assert.equal(progress.at(-1).result?.kind, 'reply', 'the agent explains the failure in its own public reply');
assert.equal(progress.at(-1).phoneStep, undefined);
send(a, 'assistant/message', {message:{content:[text('旧回合待发送的进度。')]}});
end(a);
start(a);
described('bash', 'new-turn-action', {description:'处理新的任务'});
await wait(140);
assert.equal(progress.at(-1).result, undefined, 'coalescing cannot leak a previous turn reply');
assert.equal(progress.at(-1).caption, '处理新的任务');
assert.ok(progress.at(-1).turnStartedAt > generationTurn, 'the boundary survives a description before bridge delivery');
send(a, 'assistant/message', {message:{content:[text('旧回合的最终回复。')]}});
complete(a);
start(a);
await wait(140);
assert.equal(progress.at(-1).result, undefined, 'a previous same-session final cannot replace the new turn');
assert.equal(progress.at(-1).caption, undefined);
console.log('PASS ordinary command descriptions, polling freshness, failure/recovery and coalesced turn boundaries');
const zh = '刚才是护盾先破了，主力才掉血的。';
const detail = '从刚才三幅画面看，先出现破盾图标，下一帧主力的血条才开始下降。';
const marked = '**' + zh + '**\n\n' + detail.repeat(4);
async function expectSpeech(blocks, expected) {
  const message = Object.freeze({content:Object.freeze(blocks.map(Object.freeze))});
  send(a, 'assistant/message', {message});
  await wait(140);
  assert.equal(progress.at(-1).result?.text, expected);
}
function screenResult(screen, companion) {
  const id = 'choice-' + lastSeq;
  call(a, id, 'mobile_use');
  result(a, id, [text(JSON.stringify({ok:true,phase:'active',screen,companion}))]);
}
await expectSpeech([text(marked)], zh); // Ordinary conversation.
await expectSpeech([text('搞定。文件放到[工作区](file:///secret)了。')], '搞定。文件放到工作区了。');
await expectSpeech([text('好呀！' + zh + detail.repeat(4))], zh);
await expectSpeech([text('Ready? I found the calculator error. ' + detail.repeat(4))], 'I found the calculator error.');
await expectSpeech([text('# 本场战报\n\n**观察记录与说明**\n\nhttps://example.invalid/test\n' +
  '[查看详细记录](file:///private/path)\n1. ' + zh + '\n- ' + detail.repeat(4))], zh);
await expectSpeech([text('先把 **护盾** 补起来\n\n' + detail.repeat(4))], '先把 护盾 补起来');
await expectSpeech([text('字'.repeat(58) + '👩🏽‍💻🌊再长一点')], '字'.repeat(58) + '👩🏽‍💻…');
await expectSpeech([text('☀️'.repeat(60))], '☀️'.repeat(60));
screenResult('main', true);
await expectSpeech([text(marked)], zh);
await expectSpeech([{type:'thinking',thinking:'private thought'},text('**It worked!**'),text(detail.repeat(4))], 'It worked!');
await expectSpeech([text('まず盾が割れました。\n\nそのあと、体力が減りました。')], 'まず盾が割れました。 そのあと、体力が減りました。');
screenResult('virtual', true);
await expectSpeech([text('「先に盾が割れたよ！」そのあと体力も少し減ったね。\r\n\r\n' + detail.repeat(4))], '「先に盾が割れたよ！」');
screenResult('virtual', false);
await expectSpeech([text(zh),text(detail.repeat(4))], zh);
complete(a);
await wait(150);
start(a);
await expectSpeech([text('**' + zh + '**')], zh);
console.log('PASS concise replies across ordinary, phone and companion sessions, Markdown, sentence boundaries and emoji');
bridge.petState = async (state, sessionId, result, phase) => phases.push({state, sessionId, result, phase});
start(b);
send(b, 'assistant/message', {message:{content:[text('Final B')]}});
complete(b);
tool(a, 'write');
call(a, 'next', 'write');
result(a, 'next', [text('Saved A')]);
await wait(300);
assert.deepEqual(phases.filter(v => v.result).slice(-2).map(v => v.result.text), ['Final B', 'Saved A'],
  'parallel tool output cannot displace a terminal answer awaiting delivery');

const attempts = [];
let failResult = true;
bridge.petState = async (state, sessionId, result) => {
  if (!result) return;
  attempts.push(result);
  if (failResult) { failResult = false; throw new Error('lost acknowledgement'); }
};
start(b);
send(b,'assistant/message',{message:{content:[text('Done')]}});
complete(b);
await wait(2800);
assert.equal(attempts.length, 2);
assert.deepEqual(attempts[1], attempts[0], 'a result retry reuses its original id and conversation');
cleanup.pop()();
console.log('PASS conversation routing, bounded actual answers/tool receipts, idempotent result retries');

const restored = [];
bridge.petState = async (state, id) => restored.push([state,id]);
a.status = 'running';
apply(subscriptions());
await wait(160);
assert.deepEqual(restored.at(-1), ['thinking','a'], 'snapshot restores a turn inside model inference');
events.get('agent/status')({agent:b, status:'running'});
events.get('agent/disposed')({agent:a});
await wait(160);
assert.deepEqual(restored.at(-1), ['thinking','b'], 'a disposed root cannot hide the other running root');
events.get('agent/status')({agent:b, status:'idle'});
await wait(160);
assert.deepEqual(restored.at(-1), ['idle',undefined], 'missing turn/end still releases an interrupted root');
cleanup.pop()();


// Tool outcomes are for the agent to interpret across every task type.
const outcomeReports = [], outcomeNotifications = [];
bridge.petState = async (state, sessionId, result, phase, caption, phoneStep, captionAt) =>
  outcomeReports.push({state, sessionId, result, phase, caption, phoneStep, captionAt});
bridge.agentNotification = async (...args) => outcomeNotifications.push(args);
a.status = b.status = 'idle';
apply(subscriptions());
const failures = [
  {name:'bash', output:'No matches\n[exit code: 1]'},
  {name:'bash', output:'Command failed', meta:{exitCode:2}},
  {name:'bash', output:'Interrupted\n[exit code: -9]'},
  {name:'bash', output:'Execution unavailable', isError:true},
  {name:'read', output:'File not found', isError:true},
  ...['write','edit','apply_patch','str_replace_editor'].map(name => ({name, output:'Permission denied', isError:true})),
  {name:'third_party_tool', output:'Request failed', isError:true},
  {name:'job_output', output:'Background job failed', isError:true},
  ...['stale_observation','user_control','image_unavailable'].map(code =>
    ({name:'bash', output:code + ': details stay in the conversation\n[exit code: 1]'})),
];
for (const [index, failure] of failures.entries()) {
  start(a);
  const id = 'outcome-' + index;
  described('bash', id + '-progress', {description:'检查结果，再决定下一步'});
  await wait(140);
  const actionAt = outcomeReports.at(-1).captionAt;
  // The reply and failure arrive in one publisher batch, just as a short command can.
  send(a, 'assistant/message', {message:{content:[text('我正在确认结果。')]}});
  call(a, id, failure.name);
  const body = Object.freeze({message:Object.freeze({role:'tool', toolCallId:id,
    content:Object.freeze([Object.freeze(text(failure.output))]), isError:!!failure.isError}),
    meta:failure.meta && Object.freeze(failure.meta)});
  const raw = JSON.stringify(body);
  const before = outcomeReports.length;
  send(a, 'tool/result', body);
  send(a, 'step/start', {});
  await wait(140);
  const emitted = outcomeReports.slice(before);
  assert.equal(JSON.stringify(body), raw, 'the agent still receives the untouched tool error and exit metadata');
  assert.equal(emitted.at(-1).result?.kind, 'reply', failure.name + ': a failure cannot replace queued agent speech');
  assert.equal(emitted.at(-1).result?.text, '我正在确认结果。');
  assert.equal(emitted.at(-1).phoneStep, undefined);
  assert.equal(emitted.at(-1).captionAt, actionAt, 'a result must not make the preceding action look freshly updated');
  assert.ok(!JSON.stringify(emitted).includes(failure.output), 'raw error output stays out of pet speech');
  send(a, 'assistant/message', {message:{content:[text('这个方法没有成功，我换一种方式。')]}});
  await wait(140);
  assert.equal(outcomeReports.at(-1).result?.text, '这个方法没有成功，我换一种方式。',
    'the agent can explain a real failure in its own words');
  end(a);
}
await wait(140);
start(a);
send(a, 'assistant/message', {message:{content:[text('尚未完成的进度。')]}});
send(a, 'step/start', {});
const terminalError = Object.freeze({reason:Object.freeze({kind:'error', message:'Provider unavailable'})});
send(a, 'turn/end', terminalError);
await wait(140);
assert.equal(outcomeReports.at(-1).state, 'idle', 'a terminal error clears the busy state');
assert.equal(outcomeReports.at(-1).result, undefined, 'a terminal error invents neither a failure line nor a final answer');
assert.deepEqual(terminalError, {reason:{kind:'error', message:'Provider unavailable'}});
assert.equal(outcomeNotifications.length, 0, 'tool errors and failed/aborted turns do not send completion notifications');
assert.ok(outcomeReports.every(row => row.result?.kind !== 'error' && row.phoneStep !== 'error'));
start(a);
send(a, 'assistant/message', {message:{content:[text('已处理好，可以继续了。')]}});
complete(a);
await wait(140);
assert.equal(outcomeReports.at(-1).result?.kind, 'answer');
assert.equal(outcomeReports.at(-1).result?.text, '已处理好，可以继续了。');
assert.equal(outcomeNotifications.length, 1, 'a later successful turn still notifies normally');
cleanup.pop()();
console.log('PASS generic command/edit/read/plugin/job errors and takeover outcomes preserve agent speech; terminal errors remain in chat');
