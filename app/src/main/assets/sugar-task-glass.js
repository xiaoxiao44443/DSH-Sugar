// The interactive controls live in Android. Only the pixels behind them are filtered here.
(() => {
  if (window.SugarTaskGlass) return;
  let glass, frost;
  function clear() {
    glass?.remove(); glass = frost = null;
    window.SugarEdgeNav?.setTaskLayerTracking(false);
  }
  function place(x, y, width, height, radius, blur, dark) {
    if (!glass) {
      glass = document.createElement('div');
      glass.setAttribute('aria-hidden', 'true');
      glass.inert = true;
      Object.assign(glass.style, {
        all: 'initial', position: 'fixed', left: '0', top: '0', pointerEvents: 'none', zIndex: '2147482998',
      });
      // Only this inner surface is clipped. The outer shadow can spread past the corners.
      frost = document.createElement('span');
      Object.assign(frost.style, {
        all: 'initial', position: 'absolute', inset: '0', borderRadius: 'inherit', overflow: 'hidden',
        background: 'rgba(255,255,255,.012)', pointerEvents: 'none',
      });
      glass.appendChild(frost);
      document.body.appendChild(glass);
      window.SugarEdgeNav?.setTaskLayerTracking(true);
    }
    const unit = window.visualViewport?.width || document.documentElement.clientWidth;
    const px = ratio => (unit * ratio) + 'px';
    const filter = blur ? 'blur(' + px(blur) + ')' : 'none';
    const badge = width * unit < 90;
    const shadow = dark ? '18,17,34,.36' : '73,68,124,.22';
    Object.assign(glass.style, {
      transform: 'translate3d(' + px(x) + ',' + px(y) + ',0)',
      width: px(width), height: px(height), borderRadius: px(radius),
      boxShadow: (badge ? '0 6px 18px -1px' : '0 13px 33px -4px') + ' rgba(' + shadow + ')',
    });
    Object.assign(frost.style, {
      clipPath: 'inset(0 round ' + px(radius) + ')', backdropFilter: filter, webkitBackdropFilter: filter,
    });
  }
  window.SugarTaskGlass = {place, clear};
  window.addEventListener('pagehide', clear);
})();
