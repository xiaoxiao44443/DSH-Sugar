import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { apply } from '../app/src/main/assets/guest/dsh-sugar-notifications/index.js';

const events = new Map();
const sent = [];
const cleanup = [];
const root = { session: { id: 'main', header: {} } };
const second = { session: { id: 'second', header: {} } };
const child = { session: { id: 'child', header: {origin:'subagent'} } };
let finishPosting;
const rootTitle = { title: 'First conversation' };
const questionPrompt = '给这张海报配色';
let reads = 0, released = 0;
const titleProjection = { snapshot: (session, keys) => {
  assert.deepEqual(keys, ['title']);
  return { values: session === root.session ? rootTitle : { title: null } };
} };
const sessionQuery = { async observeSession(id, options) {
  assert.equal(id, second.session.id);
  assert.deepEqual(options, { projectionMode: 'none' });
  reads++;
  return { events: [
    { type: 'tool/result', data: { text: 'private tool output' } },
    { type: 'user/message', data: { source: { kind: 'plugin' }, content: [{ type: 'text', text: 'generated prompt' }] } },
    { type: 'user/message', data: { source: { kind: 'user' }, content: [
      { type: 'image', name: 'private attachment' }, { type: 'text', text: questionPrompt + '\nprivate second line' }
    ] } },
    { type: 'user/message', data: { source: { kind: 'user' }, content: [{ type: 'text', text: 'different prompt' }] } },
  ], [Symbol.dispose]() { released++; } };
} };
globalThis[Symbol.for('dsh.sugar.projects')] = {
  async agentNotification(kind, sessionId, eventId, title, dismiss, body) {
    sent.push({ kind, sessionId, eventId, title, dismiss, body });
    if (!dismiss && kind === 'permission') await new Promise(resolve => { finishPosting = resolve; });
  },
};
apply({
  agents: { list: () => [root, second, child] },
  get: name => ({ sessionProjections: titleProjection, sessionQuery }[name]),
  on: (name, callback) => events.set(name, callback),
  effect: factory => cleanup.push(factory()),
});
const end = (agent, seq, kind) => events.get('session/event')(agent.session, {
  type: 'turn/end', seq, data: { turn: 1, reason: { kind } },
});
end(child, 5, 'completed');
end(root, 6, 'error');
end(root, 7, 'blocked');
end(root, 8, 'aborted');
end(root, 9, 'completed');
end(root, 9, 'completed');
end(second, 9, 'completed');
await delay(0);
assert.deepEqual(sent.map(e => [e.kind, e.sessionId, e.eventId]), [
  ['completion', 'main', 'turn-9'], ['completion', 'second', 'turn-9'],
]);

assert.deepEqual(sent.map(e => e.title), ['First conversation', questionPrompt]);
assert.equal(reads, 1);
assert.equal(released, 1);
rootTitle.title = 'Renamed conversation';
const permission = events.get('approval/request');
assert.equal(await permission({ agent: root }, () => 'already granted'), 'already granted');
const controller = new AbortController();
let resolvePermission, resolveQuestion;
const awaitingPermission = permission({ agent: root, signal: controller.signal, toolName:'sugar-root',
  reason:'请求以 Root 权限管理这台手机。\n\nsecret-command --token never-show' }, () =>
  new Promise(resolve => { resolvePermission = resolve; }));
const question = events.get('user-questions/request');
const awaitingQuestion = question({ agent: second, questions: [{question:'你希望使用哪种配色？'}, {question:'要加标题吗？'}] }, () =>
  new Promise(resolve => { resolveQuestion = resolve; }));
await delay(240);
assert.deepEqual(sent.slice(2).map(e => [e.kind, e.sessionId, e.dismiss]), [
  ['permission', 'main', false], ['question', 'second', false],
]);
assert.deepEqual(sent.slice(2).map(e => e.title), ['Renamed conversation', questionPrompt]);
assert.deepEqual(sent.slice(2).map(e => e.body), ['sugar-root · 请求以 Root 权限管理这台手机。', '你希望使用哪种配色？\n要加标题吗？']);
assert.equal(reads, 1, 'first human excerpt is cached but renames remain live');
controller.abort();
resolvePermission('cancelled');
resolveQuestion('yes');
assert.equal(await awaitingPermission, 'cancelled');
assert.equal(await awaitingQuestion, 'yes');
await delay(0);
assert.deepEqual(sent.slice(4).map(e => [e.kind, e.eventId, e.dismiss]), [
  ['question', sent[3].eventId, true],
]);
finishPosting();
await delay(0);
assert.deepEqual(sent[5], { ...sent[2], title: undefined, dismiss: true, body: undefined });

// Make a previously unnamed conversation acquire its genuine sidebar title.
titleProjection.snapshot = session => ({ values: { title: session === second.session ? '海报颜色' : rootTitle.title } });
end(second, 11, 'completed');
await delay(0);
assert.equal(sent.pop().title, '海报颜色');
let endApprovalQuestion;
const structured = question({ agent: root, questions: [{question:'允许访问所选文件夹吗？', detail:'private script not for notifications', intent: { kind: 'approval' } }] }, () =>
  new Promise(resolve => { endApprovalQuestion = resolve; }));
await delay(240);
assert.equal(sent[6].kind, 'permission');
assert.equal(sent[6].body, '允许访问所选文件夹吗？');
for (const dispose of cleanup) dispose();
endApprovalQuestion('no');
assert.equal(await structured, 'no');
finishPosting();
await delay(0);
assert.deepEqual(sent[7], { ...sent[6], title: undefined, dismiss: true, body: undefined });
// A transient title-service error must not eat the result; Android has a stable ID fallback.
titleProjection.snapshot = () => { throw new Error('provider disposed'); };
end(root, 15, 'completed');
await delay(0);
assert.deepEqual([sent.at(-1).sessionId, sent.at(-1).title], ['main', undefined]);
// A final reply is distinct from the short pet excerpt and preserves readable paragraphs.
let sequence = 20;
const event = (type, data = {}, agent = root) => events.get('session/event')(agent.session, {type, seq:++sequence, data});
const start = () => event('turn/start');
const say = content => event('assistant/message', {message:{content}});
const text = value => ({type:'text', text:value});
const finish = async () => { end(root, ++sequence, 'completed'); await delay(0); return sent.at(-1); };
start();
say([text('这次已经完成了。\n\n**图片**保存好了。\n- 第一项\n- 第二项'),
  {type:'reasoning', text:'private reasoning'}, {type:'tool-result', text:'private tool transcript'},
  text('\n[查看图片](sandbox:/private/image.png)\n```sh\nprivate-command --token secret\n```\n结尾。')]);
assert.equal((await finish()).body, '这次已经完成了。\n\n图片保存好了。\n- 第一项\n- 第二项\n\n查看图片\n结尾。');
start();
assert.equal((await finish()).body, undefined, 'a new empty turn cannot reuse an old reply');
start();
say([text('我先检查一下。'), {type:'tool-call', name:'bash'}]);
assert.equal((await finish()).body, undefined, 'an explanation accompanying a tool is not a final reply');
start();
say([text('先检查数据。')]);
event('tool/call', {callId:'check',name:'read'});
event('tool/result', {message:{content:[text('private file output')]}});
assert.equal((await finish()).body, undefined, 'a later tool invalidates the interim text without exposing its result');
start();
say([text('上一步的进展。')]);
event('step/start');
assert.equal((await finish()).body, undefined);
start();
say([text('更早的段落。')]);
say([text('最终答复。')]);
assert.equal((await finish()).body, '最终答复。');
start();
say([text('先前有文字。')]);
say([{type:'image', data:'private image bytes'}]);
assert.equal((await finish()).body, undefined, 'an attachment-only final does not recycle an earlier reply');
start();
say([text('正文。\u001b[31m彩色\u001b[0m\u202e\u200f\nhttps://private.invalid/?token=hidden\ndata:image/png;base64,hidden')]);
assert.equal((await finish()).body, '正文。彩色');
start();
say([text('🍀'.repeat(1800))]);
const longBody = (await finish()).body;
assert.ok(longBody.length <= 2000 && longBody.endsWith('…'));
assert.ok(!/[\uD800-\uDBFF]$/u.test(longBody.slice(0, -1)), 'truncation must not cut a surrogate pair');
start();
say([text('这条已经取消。')]);
end(root, ++sequence, 'aborted');
start();
assert.equal((await finish()).body, undefined);
// Even an approval whose reason is a raw command should identify only the tool.
let resolveUnsafe;
const unsafe = permission({agent:root,toolName:'bash',reason:'curl --header Authorization:Bearer-secret https://private.invalid/'}, () =>
  new Promise(resolve => {resolveUnsafe = resolve;}));
await delay(240);
assert.equal(sent.at(-1).body, 'bash');
resolveUnsafe('no');
await unsafe;
finishPosting();
await delay(0);
console.log('PASS mobile agent notifications: final replies, questions, safe approvals and fallback');
