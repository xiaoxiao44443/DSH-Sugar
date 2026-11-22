import assert from 'node:assert/strict';
import {mkdtemp, mkdir, copyFile, readFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {registerHooks} from 'node:module';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {runInNewContext} from 'node:vm';

const assets = fileURLToPath(new URL('../app/src/main/assets/guest/', import.meta.url));
const window = new EventTarget(), document = new EventTarget();
window.top = window;
window.SugarI18n = {add() {}};
const location = {protocol:'http:', hostname:'127.0.0.1'};
let plugin, cleanup, fullscreen = false, geometryChanged;
document.querySelector = selector => selector === '[data-rightbar-fullscreen]' && fullscreen ? {} : null;
const browser = {window, document, location, CustomEvent, MutationObserver: class {
  constructor(callback) { geometryChanged = callback; }
  observe() {}
  disconnect() { geometryChanged = null; }
}};
window.__ModuleLoader__ = {load: ({factory}) => { plugin = factory(); }};
runInNewContext(await readFile(join(assets, 'dsh-sugar-mobile-use/client.js'), 'utf8'), browser);
runInNewContext(await readFile(join(assets, '../sugar-mobile-controls.js'), 'utf8'), browser);
function store(snapshot) {
  const listeners = new Set();
  return {getSnapshot: () => snapshot, subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn); },
    set(value) { snapshot = value; for (const fn of listeners) fn(); }};
}
const byId = {a:{blank:false}, b:{blank:false}, draft:{blank:true}};
const list = store({phase:'ready', byId}), mounted = store('a'), panelInfo = store({activePanelId:null});
const ctx = {sessions:{list}, sidebarRight:{mounted}, layout:{panelInfo}, effect(fn) {cleanup = fn();}};
plugin.apply(ctx);
const reports = [], titleReports = [];
const post = message => {
  if (message.type === 'mobileConversation') reports.push(message.conversation);
  else if (message.type === 'mobileScreenTitles') titleReports.push(JSON.parse(JSON.stringify(message)));
};
const controls = window.SugarMobileControls.mount(post, () => {});
assert.equal(reports.at(-1),'a');  // The plugin can arrive before the shell.
controls.sendConversation();       // A newly connected port replays the current view.
assert.deepEqual(reports,['a','a']);
mounted.set('b');
assert.equal(reports.at(-1),'b');
list.set({phase:'ready',byId:{...byId, a:{blank:false, running:true}}});
assert.equal(reports.length,3);    // A background task did not change the selected chat.
panelInfo.set({activePanelId:'plugins'});
assert.equal(reports.at(-1),'');
panelInfo.set({activePanelId:null});
assert.equal(reports.at(-1),'b');
mounted.set('draft');
assert.equal(reports.at(-1),'');
list.set({phase:'ready',byId:{...byId,draft:{blank:false}}});
assert.equal(reports.at(-1),'draft'); // First prompt turns a blank session into a conversation.
fullscreen = true; geometryChanged();
assert.equal(reports.at(-1),'');
fullscreen = false; geometryChanged();
controls.setObscured(true);
mounted.set('b');
assert.equal(reports.at(-1),'');
controls.setObscured(false);
assert.equal(reports.at(-1),'b');
document.hidden = true; document.dispatchEvent(new Event('visibilitychange'));
assert.equal(reports.at(-1),'');
document.hidden = false; document.dispatchEvent(new Event('visibilitychange'));
assert.equal(reports.at(-1),'b');
window.dispatchEvent(new Event('pagehide'));
assert.equal(reports.at(-1),'');
window.dispatchEvent(new Event('pageshow'));
assert.equal(reports.at(-1),'b');
list.set({phase:'loading'});
assert.equal(reports.at(-1),'');
list.set({phase:'ready',byId});
cleanup();
assert.equal(reports.at(-1),'');
plugin.apply(ctx);                 // The shell can also arrive before the plugin.
assert.equal(reports.at(-1),'b');
assert.deepEqual(Object.keys(window.SugarMobile),['setup']);
const screenTitles = {a:{blank:false,title:'Chat A'}, b:{blank:false,title:'Chat B'},
  c:{blank:false,title:'Private unrelated chat'}};
list.set({phase:'ready',byId:screenTitles});
assert.equal(titleReports.length,0);
const watch = (id, conversations) => controls.receive({type:'mobileTitleWatch',watch:id,conversations});
watch('first',['a','b','missing']);
assert.deepEqual(titleReports.map(m=>[m.conversation,m.title]),[['a','Chat A'],['b','Chat B'],['missing','']]);
assert(titleReports.every(m=>m.watch==='first'));
const firstCount = titleReports.length;
list.set({phase:'ready',byId:{...screenTitles,c:{blank:false,title:'Other name'}}});
assert.equal(titleReports.length,firstCount);
list.set({phase:'ready',byId:{...screenTitles,a:{blank:false,title:'Renamed A'}}});
assert.deepEqual([titleReports.at(-1).conversation,titleReports.at(-1).title],['a','Renamed A']);
list.set({phase:'ready',byId:{...screenTitles,a:{blank:false}}});
assert.deepEqual([titleReports.at(-1).conversation,titleReports.at(-1).title],['a','']);
watch('second',['b']);
cleanup();
assert.equal(titleReports.at(-1).title,'');
plugin.apply(ctx);
assert.deepEqual(titleReports.at(-1),{type:'mobileScreenTitles',watch:'second',conversation:'b',title:'Chat B'});
const many = Object.fromEntries(Array.from({length:40},(_,i)=>['session-'+i,{blank:false,title:'名'.repeat(120)}]));
list.set({phase:'ready',byId:many});
watch('batch',Object.keys(many));
const batch = titleReports.filter(m=>m.watch==='batch');
assert.equal(batch.length,40);
assert(batch.every(m=>m.title.length===120 && JSON.stringify(m).length<2048));
watch('closed',[]);
const titlesOnClose = titleReports.length;
list.set({phase:'ready',byId:{...screenTitles,b:{blank:false,title:'After closing'}}});
assert.equal(titleReports.length,titlesOnClose);
controls.dispose();
assert.equal(reports.at(-1),'');
const count = reports.length;
mounted.set('a');
cleanup();
assert.equal(reports.length,count);
console.log('PASS mobile_use: visible conversation, reset/replay, scoped screen titles, live renames and unsubscribe');

const work = await mkdtemp(join(tmpdir(), 'sugar-control-plugin-'));
try {
  // The runtime installs the skill as a sibling of the Cordis plugin.
  const pluginDir = join(work,'dsh-sugar-mobile-use');
  await mkdir(pluginDir);
  for (const name of ['index.js','package.json']) await copyFile(join(assets,'dsh-sugar-mobile-use',name), join(pluginDir,name));
  await copyFile(join(assets,'mobile-use-guide.md'),join(pluginDir,'guide.md'));
  const actual = process.env.DSH_TEST_NODE_MODULES;
  const sdk = actual ? pathToFileURL(join(actual,'@deepseek-ai/dsh-tools/lib/index.js')).href
    : 'data:text/javascript,export const defineTool=tool=>tool';
  registerHooks({resolve(spec,ctx,next) {
    return spec === '/opt/dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js' ? {url:sdk, shortCircuit:true} : next(spec,ctx);
  }});
  const { apply } = await import(pathToFileURL(join(pluginDir,'index.js')).href);
  const states = new Map(), hooks = new Map(), calls = [];
  let tool, phonePolicy, delayFinish, adbRoute, disposal;
  globalThis[Symbol.for('dsh.sugar.projects')] = {async mobileTask(agent, method, {taskId,screen}) {
    const key = agent.session;
    calls.push([key,method,taskId,screen]);
    if (method === 'begin' && adbRoute && screen === 'ask') return {ok:true,phase:adbRoute,taskId:null};
    if (method === 'begin') states.set(key,{ok:true,taskId,phase:screen === 'ask' ? 'choosing' : 'active',screen,controlState:'agent',controlGeneration:0});
    if ((method === 'hold' || method === 'resume') && states.get(key)?.phase === 'active')
      states.get(key).holding = method === 'hold';
    if (method === 'finish') {
      if (delayFinish) await delayFinish;
      states.get(key).phase = states.get(key).screen === 'virtual' ? 'parked' : 'completed';
    } else if (method === 'stop') states.get(key).phase = 'stopped';
    return {...states.get(key)};
  }};
  apply({systemPrompt:{getSectionOrder:name => {assert.equal(name,'TOOL_COMPUTER_USE'); return 3000;},
    section:spec => {phonePolicy=spec;}}, tools:{register:t => {tool=t;}}, skills:{register(s) {
      assert.equal(s.name,'mobile-use');
      assert(s.content.includes('read_image') && s.content.includes('screen.nodes'));
      assert(s.content.includes('current project workspace'));
      assert(s.content.includes('adb exec-out screencap -p > screen.png') && s.content.includes('adb ui-dump > ui.xml'));
      assert(s.content.includes('phone.recents()'));
      assert(s.content.includes('phone.open_app("计算器")') && s.content.includes('ambiguous_app'));
      assert(s.content.includes('screen.get("window")') && s.content.includes('geometry_signature'));
      assert(s.content.includes('user_control') && s.content.includes('不能自动重放') && s.content.includes('不消耗模型 token'));
      assert(s.content.includes('borrowed') && s.content.includes('created') && s.content.includes('parked'));
      assert(s.content.includes('回到应用') && s.content.includes('不恢复 Agent'));
      const generalGuide = s.content.split('### 陪玩与持续看屏')[0];
      assert.match(generalGuide, /phone\.status\(/);
      assert.match(generalGuide, /角色口吻/);
      assert.match(generalGuide, /同一脚本中.*先更新气泡，再等待/);
      assert.match(generalGuide, /phone\.wait\(until=installed, timeout=45, description=/);
    }},
    effect:fn=>{disposal=fn();},on:(event,fn)=>hooks.set(event,fn)});
  assert.equal(phonePolicy.name, 'sugar:phone-operation');
  assert.match(phonePolicy.text, /Prefer mobile_use/);
  assert.match(phonePolicy.text, /when the user requests ADB/);
  assert.match(phonePolicy.text, /multi-step phone tasks.*phone\.status/);
  const user = text => ({type:'user/message',data:{role:'user',source:{kind:'user'},content:[{type:'text',text}]}});
  const start = {type:'turn/start'};
  function createAgent(events) {
    return {inbox:{nextStep:[]},session:{get seq(){return events.length;},eventAt:i=>events[i]},
      send(message, target, wakeup) {
        assert.equal(target, 'next-step');
        assert.equal(wakeup, false);
        this.inbox.nextStep.push(message);
      }};
  }
  const bEvents = [start,user('请用虚拟屏打开计算器')];
  const controller = new AbortController(), a = createAgent([start,user('打开手机上的应用')]), b = createAgent(bEvents);
  const shape = actual ? tool.parameters.properties : tool.parameters;
  assert.deepEqual(shape.screen.enum, ['ask','main','virtual']);
  assert.equal(shape.screen.default, 'ask');
  assert.equal(shape.companion.type, 'boolean');
  assert.match(tool.description, /打开计算器.*mobile_use\(\{\}\)/);
  assert(!tool.parameters.required?.includes('screen'));
  for (const [prompt, screen, expected] of [
    ['打开计算器', undefined, 'ask'],
    ['打开计算器', 'ask', 'ask'],
    ['用 ADB 截图', undefined, 'ask'],
    ['用虚拟屏打开计算器', undefined, 'ask'],
    ['在主屏打开计算器', 'main', 'main'],
    ['在虚拟屏打开计算器', 'virtual', 'virtual']
  ]) {
    const agent = createAgent([start,user(prompt)]), stop = new AbortController();
    const result = tool.execute(screen ? {screen} : {}, {agent,signal:stop.signal});
    assert.equal(states.get(agent.session).screen, expected);
    if (expected === 'ask') {
      stop.abort();
      await assert.rejects(result, {name:'AbortError'});
    } else await result;
  }
  const invalid = createAgent([start,user('打开计算器')]);
  await assert.rejects(tool.execute({screen:'adb'}, {agent:invalid,signal:controller.signal}), /screen.*must/);
  assert(!states.has(invalid.session));
  const stopped = createAgent([start,user('打开计算器')]);
  const firstChoice = tool.execute({}, {agent:stopped,signal:controller.signal});
  states.get(stopped.session).phase = 'stopped';  // User dismissed the native picker.
  for (const result of [await firstChoice, await tool.execute({}, {agent:stopped,signal:controller.signal})]) {
    assert.equal(result.ok, false);
    assert.equal(result.phase, 'stopped');
    assert.equal(result.retryable, false);
    assert.match(result.next, /end the turn now/);
  }
  assert.equal(calls.filter(([s,m]) => s === stopped.session && m === 'begin').length, 1);
  await hooks.get('session/event')(stopped.session,{type:'turn/end',data:{reason:{kind:'completed'}}});

  const fallback = createAgent([start,user('打开计算器')]);
  for (adbRoute of ['adb', 'adb_disconnected']) {
    const result = await tool.execute({}, {agent:fallback,signal:controller.signal});
    assert.equal(result.phase, adbRoute);
    assert.equal(result.taskId,null);
    assert.match(result.next, adbRoute === 'adb' ? /android-adb/ : /connect ADB/);
  }
  assert.deepEqual(calls.filter(([s])=>s===fallback.session).map(([,m])=>m),['begin','begin']);
  await hooks.get('session/event')(fallback.session,{type:'turn/end',data:{reason:{kind:'completed'}}});
  assert.equal(calls.filter(([s])=>s===fallback.session).length,2);
  adbRoute=undefined;
  const first = tool.execute({}, {agent:a, signal:controller.signal});
  assert.equal(states.get(a.session).phase,'choosing');
  const direct = await tool.execute({screen:'virtual'}, {agent:b, signal:controller.signal});
  assert.equal(direct.screen,'virtual');
  assert(direct.next.includes('phone.open_app("计算器")') && direct.next.includes('phone.launch(package)'));
  assert(direct.next.includes('displayId'));
  assert.match(direct.next, /task_origin.*Do not switch to main.*app_on_other_display.*another conversation/);
  assert(!direct.next.includes('screencap'));
  assert.doesNotMatch(direct.next,/phone\.recents/);
  assert.notEqual(states.get(a.session).taskId,direct.taskId);
  states.get(a.session).screen='main';
  states.get(a.session).phase='active';
  const selected = await first;
  assert.equal(selected.screen,'main');
  for (const active of [selected, direct]) {
    assert.match(active.next, /phone\.status\(text\).*why/);
    assert.match(active.next, /observation confirms a new stage.*replace the previous caption.*before waiting/);
    assert.match(active.next, /cannot identify it yet.*what you are checking/);
    assert.match(active.next, /cue.*times out.*last observation/);
    assert.match(active.next, /phone\.wait\(\.\.\., description=.*cue.*custom polling loop.*phone\.status/);
  }
  const splice = (target, kind) => ({type:'agent/inbox/spliced', data:{target, inserted:[{source:{kind}}]}});
  await hooks.get('session/event')(a.session, splice('next-step', 'user'));
  await hooks.get('session/event')(a.session, splice('next-turn', 'user'));
  await hooks.get('session/event')(a.session, splice('next-step', 'system'));
  assert.deepEqual(calls.filter(([,m]) => m === 'wake').map(([s,,id]) => [s,id]), [[a.session, selected.taskId]]);
  assert(selected.next.includes('phone.open_app("计算器")') && selected.next.includes('phone.launch(package)'));
  assert.match(selected.next,/mobile_use run task\.py/);
  assert.match(selected.next,/phone\.recents\(\)/);
  assert(selected.next.includes('screen.get("window")'));
  assert.match(selected.next,/after a window moves or resizes, observe again/);
  assert.doesNotMatch(selected.next,/window_mode/);
  const repeated = await tool.execute({}, {agent:a,signal:controller.signal});
  const resume = await tool.execute({screen:'ask'}, {agent:a,signal:controller.signal});
  assert.equal(resume.taskId,selected.taskId);
  assert.equal(resume.screen,'main');
  const conflict = await tool.execute({screen:'virtual'}, {agent:a,signal:controller.signal});
  assert.equal(conflict.ok,false);
  assert.equal(conflict.error,'screen_conflict');
  assert.equal(states.get(a.session).phase,'active');
  assert.equal(repeated.taskId,selected.taskId);
  assert.equal(repeated.next,selected.next);
  assert.equal(calls.filter(([s,m])=>s===a.session && m==='begin').length,1);
  await hooks.get('session/event')(a.session,{type:'turn/end',data:{reason:{kind:'completed'}}});
  assert.equal(states.get(a.session).phase,'completed');
  assert.equal(states.get(b.session).phase,'active');
  const canceled = tool.execute({}, {agent:a, signal:controller.signal});
  controller.abort();
  await assert.rejects(canceled);
  assert.equal(states.get(a.session).phase,'stopped');
  assert.equal(states.get(b.session).phase,'active');
  assert.equal(calls.filter(([s,m])=>s===b.session && m==='begin').length,1);
  const finished = Promise.withResolvers();
  delayFinish = finished.promise;
  const end = hooks.get('session/event')(b.session, {type:'turn/end', data:{reason:{kind:'completed'}}});
  bEvents.push({type:'turn/end'},start,user('打开计算器，让我选择怎么打开'));
  const next = tool.execute({}, {agent:b, signal:new AbortController().signal});
  assert.equal(calls.filter(([s,m]) => s === b.session && m === 'begin').length, 1);
  finished.resolve();
  await end;
  await new Promise(resolve => setImmediate(resolve));
  const newState = states.get(b.session);
  assert.equal(newState.phase, 'choosing');
  assert.notEqual(newState.taskId, direct.taskId);
  newState.phase = 'active';
  newState.screen = 'virtual';
  await next;
  await hooks.get('session/event')(b.session, {type:'turn/end', data:{reason:{kind:'completed'}}});
  assert.equal(states.get(b.session).phase, 'parked');
  console.log('PASS mobile_use: native screen choice, turn-bound control, retained virtual screen and fresh choice on rapid follow-up');

  const take = createAgent([start,user('在虚拟屏操作')]);
  const takeSignal = new AbortController().signal;
  await tool.execute({screen:'virtual'}, {agent:take, signal:takeSignal});
  const takeState = states.get(take.session);
  const tick = () => new Promise(resolve=>setImmediate(resolve));
  let admitted = 0, dispatched = 0;
  const preStep = (messages=[], signal=takeSignal) => hooks.get('agent/pre-step')({agent:take, signal},
    async () => { admitted++; return {kind:'enter', messages}; });
  const pendingTool = () => hooks.get('tools/pre-execute')({agent:take, signal:takeSignal, name:'Bash'},
    async () => { dispatched++; return {kind:'allow'}; });
  takeState.controlState = 'taking_over'; takeState.controlGeneration++;
  const firstUserMessage = {id:'first-user', role:'user', source:{kind:'user'}, content:[]};
  const gated = preStep([firstUserMessage]);
  await tick();
  assert.equal(admitted, 0, 'takeover waits before another model request');
  const interruption = {id:'new-user', source:{kind:'user'}};
  take.inbox.nextStep.push(interruption);
  await hooks.get('session/event')(take.session, splice('next-step','user'));
  await tick();
  assert.equal(admitted, 0, 'user messages do not bypass the takeover gate');
  assert.equal((await pendingTool()).info.code, 'user_control');
  takeState.controlState = 'focusing';
  assert.equal((await pendingTool()).info.code, 'user_control');
  await tick(); assert.equal(admitted, 0, 'UI focus does not return control to the agent');
  takeState.controlState = 'user';
  takeState.controlState = 'restoring'; takeState.controlGeneration++;
  assert.equal((await pendingTool()).kind, 'deny');
  takeState.controlState = 'agent';
  const returned = await gated;
  assert.equal(returned.messages[0], firstUserMessage);
  assert.equal(returned.messages.length, 2);
  assert.match(returned.messages[1].content[0].text, /phone\.observe.*do not automatically retry or replay/);
  assert.equal(returned.messages[1].source.kind, 'sugar-phone-control');
  assert.deepEqual(take.inbox.nextStep, [interruption], 'new user input is preserved');
  take.inbox.nextStep.length = 0;
  assert.equal((await pendingTool()).kind, 'allow', 'a new observation tool after the fresh step is allowed');
  assert.equal(dispatched, 1, 'denied tools were never dispatched or replayed');
  assert.equal((await preStep()).messages.length, 0, 'the return instruction is injected once');

  // A complete, short takeover between polls still invalidates the prepared action.
  takeState.controlGeneration += 2;
  assert.equal((await pendingTool()).info.code, 'stale_observation');
  assert.equal(dispatched, 1);
  assert.equal((await preStep()).messages.length, 1);
  assert.equal((await pendingTool()).kind, 'allow');

  // An ordinary (non-companion) turn stays open until the user returns control.
  takeState.controlState = 'user'; takeState.controlGeneration++;
  let ended = false;
  const boundary = hooks.get('agent/turn-stopping')({agent:take,signal:takeSignal}).then(()=>{ended=true;});
  await tick(); assert.equal(ended, false);
  takeState.controlState = 'agent'; takeState.controlGeneration++;
  await boundary;
  assert.equal(take.inbox.nextStep.length, 1);
  const queuedNotice = take.inbox.nextStep.splice(0);
  assert.deepEqual((await preStep(queuedNotice)).messages, queuedNotice, 'turn-stopping return notice is not duplicated');
  assert.equal(states.get(take.session).phase, 'active');
  assert.equal(calls.filter(([s,m])=>s===take.session && m==='begin').length, 1);

  takeState.controlState = 'unknown'; takeState.controlError = 'restore_failed'; takeState.controlGeneration++;
  const canceledGate = new AbortController();
  const gate = preStep([], canceledGate.signal);
  await tick(); canceledGate.abort();
  await assert.rejects(gate, {name:'AbortError'});
  assert.equal((await pendingTool()).kind, 'deny', 'unknown control fails closed');
  const stoppedGate = hooks.get('agent/turn-stopping')({agent:take,signal:takeSignal});
  await tick(); takeState.phase = 'stopped';
  await stoppedGate;
  assert.equal(take.inbox.nextStep.length, 0, 'native stop does not claim control was returned');
  await hooks.get('session/event')(take.session, {type:'turn/end',data:{reason:{kind:'aborted'}}});

  const disposeTake = createAgent([start,user('虚拟屏')]);
  await tool.execute({screen:'virtual'}, {agent:disposeTake, signal:takeSignal});
  states.get(disposeTake.session).controlState = 'user';
  const disposeWait = hooks.get('agent/turn-stopping')({agent:disposeTake, signal:takeSignal});
  await tick();
  await hooks.get('agent/disposed')({agent:disposeTake});
  await disposeWait;
  assert.equal(states.get(disposeTake.session).phase, 'stopped');
  console.log('PASS mobile_use: cancellable takeover gates, preserved inbox, one fresh-observation notice, no stale-tool replay, short takeover generation, native stop and disposal');

  for (const boundary of ['pre-step', 'turn-stopping', 'companion']) {
    const closed = createAgent([start,user('虚拟屏继续操作')]);
    await tool.execute({screen:'virtual', companion:boundary==='companion'}, {agent:closed, signal:takeSignal});
    const closedState = states.get(closed.session);
    const original = {id:'original-'+boundary, role:'user', source:{kind:'user'}, content:[]};
    const arriving = {id:'arriving-'+boundary, source:{kind:'user'}};
    const admitClosed = messages => hooks.get('agent/pre-step')({agent:closed,signal:takeSignal},
      async () => ({kind:'enter', messages}));
    if (boundary !== 'companion') {
      closedState.controlState = 'user'; closedState.controlGeneration++;
    }
    const pending = boundary === 'pre-step' ? admitClosed([original]) :
      hooks.get('agent/turn-stopping')({agent:closed,signal:takeSignal});
    await tick();
    if (boundary === 'companion') assert.equal(closedState.holding, true);
    closed.inbox.nextStep.push(arriving);
    closedState.phase = 'stopped'; closedState.controlState = 'closed';
    closedState.error = {code:'task_gone', message:'The app task no longer exists.'};
    const result = await pending;
    let messages;
    if (boundary === 'pre-step') {
      messages = result.messages;
      assert.equal(messages[0], original);
      assert.deepEqual(closed.inbox.nextStep, [arriving]);
    } else {
      assert.equal(closed.inbox.nextStep[0], arriving);
      messages = (await admitClosed(closed.inbox.nextStep.splice(0))).messages;
    }
    const notices = messages.filter(message => message.source?.kind === 'sugar-phone-control');
    assert.equal(notices.length, 1, boundary+' emits one terminal app-closed notice');
    assert.match(notices[0].content[0].text, /app task has closed.*phone task has ended.*Tell the user/);
    assert.match(notices[0].content[0].text, /Do not automatically reopen.*retry.*Wait for a new user request/);
    assert.doesNotMatch(notices[0].content[0].text, /phone\.observe/);
    assert.equal((await admitClosed([])).messages.length, 0);
    closed.inbox.nextStep.length = 0;
    await hooks.get('agent/turn-stopping')({agent:closed,signal:takeSignal});
    assert.equal(closed.inbox.nextStep.length, 0, 'task_gone never starts another automatic model step');
    await hooks.get('session/event')(closed.session, {type:'turn/end',data:{reason:{kind:'aborted'}}});
  }
  console.log('PASS mobile_use: app closed during takeover ends the task once, preserves user messages and never requests observe or automatic reopen');

  for (const phase of ['closing', 'close_failed']) {
    for (const boundary of ['pre-step', 'turn-stopping']) {
      const closing = createAgent([start,user('关闭虚拟屏')]);
      await tool.execute({screen:'virtual'}, {agent:closing,signal:takeSignal});
      const current = states.get(closing.session);
      current.controlState = 'user'; current.controlGeneration++;
      const userMessage = {id:phase+'-'+boundary,source:{kind:'user'}};
      const enter = messages => hooks.get('agent/pre-step')({agent:closing,signal:takeSignal},
        async () => ({kind:'enter',messages}));
      const pending = boundary === 'pre-step' ? enter([userMessage])
        : hooks.get('agent/turn-stopping')({agent:closing,signal:takeSignal});
      await tick();
      current.phase = phase; current.controlState = phase;
      const result = await pending;
      const messages = boundary === 'pre-step' ? result.messages
        : (await enter(closing.inbox.nextStep.splice(0))).messages;
      if (boundary === 'pre-step') assert.equal(messages[0], userMessage);
      const notice = messages.find(message => message.source?.kind === 'sugar-phone-control').content[0].text;
      assert.match(notice, phase === 'closing' ? /closing is in progress/ : /closing failed.*retry Close in Sugar/);
      assert.doesNotMatch(notice, /phone\.observe/);
      assert.equal((await enter([])).messages.length, 0);
      await hooks.get('agent/turn-stopping')({agent:closing,signal:takeSignal});
      assert.equal(closing.inbox.nextStep.length, 0);
      assert.equal((await tool.execute({}, {agent:closing,signal:takeSignal})).phase, phase);
      assert.equal(calls.filter(([s,m])=>s===closing.session && m==='begin').length, 1, 'terminal cleanup never reopens the screen');
      await hooks.get('session/event')(closing.session,{type:'turn/end',data:{reason:{kind:'aborted'}}});
    }
  }
  console.log('PASS mobile_use: closing and close_failed release takeover waits, explain cleanup once and never reopen a screen');

  const flow = createAgent([start,user('陪我玩，直到我说结束')]);
  const flowSignal = new AbortController().signal;
  const call = args => tool.execute(args, {agent:flow,signal:flowSignal});
  const stopping = (signal=flowSignal) => hooks.get('agent/turn-stopping')({agent:flow,turn:1,signal});
  assert.equal((await call({screen:'main'})).companion, false);
  await stopping(); // Finite phone operations release at their normal boundary.
  const watch = await call({companion:true});
  assert.equal(watch.companion,true);
  assert.equal((await call({})).companion,true);
  const nextTick = () => new Promise(resolve=>setImmediate(resolve));
  for (const kind of ['user','tool-jobs','user']) {
    let returned = false;
    const wait = stopping().then(()=>{returned=true;});
    await nextTick();
    assert.equal(returned,false);
    assert.equal(states.get(flow.session).holding,true,'the pet stops timing during the same-turn hold');
    await hooks.get('session/event')(flow.session,splice('next-turn','user'));
    await hooks.get('session/event')(a.session,splice('next-step','user'));
    assert.equal(returned,false);
    await hooks.get('session/event')(flow.session,splice('next-step',kind));
    await wait;
    assert.equal(states.get(flow.session).holding,false,'resume before the next model step');
    assert.equal((await call({})).taskId,watch.taskId);
  }
  const owned = calls.filter(([s])=>s===flow.session);
  assert.equal(owned.filter(([,m])=>m==='begin').length,1);
  assert.equal(owned.filter(([,m])=>m==='wake').length,2);
  flow.inbox.nextStep.push({id:'already-received'});
  await stopping();
  flow.inbox.nextStep.length=0;
  await call({companion:false});
  await stopping();
  await call({companion:true});
  const abort = new AbortController();
  const abortWait = stopping(abort.signal);
  abort.abort();
  await abortWait;
  assert.equal(states.get(flow.session).holding,false,'aborting a hold resumes even if its entry request is in flight');
  const nativeStop = stopping();
  states.get(flow.session).phase='stopped'; // Also releases when the device stops without an agent job.
  await nativeStop;
  states.get(flow.session).phase='active';
  await hooks.get('session/event')(flow.session,{type:'assistant/message',data:{stream:[
    {type:'chunk',chunk:{type:'finish',reason:{kind:'max-tokens'}}}
  ]}});
  await stopping(); // A truncated reply must release instead of keeping an unusable model turn.
  await hooks.get('session/event')(flow.session,{type:'turn/end',data:{reason:{kind:'max-tokens'}}});
  const reselected = await call({screen:'main',companion:true});
  assert.notEqual(reselected.taskId,watch.taskId);
  const disposed = stopping();
  await disposal();
  await disposed;
  assert.equal(states.get(flow.session).phase,'stopped');
  assert.equal(calls.filter(([session, method]) => session === flow.session && method === 'begin').length,2);
  console.log('PASS mobile_use: companion holds between replies, wakes on same-turn input/jobs, and releases on stop, abort, truncation or disposal');
} finally { await rm(work,{recursive:true,force:true}); }
