import { rootAgents } from '../root-agents.mjs';
import { randomUUID } from 'node:crypto';
import { stripVTControlCharacters } from 'node:util';
import { createPetReplies } from './pet-replies.js';

export const inject = ['agents'];

// Keep only the first visible line of a human message. Attachments, tool results and
// system-generated prompts may share a session, but should never name a notification.
function firstPromptExcerpt(events) {
  for (const event of events) {
    if (event.type !== 'user/message' || event.data.source.kind !== 'user') continue;
    for (const block of event.data.content) {
      if (block.type !== 'text') continue;
      const line = stripVTControlCharacters(block.text.trimStart().split(/[\r\n]/u, 1)[0].slice(0, 256))
        .replace(/[\p{Cc}\u200b\u200e\u200f\u202a-\u202e\u2060-\u206f\ufeff]/gu, ' ')
        .replace(/\s+/gu, ' ').trim();
      const chars = Array.from(line);
      if (chars.length) return chars.length > 40 ? chars.slice(0, 40).join('') + '…' : line;
    }
  }
}

// A plugin reload can overlap the previous instance's last asynchronous bridge
// request. Keep one sender for the lifetime of this guest and one current owner.
function createPetPublisher(bridge) {
  const idle = { state: 'idle' };
  let owner, desired = idle, reported = idle, pendingFinal, pendingReply, pendingTool, sending = false, timer;
  function schedule(delay) {
    clearTimeout(timer);
    timer = undefined;
    if (!sending && (desired !== reported || pendingFinal || pendingReply || pendingTool)) timer = setTimeout(send, delay).unref();
  }
  async function send() {
    timer = undefined;
    sending = true;
    const value = desired;
    const result = pendingFinal ?? (pendingTool?.kind === 'error' ? pendingTool : pendingReply ?? pendingTool);
    try {
      await bridge.petState(value.state, value.sessionId, result, value.phase, value.caption, value.phoneStep, value.captionAt);
      reported = value;
      if (pendingFinal === result) pendingFinal = undefined;
      if (pendingReply === result) pendingReply = undefined;
      if (pendingTool === result) pendingTool = undefined;
    } catch {
      // A lost reply doesn't tell us whether Android applied the state.
      reported = undefined;
    }
    finally {
      sending = false;
      // With no owner, allow one last reset after an in-flight active state.
      // A failed reset has no reason to keep a disposed plugin retrying.
      if (owner || value !== idle) schedule(reported === undefined && value === desired ? 2500 : 100);
    }
  }
  return {
    claim(id) { owner = id; desired = idle; pendingTool = pendingReply = undefined; schedule(100); },
    update(id, state, sessionId, phase, result, progress) {
      if (id !== owner) return;
      const changed = desired.state !== state || desired.sessionId !== sessionId || desired.phase !== phase ||
        desired.caption !== progress?.caption || desired.phoneStep !== progress?.phoneStep ||
        desired.captionAt !== progress?.captionAt;
      if (!changed && !result) return;
      if (changed) {
        desired = state === 'idle' ? idle : { state, sessionId, phase, ...progress };
        pendingTool = undefined;
        if (state === 'asking' || pendingReply?.sessionId !== sessionId) pendingReply = undefined;
      }
      if (result?.id.startsWith('turn-')) {
        pendingFinal = result;
        if (pendingReply?.sessionId === result.sessionId) pendingReply = undefined;
      }
      else if (result?.kind === 'reply' && result.sessionId === sessionId && state !== 'asking') {
        pendingReply = result;
      }
      else if (result && result.sessionId === sessionId && (state === 'coding' || state === 'working')) {
        pendingTool = result;
        if (result.kind === 'error') pendingReply = undefined;
      }
      if (!timer) schedule(100);
    },
    release(id) {
      if (id !== owner) return;
      owner = undefined;
      desired = idle;
      pendingTool = pendingReply = undefined;
      schedule(100);
    },
  };
}

// Match the native bridge limits (UTF-16 units), without splitting an emoji or a combining mark.
const graphemes = new Intl.Segmenter(undefined, {granularity:'grapheme'});
function boundedLine(line, limit) {
  if (line.length <= limit) return line;
  const cut = graphemes.segment(line).containing(limit - 1).index;
  return line.slice(0, cut).trimEnd() + '…';
}

function* publicLines(message) {
  let fence = '';
  for (const block of message?.content ?? []) {
    if (block.type !== 'text' || typeof block.text !== 'string') continue;
    for (const raw of stripVTControlCharacters(block.text).split(/[\r\n]+/u)) {
      const marker = /^\s*(`{3,}|~{3,})/u.exec(raw)?.[1];
      if (marker) {
        if (!fence) fence = marker;
        else if (marker.startsWith(fence) && raw.trim() === marker) fence = '';
        continue;
      }
      if (fence) continue;
      yield raw.replace(/[\p{Cc}\u200b\u202a-\u202e\u2066-\u2069]/gu, ' ').replace(/\s+/gu, ' ').trim();
    }
  }
}

// Read the visible link label. Attachment URLs can contain a whole encoded image.
function visibleLinks(line) {
  return line.replace(/!?\[([^\]\r\n]*)\]\([^\s)]*\)/gu, '$1');
}

function spokenText(message, limit) {
  let text = '';
  for (const raw of publicLines(message)) {
    const line = visibleLinks(raw).trim();
    if (!line) continue;
    text = text ? text + ' ' + line : line;
    if (text.length > limit) return boundedLine(text, limit);
  }
  return text || undefined;
}

const sentences = new Intl.Segmenter(undefined, {granularity:'sentence'});
const replyLimit = 60;
function clipReply(text) {
  let count = 0, lastStart = 0;
  for (const {index} of graphemes.segment(text)) {
    if (++count > replyLimit) return text.slice(0, lastStart).trimEnd() + '…';
    lastStart = index;
  }
  return text;
}

function replyCaption(message) {
  let shortText = '', firstSentence, directSentence, isLong = false;
  for (const raw of publicLines(message)) {
    if (!raw || /^#{1,6}(?:\s|$)/u.test(raw) || /^!?\[[^\]]*\]\([^\s)]*\)$/u.test(raw)) continue;
    const line = visibleLinks(raw).replace(/(?:https?:\/\/|file:\/\/|data:|sandbox:\/)[^\s<>，。！？、]+/giu, '')
      .replace(/^(?:>\s*)*(?:[-+*]\s+|\d+[.)]\s+)?/u, '')
      .replace(/(\*\*|__|~~|`|\*)([^\s].*?)\1/gu, '$2').trim();
    if (!/[\p{L}\p{N}\p{Extended_Pictographic}]/u.test(line)) continue;
    if (!isLong) {
      shortText = shortText ? shortText + ' ' + line : line;
      isLong = clipReply(shortText) !== shortText;
    }
    // Bold labels such as **详情** make poor excerpts; a complete bold opening
    // sentence is an intentional summary, even if it's very short.
    if (!directSentence) {
      const boldLine = /^(?:\*\*.+\*\*|__.+__)$/u.test(raw);
      const completeBold = boldLine && /[\p{Sentence_Terminal}…][”’」』）》）)"']*$/u.test(line);
      if (boldLine && !completeBold) continue;
      for (const {segment} of sentences.segment(line)) {
        const sentence = segment.trim();
        firstSentence ??= sentence;
        if ((completeBold && sentence === line) || /(?:[\p{L}\p{N}][^\p{L}\p{N}]*){7}/u.test(sentence)) {
          directSentence = sentence;
          break;
        }
      }
    }
    if (isLong && directSentence) break;
  }
  if (!isLong) return shortText || undefined;
  return clipReply(directSentence ?? firstSentence ?? shortText);
}

function operationExcerpt(message) {
  for (const block of message?.content ?? []) {
    if (block.type !== 'text' || typeof block.text !== 'string') continue;
    // Don't echo a file dump, JSON protocol, tool transcript or an attachment onto the desktop.
    if (block.text.length > 350 || /^\s*[<{\[]/u.test(block.text)) continue;
    const visible = stripVTControlCharacters(block.text).trim();
    const line = visible.split(/[\r\n]/u, 1)[0].replace(/[\p{Cc}\u200b\u202a-\u202e\u2066-\u2069]/gu, ' ')
      .replace(/\s+/gu, ' ').trim();
    if (!line || line.startsWith('```') || visible.split(/\r?\n/u).length > 2) continue;
    return boundedLine(line, 120);
  }
}

// Only the tool's user-facing description is suitable for a desktop overlay.
// Source code, commands, attachment paths and model reasoning stay in the chat.
// Both the Linux guest and Android read CLOCK_MONOTONIC. Microseconds fit safely
// in JSON numbers. Timestamp the original call, before publisher batching or retries.
function captionTime() { return Number(process.hrtime.bigint() / 1000n); }

function phoneProgress(name, args, previous) {
  // Reading the next frame can verify the action we just described. Keep its purpose
  // visible while checking the result; a fresh observation after an error starts a new step.
  if (name === 'read_image') return previous?.caption ? previous : {phoneStep:'screen', captionAt:previous?.captionAt};
  if (name === 'job_output') return previous;
  const cleared = previous?.captionAt ? {captionAt:previous.captionAt} : undefined;
  if (name !== 'bash' && name !== 'adb_shell') return cleared;
  let input;
  try { input = typeof args === 'string' ? JSON.parse(args) : args; } catch { return cleared; }
  if (typeof input?.description !== 'string') return cleared;
  const line = stripVTControlCharacters(input.description).trim();
  if (!line || /[\r\n`]/u.test(line)) return cleared;
  const caption = line.replace(/[\p{Cc}\u200b\u202a-\u202e\u2066-\u2069]/gu, ' ').replace(/\s+/gu, ' ').trim();
  if (caption) return {caption:boundedLine(caption, 240), captionAt:captionTime()};
  return cleared;
}

function toolFailed(name, message, meta) {
  if (message.isError) return true;
  if (name !== 'bash') return false;
  if (Number.isInteger(meta?.exitCode)) return meta.exitCode !== 0;
  const last = message.content?.at(-1);
  // The bundled Bash tool reports a nonzero child exit as a trailer on a successful tool call.
  return last?.type === 'text' && /(?:^|\n)\[exit code: -?[1-9]\d*\]\s*$/u.test(last.text);
}

export function apply(ctx) {
  const bridge = globalThis[Symbol.for('dsh.sugar.projects')];
  const petReplies = createPetReplies(ctx);
  const detach = bridge?.attachPetReplies?.(body => {
    if (body.op === 'voiceAvailable' || body.op === 'voiceStatus' || body.op === 'voiceTranscribe') {
      return import('./pet-voice.js').then(({petVoiceRequest}) => petVoiceRequest(ctx, body.op, body));
    }
    return petReplies.handle(body);
  });
  const completed = new WeakMap();
  const active = new Map();
  const petTurns = new Map();
  const answers = new WeakMap();
  const editingTools = new Set(['write', 'edit', 'apply_patch', 'str_replace_editor']);
  // Committed tool/result messages identify their tool invocation by toolCallId.
  // Keep only outstanding calls in this turn; missing metadata is a generic result.
  const toolCalls = new WeakMap();
  const pendingQuestions = new Map();
  const owner = Symbol();
  const publisher = bridge?.petState
    ? (globalThis[Symbol.for('dsh.sugar.pet-publisher')] ??= createPetPublisher(bridge))
    : undefined;
  publisher?.claim(owner);
  function petChanged(result) {
    if (!publisher) return;
    const asking = pendingQuestions.entries().next().value;
    if (asking) {
      const [phase, {sessionId, caption}] = asking;
      return publisher.update(owner, 'asking', sessionId, phase, result, {caption});
    }
    for (const pose of ['coding', 'working', 'thinking']) {
      for (const [session, turn] of petTurns) {
        if (turn.state === pose) return publisher.update(owner, pose, session.id, turn.phase, result, turn.progress);
      }
    }
    publisher.update(owner, 'idle', undefined, undefined, result);
  }

  // A reloaded plugin does not receive the current turn/start again. Live Agent
  // status also clears an interrupted or disposed turn without a final event.
  if (publisher) {
    function trackAgent(agent, running) {
      if (running) {
        if (petTurns.has(agent.session) || !rootAgents(ctx.agents).includes(agent)) return;
        petTurns.set(agent.session, {state:'thinking'});
      } else {
        for (const [settle, session] of active) if (session === agent.session) settle();
        if (!petTurns.delete(agent.session)) return;
      }
      petChanged();
    }
    ctx.on('agent/status', ({agent, status}) => trackAgent(agent, status === 'running'), {global:true});
    ctx.on('agent/disposed', ({agent}) => trackAgent(agent, false), {global:true});
    for (const agent of rootAgents(ctx.agents)) if (agent.status === 'running') trackAgent(agent, true);
  }

  const firstPrompts = new WeakMap();

  async function conversationTitle(session) {
    // This client-visible projection tracks both manual renames and automatic titles.
    const values = ctx.get('sessionProjections')?.snapshot(session, ['title']).values;
    const title = values?.title === undefined ? ctx.get('sessionTitle')?.get(session)?.title : values.title;
    if (title?.trim()) return Array.from(title.trim()).slice(0, 120).join('');
    if (firstPrompts.has(session)) return firstPrompts.get(session);

    // The first approval can precede the title provider. Use the upstream read API
    // for both new and restored sessions and retain only the short human excerpt.
    const view = await ctx.get('sessionQuery')?.observeSession(session.id, { projectionMode: 'none' });
    if (!view) return;
    try {
      const excerpt = firstPromptExcerpt(view.events);
      if (excerpt) firstPrompts.set(session, excerpt);
      return excerpt;
    } finally {
      view[Symbol.dispose]();
    }
  }

  async function notify(session, kind, id, dismiss = false) {
    try {
      const title = dismiss ? undefined : await conversationTitle(session).catch(() => undefined);
      await bridge.agentNotification(kind, session.id, id, title, dismiss);
    } catch {
      ctx.logger.warn('DSH Sugar: could not deliver an Android notification');
    }
  }

  // Upstream emits committed live events. Restoring history does not replay them here.
  ctx.on('session/event', (session, event) => {
    if (!['turn/start', 'step/start', 'assistant/message', 'tool/call', 'tool/result', 'turn/end'].includes(event.type)) return;
    if (!rootAgents(ctx.agents).some(agent => agent.session === session)) return;
    if (event.type === 'turn/end') {
      if ((completed.get(session) ?? -1) >= event.seq) return;
      completed.set(session, event.seq);
      petTurns.delete(session);
      toolCalls.delete(session);
      const reason = event.data.reason.kind;
      const resultId = 'turn-' + event.seq;
      const text = reason === 'completed' ? answers.get(session) : undefined;
      answers.delete(session);
      petChanged(reason === 'completed' || reason === 'error' ? {sessionId:session.id, id:resultId,
        kind:reason === 'completed' ? 'answer' : 'error', text} : undefined);
      if (reason === 'completed') void notify(session, 'completion', resultId);
      return;
    }
    if (!publisher) return;
    if (event.type === 'tool/call') {
      const {callId, name} = event.data;
      if (!petTurns.has(session) || typeof callId !== 'string' || typeof name !== 'string') return;
      let calls = toolCalls.get(session);
      if (!calls) toolCalls.set(session, calls = new Map());
      calls.set(callId, name);
      if (calls.size > 128) calls.delete(calls.keys().next().value);
      const turn = petTurns.get(session);
      const progress = phoneProgress(name, event.data.arguments, turn.progress);
      if (turn.state === 'thinking' || progress || turn.progress) {
        petTurns.set(session, {state:turn.state === 'thinking' ? (editingTools.has(name) ? 'coding' : 'working') : turn.state,
          phase:String(event.seq), progress});
        petChanged();
      }
    } else if (event.type === 'tool/result') {
      const message = event.data.message;
      if (!message || !petTurns.has(session)) return;
      const calls = toolCalls.get(session);
      const name = calls?.get(message.toolCallId);
      calls?.delete(message.toolCallId);
      // Reads, searches and shell commands can return arbitrary file contents or secrets.
      const text = editingTools.has(name) ? operationExcerpt(message) : undefined;
      const failed = toolFailed(name, message, event.data.meta);
      if (failed && ['bash', 'adb_shell', 'read_image', 'mobile_use'].includes(name)) {
        petTurns.get(session).progress = {phoneStep:'error', captionAt:captionTime()};
      }
      if (text || failed)
        petChanged({sessionId:session.id, id:'tool-' + event.seq, kind:failed ? 'error' : 'operation', text});
    } else if (event.type === 'assistant/message') {
      const message = event.data.message;
      const calls = message.content.filter(block => block.type === 'tool-call');
      const text = replyCaption(message);
      const reply = text ? {sessionId:session.id, id:'say-' + event.seq, kind:'reply', text, atMicros:captionTime()} : undefined;
      if (!calls.length) {
        if (text) answers.set(session, text);
        if (reply) petChanged(reply);
        return;
      }
      answers.delete(session);
      petTurns.set(session, {state:calls.some(call => editingTools.has(call.name)) ? 'coding' : 'working',
        phase:String(event.seq), progress:petTurns.get(session)?.progress});
      petChanged(reply);
    } else {
      if (event.type === 'turn/start') toolCalls.delete(session);
      answers.delete(session);
      petTurns.set(session, {state:'thinking', phase:String(event.seq),
        progress:event.type === 'step/start' ? petTurns.get(session)?.progress : undefined});
      petChanged();
    }
  });

  // Wrap the Host request so native alerts and answers refer to the same interaction.
  // A request resolved immediately (e.g. by an existing grant) needs no alert.
  async function observe(kind, request, next) {
    const agent = request.agent;
    if (!agent || request.signal?.aborted || !rootAgents(ctx.agents).includes(agent)) return next();
    const id = randomUUID();
    let posted;
    const timer = setTimeout(() => {
      const question = kind === 'question' ? request.questions?.[0]?.question : undefined;
      const caption = question && spokenText({content:[{type:'text', text:question}]}, 240);
      pendingQuestions.set(id, {sessionId:agent.session.id, caption});
      petChanged();
      posted = notify(agent.session, kind, id);
    }, 200);
    timer.unref();
    function settle() {
      if (!active.delete(settle)) return;
      clearTimeout(timer);
      request.signal?.removeEventListener('abort', settle);
      // Wait for posting to finish so the cancellation cannot overtake it on the bridge.
      if (posted) {
        pendingQuestions.delete(id);
        petChanged();
        void posted.then(() => notify(agent.session, kind, id, true));
      }
    }
    active.set(settle, agent.session);
    request.signal?.addEventListener('abort', settle, { once: true });
    try { return await (detach ? petReplies.observe(kind, request, next) : next()); }
    finally { settle(); }
  }

  const first = { global: true, prepend: true };
  ctx.on('approval/request', (request, next) => observe('permission', request, next), first);
  ctx.on('user-questions/request', (request, next) => {
    const approval = request.questions.some(question => question.intent?.kind === 'approval');
    return observe(approval ? 'permission' : 'question', request, next);
  }, first);
  ctx.effect(() => () => {
    detach?.();
    petReplies.close();
    publisher?.release(owner);
    for (const settle of active.keys()) settle();
    petTurns.clear();
  }, 'Sugar: pending Android alerts');
}
