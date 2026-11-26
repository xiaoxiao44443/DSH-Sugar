(() => {
  'use strict';
  window.SugarShell?.dispose();
  const rail = window.SugarEdgeNav;
  const i18n = window.SugarI18n;
  const t = i18n.text;
  let requestedLanguage = null;
  const motion = window.SugarPageMotion;
  const tones = [
    {id:'paper', name:'亮白', color:'#F7F6F2', dark:false},
    {id:'charcoal', name:'深灰', color:'#25272C', dark:true},
    {id:'sky', name:'浅蓝', color:'#BEDBEA', dark:false},
    {id:'ocean', name:'深海', color:'#193C51', dark:true},
    {id:'berry', name:'莓紫', color:'#773E69', dark:true},
    {id:'sand', name:'暖沙', color:'#DAB77D', dark:false}
  ];
  const pages = {
    dsh: {actions:[{id:'settings', label:'设置'}, {id:'forward', label:'前进'}]},
    settings: {actions:[{id:'forward', label:'前进'}, {id:'overview', label:'概览'}, {id:'appearance', label:'外观'}, {id:'gestures', label:'手势'}, {id:'splash', label:'启动页'}, {id:'pet', label:'桌宠'}, {id:'adb', label:'手机连接'}]},
    overview: {parent:'settings', title:'设置'},
    general: {parent:'overview', title:'通用'},
    about: {parent:'general', title:'关于'},
    licenses: {parent:'about', title:'开源许可证'},
    'license-detail': {parent:'licenses', title:'许可详情'},
    pet: {parent:'settings', title:'桌宠'},
    'pet-preview': {parent:'pet', title:'动作预览'},
    appearance: {parent:'settings', title:'外观'},
    gestures: {parent:'settings', title:'手势'},
    splash: {parent:'settings', title:'启动页'},
    adb: {parent:'settings', title:'手机连接'},
    mobile: {parent:'settings', slide:true, actions:[{id:'mobile-main',label:'主屏操作'}, {id:'mobile-virtual',label:'虚拟屏操作'}]},
    'mobile-main': {parent:'mobile', title:'主屏操作'},
    'mobile-virtual': {parent:'mobile', title:'虚拟屏操作'},
    storage: {parent:'overview', title:'存储占用'},
    recovery: {parent:'overview', title:'诊断与恢复'},
    'storage-workspaces': {parent:'storage', title:'全部工作区'},
    'storage-workspace-detail': {parent:'storage-workspaces', title:'工作区文件'},
    'storage-sessions': {parent:'storage', title:'会话管理'},
    'storage-session-detail': {parent:'storage-sessions', title:'会话详情'},
    notifications: {parent:'overview', title:'消息通知'},
    'splash-preview': {parent:'splash', title:'预览与摆放'},
    palette: {parent:'appearance', slide:true,
      actions:tones.map(tone => ({id:'tone-' + tone.id, label:tone.name, color:tone.color}))}
  };
  for (const tone of tones) pages['tone-' + tone.id] = {parent:'palette', title:tone.name, tone};
  // A page inherits the nearest ancestor's non-empty action list. If that list
  // links to an ancestor of the visible page, its corresponding button stays active.
  function navigationFor(id) {
    const path = [];
    for (let node = id; node; node = pages[node].parent) {
      path.push(node);
      const actions = pages[node].actions;
      if (actions?.length) return {owner:node, actions,
        selected:path.find(route => actions.some(action => action.id === route))};
    }
    return {owner:id, actions:[], selected:null};
  }
  function siblingPage(offset) {
    const actions = pages[pages[tab]?.parent]?.actions?.filter(action => pages[action.id]?.title);
    const i = actions?.findIndex(action => action.id === tab);
    if (i >= 0) return actions[i + offset]?.id;
  }
  const palette = ['#88BEB7', '#AC9ECD', '#DEA8A9', '#E2BF86', '#91AFD9', '#ACBD93', '#B9A2B6', '#85B7CA'];
  const cleanups = [], past = [], future = [], inerted = new Set();
  let prefs, css, port, host, root, previousFocus, backdrop, idleTimer, tab = null;
  let rootObserver, lastTap, scrub, skipSwipe, visit, swipePeer, disposed = false, travelling = false;
  let notices, systemNotificationsEnabled, noticePicker, noticeTrigger;
  const completionModes = {background:'仅在 App 后台时', always:'始终通知', off:'关闭'};
  let deck, lastVisitKey, finishLeaving, splashSettings, initialSplash, splashPreviewUrl;
  let lastTonePage = pages.palette.actions[0].id, reportedSurface = null;
  let mobileControls, pets, storage, sessionStoragePage, sessionDetailId;
  let workspaceFilesPage, workspaceId, workspacePath = '';
  let recoveryPage, recoveryState = {};
  let profilePanel, adbState, adbPending, adbTimer, adbResumeTimer;
  let deviceRootState, appInfo;
  let licenseData, licenseLoading = false, licenseError = false, licenseRequest, licenseId;
  let adbAwaitingSince = 0, adbStale = false, adbTriedConnect = false;
  let nearStyles, nearNodes = new Set(), lastSampleAt = -Infinity;
  function listen(target, event, fn, options) {
    target.addEventListener(event, fn, options);
    cleanups.push(() => target.removeEventListener(event, fn, options));
  }
  function post(value) { port?.postMessage(JSON.stringify(value)); }
  function listenForEditing() {
    let lastSent = -Infinity, timer;
    function notify() {
      timer = undefined;
      if (document.hidden) return;
      lastSent = performance.now();
      post({type:'editingInteraction'});
    }
    function onEdit(event) {
      if (!event.isTrusted || document.hidden) return;
      const target = event.composedPath()[0];
      if (!target?.isContentEditable && !target?.matches?.('textarea,input')) return;
      const remaining = 1000 - (performance.now() - lastSent);
      if (remaining <= 0) {
        clearTimeout(timer);
        notify();
      } else if (timer === undefined) {
        timer = setTimeout(notify, remaining);
      }
    }
    listen(document, 'beforeinput', onEdit, true);
    listen(document, 'compositionupdate', onEdit, true);
    cleanups.push(() => clearTimeout(timer));
  }
  function sendPopupPalette() {
    const palette = rail.getPopupPalette();
    if (palette) post({type:'popupPalette', ...palette});
  }
  function isSessionPage(route) {
    return route === 'storage-sessions' || route === 'storage-session-detail';
  }
  function capture() {
    if (tab === 'storage-workspace-detail') return {
      key:'sugar/' + tab + '/' + encodeURIComponent(workspaceId) + '/' + encodeURIComponent(workspacePath),
      tab, workspaceId, workspacePath
    };
    if (tab === 'storage-session-detail') return {key:'sugar/' + tab + '/' + sessionDetailId, tab, detailId:sessionDetailId};
    if (tab === 'license-detail') return {key:'sugar/' + tab + '/' + encodeURIComponent(licenseId), tab, licenseId};
    if (tab) return {key:'sugar/' + tab, tab};
    // The DSH drawer is a transient menu. Swiping back to a conversation restores its page.
    const mark = window.SugarNavigation?.bookmark?.({includeDrawer:false});
    return mark ? {key:'dsh/' + mark.key, mark} : null;
  }
  function push(stack, entry) {
    if (entry && stack.at(-1)?.key !== entry.key) stack.push(entry);
    if (stack.length > 100) stack.shift();
  }
  function peek(stack) {
    while (stack.length && stack.at(-1).mark && !stack.at(-1).mark.reachable()) stack.pop();
    return stack.at(-1);
  }
  function setVisit(entry, returnDirection) {
    if (entry?.key !== visit?.key) swipePeer = visit && entry ? {key:visit.key, direction:returnDirection} : null;
    visit = entry;
  }
  function record(entry, nativeReturn = false) {
    if (!entry || visit?.key === entry.key) return;
    const returning = nativeReturn && visit?.mark && past.at(-1)?.key === entry.key;
    if (returning) {
      past.pop(); push(future, visit);
    } else {
      push(past, visit); future.length = 0;
    }
    setVisit(entry, returning ? 'forward' : 'back');
  }
  function availability(nav) {
    const previous = peek(past), next = peek(future);
    const peer = swipePeer?.direction === 'forward' ? next : previous;
    // Buttons keep the complete stacks. Both edges point to the last distinct, still-reachable page.
    const swipeDirection = !tab && nav.drawerOpen ? 'back' :
      peer && peer.key === swipePeer?.key ? swipePeer.direction : null;
    return {canBack: Boolean(previous || tab || nav.canBack), canForward: Boolean(next), swipeDirection};
  }
  function state() {
    if (disposed) return;
    const nav = window.SugarNavigation?.state() || {};
    if (!travelling && !nav.busy) record(capture(), !tab && nav.canForward);
    if (visit?.key !== lastVisitKey) {
      lastVisitKey = visit?.key;
      post({type:'invalidate'});
    }
    if (!travelling && !nav.busy) motion?.watchHome?.(!tab && nav.isHome ? visit?.key : null);
    const available = scrub?.availability || availability(nav);
    const group = navigationFor(tab || 'dsh');
    rail.setPage(group.owner, group.selected || (!tab && nav.isHome ? 'home' : null), group.actions);
    const rightPanelOpen = !tab && !nav.drawerOpen && (nav.rightPanelOpen ??
      Boolean(document.querySelector('[data-sidebar-right-panel][data-sidebar-right-open]:not([data-sidebar-right-open="false"])')));
    rail.setNavigation({...available, rightPanelOpen});
    const picker = !tab && Boolean(document.querySelector('[data-sugar-picker-header]'));
    post({type: 'state', back: Boolean(picker || rail.isExpanded() || available.canBack), picker, expanded: rail.isExpanded(), settings: Boolean(tab),
      embedded: rightPanelOpen, storage: window.SugarStorage.isStoragePage(tab) || isSessionPage(tab), pet: tab === 'pet' || tab === 'pet-preview',
      sessionStorage: isSessionPage(tab) ? (travelling || scrub ? undefined : true) : false,
      sessionStorageDetail: !travelling && !scrub ? (tab === 'storage-session-detail' ? sessionDetailId : '') : undefined,
      workspaceFiles: !travelling && !scrub ? tab === 'storage-workspace-detail' : undefined,
      workspaceFilesProject: !travelling && !scrub ? (tab === 'storage-workspace-detail' ? workspaceId : '') : undefined,
      workspaceFilesPath: !travelling && !scrub ? (tab === 'storage-workspace-detail' ? workspacePath : '') : undefined,
      settingsRoute: !travelling && !scrub ? (tab || 'home') : undefined});
    if (!travelling && !scrub) {
      sessionStoragePage?.show(tab, false, sessionDetailId);
      workspaceFilesPage?.show(tab, false, workspaceId, workspacePath);
    }
  }
  function bounds() {
    const zones = rail.getGestureBounds();
    post({type: 'bounds', width: window.visualViewport?.width || innerWidth, ...rail.getPopupDock(),
      zones: zones.map(box => [box.left, box.top, box.right, box.bottom].map(Math.round))});
  }
  const rect = r => [r.left, r.top, r.right, r.bottom].map(Math.round);
  function railRects() {
    const visual = document.getElementById('sugar-edge-navigation')?.shadowRoot;
    if (!visual) return [];
    // A folded grip sits on the very edge, often over a modal's dimmed gutter.
    // Sample beside the same dock footprint before and after it is unfolded.
    const el = visual.querySelector('.dock');
    if (!el) return [];
    const r = el.getBoundingClientRect();
    if (r.right <= 0 || r.left >= innerWidth || !r.height) return [];
    return [rect(r)];
  }
  function hasVisible(selector) {
    for (const el of document.querySelectorAll(selector)) {
      const r = el.getBoundingClientRect();
      if (Math.min(r.right, innerWidth) - Math.max(r.left, 0) < 1 ||
          Math.min(r.bottom, innerHeight) - Math.max(r.top, 0) < 1) continue;
      // DSH keeps its closed mobile drawer mounted off screen with role=dialog.
      const visible = el.checkVisibility
        ? el.checkVisibility({visibilityProperty:true, opacityProperty:true})
        : getComputedStyle(el).visibility === 'visible' && Number(getComputedStyle(el).opacity) > 0;
      if (visible) return true;
    }
    return false;
  }
  function canPreviewPage() {
    return !document.hidden && document.hasFocus() && window.SugarNavigation?.state().previewable &&
      !hasVisible('dialog[open],[role=dialog]');
  }
  function canCaptureContent() {
    return !document.activeElement?.matches('input,textarea,[contenteditable=true]') &&
      !hasVisible('iframe,video,input[type=password]');
  }
  // Observe only the page elements beside the rail and their ancestors. PixelCopy reads
  // the rendered strip, so these probes are change notifications, never color samples.
  function watchNearby(box) {
    const left = prefs.side === 'left';
    const x = Math.max(0, Math.min(innerWidth - 1, left ? box[2] + 28 : box[0] - 28));
    const next = new Set();
    for (const y of [.2, .5, .8]) {
      let node = document.elementFromPoint(x, innerHeight * y);
      while (node && !next.has(node)) {
        next.add(node);
        node = node.parentElement;
      }
    }
    if (next.size === nearNodes.size && [...next].every(node => nearNodes.has(node))) return;
    nearStyles.disconnect();
    nearNodes = next;
    for (const node of next) nearStyles.observe(node, {childList:true, attributes:true,
      attributeFilter:['style','class','data-theme','src','hidden']});
  }
  // DSH already owns the route while the old settings surface is animating away.
  // PixelCopy sees the composed window, so wait for the surface to be hidden.
  function settingsOnScreen() { return Boolean(host && !host.hidden); }
  function sampleSoon() {
    if (idleTimer || !port || disposed || settingsOnScreen() || scrub || document.hidden) return;
    // Coalesce bursts without indefinitely postponing updates on a continuously scrolling page.
    idleTimer = setTimeout(() => {
      idleTimer = 0;
      const control = document.getElementById('sugar-edge-navigation');
      if (disposed || settingsOnScreen() || scrub || document.hidden || control?.matches('[data-drag],[data-moving]')) return;
      const boxes = railRects();
      if (!boxes.length) return;
      watchNearby(boxes[0]);
      lastSampleAt = performance.now();
      post({type:'sample', width:innerWidth, rails:boxes});
    }, Math.max(32, lastSampleAt + 140 - performance.now()));
  }
  function reportSurface(color) {
    if (reportedSurface === color) return;
    reportedSurface = color;
    window.dispatchEvent(new CustomEvent('sugar-surface-color', {detail:{color}}));
  }
  function profileSurface() {
    if (!profilePanel) return '';
    // A centered DSH settings panel can be much narrower than the screen. Its
    // opaque surface is the page behind the native editor, even when the rail
    // is over the scrim. Translucent or unknown surfaces still use PixelCopy.
    const rgb = getComputedStyle(profilePanel).backgroundColor.match(/^rgb\(\s*(\d+),\s*(\d+),\s*(\d+)\s*\)$/);
    return rgb ? '#' + rgb.slice(1).map(v => Number(v).toString(16).padStart(2, '0')).join('') : '';
  }
  function appearance() {
    if (tab) paint();
    const paper = tab && getComputedStyle(host).getPropertyValue('--paper').trim();
    if (tab || !settingsOnScreen()) reportSurface(paper || null);
    const colors = rail.configure({...prefs, backdrop:paper || profileSurface() || backdrop || ''});
    if (tab && colors) {
      host.style.setProperty('--preview-ink', colors.ink);
      host.style.setProperty('--preview-glass', colors.glass);
    }
    motion?.setReduced(prefs.reducedMotion);
  }
  function update(change) {
    Object.assign(prefs, change);
    post({type: 'settings', ...prefs});
    appearance();
    bounds();
    sampleSoon();
  }
  function play(direction, target, navigationTap = false) {
    if (scrub) return;
    const origin = lastTap && performance.now() - lastTap.at < 950 ? {x:lastTap.x, y:lastTap.y} : undefined;
    motion?.play({direction, origin, target, navigationTap});
  }
  function darkPage() {
    if (document.querySelector('[data-ds-dark-theme="true"],[data-ds-dark-theme="dark"]')) return true;
    if (document.querySelector('[data-ds-dark-theme="false"],[data-ds-dark-theme="light"]')) return false;
    const css = getComputedStyle(document.body);
    if (css.colorScheme === 'dark' || css.colorScheme === 'light') return css.colorScheme === 'dark';
    const rgb = css.color.match(/[\d.]+/g);
    return rgb?.length >= 3 ? +rgb[0] * .21 + +rgb[1] * .72 + +rgb[2] * .07 > 164 :
      matchMedia('(prefers-color-scheme: dark)').matches;
  }
  function paintNotifications() {
    const controls = root?.querySelector('.notification-controls');
    if (!controls) return;
    controls.disabled = !port;
    noticeTrigger.value = notices.completion;
    noticeTrigger.querySelector('span').textContent = t(completionModes[notices.completion]);
    for (const option of noticePicker.querySelectorAll('button'))
      option.setAttribute('aria-checked', String(option.value === notices.completion));
    for (const toggle of controls.querySelectorAll('[data-notice]'))
      toggle.setAttribute('aria-checked', String(notices[toggle.dataset.notice]));
    controls.querySelector('.system-notice').hidden = systemNotificationsEnabled;
  }
  function closeNoticePicker() {
    if (!noticePicker?.open) return false;
    noticePicker.close();
    noticeTrigger.setAttribute('aria-expanded','false');
    noticeTrigger.focus({preventScroll:true});
    return true;
  }
  function positionNoticePicker() {
    if (!noticePicker?.open) return;
    const anchor = noticeTrigger.getBoundingClientRect();
    const view = window.visualViewport;
    const style = getComputedStyle(host);
    const inset = edge => (parseFloat(style.getPropertyValue('--sugar-safe-' + edge)) || 0) + 6;
    const {dockSide, dockWidth} = rail.getPopupDock();
    const left = (view?.offsetLeft || 0) + Math.max(inset('left'), dockSide === 'left' ? dockWidth + 6 : 0);
    const top = (view?.offsetTop || 0) + inset('top');
    const right = (view?.offsetLeft || 0) + (view?.width || innerWidth) -
      Math.max(inset('right'), dockSide === 'right' ? dockWidth + 6 : 0);
    const bottom = (view?.offsetTop || 0) + (view?.height || innerHeight) - inset('bottom');
    const below = bottom - anchor.bottom - 4, above = anchor.top - top - 4;
    noticePicker.style.maxHeight = Math.max(44, below, above) + 'px';
    noticePicker.style.maxWidth = Math.max(0, right - left) + 'px';
    noticePicker.style.minWidth = Math.max(0, Math.min(145, right - left)) + 'px';
    const box = noticePicker.getBoundingClientRect();
    const y = box.height <= below || above < below ? anchor.bottom + 4 : anchor.top - 4 - box.height;
    noticePicker.style.left = Math.max(left, Math.min(right, anchor.right) - box.width) + 'px';
    noticePicker.style.top = Math.max(top, Math.min(bottom - box.height, y)) + 'px';
  }
  function mountNoticePicker(main) {
    noticeTrigger = main.querySelector('#notice-completion');
    noticePicker = document.createElement('dialog');
    noticePicker.className = 'notice-picker';
    noticePicker.id = 'notice-options';
    noticePicker.setAttribute('aria-labelledby','notice-label');
    noticePicker.innerHTML = '<div role="menu" aria-labelledby="notice-label">' +
      Object.entries(completionModes).map(([value,label]) =>
        `<button type="button" role="menuitemradio" value="${value}">${label}</button>`).join('') + '</div>';
    cleanups.push(i18n.bindStatic(noticePicker));
    root.append(noticePicker);
    listen(noticeTrigger, 'click', () => {
      noticePicker.showModal();
      noticeTrigger.setAttribute('aria-expanded','true');
      positionNoticePicker();
      noticePicker.querySelector('[aria-checked=true]')?.focus({preventScroll:true});
    });
    listen(noticePicker, 'click', e => {
      const option = e.target.closest('[role=menuitemradio]');
      if (option) {
        changeNotice('completion', option.value);
        closeNoticePicker();
      } else if (e.target === noticePicker) {
        // The transparent dialog backdrop receives outside taps on the dialog element.
        const box = noticePicker.getBoundingClientRect();
        if (e.clientX < box.left || e.clientX >= box.right || e.clientY < box.top || e.clientY >= box.bottom) closeNoticePicker();
      }
    });
    listen(noticePicker, 'keydown', e => {
      if (!['ArrowDown','ArrowUp','Home','End'].includes(e.key)) return;
      e.preventDefault();
      const buttons = [...noticePicker.querySelectorAll('button')], i = buttons.indexOf(root.activeElement);
      const last = buttons.length - 1;
      const next = e.key === 'Home' ? 0 : e.key === 'End' ? last :
        (i + (e.key === 'ArrowDown' ? 1 : last)) % buttons.length;
      buttons[next].focus();
    });
    listen(noticePicker, 'cancel', e => { e.preventDefault(); closeNoticePicker(); });
    listen(window, 'resize', positionNoticePicker);
    if (window.visualViewport) listen(window.visualViewport, 'resize', positionNoticePicker);
  }
  function changeNotice(key, value) {
    if (!port) return;
    notices[key] = value;
    post({type:'notificationSettings', changes:{[key]:value}});
    paintNotifications();
  }
  function refreshPlatformStatus() {
    if (document.hidden) return;
    if (tab === 'notifications') post({type:'notificationStatus'});
    if (tab === 'adb') queryAdb();
    if (tab === 'mobile-main' || tab === 'mobile-virtual') post({type:'mobileSettings'});
  }
  function onPlatformResume() {
    refreshPlatformStatus();
    if (tab === 'pet' || tab === 'pet-preview') post({type:'petStatus'});
    clearTimeout(adbResumeTimer);
    if (tab === 'adb' && !document.hidden)
      adbResumeTimer = setTimeout(queryAdb, 700);
  }
  function queryAdb() {
    if (!port || disposed || tab !== 'adb' || document.hidden) return;
    adbAwaitingSince ||= Date.now();
    post({type:'adbStatus'});
    post({type:'rootStatus'});
    scheduleAdbCheck();
  }
  function scheduleAdbCheck() {
    clearTimeout(adbTimer);
    if (!port || disposed || tab !== 'adb' || document.hidden || adbStale ||
        !(adbAwaitingSince || adbPending || adbState?.busy)) return;
    adbTimer = setTimeout(() => {
      if (disposed || tab !== 'adb' || document.hidden) return;
      if (adbAwaitingSince && Date.now() - adbAwaitingSince >= 6500) {
        adbStale = true; adbPending = undefined;
        paintAdb();
      } else queryAdb();
    }, 1800);
  }
  function paintAdb() {
    const pane = root?.querySelector('[data-pane=adb]');
    if (!pane) return;
    paintRoot(pane);
    const ready = Boolean(port && adbState?.supported);
    const connected = Boolean(adbState?.connected);
    const phase = adbPending || adbState?.phase;
    const busy = Boolean(adbPending || adbState?.busy);
    const labels = {
      requestingConnection:'正在检查本机无线调试…', requestingPair:'正在准备配对…',
      requestingPermission:'等待系统授权本地网络访问', waitingForWireless:'等待开启无线调试',
      discoveringPair:'正在搜索本机配对端口…', awaitingCode:'等待输入六位配对码',
      discoveringConnect:'正在搜索本机连接端口…', pairing:'正在与本机配对…', connecting:'正在连接本机…',
      canceling:'正在取消…', disconnecting:'正在断开连接…'
    };
    const summary = pane.querySelector('.adb-status');
    if (adbStale) summary.textContent = t('暂时无法读取连接状态');
    else if (!port || !adbState) summary.textContent = t('正在读取本机连接状态…');
    else if (!adbState.supported) summary.textContent = t('此设备不支持无线调试，需要 Android 11 或更高版本。');
    else if (labels[phase]) summary.textContent = t(labels[phase]);
    else if (connected) summary.textContent = t('DSH Sugar 已连接本机');
    else summary.textContent = t(adbState.paired ? '已配对，尚未连接' : '尚未配对');
    const feedback = pane.querySelector('.adb-message');
    const guidance = {
      waitingForWireless:'请到系统开发者选项开启「无线调试」。开启后，Sugar 会继续连接。',
      awaitingCode:'保持系统配对窗口打开，在 Sugar 通知中输入六位配对码。'
    };
    feedback.textContent = adbStale || adbPending ? '' : t(adbState?.message || guidance[phase] || '');
    feedback.hidden = !feedback.textContent || feedback.textContent === summary.textContent;
    pane.querySelector('[data-adb-refresh]').hidden = !adbStale;
    pane.querySelector('.adb-setup').hidden = !ready || adbStale;
    pane.querySelector('.adb-steps').hidden = connected || busy;
    pane.querySelector('[data-adb-first-pair]').hidden = Boolean(adbState?.paired);
    pane.querySelector('[data-adb-repair]').hidden = !adbState?.paired;
    const auto = pane.querySelector('[data-adb-auto]');
    auto.hidden = !adbState?.paired;
    auto.disabled = busy;
    auto.textContent = t(adbTriedConnect ? '重新连接' : '连接本机');
    pane.querySelector('[data-adb-enable]').hidden = !['waitingForWireless','discoveringPair','awaitingCode'].includes(phase);
    pane.querySelectorAll('fieldset').forEach(field => { field.disabled = !ready || busy; });
    const disconnect = pane.querySelector('[data-adb-disconnect]');
    disconnect.hidden = !connected && !(adbState?.cancellable && busy);
    disconnect.textContent = t(connected ? '断开连接' : '取消');
    disconnect.disabled = !ready || phase === 'disconnecting' || phase === 'canceling';
  }
  function paintRoot(pane) {
    const card = pane.querySelector('[data-root-controls]');
    const enabled = Boolean(deviceRootState?.enabled);
    const busy = Boolean(deviceRootState?.busy);
    const toggle = card.querySelector('[data-root-enable]');
    toggle.setAttribute('aria-checked', String(enabled));
    toggle.disabled = !port || !deviceRootState;
    const summary = card.querySelector('[data-root-status]');
    const detail = card.querySelector('[data-root-message]');
    const labels = {
      disabled:'Root 未启用', checking:'等待 Root 管理器授权…', ready:'Sugar 已获 Root 授权',
      unavailable:'Root 暂不可用', timeout:'Root 授权检查超时', disconnected:'Root 授权需要重新检查'
    };
    const failure = ['unavailable', 'timeout', 'disconnected'].includes(deviceRootState?.code) ? deviceRootState.code : 'unavailable';
    const code = !enabled ? 'disabled' : busy ? 'checking' : deviceRootState?.available ? 'ready' : failure;
    summary.textContent = t(deviceRootState ? labels[code] || labels.unavailable : '正在读取授权状态…');
    detail.textContent = t(enabled && !busy && !deviceRootState?.available
      ? '请确认设备已启用 Root，并在管理器中允许 DSH Sugar。' : '授权后，手机操作会自动使用 Root。工作区命令遵循会话权限。');
    const check = card.querySelector('[data-root-check]');
    check.hidden = !enabled;
    check.disabled = !port || busy;
    check.setAttribute('aria-busy', String(busy));
    check.querySelector('span').textContent = t(busy ? '检查中' : '检查授权');
    card.dataset.rootCode = code;
  }
  function mountRoot(pane) {
    listen(pane.querySelector('[data-root-enable]'), 'click', event => {
      if (!event.isTrusted || !port || !deviceRootState || tab !== 'adb' || travelling || scrub) return;
      const enabled = !deviceRootState.enabled;
      deviceRootState = {...deviceRootState, enabled, available:false, busy:enabled};
      paintRoot(pane);
      post({type:'rootSetEnabled', enabled});
    });
    listen(pane.querySelector('[data-root-check]'), 'click', event => {
      if (!event.isTrusted || !port || !deviceRootState?.enabled || deviceRootState.busy || tab !== 'adb' || travelling || scrub) return;
      deviceRootState = {...deviceRootState, busy:true};
      paintRoot(pane);
      post({type:'rootProbe'});
    });
  }
  function mountAdb(pane) {
    mountRoot(pane);
    for (const button of pane.querySelectorAll('[data-adb-notification],[data-adb-repair]'))
      listen(button, 'click', () => submit({type:'adbPairNotification'}, 'requestingPair'));
    for (const button of pane.querySelectorAll('[data-adb-system],[data-adb-enable]'))
      listen(button, 'click', () => post({type:'openWirelessSettings'}));
    listen(pane.querySelector('[data-adb-refresh]'), 'click', () => {
      adbStale = false; adbAwaitingSince = 0;
      paintAdb(); queryAdb();
    });
    function submit(message, phase) {
      if (!port || !adbState?.supported || adbStale ||
          ((adbPending || adbState.busy) && message.type !== 'adbDisconnect')) return;
      if (message.type === 'adbAutoConnect' || message.type === 'adbConnect') adbTriedConnect = true;
      adbPending = phase;
      adbAwaitingSince ||= Date.now();
      paintAdb();
      post(message); scheduleAdbCheck();
    }
    listen(pane.querySelector('[data-adb-auto]'), 'click', () => submit({type:'adbAutoConnect'}, 'requestingConnection'));
    listen(pane.querySelector('[data-adb-disconnect]'), 'click', () =>
      submit({type:'adbDisconnect'}, adbState?.connected ? 'disconnecting' : 'canceling'));
    for (const form of pane.querySelectorAll('form')) {
      const portInput = form.elements.port;
      listen(portInput, 'input', () => portInput.setCustomValidity(''));
      listen(form, 'submit', event => {
        event.preventDefault();
        const portNumber = Number(portInput.value);
        if (!Number.isInteger(portNumber) || portNumber < 1 || portNumber > 65535) {
          portInput.setCustomValidity(t('请输入 1 到 65535 之间的端口号。'));
          portInput.reportValidity();
          return;
        }
        const code = form.elements.code;
        if (code) {
          submit({type:'adbPair', port:portNumber, code:code.value}, 'pairing');
          code.value = '';
        } else submit({type:'adbConnect', port:portNumber}, 'connecting');
      });
    }
    paintAdb();
  }
  function paint(visibleTab = tab) {
    host.dataset.side = prefs.side;
    host.toggleAttribute('data-reduced', prefs.reducedMotion);
    host.toggleAttribute('data-frost', prefs.blur);
    host.classList.toggle('dark', darkPage());
    const tone = pages[visibleTab]?.tone;
    if (tone) {
      host.dataset.tone = tone.id;
      host.style.setProperty('--palette-bg', tone.color);
      host.style.setProperty('--palette-ink', tone.dark ? '#FFFBF6' : '#202329');
      host.style.setProperty('--palette-scheme', tone.dark ? 'dark' : 'light');
    } else delete host.dataset.tone;
    const adaptive = backdrop && !/^#(?:f{6}|0{6})$/i.test(backdrop) ? backdrop : '#93BCB5';
    host.style.setProperty('--accent', prefs.colorMode === 'random' ? prefs.accent : adaptive);
    host.style.setProperty('--rail-opacity', String(1 - prefs.transparency / 100));
    host.toggleAttribute('data-clear', prefs.transparency === 100);
    const slider = root.querySelector('input[name=transparency]');
    slider.value = prefs.transparency;
    slider.setAttribute('aria-valuetext', prefs.transparency + '%');
    root.querySelector('output[for=sugar-transparency]').textContent = prefs.transparency + '%';
    for (const button of root.querySelectorAll('button[data-choice]'))
      button.setAttribute('aria-pressed', String(prefs[button.dataset.choice] === button.value));
    for (const button of root.querySelectorAll('button[name][role=switch]'))
      button.setAttribute('aria-checked', String(prefs[button.name]));
    root.querySelector('.shuffle').hidden = prefs.colorMode !== 'random';
  }
  function suspendPage(node) {
    if (!(node instanceof HTMLElement) || node === host || node.id === 'sugar-edge-navigation' ||
        node.matches('style,script,link') || node.inert) return;
    node.inert = true;
    inerted.add(node);
  }
  function showTab(next, direction, navigationTap = false) {
    if (next === tab || !Object.hasOwn(pages, next) || !pages[next].title) return;
    if (!host) createPage();
    finishLeaving?.();
    if (!tab) {
      const active = document.activeElement;
      previousFocus = active?.shadowRoot?.activeElement || active;
      [...document.body.children].forEach(suspendPage);
      rootObserver.observe(document.body, {childList: true});
      host.hidden = false;
      nearStyles.disconnect(); nearNodes.clear();
    }
    const before = tab, page = pages[next], siblings = pages[page.parent]?.actions || [];
    const index = id => siblings.findIndex(action => action.id === id);
    const vertical = before && pages[before].parent === page.parent ? (index(next) > index(before) ? 'up' : 'down') : null;
    const turn = vertical || direction || 'forward';
    const tapTransition = Boolean(!scrub && before && deck && navigationTap);
    const slide = Boolean(!scrub && deck && pages[page.parent]?.slide && vertical);
    if (tab === 'adb') root.querySelector('#adb-code').value = '';
    tab = next;
    if (tab === 'license-detail') renderLicenseDetail(licenseId);
    if (tab === 'notifications') paintNotifications();
    if (tab === 'adb') paintAdb();
    refreshPlatformStatus();
    if (page.tone) lastTonePage = tab;
    const pane = root.querySelector('[data-pane="' + tab + '"]');
    if (deck) deck.activate(tab, tapTransition ? 'random-' + turn : slide ? vertical : null);
    else root.querySelectorAll('[data-pane]').forEach(p => { p.hidden = p !== pane; });
    host.dataset.tab = tab;
    const heading = pane.querySelector('h1');
    root.querySelector('main').setAttribute('aria-labelledby', heading.id);
    appearance();
    state();
    pets?.show(tab);
    storage?.show(tab);
    if (tab === 'licenses') loadLicenses();
    if (!before && !scrub) rail.setExpanded(true);
    bounds();
    if (!scrub) heading.focus({preventScroll: true});
    if (!tapTransition && !slide) play(direction || vertical || 'forward', !before && navigationTap ? host : pane, navigationTap);
  }
  function openWorkspace(id) {
    workspaceId = id;
    workspacePath = '';
    navigate('storage-workspace-detail');
  }
  function openWorkspaceDir(path) {
    if (tab !== 'storage-workspace-detail' || workspacePath === path) return;
    workspacePath = path;
    state();
  }
  function discardMissingWorkspaceVisits(id, parent, rows) {
    const prefix = parent ? parent + '/' : '';
    const directories = new Set(rows.filter(row => row.kind === 'directory').map(row => row.path));
    let changed = false;
    for (const stack of [past, future]) {
      for (let i = stack.length - 1; i >= 0; i--) {
        const entry = stack[i];
        if (entry.workspaceId !== id || !entry.workspacePath || !entry.workspacePath.startsWith(prefix)) continue;
        const child = entry.workspacePath.slice(prefix.length).split('/')[0];
        if (child && !directories.has(prefix + child)) {
          if (swipePeer?.key === entry.key) swipePeer = null;
          stack.splice(i, 1);
          changed = true;
        }
      }
    }
    if (changed) {
      for (const stack of [past, future]) {
        for (let i = stack.length - 1; i > 0; i--) {
          if (stack[i].key === stack[i - 1].key) stack.splice(i, 1);
        }
        if (stack.at(-1)?.key === visit?.key) stack.pop();
      }
      if (!swipePeer) {
        const direction = peek(past) ? 'back' : 'forward';
        const peer = direction === 'back' ? peek(past) : peek(future);
        if (peer) swipePeer = {key:peer.key, direction};
      }
      state();
    }
  }
  function openSessionDetail(id) {
    sessionDetailId = id;
    navigate('storage-session-detail');
  }
  async function returnToSessionList(removedId) {
    if (tab !== 'storage-session-detail') return;
    if (peek(past)?.tab === 'storage-sessions') await travel('back');
    else showTab('storage-sessions', 'back');
    if (removedId) {
      for (const stack of [past, future]) {
        for (let i = stack.length - 1; i >= 0; i--) {
          if (stack[i].detailId === removedId) stack.splice(i, 1);
        }
      }
      state();
    }
  }
  function leaveSettings(animate = true, navigationTap = false, direction = 'back') {
    if (!tab) return;
    closeNoticePicker();
    if (tab === 'adb') root.querySelector('#adb-code').value = '';
    tab = null;
    pets?.show(null);
    storage?.show(null);
    sessionStoragePage?.show(null);
    // Keep the departing settings surface above the live DSH page for a button
    // transition. Focus, hit testing and history can already switch to DSH.
    if (navigationTap) {
      host.inert = true;
      host.setAttribute('data-exiting','');
    } else host.hidden = true;
    rootObserver.disconnect();
    for (const node of inerted) node.inert = false;
    inerted.clear();
    if (!scrub && previousFocus?.getRootNode()?.host?.id === 'sugar-edge-navigation') rail.focusHandle();
    else if (!scrub && previousFocus?.isConnected) previousFocus.focus({preventScroll: true});
    previousFocus = null;
    appearance(); state();
    if (navigationTap) {
      const finish = () => {
        if (finishLeaving !== finish) return;
        finishLeaving = null;
        host.inert = false;
        host.removeAttribute('data-exiting');
        if (!tab) {
          host.hidden = true;
          if (!disposed) {
            post({type:'invalidate'});
            reportSurface(null);
            sampleSoon();
          }
        }
      };
      finishLeaving = finish;
      if (!motion?.play({target:host, direction, navigationTap:true, exiting:true, onFinish:finish})) finish();
    } else if (animate) play('back');
    sampleSoon();
  }
  function navigate(command, navigationTap = false) {
    if (closeNoticePicker()) return true;
    if (!tab && command === 'back' && !window.dispatchEvent(new Event('sugar-picker-back', {cancelable:true}))) return true;
    if (travelling) return false;
    if (command === 'settings') command = 'overview';
    if (command === 'palette') command = lastTonePage;
    if (Object.hasOwn(pages, command) && pages[command].title) {
      showTab(command, undefined, navigationTap);
      return true;
    }
    const nav = window.SugarNavigation?.state() || {};
    const allowed = availability(nav);
    if (!(command === 'home' || command === 'back' && allowed.canBack || command === 'forward' && allowed.canForward)) return false;
    travel(command, navigationTap);
    return true;
  }
  function back() {
    if (scrub) { finishScrub(false); return true; }
    if (tab) return navigate('back');
    if (document.querySelector('[data-sugar-picker-header]')) return navigate('back');
    if (rail.isExpanded()) { rail.setExpanded(false); return true; }
    return navigate('back');
  }
  function clicked(e) {
    const action = e.detail;
    if (rail.getLastActionSource() === 'swipe' && (skipSwipe === action || scrub?.finishing ||
        availability(window.SugarNavigation?.state() || {}).swipeDirection !== action)) return;
    const navigationTap = rail.getLastActionSource() === 'button';
    if (scrub) { finishScrub(false).then(() => navigate(action, navigationTap)); return; }
    if (!tab && ['back', 'home', 'forward'].includes(action) && navigationTap) rail.setExpanded(false);
    navigate(action, navigationTap);
    state();
  }
  function surface() {
    return tab ? root.querySelector('[data-pane="' + tab + '"]') :
      motion?.surface?.() || document.querySelector('.pI_x6G_centerCol');
  }
  // Only settle the scrub after the DSH session/panel change has actually committed.
  function dshAction(direction) {
    const navigation = window.SugarNavigation;
    const was = navigation?.state();
    if (!navigation?.action(direction)) return Promise.resolve(false);
    return new Promise(resolve => {
      let timer;
      function done(ok) {
        clearTimeout(timer);
        window.removeEventListener('sugar-navigation-state', observe);
        resolve(ok);
      }
      function observe() {
        const now = navigation.state();
        if (!now.busy) done(now.routeKey !== was.routeKey);
      }
      window.addEventListener('sugar-navigation-state', observe);
      timer = setTimeout(observeTimeout, 8000);
      function observeTimeout() { done(navigation.state().routeKey !== was.routeKey); }
      queueMicrotask(observe);
    });
  }
  async function applyVisit(entry, direction, navigationTap = false) {
    if (entry.tab) {
      if (entry.tab === 'storage-workspace-detail') {
        workspaceId = entry.workspaceId;
        workspacePath = entry.workspacePath;
      }
      if (entry.tab === 'storage-session-detail') sessionDetailId = entry.detailId;
      if (entry.tab === 'license-detail') {
        if (!renderLicenseDetail(entry.licenseId)) return false;
        licenseId = entry.licenseId;
      }
      showTab(entry.tab, direction, navigationTap);
      return true;
    }
    if (!entry.mark.reachable() || !(await entry.mark.restore())) return false;
    leaveSettings(false, navigationTap, direction);
    return true;
  }
  async function travel(command, navigationTap = false) {
    if (disposed || travelling) return false;
    if (command === 'back' && !tab && window.SugarNavigation?.state().drawerOpen) return dshAction('back');
    const old = visit || capture(), wasSettings = Boolean(tab);
    if (!scrub && !wasSettings && old?.mark?.previewable && canPreviewPage() && canCaptureContent())
      motion?.rememberPeer?.(surface(), old.key);
    const from = command === 'back' ? past : future;
    const target = command === 'home' ? null : peek(from);
    travelling = true;
    try {
      // Maintenance can stop the DSH socket. Leaving for DSH must still reconnect it.
      if (tab === 'recovery' && recoveryState.touched && (command === 'home' || target?.mark)) {
        leaveSettings(false, navigationTap, command === 'back' ? 'back' : 'forward');
        return true;
      }
      if (target) {
        if (!await applyVisit(target, command, navigationTap)) return false;
        from.pop();
        push(command === 'back' ? future : past, old);
        setVisit(target, command === 'back' ? 'forward' : 'back');
      } else {
        if (wasSettings && !navigationTap) leaveSettings(false);
        const changed = await dshAction(command);
        if (wasSettings && navigationTap) leaveSettings(false, true, command === 'back' ? 'back' : 'forward');
        if (!wasSettings && !changed) return false;
        const here = capture();
        if (command === 'home') record(here);
        else { push(command === 'back' ? future : past, old); setVisit(here, command === 'back' ? 'forward' : 'back'); }
      }
      if (!tab && !(wasSettings && navigationTap)) play(command === 'back' ? 'back' : 'forward', undefined, navigationTap);
      return true;
    } finally { travelling = false; state(); sampleSoon(); }
  }
  function finishScrub(commit, release) {
    const run = scrub;
    if (!run) return Promise.resolve(false);
    if (run.finishing) return run.finishing;
    run.finishing = Promise.resolve(run.gesture.finish(commit, false, release)).finally(() => {
      if (!disposed && scrub === run) {
        rail.setReveal(null, 0); scrub = null; state(); sampleSoon();
      }
    });
    return run.finishing;
  }
  function settingsDrag(target, side, motionDirection, draw) {
    if (!target || !deck) return null;
    if (target.tab === 'license-detail' && tab !== 'license-detail' && !renderLicenseDetail(target.licenseId)) return null;
    if (tab && target.tab) return deck.begin(tab, target.tab, motionDirection, draw);
    if (!tab && !target.tab) return null;
    // While Sugar settings is open, DSH keeps its current page rendered underneath.
    // Never use that page to preview a different DSH bookmark.
    if (tab && window.SugarNavigation?.bookmark?.({includeDrawer:false})?.key !== target.mark?.key) return null;
    if (target.tab) {
      finishLeaving?.();
      paint(target.tab);
    }
    return deck.beginShell(target.tab || tab, side, Boolean(target.tab), draw);
  }
  function onScrub({detail: {phase, direction, side, progress, release}}) {
    if (noticePicker?.open || disposed || !motion?.beginDrag || !['back','forward'].includes(direction)) return;
    if (phase === 'move' && !scrub) {
      const navigation = window.SugarNavigation;
      const nav = navigation?.state() || {};
      const origin = visit, undo = past.slice(), redo = future.slice(), checkpoint = navigation?.checkpoint?.();
      const target = peek(direction === 'back' ? past : future);
      const ready = availability(nav), originalPeer = swipePeer;
      if (ready.swipeDirection !== direction) return;
      // A delayed staging callback belongs to this exact pair, even if the host opened a new route.
      const traverse = () => !disposed && visit?.key === origin?.key &&
        peek(direction === 'back' ? past : future)?.key === target?.key && travel(direction);
      const canStage = Boolean(origin && (tab || !nav.drawerOpen) &&
        (target ? target.tab || target.mark.canStage() : navigation?.canStage?.(direction)));
      // The inspector can go back with a leftward swipe. Keep the layers under the finger.
      const motionDirection = side === 'right' ? 'forward' : side === 'left' ? 'back' : direction;
      const revealSide = side || (motionDirection === 'forward' ? 'right' : 'left');
      const draw = p => rail.setReveal(revealSide, p);
      const panel = !tab && (nav.drawerOpen && direction === 'back' || !target?.tab)
        && navigation?.beginPanelDrag?.(direction, target?.mark, revealSide, draw);
      const pair = panel || settingsDrag(target, revealSide, motionDirection, draw);
      const previewable = !pair && !tab && target?.mark?.previewable && canPreviewPage();
      const remember = previewable && canCaptureContent();
      const scope = remember && canStage ? surface() : null;
      const gesture = pair ? {update: p => pair.update(p), cancel: () => pair.cancel?.(),
        finish: (commit, _instantly, timing) => pair.finish(commit, traverse, timing)} : motion.beginDrag({direction:motionDirection, target:surface, canStage,
        captureScope: scope, homeTarget: previewable ? target.key : null,
        peerTarget: previewable ? target.key : null, originKey: remember ? origin?.key : null,
        webRoute: !tab && nav.previewable && target?.mark?.previewable,
        captureAllowed: () => canPreviewPage() && canCaptureContent(), onProgress: draw,
        navigate: traverse,
        restore: async () => {
          if (disposed || visit?.key !== target?.key) return false;
          travelling = true;
          try {
            if (!origin || !await applyVisit(origin, direction === 'back' ? 'forward' : 'back')) return false;
            checkpoint?.(); visit = origin; swipePeer = originalPeer;
            past.splice(0,past.length,...undo); future.splice(0,future.length,...redo);
            return true;
          } finally { travelling = false; state(); }
        }});
      if (!gesture) return;
      post({type:'invalidate'});
      scrub = {gesture, availability: ready, direction};
    }
    if (phase === 'move' && scrub && !scrub.finishing) scrub.gesture.update(progress);
    if (phase === 'cancel') finishScrub(false, release);
    if (phase === 'commit' && scrub) {
      skipSwipe = direction;
      queueMicrotask(() => { skipSwipe = null; });
      finishScrub(true, release);
    }
  }
  function paintLanguage() {
    if (!host) return;
    host.lang = i18n.language;
    for (const button of root.querySelectorAll('[data-language]')) {
      button.setAttribute('aria-pressed', String(button.dataset.language === i18n.language));
    }
  }
  function selectLanguage(language) {
    if (language === i18n.language) return;
    requestedLanguage = language;
    i18n.setLanguage(language);
    post({type:'setLanguage', language});
  }
  const licenseGroups = {project:'项目', android:'Android 组件', runtime:'运行环境', dependencies:'运行环境依赖'};
  function licenseNode(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }
  function licenseLabel(node, text) {
    node.dataset.licenseLabel = text;
    node.textContent = t(text);
    return node;
  }
  function paintLicenses() {
    const pane = root?.querySelector('[data-pane=licenses]');
    if (!pane) return;
    for (const node of root.querySelectorAll('[data-license-label]')) node.textContent = t(node.dataset.licenseLabel);
    const status = pane.querySelector('[data-license-status]');
    status.textContent = licenseError ? t('暂时无法读取许可信息') : licenseLoading ? t('正在读取许可信息…') : '';
    status.hidden = !status.textContent;
    pane.querySelector('[data-license-retry]').hidden = !licenseError;
    pane.querySelector('[data-license-list]').setAttribute('aria-busy', String(licenseLoading));
    pane.querySelector('[data-license-count]').textContent = licenseData ? new Intl.NumberFormat(i18n.language).format(licenseData.components.length) : '';
  }
  function renderLicenses() {
    const list = root.querySelector('[data-license-list]');
    const fragment = document.createDocumentFragment();
    for (const [groupId, title] of Object.entries(licenseGroups)) {
      const components = licenseData.components.filter(item => (Object.hasOwn(licenseGroups, item.group) ? item.group : 'dependencies') === groupId);
      if (!components.length) continue;
      const group = licenseNode('section', 'license-group');
      group.dataset.licenseGroup = groupId;
      group.append(licenseLabel(licenseNode('h3'), title));
      for (const component of components) {
        const row = licenseNode('button', 'license-component');
        row.type = 'button';
        row.dataset.licenseComponent = component.id;
        const identity = licenseNode('span', 'license-identity');
        identity.translate = false;
        identity.append(licenseNode('span', 'license-name', component.name));
        const metadata = licenseNode('span', 'license-metadata');
        if (component.version) metadata.append(licenseNode('span', 'license-version', component.version));
        metadata.append(licenseNode('span', 'license-tag', component.license));
        identity.append(metadata);
        const chevron = licenseNode('span', 'license-chevron');
        chevron.setAttribute('aria-hidden', 'true');
        row.append(identity, chevron);
        group.append(row);
      }
      fragment.append(group);
    }
    list.replaceChildren(fragment);
    paintLicenses();
  }
  function openLicenseDetail(id) {
    if (travelling || scrub || !renderLicenseDetail(id)) return;
    licenseId = id;
    navigate('license-detail', true);
  }
  function renderLicenseDetail(id) {
    const component = licenseData?.components.find(item => item.id === id);
    const pane = root?.querySelector('[data-pane=license-detail]');
    if (!component || !pane) return false;
    if (pane.dataset.licenseId === id) return true;
    pane.dataset.licenseId = id;
    pane.querySelector('h1').textContent = component.name;
    const body = pane.querySelector('[data-license-detail]');
    const fragment = document.createDocumentFragment();
    const metadata = licenseNode('div', 'license-metadata license-detail-metadata');
    metadata.translate = false;
    if (component.version) metadata.append(licenseNode('span', 'license-version', component.version));
    metadata.append(licenseNode('span', 'license-tag', component.license));
    fragment.append(metadata);
    if (component.sourceUrl) {
      const source = licenseLabel(licenseNode('button', 'license-source'), '项目源码');
      source.type = 'button'; source.dataset.licenseSource = component.id;
      fragment.append(source);
    }
    // Keep upstream names and license originals as text, including their line breaks.
    // Only the chosen component's documents are mounted; the list stays unchanged.
    for (const doc of component.documents) {
      const article = licenseNode('article', 'license-document');
      article.translate = false;
      article.append(licenseNode('h2', null, doc.title), licenseNode('pre', null, doc.text));
      fragment.append(article);
    }
    body.replaceChildren(fragment);
    pane.querySelector('[data-pane-scroll]').scrollTop = 0;
    return true;
  }
  async function loadLicenses() {
    if (disposed || licenseLoading || licenseData) return;
    licenseLoading = true; licenseError = false;
    licenseRequest = new AbortController();
    paintLicenses();
    try {
      const response = await fetch('/__sugar__/open-source-notices.json', {signal:licenseRequest.signal});
      if (!response.ok) throw new Error('Local license asset unavailable');
      const data = await response.json();
      if (data.schema !== 1 || !Array.isArray(data.components) || !data.components.length ||
          !data.components.every(item => typeof item.id === 'string' && typeof item.name === 'string' &&
            typeof item.license === 'string' && Array.isArray(item.documents) && item.documents.length &&
            item.documents.every(doc => typeof doc.title === 'string' && typeof doc.text === 'string'))) {
        throw new Error('Invalid local license index');
      }
      if (disposed) return;
      licenseData = data;
      renderLicenses();
    } catch (error) {
      if (!disposed && error.name !== 'AbortError') licenseError = true;
    } finally {
      licenseLoading = false;
      if (!disposed) paintLicenses();
    }
  }
  function settingsPane(id, content, headerAction = '') {
    return `<section data-pane="${id}" ${id === 'overview' ? '' : 'hidden'}><div class="settings-layout">
      <header><span class="wordmark">DSH Sugar</span>${headerAction}<h1 class="heading" id="title-${id}" tabindex="-1">${pages[id].title}</h1></header>
      <div class="settings-content" data-pane-scroll>${content}</div></div></section>`;
  }
  function createPage() {
    host = document.createElement('div');
    host.id = 'sugar-shell-settings';
    host.hidden = true;
    root = host.attachShadow({mode: 'open'});
    // Android resizes the WebView after the editor receives focus. Keep the field
    // inside its own pane as the keyboard settles, without scrolling the DSH page.
    let inputFrame = 0;
    function revealFocusedInput() {
      if (inputFrame) return;
      inputFrame = requestAnimationFrame(() => {
        inputFrame = 0;
        const field = root.activeElement;
        const isTextField = field?.matches('textarea,input:not([type]),input:is([type=text],[type=search],[type=number],[type=password],[type=url],[type=email],[type=tel])');
        const pane = tab && isTextField && field.closest('[data-pane-scroll]');
        const viewport = window.visualViewport;
        const viewportTop = viewport?.offsetTop || 0;
        const viewportBottom = viewportTop + (viewport?.height ?? innerHeight);
        // Some IMEs reduce only the visual viewport. Give the pane the same
        // bottom edge in that case so even its last row can scroll into view.
        const covered = pane && viewport?.scale === 1 ? Math.max(0, innerHeight - viewportBottom) : 0;
        if (covered) host.style.bottom = covered + 'px';
        else host.style.removeProperty('bottom');
        if (!pane) return;
        const rect = pane.getBoundingClientRect();
        const top = Math.max(rect.top, viewportTop) + 6;
        const bottom = Math.min(rect.bottom, viewportBottom) - 6;
        if (bottom <= top) return;
        const editor = field.closest('.pet-speech-editor');
        const fieldBox = field.getBoundingClientRect();
        const editorBox = editor?.getBoundingClientRect();
        const actionsBottom = editor?.querySelector('.pet-editor-actions')?.getBoundingClientRect().bottom;
        let target = fieldBox;
        if (editorBox && editorBox.height <= bottom - top) target = editorBox;
        else if (actionsBottom != null && actionsBottom - fieldBox.top <= bottom - top) {
          target = {top: fieldBox.top, bottom: actionsBottom};
        }
        if (target.top < top) pane.scrollTop += target.top - top;
        else if (target.bottom > bottom) pane.scrollTop += Math.min(target.bottom - bottom,target.top - top);
      });
    }
    listen(root, 'focusin', revealFocusedInput);
    listen(root, 'focusout', revealFocusedInput);
    listen(window, 'resize', revealFocusedInput);
    if (window.visualViewport) listen(window.visualViewport, 'resize', revealFocusedInput);
    cleanups.push(() => cancelAnimationFrame(inputFrame));
    const style = document.createElement('style'); style.textContent = css;
    root.append(style);
    const main = document.createElement('main');
    main.innerHTML = `<div class="viewport">
      ${settingsPane('overview', `<h2>侧边导航</h2>
          <div class="links">
            <button type="button" data-go="appearance"><span class="swatch" aria-hidden="true"></span><span>外观<small>导航配色与磨砂背景</small></span><i aria-hidden="true">›</i></button>
            <button type="button" data-go="gestures"><span class="gesture-mark" aria-hidden="true">⇄</span><span>手势<small>停靠位置与切换动效</small></span><i aria-hidden="true">›</i></button>
          </div>
          <h2 class="splash-section">应用</h2>
          <div class="links"><button type="button" data-go="general"><span class="notice-mark" aria-hidden="true">${rail.icon('settings')}</span>
              <span>通用<small>界面语言与任务时常亮</small></span><i aria-hidden="true">›</i></button>
            <button type="button" data-go="splash"><span class="splash-mark" aria-hidden="true"></span>
            <span>启动页<small>启动图片、加载文字与退出转场</small></span><i aria-hidden="true">›</i></button>
            <button type="button" data-go="pet"><span class="notice-mark" aria-hidden="true">${rail.icon('pet')}</span>
            <span>桌宠<small>选择宠物、预览动作与调整大小</small></span><i aria-hidden="true">›</i></button>
            <button type="button" data-go="notifications"><span class="notice-mark" aria-hidden="true">${rail.icon('notifications')}</span>
            <span>消息通知<small>轮次完成、权限审批与提问</small></span><i aria-hidden="true">›</i></button>
            <button type="button" data-go="mobile-main"><span class="notice-mark" aria-hidden="true"><svg viewBox="0 0 24 24"><rect x="6" y="2" width="12" height="20" rx="3"/><path d="M10 18h4"/></svg></span>
            <span>手机操作<small>无障碍主屏与虚拟屏</small></span><i aria-hidden="true">›</i></button>
            <button type="button" data-go="adb"><span class="notice-mark" aria-hidden="true"><svg viewBox="0 0 24 24"><path d='M4 8a12 12 0 0 1 16 0M7 11a7.8 7.8 0 0 1 10 0M9 14h6v7H9z'/></svg></span>
            <span>手机连接<small>无线 ADB 与 Root</small></span><i aria-hidden="true">›</i></button>
            <button type="button" data-go="storage"><span class="notice-mark" aria-hidden="true"><svg viewBox="0 0 24 24"><ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v13c0 4 16 4 16 0V5M4 12c0 4 16 4 16 0"/></svg></span>
            <span>存储占用<small>工作区与共享工具</small></span><i aria-hidden="true">›</i></button>
            <button type="button" data-go="recovery"><span class="notice-mark" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M3 11a9 9 0 1 1 3 8M3 5v6h6m1 2h5m-2.5-2.5v5"/></svg></span>
            <span>诊断与恢复<small>检查启动问题与恢复 DSH</small></span><i aria-hidden="true">›</i></button></div>
      `)}
      ${settingsPane('general', `<h2>偏好设置</h2><div class="group general-options">
          <div class="row language-row"><span id="sugar-language-label">界面语言</span>
            <div class="choices" role="group" aria-labelledby="sugar-language-label" translate="no">
              <button type="button" data-language="zh" lang="zh">中文</button>
              <button type="button" data-language="en" lang="en">English</button>
              <button type="button" data-language="ja" lang="ja">日本語</button>
            </div></div>
          <div class="row"><label for="sugar-task-screen"><span id="task-screen-label">任务时常亮</span>
              <small id="task-screen-hint">闲置后自动调暗</small></label>
            <button type="button" id="sugar-task-screen" name="dimScreenDuringTasks" role="switch" aria-labelledby="task-screen-label" aria-describedby="task-screen-hint"></button>
          </div></div>
          <h2 class="splash-section">应用信息</h2>
          <div class="links general-about"><button type="button" data-go="about"><span class="notice-mark" aria-hidden="true"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7v.5"/></svg></span>
            <span>关于</span><i aria-hidden="true">›</i></button></div>`)}
      ${settingsPane('about', `<div class="about-identity">
          <img class="about-icon" alt="" width="96" height="96" hidden>
          <div class="about-name" translate="no"></div>
          <p class="about-version"><span>版本</span> <span data-app-version translate="no"></span></p>
        </div>
        <button type="button" class="about-source" data-about-source><span>在 GitHub 查看源码</span>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 4h6v6M20 4l-9 9M10 4H5a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-5"/></svg></button>
        <button type="button" class="about-source about-licenses" data-go="licenses"><span>开源许可证<small>捆绑依赖项的第三方声明</small></span>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 5 7 7-7 7"/></svg></button>`)}
      ${settingsPane('licenses', `<p class="license-intro">感谢开源社区的每一份贡献。</p>
        <button type="button" class="license-release" data-license-release><span><strong>对应源码</strong><small>查看此版本的源码与发布附件</small></span>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 4h6v6M20 4l-9 9M10 4H5a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-5"/></svg></button>
        <div class="license-list-heading"><h2>组件与许可</h2><span data-license-count aria-live="polite"></span></div>
        <p class="license-status" data-license-status role="status" hidden></p>
        <button type="button" class="license-retry" data-license-retry hidden>重新读取</button>
        <div data-license-list></div>
        <p class="license-footnote">许可原文可离线查看，源码链接需联网。</p>`)}
      ${settingsPane('license-detail', '<div data-license-detail></div>')}
      ${settingsPane('mobile-main' , window.SugarMobileControls.controls.main)}
      ${settingsPane('storage', window.SugarStorage.controls)}
      ${settingsPane('recovery', window.SugarRecoveryPage.controls)}
      ${settingsPane('storage-workspaces', window.SugarStorage.workspaces)}
      ${settingsPane('storage-workspace-detail', window.SugarWorkspaceFiles.controls)}
      ${settingsPane('storage-sessions', window.SugarSessionStorage.controls)}
      ${settingsPane('storage-session-detail', window.SugarSessionStorage.detailControls)}
      ${settingsPane('pet', window.SugarPets.controls.pet)}
      ${settingsPane('pet-preview', window.SugarPets.controls.preview, window.SugarPets.controls.previewBack)}
      ${settingsPane('mobile-virtual', window.SugarMobileControls.controls.virtual)}
      ${settingsPane('appearance', `
          <div class="preview" aria-hidden="true"><div class="mini-page"><i></i><i></i><i></i></div><div class="mini-rail"><span></span><i></i><b></b></div></div>
          <div class="group"><div class="row"><span id="colors">导航配色</span><div class="choices" role="group" aria-labelledby="colors"><button type="button" data-choice="colorMode" value="adaptive">随背景</button><button type="button" data-choice="colorMode" value="random">随机色</button></div></div>
          <button type="button" class="row shuffle"><span class="ink-dot"></span><span>换一个颜色</span><i aria-hidden="true">↻</i></button>
          <div class="row"><span id="blur">磨砂背景</span><button type="button" name="blur" role="switch" aria-labelledby="blur"></button></div>
          <div class="slider-row"><label for="sugar-transparency">背景透明度</label><output for="sugar-transparency"></output>
          <input id="sugar-transparency" name="transparency" type="range" min="0" max="100" step="1"></div></div>
          <button type="button" class="palette-link" data-go="palette"><span class="palette-mark" aria-hidden="true"></span>配色测试<i aria-hidden="true">›</i></button>
      `)}
      ${tones.map(tone => `<section class="tone-pane" data-pane="tone-${tone.id}" hidden
          style="--tone-bg:${tone.color};--tone-ink:${tone.dark ? '#FFFBF6' : '#202329'}">
        <div class="tone-current"><span class="tone-label">配色测试</span>
          <h1 class="tone-name" id="title-tone-${tone.id}" tabindex="-1">${tone.name}</h1>
          <code class="tone-code">${tone.color}</code></div>
      </section>`).join('')}
      ${settingsPane('gestures', `
          <div class="group"><div class="row"><span id="position">停靠位置</span><div class="choices" role="group" aria-labelledby="position"><button type="button" data-choice="side" value="left">左侧</button><button type="button" data-choice="side" value="right">右侧</button></div></div>
          <div class="row"><span><span id="full-edge">整条侧边交给 Sugar</span><br><small id="full-edge-hint">开启后底部手势条暂时收起，从屏幕底部上滑可唤回。</small></span><button type="button" name="fullEdgeGestures" role="switch" aria-labelledby="full-edge" aria-describedby="full-edge-hint"></button></div>
          <div class="row"><span id="motion">减少动效</span><button type="button" name="reducedMotion" role="switch" aria-labelledby="motion"></button></div></div>
          <dl><dt>收起后的边缘手势</dt><dd>从屏幕边缘向内快扫可展开导航。按住边缘再慢慢向内拖，可以预览历史页面；拖过中线后松手切换，拖回可取消。</dd><dt>横向切换</dt><dd>拖过屏幕中线，或在导航条展开后朝对侧快甩。从任一侧滑动都回到刚才的页面，再滑一次回到当前页。打开其他页面后，这两页随之更新。返回和前进按钮可以继续翻看更早的记录。只有一个页面时，拖动可调整导航停靠位置。</dd><dt>上下滑动设置页</dt><dd>在设置的相邻页面间切换。</dd></dl>
      `)}
      ${settingsPane('adb', `<div class="adb-controls">
        <h2>无线 ADB</h2>
        <div role="status" aria-live="polite"><strong class="adb-status"></strong><p class="adb-message" hidden></p></div>
        <button type="button" data-adb-refresh hidden>刷新连接状态</button>
        <div class="adb-setup" hidden>
          <p>连接后，所有工作区的 Agent 均可执行本机 ADB 命令，断开后停止。</p>
          <button type="button" data-adb-enable hidden>打开无线调试设置</button>
          <button type="button" data-adb-disconnect hidden>断开连接</button>
          <div class="adb-steps">
            <button type="button" data-adb-auto hidden>连接本机</button>
            <div data-adb-first-pair><p>打开「无线调试 → 使用配对码配对设备」。保持配对弹窗打开，下拉通知栏，在 Sugar 通知中输入 6 位配对码。</p>
            <button type="button" data-adb-notification>通知栏配对</button>
            <p>配对完成后，Sugar 会自动查找本机连接端口。</p></div>
            <details><summary>更多连接方式</summary><button type="button" data-adb-system>打开无线调试设置</button>
            <button type="button" data-adb-repair hidden>用通知栏重新配对</button>
            <section><h2>配对本机</h2><p>可用分屏同时打开系统设置和 Sugar，填写配对弹窗中的端口和 6 位配对码。</p>
              <form autocomplete="off"><fieldset disabled>
                <label>配对端口<input name="port" type="text" inputmode="numeric" pattern="[0-9]{1,5}" maxlength="5" required></label>
                <label>配对码<input id="adb-code" name="code" type="text" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" required></label>
                <button type="submit">配对</button>
              </fieldset></form>
            </section>
            <section><h2>连接本机</h2><p>配对完成后，在「无线调试」主页面查看「IP 地址和端口」，填写冒号后的连接端口。</p>
              <form autocomplete="off"><fieldset disabled>
                <label>连接端口<input name="port" type="text" inputmode="numeric" pattern="[0-9]{1,5}" maxlength="5" required></label>
                <button type="submit">连接</button>
              </fieldset></form>
            </section></details>
          </div>
        </div>
      </div><details class="root-options"><summary>Root（可选）</summary>
        <section class="root-controls" data-root-controls aria-labelledby="sugar-root-label">
          <div class="row"><span id="sugar-root-label">使用 Root</span>
            <button type="button" data-root-enable role="switch" aria-checked="false" aria-labelledby="sugar-root-label" aria-describedby="sugar-root-message" disabled></button></div>
          <div class="root-status-row"><span data-root-status role="status" aria-live="polite"></span>
            <button type="button" data-root-check hidden><i class="root-check-spinner" aria-hidden="true"></i><span>检查授权</span></button></div>
          <p id="sugar-root-message" data-root-message></p>
        </section>
      </details>`)}
      ${settingsPane('notifications', `<fieldset class="notification-controls" aria-label="消息通知" disabled>
          <div class="system-notice" hidden><span>系统通知已关闭</span><button type="button" data-system-notifications>打开系统通知设置</button></div>
          <div class="group"><div class="row"><div><label id="notice-label" for="notice-completion">轮次完成通知</label><small>Agent 完成本轮任务时提醒</small></div>
            <button id="notice-completion" type="button" aria-haspopup="dialog" aria-controls="notice-options" aria-expanded="false" aria-labelledby="notice-label notice-value"><span id="notice-value"></span></button></div>
          <div class="row"><span id="notice-permission">权限审批通知<small>需要你批准操作时，在前台和后台都提醒</small></span>
            <button type="button" data-notice="permission" role="switch" aria-labelledby="notice-permission"></button></div>
          <div class="row"><span id="notice-question">提问通知<small>需要你回答问题时，在前台和后台都提醒</small></span>
            <button type="button" data-notice="question" role="switch" aria-labelledby="notice-question"></button></div></div>
          <div class="group"><div class="row"><span id="notice-pet">桌宠免通知<small>桌宠显示时，暂停消息通知</small></span>
            <button type="button" data-notice="muteWhilePetVisible" role="switch" aria-labelledby="notice-pet"></button></div></div>
        </fieldset>`)}
      ${settingsPane('splash', window.SugarSplashSettings.controls)}
      ${settingsPane('splash-preview', window.SugarSplashPreview.controls)}
      </div>`;
    const about = main.querySelector('[data-pane=about]');
    about.querySelector('.about-name').textContent = appInfo?.name || 'DSH Sugar';
    about.querySelector('[data-app-version]').textContent = appInfo?.version || '—';
    if (typeof appInfo?.icon === 'string' && appInfo.icon.startsWith('data:image/png;base64,')) {
      const icon = about.querySelector('.about-icon');
      icon.src = appInfo.icon; icon.hidden = false;
    }
    main.querySelector('[data-pane=license-detail] h1').translate = false;
    main.querySelector('[data-pane=licenses]').dataset.keepScrollFrom = 'license-detail';
    cleanups.push(i18n.bindStatic(main));
    root.append(main);
    recoveryPage = window.SugarRecoveryPage.mount(main.querySelector('[data-pane=recovery]'), action => {
      if (tab === 'recovery' && !scrub) post({type:'recoveryAction', action});
    });
    recoveryPage.update(recoveryState);
    const workspaceList = main.querySelector('[data-pane=storage-workspaces]');
    workspaceList.dataset.keepScrollFrom = 'storage-workspace-detail';
    storage = window.SugarStorage.mount(main.querySelector('[data-pane=storage]'), workspaceList, post, openWorkspace);
    workspaceFilesPage = window.SugarWorkspaceFiles.mount(main.querySelector('[data-pane=storage-workspace-detail]'), post, openWorkspaceDir, discardMissingWorkspaceVisits);
    const sessionList = main.querySelector('[data-pane=storage-sessions]');
    sessionList.dataset.keepScrollFrom = 'storage-session-detail';
    sessionStoragePage = window.SugarSessionStorage.mount(sessionList,
      main.querySelector('[data-pane=storage-session-detail]'), post, openSessionDetail, returnToSessionList);
    pets = window.SugarPets.mount(main, null, post, navigate);
    mobileControls?.attach(main);
    paintLanguage();
    document.body.append(host);
    splashSettings = window.SugarSplashSettings.mount(main.querySelector('[data-pane=splash]'), initialSplash, post, Boolean(port),
      main.querySelector('[data-pane=splash-preview]'), splashPreviewUrl);
    rootObserver = new MutationObserver(records => records.forEach(record => record.addedNodes.forEach(suspendPage)));
    mountNoticePicker(main);
    mountAdb(main.querySelector('[data-pane=adb]'));
    const slider = main.querySelector('input[name=transparency]');
    let sliderFrame = 0;
    listen(slider, 'input', () => {
      prefs.transparency = Number(slider.value);
      if (!sliderFrame) sliderFrame = requestAnimationFrame(() => {
        sliderFrame = 0;
        rail.configure({transparency: prefs.transparency});
        host.style.setProperty('--rail-opacity', String(1 - prefs.transparency / 100));
        host.toggleAttribute('data-clear', prefs.transparency === 100);
        root.querySelector('output[for=sugar-transparency]').textContent = prefs.transparency + '%';
        slider.setAttribute('aria-valuetext', prefs.transparency + '%');
      });
    });
    listen(slider, 'change', () => update({transparency: Number(slider.value)}));
    cleanups.push(() => cancelAnimationFrame(sliderFrame));
    listen(main, 'click', e => {
      const button = e.target.closest('button');
      if (!button) return;
      if (button.hasAttribute('data-about-source')) return post({type:'openSource'});
      if (button.hasAttribute('data-license-component')) return openLicenseDetail(button.dataset.licenseComponent);
      if (button.hasAttribute('data-license-release')) return post({type:'openLicenseRelease'});
      if (button.hasAttribute('data-license-source')) return post({type:'openLicenseSource', id:button.dataset.licenseSource});
      if (button.hasAttribute('data-license-retry')) return loadLicenses();
      if (button.dataset.language) return selectLanguage(button.dataset.language);
      if (button.dataset.go) {
        if (navigate(button.dataset.go) && button.dataset.go === 'palette') rail.setExpanded(true);
        return;
      }
      if (button.dataset.notice) return changeNotice(button.dataset.notice, !notices[button.dataset.notice]);
      if (button.hasAttribute('data-system-notifications')) return post({type:'openNotificationSettings'});
      const key = button.dataset.choice;
      if (key === 'side' || key === 'colorMode') update({[key]: button.value});
      else if (button.matches('button[name][role=switch]')) update({[button.name]: !prefs[button.name]});
      else if (button.classList.contains('shuffle')) {
        const options = palette.filter(color => color.toLowerCase() !== prefs.accent.toLowerCase());
        update({accent: options[Math.floor(Math.random() * options.length)]});
      }
    });
    const viewport = main.querySelector('.viewport');
    deck = window.SugarPaneDeck?.prepare(viewport, Array.from(main.querySelectorAll('[data-pane]')));
    let touch;
    listen(viewport, 'touchstart', e => {
      touch = null;
      if (e.touches.length !== 1) return;
      const p = e.touches[0];
      const scrolling = deck?.scrollElement() || viewport;
      touch = {x:p.clientX,y:p.clientY,id:p.identifier,top:scrolling.scrollTop <= 1,
        bottom:scrolling.scrollHeight - scrolling.clientHeight - scrolling.scrollTop < 2};
    }, {passive:true});
    listen(viewport, 'touchcancel', () => { touch = null; }, {passive:true});
    listen(viewport, 'touchend', e => {
      const start = touch; touch = null;
      const p = e.changedTouches[0];
      if (!start || !p || e.touches.length || p.identifier !== start.id) return;
      const dy = p.clientY - start.y;
      if (Math.abs(dy) < 54 || Math.abs(dy) < 1.5 * Math.abs(p.clientX - start.x)) return;
      if (dy < 0 && start.bottom || dy > 0 && start.top) navigate(siblingPage(dy < 0 ? 1 : -1));
    }, {passive:true});
  }
  function mount(initial, styles) {
    const {language = 'zh', appInfo:application, splash, splashPreviewUrl:previewUrl, notifications, recoveryLabels, systemNotificationsEnabled:enabled, ...edgeSettings} = initial;
    appInfo = application;
    recoveryState = {labels:recoveryLabels};
    i18n.setLanguage(language);
    listenForEditing();
    mobileControls = window.SugarMobileControls.mount(post,navigate);
    listen(window, 'sugar-language-changed', () => {
      storage?.paint();
      sessionStoragePage?.paint();
      workspaceFilesPage?.paint();
      paintLanguage();
      paintLicenses();
      paintNotifications();
      paintAdb();
      state();
    });
    notices = {completion:'background', permission:true, question:true, muteWhilePetVisible:true, ...notifications};
    systemNotificationsEnabled = enabled !== false;
    splashPreviewUrl = previewUrl;
    initialSplash = splash;
    prefs = {fullEdgeGestures:false, dimScreenDuringTasks:true, ...edgeSettings}; css = styles;
    // DSH mounts the settings portal on body. Update the native popup palette as
    // soon as it appears, before the user opens the configuration file.
    const settingsPortal = '[data-shortcut-modal="settings"][role="dialog"]';
    profilePanel = document.querySelector(settingsPortal);
    mobileControls.setObscured(Boolean(profilePanel));
    const portals = new MutationObserver(() => {
      const panel = document.querySelector(settingsPortal);
      if (panel === profilePanel) return;
      profilePanel = panel;
      mobileControls.setObscured(Boolean(panel));
      appearance(); sampleSoon();
    });
    portals.observe(document.body, {childList:true});
    cleanups.push(() => portals.disconnect());
    appearance(); state();
    listen(window, 'sugar-popup-palette', sendPopupPalette);
    listen(window, 'sugar-picker-visibility', state);
    listen(window, 'sugar-edge-action', clicked);
    listen(window, 'sugar-edge-scrub', onScrub);
    listen(window, 'sugar-edge-dock', e => { if (['left','right'].includes(e.detail)) update({side:e.detail}); });
    listen(window, 'sugar-edge-expanded', () => { state(); bounds(); sampleSoon(); });
    listen(window, 'sugar-edge-gesture-bounds', bounds);
    listen(window, 'sugar-task-rail', e => post({type:'taskRail', ...e.detail}));
    nearStyles = new MutationObserver(records => {
      if (records.some(change => change.type === 'attributes' ||
        [...change.addedNodes, ...change.removedNodes].some(node => node.nodeType === Node.ELEMENT_NODE))) sampleSoon();
    });
    const railMotion = new MutationObserver(sampleSoon);
    railMotion.observe(document.getElementById('sugar-edge-navigation'),
      {attributes:true, attributeFilter:['data-moving','data-drag']});
    cleanups.push(() => { nearStyles.disconnect(); nearNodes.clear(); railMotion.disconnect(); });
    const nearbyPainted = event => { if (nearNodes.has(event.target)) sampleSoon(); };
    listen(document, 'transitionend', nearbyPainted, true);
    listen(document, 'animationend', nearbyPainted, true);
    listen(document, 'load', nearbyPainted, true);
    listen(window, 'sugar-navigation-state', () => { state(); sampleSoon(); });
    listen(window, 'resize', () => { bounds(); sampleSoon(); });
    listen(window, 'focus', () => { sampleSoon(); onPlatformResume(); });
    listen(window, 'pageshow', onPlatformResume);
    listen(document, 'scroll', () => { sampleSoon(); }, {capture:true, passive:true});
    listen(document, 'pointerup', e => {
      lastTap = {x:e.clientX,y:e.clientY,at:performance.now()};
      sampleSoon();
    }, {capture:true, passive:true});
    listen(document, 'visibilitychange', () => {
      if (document.hidden) {
        rail.setReveal(null, 0);
        if (scrub) finishScrub(false);
      } else { sampleSoon(); onPlatformResume(); }
    });
    listen(window, 'keydown', e => {
      if (!tab || !['Escape','PageUp','PageDown'].includes(e.key)) return;
      e.preventDefault(); e.stopPropagation();
      if (e.key === 'Escape') navigate('back');
      else navigate(siblingPage(e.key === 'PageDown' ? 1 : -1));
    }, true);
    function themeChanged() {
      appearance(); sampleSoon();
    }
    const scheme = matchMedia('(prefers-color-scheme: dark)');
    scheme.addListener(themeChanged);
    cleanups.push(() => scheme.removeListener(themeChanged));
    const observer = new MutationObserver(themeChanged);
    observer.observe(document.documentElement, {subtree:true, attributes:true, attributeFilter:['data-ds-dark-theme']});
    const pageStyles = new MutationObserver(() => {
      if (tab || profilePanel) appearance();
      sampleSoon();
    });
    for (const node of [document.documentElement, document.body]) {
      pageStyles.observe(node,{attributes:true,attributeFilter:['class','style','data-theme']});
    }
    cleanups.push(() => { observer.disconnect(); pageStyles.disconnect(); });
    listen(window, 'message', e => {
      if (e.data !== 'dsh-sugar/edge-shell' || e.ports?.length !== 1 || port || (e.source && e.source !== window)) return;
      port = e.ports[0];
      mobileControls.sendConversation();
      if (requestedLanguage) post({type:'setLanguage', language:requestedLanguage});
      sendPopupPalette();
      splashSettings?.connect();
      paintNotifications(); paintAdb(); refreshPlatformStatus();
      port.onmessage = ({data}) => {
        if (typeof data === 'string' && /^#[0-9a-f]{6}$/i.test(data)) {
          if (!settingsOnScreen()) { backdrop = data; appearance(); }
        }
        else if (data === 'reveal-left' || data === 'reveal-right') rail.revealFromSystemEdge(data.slice(7));
        else if (typeof data === 'string' && data.startsWith('{')) {
          let message;
          try { message = JSON.parse(data); } catch { return; }
          if (message.type === 'recoveryDestination') {
            if (message.route === 'home' || (Object.hasOwn(pages, message.route) && pages[message.route].title))
              navigate(message.route, true);
          } else if (message.type === 'recoveryState') {
            Object.assign(recoveryState, message);
            recoveryPage?.update(recoveryState);
          } else if (message.type === 'storageUsage') {
            storage?.receive(message);
          } else if (['workspaceFiles', 'workspaceFilesResult', 'workspaceFilesOpened'].includes(message.type)) {
            workspaceFilesPage?.receive(message);
          } else if (['sessionStorage', 'sessionStorageResult', 'sessionStorageOpened'].includes(message.type)) {
            sessionStoragePage?.receive(message);
          } else if (message.type === 'petState' || message.type === 'petVoiceStatus' || message.type === 'petVoiceNavigation') {
            pets?.receive(message);
          } else if (message.type === 'mobileSettings' || message.type === 'mobileTitleWatch') {
            mobileControls?.receive(message);
          } else if (message.type === 'languageState') {
            if (requestedLanguage && message.language !== requestedLanguage) return;
            requestedLanguage = null;
            if (message.recoveryLabels) {
              recoveryState.labels = message.recoveryLabels;
              recoveryPage?.update(recoveryState);
            }
            i18n.setLanguage(message.language);
          } else if (message.type === 'notificationStatus' && typeof message.enabled === 'boolean') {
            systemNotificationsEnabled = message.enabled;
            paintNotifications();
          } else if (message.type === 'adbState' && typeof message.supported === 'boolean') {
            adbState = message; adbPending = undefined;
            adbAwaitingSince = 0; adbStale = false;
            if (message.connected) adbTriedConnect = true;
            paintAdb(); scheduleAdbCheck();
          } else if (message.type === 'rootState' && typeof message.enabled === 'boolean') {
            deviceRootState = message;
            paintAdb();
          } else if (message.type === 'splashState' && message.splash) {
            initialSplash = message.splash;
            splashSettings?.receive(message);
          }
        }
      };
      state(); bounds(); sampleSoon();
      storage?.show(tab, true);
      if (!travelling && !scrub) {
        sessionStoragePage?.show(tab, true, sessionDetailId);
        workspaceFilesPage?.show(tab, true, workspaceId, workspacePath);
      }
      if (tab === 'pet' || tab === 'pet-preview') post({type:'petStatus'});
    });
  }
  function dispose() {
    disposed = true;
    licenseRequest?.abort();
    while (cleanups.length) cleanups.pop()();
    closeNoticePicker();
    sessionStoragePage?.show(null);
    workspaceFilesPage?.show(null);
    mobileControls?.dispose();
    pets?.dispose();
    scrub?.gesture.cancel?.(); rail.setReveal(null, 0);
    clearTimeout(adbTimer); clearTimeout(adbResumeTimer);
    clearTimeout(idleTimer);
    rootObserver?.disconnect();
    for (const node of inerted) node.inert = false;
    finishLeaving?.(); splashSettings?.dispose(); deck?.dispose(); host?.remove(); port?.close(); motion?.cancel(); reportSurface(null);
    delete window.SugarShell;
  }
  window.SugarShell = {mount, back, dispose, closeForNotification: () => leaveSettings(false)};
})();
