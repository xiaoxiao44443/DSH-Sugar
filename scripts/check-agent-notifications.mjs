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
  async agentNotification(kind, sessionId, eventId, title, dismiss) {
    sent.push({ kind, sessionId, eventId, title, dismiss });
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
const awaitingPermission = permission({ agent: root, signal: controller.signal }, () =>
  new Promise(resolve => { resolvePermission = resolve; }));
const question = events.get('user-questions/request');
const awaitingQuestion = question({ agent: second, questions: [{}] }, () =>
  new Promise(resolve => { resolveQuestion = resolve; }));
await delay(240);
assert.deepEqual(sent.slice(2).map(e => [e.kind, e.sessionId, e.dismiss]), [
  ['permission', 'main', false], ['question', 'second', false],
]);
assert.deepEqual(sent.slice(2).map(e => e.title), ['Renamed conversation', questionPrompt]);
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
assert.deepEqual(sent[5], { ...sent[2], title: undefined, dismiss: true });

// Make a previously unnamed conversation acquire its genuine sidebar title.
titleProjection.snapshot = session => ({ values: { title: session === second.session ? '海报颜色' : rootTitle.title } });
end(second, 11, 'completed');
await delay(0);
assert.equal(sent.pop().title, '海报颜色');
let endApprovalQuestion;
const structured = question({ agent: root, questions: [{ intent: { kind: 'approval' } }] }, () =>
  new Promise(resolve => { endApprovalQuestion = resolve; }));
await delay(240);
assert.equal(sent[6].kind, 'permission');
for (const dispose of cleanup) dispose();
endApprovalQuestion('no');
assert.equal(await structured, 'no');
finishPosting();
await delay(0);
assert.deepEqual(sent[7], { ...sent[6], title: undefined, dismiss: true });
// A transient title-service error must not eat the result; Android has a stable ID fallback.
titleProjection.snapshot = () => { throw new Error('provider disposed'); };
end(root, 15, 'completed');
await delay(0);
assert.deepEqual([sent.at(-1).sessionId, sent.at(-1).title], ['main', undefined]);
console.log('PASS mobile agent notifications');
