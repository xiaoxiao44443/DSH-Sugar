import { rootAgents } from '../root-agents.mjs';
import { randomUUID } from 'node:crypto';

const maxReply = 4096;
const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;

// Older bridges omit the zone. DSH accepts UTC or a named area/location.
function replyTimeZone(value) {
  if (typeof value !== 'string' || !value) return undefined;
  try {
    const zone = new Intl.DateTimeFormat('en', {timeZone:value}).resolvedOptions().timeZone;
    return zone === 'UTC' || zone.includes('/') ? zone : undefined;
  } catch (error) {
    if (error instanceof RangeError) return undefined;
    throw error;
  }
}

function cleanAnswers(questions, answers) {
  const bad = () => { throw new Error('问题或选项已变化，请重新查看。'); };
  if (!Array.isArray(answers) || answers.length !== questions.length) bad();
  return questions.map(({id, options, multiSelect}) => {
    const answer = answers.find(item => item?.id === id);
    if (!answer || !Array.isArray(answer.selected)) bad();
    const selected = answer.selected;
    const custom = answer.custom?.trim();
    if ((!multiSelect && selected.length > 1) || new Set(selected).size !== selected.length ||
        !selected.every(label => options?.some(option => option.label === label)) ||
        (!selected.length && !custom) || (custom && (custom.length > maxReply || selected.length))) bad();
    return {id, selected, ...(custom ? {custom} : {})};
  });
}

export function createPetReplies(ctx) {
  const live = new Set();
  const deliveries = new Map();

  async function observe(kind, request, next) {
    const agent = request.agent;
    if (!agent || !rootAgents(ctx.agents).includes(agent) || request.signal?.aborted) return next();
    const answerable = kind === 'question' && request.questions?.length &&
      request.questions.every(q => typeof q.id === 'string' && typeof q.question === 'string' && !q.intent);
    const record = {agent, id:request.wait?.callId ?? randomUUID(), questions:answerable ? request.questions : undefined};
    live.add(record);
    try {
      if (!answerable) return await next();
      const original = request.signal;
      const remote = new AbortController();
      const signal = original ? AbortSignal.any([original, remote.signal]) : remote.signal;
      // Cordis passes the same scoped request down its waterfall. Cancel its other
      // answer surfaces when the pet wins, without cancelling the actual tool call.
      request.signal = signal;
      try {
        const native = Promise.withResolvers();
        record.answer = answers => {
          if (signal.aborted || !live.delete(record)) throw new Error('这个问题已结束。');
          native.resolve({answers});
          remote.abort();
        };
        return await Promise.race([native.promise, next()]);
      } finally {
        if (original === undefined) delete request.signal;
        else request.signal = original;
      }
    } finally { live.delete(record); }
  }

  function current(agent) {
    const here = [...live].filter(item => item.agent === agent);
    if (here.some(item => !item.questions)) return {mode:'unavailable'};
    if (here.length) return {mode:'question', record:here[0]};
    const service = ctx.get('userQuestions');
    // A timed question can outlive its waterfall. The same call ID continues
    // to identify it in DSH's projection until DSH admits the late answer.
    const pending = service?.continued?.(agent).find(q => ![...agent.inbox.nextTurn, ...agent.inbox.nextStep]
      .some(message => message.source.kind === 'user-question-reply' && message.source.callId === q.callId));
    if (pending) return {mode:'question', record:{
      id:pending.callId, questions:pending.questions,
      answer(answers) {
        if (!service.answer(agent, pending.callId, {answers})) throw new Error('这个问题已结束。');
      },
    }};
    return {mode:ctx.get('sessionController') ? 'quick' : 'unavailable'};
  }

  async function handle(body) {
    const {op, sessionId, deliveryId} = body;
    if (!sessionId || typeof sessionId !== 'string' || sessionId.length > 256) throw new Error('找不到这条对话。');
    const agent = rootAgents(ctx.agents).find(item => item.session.id === sessionId);
    if (op === 'state') {
      const {mode, record} = agent ? current(agent) : {mode:'unavailable'};
      const result = {mode, sessionId};
      if (agent) {
        const title = ctx.get('sessionTitle')?.get(agent.session)?.title ??
          ctx.get('sessionProjections')?.snapshot(agent.session, ['title']).values?.title;
        if (typeof title === 'string' && title.trim()) result.sessionTitle = Array.from(title.trim()).slice(0, 120).join('');
      }
      if (record) {
        result.questionId = record.id;
        result.questions = record.questions.map(({id, question, options, multiSelect}) =>
          ({id, question, options:options ?? [], multiSelect:!!multiSelect, allowCustom:true}));
      }
      return result;
    }
    if (op !== 'send' || !uuid.test(deliveryId)) throw new Error('回复标识无效。');
    const signature = JSON.stringify([sessionId, body.text, body.questionId, body.answers]);
    const existing = deliveries.get(deliveryId);
    if (existing) {
      if (existing.signature !== signature) throw new Error('回复标识已被使用。');
      return existing.result;
    }
    if (!agent) throw new Error('原对话已关闭，请回到对话查看。');
    const {mode, record} = current(agent);
    if (mode === 'unavailable') throw new Error('请在原对话里处理当前请求。');
    let submit;
    if (record) {
      if (record.id !== body.questionId || body.text != null) throw new Error('问题已变化，请重新查看。');
      const answers = cleanAnswers(record.questions, body.answers);
      submit = () => record.answer(answers);
    } else {
      const text = typeof body.text === 'string' ? body.text.trim() : '';
      if (body.questionId != null || body.answers != null) throw new Error('这个问题已结束。');
      if (!text || text.length > maxReply) throw new Error('请填写不超过 4096 字的回复。');
      const controller = ctx.get('sessionController');
      submit = () => controller.prompt({sessionId, requestId:deliveryId, clientTimeZone:replyTimeZone(body.clientTimeZone),
        mode:agent.status === 'running' ? 'steer' : 'queue', content:[{type:'text', text}]}, new AbortController().signal);
    }
    // Cache the in-flight receipt as well as successful deliveries. The Host
    // additionally deduplicates ordinary prompts by their durable requestId.
    const result = Promise.resolve(submit()).then(() => ({accepted:true, sessionId, deliveryId}));
    deliveries.set(deliveryId, {signature, result});
    if (deliveries.size > 256) deliveries.delete(deliveries.keys().next().value);
    try { return await result; }
    catch (error) { deliveries.delete(deliveryId); throw error; }
  }
  return {observe, handle, close() { live.clear(); deliveries.clear(); }};
}
