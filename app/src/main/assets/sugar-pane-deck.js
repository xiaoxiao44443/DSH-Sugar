// The settings panes are real, local DOM surfaces. Only the two involved in a gesture are painted.
(() => {
  if (window !== window.top) return;
  window.SugarPaneDeck?.dispose();
  const decks = new Map();
  const motionPreference = matchMedia('(prefers-reduced-motion: reduce)');
  const styles = `
    [data-sugar-pane-deck] { display:grid; grid-template-columns:minmax(0,1fr); grid-template-rows:minmax(0,1fr);
      overflow:hidden; isolation:isolate; }
    [data-sugar-pane-deck] > [data-pane] { grid-area:1/1; min-height:0; min-width:0;
      overflow-y:auto; overflow-x:hidden; overscroll-behavior:contain; scrollbar-width:thin; }
    [data-sugar-pane-deck] > [data-pane][hidden] { display:var(--sugar-pane-display,block)!important; visibility:hidden; pointer-events:none; }
    [data-sugar-pane-deck] > [data-pane][data-deck-layer] { visibility:visible; pointer-events:none;
      background-color:var(--paper,Canvas); will-change:transform,opacity; }
    [data-sugar-pane-deck] > [data-pane][data-slide-under] { visibility:visible; pointer-events:none; z-index:1; }
    [data-sugar-pane-deck] > [data-pane][data-slide-front] { z-index:2; }
    [data-sugar-pane-deck] > [data-pane][data-deck-layer="front"] { z-index:2; }
    [data-sugar-pane-deck] > [data-pane][data-deck-layer="under"] { z-index:1; }
    [data-sugar-pane-deck] > :is([data-deck-layer],[data-deck-resting],[data-slide-front]) > :nth-child(2) { animation:none!important; }
  `;
  // Both pages retain their final geometry. Only the seam travels with the rail.
  function revealFrames(side, incoming) {
    const full = {clipPath:'inset(0% 0% 0% 0%)'};
    const trimLeft = incoming ? side === 'right' : side === 'left';
    const empty = {clipPath:trimLeft ? 'inset(0% 0% 0% 100%)' : 'inset(0% 100% 0% 0%)'};
    return incoming ? [empty, full] : [full, empty];
  }
  function follow(element, frames, quiet, onProgress, commitPage, cleanup) {
    const animation = element.animate(frames,{duration:1000,easing:'linear',fill:'both'});
    animation.pause();
    let progress = -1, frame = 0, settleNow, finished, ended = false;
    function draw(value) {
      const next = Math.max(0, Math.min(1, value));
      if (ended || next === progress) return;
      progress = next;
      animation.currentTime = 1000 * progress;
      onProgress?.(progress);
    }
    function end(keep = false) {
      if (ended) return;
      ended = true;
      settleNow?.();
      animation.cancel();
      cleanup(keep);
    }
    function settle(to, release) {
      const from = progress, remaining = Math.abs(to - from);
      const timing = release || window.SugarPageMotion?.releaseTiming?.(remaining);
      if (quiet() || remaining < .005 || timing?.duration === 0) { draw(to); return; }
      const start = performance.now(), duration = Math.max(24,140 * remaining);
      return new Promise(resolve => {
        settleNow = () => { cancelAnimationFrame(frame); settleNow = null; resolve(); };
        function step(now) {
          const t = Math.max(0,Math.min(1,(now-start)/duration));
          const fraction = quiet() ? 1 : timing?.fractionAt(now) ?? 1 - (1 - t) ** 2;
          draw(fraction >= 1 ? to : from + (to - from) * fraction);
          if (fraction < 1) frame = requestAnimationFrame(step); else settleNow();
        }
        frame = requestAnimationFrame(step);
      });
    }
    function finish(commit, onCommit, timing) {
      if (finished) return finished;
      if (ended) return Promise.resolve(false);
      finished = Promise.resolve().then(async () => {
        let applied = false;
        try {
          if (ended) return false;
          await settle(commit ? 1 : 0, timing);
          if (!commit || ended) return false;
          applied = await commitPage(onCommit) !== false && !ended;
          if (!applied && !ended) await settle(0);
          return applied;
        } finally { end(applied); }
      });
      return finished;
    }
    draw(0);
    return { update(p) { if (!finished && Number.isFinite(p)) draw(p); }, finish, cancel:end };
  }
  function prepare(container, elements = container.children) {
    if (decks.has(container)) return decks.get(container);
    const panes = new Map(Array.from(elements).filter(p => p.parentElement === container && p.dataset.pane)
      .map(p => [p.dataset.pane,p]));
    if (!panes.size) throw Error('No settings panes in container');
    const sheet = document.createElement('style');
    sheet.textContent = styles;
    const root = container.getRootNode();
    (root instanceof ShadowRoot ? root : document.head).append(sheet);
    container.setAttribute('data-sugar-pane-deck','');
    let active = null, drag = null, slide = null, disposed = false;
    const lookup = value => typeof value === 'string' ? panes.get(value) : panes.get(value?.dataset?.pane);
    const quiet = () => motionPreference.matches || document.hidden || root.host?.hasAttribute('data-reduced');

    const scroller = pane => pane?.querySelector('[data-pane-scroll]') || pane;
    function stopSlide() {
      if (!slide) return;
      slide.cancel();
    }
    function keepScroll(from, to) {
      return Boolean(from && to.dataset.keepScrollFrom === from.dataset.pane);
    }
    function select(next, reset) {
      if (reset && next !== active && !keepScroll(active, next)) scroller(next).scrollTop = 0;
      active = next;
      for (const pane of panes.values()) {
        if (pane !== active) pane.removeAttribute('data-deck-resting');
        pane.hidden = pane !== active;
        pane.inert = pane !== active;
        pane.setAttribute('aria-hidden', String(pane !== active));
      }
    }
    function activate(value, transition) {
      const next = lookup(value);
      if (disposed || !next) return false;
      if (drag && next !== active && !(drag.committing && drag.to === next)) drag.abort();
      if (!drag) next.removeAttribute('data-deck-resting');
      stopSlide();
      const from = active;
      select(next, true);
      if (from && from !== next && !drag && !quiet() && transition) {
        from.setAttribute('data-slide-under',''); next.setAttribute('data-slide-front','');
        const cleanup = () => {
          from.removeAttribute('data-slide-under'); next.removeAttribute('data-slide-front');
          if (active === next && transition !== 'up' && transition !== 'down') next.setAttribute('data-deck-resting','');
          slide = null;
        };
        if (transition === 'up' || transition === 'down') {
          // A vertical swipe retains the direction and timing of the finger gesture.
          const y = transition === 'up' ? '100%' : '-100%';
          const animation = next.animate([{transform:'translateY('+y+')'},{transform:'none'}],
            {duration:190,easing:'cubic-bezier(.22,.72,.22,1)'});
          const cancel = () => {
            animation.onfinish = animation.oncancel = null;
            animation.cancel(); cleanup();
          };
          slide = {cancel};
          animation.onfinish = animation.oncancel = cancel;
        } else {
          const random = transition.startsWith('random-');
          const cancel = window.SugarPageMotion?.playBetween(from, next, {
            direction:random ? transition.slice(7) : 'up', effect:random ? 'random' : transition, onFinish:cleanup
          });
          if (cancel) slide = {cancel}; else cleanup();
        }
      }
      return true;
    }
    function begin(fromValue,toValue,direction,onProgress) {
      const from = lookup(fromValue), to = lookup(toValue);
      if (disposed || drag || !from || from !== active || !to || to === from ||
          !['forward','back','up','down'].includes(direction)) return null;
      stopSlide();
      const ahead = direction === 'forward' || direction === 'up';
      const horizontal = direction === 'forward' || direction === 'back';
      const front = to, under = from;
      const toScroll = scroller(to), scrollBefore = toScroll.scrollTop;
      if (!keepScroll(from, to)) toScroll.scrollTop = 0;
      front.dataset.deckLayer = 'front';
      under.dataset.deckLayer = 'under';
      const reduced = quiet();
      if (horizontal) {
        under.dataset.dragDock = root.host?.dataset.side || 'right';
        front.dataset.dragDock = ahead ? 'left' : 'right';
      }
      const frames = horizontal ? revealFrames(ahead ? 'right' : 'left', true) : [
        {transform:reduced ? 'none' : 'translateY(' + (ahead ? 100 : -100) + '%)', opacity:reduced ? 0 : 1},
        {transform:'none', opacity:1}];
      const current = {to,committing:false};
      drag = current;
      const gesture = follow(front, frames, quiet, onProgress, async onCommit => {
        current.committing = true;
        if (onCommit && await onCommit() === false || drag !== current || disposed) return false;
        select(to,false);
        return true;
      }, keep => {
        if (!keep && current.committing && active === to && !disposed) select(from,false);
        if (active === from || active === to) active.setAttribute('data-deck-resting','');
        delete front.dataset.deckLayer; delete under.dataset.deckLayer;
        delete front.dataset.dragDock; delete under.dataset.dragDock;
        drag = null;
        if (!keep) toScroll.scrollTop = scrollBefore;
      });
      current.abort = gesture.cancel;
      return gesture;
    }
    // Settings is a sibling above the still-mounted DSH app. Clipping this one
    // surface exposes both real pages, including DSH portals and fixed headers.
    function beginShell(page, side, incoming, onProgress) {
      const shell = root.host, pane = lookup(page);
      if (!shell || !pane || disposed || drag) return null;
      stopSlide();
      if (incoming) activate(page);
      pane.setAttribute('data-deck-resting','');
      pane.dataset.dragDock = incoming ? (side === 'left' ? 'right' : 'left') : shell.dataset.side;
      shell.setAttribute('data-shell-scrub','');
      const wasInert = shell.inert;
      if (incoming) shell.inert = true;
      const gesture = follow(shell, revealFrames(side, incoming), quiet, onProgress,
        apply => apply(), () => {
          delete pane.dataset.dragDock;
          shell.removeAttribute('data-shell-scrub');
          shell.inert = wasInert;
          drag = null;
        });
      drag = {to:pane, committing:incoming, abort:gesture.cancel};
      return gesture;
    }
    function dispose() {
      disposed = true;
      stopSlide(); drag?.abort();
      for (const pane of panes.values()) {
        pane.inert = false;
        pane.removeAttribute('aria-hidden');
        pane.removeAttribute('data-deck-resting');
      }
      container.removeAttribute('data-sugar-pane-deck');
      sheet.remove();
      decks.delete(container);
    }
    const api = {activate,begin,beginShell,scrollElement:() => scroller(active),dispose};
    decks.set(container,api);
    select([...panes.values()].find(p => !p.hidden) || panes.values().next().value,false);
    return api;
  }
  window.SugarPaneDeck = {prepare, dispose() {
    for (const api of decks.values()) api.dispose();
    delete window.SugarPaneDeck;
  }};
})();
