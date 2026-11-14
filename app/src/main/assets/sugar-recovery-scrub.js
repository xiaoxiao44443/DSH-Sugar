// The recovery profile uses DSH's own history and Sugar's usual rail and page renderer.
(() => {
  if (window !== window.top) return;
  window.SugarRecoveryScrub?.dispose();
  const motion = window.SugarPageMotion, rail = window.SugarEdgeNav;
  if (!motion || !rail) return;
  let drag, committed, disposed = false;
  const surface = () => document.querySelector('[data-sidebar-right-panel][data-sidebar-right-open]:not([data-sidebar-right-open="false"])') ||
    document.querySelector('.pI_x6G_centerCol');
  function captureAllowed() {
    if (document.hidden || !document.hasFocus() || document.activeElement?.matches('input,textarea,[contenteditable=true]')) return false;
    return ![...document.querySelectorAll('dialog[open],[role=dialog],iframe,video,input[type=password]')]
      .some(node => node.getClientRects().length && !node.closest('[aria-hidden=true]'));
  }
  function watch() {
    const state = window.SugarNavigation?.state();
    if (!drag && !state?.busy) motion.watchHome(state?.isHome && captureAllowed() ? state.routeKey : null);
  }
  function begin(direction, side) {
    const nav = window.SugarNavigation, state = nav?.state();
    if (!state || state.busy || !(direction === 'back' ? state.canBack : state.canForward)) return;
    const origin = nav.bookmark(), peer = nav.previewTarget(direction), checkpoint = nav.checkpoint();
    const draw = amount => rail.setReveal(side, amount);
    const navigate = async () => {
      if (disposed || nav.state().routeKey !== state.routeKey || !nav.action(direction)) return false;
      if (nav.state().busy) await new Promise(resolve => {
        const stop = () => {
          clearTimeout(timer);
          window.removeEventListener('sugar-navigation-state', observe);
          resolve();
        };
        const observe = () => { if (!nav.state().busy) stop(); };
        const timer = setTimeout(stop, 8000);
        window.addEventListener('sugar-navigation-state', observe);
        observe();
      });
      // Closing a drawer is scheduled for React's next frame.
      if (state.drawerOpen) await new Promise(requestAnimationFrame);
      return nav.state().routeKey !== state.routeKey;
    };
    const panel = nav.beginPanelDrag(direction, null, side, draw);
    if (panel) return {update: p => panel.update(p), cancel: () => panel.cancel(),
      finish: (commit, release) => panel.finish(commit, navigate, release)};
    const preview = state.previewable && peer?.previewable && captureAllowed();
    const gesture = motion.beginDrag({
      target: surface, direction: side === 'right' ? 'forward' : 'back', onProgress: draw,
      canStage: nav.canStage(direction), captureScope: preview ? surface() : null,
      captureAllowed, originKey: preview ? state.routeKey : null, homeTarget: preview ? peer.key : null,
      peerTarget: preview ? peer.key : null, webRoute: Boolean(preview), navigate,
      restore: async () => {
        if (disposed || !origin || nav.state().routeKey !== peer?.key || !await origin.restore()) return false;
        return checkpoint();
      }
    });
    if (gesture) return {update: p => gesture.update(p), cancel: () => gesture.finish(false, true).catch(() => {}),
      finish: (commit, release) => gesture.finish(commit, false, release)};
  }
  function onScrub({detail: {phase, direction, side, progress, release}}) {
    if (disposed || !['back','forward'].includes(direction)) return;
    if (phase === 'move') {
      if (!drag) {
        const gesture = begin(direction, side);
        if (gesture) drag = {direction, gesture};
      }
      if (drag && !drag.finishing) drag.gesture.update(progress);
      return;
    }
    if (!drag || drag.finishing || !['cancel','commit'].includes(phase)) return;
    const run = drag;
    run.finishing = true;
    if (phase === 'commit') {
      committed = direction;
      queueMicrotask(() => { committed = null; });
    }
    const finish = () => {
      if (disposed || drag !== run) return;
      drag = null;
      rail.setReveal(null, 0);
      watch();
      window.dispatchEvent(new Event('sugar-recovery-scrub-settled'));
    };
    Promise.resolve(run.gesture.finish(phase === 'commit', release)).then(finish, finish);
  }
  function dispose() {
    disposed = true;
    drag?.gesture.cancel();
    window.removeEventListener('sugar-edge-scrub', onScrub);
    window.removeEventListener('sugar-navigation-state', watch);
    motion.watchHome(null);
    rail.setReveal(null, 0);
    delete window.SugarRecoveryScrub;
  }
  window.addEventListener('sugar-edge-scrub', onScrub);
  window.addEventListener('sugar-navigation-state', watch);
  window.SugarRecoveryScrub = {consume: direction => committed === direction ||
    Boolean(drag?.finishing && drag.direction === direction), active: () => Boolean(drag), dispose};
  watch();
})();
