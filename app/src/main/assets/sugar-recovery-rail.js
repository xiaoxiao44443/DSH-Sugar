// Mounted only in the authenticated, top-level temporary DSH document.
(() => {
  'use strict';
  window.SugarRecoveryRail?.dispose();
  let cleanup = () => {};
  function mount(settings) {
    cleanup();
    const rail = window.SugarEdgeNav;
    if (!rail || !document.body) return false;
    const host = document.getElementById('sugar-edge-navigation');
    const listeners = [];
    const listen = (node, name, action, capture = false) => {
      node.addEventListener(name, action, capture);
      listeners.push(() => node.removeEventListener(name, action, capture));
    };
    let port, lastLayout, documentBack = false, documentForward = false;
    let preferred = 'back', routeKey, previousForward = false, asked, navigation;
    let tap, tapFrame = 0, mounted = true;
    let explicitEnd = -Infinity;
    const actions = [{id:'end', label:settings.end}, {id:'forward', label:settings.forward}];
    const display = {keepForwardVisible:true, homeLabel:settings.home};
    window.SugarI18n.setLanguage(settings.language);
    window.SugarPageMotion?.setReduced(settings.reducedMotion);
    rail.configure(settings);
    rail.setCovered(true);
    function post(message) { port?.postMessage(message); }
    function layout() {
      if (!port) return;
      const {dockSide, dockWidth} = rail.getPopupDock();
      const data = JSON.stringify({side:host.dataset.side, dockSide, dockWidth, width:innerWidth,
        turnActive:window.SugarRecoveryTurnActive === true, preview:window.SugarNavigation?.state?.().rightPanelOpen === true});
      if (data !== lastLayout) { lastLayout = data; post(data); }
    }
    function state() {
      if (!mounted) return;
      const nav = window.SugarNavigation?.state?.() || {};
      const frozen = window.SugarRecoveryScrub?.active();
      if (tap && nav.busy) tap.busy = true;
      if (tap && !nav.busy && !frozen && nav.routeKey && tap.from !== nav.routeKey) {
        const direction = tap.direction;
        tap = null;
        cancelAnimationFrame(tapFrame);
        tapFrame = requestAnimationFrame(() => {
          if (!window.SugarRecoveryScrub?.active()) window.SugarPageMotion?.play({direction,navigationTap:true});
        });
      } else if (tap?.busy && !nav.busy) tap = null;
      if (!frozen && !nav.busy && nav.routeKey && nav.routeKey !== routeKey) {
        preferred = asked === 'back' || (!asked && nav.canForward && !previousForward) ? 'forward' : 'back';
        routeKey = nav.routeKey;
        previousForward = Boolean(nav.canForward);
        asked = null;
      }
      const canBack = !nav.busy && Boolean(nav.canBack || documentBack);
      const canForward = !nav.busy && Boolean(nav.canForward || documentForward);
      const swipeDirection = !nav.drawerOpen && canForward && (preferred === 'forward' || !canBack)
        ? 'forward' : canBack ? 'back' : null;
      rail.setPage('dsh', nav.isHome ? 'home' : null, actions, display);
      if (!frozen || !navigation) navigation = {canBack,canForward,swipeDirection,rightPanelOpen:Boolean(nav.rightPanelOpen)};
      rail.setNavigation(navigation);
      layout();
    }
    function action({detail: command}) {
      if (!['home','back','forward','end'].includes(command)) return;
      const swipe = rail.getLastActionSource() === 'swipe';
      if (command === 'end') {
        if (!swipe && performance.now() - explicitEnd < 150) post('end');
        explicitEnd = -Infinity;
        return;
      }
      if (swipe && window.SugarRecoveryScrub?.consume(command)) return;
      asked = command;
      if (!swipe) {
        rail.setExpanded(false);
        const nav = window.SugarNavigation, before = nav?.state?.();
        if (before?.busy || (command === 'home' && before?.isHome)) return;
        if (before?.routeKey && (command === 'home' || (command === 'back' ? before.canBack : before.canForward))) {
          tap = {from:before.routeKey, direction:command === 'back' ? 'back' : 'forward'};
          const intent = tap;
          if (nav.action(command)) {
            // React commits a drawer change on its next frame; an async session change publishes
            // its new route after busy clears. Never animate a tap which stayed on the same page.
            state();
            requestAnimationFrame(() => requestAnimationFrame(() => {
              state();
              if (mounted && tap === intent && !nav.state().busy) tap = null;
            }));
            return;
          }
          tap = null;
        }
      }
      post(command);
    }
    function armEnd(e) {
      if (e.isTrusted && e.target.closest?.('button')?.dataset.action === 'end') explicitEnd = performance.now();
    }
    function connect(e) {
      if (e.data !== 'sugar-recovery/rail' || e.ports.length !== 1 || port || (e.source && e.source !== window)) return;
      port = e.ports[0];
      port.onmessage = ({data}) => {
        if (/^[0-3]$/.test(data)) {
          const flags = Number(data);
          documentBack = (flags & 1) !== 0;
          documentForward = (flags & 2) !== 0;
          state();
        } else if (data === 'system-back') {
          if (rail.isExpanded()) rail.setExpanded(false);
          else { asked = 'back'; post('back'); }
        } else if (data === 'edge-left' || data === 'edge-right') {
          if (!rail.isExpanded()) rail.revealFromSystemEdge(data.slice(5));
        }
      };
      rail.setCovered(false);
      post('ready');
      state();
    }
    listen(host.shadowRoot,'pointerup',armEnd,true);
    listen(host.shadowRoot,'click',armEnd,true);
    listen(document,'pointerdown',() => { tap = null; cancelAnimationFrame(tapFrame); },true);
    listen(window,'sugar-edge-action',action);
    listen(window,'sugar-edge-scrub',e => { if (e.detail.phase === 'commit') asked = e.detail.direction; });
    listen(window,'sugar-navigation-state',state);
    listen(window,'sugar-recovery-turn',layout);
    function editing(event) {
      if (event.isTrusted && !document.hidden && document.hasFocus()) post('editing');
    }
    listen(document,'beforeinput',editing,true);
    listen(document,'compositionupdate',editing,true);
    listen(window,'sugar-recovery-scrub-settled',state);
    listen(window,'sugar-edge-gesture-bounds',layout);
    listen(window,'sugar-edge-dock',layout);
    listen(window,'sugar-edge-expanded',layout);
    listen(window,'resize',layout);
    listen(window,'message',connect);
    cleanup = () => {
      mounted = false;
      tap = null;
      cancelAnimationFrame(tapFrame);
      for (const off of listeners) off();
      port?.close();
    };
    state();
    return true;
  }
  window.SugarRecoveryRail = {mount, dispose:() => cleanup()};
})();
