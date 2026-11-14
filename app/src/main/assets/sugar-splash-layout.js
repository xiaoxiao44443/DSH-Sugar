// The editor and the launch screen render at device CSS-pixel dimensions with the same safe area.
(() => {
  'use strict';
  function foreground(hex) {
    const rgb = [1,3,5].map(i => parseInt(hex.slice(i,i+2),16)/255)
      .map(c => c <= .04045 ? c/12.92 : ((c+.055)/1.055)**2.4);
    return .2126*rgb[0]+.7152*rgb[1]+.0722*rgb[2] > .179 ? '#000000' : '#ffffff';
  }
  const labels = {
    zh: {progress:'加载进度', stage:'正在启动 DSH WebUI', image:'启动图片，可用方向键调整位置', text:'加载文字和进度，可用方向键调整位置'},
    en: {progress:'Loading progress', stage:'Starting DSH WebUI', image:'Startup image. Use the arrow keys to move it.', text:'Loading text and progress. Use the arrow keys to move them.'},
    ja: {progress:'読み込み状況', stage:'DSH WebUI を起動中', image:'起動画像。矢印キーで移動できます。', text:'読み込みテキストと進捗バー。矢印キーで移動できます。'}
  };
  const clamp = n => Math.min(100, Math.max(0, n));
  const markup = `<style>
    :host { display:block }
    * { box-sizing:border-box }
    .scene { width:100%; height:100%; padding:var(--safe,0); background:var(--paper);
      color:var(--ink); font:500 16px/1.45 system-ui,sans-serif; text-align:center }
    main { position:relative; width:100%; height:100%; overflow:hidden }
    img,.copy { position:absolute; user-select:none }
    img { display:block; -webkit-user-drag:none }
    .copy { width:max-content; max-width:92%; max-height:100%; overflow:hidden;
      display:flex; flex-direction:column; align-items:center }
    .caption,.stage { white-space:pre-wrap; overflow-wrap:anywhere; max-width:100% }
    .caption:empty,.stage:empty,[hidden] { display:none !important }
    .status { display:flex; flex-direction:column; align-items:center; max-width:100% }
    .stage { font-weight:400; font-size:13px; opacity:.82 }
    .caption:not(:empty) + .status .stage:not(:empty) { margin-top:5px }
    [part=progress] { position:relative; width:100px; max-width:100%; height:4px; margin:12px 0 3px;
      overflow:hidden; border-radius:2px }
    [part=progress]::before { content:''; position:absolute; inset:0; background:currentColor; opacity:.2 }
    .fill { display:block; width:100%; height:100%; border-radius:inherit; background:currentColor;
      transform-origin:left center; transform:scaleX(var(--fraction,1)) }
    [part=progress]:not([aria-valuenow]) .fill { animation:sugar-wait 900ms ease-in-out infinite alternate }
    @keyframes sugar-wait {
      from { transform:translateX(0) scaleX(.34) } to { transform:translateX(66%) scaleX(.34) }
    }
    [data-reduced] [part=progress]:not([aria-valuenow]) .fill { animation:none; transform:translateX(33%) scaleX(.34) }
    @media (prefers-reduced-motion:reduce) {
      [part=progress]:not([aria-valuenow]) .fill { animation:none; transform:translateX(33%) scaleX(.34) }
    }
  </style><div class="scene"><main part="area">
    <img part="image" alt="" draggable="false">
    <div class="copy" part="text"><div class="caption" part="caption"></div><div class="status" part="status">
      <div class="stage" part="stage" aria-live="polite"></div>
      <div part="progress" role="progressbar" aria-label="加载进度" aria-valuemin="0" aria-valuemax="100"><span class="fill"></span></div>
    </div></div>
  </main></div>`;

  function create(host) {
    const root = host.attachShadow({mode:'open'});
    root.innerHTML = markup;
    const find = selector => root.querySelector(selector);
    const scene = find('.scene'), area = find('main'), art = find('img'), text = find('.copy');
    const caption = find('.caption'), status = find('.status'), stage = find('.stage'), progress = find('[part=progress]');
    let settings, forcedSide;
    const side = () => forcedSide || (host.clientWidth > host.clientHeight ? 'landscape' : 'portrait');
    const key = part => side() + (part === 'image' ? 'ImagePosition' : 'TextPosition');
    function place(node, position, x, y, width, height) {
      const dx = Math.max(0, area.clientWidth-width), dy = Math.max(0, area.clientHeight-height);
      node.style.left = (position ? dx * clamp(position.x)/100 : x) + 'px';
      node.style.top = (position ? dy * clamp(position.y)/100 : y) + 'px';
    }
    function layout() {
      if (!settings || !area.clientWidth || !area.clientHeight) return;
      const orientation = side();
      const width = area.clientWidth, height = area.clientHeight;
      const textWidth = text.offsetWidth, textHeight = text.offsetHeight;
      const gap = textHeight ? (caption.textContent ? 14 : 5) : 0;
      const ratio = settings[orientation+'Percent']/100;
      const boxWidth = width * ratio, boxHeight = Math.max(0,Math.min(height * ratio, height-textHeight-gap));
      const fit = settings[orientation+'Fit'];
      let imageWidth = boxWidth, imageHeight = boxHeight;
      if (fit === 'contain' && art.naturalWidth && art.naturalHeight) {
        const scale = Math.min(boxWidth / art.naturalWidth, boxHeight / art.naturalHeight);
        imageWidth = art.naturalWidth*scale;
        imageHeight = art.naturalHeight*scale;
      }
      art.style.width = imageWidth+'px';
      art.style.height = imageHeight+'px';
      art.style.objectFit = fit;
      const top = Math.max(0,(height-imageHeight-gap-textHeight)/2);
      place(art,settings[key('image')],(width-imageWidth)/2,top,imageWidth,imageHeight);
      place(text,settings[key('text')],(width-textWidth)/2,top+imageHeight+gap,textWidth,textHeight);
    }
    function configure(next, orientation) {
      settings = {...next};
      forcedSide = orientation;
      const language = Object.hasOwn(labels, next.language) ? next.language : 'zh';
      const copy = labels[language];
      host.lang = language;
      progress.setAttribute('aria-label', copy.progress);
      if (orientation) {
        art.setAttribute('aria-label', copy.image);
        text.setAttribute('aria-label', copy.text);
        stage.textContent = copy.stage;
      }
      scene.style.setProperty('--paper',next.backgroundColor);
      scene.style.setProperty('--ink',foreground(next.backgroundColor));
      scene.toggleAttribute('data-reduced', Boolean(next.reducedMotion));
      caption.textContent = next.loadingText;
      status.hidden = !next.showProgress;
      layout();
    }
    function setImage(url) {
      art.hidden = false;
      if (art.getAttribute('src') !== url) art.src = url;
    }
    function setStage(message, percent = null) {
      stage.textContent = message;
      if (Number.isFinite(percent)) {
        const value = clamp(percent);
        progress.setAttribute('aria-valuenow', String(value));
        progress.style.setProperty('--fraction', String(value / 100));
      } else {
        progress.removeAttribute('aria-valuenow');
        progress.style.removeProperty('--fraction');
      }
      layout();
    }
    function position(part) {
      const saved = settings?.[key(part)];
      if (saved) return {...saved};
      const el = part === 'image' ? art : text;
      const a = area.getBoundingClientRect(), b = el.getBoundingClientRect();
      return {x:a.width-b.width > .01 ? clamp((b.left-a.left)/(a.width-b.width)*100) : 50,
        y:a.height-b.height > .01 ? clamp((b.top-a.top)/(a.height-b.height)*100) : 50};
    }
    function move(part, x, y) {
      if (!settings || !Number.isFinite(x) || !Number.isFinite(y)) return;
      settings[key(part)] = {x:clamp(x),y:clamp(y)};
      layout();
    }
    const observer = new ResizeObserver(layout);
    observer.observe(host); observer.observe(area); observer.observe(text);
    art.addEventListener('load',layout);
    function dispose() { observer.disconnect(); art.removeEventListener('load',layout); }
    return {area,art,text,configure,setImage,setStage,position,move,dispose};
  }
  window.SugarSplashLayout = {create};
})();
