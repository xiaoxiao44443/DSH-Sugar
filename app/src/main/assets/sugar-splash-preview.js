(() => {
  'use strict';
  const controls = `<div class="preview-layout">
    <div class="row"><div class="choices" role="group" aria-label="预览方向">
      <button type="button" data-preview-orientation="portrait">竖屏</button>
      <button type="button" data-preview-orientation="landscape">横屏</button>
    </div><button type="button" class="preview-reset">恢复自动布局</button></div>
    <div data-preview-stage><div class="preview-glass"></div>
      <small class="preview-error" role="status" hidden>启动页预览暂时不可用</small></div>
    <small class="preview-help">拖动图片或加载文字调整位置，松手自动保存。进度条随文字移动。</small>
  </div>`;
  function mount(pane, previewUrl, commit) {
    const stage = pane.querySelector('[data-preview-stage]');
    const glass = stage.querySelector('.preview-glass'), error = stage.querySelector('.preview-error');
    const reset = pane.querySelector('.preview-reset');
    const buttons = [...pane.querySelectorAll('[data-preview-orientation]')];
    const shell = pane.getRootNode().host;
    const safeByOrientation = new Map();
    let orientation = matchMedia('(orientation:landscape)').matches ? 'landscape' : 'portrait';
    let frame, ready = false, updatedImage = 0, state, channelOrigin, timeout;
    function geometry() {
      const wide = innerWidth > innerHeight, actual = wide ? 'landscape' : 'portrait';
      const long = Math.max(innerWidth,innerHeight), short = Math.min(innerWidth,innerHeight);
      const width = orientation === 'portrait' ? short : long, height = orientation === 'portrait' ? long : short;
      const vars = getComputedStyle(document.documentElement);
      const insets = ['top','right','bottom','left'].map(key => parseFloat(vars.getPropertyValue('--sugar-safe-' + key)) || 0);
      safeByOrientation.set(actual,insets);
      // A rotation on this device supplies the exact insets for the second orientation.
      // Until then retain its status/gesture bands and reserve the short-edge cutout.
      const safe = safeByOrientation.get(orientation) || (wide ? [Math.max(insets[0],insets[1],insets[3]),0,insets[2],0] :
        [insets[0],0,insets[2],insets[0]]);
      return {width,height,safe};
    }
    function redraw() {
      const imageKey = orientation + 'ImagePosition', textKey = orientation + 'TextPosition';
      for (const button of buttons) button.setAttribute('aria-pressed',String(button.dataset.previewOrientation === orientation));
      reset.disabled = !state || (!state[imageKey] && !state[textKey]);
      if (!frame || !state || !stage.clientWidth || !stage.clientHeight) return;
      const language = window.SugarI18n?.language ?? 'zh';
      frame.title = window.SugarI18n?.text('可拖动的启动页预览') ?? '可拖动的启动页预览';
      const {width,height,safe} = geometry();
      const scale = Math.min(stage.clientWidth / width, stage.clientHeight / height);
      Object.assign(glass.style,{width:width*scale+'px',height:height*scale+'px',backgroundColor:state.backgroundColor});
      Object.assign(frame.style,{width:width+'px',height:height+'px',transform:'scale('+scale+')'});
      if (ready) frame.contentWindow.postMessage({type:'sugar-preview-configure',orientation,state:{...state,language,reducedMotion:shell?.hasAttribute('data-reduced')},safe,
        imageRevision:updatedImage},channelOrigin);
    }
    function receive(event) {
      if (!frame || event.source !== frame.contentWindow || event.origin !== channelOrigin) return;
      const m = event.data;
      if (m?.type === 'sugar-preview-ready') {
        clearTimeout(timeout); ready = true; error.hidden = true; redraw();
      } else if (m?.type === 'sugar-preview-position' && m.orientation === orientation) {
        if (!['image','text'].includes(m.part) || ![m.x,m.y].every(n => Number.isFinite(n) && n >= 0 && n <= 100)) return;
        const key=orientation+(m.part === 'image' ? 'ImagePosition' : 'TextPosition');
        commit({[key]:{x:m.x,y:m.y}});
      }
    }
    function removeFrame() {
      clearTimeout(timeout); ready = false; frame?.remove(); frame = null;
    }
    function updateVisibility() {
      const onScreen = shell?.isConnected && !shell.hidden &&
        (!pane.hidden || pane.matches('[data-deck-layer],[data-slide-under]'));
      if (!onScreen) { removeFrame(); return; }
      if (!previewUrl) { error.hidden = false; return; }
      if (!frame) {
        const url = new URL(previewUrl);
        channelOrigin = url.origin;
        frame = document.createElement('iframe');
        frame.setAttribute('sandbox','allow-scripts allow-same-origin');
        frame.referrerPolicy = 'no-referrer';
        frame.src = url.href;
        glass.append(frame);
        error.hidden = true;
        timeout = setTimeout(() => { if (!ready) error.hidden = false; }, 6000);
      }
      redraw();
    }
    for (const button of buttons) button.addEventListener('click',() => {
      orientation=button.dataset.previewOrientation;
      redraw();
    });
    reset.addEventListener('click',() => commit({[orientation+'ImagePosition']:null,[orientation+'TextPosition']:null}));
    const resize = new ResizeObserver(redraw);
    resize.observe(stage);
    const visibility = new MutationObserver(updateVisibility);
    visibility.observe(pane,{attributes:true,attributeFilter:['hidden','data-deck-layer','data-slide-under']});
    if (shell) visibility.observe(shell,{attributes:true,attributeFilter:['hidden','data-reduced']});
    const safeChange = new MutationObserver(() => { if (frame) redraw(); });
    safeChange.observe(document.documentElement,{attributes:true,attributeFilter:['style']});
    window.addEventListener('message',receive);
    window.addEventListener('resize',redraw);
    updateVisibility();
    return {
      configure(next) { state = next; redraw(); },
      imageChanged() { updatedImage++; },
      dispose() {
        resize.disconnect(); visibility.disconnect(); safeChange.disconnect(); removeFrame();
        window.removeEventListener('message',receive); window.removeEventListener('resize',redraw);
      }
    };
  }
  window.SugarSplashPreview = {controls,mount};
})();
