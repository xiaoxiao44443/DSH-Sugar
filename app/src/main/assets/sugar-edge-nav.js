// Android injects this component only in the active local DSH page.
(() => {
  if (window !== window.top || !document.body) return;
  window.SugarEdgeNav?.dispose();

  window.SugarI18n?.add({
    "DSH Sugar 应用导航": ["DSH Sugar navigation", "DSH Sugar ナビゲーション"],
    "收起侧边导航": ["Collapse side navigation", "サイドナビを閉じる"],
    "展开侧边导航": ["Expand side navigation", "サイドナビを開く"],
    "从右侧展开导航": ["Open navigation from the right", "右端からナビを開く"],
    "从左侧展开导航": ["Open navigation from the left", "左端からナビを開く"],
    "回到 DSH 主页": ["Go to DSH home", "DSH ホームに戻る"],
    "主页": ["Home", "ホーム"],
    "返回": ["Back", "戻る"],
    "本机时间": ["Local time", "現在時刻"]
  });
  const t = value => window.SugarI18n?.text(value) ?? value;

  const host = document.createElement('div');
  host.id = 'sugar-edge-navigation';
  const root = host.attachShadow({ mode: 'open' });
  const unfoldMs = 150;
  const style = document.createElement('style');
  style.textContent = `
    :host { all: initial; position: fixed; inset: 0; z-index: 2147483000;
      pointer-events: none; direction: ltr; font-family: Roboto, system-ui, sans-serif;
      -webkit-tap-highlight-color: transparent; --width: 62px; --button-width: 54px;
      --item-margin: calc((var(--width) - 1px - var(--button-width)) / 2);
      --unfold-time: ${unfoldMs}ms;
      --top: calc(var(--sugar-safe-top, env(safe-area-inset-top, 0px)) + 7px);
      --bottom: calc(var(--sugar-safe-bottom, env(safe-area-inset-bottom, 0px)) + 7px);
      --left-inset: var(--sugar-safe-left, env(safe-area-inset-left, 0px));
      --right-inset: var(--sugar-safe-right, env(safe-area-inset-right, 0px));
      --edge: var(--right-inset); --other-edge: var(--left-inset);
      --row: 52px; --tail: 9px;
      --depth: 1; --offset: 100%; }
    :host([data-side="left"]) { --depth: -1; --offset: -100%;
      --edge: var(--left-inset); --other-edge: var(--right-inset); }
    *, *::before, *::after { box-sizing: border-box; }
    .dock { position: absolute; top: 0; bottom: 0; right: 0;
      width: calc(var(--width) + var(--edge)); color: var(--ink); user-select: none; }
    :host([data-side="left"]) .dock { right: auto; left: 0; }
    .other-dock { position: absolute; left: 0; top: 0; bottom: 0;
      width: calc(var(--width) + var(--other-edge)); color: var(--ink); }
    :host([data-side="left"]) .other-dock { left: auto; right: 0; }
    :host([data-covered]) .dock, :host([data-covered]) .other-dock { display: none; }
    button { appearance: none; border: 0; margin: 0; padding: 0; color: inherit;
      background: none; font: inherit; cursor: pointer; touch-action: none; }
    button:focus-visible { outline: 2px solid currentColor; outline-offset: -3px; }
    .track { position: absolute; inset: 0; transform: translateX(var(--offset));
      transition: transform var(--unfold-time) cubic-bezier(.16,.8,.22,1); }
    .track::before { content: ''; position: absolute; inset-block: 0; right: 100%; width: 7px;
      pointer-events: none; background: var(--edge-shadow); opacity: 0; transition: opacity 105ms; }
    :host([data-side="left"]) .track::before { right: auto; left: 100%; transform: scaleX(-1); }
    :host(:is([data-expanded],[data-drag])) .track::before { opacity: 1; }
    :host([data-expanded]) .track { transform: translateX(0); }
    :host(:is([data-drag],[data-landing])) .track, :host(:is([data-drag],[data-landing])) .grip { transition: none; }
    :host([data-landing]) .band { visibility: visible; }
    :host([data-moving]) .track { will-change: transform; }
    /* The revealed page supplies the far end of the gradient, including images and text. */
    .track[data-reveal]::after { content:''; position:absolute; inset-block:0; pointer-events:none;
      width:min(var(--width),18vw); opacity:var(--reveal-opacity);
      background:linear-gradient(var(--reveal-direction),var(--glass),transparent); }
    .track[data-reveal="right"]::after { left:100%; --reveal-direction:to right; }
    .track[data-reveal="left"]::after { right:100%; --reveal-direction:to left; }
    @supports ((backdrop-filter:blur(1px)) or (-webkit-backdrop-filter:blur(1px))) and
              ((mask-image:linear-gradient(black,transparent)) or (-webkit-mask-image:linear-gradient(black,transparent))) {
      :host([data-blur]) .track[data-reveal]::after { background:var(--glass);
        -webkit-backdrop-filter:var(--reveal-blur); backdrop-filter:var(--reveal-blur);
        --feather:linear-gradient(var(--reveal-direction), black, rgba(0,0,0,.42) 48%, transparent);
        -webkit-mask-image:var(--feather); mask-image:var(--feather); }
    }
    .band { position: absolute; inset: 0; display: flex; flex-direction: column;
      align-items: center; justify-content: space-between; overflow: hidden;
      visibility: hidden;
      padding: var(--top) var(--edge) var(--bottom) 0; border-left: 1px solid var(--hairline);
      background: var(--glass); transition: visibility 0s var(--unfold-time);
      touch-action: pan-y; }
    :host([data-side="left"]) .band { padding: var(--top) 0 var(--bottom) var(--edge);
      border-left: 0; border-right: 1px solid var(--hairline); }
    :host([data-expanded]) .band, :host([data-drag]) .band {
      visibility: visible; pointer-events: auto; transition-delay: 0s; }
    @supports ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px))) {
      /* Filter the page behind the moving band, then paint its tint and sharp controls. */
      :host([data-blur]) .band {
        -webkit-backdrop-filter: var(--frost-filter); backdrop-filter: var(--frost-filter); }
    }
    .cap, .item { position: relative; display: grid; justify-items: center; align-content: center;
      width: min(100%, var(--button-width)); margin-inline: auto; min-height: 48px; flex-shrink: 0; }
    .head, .actions { position: relative; }
    .head { width: 100%; flex-shrink: 0; display: flex; flex-direction: column; align-items: center; }
    .cap { height: 58px; }
    .seal { display: block; width: 29px; height: 29px; object-fit: contain; }
    .clock { --clock-size: min(calc((var(--width) - 1px) * .63),
      clamp(30px, calc((100vh - var(--top) - var(--bottom)) * .049), 42px));
      padding: 5px 0 6px; writing-mode: vertical-rl; white-space: nowrap;
      font: 500 var(--clock-size)/1.04 Roboto, system-ui, sans-serif;
      font-variant-numeric: tabular-nums; letter-spacing: -.055em; text-shadow: var(--text-halo); }
    svg { display: block; width: 22px; height: 22px; fill: none; stroke: currentColor;
      stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.55; }
    .seal, .item > * { pointer-events: none; }
    .item svg { filter: var(--icon-halo); }
    :host([data-moving]) .item svg { filter: none; }
    .actions { width: 100%; min-height: 0; display: flex; flex-direction: column; padding-bottom: var(--tail); }
    .context { min-height: 0; overflow-y: auto; scrollbar-width: none; overscroll-behavior: contain; }
    .context::-webkit-scrollbar { display: none; }
    .fixed { flex-shrink: 0; }
    .item { min-height: var(--row); touch-action: pan-y; }
    .item .swatch { fill:var(--action-color); stroke:currentColor; stroke-width:1.2; }
    .item[hidden] { display: none; }
    .item[aria-current="page"] { font-weight: 650; }
    .item[aria-current="page"] svg { stroke-width: 2; }
    .item:disabled { opacity: .38; cursor: default; }
    .item i { position: absolute; right: calc(4px - var(--item-margin)); top: 50%; width: 4px; height: 20px;
      margin-top: -10px; border-radius: 4px; background: currentColor; box-shadow: var(--text-halo);
      visibility: hidden; }
    .moving-mark { display: none; position: absolute; z-index: 1; pointer-events: none;
      width: 4px; height: 20px; background: currentColor; border-radius: 4px; box-shadow: var(--text-halo); }
    .band[data-marker-sliding] .moving-mark { display: block; }
    .band[data-marker-sliding] .item i { visibility: hidden; }
    :host([data-side="left"]) .item i { right: auto; left: calc(4px - var(--item-margin)); }
    .item[aria-current="page"] i { visibility: visible; }
    .item[data-pulse], .item:not(:disabled):active, .cap:active { background: var(--wash); }
    .grip, .other-grip { position: absolute; z-index: 1; top: 50%;
      height: min(176px, 38%); min-height: 36px;
      transform: translateY(-50%); display: flex; align-items: center; justify-content: flex-end;
      pointer-events: auto; }
    .grip { left: 0; width: calc(20.5px + var(--edge));
      --fold-distance: calc(-20.5px - var(--edge));
      transform: translate(calc(var(--fold-distance) * var(--grip-fold, 1) + var(--grip-cross, 0px)), -50%);
      transition: transform var(--unfold-time) cubic-bezier(.16,.8,.22,1); }
    :host([data-side="left"]) .grip { left: auto; right: 0; --fold-distance: calc(20.5px + var(--edge)); }
    :host([data-expanded]) .grip, :host([data-drag]) .grip { --grip-fold: 0; width: 20.5px; }
    .other-grip { width: calc(18px + var(--other-edge)); left: 0; justify-content: flex-start; pointer-events: none; }
    :host([data-side="left"]) .other-grip { left: auto; right: 0; justify-content: flex-end; }
    .tab { position: absolute; left: 5px; width: 8.5px; height: 44px; border-radius: 99px;
      background: var(--idle-handle); opacity: .18; transition: opacity 150ms; }
    :host([data-side="left"]) .tab { left: auto; right: 5px; }
    :host(:is([data-expanded],[data-drag])) .tab { background: var(--handle); opacity: 1; }
    .grip:focus-visible .tab { opacity: 1; }
    :host([data-expanded][data-motion]) .item > svg { animation: arrive 140ms backwards; animation-delay: var(--delay); }
    @keyframes arrive { from { opacity: 0; transform: translateX(calc(6px * var(--depth))); } }
    @media (max-height: 650px) {
      .clock { --clock-size: min(calc((var(--width) - 1px) * .63), clamp(23px, calc((100vh - var(--top) - var(--bottom)) * .047), 26px)); }
    }
    @media (max-height: 470px) { .clock { display: none; } }
    @media (max-height: 610px) {
      :host { --row: 48px; }
      .cap { height: 48px; }
      .grip, .other-grip { height: min(150px, 24%); }
    }
    @media (max-height: 425px) {
      :host { --row: 44px; --tail: 3px; }
      .cap { height: 44px; min-height: 0; }
      .grip, .other-grip { height: min(90px, 18%); }
    }
    @media (orientation: landscape) and (min-height: 291px) and (max-height: 550px) {
      /* Keep every action visible while space allows, then scroll the contextual group. */
      :host { --row: clamp(36px, 10.5vh, 46px); --tail: 3px; }
      .context .item { min-height:clamp(32px, calc((100vh - var(--top) - var(--bottom) - 36px - var(--tail) - 2 * var(--row)) / var(--context-count, 1)), var(--row)); }
      .clock { display: none; } .cap { height: 36px; min-height: 36px; } .seal { width: 25px; height: 25px; }
    }
    @media (max-height: 290px) {
      .head { display: none; } .band { justify-content: flex-end; } .item { min-height: 37px; }
    }
    :host(:not([data-motion])) *, :host(:not([data-motion])) *::before, :host(:not([data-motion])) *::after { animation: none!important; transition: none!important; }
    @media (prefers-reduced-motion: reduce) { *, *::before, *::after { animation: none!important; transition: none!important; } }
  `;
  const paths = {
    back: 'm11 5-7 7 7 7M5 12h15', forward: 'm13 5 7 7-7 7m6-7H4',
    home: 'm3 10 9-7.5 9 7.5M5.5 8.5V20h4.2v-6h4.6v6h4.2V8.5',
    end: 'm6 6 12 12M18 6 6 18',
    settings: 'M4 6.5h8m4 0h4M4 17.5h3m4 0h9M14 4v5m-5 6v5',
    overview: 'M3.5 4.5h17v15h-17zM3.5 9h17m-11 0v10',
    appearance: 'M12 3a9 9 0 1 0 0 18 2 2 0 0 0 1.6-3.2c-1.2-1.4-.3-3.5 1.6-3.5H17A4 4 0 0 0 21 10c-.5-4-4.2-7-9-7Zm-5 8h.1m2.6-3.8h.1m4.5-.3h.1m3 3h.1',
    'mobile-main': 'M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2Zm3 16h4',
    'mobile-virtual': 'M3 4h18v13H3zM9 21h6m-3-4v4',
    adb: 'M4 8a12 12 0 0 1 16 0M7 11a7.8 7.8 0 0 1 10 0M9 14h6v7H9z',
    pet: 'M2.15 13.08C2.44 8.53 5.29 4.78 9.47 4.78c4.18 0 6.68 2.9 8.02 6.44.24.64.5 1.09.82 1.33-.72-2.56.1-4.55 1.86-6.02.79 1.84.82 3.38.05 4.79 1.07-.47 1.99-.42 2.88-.03-.78 2.26-2.41 3.75-4.39 3.34-1.69 2.17-3.46 3.38-6.07 4.01-3.86.93-7.88-.12-9.65-2.48-.62-.83-.91-1.94-.84-3.08Z',
    notifications: 'M5 16.5c1.5-2 1.5-3.6 1.5-6a5.5 5.5 0 0 1 11 0c0 2.4 0 4 1.5 6H5Zm4.8 3a2.5 2.5 0 0 0 4.4 0M12 3v2',
    splash: 'M3 4.5h18v15H3zM3 16l5.5-5.5 5 4.5 3-3 4.5 4.5M15.5 8.2h.1',
    gestures: 'm5 12-3 3 3 3m-3-3h10m7-9 3 3-3 3m3-3H12'
  };
  function icon(name) {
    const outline = Object.hasOwn(paths, name) ? paths[name] : paths.overview;
    const drawing = name === 'pet'
      ? `<path d="${outline}" fill="#648fe3" stroke="none"/>
         <path d="M2.24 13.48c1.75-1.08 3.79-1.39 6.08-.53 2.85 1.07 5.23 3.53 9.98 1.4-1.57 2.48-4.33 4.1-7.26 4.43-4.27.46-8.76-1.43-8.8-5.3Z" fill="#fff7ee" stroke="none"/>
         <path d="${outline}"/>
         <path d="M9.35 13.64c1.71-.02 3.62 1.74 4.08 3.45-1.99.3-3.72-.76-4.17-2.25" fill="#487ccf" stroke="#355786" stroke-width="1"/>
         <circle cx="7.8" cy="10.68" r=".95" fill="#253852" stroke="none"/>`
      : `<path d="${outline}"/>`;
    return `<svg viewBox="0 0 24 24" aria-hidden="true">${drawing}</svg>`;
  }
  // Context actions grow upwards. Home and Back keep their physical positions across pages.
  const legacyContext = {
    dsh: [{id:'settings',label:'设置'}, {id:'forward',label:'前进'}],
    settings: [{id:'forward',label:'前进'}, {id:'overview',label:'概览'}, {id:'appearance',label:'外观'}, {id:'gestures',label:'手势'}, {id:'splash',label:'启动页'}, {id:'pet',label:'桌宠'}, {id:'adb',label:'无线 ADB'}]
  };
  function actionButton({id,label,icon:iconName = id,color}, index) {
    const button = document.createElement('button');
    button.type = 'button'; button.className = 'item';
    button.dataset.action = id;
    button.dataset.label = label;
    localizeAction(button);
    button.style.setProperty('--delay',(8+7*index)+'ms');
    if (color) {
      button.style.setProperty('--action-color',color);
      button.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><circle class="swatch" cx="12" cy="12" r="8"/></svg>';
    } else button.innerHTML = icon(iconName);
    button.append(document.createElement('i'));
    return button;
  }
  function localizeAction(button) {
    button.title = t(button.dataset.label);
    button.setAttribute('aria-label',button.dataset.action === 'home' && button.dataset.label === '主页' ? t('回到 DSH 主页') : button.title);
  }
  const dock = document.createElement('nav');
  dock.className = 'dock';
  dock.innerHTML = '<div class="track"><div class="band" id="edge-actions" aria-hidden="true">' +
    '<div class="head"><button class="cap" aria-label="收起侧边导航" title="收起侧边导航">' +
    '<img class="seal" src="/assets/sugar/launcher-icon.png" alt="" draggable="false"></button><time class="clock"></time></div>' +
    '<div class="actions"><div class="context"></div><div class="fixed"></div></div></div>' +
    '<button class="grip" aria-label="展开侧边导航" aria-controls="edge-actions" aria-expanded="false">' +
    '<span class="tab" aria-hidden="true"></span></button></div>';
  const otherDock = document.createElement('div');
  otherDock.className = 'other-dock';
  otherDock.innerHTML = '<button class="other-grip"></button>';
  const otherGrip = otherDock.firstElementChild;
  root.append(style, dock, otherDock);
  document.body.appendChild(host);

  const track = dock.querySelector('.track');
  const band = dock.querySelector('.band');
  const grip = dock.querySelector('.grip');
  const movingMark = document.createElement('i');
  movingMark.className = 'moving-mark';
  movingMark.setAttribute('aria-hidden','true');
  band.append(movingMark);
  let markerSlide = null;
  const cap = dock.querySelector('.cap');
  const clock = dock.querySelector('.clock');
  const contextList = dock.querySelector('.context');
  const fixed = [{id:'home',label:'主页'}, {id:'back',label:'返回'}].map(actionButton);
  dock.querySelector('.fixed').append(...fixed);
  let items = fixed, contextActions, keepForwardVisible = false;
  const cleanups = [];
  function listen(target, event, handler, options) {
    target.addEventListener(event, handler, options);
    cleanups.push(() => target.removeEventListener(event, handler, options));
  }
  const prefs = { side: 'right', colorMode: 'adaptive', accent: '#aacac0', backdrop: '', blur: true, transparency: 16, reducedMotion: false };
  const historyState = { back: false, forward: false };
  let page = 'dsh', current = null, rightPanelOpen = false, swipeDirection, clockTimer = 0;
  let expanded = false, covered = false, drag = null, blockClickUntil = 0, lastSource = '', landing = null;
  let frame = 0, lastBounds = '', markTimer = 0, settleTimer = 0, gripLanding = null;
  let pageTouch = null;

  function rgb(hex) { return [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16)); }
  function mix(a, b, amount) { return a.map((v, i) => Math.round(v * (1 - amount) + b[i] * amount)); }
  function light(color) {
    const channels = color.map(v => v / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4);
    return .2126 * channels[0] + .7152 * channels[1] + .0722 * channels[2];
  }
  function themeColor() {
    for (const node of [document.body, document.documentElement]) {
      const match = getComputedStyle(node).backgroundColor.match(/^rgba?\(\s*(\d+),\s*(\d+),\s*(\d+)(?:,\s*([.\d]+))?\)/);
      if (match && (match[4] === undefined || +match[4] > .7)) return match.slice(1, 4).map(Number);
    }
    const dark = document.querySelector('[data-ds-dark-theme="true"]') ||
      matchMedia('(prefers-color-scheme: dark)').matches;
    return dark ? [33, 33, 37] : [249, 249, 250];
  }
  const darkInk = [8, 12, 17], lightInk = [255, 254, 251];
  const lightLevel = light(lightInk) + .05, darkLevel = light(darkInk) + .05;
  function foreground(surface) {
    const level = light(surface) + .05;
    return lightLevel / level > level / darkLevel ? lightInk : darkInk;
  }
  // Publish Sugar's popup palette for the settings shadow tree. Editing the CSSOM
  // leaves the document's style attribute alone (the backdrop sampler observes it).
  const popupSheet = document.createElement('style');
  popupSheet.textContent = ':root { --sugar-popup-radius: 12px; }';
  document.head.append(popupSheet);
  const popupStyle = popupSheet.sheet.cssRules[0].style;
  let popupPalette, popupFrameScale;
  function notificationHalo(film, opacity, darkPage, accent, selectedAlpha) {
    // A large control under the letters can be the opposite colour of the sampled
    // page. Guard just the glyphs in that case so the rest of a light menu stays clear.
    const black = [0,0,0], white = [255,255,255];
    const row = mix(darkPage ? white : black, film, opacity);
    const selected = mix(row, accent, selectedAlpha);
    const worst = darkPage ? Math.max(light(row), light(selected)) : Math.min(light(row), light(selected));
    const ratio = darkPage ? lightLevel / (worst + .05) : (worst + .05) / darkLevel;
    if (ratio >= 6) return 'none';
    const shade = mix(film, darkPage ? black : white, .58);
    const color = 'rgba(' + shade.concat(.87).join(',') + ')';
    return ['0 0 .7px', '-.85px 0 .6px', '.85px 0 .6px', '0 -.85px .6px', '0 .85px .6px']
      .map(offset => offset + ' ' + color).join(',');
  }
  function paintPopups(surface, ink, alpha, backdrop) {
    const hex = color => '#' + color.map(v => v.toString(16).padStart(2, '0')).join('');
    const opacity = Math.round(82 + 10 * alpha) / 100;
    const fraction = Math.max(0, Math.min(1, (light(backdrop) - .075) / (.62 - .075)));
    const daylight = fraction * fraction * (3 - 2 * fraction);
    const blurDp = prefs.blur ? Math.round(18 + 4 * daylight) : 0;
    const tint = hex(surface), text = hex(ink);
    const pageSurface = hex(backdrop), pageInk = hex(foreground(backdrop));
    const frameScale = window.devicePixelRatio || 1;
    if (popupPalette?.surface === tint && popupPalette.ink === text &&
        popupPalette.opacity === opacity && popupPalette.blurDp === blurDp && popupPalette.pageSurface === pageSurface && popupFrameScale === frameScale) return;
    popupFrameScale = frameScale;
    popupPalette = {surface:tint, ink:text, opacity, blurDp, pageSurface, pageInk};
    const rgba = (color, a) => 'rgba(' + color.concat(a).join(',') + ')';
    const darkPage = foreground(backdrop) === lightInk;
    const accent = darkPage ? [196,188,255] : [104,96,173];
    const filterable = blurDp && (CSS.supports('backdrop-filter', 'blur(1px)') || CSS.supports('-webkit-backdrop-filter', 'blur(1px)'));
    const selectedAlpha = darkPage ? .09 : .045;
    const film = mix(backdrop, mix([30,37,46], [252,253,255], daylight), .055);
    const lightDensity = filterable ? .56 + .12 * opacity : .72 + .10 * opacity;
    const darkDensity = filterable ? .77 + .03 * opacity : .82 + .01 * opacity;
    const density = darkDensity + (lightDensity - darkDensity) * daylight;
    popupStyle.setProperty('--sugar-popup-bg', rgba(film, density));
    popupStyle.setProperty('--sugar-popup-text-halo', notificationHalo(film, density, darkPage, accent, selectedAlpha));
    popupStyle.setProperty('--sugar-popup-ink', pageInk);
    const stroke = mix(mix(backdrop, [172,176,184], .88), [255,255,255], daylight);
    const strokePx = Math.max(1, Math.round((1.08 + .42 * daylight) * frameScale));
    // CSS floors fractional device pixels. Keep the rounded width through CSS subpixel quantization.
    popupStyle.setProperty('--sugar-popup-border-width', ((strokePx + .03125) / frameScale) + 'px');
    popupStyle.setProperty('--sugar-popup-border', rgba(stroke, .52 + .42 * daylight));
    popupStyle.setProperty('--sugar-popup-hover', rgba(accent, selectedAlpha));
    popupStyle.setProperty('--sugar-popup-active-border', rgba(accent, darkPage ? .20 : .12));
    popupStyle.setProperty('--sugar-popup-state-shadow', '0 1px 3px ' + rgba(accent, .085));
    popupStyle.setProperty('--sugar-popup-shadow', '0 10px 28px -4px ' + (darkPage ? 'rgba(18,17,34,.36)' : 'rgba(73,68,124,.22)'));
    popupStyle.setProperty('--sugar-popup-blur', blurDp + 'px');
    window.dispatchEvent(new Event('sugar-popup-palette'));
  }
  function paint() {
    const behind = prefs.backdrop ? rgb(prefs.backdrop) : themeColor();
    const base = prefs.colorMode === 'random' ? rgb(prefs.accent) : behind;
    const darker = light(base) < .22;
    const neutral = Math.max(...base) - Math.min(...base) < 22;
    const paper = mix(base, darker ? [66, 73, 92] : [176, 199, 211], neutral && prefs.colorMode === 'adaptive' ? .36 : .12);
    const alpha = 1 - prefs.transparency / 100;
    const surface = mix(behind, paper, alpha);
    const fg = foreground(surface);
    paintPopups(surface, fg, alpha, behind);
    const rgba = (c, a = 1) => 'rgba(' + c.concat(a).join(',') + ')';
    host.style.setProperty('--ink', rgba(fg));
    host.style.setProperty('--glass', rgba(paper, alpha));
    host.style.setProperty('--reveal-blur', 'blur(' + (2 * alpha) + 'px)');
    const frostRadius = 22 * Math.min(1, alpha / .4);
    host.style.setProperty('--frost-filter', 'blur(' + frostRadius + 'px) saturate(' + (1 + .12 * alpha) + ')');
    const halo = '0 0 1.5px ' + rgba(fg === lightInk ? darkInk : lightInk, .82);
    host.style.setProperty('--text-halo', prefs.transparency >= 55 ? halo : 'none');
    host.style.setProperty('--icon-halo', prefs.transparency >= 55 ? 'drop-shadow(' + halo + ')' : 'none');
    host.style.setProperty('--line', rgba(fg, .38));
    host.style.setProperty('--hairline', rgba(fg, .10 * alpha));
    const shaded = light(behind) < .18;
    const shadow = shaded ? [0, 0, 0] : [21, 28, 36];
    const strength = (shaded ? .28 : .14) * alpha;
    host.style.setProperty('--edge-shadow', 'linear-gradient(to left, ' + rgba(shadow, strength) + ', ' +
      rgba(shadow, strength * .36) + ' 45%, transparent)');
    host.style.setProperty('--handle', rgba(fg, .54));
    // The closed grip lies directly over the page, even with an opaque random rail tint.
    host.style.setProperty('--idle-handle', rgba(foreground(behind)));
    host.style.setProperty('--wash', rgba(fg, .10));
    return {ink: rgba(fg), glass: rgba(paper, alpha)};
  }
  function setReveal(side, progress) {
    if (progress <= 0 || progress >= 1 || minimalMotion() || covered || !['left','right'].includes(side)) {
      track.removeAttribute('data-reveal');
      return;
    }
    if (track.dataset.reveal !== side) track.dataset.reveal = side;
    // The band itself carries the gradient during both the drag and the landing animation.
    const envelope = Math.min(1, progress / .12, (1 - progress) / .18);
    track.style.setProperty('--reveal-opacity', String(.70 * envelope));
  }
  function stopMarker() {
    if (!markerSlide) return;
    markerSlide.cancel();
    markerSlide = null;
    band.removeAttribute('data-marker-sliding');
  }
  function slideMarker(fromY, target) {
    const end = target.getBoundingClientRect();
    const dy = fromY - end.top;
    if (!end.height || Math.abs(dy) < 1) return;
    // The fixed Home button and the scrolling context buttons have separate clipping areas.
    // One overlay inside their common band can travel across both.
    const parent = band.getBoundingClientRect();
    movingMark.style.left = (end.left - parent.left - band.clientLeft) + 'px';
    movingMark.style.top = (end.top - parent.top - band.clientTop) + 'px';
    band.setAttribute('data-marker-sliding','');
    const slide = movingMark.animate([{transform:'translateY(' + dy + 'px)'}, {transform:'translateY(0)'}],
      {id:'sugar-active-marker', duration:90, easing:'cubic-bezier(.18,.75,.25,1)'});
    markerSlide = slide;
    slide.onfinish = () => { if (markerSlide === slide) stopMarker(); };
  }
  function updateButtons(fromY) {
    const available = expanded && !covered;
    const previous = root.activeElement;
    const selected = band.querySelector('.item[aria-current="page"]');
    const next = items.find(item => item.dataset.action === current) || null;
    if (selected !== next || !available || prefs.reducedMotion) {
      if (fromY === undefined && selected && next && available && !minimalMotion() && !drag?.swiping) {
        fromY = (markerSlide ? movingMark : selected.lastElementChild).getBoundingClientRect().top;
      }
      stopMarker();
    }
    cap.tabIndex = available ? 0 : -1;
    for (const button of items) {
      const name = button.dataset.action;
      button.hidden = name === 'forward' && !keepForwardVisible && !historyState.forward;
      button.disabled = name in historyState && !canNavigate(name);
      button.tabIndex = available && !button.hidden && !button.disabled ? 0 : -1;
      if (name === current && !button.hidden) button.setAttribute('aria-current', 'page');
      else button.removeAttribute('aria-current');
    }
    host.style.setProperty('--context-count', String(Math.max(1, items.filter(button => !button.hidden).length - fixed.length)));
    if (selected !== next && next?.parentNode === contextList && available && !next.hidden &&
        contextList.scrollHeight > contextList.clientHeight) {
      const viewport=contextList.getBoundingClientRect(), row=next.getBoundingClientRect();
      if (row.bottom > viewport.bottom) contextList.scrollTop += row.bottom-viewport.bottom;
      else if (row.top < viewport.top) contextList.scrollTop += row.top-viewport.top;
    }
    if (fromY !== undefined && next && !next.hidden) slideMarker(fromY, next.lastElementChild);
    band.setAttribute('aria-hidden', String(!available));
    if (available && previous?.classList.contains('item') && (previous.hidden || previous.disabled)) {
      const next = items.find(item => !item.hidden && item.dataset.action === current) || cap;
      next.focus({ preventScroll: true });
    }
  }
  function canNavigate(name) {
    return (page !== 'dsh' && name === 'back') || historyState[name];
  }
  function setPage(next, active = null, actions, options = {}) {
    if (typeof next !== 'string' || !next) return;
    const list = actions ?? legacyContext[next];
    if (!Array.isArray(list)) return;
    keepForwardVisible = options.keepForwardVisible === true;
    const homeLabel = options.homeLabel || '主页';
    if (fixed[0].dataset.label !== homeLabel) {
      fixed[0].dataset.label = homeLabel;
      localizeAction(fixed[0]);
    }
    if (!actions && next === 'settings' && !['appearance','gestures','splash','pet','adb'].includes(active)) active='overview';
    let fromY, lostFocus = false;
    if (list !== contextActions) {
      const same = contextActions && list.length === contextActions.length && list.every((item,i) => {
        const old=contextActions[i];
        return item.id===old.id && item.label===old.label && item.icon===old.icon && item.color===old.color;
      });
      if (!same) {
        const selected = band.querySelector('.item[aria-current=page]');
        if (selected && expanded && !covered && !minimalMotion() && !drag?.swiping)
          fromY = (markerSlide ? movingMark : selected.lastElementChild).getBoundingClientRect().top;
        lostFocus = contextList.contains(root.activeElement);
        stopMarker();
        const buttons=list.filter(item=>item.id!=='home' && item.id!=='back').map(actionButton);
        contextList.replaceChildren(...buttons);
        items = [...buttons,...fixed];
      }
      contextActions = list;
    }
    page = next; current = active;
    host.dataset.page = page;
    updateButtons(fromY);
    if (lostFocus && expanded && !covered) (items.find(b=>b.dataset.action===current && !b.hidden && !b.disabled) || cap)
      .focus({preventScroll:true});
  }
  function updateClock() {
    clearTimeout(clockTimer);
    if (document.hidden) return;
    const now = new Date();
    const time = [now.getHours(), now.getMinutes()].map(n => String(n).padStart(2, '0')).join(':');
    clock.textContent = time;
    clock.dateTime = time;
    clock.setAttribute('aria-label', t('本机时间') + ' ' + time);
    if (expanded && !covered) clockTimer = setTimeout(updateClock, 60000 - now.getSeconds() * 1000 - now.getMilliseconds());
  }
  function bounds(button) {
    const r = button.getBoundingClientRect();
    return { left: r.left, top: r.top, right: r.right, bottom: r.bottom };
  }
  const getBounds = () => bounds(grip);
  // Native task controls share this WebView. Only the rail pixels actually on screen
  // cover the controls, and only the rail's live hit targets receive their touches.
  let trackTask = false, taskFrame = 0, lastTaskRail = '';
  const taskObserver = new MutationObserver(queueTaskRail);
  function queueTaskRail() {
    if (trackTask && !taskFrame) taskFrame = requestAnimationFrame(readTaskRail);
  }
  function readTaskRail() {
    taskFrame = 0;
    const width = window.visualViewport?.width || innerWidth;
    const visible = [], touch = [];
    if (trackTask && !host.hasAttribute('data-covered')) {
      function measure(element, round, painted) {
        const box = element.getBoundingClientRect();
        if (box.width <= 0 || box.height <= 0 || box.left >= width || box.right <= 0 || box.top >= innerHeight || box.bottom <= 0) return;
        const css = getComputedStyle(element);
        if (css.visibility !== 'visible') return;
        const radius = round ? Math.min(box.width, box.height) / 2 : 0;
        const rect = [box.left, box.top, box.right, box.bottom, radius].map(n => Math.round(n * 4) / 4);
        if (painted) visible.push(rect);
        if (element !== grip.firstElementChild && css.pointerEvents !== 'none') touch.push(rect);
      }
      measure(band, false, true);
      measure(grip.firstElementChild, true, true);
      measure(grip, false, false);
    }
    const detail = {width, visible, touch};
    const key = JSON.stringify(detail);
    if (key !== lastTaskRail) {
      lastTaskRail = key;
      window.SugarTaskMirror?.setRail(visible);
      window.dispatchEvent(new CustomEvent('sugar-task-rail', {detail}));
    }
    const moving = track.getAnimations
      ? [track, grip].some(el => el.getAnimations().some(a => a.pending || a.playState === 'running'))
      : host.matches('[data-moving],[data-landing]');
    if (trackTask && moving) queueTaskRail();
  }
  function setTaskLayerTracking(active) {
    if (trackTask === active) return;
    trackTask = active;
    taskObserver.disconnect();
    cancelAnimationFrame(taskFrame);
    if (active) {
      taskObserver.observe(host, {attributes:true, attributeFilter:['data-expanded','data-drag','data-landing','data-moving','data-side','data-covered','style']});
      for (const el of [track, grip]) taskObserver.observe(el, {attributes:true, attributeFilter:['style']});
    }
    readTaskRail();
  }
  function getPopupDock() {
    // The stationary dock already includes the cutout inset. Its track may still
    // be animating when the user opens a dialog with the next tap.
    return {dockSide: expanded && !covered ? prefs.side : null,
      dockWidth: expanded && !covered ? dock.getBoundingClientRect().width : 0};
  }
  function getGestureBounds() {
    // Android's back-gesture exclusion stays at the physical edge when the grip moves.
    const other = bounds(otherGrip);
    if (other.top === other.bottom) return [other, other];
    const reach = Math.min(32, innerWidth / 2);
    const left = other.left < innerWidth / 2 ? innerWidth - reach : 0;
    return [{left, right:left + reach, top:other.top, bottom:other.bottom}, other];
  }
  function layout() {
    frame = 0;
    queueTaskRail();
    const pair = getGestureBounds();
    const dockBounds = getPopupDock();
    const key = [...pair.flatMap(item => Object.values(item)), dockBounds.dockSide, dockBounds.dockWidth].join(',');
    if (key === lastBounds) return;
    lastBounds = key;
    window.dispatchEvent(new CustomEvent('sugar-edge-bounds', { detail: pair[0] }));
    window.dispatchEvent(new CustomEvent('sugar-edge-gesture-bounds', { detail: pair }));
  }
  function queueLayout() { if (!frame) frame = requestAnimationFrame(layout); }
  const minimalMotion = () => prefs.reducedMotion || matchMedia('(prefers-reduced-motion: reduce)').matches;
  function settle() {
    clearTimeout(settleTimer);
    if (minimalMotion()) host.removeAttribute('data-moving');
    else {
      host.setAttribute('data-moving', '');
      // Release the track's compositor hint after docking.
      settleTimer = setTimeout(() => { if (!drag?.swiping && !landing && !gripLanding) host.removeAttribute('data-moving'); }, unfoldMs + 5);
    }
  }
  function cancelDrag(restore = false) {
    if (!drag) return;
    if (restore && drag.swiping) { releaseDrag(prefs.side, expanded, false); return; }
    const gesture = drag;
    const { owner, id, swiping, paintFrame } = gesture;
    if (paintFrame) cancelAnimationFrame(paintFrame);
    drag = null;
    if (gesture.scrubbed) scrub('cancel', gesture);
    host.removeAttribute('data-drag');
    track.style.removeProperty('transform');
    track.style.removeProperty('width');
    grip.style.removeProperty('--grip-fold');
    grip.style.removeProperty('--grip-cross');
    host.dataset.side = prefs.side;
    if (owner?.hasPointerCapture(id)) owner.releasePointerCapture(id);
    if (!owner) detachPageTouch();
    if (swiping) settle();
  }
  function setExpanded(next) {
    cancelDrag();
    const changed = expanded !== Boolean(next);
    expanded = Boolean(next);
    if (changed) settle();
    host.toggleAttribute('data-expanded', expanded);
    grip.setAttribute('aria-expanded', String(expanded));
    grip.setAttribute('aria-label', t(expanded ? '收起侧边导航' : '展开侧边导航'));
    if (!expanded && band.contains(root.activeElement)) grip.focus({ preventScroll: true });
    updateButtons();
    if (changed) updateClock();
    queueLayout();
    if (changed) window.dispatchEvent(new CustomEvent('sugar-edge-expanded', { detail: expanded }));
  }
  const validHex = value => typeof value === 'string' && /^#[0-9a-f]{6}$/i.test(value);
  function configure(value = {}) {
    if (['left','right'].includes(value.side) && value.side !== prefs.side) {
      cancelDrag();
      prefs.side = value.side;
    }
    if (['adaptive','random'].includes(value.colorMode)) prefs.colorMode = value.colorMode;
    if (validHex(value.accent)) prefs.accent = value.accent;
    if (validHex(value.backdrop) || value.backdrop === '') prefs.backdrop = value.backdrop;
    if (typeof value.blur === 'boolean') prefs.blur = value.blur;
    if (Number.isFinite(value.transparency)) prefs.transparency = Math.max(0, Math.min(100, value.transparency));
    if (typeof value.reducedMotion === 'boolean') prefs.reducedMotion = value.reducedMotion;
    if (!drag?.swiping) host.dataset.side = prefs.side;
    otherGrip.setAttribute('aria-label', t(prefs.side === 'left' ? '从右侧展开导航' : '从左侧展开导航'));
    host.toggleAttribute('data-blur', prefs.blur && prefs.transparency < 100);
    host.toggleAttribute('data-motion', !prefs.reducedMotion);
    if (minimalMotion()) { stopMarker(); stopLanding(); }
    const colors = paint();
    queueLayout();
    return colors;
  }
  function setNavigation(value = {}) {
    if (typeof value.canBack === 'boolean') historyState.back = value.canBack;
    if (typeof value.canForward === 'boolean') historyState.forward = value.canForward;
    if (typeof value.rightPanelOpen === 'boolean') rightPanelOpen = value.rightPanelOpen;
    if ('swipeDirection' in value) swipeDirection = ['back','forward'].includes(value.swipeDirection) ? value.swipeDirection : null;
    updateButtons();
  }
  function setCovered(next) {
    covered = Boolean(next);
    cancelDrag();
    grip.tabIndex = covered ? -1 : 0;
    otherGrip.tabIndex = covered ? -1 : 0;
    host.toggleAttribute('data-covered', covered);
    host.setAttribute('aria-hidden', String(covered));
    updateButtons();
    updateClock();
    queueLayout();
  }
  function dispatch(name, source = 'button') {
    if (covered || name in historyState && !canNavigate(name)) return;
    const chosen = items.find(item => item.dataset.action === name);
    if (source === 'button' && chosen.hidden) return;
    clearTimeout(markTimer);
    for (const item of items) item.toggleAttribute('data-pulse', item === chosen && !chosen.hidden);
    markTimer = setTimeout(() => chosen.removeAttribute('data-pulse'), 200);
    lastSource = source;
    window.dispatchEvent(new CustomEvent('sugar-edge-action', { detail: name }));
  }
  const opposite = side => side === 'left' ? 'right' : 'left';
  function stopLanding() {
    if ((landing || gripLanding) && !drag?.swiping) host.removeAttribute('data-moving');
    landing?.cancel(); gripLanding?.cancel();
    landing = gripLanding = null;
    host.removeAttribute('data-landing');
  }
  function dockAt(side, open = true) {
    const changed = prefs.side !== side;
    configure({side});
    setExpanded(open);
    if (changed) window.dispatchEvent(new CustomEvent('sugar-edge-dock', {detail:side}));
  }
  function releaseDrag(side, open, commit, velocity = 0) {
    const gesture = drag, mark = grip.firstElementChild;
    const before = track.getBoundingClientRect(), beforeMark = mark.getBoundingClientRect().left;
    const end = gesture.edges[side] + (open ? 0 : (side === 'left' ? -1 : 1) * gesture.widths[side]);
    const remaining = Math.min(1, Math.abs(end - before.left) / gesture.distance);
    const release = window.SugarPageMotion?.releaseTiming?.(remaining, velocity) || {
      startTime:document.timeline.currentTime, duration:Math.min(140,Math.max(32,remaining*160)),
      easing:'cubic-bezier(.333333,.666667,.666667,1)'
    };
    if (commit && gesture.canScrub) {
      if (!gesture.scrubbed) scrub('move',gesture);
      scrub('commit',gesture,release);
    } else if (gesture.scrubbed) scrub('cancel',gesture,release);
    stopLanding();
    // Suppress the open/close CSS transition while changing the physical dock. A single
    // compositor timeline takes ownership of both positions up to their actual resting geometry.
    host.setAttribute('data-landing','');
    cancelDrag();
    dockAt(side,open);
    if (minimalMotion() || !release.duration) { stopLanding(); return; }
    const after = track.getBoundingClientRect();
    const dx = before.left - after.left, dw = before.width - after.width;
    const markDx = beforeMark - mark.getBoundingClientRect().left - dx - (side === 'left' ? dw : 0);
    function translate(node, distance, resize = false) {
      if (Math.abs(distance)<.25 && !resize) return null;
      const base = getComputedStyle(node).transform;
      const from = {transform:'translateX('+distance+'px)' + (base==='none' ? '' : ' '+base)};
      const to = {transform:base};
      if (resize) { from.width=before.width+'px'; to.width=after.width+'px'; }
      const flight=node.animate([from,to],{duration:release.duration,easing:release.easing});
      flight.id='sugar-rail-land';flight.startTime=release.startTime;
      return flight;
    }
    landing = translate(track,dx,Math.abs(dw)>.25);
    gripLanding = translate(grip,markDx);
    const last = landing || gripLanding;
    if (!last) { stopLanding(); return; }
    last.onfinish = () => { if (last === (landing || gripLanding)) stopLanding(); };
  }
  function revealFromSystemEdge(side) {
    if (covered || !['left', 'right'].includes(side)) return false;
    cancelDrag();
    detachPageTouch();
    dockAt(side);
    return true;
  }
  function activate(button, keyboard) {
    if (covered || button.disabled) return;
    if (button === grip) setExpanded(!expanded);
    else if (button === cap) setExpanded(false);
    else if (button === otherGrip) dockAt(opposite(prefs.side));
    else { dispatch(button.dataset.action); return; }
    if (expanded && keyboard) cap.focus({ preventScroll: true });
  }
  listen(root,'click',event => {
    const button=event.target.closest('button');
    if (button) activate(button,event.detail===0);
  });

  function startDrag(side, x, y, id, owner, at = performance.now()) {
    const passive = side !== prefs.side;
    // Staging can close the inspector before release. Keep the original history action.
    const direction = swipeDirection === undefined
      ? (side === 'left' || (page === 'dsh' && rightPanelOpen) ? 'back' : 'forward') : swipeDirection;
    drag = { id, owner, startX: x, startY: y, inward: 0, swiping: false, side, direction,
      startedOpen: expanded && !passive, passive, paintFrame: 0, scrubbed: false,
      fromHidden: !expanded, historyReady: expanded, downAt: at, revealOnly: false,
      allowFlick: expanded && owner && !passive, flickSamples:[{at,inward:0}], peakInward: 0,
      canScrub: Boolean(canNavigate(direction)) };
  }
  listen(root, 'pointerdown', e => {
    if (!e.isPrimary || e.button !== 0 || covered || drag) return;
    blockClickUntil = 0;
    const owner = e.target.closest('button') || band;
    startDrag(owner === otherGrip ? opposite(prefs.side) : prefs.side, e.clientX, e.clientY, e.pointerId, owner, e.timeStamp);
    owner.setPointerCapture(e.pointerId);
  });
  function scrub(phase, gesture, release) {
    const {direction, side} = gesture;
    const painted = (gesture.drawn || 0) * (side === 'left' ? 1 : -1);
    const progress = Math.max(0,Math.min(1,painted / gesture.distance));
    gesture.scrubbed = phase === 'move';
    window.dispatchEvent(new CustomEvent('sugar-edge-scrub', { detail: {phase, direction, side, progress, release} }));
  }
  function maybeScrub(gesture) {
    // The horizontal intent was already established before painting. Keep this
    // scrub alive at zero so reversing direction under the finger cannot start a second route change.
    const travel = gesture.drawn * (gesture.side === 'left' ? 1 : -1);
    if (gesture.historyReady && gesture.canScrub && (travel > 0 || gesture.scrubbed)) scrub('move', gesture);
  }
  function sampleFlick(gesture, inward, at) {
    const points = gesture.flickSamples;
    if (!points || !Number.isFinite(at) || inward === points.at(-1)?.inward) return;
    gesture.peakInward = Math.max(gesture.peakInward, inward);
    while (points.length && points[0].at < at - 90) points.shift();
    points.push({at, inward});
  }
  function flicked(gesture, event) {
    const points = gesture.flickSamples;
    if (!gesture.allowFlick || gesture.inward < 36 ||
        gesture.inward < 1.4 * Math.abs(event.clientY - gesture.startY)) return false;
    const at = event.timeStamp;
    const reversal = gesture.peakInward - gesture.inward;
    if (reversal > 3) {
      // WebView may extrapolate the last pointermove past the actual finger-up position.
      // Accept one small correction at lift-off, while keeping a deliberate turn-back cancellable.
      const peak = points.at(-2);
      if (reversal > 14 || peak?.inward !== gesture.peakInward || at - peak.at > 26) return false;
    }
    if (at - points.at(-1).at > 70) return false;
    const first = points.find(point => at - point.at <= 90);
    if (!first) return false;
    const distance = gesture.inward - first.inward;
    return distance >= 22 && distance >= .85 * Math.max(16, at - first.at);
  }
  function gripTravel(gesture, distance, width) {
    const span = gesture.widths.left - 18.5; // two 5px insets and the 8.5px mark
    const approach = Math.min(gesture.distance, Math.max(gesture.distance*.45, span*1.8));
    const t = Math.max(0,1 - (gesture.distance - distance)/approach);
    return t*t*(3-2*t)*(gesture.side === 'left' ? 18.5-width : span);
  }
  function move(e, lifting = false) {
    if (!drag || drag.id !== e.pointerId) return;
    const sign = drag.side === 'right' ? -1 : 1;
    const inward = (e.clientX - drag.startX) * sign;
    const vertical = Math.abs(e.clientY - drag.startY);
    sampleFlick(drag, inward, e.timeStamp);
    if (!drag.swiping) {
      if (vertical > Math.abs(inward) && vertical > 10) {
        blockClickUntil = performance.now() + 400;
        cancelDrag(); return;
      }
      if (Math.abs(inward) < 7 || Math.abs(inward) < vertical * 1.25) return;
      // Read both possible anchors once, before styling the panel. Move events only update its translation.
      const anchor = dock.getBoundingClientRect();
      const other = otherDock.getBoundingClientRect();
      drag.width = drag.passive ? other.width : anchor.width;
      drag.viewportWidth = innerWidth;
      drag.edges = { left: prefs.side === 'left' ? anchor.left : other.left,
        right: prefs.side === 'right' ? anchor.left : other.left };
      drag.widths = {left:prefs.side === 'left' ? anchor.width : other.width,
        right:prefs.side === 'right' ? anchor.width : other.width};
      drag.distance = Math.max(1,drag.edges.right-drag.edges.left);
      drag.base = drag.startedOpen ? 0 : -drag.width;
      if (!drag.passive && host.matches('[data-moving],[data-landing]') && !minimalMotion()) {
        const rect = track.getBoundingClientRect();
        drag.base = (rect.left - anchor.left)*sign;
        if (drag.base > 0) {
          const gripAnchor = drag.side === 'left' ? rect.width - 13.5 : 5;
          drag.gripBias = grip.firstElementChild.getBoundingClientRect().left - rect.left - gripAnchor - gripTravel(drag,drag.base,rect.width);
        }
      }
      stopLanding();
      drag.swiping = true;
      if (!expanded) updateClock();
      host.dataset.side = drag.side;
      host.setAttribute('data-drag', '');
      clearTimeout(settleTimer);
      host.setAttribute('data-moving', '');
    }
    const width = drag.width;
    // A quick pull on a hidden edge reveals the rail. Staying inside its opening width
    // briefly, then continuing inward with the same finger, engages page history.
    if (!drag.historyReady && !drag.revealOnly && drag.canScrub && inward > drag.inward && !lifting) {
      if (e.timeStamp - drag.downAt >= 160) drag.historyReady = true;
      else if (inward >= width + 8) drag.revealOnly = true;
    }
    drag.inward = inward;
    // Unfold the original edge first, then keep the band under the finger across the screen.
    const travel = drag.historyReady ? drag.distance : 0;
    const offset = Math.max(-width, Math.min(travel, drag.base + inward));
    drag.offset = offset * sign;
    if (!drag.paintFrame) drag.paintFrame = requestAnimationFrame(() => {
      drag.paintFrame = 0;
      if (drag.drawn === drag.offset) return;
      drag.drawn = drag.offset;
      track.style.transform = 'translate3d(' + drag.drawn + 'px,0,0)';
      const fold = Math.max(0, Math.min(1, -drag.drawn * sign / width));
      if (fold !== (drag.fold ?? 0)) grip.style.setProperty('--grip-fold', String(fold));
      drag.fold = fold;
      // The inner grip changes ends of the bar on a side switch. Move it across the bar
      // proportionally now, so it never needs to travel backwards after the finger lifts.
      const travel = drag.drawn*sign;
      let paintedWidth = width;
      if (drag.widths.left !== drag.widths.right) {
        // Opposite cutout insets can give the two docks different widths. Carry that
        // difference through the drag so the far edge also meets the destination continuously.
        const p = Math.max(0,travel/drag.distance);
        paintedWidth = width + (drag.widths[opposite(drag.side)]-width)*p;
        track.style.width = paintedWidth+'px';
      }
      let shift = gripTravel(drag,travel,paintedWidth);
      if (drag.gripBias) {
        // A re-grab can interrupt the previous flight anywhere. Keep its visible grip
        // offset at contact and continuously converge to the chosen endpoint.
        const remaining = drag.inward >= 0 ? drag.distance - drag.base : drag.base;
        shift += drag.gripBias*Math.max(0,1-Math.abs(drag.inward)/Math.max(1,remaining));
      }
      if (shift !== (drag.gripShift || 0)) {
        grip.style.setProperty('--grip-cross',shift+'px');
        drag.gripShift = shift;
      }
      maybeScrub(drag);
    });
  }
  listen(root, 'pointermove', e => { if (drag?.owner) move(e); }, { passive: true });
  function finish(e) {
    if (!drag || drag.id !== e.pointerId) return;
    const last = drag.flickSamples.at(-1);
    const first = drag.flickSamples.find(sample => last.at-sample.at<=65 && sample.at<last.at);
    const speed = first && e.timeStamp-last.at <= 70 ? (last.inward-first.inward)/Math.max(1,last.at-first.at) : 0;
    move(e, true);
    if (!drag) return;
    const { owner, swiping, inward, side, direction, startedOpen, passive, viewportWidth, distance } = drag;
    const crossed = side === 'left' ? e.clientX > viewportWidth / 2 : e.clientX < viewportWidth / 2;
    const edgeRelease = side === 'left' ? e.clientX < 3 : e.clientX > viewportWidth - 3;
    const switchSides = swiping && drag.historyReady && inward > 20 && (crossed || flicked(drag, e));
    if (!swiping) {
      cancelDrag();
      // Chromium may omit the compatibility click on a freshly moved compositor layer.
      if (e.pointerType === 'touch' && owner?.tagName === 'BUTTON') {
        blockClickUntil = performance.now() + 400;
        activate(owner, false);
      }
      return;
    }
    blockClickUntil = performance.now() + 400;
    if (switchSides) {
      releaseDrag(opposite(side),true,true,Math.max(0,speed/distance));
      if (direction) dispatch(direction,'swipe');
    } else if (inward >= 20 && (!startedOpen || passive)) {
      releaseDrag(side,true,false);
    } else if (startedOpen && (inward <= -20 || (edgeRelease && inward <= -7))) {
      releaseDrag(side,false,false);
    } else releaseDrag(prefs.side,expanded,false,Math.max(0,-speed/distance));
  }
  listen(root, 'pointerup', e => { if (drag?.owner) finish(e); });
  for (const event of ['pointercancel', 'lostpointercapture']) listen(root, event, e => {
    if (drag?.owner && drag.id === e.pointerId) cancelDrag(true);
  });
  // We handled this touch at pointerup. When that hides a button, WebView can
  // retarget its compatibility click to a completely different control beneath it.
  // touchend still belongs to the original shadow button, even after it moved.
  listen(root, 'touchend', e => {
    if (e.cancelable && performance.now() < blockClickUntil) e.preventDefault();
  }, {capture:true, passive:false});
  listen(root, 'click', e => {
    if (e.detail !== 0 && performance.now() < blockClickUntil) {
      e.preventDefault(); e.stopImmediatePropagation();
    }
  }, true);
  // A document tap keeps its original target. Observe only touch sequences that start near either edge,
  // and claim only an inward horizontal movement outside a scrollable or interactive gesture surface.
  function ownsPan(path) {
    return path.some(node => {
      if (!(node instanceof Element)) return false;
      if (node.matches('input, textarea, [contenteditable="true"], [role="slider"], [draggable="true"], canvas, video, audio, iframe')) return true;
      const css = getComputedStyle(node);
      return css.touchAction === 'pan-x' || (/auto|scroll|overlay/.test(css.overflowX) && node.scrollWidth > node.clientWidth + 1);
    });
  }
  function edgeAt(x) {
    return x <= 36 ? 'left' : x >= innerWidth - 36 ? 'right' : null;
  }
  // Sandboxed HTML previews have their own input stream. Android asks about the
  // actual down point before claiming a horizontal drag. Taps and vertical scrolls
  // still reach the iframe, as do all touches on content covering it.
  function isEmbeddedEdge(x, y) {
    if (page !== 'dsh' || covered || drag || !Number.isFinite(x) || !Number.isFinite(y) ||
        x < 0 || x >= innerWidth || y < 0 || y >= innerHeight || !edgeAt(x)) return false;
    const hit = document.elementFromPoint(x, y);
    return Boolean(hit?.matches('[data-sidebar-right-panel][data-sidebar-right-open="true"] [data-slot="sidebar.right.tab.document"] iframe'));
  }
  function embeddedDrag(phase, x, y, startX, startY, id, elapsed) {
    if (!Number.isSafeInteger(id)) return false;
    const pointerId = 'embedded-' + id;
    const timed = Number.isFinite(elapsed) && elapsed >= 0;
    if (phase === 'cancel') {
      if (drag?.id !== pointerId) return false;
      cancelDrag(true);
      return true;
    }
    if (!Number.isFinite(x) || !Number.isFinite(y)) return false;
    if (phase === 'start') {
      if (!isEmbeddedEdge(startX, startY)) return false;
      const side = edgeAt(startX);
      const inward = (x - startX) * (side === 'left' ? 1 : -1);
      if (inward < 5 || inward < Math.abs(y - startY) * 1.4) return false;
      detachPageTouch();
      startDrag(side, startX, startY, pointerId, null, timed ? 0 : performance.now());
    } else if (drag?.id !== pointerId || (phase !== 'move' && phase !== 'end')) return false;
    const sample = {pointerId, clientX:x, clientY:y, pointerType:'touch', timeStamp:timed ? elapsed : performance.now()};
    if (phase === 'end') finish(sample);
    else move(sample);
    return true;
  }
  function detachPageTouch() {
    if (!pageTouch) return;
    const {target, orphanTimer} = pageTouch;
    pageTouch = null;
    clearTimeout(orphanTimer);
    for (const type of ['touchmove','touchend','touchcancel']) target.removeEventListener(type, detachedDelivery, true);
    window.removeEventListener('pointercancel', pointerLost, true);
    document.removeEventListener('touchmove', pageMove, true);
    document.removeEventListener('touchend', pageEnd, true);
    document.removeEventListener('touchcancel', pageCancel, true);
  }
  function pageCancel() {
    if (drag && !drag.owner) cancelDrag(true);
    detachPageTouch();
  }
  // React may replace the touched button while staging a conversation. Touch events keep
  // their original target, and events on a detached subtree never bubble to document.
  function detachedDelivery(e) {
    if (!pageTouch || e.composedPath().includes(document)) return;
    if (e.type === 'touchmove') pageMove(e);
    else if (e.type === 'touchend') pageEnd(e);
    else pageCancel();
  }
  function pointerLost(e) {
    const seed = pageTouch;
    if (!seed || seed.target.isConnected || !drag || drag.owner || e.pointerType !== 'touch' || !e.isPrimary) return;
    // Android also sends pointercancel for compositor takeover. Prefer the original touch
    // stream if it resumes. Roll back if the platform has abandoned the detached target.
    clearTimeout(seed.orphanTimer);
    seed.orphanTimer = setTimeout(() => { if (pageTouch === seed) pageCancel(); }, 180);
  }
  function pageMove(e) {
    if (!pageTouch || covered || e.touches.length !== 1) { pageCancel(); return; }
    const t = e.touches[0], seed = pageTouch;
    if (t.identifier !== seed.id) return;
    clearTimeout(seed.orphanTimer);
    if (!drag) {
      const dx = (t.clientX - seed.x) * (seed.side === 'left' ? 1 : -1);
      const dy = Math.abs(t.clientY - seed.y);
      if ((dy > 7 && dy >= Math.abs(dx)) || dx < -7) { detachPageTouch(); return; }
      // Some Android WebViews offer just one cancellable move, at roughly 8 CSS pixels,
      // before the compositor takes over. Claim clear horizontal intent on that first sample.
      if (dx < 5 || dx < dy * 1.4) return;
      if (ownsPan(seed.path)) { detachPageTouch(); return; }
      startDrag(seed.side, seed.x, seed.y, seed.id, null, seed.at);
    }
    if (!drag.owner) {
      // A compositor pointercancel can still be followed by an intact touchmove/end stream.
      // Its cancelable flag only controls the native default; the positions are still valid.
      if (e.cancelable) e.preventDefault();
      e.stopPropagation();
      move({pointerId: seed.id, clientX: t.clientX, clientY: t.clientY, timeStamp:e.timeStamp});
    }
  }
  function pageEnd(e) {
    const t = Array.from(e.changedTouches).find(t => t.identifier === pageTouch?.id);
    if (!t) return;
    if (drag && !drag.owner) {
      if (e.cancelable) e.preventDefault();
      e.stopPropagation();
      finish({pointerId: t.identifier, clientX: t.clientX, clientY: t.clientY, timeStamp:e.timeStamp, pointerType: 'touch'});
    }
    detachPageTouch();
  }
  listen(document, 'touchstart', e => {
    if (pageTouch) pageCancel();
    const path = e.composedPath();
    if (drag || covered || e.touches.length !== 1 || path.includes(host)) return;
    const t = e.touches[0];
    const side = edgeAt(t.clientX);
    if (!side) return;
    const target = path[0];
    pageTouch = { id: t.identifier, x: t.clientX, y: t.clientY, side, path, target, at:e.timeStamp, orphanTimer:0 };
    for (const type of ['touchmove','touchend','touchcancel']) {
      target.addEventListener(type, detachedDelivery, {capture:true, passive:type === 'touchcancel'});
    }
    window.addEventListener('pointercancel', pointerLost, {capture:true,passive:true});
    document.addEventListener('touchmove', pageMove, {capture:true, passive:false});
    document.addEventListener('touchend', pageEnd, {capture:true, passive:false});
    document.addEventListener('touchcancel', pageCancel, {capture:true, passive:true});
  }, {capture:true, passive:true});
  listen(document, 'click', e => {
    if (!e.isTrusted || page !== 'dsh' || !expanded || covered) return;
    const path = e.composedPath();
    if (path.includes(host) || path.some(node => node.matches?.('[data-sugar-open-settings],[data-sugar-picker-remove],[data-sugar-picker-phone]'))) return;
    setExpanded(false);
  }, true);
  listen(dock, 'keydown', e => {
    if (e.key === 'Escape' && expanded) {
      e.stopPropagation(); e.preventDefault(); setExpanded(false); grip.focus();
    } else if (expanded && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) {
      const choices = [cap, ...items, grip].filter(button => !button.disabled && button.offsetHeight > 0);
      const current = choices.indexOf(root.activeElement);
      if (current === -1) return;
      const step = e.key === 'ArrowDown' ? 1 : -1;
      choices[(current + step + choices.length) % choices.length].focus({ preventScroll: true });
      e.stopPropagation(); e.preventDefault();
    }
  });
  function resized() { stopMarker(); stopLanding(); cancelDrag(); queueLayout(); }
  listen(window, 'resize', resized);
  listen(document, 'visibilitychange', updateClock);
  if (window.visualViewport) listen(window.visualViewport, 'resize', resized);
  const resize = typeof ResizeObserver === 'function' ? new ResizeObserver(queueLayout) : null;
  resize?.observe(dock);
  const observeTheme = new MutationObserver(() => {
    if (prefs.colorMode === 'adaptive' && !prefs.backdrop) paint();
    queueLayout();
  });
  for (const node of [document.documentElement, document.body]) {
    observeTheme.observe(node, { attributes: true, attributeFilter: ['style', 'class', 'data-ds-dark-theme'] });
  }
  const scheme = matchMedia('(prefers-color-scheme: dark)');
  const themeChanged = () => { if (!prefs.backdrop && prefs.colorMode === 'adaptive') paint(); };
  scheme.addListener(themeChanged);
  function dispose() {
    setTaskLayerTracking(false);
    stopMarker();
    cancelDrag();
    detachPageTouch();
    landing?.cancel();
    gripLanding?.cancel();
    cancelAnimationFrame(frame); clearTimeout(markTimer); clearTimeout(clockTimer); clearTimeout(settleTimer);
    resize?.disconnect(); observeTheme.disconnect(); scheme.removeListener(themeChanged);
    for (const clean of cleanups) clean();
    popupSheet.remove();
    host.remove();
    if (window.SugarEdgeNav === api) delete window.SugarEdgeNav;
  }
  const api = { icon, getPopupPalette: () => popupPalette, configure, setNavigation, setPage, setExpanded, setReveal, revealFromSystemEdge, isExpanded: () => expanded,
    setCovered, getBounds, getGestureBounds, getPopupDock, setTaskLayerTracking, isEmbeddedEdge, embeddedDrag, getLastActionSource: () => lastSource,
    focusHandle: () => grip.focus({ preventScroll: true }), dispose };
  function localizeRail() {
    host.lang = window.SugarI18n?.language ?? 'zh';
    dock.setAttribute('aria-label', t('DSH Sugar 应用导航'));
    cap.title = t('收起侧边导航');
    cap.setAttribute('aria-label', cap.title);
    grip.setAttribute('aria-label', t(expanded ? '收起侧边导航' : '展开侧边导航'));
    otherGrip.setAttribute('aria-label', t(prefs.side === 'left' ? '从右侧展开导航' : '从左侧展开导航'));
    items.forEach(localizeAction);
    updateClock();
  }
  listen(window, 'sugar-language-changed', localizeRail);
  window.SugarEdgeNav = api;
  configure();
  setPage('dsh');
  localizeRail();
})();
