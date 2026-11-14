// Exercises the local Web plugin against the service contracts of the bundled DSH.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = fs.readFileSync(process.argv[2] ||
  path.resolve(__dirname, '../app/src/main/assets/guest/dsh-sugar-navigation/client.js'), 'utf8');
const settle = () => new Promise(resolve => setImmediate(resolve));

function fixture(host = '127.0.0.1', freshProfile = false) {
  let active = freshProfile ? undefined : 'a', panel = null, drawer = false, cancelOpen = false, dispose, navEvents = 0;
  let hasWorkspace = !freshProfile;
  let held, rightTab = 'file', deferLayout = false;
  const layoutObservers = new Set();
  const openTabs = ['file', 'second-file'];
  const right = new Set(), archived = [], byId = {};
  for (const [id, blank] of [['a', false], ['b', false], ['draft', true]]) {
    byId[id] = {id, blank, retainedBy: {}};
  }
  function use(id) {
    active = id;
    panel = null;
    for (const row of Object.values(byId)) row.retainedBy.mainView = row.id === id ? 1 : 0;
  }
  use(active);
  const subscribers = new Set();
  const source = read => ({getSnapshot:read, subscribe:fn => { subscribers.add(fn); return () => subscribers.delete(fn); }});
  const ctx = {
    locale: {bind: name => { assert.equal(name,'conversation'); return key => key === 'input.stop' ? '停止生成' : key; }},
    sessions: {list: source(() => ({phase: 'ready', byId})), subagentAddress: () => undefined},
    workspaces: {list: source(() => ({phase: 'ready', archivedSessionIds: archived,
      items: hasWorkspace ? [{workspaceId: 'project', sessionIds: Object.keys(byId)}] : []}))},
    layout: {panelInfo: source(() => ({activePanelId: panel})), selectPanel: id => { panel = id; },
      toggleSidebar: () => { drawer = !drawer; paintLayout(); }},
    sidebarRight: {mounted: source(() => panel ? undefined : active), active: () => rightTab ? {id: rightTab} : undefined,
      focus: id => { assert.ok(openTabs.includes(id)); rightTab = id; },
      openTabs: source(() => openTabs.map(tabId => ({sessionId: 'a', tabId}))), isExpanded: () => !panel && right.has(active),
      toggleExpanded: () => { if (right.has(active)) right.delete(active); else right.add(active); paintLayout(); }},
    uiWorkspace: {openSession: use, openWorkspace: async (id, prepare) => {
      assert.equal(id, 'project');
      if (cancelOpen) { await new Promise(resolve => { held = resolve; }); return; }
      prepare('draft');
      use('draft');
    }},
    effect: apply => { dispose = apply(); }
  };
  function surfaceNode(attributes, width) {
    const properties = new Map();
    return {
      attributes, isConnected:true, offsetWidth:width, properties,
      style:{setProperty:(key,value) => properties.set(key,value), removeProperty:key => properties.delete(key)},
      getBoundingClientRect:() => ({width}), hasAttribute:key => key in attributes,
      getAttribute:key => attributes[key], setAttribute:(key,value) => { attributes[key]=value; },
      removeAttribute:key => { delete attributes[key]; }
    };
  }
  const dock = surfaceNode({'data-sidebar-right-session':'a','data-sidebar-right-panel':'fullscreen'},400);
  const center = surfaceNode({},400), rightColumn = surfaceNode({},400);
  rightColumn.previousElementSibling = center;
  dock.closest = () => rightColumn;
  dock.querySelector = query => query.includes('data-dockkit-host') ? {} : null;
  const drawerFrame = surfaceNode({},400);
  drawerFrame.querySelector = () => ({nextElementSibling:{offsetWidth:280}});
  function paintLayout() {
    if (deferLayout) return;
    if (right.has(active)) dock.setAttribute('data-sidebar-right-open',''); else dock.removeAttribute('data-sidebar-right-open');
    if (drawer) drawerFrame.removeAttribute('data-sidebar-collapsed'); else drawerFrame.setAttribute('data-sidebar-collapsed','');
    for (const callback of layoutObservers) queueMicrotask(callback);
  }
  const pointerListeners = new Set();
  const document = {getElementById:() => null,querySelectorAll:() => [dock],
    addEventListener(type, fn) { if (type === 'pointerdown') pointerListeners.add(fn); },
    removeEventListener(type, fn) { if (type === 'pointerdown') pointerListeners.delete(fn); },
    createElement:() => ({remove() {}}),head:{appendChild() {}},documentElement: {}, querySelector: query => query === '[data-sidebar-drawer]' ? {} : query.includes('data-sidebar-drawer') ?
    (drawer ? drawerFrame : null) : {getAttribute: () => active}};
  const window = {dispatchEvent: event => {
    assert.equal(event.type, 'sugar-navigation-state');
    navEvents++;
  }, __ModuleLoader__: {load: ({factory}) => factory().apply(ctx)}};
  window.top = window;
  vm.runInNewContext(script, {window, document, location: {protocol: 'http:', hostname: host}, console,
    MutationObserver: class {
      constructor(callback) { this.callback=callback; }
      observe(node) { if (node===dock || node===drawerFrame) layoutObservers.add(this.callback); }
      disconnect() { layoutObservers.delete(this.callback); }
    }, setTimeout,clearTimeout,
    CustomEvent: class {constructor(type, {detail}) {this.type = type; this.detail = detail;}},
    performance, getComputedStyle:() => ({backgroundColor:'rgba(0, 0, 0, 0)'}), matchMedia:() => ({matches:false}),
    requestAnimationFrame: fn => setImmediate(() => fn(performance.now() + 160)), cancelAnimationFrame: clearImmediate});
  function composerPress(label, pointerType, disabled = false) {
    let prevented = false;
    const button = {disabled, getAttribute: () => label};
    const target = {closest: selector => selector === '[data-composer-card] button[aria-label]' ? button : null};
    for (const listener of pointerListeners) listener({target, pointerType, isPrimary:true, button:0, preventDefault() { prevented = true; }});
    return prevented;
  }
  return {composerPress, window, ctx, dock, center, drawerFrame, byId, use, archived, publish:() => subscribers.forEach(fn=>fn()), panel: () => panel, setPanel: id => {panel = id;},
    setRightTab: tab => { rightTab = tab; }, drawer: () => drawer, expand: () => { right.add(active); paintLayout(); }, expanded: () => right.has(active),
    holdLayout:() => { deferLayout=true; }, commitLayout:() => { deferLayout=false; paintLayout(); },
    active: () => active, defaultWorkspaceReady:() => { hasWorkspace = true; },
    cancelNext: () => {cancelOpen = true;}, finishOpen: () => held(),
    dispose: () => dispose(), events: () => navEvents};
}

(async () => {
  const fresh = fixture('127.0.0.1',true), freshNav = fresh.window.SugarNavigation;
  assert.equal(freshNav.state().canBack,false);
  fresh.defaultWorkspaceReady();
  fresh.publish(); await settle();
  fresh.use('draft'); fresh.publish(); await settle();
  assert.equal(freshNav.state().isHome,true);
  assert.equal(freshNav.state().canBack,false,'initializing the default workspace must not create a back target');
  assert.equal(freshNav.action('back'),false);
  fresh.setPanel('settings'); fresh.publish(); await settle();
  assert.equal(freshNav.state().canBack,true,'a real panel on the same home is still a visit');
  assert.equal(freshNav.action('back'),true); await settle();
  assert.equal(freshNav.state().isHome,true);
  assert.equal(freshNav.state().canBack,false);
  assert.equal(freshNav.action('forward'),true); await settle();
  assert.equal(fresh.panel(),'settings');
  assert.equal(fresh.composerPress('停止生成','touch'),true);
  assert.equal(fresh.composerPress('发送','touch'),false);
  assert.equal(fresh.composerPress('停止生成','mouse'),false);
  assert.equal(fresh.composerPress('停止生成','touch',true),false);
  fresh.dispose();
  assert.equal(fresh.composerPress('停止生成','touch'),false);
  const reminder = fixture();
  reminder.use('b'); reminder.expand(); reminder.use('a');
  reminder.ctx.layout.toggleSidebar();
  let phase = 'loading', settingsOpen = true;
  const snapshot = reminder.ctx.sessions.list.getSnapshot;
  reminder.ctx.sessions.list.getSnapshot = () => ({...snapshot(), phase});
  reminder.window.SugarShell = {closeForNotification() { settingsOpen = false; }};
  reminder.window.__sugarNotificationSession = 'b';
  reminder.window.SugarNavigation.openNotification();
  assert.equal(reminder.active(), 'a', 'a cold WebView waits for DSH to load its sessions');
  phase = 'ready'; reminder.publish(); await settle();
  assert.equal(reminder.active(), 'b');
  assert.equal(settingsOpen || reminder.expanded() || reminder.drawer(), false, 'the originating conversation is visible');
  assert.equal(reminder.window.__sugarNotificationSession, undefined);
  reminder.use('a'); reminder.publish(); await settle();
  assert.equal(reminder.active(), 'a', 'a consumed notification does not steal navigation again');
  reminder.dispose();

  const screens = fixture();
  screens.use('b'); screens.expand(); screens.use('a');
  screens.ctx.layout.toggleSidebar();
  let screenSettings = true;
  screens.window.SugarShell = {closeForNotification() { screenSettings = false; }};
  const openScreenChat = screens.window.SugarNavigation.openConversation;
  assert.equal(openScreenChat('removed-session'),false);
  assert.equal(screens.active(),'a');
  assert.equal(screenSettings,true,'a missing owner keeps the screen manager in settings');
  assert.equal(openScreenChat('b'),true,'settings preview of B navigates to B while A is mounted');
  assert.equal(screens.active(),'b');
  assert.equal(screenSettings || screens.expanded() || screens.drawer(),false);
  screens.dispose();
  assert.equal(openScreenChat('a'),false,'a detached plugin cannot navigate another WebView');

  const voice = fixture();
  const openVoice = voice.window.SugarNavigation.openPetVoiceSettings;
  let hidden = false, railExpanded = true, plugins;
  voice.ctx.get = name => { assert.equal(name, 'pluginNavigation'); return plugins; };
  voice.window.SugarShell = {closeForNotification() { hidden = true; }};
  voice.window.SugarEdgeNav = {setExpanded(value) { railExpanded = value; }};
  assert.equal(openVoice(), false);
  assert.equal(hidden, false, 'manual directions remain visible without plugin access');
  const targets = [];
  plugins = {openList: () => targets.push('list')};
  assert.equal(openVoice(), true);
  plugins.openBundle = name => targets.push(name);
  assert.equal(openVoice(), true);
  assert.deepEqual(targets, ['list', '@deepseek-ai/dsh-experimental-voice-input-bundle']);
  assert.equal(hidden, true);
  assert.equal(railExpanded, false);
  assert.equal(voice.active(), 'a', 'plugin configuration keeps the active conversation');
  voice.dispose();
  assert.equal(openVoice(), false);

  const f = fixture(), nav = f.window.SugarNavigation;
  const sessionKey = nav.state().routeKey;
  assert.ok(sessionKey);
  assert.equal(nav.state().isHome, false);
  assert.equal(nav.state().previewable, true);
  assert.equal(nav.state().canBack, true, 'restored conversation has a home fallback');
  assert.equal(nav.action('forward'), false);
  f.expand();
  const fileBookmark = nav.bookmark();
  assert.ok(fileBookmark?.canStage());
  assert.equal(nav.action('back'), true); await settle();
  assert.equal(f.expanded(), false); assert.equal(f.active(), 'a');
  assert.equal(nav.action('back'), true); await settle();
  assert.equal(f.active(), 'draft'); assert.equal(nav.state().canBack, false);
  const homeKey = nav.state().routeKey;
  assert.equal(nav.state().isHome, true); assert.notEqual(homeKey, sessionKey);
  f.ctx.layout.toggleSidebar(); assert.equal(nav.state().isHome, false);
  assert.notEqual(nav.state().routeKey, homeKey);
  f.ctx.layout.toggleSidebar(); assert.equal(nav.state().isHome, true);
  assert.equal(nav.action('forward'), true); await settle();
  assert.equal(f.active(), 'a'); assert.equal(f.expanded(), false);
  assert.equal(nav.state().routeKey, sessionKey);
  assert.equal(nav.action('forward'), true); await settle();
  assert.equal(f.expanded(), true); assert.equal(nav.action('forward'), false);
  assert.equal((await fileBookmark.restore()), true);
  assert.notEqual(nav.state().routeKey, sessionKey);
  assert.equal(nav.action('home'), true); await settle();
  assert.equal(f.active(), 'draft'); assert.equal(nav.state().canForward, false);
  assert.equal(nav.action('back'), true); await settle();
  assert.equal(f.active(), 'a'); assert.equal(f.expanded(), true);
  assert.equal(nav.action('forward'), true); await settle();
  assert.equal(f.active(), 'draft'); assert.equal(f.expanded(), false);
  assert.equal(nav.action('back'), true); await settle();
  f.setPanel('plugins');
  assert.equal(nav.action('back'), true); await settle();
  assert.equal(f.panel(), null); assert.equal(f.expanded(), false);
  assert.equal(nav.action('forward'), true); await settle();
  assert.equal(f.panel(), 'plugins');
  assert.equal(nav.action('home'), true); await settle();
  f.ctx.layout.toggleSidebar();
  assert.equal(nav.action('back'), true); assert.equal(f.drawer(), false);
  assert.equal(nav.action('back'), true); await settle();
  assert.equal(f.panel(), 'plugins');
  assert.equal(nav.action('forward'), true); await settle();
  assert.equal(f.active(), 'draft');
  f.archived.push('a');
  assert.equal(nav.action('back'), false, 'archived sessions are not reopened');
  assert.ok(f.events() > 1);
  f.dispose();
  assert.equal(f.window.SugarNavigation, undefined);
  assert.equal(nav.action('home'), false);
  assert.equal(fixture('example.test').window.SugarNavigation, undefined);

  const g = fixture(), other = g.window.SugarNavigation;
  assert.equal(other.canStage('back'), false, 'first-time fallback home must wait for drop');
  g.cancelNext();
  assert.equal(other.action('home'), true);
  assert.equal(other.state().busy, true);
  assert.equal(other.action('home'), false, 'duplicate tap during transition');
  g.use('b'); g.finishOpen(); await settle();
  assert.equal(g.active(), 'b', 'superseded request did not overwrite selection');
  assert.equal(other.state().busy, false);
  assert.equal(other.state().canForward, false);

  const h = fixture(), branch = h.window.SugarNavigation;
  assert.equal(branch.action('home'), true); await settle();
  assert.equal(h.active(), 'draft');
  h.byId.draft.blank = false; // First message in the old blank starts a real conversation.
  h.ctx.uiWorkspace.openWorkspace = async (_, prepare) => {
    h.byId.next = {id: 'next', blank: true, retainedBy: {}};
    prepare('next'); h.use('next');
  };
  assert.equal(branch.action('back'), true); await settle();
  assert.equal(h.active(), 'next');
  assert.equal(branch.action('forward'), true); await settle();
  assert.equal(h.active(), 'draft', 'retains identity after the first prompt');
  assert.equal(branch.action('back'), true); await settle();
  h.use('b'); // Choosing a different conversation invalidates the old forward branch.
  assert.equal(branch.action('forward'), false);

  const i = fixture(), trail = i.window.SugarNavigation;
  i.use('b');
  assert.equal(trail.action('home'), true); await settle();
  assert.equal(trail.action('home'), false, 'a second home tap leaves history untouched');
  assert.equal(trail.action('back'), true); await settle();
  assert.equal(i.active(), 'b');
  assert.equal(trail.action('back'), true); await settle();
  assert.equal(i.active(), 'a', 'home retains the history preceding the previous conversation');
  assert.equal(trail.action('forward'), true); await settle();
  assert.equal(i.active(), 'b');
  assert.equal(trail.action('forward'), true); await settle();
  assert.equal(i.active(), 'draft');
  const j = fixture(), route = j.window.SugarNavigation;
  const first = route.state().routeKey;
  const initialEvents = j.events();
  j.use('b');
  route.action('forward'); // Coalesced observer also runs synchronously at an action boundary.
  assert.notEqual(route.state().routeKey, first);
  assert.ok(j.events() > initialEvents, 'session switch publishes even when back/forward availability stays the same');
  const k = fixture(), hist = k.window.SugarNavigation;
  k.expand(); const before = hist.bookmark(), restoreHistory = hist.checkpoint();
  assert.equal(hist.canStage('back'), true);
  assert.equal(hist.action('back'), true); await settle();
  assert.equal(await before.restore(), true); assert.ok(k.expanded());
  assert.equal(restoreHistory(), true); assert.equal(hist.state().canForward, false);
  const pageKey = hist.bookmark({includeDrawer: false}).key;
  k.ctx.layout.toggleSidebar(); const menu = hist.bookmark();
  assert.equal(hist.state().drawerOpen, true);
  assert.equal(hist.state().previewable, false);
  const pageOnly = hist.bookmark({includeDrawer: false});
  assert.equal(pageOnly.key, pageKey, 'toggling the transient sidebar does not make another page');
  assert.notEqual(menu.key, pageKey);
  assert.ok(await pageOnly.restore());
  assert.equal(hist.state().drawerOpen, false, 'returning to a page does not reopen its drawer');
  assert.ok(await pageOnly.restore()); assert.equal(k.drawer(), false);
  assert.ok(await menu.restore()); assert.equal(k.drawer(), true, 'exact rollback can still restore an open drawer');
  assert.equal(hist.canStage('back'), false, 'manual drawer dismissal waits for release');
  k.ctx.layout.toggleSidebar(); assert.equal(await menu.restore(), true); assert.ok(k.drawer());
  k.archived.push('a'); assert.equal(menu.reachable(), false); assert.equal(await menu.restore(), false);
  // The left drawer is transient. Opening the right inspector and selecting another file
  // each represent a real page in both the native shell's bookmark history and plugin fallback.
  const rf = fixture(), rightNav = rf.window.SugarNavigation;
  const pageBookmark = () => rightNav.bookmark({includeDrawer: false});
  const chat = pageBookmark();
  assert.equal(rightNav.state().rightPanelOpen, false);
  rf.expand();
  const firstFile = pageBookmark();
  assert.equal(rightNav.state().rightPanelOpen, true);
  rf.ctx.sidebarRight.focus('second-file');
  const secondFile = pageBookmark();
  assert.notEqual(chat.key, firstFile.key);
  assert.notEqual(firstFile.key, secondFile.key);
  rf.ctx.layout.toggleSidebar();
  assert.equal(pageBookmark().key, secondFile.key);
  rf.ctx.layout.toggleSidebar();
  assert.ok(rightNav.action('back')); await settle();
  assert.equal(pageBookmark().key, firstFile.key);
  assert.ok(rightNav.action('forward')); await settle();
  assert.equal(pageBookmark().key, secondFile.key);
  assert.ok(await firstFile.restore());
  assert.equal(rf.ctx.sidebarRight.active().id, 'file');
  assert.ok(await secondFile.restore());
  assert.equal(rf.ctx.sidebarRight.active().id, 'second-file');
  rf.ctx.sidebarRight.toggleExpanded();
  assert.equal(pageBookmark().key, chat.key);
  assert.ok(rightNav.action('forward')); await settle();
  assert.equal(pageBookmark().key, secondFile.key, 'manual close retains the selected file for forward');
  const plain = fixture(), plainNav = plain.window.SugarNavigation;
  const plainChat = plainNav.bookmark({includeDrawer: false});
  plain.setRightTab(undefined); plain.expand();
  const emptyPanel = plainNav.bookmark({includeDrawer: false});
  assert.equal(plainNav.state().rightPanelOpen, true, 'empty inspector publishes the same gesture context as a file');
  assert.notEqual(plainChat.key, emptyPanel.key, 'the inspector itself is a page even without a tab');
  assert.ok(plainNav.action('back')); await settle();
  assert.equal(plain.expanded(), false);
  assert.equal(plainNav.state().rightPanelOpen, false);
  assert.ok(plainNav.action('forward')); await settle();
  assert.equal(plain.expanded(), true);
  assert.equal(plainNav.bookmark({includeDrawer: false}).key, emptyPanel.key);
  const bind = fixture(), bindNav = bind.window.SugarNavigation;
  bind.use(undefined);
  const firstVisit = bindNav.bookmark(), anotherMark = bindNav.bookmark();
  bind.use('b'); bind.publish(); // No blank was ever mounted on this visit.
  assert.equal(firstVisit.canStage(),false,'another blank in a workspace cannot stand in for the actual homepage');
  bind.use(undefined);
  const nextVisit = bindNav.bookmark(), sameVisit = bindNav.bookmark();
  assert.equal(nextVisit.key,firstVisit.key);
  bind.use('draft'); bind.publish();
  bind.use('b'); bind.publish(); // The blank mounted and was left within the same render frame.
  assert.equal(firstVisit.canStage(),false,'revisiting the same public route does not bind older visits');
  assert.equal(anotherMark.canStage(),false);
  assert.equal(nextVisit.canStage(),true);
  assert.equal(sameVisit.canStage(),true);
  bind.ctx.uiWorkspace.openWorkspace = () => { throw Error('a bound home must reuse its own session'); };
  assert.ok(await nextVisit.restore());
  assert.equal(bind.active(),'draft');
  bind.byId.draft.blank = false; bind.use('b');
  assert.equal(nextVisit.canStage(),false,'after the first prompt this mark cannot create a replacement during preview');
  bind.dispose();
  const firstDock = fixture(), firstDockNav = firstDock.window.SugarNavigation;
  firstDock.use(undefined); const noId = firstDockNav.bookmark();
  firstDock.use('draft'); firstDock.dock.setAttribute('data-sidebar-right-session','draft'); firstDock.expand();
  // No bare, mounted blank was seen before the file surface appeared. Closing just this
  // workspace's blank dock is still safe and does not attach that ID to the original mark.
  const firstDockDrag = firstDockNav.beginPanelDrag('back',noId,'right');
  assert.ok(firstDockDrag); firstDockDrag.cancel();
  firstDock.use('b');
  assert.equal(noId.canStage(),false);
  firstDock.dispose();
  const cold = fixture(), coldNav = cold.window.SugarNavigation;
  cold.use(undefined); // The workspace is ready before its first blank session mounts.
  const initialHome = coldNav.bookmark({includeDrawer:false});
  cold.byId.a.blank = true;
  cold.use('a');
  const mountedHome = coldNav.bookmark({includeDrawer:false});
  assert.equal(initialHome.key,mountedHome.key,'the public home identity survives blank-session mount');
  cold.use('draft'); const otherBlank = coldNav.bookmark({includeDrawer:false});
  cold.use('a'); cold.expand();
  assert.equal(coldNav.beginPanelDrag('back',otherBlank,'right'),null,'different identified sessions do not share a live surface');
  const initialDrag = coldNav.beginPanelDrag('back',initialHome,'right');
  assert.ok(initialDrag,'the original id-less bookmark can preview its own workspace home');
  initialDrag.update(.44);
  assert.equal(Math.round(parseFloat(cold.center.properties.get('--sugar-chat-x'))),224);
  assert.equal(await initialDrag.finish(false,() => false),false);
  assert.ok(cold.expanded());
  const getWorkspaces = cold.ctx.workspaces.list.getSnapshot;
  cold.ctx.workspaces.list.getSnapshot = () => ({...getWorkspaces(),items:[
    {workspaceId:'other',sessionIds:['a']},{workspaceId:'project',sessionIds:['draft']}]});
  assert.equal(coldNav.beginPanelDrag('back',initialHome,'right'),null,'an unbound home cannot preview another workspace');
  cold.ctx.workspaces.list.getSnapshot = getWorkspaces;
  cold.byId.a.blank = false;
  assert.equal(coldNav.beginPanelDrag('back',initialHome,'right'),null,'an ordinary conversation cannot impersonate an unbound home');
  cold.byId.a.blank = true;
  const initialCommit = coldNav.beginPanelDrag('back',initialHome,'right');
  assert.ok(await initialCommit.finish(true,() => initialHome.restore()));
  assert.equal(cold.expanded(),false);
  assert.equal(cold.active(),'a');
  const live = fixture(), panelNav = live.window.SugarNavigation;
  const conversation = panelNav.bookmark();
  live.expand(); const openFile = panelNav.bookmark();
  const progress = [], start = () => panelNav.beginPanelDrag('back',conversation,'right', p => progress.push(p));
  const cancelled = start();
  assert.ok(cancelled);
  cancelled.update(.4);
  assert.equal(live.dock.properties.get('--sugar-panel-x'), '0px');
  assert.equal(live.center.properties.get('--sugar-chat-x'), '240px');
  assert.equal(live.center.getAttribute('data-sugar-chat-front'),'fullscreen');
  assert.equal(panelNav.bookmark().key,openFile.key,'preview keeps the real file route until release');
  assert.equal(await cancelled.finish(false,() => { throw Error('cancel navigated'); }),false);
  assert.deepEqual(progress,[0,.4,0]);
  assert.equal(live.dock.hasAttribute('data-sugar-panel-drag'), false);
  assert.ok(live.expanded());
  assert.equal(live.center.hasAttribute('data-sugar-chat-front'),false);
  const opposite = panelNav.beginPanelDrag('back',conversation,'left');
  opposite.update(.4);
  assert.equal(live.center.properties.get('--sugar-chat-x'),'-240px','chat enters from the left when pulled from the left');
  opposite.cancel();
  const closing = start();
  closing.update(.8);
  live.holdLayout();
  let samples = 0;
  const clock = {duration:96, fractionAt:() => ++samples === 1 ? .5 : 1};
  const landing = closing.finish(true,() => conversation.restore(),clock);
  for (let frame=0;frame<8;frame++) await settle();
  assert.equal(samples,2);
  assert.ok(progress.some(p=>Math.abs(p-.9)<.001),'panel uses the same release clock as the rail');
  assert.equal(live.expanded(),false,'store already committed');
  assert.equal(live.dock.hasAttribute('data-sidebar-right-open'),true,'React DOM deliberately lags its store');
  assert.equal(live.dock.hasAttribute('data-sugar-panel-drag'),true,'hold endpoint across multiple frames');
  assert.equal(live.center.properties.get('--sugar-chat-x'),'0px');
  live.commitLayout();
  assert.ok(await landing);
  assert.equal(live.dock.hasAttribute('data-sugar-panel-drag'),false);
  assert.equal(live.expanded(),false);
  const opening = panelNav.beginPanelDrag('forward',openFile,'right');
  opening.update(.55); assert.equal(Math.round(parseFloat(live.dock.properties.get('--sugar-panel-x'))),180);
  assert.ok(await opening.finish(true,() => openFile.restore()));
  live.setRightTab('second-file');
  panelNav.bookmark();
  assert.equal(panelNav.beginPanelDrag('back',openFile,'right'),null,'two different tabs use ordinary file history');
  live.ctx.layout.toggleSidebar();
  const drawerDrag = panelNav.beginPanelDrag('back',undefined,'right');
  drawerDrag.update(.5);
  assert.equal(live.drawerFrame.properties.get('--sugar-panel-x'),'-140px');
  assert.equal(live.drawerFrame.properties.get('--sugar-drawer-mask'),'0.5');
  assert.equal(await drawerDrag.finish(false,() => false),false);
  assert.ok(live.drawer());
  const dismiss = panelNav.beginPanelDrag('back',undefined,'left');
  dismiss.update(.5);
  assert.equal(live.drawerFrame.properties.get('--sugar-panel-x'),'140px');
  assert.ok(await dismiss.finish(true,() => panelNav.action('back')));
  assert.equal(live.drawer(),false);
  assert.equal(panelNav.beginPanelDrag('back',panelNav.bookmark(),'left'), null, 'a closed drawer cannot be summoned by a scrub');
  const interrupted = start();
  interrupted.update(.2);
  const pending = interrupted.finish(true, () => { throw Error('disposed stage committed'); });
  interrupted.cancel();
  assert.equal(await pending,false);
  assert.equal(live.dock.properties.has('--sugar-panel-x'),false,'late release never reapplies presentation after cancel');
  const beforeDispose = start();
  beforeDispose.update(.2); live.dispose();
  assert.equal(live.dock.hasAttribute('data-sugar-panel-quiet'),false);
  assert.equal(await beforeDispose.finish(true, () => true),false);
  console.log('Mobile navigation: home, history, preview, draft, cancellation, origin, and disposal passed.');
})().catch(error => { console.error(error); process.exitCode = 1; });
