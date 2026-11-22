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
    text:'Use the user’s current language for all user-facing text, including Bash description, phone.wait description and phone.status; use Chinese for Chinese requests. Keep commands, code, API names and package names unchanged. Prefer mobile_use for requested UI interaction: if the user only asks to open or operate an app, call mobile_use({}) so they can choose the screen. Use connected ADB when the user requests ADB, or when the selected driver lacks the required action. For diagnostics (model, SoC, RAM or Android version), transfers and other non-UI tasks, check adb_status or adb get-state first; if unavailable, check sugar-root --status and use only already enabled, authorized Root. If neither is available for a non-UI/background task, stop and explain how to connect ADB in Sugar. Accessibility can be an alternative, but occupies the current screen with visible actions. Ask before switching to it through mobile_use, unless the user already approved this route for the task. Do not automatically request Root authorization. During user takeover, do not retry user_control errors or bypass the selected screen through ADB or Root. After control returns, observe the current screen before any new input; never replay interrupted actions automatically. During multi-step phone tasks, use phone.status in the action script to tell the user why the next step serves their goal, in the voice of this conversation. For an explicit request to stay with the user, keep watching or wait for further messages, open or update mobile_use({companion:true}). It keeps the selected screen between replies, even after the observer script exits. Use companion:false when that ongoing request finishes. See the mobile-use skill for progress, accompanying play and waiting for screen cues or user messages.'
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
    task.controlWaiter?.abort();
    const release = router.mobileTask(task.agent, method, {taskId:task.id});
    ending.set(session, release);
    try { await release; } finally {
      if (ending.get(session) === release) ending.delete(session);
    }
  }
  const active = (task, state) => state.ok && state.taskId === task.id && state.phase === 'active';
  const userControl = state => state.controlState != null && state.controlState !== 'agent';
  const observeAgain = 'The user has returned phone control. The screen may have changed during takeover. Before any phone input, call phone.observe() and review the current screen. Discard old observation IDs, node references, coordinates and pending actions; do not automatically retry or replay the interrupted script. Preserve and follow any new user messages.';
  const screenClosing = 'The user is closing this virtual screen. Phone control for this task has ended; native cleanup is still returning borrowed app tasks or ending tasks created here. Tell the user that closing is in progress. Do not retry phone operations, reopen apps, or start another screen. Wait for a new user request after cleanup finishes.';
  const screenCloseFailed = 'Phone control for this task has ended, but closing the virtual screen could not be verified. Sugar retained the display to protect its app tasks. Tell the user that closing failed and they can retry Close in Sugar. Do not operate the app, recreate the screen, reopen apps, or retry automatically.';
  const appClosed = 'The original app task has closed during takeover, and the current phone task has ended. Tell the user that the app was closed. Do not automatically reopen the app, retry phone operations, or start another phone task. Wait for a new user request. Preserve any new user messages.';
  const controlMessage = (text=observeAgain) => ({id:randomUUID(), role:'user', source:{kind:'sugar-phone-control', form:'instructions'},
    content:[{type:'text', text}]});
  const taskGone = (task, state) => state.taskId === task.id && state.phase === 'stopped' && state.error?.code === 'task_gone';
  async function status(task, signal) {
    const state = await router.mobileTask(task.agent, 'status', {taskId:task.id}, signal);
    const terminal = taskGone(task, state) ? appClosed
      : state.phase === 'close_failed' || state.controlState === 'close_failed' ? screenCloseFailed
      : state.phase === 'closing' || state.controlState === 'closing' ? screenClosing : null;
    if (terminal) {
      task.terminalNotice = terminal;
      task.needsObserve = false;
    }
    if (active(task, state)) {
      if (userControl(state) || (task.controlGeneration != null && state.controlGeneration != null &&
          task.controlGeneration !== state.controlGeneration)) task.needsObserve = true;
      if (state.controlGeneration != null) task.controlGeneration = state.controlGeneration;
    }
    return state;
  }
  async function waitForControl(task, signal, initial) {
    const waiter = new AbortController();
    task.controlWaiter = waiter;
    const waiting = AbortSignal.any([signal, waiter.signal]);
    try {
      let state = initial || await status(task, waiting);
      while (tasks.get(task.agent.session) === task && active(task, state) && userControl(state) && !task.terminalNotice) {
        // Native state only: no screenshot, model request, or user-inbox consumption.
        await delay(750, undefined, {signal:waiting});
        state = await status(task, waiting);
      }
      waiting.throwIfAborted();
      return state;
    } finally {
      if (task.controlWaiter === waiter) delete task.controlWaiter;
    }
  }
  function queueClosedMessage(task) {
    if (task.terminalNotice && task.terminalNotified !== task.terminalNotice && task.terminalQueued !== task.terminalNotice) {
      task.terminalQueued = task.terminalNotice;
      task.agent.send(controlMessage(task.terminalNotice), 'next-step', false);
    }
  }
  function queueControlMessage(task) {
    if (task.needsObserve && !task.resumeQueued) {
      task.resumeQueued = true;
      // The stopping hook's caller rechecks next-step input without ending this turn.
      task.agent.send(controlMessage(), 'next-step', false);
    }
  }
  ctx.on('agent/pre-step', async ({agent, signal}, next) => {
    const task = tasks.get(agent.session);
    const state = task && await waitForControl(task, signal);
    signal.throwIfAborted();
    const decision = await next();
    if (decision.kind === 'enter' && tasks.get(agent.session) === task && task?.terminalNotice && task.terminalNotified !== task.terminalNotice) {
      // A terminal close supersedes a return notice queued before another takeover.
      const messages = decision.messages.filter(message => !(message.source?.kind === 'sugar-phone-control' &&
        message.content?.some(block => block.type === 'text' && block.text === observeAgain)));
      if (task.terminalQueued !== task.terminalNotice) messages.push(controlMessage(task.terminalNotice));
      task.terminalNotified = task.terminalNotice;
      delete task.terminalQueued;
      task.resumeQueued = false;
      return {...decision, messages};
    }
    if (decision.kind === 'enter' && tasks.get(agent.session) === task && task?.needsObserve && active(task, state)) {
      const messages = task.resumeQueued ? decision.messages : [...decision.messages, controlMessage()];
      task.needsObserve = false;
      task.resumeQueued = false;
      return {...decision, messages};
    }
    return decision;
  });
  ctx.on('tools/pre-execute', async ({agent, signal}, next) => {
    const task = agent && tasks.get(agent.session);
    if (!task) return next();
    const state = await status(task, signal);
    signal.throwIfAborted();
    if (active(task, state) && (userControl(state) || task.needsObserve)) {
      // Never wait and then dispatch an action prepared before the user's takeover.
      return {kind:'deny', reason:'Phone control changed. Do not replay this pending tool call. Wait for return, then observe the current screen and plan again.',
        info:{name:'MobileError', code:userControl(state) ? 'user_control' : 'stale_observation'}};
    }
    return next();
  });
  // The rc.5 loop awaits this hook after the model's last step, then checks its inbox again.
  // Both takeover and companion waits preserve the selected screen without model calls.
  ctx.on('agent/turn-stopping', async ({agent, signal}) => {
    const task = tasks.get(agent.session);
    if (!task || task.truncated || signal.aborted) return;
    let state;
    try { state = await waitForControl(task, signal); }
    catch (error) {
      if (signal.aborted || tasks.get(agent.session) !== task) return;
      throw error;
    }
    queueClosedMessage(task);
    if (!active(task, state) || task.terminalNotice) return;
    queueControlMessage(task);
    if (!task.companion || agent.inbox.nextStep.length) return;
    const waiter = new AbortController();
    task.waiter = waiter;
    const waiting = AbortSignal.any([signal, waiter.signal]);
    try {
      // Order both edges on the bridge, including inbox input during the hold request.
      const held = await router.mobileTask(agent, 'hold', {taskId:task.id});
      if (!active(task, held)) return;
      while (!waiting.aborted && !agent.inbox.nextStep.length) {
        await delay(3000, undefined, {signal:waiting});
        state = await status(task, waiting);
        queueClosedMessage(task);
        if (!active(task, state) || task.terminalNotice) break;
        if (userControl(state)) state = await waitForControl(task, waiting, state);
        queueClosedMessage(task);
        if (!active(task, state) || task.terminalNotice) break;
        queueControlMessage(task);
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
          if (task.controlGeneration == null && state.controlGeneration != null) task.controlGeneration = state.controlGeneration;
          if (screen !== 'ask' && screen !== state.screen)
            return {...state, ok:false, error:'screen_conflict', next:`This turn is already on the ${state.screen} screen. Start a new phone task in a new turn to change screens.`};
          if (companion !== undefined) task.companion = companion === true;
          state.companion = task.companion === true;
          if (userControl(state)) {
            task.needsObserve = true;
            return {...state, next:'The user is controlling this screen. Do not retry phone actions or switch drivers. Sugar will wait between model steps until control returns, then require a fresh observation.'};
          }
          const driverHint = state.screen === 'main' ? 'Use phone.recents() for Recent apps. An already open system floating window is part of main. Inspect screen.get("window") for its display-coordinate bounds; after a window moves or resizes, observe again before acting. Use node bounds or coordinates from the viewed screenshot, not window-local offsets.'
            : 'For ADB input on this virtual screen, target its current displayId. Opening an app can borrow its existing main-screen task or create one; use the returned task_origin. Do not switch to main or close an existing app first. An app_on_other_display, app_busy or ambiguous_task error requires resolving the conflict, not taking another conversation’s task or automatically retrying. Normal completion keeps this screen parked. See the mobile-use skill for task ownership and explicit screen closure.';
          return {...state, next:`The ${state.screen} screen is active. Start a fresh workspace Bash call or terminal and run mobile_use run task.py. Use phone.open_app("计算器") with the launcher name, phone.launch(package) with a known package, or phone.observe() when already there. ${driverHint} Use screen.get("package") for an observation's foreground package. For screenshot taps, use coordinates from the image you viewed with space="image". Group familiar actions in one script while you can check progress locally. At a meaningful choice or change of plan, use phone.status(text) in that same script to say what comes next and why. Use the user’s current language for all user-facing text, including Bash description, phone.wait description and phone.status; use Chinese for Chinese requests. Keep commands, code, API names and package names unchanged. Speak to the user in this conversation's voice, including in the descriptions the pet can show. When action feedback or a later observation confirms a new stage, replace the previous caption in that same script before waiting. Use phone.wait(..., description="…") to describe what you are waiting for and the cue that ends the wait. For a custom polling loop set phone.status(text) once before the loop. Poll for verified outcomes (including failure) and a cue that the original phase is still in progress. If that cue stays absent across samples and the new screen is unknown, return its image for model review immediately, before any tap or new wait. If the screen changed but you cannot identify it yet, say what you are checking. If it times out, inspect the last observation and tell the user what you can verify and what you will try before retrying. Return the current observation when a new choice or unexpected screen needs your judgment. For sustained observation, choose the script lifetime and your next review separately. Run a read-only Bash job in the background and use bounded job_output waits so you can inspect the screen or update the pet while it runs. Before taking over, stop the observer and take a fresh observation. See the mobile-use skill for the job and screen APIs.`};
        }
        return state;
      } catch (error) {
        await router.mobileTask(agent, 'stop', {taskId:task.id}).catch(() => {});
        throw error;
      }
    }
  }));
}
