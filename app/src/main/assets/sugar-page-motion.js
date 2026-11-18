// Swipe transitions use the DSH center column, independently of the Sugar navigation rail.
(() => {
  'use strict';
  if (window !== window.top) return;
  window.SugarPageMotion?.dispose();

  const media = matchMedia('(prefers-reduced-motion: reduce)');
  const effects = new Map();
  // Vectors locate the incoming surface. An upward swipe brings the next page in from below.
  const directions = {back: [-1, 0], forward: [1, 0], up: [0, 1], down: [0, -1]};
  const handlers = [];
  let remaining = [], last = '', active, drag, reduced = false, disposed = false;
  let composing = false, lastInput = -Infinity;

  // A factory returns {frames, duration, easing?, softLight?}. It only receives geometry.
  // Registration returns a disposer, so a plugin can remove its effect on unload.
  function register(name, factory) {
    if (disposed || typeof name !== 'string' || !name || effects.has(name) || typeof factory !== 'function') {
      throw new TypeError('Invalid or duplicate page motion effect');
    }
    effects.set(name, factory);
    remaining = [];
    return () => {
      if (effects.get(name) === factory) { effects.delete(name); remaining = []; }
    };
  }

  function shuffle(keys,last) {
    const bag=[...keys];
    for(let i=bag.length-1;i>0;i--) {
      const j=Math.floor(Math.random()*(i+1));
      [bag[i],bag[j]]=[bag[j],bag[i]];
    }
    const tail=bag.length-1;
    if(tail>0 && bag[tail]===last) [bag[0],bag[tail]]=[bag[tail],bag[0]];
    return bag;
  }
  function choose() {
    if (!remaining.length) remaining=shuffle(effects.keys(),last);
    last=remaining.pop();
    return effects.get(last);
  }

  const clip = window.CSS?.supports('clip-path', 'circle(10px at 50% 50%)');
  if (clip) register('iris', ({x, y, width, height}) => {
    // Reach the farthest corner even on a tablet or when the tap originated on a screen edge.
    const radius = Math.ceil(Math.hypot(Math.max(x, width - x), Math.max(y, height - y))) + 2;
    const circle = r => 'circle(' + r + 'px at ' + x + 'px ' + y + 'px)';
    return {duration: 315, frames: [
      {clipPath: circle(Math.min(26, radius)), opacity: .87},
      {clipPath: circle(radius), opacity: 1}
    ]};
  });

  if (clip) register('shutter', ({dx, dy}) => {
    // Mirror the leading diagonal for backward travel; rotate the same aperture for vertical tabs.
    function cut(near, far) {
      const points = [[near, 0], [110, 0], [110, 100], [far, 100]];
      return 'polygon(' + points.map(([u, v]) => {
        const [x, y] = dx ? [dx > 0 ? u : 100 - u, v] : [v, dy > 0 ? u : 100 - u];
        return x + '% ' + y + '%';
      }).join(', ') + ')';
    }
    return {duration: 295, frames: [
      {clipPath: cut(76, 111), opacity: .88}, {clipPath: cut(-3, -3), opacity: 1}
    ]};
  });

  register('focus', ({width, height, rest, transform}) => {
    const frames = [
      {opacity: .64, transform: transform('scale(1.038)')},
      {opacity: 1, transform: rest}
    ];
    // A small surface can afford real defocus. On a full page only a bounded soft light and
    // the compositor's scale/opacity run, avoiding a continuously blurred chat/WebView texture.
    if (width * height * devicePixelRatio ** 2 <= 240000) {
      frames[0].filter = 'blur(1.1px)';
      frames[1].filter = 'none';
    }
    return {duration: 260, frames, softLight: true};
  });

  register('push', ({dx, dy, width, height, transform, rest}) => {
    const offset = Math.min(56, (dx ? width : height) * .115);
    const arrive = 'perspective(980px) translate3d(' + dx * offset + 'px,' + dy * offset +
      'px,0) rotateX(' + dy * 3.8 + 'deg) rotateY(' + -dx * 3.8 + 'deg) scale(.978)';
    return {duration: 280, frames: [{opacity: .7, transform: transform(arrive)}, {opacity: 1, transform: rest}]};
  });

  function cancel() {
    const run = active;
    if (!run) return;
    active = null;
    cancelAnimationFrame(run.frame);
    for (const animation of run.animations) {
      animation.onfinish = animation.oncancel = null;
      animation.cancel();
    }
    run.light?.remove();
    run.restore?.();
    run.onFinish?.();
  }

  function watch(object, event, handler, options) {
    object.addEventListener(event, handler, options);
    handlers.push(() => object.removeEventListener(event, handler, options));
  }

  function mainSurface() {
    // DSH documents stable slot anchors. The main slot uses display:contents,
    // inside MainPanel inside the persistent center column; never animate the anchor.
    return document.querySelector('[data-slot="main"]')?.parentElement?.parentElement ||
      document.querySelector('.pI_x6G_centerCol');
  }
  function pageSurface() {
    return document.querySelector('[data-sidebar-right-panel][data-sidebar-right-open]:not([data-sidebar-right-open="false"])') ||
      mainSurface() || document.querySelector('main');
  }
  function isMainSurface(node) {
    return node && node !== document.body && node !== document.documentElement &&
      (node === mainSurface() || node.matches('.pI_x6G_centerCol'));
  }

  // One small gradient travels with the focus pull. It never receives input or covers the rail.
  function addLight(run, target, bounds, x, y, dx, dy, duration) {
    const size = Math.min(176, bounds.width, bounds.height);
    if (size < 28) return;
    const light = document.createElement('div');
    light.setAttribute('aria-hidden', 'true');
    const rail = document.getElementById('sugar-edge-navigation');
    const tint = rail?.style.getPropertyValue('--handle') || '#94aaa9';
    const clamp = (n, lo, hi) => Math.max(lo, Math.min(n, hi));
    const px = bounds.left + clamp(x - size / 2, 0, bounds.width - size);
    const py = bounds.top + clamp(y - size / 2, 0, bounds.height - size);
    Object.assign(light.style, {position: 'fixed', pointerEvents: 'none', userSelect: 'none',
      zIndex: '2147481000', left: px + 'px', top: py + 'px', width: size + 'px', height: size + 'px',
      opacity: '0', borderRadius: '50%', background: 'radial-gradient(closest-side, ' + tint + ', transparent)'});
    const root = target.getRootNode();
    const parent = target.closest('dialog[open]') || (root.host ? root : document.body);
    parent.appendChild(light);
    run.light = light;
    run.animations.push(light.animate([
      {opacity: .13, transform: 'scale(.72)'},
      {opacity: 0, transform: 'translate(' + -dx * 12 + 'px,' + -dy * 12 + 'px) scale(1.12)'}
    ], {duration, easing: 'ease-out'}));
  }

  const tapNames = clip ? ['iris','split','diagonal','focus','slide'] : ['focus','slide'];
  const tapBag = {names:[],last:''};
  function tapChoice(effect, bag) {
    if (effect && effect !== 'random') return tapNames.includes(effect) ? effect : 'slide';
    if (!bag.names.length) bag.names = shuffle(tapNames,bag.last);
    return bag.last = bag.names.pop();
  }
  function tapGeometry(bounds) {
    let left = bounds.left, right = bounds.left+bounds.width;
    const rail = document.getElementById('sugar-edge-navigation');
    if (rail?.matches('[data-expanded]:not([data-covered])')) {
      const band=rail.shadowRoot?.querySelector('.band')?.getBoundingClientRect();
      if (band && band.width<bounds.width && band.bottom>bounds.top && band.top<bounds.top+bounds.height) {
        if (band.left<=left && band.right>left) left=band.right;
        else if (band.right>=right && band.left<right) right=band.left;
      }
    }
    return {x:Math.round((left+right)/2-bounds.left),y:Math.round(bounds.height*.42),visibleWidth:right-left};
  }
  function tapEffect(name,bounds,direction) {
    const [dx,dy]=directions[direction];
    const easing='cubic-bezier(.28,.64,.28,1)';
    if (name==='iris') {
      const {x,y,visibleWidth}=tapGeometry(bounds);
      const radius=Math.ceil(Math.hypot(Math.max(x,bounds.width-x),Math.max(y,bounds.height-y)))+1;
      const circle=r=>'circle('+r+'px at '+x+'px '+y+'px)';
      const cap=Math.min(visibleWidth*.08,radius*.13);
      const change=cap/.13;
      const rim=[{clipPath:circle(0),offset:0}];
      if (change<radius) rim.push({clipPath:circle(change+cap),offset:change/radius});
      rim.push({clipPath:circle(radius+cap),offset:1});
      return {name,duration:285,easing:'cubic-bezier(.33,.07,.67,.74)',rim,frames:[{clipPath:circle(0)},{clipPath:circle(radius)}]};
    }
    if (name==='split') return {name,duration:280,easing,frames:[
      {clipPath:'inset(0% 50% 0% 50%)'},{clipPath:'inset(0% 0% 0% 0%)'}]};
    if (name==='diagonal') {
      function edge(near,far) {
        const points=[[near,0],[165,0],[165,100],[far,100]];
        return 'polygon('+points.map(([u,v])=>{
          const [x,y]=dx?[dx>0?u:100-u,v]:[v,dy>0?u:100-u];
          return x+'% '+y+'%';
        }).join(',')+')';
      }
      return {name,duration:285,easing,frames:[{clipPath:edge(101,145)},{clipPath:edge(-45,-1)}]};
    }
    if (name==='focus') return {name,duration:245,easing,frames:[
      {transform:'scale(.94)',opacity:0},{transform:'scale(.985)',opacity:.82,offset:.58},{transform:'scale(1)',opacity:1}]};
    return {name:'slide',duration:245,easing,frames:[
      {transform:'translate3d('+dx*100+'%,'+dy*100+'%,0)'},{transform:'translate3d(0%,0%,0)'}]};
  }
  function runTap(run,to,preset,from,exiting=false) {
    const timing={duration:preset.duration,easing:preset.easing,fill:'both',direction:exiting?'reverse':'normal'};
    const start=document.timeline?.currentTime ?? performance.now();
    function animate(el,frames,id) {
      const flight=el.animate(frames,{...timing,id});
      flight.startTime=start;
      run.animations.push(flight);
      return flight;
    }
    const main=animate(to,preset.frames,'sugar-pane-'+preset.name);
    if (from && preset.rim) {
      // The larger translucent disk is painted on the outgoing page. The opaque new
      // page above it covers its interior, leaving only the advancing rim visible.
      const b=to.getBoundingClientRect(), old=from.getBoundingClientRect();
      const rim=document.createElement('div');
      rim.setAttribute('aria-hidden','true');
      Object.assign(rim.style,{position:'absolute',pointerEvents:'none',userSelect:'none',
        left:(b.left-old.left+from.scrollLeft-from.clientLeft)+'px',top:(b.top-old.top+from.scrollTop-from.clientTop)+'px',
        width:b.width+'px',height:b.height+'px',background:pagePaper(to),opacity:'.6',zIndex:'1'});
      if (getComputedStyle(from).position==='static') {
        const oldPosition=from.style.position;
        from.style.position='relative';
        run.restore=()=>{from.style.position=oldPosition;};
      }
      from.appendChild(rim);run.light=rim;
      animate(rim,preset.rim,'sugar-iris-rim');
    }
    main.onfinish=main.oncancel=()=>{if(active===run)cancel();};
  }
  function playablePane(el) {
    const rail=document.getElementById('sugar-edge-navigation');
    return el?.isConnected && typeof el.animate==='function' && el!==document.body && el!==document.documentElement &&
      !(rail && (el===rail || el.contains(rail) || el.getRootNode()===rail.shadowRoot));
  }
  function playBetween(from,to,{direction='forward',effect='random',onFinish}={}) {
    if (drag) return null;
    cancel();
    if (disposed || reduced || media.matches || document.hidden || composing || performance.now()-lastInput<200 ||
        !Object.hasOwn(directions,direction) || from===to || !playablePane(from) || !playablePane(to) ||
        from.parentElement!==to.parentElement) return null;
    const b=to.getBoundingClientRect();
    if (b.width<8 || b.height<8) return null;
    const run={animations:[],frame:0};
    active=run;
    try {
      runTap(run,to,tapEffect(tapChoice(effect,tapBag),b,direction),from);
      run.onFinish=onFinish;
    } catch {
      cancel();return null;
    }
    return ()=>{if(active===run)cancel();};
  }

  function play({target, direction = 'forward', origin, navigationTap = false, effect:tapPreset = 'random', exiting = false, onFinish} = {}) {
    if (drag) return false;
    cancel();
    if (disposed || reduced || media.matches || document.hidden || composing ||
        performance.now() - lastInput < 200 || !Object.prototype.hasOwnProperty.call(directions, direction) || !effects.size) return false;
    if (navigationTap) {
      const el=target || pageSurface();
      if (!playablePane(el) || el.matches('input, textarea, select, [contenteditable=true], [hidden]')) return false;
      const b=el.getBoundingClientRect();
      if (b.width<8 || b.height<8) return false;
      const run={animations:[],frame:0};
      active=run;
      try {
        runTap(run,el,tapEffect(tapChoice(tapPreset,tapBag),b,direction),null,exiting);
        run.onFinish=onFinish;
      } catch { cancel();return false; }
      return true;
    }
    const run = {animations: [], frame: 0, light: null, onFinish};
    active = run;
    run.frame = requestAnimationFrame(() => {
      if (active !== run) return;
      const el = target || pageSurface();
      const rail = document.getElementById('sugar-edge-navigation');
      if (!el?.isConnected || typeof el.animate !== 'function' || el === document.body ||
          el === document.documentElement || (rail && (el.contains(rail) || el === rail || el.getRootNode() === rail.shadowRoot)) ||
          el.matches('input, textarea, select, [contenteditable=true], [hidden]')) { cancel(); return; }
      const b = el.getBoundingClientRect();
      if (b.width < 8 || b.height < 8) { cancel(); return; }
      const [dx, dy] = directions[direction];
      const coord = (value, start, length, axis) => Math.round(Math.max(0, Math.min(length,
        Number.isFinite(value) ? value - start : length * (.5 + axis * .41))));
      const x = coord(origin?.x, b.left, b.width, dx), y = coord(origin?.y, b.top, b.height, dy);
      const rest = getComputedStyle(el).transform || 'none';
      const transform = suffix => rest === 'none' ? suffix : rest + ' ' + suffix;
      try {
        const effect = choose()({width: b.width, height: b.height, dx, dy, x, y, rest, transform});
        const duration = Math.min(450, Math.max(90, Number(effect.duration) || 260));
        const animation = el.animate(effect.frames, {duration, easing: effect.easing || 'cubic-bezier(.2,.78,.25,1)'});
        run.animations.push(animation);
        if (effect.softLight) addLight(run, el, b, x, y, dx, dy, duration);
        animation.onfinish = animation.oncancel = () => { if (active === run) cancel(); };
      } catch (error) {
        cancel();
        console.warn('Sugar page transition skipped:', error);
      }
    });
    return true;
  }

  // Both page surfaces live below the rail. The opaque incoming page slides over the
  // stationary outgoing one. The compositor's images are confined to the DSH column.
  const dragRules = `
    [data-sugar-drag-scope] {view-transition-name:root!important}
    [data-sugar-drag-scope]::view-transition {pointer-events:none}
    [data-sugar-drag-scope]::view-transition-group(root),
    [data-sugar-drag-scope]::view-transition-image-pair(root) {animation:none;overflow:clip;isolation:isolate;background:var(--sugar-source-paper)}
    [data-sugar-drag-scope]::view-transition-old(root),
    [data-sugar-drag-scope]::view-transition-new(root) {
      animation:sugar-hold-drag 1s linear paused both;mix-blend-mode:normal;pointer-events:none;
    }
    [data-sugar-drag-scope]::view-transition-old(root) {z-index:0;background:var(--sugar-source-paper)}
    [data-sugar-drag-scope]::view-transition-new(root) {z-index:1;background:var(--sugar-target-paper)}
    @keyframes sugar-hold-drag {from,to {opacity:1}}
  `;
  function pagePaper(node) {
    for (let el = node; el; el = el.parentElement) {
      const color = getComputedStyle(el).backgroundColor;
      if (color && /^rgb\(/.test(color)) return color;
    }
    return getComputedStyle(node).colorScheme === 'dark' ? '#202124' : '#fff';
  }
  // WAAPI and requestAnimationFrame share the document timeline. Both surfaces use
  // the same velocity at release and reach the dock with zero remaining velocity.
  function releaseTiming(remaining, velocityTowardEnd) {
    const distance = Math.min(1, Math.abs(remaining) || 0);
    const velocity = Number.isFinite(velocityTowardEnd) ? Math.max(0, velocityTowardEnd) : null;
    const duration = distance < .001 ? 0 : Math.min(168, 38 + 142 * distance,
      velocity > 0 ? Math.max(42, 1.8 * distance / velocity) : Infinity);
    const slope = velocity === null ? 1.7 : Math.min(2.7, velocity * duration / (distance || 1));
    const startTime = document.timeline?.currentTime ?? performance.now();
    return {startTime, duration, easing: 'cubic-bezier(0.333333,' + slope / 3 + ',0.666667,1)',
      fractionAt(now) {
        if (!duration || now >= startTime + duration) return 1;
        const t = Math.max(0, (now - startTime) / duration);
        return t * (t * (3 - 2 * t) + slope * (1 - t) ** 2);
      }};
  }
  const hero = '[data-conversation-content][data-conversation-region="chat"][data-content-phase="hero"]';
  function visibleHero(column) {
    return [...(column?.querySelectorAll(hero) || [])].find(el => {
      const r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility === 'visible';
    });
  }
  const scrolled = new WeakSet();
  watch(document, 'scroll', event => scrolled.add(event.target), {capture:true, passive:true});
  function snapshot(column, current, key) {
    // Copy into an inert template document. Strip behavior, editor contents and
    // external resources before any of this markup can return to a live document.
    const template = document.createElement('template');
    const page = template.content.ownerDocument.importNode(column, true);
    // DOM cloning does not copy scroll offsets. Remember the visible scrollports
    // before removing other session trees or scrubbing the draft from the replica.
    const scroll = [], selected = column.querySelectorAll('[data-conversation-content]')[current];
    // DSH may have restored its chat scroll before this script attached.
    // Seed just the message's ancestors, then consult actual scroll events
    // for nested code blocks and other manually scrolled containers.
    for (let node = selected?.querySelector(chatRow); node && node !== column; node = node.parentElement)
      scrolled.add(node);
    const nodes = column.querySelectorAll('*'), copies = page.querySelectorAll('*');
    for (let i = 0; i < nodes.length; i++) {
      if (current >= 0 && !scrolled.has(nodes[i])) continue;
      const left = nodes[i].scrollLeft, top = nodes[i].scrollTop;
      if (left || top) scroll.push([copies[i], left, top]);
    }
    page.querySelectorAll('[data-conversation-content]').forEach((el,i) => { if (i !== current) el.remove(); });
    const omit = 'script,style,link,meta,base,audio,video,canvas,iframe,object,embed,source,picture,image,' +
      'foreignObject,template,[is],[hidden],input[type=password],[data-composer-seat] ._54WpYG_rail,' +
      '[role=dialog],[role=listbox],[role=menu],[role=tooltip]';
    for (const node of page.querySelectorAll(omit)) node.remove();
    for (const node of [page, ...page.querySelectorAll('*')]) {
      if (node.localName.includes('-')) { node.remove(); continue; }
      if (node.matches('input,textarea,[contenteditable],[data-composer-input],[data-composer-overlay],[data-queue-dock]')) {
        node.textContent = '';
        if ('value' in node) node.value = '';
      }
      for (const name of node.getAttributeNames()) {
        if (/^(?:id|name|is|value|for|form|action|formaction|autofocus|contenteditable|tabindex|srcset|srcdoc|on.*)$/i.test(name) ||
            /^(?:href|xlink:href)$/i.test(name) && !(node.localName === 'use' && node.getAttribute(name).startsWith('#')))
          node.removeAttribute(name);
      }
      if (node.hasAttribute('src') && !(node.localName === 'img' && /^data:image\/(?:png|webp|jpeg);/i.test(node.getAttribute('src'))))
        node.removeAttribute('src');
    }
    page.inert = true;
    page.setAttribute('aria-hidden', 'true');
    // The replica has a different parent. DSH's Android safe-area selectors can
    // otherwise add the bottom inset twice and lift the whole hero at handoff.
    const layout = getComputedStyle(column);
    return {key, page, scroll:scroll.filter(([node]) => page.contains(node)), bottom:layout.paddingBottom,
      viewportWidth:innerWidth, height:column.getBoundingClientRect().height, paper:pagePaper(column)};
  }
  // Only the most recent peer is retained, in this document's memory.
  // Panels share this path with chats; no plugin IDs or per-page selectors are needed.
  // Take it when leaving a page, never on each streaming token or pointer move.
  let peerPaper, homePaper, homeObserver, homeFrame = 0, homeKey;
  function rememberPeer(column, key) {
    if (!key || !isMainSurface(column)) return;
    const regions = [...column.querySelectorAll('[data-conversation-content]')];
    const current = regions.findIndex(el => el.matches(chatRegion) && el.getBoundingClientRect().height &&
      el.querySelector(chatRow) && getComputedStyle(el).visibility === 'visible');
    const panel = Boolean(column.querySelector('[data-global-panel]:not([data-global-panel="false"])'));
    if (current < 0 && !panel) return;
    // Bound the replica size. Large chats can use scoped capture; uncached panels wait for release.
    if (column.getElementsByTagName('*').length > 3500) { peerPaper = null; return; }
    peerPaper = snapshot(column, panel ? -1 : current, key);
    peerPaper.session = panel ? null : regions[current].getAttribute('data-conversation-session');
  }
  function stopWatchingHome() {
    homeObserver?.disconnect(); homeObserver = null;
    cancelAnimationFrame(homeFrame); homeFrame = 0;
  }
  // A first prompt can consume DSH's blank session. Retain one inert visual of
  // the genuine empty home so a tentative swipe never has to create a session.
  function watchHome(key) {
    if (key === homeKey) return;
    stopWatchingHome(); homeKey = key;
    if (!key) return;
    homePaper = null;
    function remember() {
      homeFrame = 0;
      const column = mainSurface();
      if (!column) return;
      const regions = [...column.querySelectorAll('[data-conversation-content]')];
      const current = regions.indexOf(visibleHero(column));
      if (current < 0) return;
      homePaper = snapshot(column, current, key);
      stopWatchingHome();
    }
    const schedule = () => { if (!homeFrame) homeFrame = requestAnimationFrame(remember); };
    homeObserver = new MutationObserver(schedule);
    homeObserver.observe(document.body, {childList:true, subtree:true, attributes:true, attributeFilter:['data-content-phase']});
    schedule();
  }
  const chatRegion = '[data-conversation-region="chat"][data-conversation-session]';
  const chatRow = '[data-chat-node-key],[data-chat-turn],[data-message-bubble]';
  function populatedSession(column, expected) {
    for (const region of column.querySelectorAll(chatRegion)) {
      const id = region.getAttribute('data-conversation-session');
      if (id && (expected === undefined || id === expected) &&
          region.getBoundingClientRect().height && region.querySelector(chatRow)) return id;
    }
    return null;
  }
  function waitForConversation(column, session) {
    if (!session || !column) return;
    const ready = () => !column.isConnected || document.hidden || populatedSession(column, session);
    if (ready()) return;
    // The store can restore the conversation header and composer a frame or two before its rows.
    // Keep the compositor's original picture until the real rows can replace it.
    return new Promise(resolve => {
      const done = () => { observer.disconnect(); clearTimeout(limit); resolve(); };
      const observer = new MutationObserver(() => { if (ready()) done(); });
      const limit = setTimeout(done, 400);
      observer.observe(column, {subtree:true,childList:true,attributes:true,
        attributeFilter:['data-conversation-session','data-content-phase','hidden']});
    });
  }
  function beginDrag({target, direction = 'forward', navigate, restore, canStage = true, captureScope, captureAllowed, homeTarget, peerTarget, originKey, webRoute = false, onProgress} = {}) {
    if (disposed || drag || !Object.prototype.hasOwnProperty.call(directions, direction) ||
        typeof navigate !== 'function') return null;
    cancel();
    const surface = typeof target === 'function' ? target : () => target || pageSurface();
    const original = surface();
    if (!original?.isConnected) return null;
    const [dx, dy] = directions[direction];
    // An existing home preview is enough to follow the finger. Reopening the live blank here
    // would unmount the chat and make DSH reload its messages even when the user turns back.
    const savedHome = homeTarget && homePaper?.key === homeTarget ? homePaper : null;
    const savedPeer = peerTarget && peerPaper?.key === peerTarget &&
      peerPaper.viewportWidth === innerWidth && Math.abs(peerPaper.height-original.getBoundingClientRect().height) < 1 ? peerPaper : null;
    const saved = savedHome || savedPeer;
    const fixedSurface = webRoute || Boolean(peerTarget || homeTarget);
    const scope = canStage && !saved && captureScope === original && isMainSurface(original) &&
      typeof original.startViewTransition === 'function' ? original : null;
    const bounds = (scope || original).getBoundingClientRect();
    const sourceSession = scope && populatedSession(scope);
    const quiet = () => disposed || reduced || media.matches || document.hidden;
    const tracks = [];
    let progress = 0, staged = false, pending, finishing, finishPromise, transition, style, snap = false;
    let paperLayer, sourcePaper;
    let applied = false, live, immediate = false, frame = 0, endSettle, mutation;
    async function cleanVisual() {
      live?.cancel(); live = null;
      paperLayer?.remove(); paperLayer = null;
      const vt = transition;
      if (vt) {
        // The compositor owns this settings subtree until finished settles.
        // Let it release both frames before restoring a route that may unmount it.
        vt.skipTransition();
        try { await vt.finished; } catch { /* A failed update has no remaining capture. */ }
        transition = null;
      }
      while (tracks.length) tracks.pop().cancel();
      if (scope) {
        scope.removeAttribute('data-sugar-drag-scope');
        scope.style.removeProperty('--sugar-source-paper');
        scope.style.removeProperty('--sugar-target-paper');
      }
      style?.remove(); style = null;
      snap = false;
    }
    function translate(x, y) { return 'translate3d(' + x + 'px,' + y + 'px,0)'; }
    function track(el, frames, pseudoElement) {
      const a = el.animate(frames, {duration: 1000, fill: 'both', easing: 'linear',
        ...(pseudoElement && {pseudoElement})});
      a.pause(); a.currentTime = progress * 1000;
      return a;
    }
    function showLive(el, incoming) {
      if (!el?.isConnected || !el.animate || quiet()) return;
      live?.cancel();
      const box = el.getBoundingClientRect();
      const extent = dx ? box.width : box.height;
      const distance = Math.min(96, extent * .28);
      const rest = getComputedStyle(el).transform;
      const at = n => (rest === 'none' ? '' : rest + ' ') + translate(dx * distance * n, dy * distance * n);
      live = track(el, incoming ? [{transform: at(1)}, {transform: at(0)}] :
        [{transform: at(0)}, {transform: at(-1)}]);
    }
    function revealFrames(incoming = true) {
      const sign = incoming ? 1 : -1;
      const closed = dx * sign < 0 ? 'inset(0% 100% 0% 0%)' : dx * sign > 0 ? 'inset(0% 0% 0% 100%)' :
        dy * sign < 0 ? 'inset(0% 0% 100% 0%)' : 'inset(100% 0% 0% 0%)';
      const frames = [{clipPath:closed}, {clipPath:'inset(0% 0% 0% 0%)'}];
      return incoming ? frames : frames.reverse();
    }
    function showPaper(visual, incoming) {
      const clip = document.createElement('div'), page = visual.page;
      clip.inert = true; clip.setAttribute('aria-hidden', 'true');
      clip.setAttribute(incoming && savedHome ? 'data-sugar-home-preview' : 'data-sugar-peer-preview', '');
      Object.assign(clip.style, {position:'fixed', left:bounds.left+'px', top:bounds.top+'px',
        width:bounds.width+'px', height:bounds.height+'px', overflow:'clip', pointerEvents:'none', zIndex:'2147480000'});
      Object.assign(page.style, {position:'absolute', inset:'0', width:'100%', height:'100%', margin:'0',
        backgroundColor:visual.paper, overflow:'hidden', pointerEvents:'none'});
      const bottom = visual.viewportWidth === innerWidth ? visual.bottom : getComputedStyle(original).paddingBottom;
      page.style.setProperty('padding-bottom', bottom, 'important');
      clip.append(page); original.after(clip); paperLayer = clip;
      for (const [node, left, top] of visual.scroll) {
        if (node.isConnected) { node.scrollLeft = left; node.scrollTop = top; }
      }
      if (incoming) live = track(clip, revealFrames());
      else {
        // Older WebViews have no element-scoped compositor capture. The real
        // destination paints under this stationary, progressively clipped source.
        sourcePaper = visual;
        live = track(clip, revealFrames(false));
      }
    }
    function paint(p) {
      progress = p;
      onProgress?.(p);
      if (live) live.currentTime = p * 1000;
      for (const a of tracks) a.currentTime = p * 1000;
    }
    function change() {
      if (!mutation) mutation = (async () => {
        if (finishing === 'cancel') return false;
        applied = Boolean(await navigate());
        return applied;
      })();
      return mutation;
    }
    async function stage() {
      if (paperLayer) {
        await change();
        if (applied && savedPeer) await waitForConversation(surface(), savedPeer.session);
        if (applied && savedHome && !visibleHero(surface())) {
          // The route store can acknowledge before React paints its hero. Leave
          // the foreground in place through that handoff, with a bounded fallback.
          await new Promise(resolve => {
            const done = () => { observer.disconnect(); clearTimeout(limit); resolve(); };
            const observer = new MutationObserver(() => { if (visibleHero(surface())) done(); });
            const limit = setTimeout(done, 400);
            observer.observe(document.body, {childList:true,subtree:true,attributes:true,attributeFilter:['data-content-phase']});
          });
        }
        return;
      }
      if (quiet() || !canStage || !scope || !original.animate) {
        await change();
        if (applied && !fixedSurface) showLive(surface(), true);
        return;
      }
      let invoked = false;
      try {
        style = document.createElement('style');
        style.textContent = dragRules;
        document.head.appendChild(style);
        scope.style.setProperty('--sugar-source-paper', pagePaper(scope));
        scope.setAttribute('data-sugar-drag-scope', '');
        live?.cancel(); live = null;
        transition = scope.startViewTransition(async () => {
          invoked = true;
          const changed = await change();
          if (!changed || surface() !== scope || !scope.isConnected || !captureAllowed?.()) {
            transition?.skipTransition();
            return;
          }
          scope.style.setProperty('--sugar-target-paper', pagePaper(scope));
        });
        const vt = transition;
        vt.finished.catch(() => {});
        await vt.ready;
        if (!applied || immediate || transition !== vt) return;
        tracks.push(track(scope, revealFrames(), '::view-transition-new(root)'));
        snap = true;
      } catch {
        // The browser may decline capture (hidden page, another transition, unavailable surface).
        // A skipped transition still runs its update callback. Never issue the same navigation twice.
        if (transition) { try { await transition.updateCallbackDone; } catch {} }
        else if (!invoked) await change();
      } finally {
        if (!snap) {
          await cleanVisual();
          if (!immediate && !fixedSurface) showLive(applied ? surface() : original, applied);
        }
      }
    }
    function start() {
      if (staged) return pending;
      staged = true;
      pending = stage().catch(error => { console.warn('Sugar page preview failed:', error); });
      return pending;
    }
    function update(value) {
      if (finishing || !Number.isFinite(value)) return;
      paint(Math.max(0, Math.min(1, value)));
      // The rail sends updates only after it has distinguished history navigation from an edge reveal.
      if ((!paperLayer || sourcePaper) && !quiet() && canStage && typeof restore === 'function' &&
          (scope || sourcePaper ? progress > 0 : !fixedSurface && progress >= .25)) start();
    }
    function settle(to, release) {
      if (immediate || quiet() || progress === to) { paint(to); return Promise.resolve(); }
      const from = progress, timing = release || releaseTiming(to - from);
      if (!timing.duration) { paint(to); return Promise.resolve(); }
      return new Promise(resolve => {
        endSettle = () => { cancelAnimationFrame(frame); paint(to); endSettle = null; resolve(); };
        function step(now) {
          const t = immediate || quiet() ? 1 : timing.fractionAt(now);
          if (t >= 1) { endSettle(); return; }
          paint(from + (to - from) * t);
          frame = requestAnimationFrame(step);
        }
        frame = requestAnimationFrame(step);
      });
    }
    function finish(commit, instantly = false, release) {
      if (instantly) { immediate = true; endSettle?.(); }
      if (finishPromise) return finishPromise;
      finishing = commit ? 'commit' : 'cancel';
      if (instantly && transition && !snap) transition.skipTransition();
      // Opening a settings pane may synchronously update motion preferences inside navigate().
      // Install the single completion promise before the navigation callback can reenter.
      finishPromise = Promise.resolve().then(async () => {
        try {
          // The release motion and route preparation can finish independently. Waiting for
          // the route first would add another full animation after a slow DSH response.
          if (commit && paperLayer && !sourcePaper) {
            // The outgoing live page is visible outside the cut. Changing its route
            // mid-flight would show the destination on both sides of the moving rail.
            await settle(1, release);
            await start();
          } else if (commit && fixedSurface && !scope && !sourcePaper) {
            // Media/active editors can rule out both captures. Leave the live route at
            // its real coordinates until the release finishes on that WebView.
            await settle(1, release);
            await start();
          } else await Promise.all([commit ? start() : pending, settle(commit ? 1 : 0, release)]);
          if (commit && !applied) await settle(0);
          return commit && applied;
        } finally {
          try {
            const rollback = !commit && applied && typeof restore === 'function';
            if (rollback && (sourcePaper || snap && scope.isConnected)) {
              // At zero the old image fully covers the staged page. Put its real
              // route back underneath before removing that last compositor frame.
              try {
                if (await restore()) await waitForConversation(surface(), sourcePaper?.session || sourceSession);
              } finally { await cleanVisual(); }
            } else {
              await cleanVisual();
              if (rollback) await restore();
            }
          } finally {
            if (drag === gesture) drag = null;
          }
        }
      });
      return finishPromise;
    }
    const gesture = {update, finish};
    drag = gesture;
    if (originKey && !quiet()) rememberPeer(original, originKey);
    const source = canStage && !saved && !scope && originKey && (originKey === peerPaper?.key ? peerPaper :
      originKey === homePaper?.key ? homePaper : null);
    if ((saved || source) && !quiet() && original.animate) showPaper(saved || source, Boolean(saved));
    else if (!scope && !fixedSurface) showLive(original, false);
    return gesture;
  }

  function stopAll() { cancel(); drag?.finish(false, true).catch(() => {}); }
  const mediaChanged = () => { if (media.matches) stopAll(); };
  if (typeof media.addEventListener === 'function') watch(media, 'change', mediaChanged);
  else { media.addListener(mediaChanged); handlers.push(() => media.removeListener(mediaChanged)); }
  watch(window, 'pagehide', stopAll);
  watch(document, 'visibilitychange', () => { if (document.hidden) stopAll(); });
  watch(document, 'pointerdown', cancel, {capture: true, passive: true});
  watch(document, 'keydown', cancel, true);
  watch(document, 'compositionstart', () => { composing = true; cancel(); }, true);
  watch(document, 'compositionend', () => { composing = false; lastInput = performance.now(); }, true);
  watch(document, 'input', () => { lastInput = performance.now(); cancel(); }, true);
  const api = {
    play, playBetween, register, releaseTiming, surface: pageSurface, watchHome, rememberPeer, beginDrag, cancel: stopAll,
    setReduced(value) {
      if (reduced === (value === true)) return;
      reduced = value === true;
      if (reduced) stopAll();
    },
    dispose() {
      disposed = true;
      stopAll();
      while (handlers.length) handlers.pop()();
      stopWatchingHome(); homePaper = peerPaper = null;
      effects.clear();
      if (window.SugarPageMotion === api) delete window.SugarPageMotion;
    }
  };
  window.SugarPageMotion = api;
})();
