import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { defineTool } from '/opt/dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js';

export const inject = ['tools', 'skills', 'systemPrompt'];

export function apply(ctx) {
  const router = globalThis[Symbol.for('dsh.sugar.projects')];
  const tasks = new Map();
  const ending = new WeakMap();
  ctx.systemPrompt.section({
    name:'sugar:phone-operation',
    order:ctx.systemPrompt.getSectionOrder('TOOL_COMPUTER_USE'),
    text:'Prefer mobile_use for phone interaction: if the user only asks to open or operate an app, call mobile_use({}) so they can choose the screen; use connected ADB for diagnostics, when the user requests ADB, or when the selected driver lacks the required action. During multi-step phone tasks, use phone.status in the action script to tell the user why the next step serves their goal, in the voice of this conversation. For an explicit request to stay with the user, keep watching or wait for further messages, open or update mobile_use({companion:true}). It keeps the selected screen between replies, even after the observer script exits. Use companion:false when that ongoing request finishes. See the mobile-use skill for progress, accompanying play and waiting for screen cues or user messages.'
  });
  ctx.skills.register({
    name:'mobile-use', provider:'dsh-sugar-mobile-use', source:'bundled',
    description:'Operate Android apps, accompany gameplay, wait for a user or a screen cue, review recent frames and update the desktop pet. Call mobile_use({}) to choose the screen.',
    content:readFileSync(new URL('./guide.md', import.meta.url), 'utf8')
  });

  async function end(session, method) {
    const task = tasks.get(session);
    if (!task) return ending.get(session);
    tasks.delete(session);
    task.waiter?.abort();
    const release = router.mobileTask(task.agent, method, {taskId:task.id});
    ending.set(session, release);
    try { await release; } finally {
      if (ending.get(session) === release) ending.delete(session);
    }
  }
  // The rc.3 loop awaits this hook after the model's last step, then checks its inbox again.
  // Keeping the turn open preserves the selected screen without spending another model call.
  ctx.on('agent/turn-stopping', async ({agent, signal}) => {
    const task = tasks.get(agent.session);
    if (!task?.companion || task.truncated || signal.aborted || agent.inbox.nextStep.length) return;
    const waiter = new AbortController();
    task.waiter = waiter;
    const waiting = AbortSignal.any([signal, waiter.signal]);
    try {
      // The screen stays open, but the model has finished its reply. Order both
      // edges on the bridge even if an inbox message arrives during this request.
      const held = await router.mobileTask(agent, 'hold', {taskId:task.id});
      if (!held.ok || held.taskId !== task.id || held.phase !== 'active') return;
      while (!waiting.aborted && !agent.inbox.nextStep.length) {
        // Native Stop can be pressed outside DSH. This checks only its task state, not the screen.
        await delay(3000, undefined, {signal:waiting});
        const state = await router.mobileTask(agent, 'status', {taskId:task.id}, waiting);
        if (!state.ok || state.taskId !== task.id || state.phase !== 'active') break;
      }
    } catch (error) {
      if (!waiting.aborted) throw error;
    } finally {
      if (task.waiter === waiter) {
        delete task.waiter;
        if (tasks.get(agent.session) === task) await router.mobileTask(agent, 'resume', {taskId:task.id});
      }
    }
  });
  ctx.on('session/event', (session, event) => {
    const task = tasks.get(session);
    if (task && event.type === 'assistant/message' && event.data.stream?.some(record =>
        record.chunk?.type === 'finish' && record.chunk.reason?.kind === 'max-tokens')) task.truncated = true;
    if (task && event.type === 'agent/inbox/spliced' && event.data.target === 'next-step' && event.data.inserted?.length) {
      task.waiter?.abort();
      if (event.data.inserted.some(message => message.source?.kind === 'user'))
        return router.mobileTask(task.agent, 'wake', {taskId:task.id}).catch(() => {});
    }
    if (event.type === 'turn/end')
      return end(session, event.data.reason.kind === 'completed' ? 'finish' : 'stop').catch(() => {});
  });
  ctx.on('agent/disposed', ({agent}) => void end(agent.session, 'stop').catch(() => {}));
  ctx.effect(() => () => Promise.allSettled([...tasks.keys()].map(session => end(session, 'stop'))), 'Sugar: release phone tasks');

  ctx.tools.register(defineTool({
    name:'mobile_use',
    description:'Open or resume phone control. If the user only asks to open or operate an app (e.g. “打开计算器”), call mobile_use({}) or use screen="ask" to show Sugar’s native chooser. Use main or virtual only for an explicitly requested screen. An active task keeps its chosen screen. For ongoing companionship or watching until the user stops, set companion=true even when starting from an already selected screen.',
    parameters:{screen:{type:'string', enum:['ask','main','virtual'], default:'ask',
      description:'Default ask: omit or set ask when the user has not named a screen. Do not infer main from an app name. Use main or virtual only if the user specified that screen.'},
      companion:{type:'boolean', description:'true keeps this turn available for pet replies and background observer results until the ongoing task ends. false releases that hold so the turn can finish. Omit for an ordinary phone task or to keep the current setting.'}},
    output:{schema:{type:'object', additionalProperties:true, properties:{}},
      render:(_args, state) => [{type:'text', text:JSON.stringify(state)}]},
    async execute({screen = 'ask', companion}, {agent, signal}) {
      if (!agent?.session) throw Error('Invalid mobile_use session.');
      // A quick follow-up must not inherit the preceding turn's input authorization.
      const releasing = ending.get(agent.session);
      if (releasing) await releasing;
      signal.throwIfAborted();
      if (screen !== 'ask' && screen !== 'main' && screen !== 'virtual')
        throw Error('mobile_use screen must be ask, main or virtual.');
      let task = tasks.get(agent.session);
      const fresh = !task;
      if (fresh) {
        task = {agent, id:randomUUID()};
        tasks.set(agent.session, task);
      }
      try {
        let state = await router.mobileTask(agent, fresh ? 'begin' : 'status',
          fresh ? {taskId:task.id, screen} : {taskId:task.id}, signal);
        if (state.ok && (state.phase === 'adb' || state.phase === 'adb_disconnected')) {
          if (tasks.get(agent.session) === task) tasks.delete(agent.session);
          const next = state.phase === 'adb'
            ? 'Both Sugar screen modes are off. For UI that may use the main screen, load the android-adb skill and use the connected device. If the user needs background UI or wants their screen undisturbed, report that virtual screen setup is required.'
            : 'Both Sugar screen modes are off and ADB is disconnected. Ask the user to connect ADB in Sugar before proceeding.';
          return {...state, next};
        }
        if (state.ok && state.taskId) task.id = state.taskId;
        while (state.ok && ['choosing','disabled','permission','starting'].includes(state.phase)) {
          await delay(650, undefined, {signal});
          state = await router.mobileTask(agent, 'status', {taskId:task.id}, signal);
        }
        if (state.phase === 'stopped') {
          // Native stop covers a dismissed picker as well as an interrupted task.
          return {ok:false, taskId:state.taskId, phase:'stopped', screen:state.screen, retryable:false,
            next:'Phone control ended for this turn. Tell the user the operation was stopped and end the turn now. Do not retry mobile_use, run Bash or ADB, or wait for a screen choice. A new user request can start a new task.'};
        }
        if (state.ok && state.phase === 'active') {
          if (screen !== 'ask' && screen !== state.screen)
            return {...state, ok:false, error:'screen_conflict', next:`This turn is already on the ${state.screen} screen. Start a new phone task in a new turn to change screens.`};
          if (companion !== undefined) task.companion = companion === true;
          state.companion = task.companion === true;
          const driverHint = state.screen === 'main' ? 'Use phone.recents() for Recent apps.'
            : 'For ADB input on this virtual screen, target its current displayId.';
          return {...state, next:`The ${state.screen} screen is active. Start a fresh workspace Bash call or terminal and run mobile_use run task.py. Use phone.open_app("计算器") with the launcher name, phone.launch(package) with a known package, or phone.observe() when already there. ${driverHint} Use screen.get("package") for an observation's foreground package. For screenshot taps, use coordinates from the image you viewed with space="image". Group familiar actions in one script while you can check progress locally. At a meaningful choice or change of plan, use phone.status(text) in that same script to say what comes next and why. Speak to the user in this conversation's voice, including in the Bash and phone.wait descriptions the pet can show. When action feedback or a later observation confirms a new stage, replace the previous caption in that same script before waiting. Use phone.wait(..., description="…") to describe what you are waiting for and the cue that ends the wait. For a custom polling loop set phone.status(text) once before the loop. Poll for verified outcomes (including failure) and a cue that the original phase is still in progress. If that cue stays absent across samples and the new screen is unknown, return its image for model review immediately, before any tap or new wait. If the screen changed but you cannot identify it yet, say what you are checking. If it times out, inspect the last observation and tell the user what you can verify and what you will try before retrying. Return the current observation when a new choice or unexpected screen needs your judgment. For sustained observation, choose the script lifetime and your next review separately. Run a read-only Bash job in the background and use bounded job_output waits so you can inspect the screen or update the pet while it runs. Before taking over, stop the observer and take a fresh observation. See the mobile-use skill for the job and screen APIs.`};
        }
        return state;
      } catch (error) {
        await router.mobileTask(agent, 'stop', {taskId:task.id}).catch(() => {});
        throw error;
      }
    }
  }));
}
