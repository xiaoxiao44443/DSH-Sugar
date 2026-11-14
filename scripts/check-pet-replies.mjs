import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createPetReplies } from '../app/src/main/assets/guest/dsh-sugar-notifications/pet-replies.js';

const roots = ['one', 'two'].map(id => ({session:{id,header:{}}, status:'running', inbox:{nextStep:[], nextTurn:[]}}));
const root = roots[0];
const prompts = [];
const late = [];
const services = {
  sessionController:{async prompt(prompt) { prompts.push(prompt); return {accepted:true}; }},
  userQuestions:{
    continued(agent) { return agent === root ? late : []; },
    answer(agent, id, batch) {
      assert.equal(agent, root);
      assert.equal(id, late[0]?.callId);
      assert.deepEqual(batch.answers, [{id:'a', selected:[], custom:'手动回答'}]);
      late.pop();
      return true;
    },
  },
};
const pet = createPetReplies({agents:{list:() => [...roots, {session:{id:'child',header:{origin:'subagent'}}}]}, get:name => services[name]});
const state = (id = 'one') => pet.handle({op:'state', sessionId:id});
const send = (parts, sessionId = 'one') => ({op:'send', sessionId, deliveryId:randomUUID(), ...parts});
assert.equal((await state('unknown')).mode, 'unavailable');
assert.equal((await state('child')).mode, 'unavailable');

const quick = send({text:'继续操作', clientTimeZone:'Asia/Tokyo'}, 'two');
await Promise.all([pet.handle(quick), pet.handle({...quick, clientTimeZone:'Europe/Paris'})]);
assert.deepEqual(prompts.map(p => [p.sessionId,p.mode,p.content[0].text]), [['two','steer','继续操作']]);
assert.equal(prompts[0].clientTimeZone, 'Asia/Tokyo');
await assert.rejects(pet.handle({...quick, sessionId:'one'}));
root.status = 'idle';
await pet.handle(send({text:'继续这条对话', clientTimeZone:'America/New_York'}));
assert.equal(prompts[1].mode, 'queue');
assert.equal(prompts[1].clientTimeZone, 'America/New_York');
for (const zone of [undefined, 'unknown/device-zone', 'GMT+05:45']) {
  await pet.handle(send({text:'继续', clientTimeZone:zone}));
  assert.equal(prompts.at(-1).clientTimeZone, undefined);
}
root.status = 'running';

const questions = [{id:'a', question:'选择策略', options:[{label:'继续'}, {label:'暂停'}]}];
const signal = new AbortController();
const request = {agent:root, signal:signal.signal, questions, wait:{callId:'call-1'}};
let retired = false;
const asking = pet.observe('question', request, () => new Promise((resolve, reject) => {
  request.signal.addEventListener('abort', () => { retired = true; reject(request.signal.reason); }, {once:true});
}));
const questionId = (await state()).questionId;
assert.equal(questionId, 'call-1');
assert.equal((await state('two')).mode, 'quick');
await assert.rejects(pet.handle(send({text:'不要当成消息发送'})));
await assert.rejects(pet.handle(send({questionId:'wrong', answers:[]})));
await assert.rejects(pet.handle(send({questionId, answers:[{id:'a', selected:['不存在的选项']}]})));
const pick = send({questionId, answers:[{id:'a', selected:['暂停']}]});
await pet.handle(pick);
assert.deepEqual(await asking, {answers:pick.answers});
assert.equal(retired, true);
assert.equal(request.signal, signal.signal);
assert.equal(signal.signal.aborted, false);
await pet.handle(pick);
await assert.rejects(pet.handle(send({questionId, answers:pick.answers})));

const ui = Promise.withResolvers();
const onWeb = pet.observe('question', request, () => ui.promise);
ui.resolve({answers:[{id:'a', selected:['继续']}]});
await onWeb;
await assert.rejects(pet.handle(send({questionId, answers:pick.answers})));
late.push({callId:questionId, questions});
assert.equal((await state()).questionId, questionId);
await pet.handle(send({questionId, answers:[{id:'a', selected:[], custom:'手动回答'}]}));
assert.equal((await state()).mode, 'quick');

const release = Promise.withResolvers();
const permission = pet.observe('permission', {agent:root}, () => release.promise);
assert.equal((await state()).mode, 'unavailable');
await assert.rejects(pet.handle(send({text:'允许'})));
release.resolve('from original UI');
assert.equal(await permission, 'from original UI');
assert.equal(prompts.length, 5);
pet.close();
console.log('Pet replies passed for targeted steering, followup, both live answer surfaces, continued questions and stale or duplicate submissions.');
