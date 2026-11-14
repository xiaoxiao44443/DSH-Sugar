// The APK's local recovery page uses the same shadow DOM, controls and rail as regular settings.
(() => {
  'use strict';
  function mount(config) {
    if (window !== top || !document.body || document.getElementById('sugar-shell-settings')) return false;
    const rail = window.SugarEdgeNav;
    const page = window.SugarRecoveryPage;
    if (!rail || !page) return false;
    const host = document.createElement('div');
    host.id = 'sugar-shell-settings';
    host.dataset.side = config.side;
    host.classList.toggle('dark', config.dark);
    host.toggleAttribute('data-reduced', config.reducedMotion);
    host.style.visibility = 'hidden';
    const initialPaper = getComputedStyle(document.documentElement).getPropertyValue('--sugar-recovery-paper').trim();
    if (initialPaper) host.style.setProperty('--settings-paper',initialPaper);
    const root = host.attachShadow({mode:'open'});
    const sheet = document.createElement('link');
    sheet.rel = 'stylesheet';
    sheet.href = './sugar-settings.css';
    const main = document.createElement('main');
    main.innerHTML = '<div class="viewport"><section data-pane="recovery"><div class="settings-layout">' +
      '<header><span class="wordmark">DSH Sugar</span><h1 class="heading" tabindex="-1"></h1></header>' +
      '<div class="settings-content" data-pane-scroll></div></div></section></div>';
    const pane = main.querySelector('section');
    pane.querySelector('.heading').textContent = config.labels.diag_title;
    pane.querySelector('.settings-content').innerHTML = page.controls;
    root.append(sheet, main);
    document.body.append(host);
    let port, styled = false, busy = true, leaving = false, lastDock;
    function post(text) { port?.postMessage(text); }
    const controls = page.mount(pane, action => {
      if (!busy && !leaving) post(action);
    });
    const motion = window.SugarPageMotion;
    window.SugarI18n.setLanguage(config.language);
    motion?.setReduced(config.reducedMotion);
    rail.configure({...config, backdrop:initialPaper});
    rail.setPage('dsh', null, [], {homeLabel:config.labels.diag_nav_home});
    // Home and Back hand control to the failed startup screen. Horizontal swipes only move the
    // rail here; this local document has no second page to preview.
    function update(message) {
      busy = message.busy;
      controls.update({labels:config.labels, busy, message:message.message || '', loadingMode:message.loadingMode ?? null});
      rail.setNavigation({canBack:!busy, canForward:false, swipeDirection:null});
    }
    update({busy:true});
    function dock() {
      host.dataset.side = document.getElementById('sugar-edge-navigation').dataset.side;
      const {dockSide, dockWidth} = rail.getPopupDock();
      const packet = JSON.stringify({side:host.dataset.side, dockSide, dockWidth, width:innerWidth});
      if (port && lastDock !== packet) { lastDock = packet; post(packet); }
    }
    function leave(command) {
      if (busy || leaving) return;
      leaving = true;
      const done = () => { if (!busy) post(command); else leaving = false; };
      if (!motion?.play({target:pane, direction:'back', navigationTap:true, exiting:true, onFinish:done})) done();
    }
    window.addEventListener('sugar-edge-action', ({detail}) => {
      if (rail.getLastActionSource() !== 'swipe' && ['home','back'].includes(detail)) leave(detail);
    });
    for (const event of ['sugar-edge-dock','sugar-edge-expanded','sugar-edge-gesture-bounds','resize']) {
      window.addEventListener(event, dock);
    }
    function ready() {
      if (port && styled) { post('ready'); dock(); }
    }
    function connect(event) {
      if (event.data !== 'sugar-recovery/management' || event.ports.length !== 1 ||
          (event.source && event.source !== window)) return;
      window.removeEventListener('message', connect);
      port = event.ports[0];
      port.onmessage = ({data}) => {
        if (data === 'system-back') {
          if (rail.isExpanded()) rail.setExpanded(false);
          else leave('back');
        } else if (data === 'edge-left' || data === 'edge-right') {
          if (!rail.isExpanded()) rail.revealFromSystemEdge(data.slice(5));
        } else {
          const state = JSON.parse(data);
          if (typeof state.busy === 'boolean') update(state);
          if (Array.isArray(state.insets)) {
            ['left','top','right','bottom'].forEach((side,i) => document.documentElement.style.setProperty(
              '--sugar-safe-' + side, (state.insets[i] / devicePixelRatio) + 'px'));
          }
        }
      };
      ready();
    }
    window.addEventListener('message', connect);
    sheet.addEventListener('load', () => {
      const paper = getComputedStyle(host).getPropertyValue('--settings-paper').trim();
      document.documentElement.style.backgroundColor = paper;
      rail.configure({backdrop:paper});
      host.style.removeProperty('visibility');
      styled = true;
      motion?.play({target:pane, navigationTap:true});
      ready();
    });
    return true;
  }
  window.SugarRecoveryManagement = {mount};
})();
