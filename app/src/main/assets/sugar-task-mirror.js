/* Only Sugar's own transparent native task controls are mirrored into the compositor.
   The rail and its live backdrop filter can then paint over them. Input stays native. */
(() => {
  if (window.SugarTaskMirror) return;
  const ns = 'http://www.w3.org/2000/svg';
  let latest = 0, active = 0, revision = -1;
  let box = null, rails = [];
  let host, clip, canvas;
  let previousLayout = '', hasImage = false;

  function reset() {
    if (canvas) { canvas.width = canvas.height = 0; }
    host?.remove();
    host = clip = canvas = null;
    previousLayout = '';
    box = null;
    hasImage = false;
    revision = -1;
  }
  function create() {
    host = document.createElement('div');
    host.id = 'sugar-task-mirror';
    host.inert = true;
    host.setAttribute('aria-hidden','true');
    Object.assign(host.style, {all:'initial', position:'fixed', left:'0', top:'0', pointerEvents:'none',
      visibility:'hidden', overflow:'hidden', zIndex:'2147482999'});
    const svg = document.createElementNS(ns,'svg');
    svg.setAttribute('width','0');
    svg.setAttribute('height','0');
    svg.style.position = 'absolute';
    const defs = document.createElementNS(ns,'defs');
    clip = document.createElementNS(ns,'clipPath');
    clip.id = 'sugar-task-mirror-clip-' + active;
    clip.setAttribute('clipPathUnits','userSpaceOnUse');
    defs.appendChild(clip);
    svg.appendChild(defs);
    canvas = document.createElement('canvas');
    Object.assign(canvas.style,{all:'initial',display:'block',pointerEvents:'none',width:'100%',height:'100%',
      clipPath:'url(#' + clip.id + ')'});
    host.append(svg,canvas);
    // Siblings of the rail share its stacking context even when the page body creates one.
    (document.getElementById('sugar-edge-navigation')?.parentElement || document.body).appendChild(host);
  }
  function layout() {
    if (!active || !box || !hasImage || !host) return;
    const unit = window.visualViewport?.width || document.documentElement.clientWidth;
    const [x,y,w,h] = box.map(v => v*unit);
    const overlaps = rails.filter(([left,top,right,bottom]) =>
      left < x+w && right > x && top < y+h && bottom > y);
    if (!overlaps.length) {
      host.style.visibility = 'hidden';
      previousLayout = '';
      return;
    }
    const key = [x,y,w,h,...overlaps.flat()].join(',');
    if (key === previousLayout) return;
    previousLayout = key;
    host.style.transform = 'translate3d(' + x + 'px,' + y + 'px,0)';
    host.style.width = w + 'px';
    host.style.height = h + 'px';
    // A single clip path takes the union. Its grip and track may overlap while the rail unfolds.
    while (clip.children.length < overlaps.length) clip.appendChild(document.createElementNS(ns,'rect'));
    while (clip.children.length > overlaps.length) clip.lastElementChild.remove();
    overlaps.forEach(([left,top,right,bottom,radius], i) => {
      const shape = clip.children[i];
      shape.setAttribute('x', left-x);
      shape.setAttribute('y', top-y);
      shape.setAttribute('width', right-left);
      shape.setAttribute('height', bottom-top);
      shape.setAttribute('rx', radius);
      shape.setAttribute('ry', radius);
    });
    host.style.visibility = 'visible';
  }
  function place(id,x,y,width,height) {
    if (!Number.isSafeInteger(id) || ![x,y,width,height].every(Number.isFinite) || width<=0 || height<=0) return;
    if (id !== active) {
      if (id <= latest) return;
      reset();
      latest = active = id;
    }
    box = [x,y,width,height];
    layout();
  }
  function setRail(rects) {
    rails = Array.isArray(rects) ? rects.slice(0,4).filter(rect => Array.isArray(rect) &&
      (rect.length===4 || rect.length===5) && rect.every(Number.isFinite) &&
      rect[2]>rect[0] && rect[3]>rect[1]).map(([l,t,r,b,round=0]) => [l,t,r,b,Math.max(0,round)]) : [];
    layout();
  }
  async function image(id,version,png) {
    if (id!==active || !Number.isSafeInteger(version) || version < revision || typeof png!=='string') return;
    revision = version;
    let bitmap;
    try {
      const binary = atob(png);
      const bytes = new Uint8Array(binary.length);
      for (let i=0; i<bytes.length; i++) bytes[i]=binary.charCodeAt(i);
      bitmap = await createImageBitmap(new Blob([bytes],{type:'image/png'}));
      if (id!==active || version!==revision) return;
      if (!host) create();
      canvas.width = bitmap.width;
      canvas.height = bitmap.height;
      canvas.getContext('2d').drawImage(bitmap,0,0);
      hasImage = true;
      layout();
    } catch (error) {
      console.warn('Sugar could not decode its local task card', error);
    } finally { bitmap?.close(); }
  }
  function clear(id) {
    if (id!==active) return;
    reset();
    active = 0;
  }
  window.SugarTaskMirror = {place,image,clear,setRail};
  window.addEventListener('pagehide', () => {reset(); active=0; rails=[];});
})();
