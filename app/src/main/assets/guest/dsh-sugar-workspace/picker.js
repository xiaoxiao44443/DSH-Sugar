// Installed by the mobile WebUI plugin. Use the upstream picker's accessible controls;
// an unrecognised dialog keeps its normal behaviour. Android validates the path again.
function installSugarDirectoryPicker(ctx) {
  const t = ctx.locale.bind('directory-browser');
  const copy = {
    phone: {zh:'选择手机文件夹', en:'Choose phone folder', ja:'端末のフォルダを選択', es:'Elegir carpeta del teléfono',
      fr:'Choisir un dossier du téléphone', de:'Handyordner auswählen', ru:'Выбрать папку телефона', pt:'Escolher pasta do telefone'},
    remove: {zh:'删除空文件夹…', en:'Delete empty folder…', ja:'空のフォルダを削除…', es:'Eliminar carpeta vacía…',
      fr:'Supprimer le dossier vide…', de:'Leeren Ordner löschen…', ru:'Удалить пустую папку…', pt:'Excluir pasta vazia…'}
  };
  const css = document.createElement('style');
  css.textContent = [
    '[data-sugar-picker-header]{display:grid!important;grid-template-columns:minmax(0,1fr) auto;align-items:center}',
    '[data-sugar-picker-header]>h2{grid-area:1/1}',
    '[data-sugar-picker-header]>[data-sugar-picker-phone]{grid-area:1/2;max-width:44vw;white-space:normal}',
    '[data-sugar-picker-header]>:not(h2):not([data-sugar-picker-phone]){grid-column:1/-1}',
    '[data-sugar-picker-footer]>button[aria-pressed=false]::after{content:"";flex:0 0 14px;height:14px}',
    '[data-sugar-picker-error]{color:var(--dsw-alias-label-primary);margin:0;padding:0 24px;font-size:13px}',
    '@media(max-width:680px){',
    '[data-sugar-picker-footer]{display:flex;flex-wrap:wrap;gap:4px 8px!important;padding-inline:clamp(10px,3vw,20px)!important}',
    '[data-sugar-picker-footer]>button:not([aria-pressed]):nth-last-child(n+3){max-inline-size:calc(50% - 4px);min-width:0;white-space:normal}',
    '[data-sugar-picker-footer]>span{order:1;flex:0 0 100%!important;height:0}',
    '[data-sugar-picker-footer]>button[aria-pressed]{order:2;flex:1 1 0;min-width:0;white-space:normal;text-align:start}',
    '[data-sugar-picker-footer]>button:nth-last-child(-n+2){order:3;flex:0 0 auto;min-width:64px}}'
  ].join('\n');
  document.head.append(css);
  const dialogs = new Map();
  let granted = false;
  let stopped = false;
  let visible = false;

  function reportVisibility() {
    const shown = dialogs.size !== 0;
    if (visible === shown) return;
    visible = shown;
    window.dispatchEvent(new Event('sugar-picker-visibility'));
  }

  function onBack(event) {
    if (!visible) return;
    const top = [...document.querySelectorAll('[role="dialog"]')].filter(card => card.getClientRects().length).at(-1);
    const state = dialogs.get(top);
    if (state) {
      event.preventDefault();
      const editor = state.header.querySelector('input');
      if (editor) editor.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true, cancelable:true}));
      else if (!state.cancel.disabled) state.cancel.click();
    } else if (top?.querySelector('input')?.getAttribute('aria-label') === t('browser.folderName') &&
               top.querySelector('h2,h3')?.textContent === t('browser.newFolder')) {
      event.preventDefault();
      const cancel = [...top.querySelectorAll('button')].find(button => button.textContent === t('browser.cancel'));
      if (cancel && !cancel.disabled) cancel.click();
    }
  }

  function inspect(card) {
    const heading = [...card.querySelectorAll('h2')].find(node => node.closest('[role="dialog"]') === card);
    if (heading?.textContent !== t('browser.title')) return null;
    const toggle = [...card.querySelectorAll('button[aria-pressed]')].find(node =>
      node.closest('[role="dialog"]') === card && node.textContent === t('browser.showHidden'));
    if (!toggle) return null;
    const footer = toggle.parentElement;
    const original = [...footer.children].filter(node => !node.hasAttribute('data-sugar-picker-remove'));
    const [create, hidden, space, cancel, open] = original;
    if (original.length !== 5 || hidden !== toggle || space.tagName !== 'SPAN' ||
        ![create,cancel,open].every(node => node.tagName === 'BUTTON') ||
        create.textContent !== t('browser.newFolder') || cancel.textContent !== t('browser.cancel') ||
        open.textContent !== t('browser.open')) return null;
    return {card, header:heading.parentElement, footer, create, toggle, cancel, open};
  }

  function selection(view) {
    const trail = view.header.querySelector('[role="navigation"]');
    if (!trail || view.open.disabled || view.create.disabled ||
        view.card.querySelector('[role="alert"]:not([data-sugar-picker-error])')) return null;
    const crumbs = [...trail.querySelectorAll('button')];
    if (!crumbs.length) return null;
    const [first, ...tail] = crumbs.map(button => button.textContent);
    const base = first === '/' ? '' : first === t('browser.home') ? '/home/sugar' : null;
    if (base === null || tail.some(part => !part || part.includes('/'))) return null;
    const path = [base, ...tail].join('/') || '/';
    return {path, parent:crumbs.at(-2)};
  }

  const below = (path, base) => path === base || path.startsWith(base + '/');
  function removable(path) {
    if (!granted || typeof window.__dshSugarRemoveEmptyFolder !== 'function' ||
        !(path.startsWith('/workspace/') || path.startsWith('/home/')) || path === '/home/sugar' ||
        new TextEncoder().encode(path).length > 4095 ||
        path.slice(1).split('/').some(part => !part.trim() || part.startsWith('.') || /[\\\x00-\x1f\x7f-\x9f]/u.test(part)) ||
        path === '/workspace/deepseek-harness' || below(path, '/workspace/deepseek-harness/default-workspace') ||
        /^\/workspace\/(?:phone|local)-[0-9a-f]{64}(?:\/|$)/.test(path)) return false;
    const list = ctx.workspaces.list.getSnapshot();
    return list.phase === 'ready' && list.state !== 'loading' && !list.items.some(row =>
      typeof row.path !== 'string' || below(path, row.path) || below(row.path, path));
  }

  function button(template, attribute) {
    const result = document.createElement('button');
    result.type = 'button';
    result.className = template.className;
    result.setAttribute(attribute, '');
    return result;
  }

  function cleanup(state) {
    state.events.abort();
    state.phone.remove();
    state.remove.remove();
    state.error.remove();
    state.header.removeAttribute('data-sugar-picker-header');
    state.footer.removeAttribute('data-sugar-picker-footer');
    dialogs.delete(state.card);
  }

  function mount(view) {
    const state = {...view, events:new AbortController(), pending:false,
      phone:button(view.create, 'data-sugar-picker-phone'), remove:button(view.create, 'data-sugar-picker-remove'),
      error:document.createElement('p')};
    dialogs.set(view.card, state);
    view.header.setAttribute('data-sugar-picker-header', '');
    view.footer.setAttribute('data-sugar-picker-footer', '');
    state.error.setAttribute('data-sugar-picker-error', '');
    state.error.setAttribute('role', 'alert');
    const options = {signal:state.events.signal};
    state.phone.addEventListener('click', () => {
      if (state.pending || state.cancel.disabled || typeof window.__dshSugarChooseLocal !== 'function') return;
      if (window.__dshSugarChooseLocal() === true) state.cancel.click();
    }, options);
    state.remove.addEventListener('click', async () => {
      const current = inspect(state.card);
      const chosen = current && selection(current);
      if (state.pending || !chosen?.parent || !removable(chosen.path)) return;
      state.pending = true;
      state.remove.disabled = true;
      state.error.remove();
      try {
        const removed = await window.__dshSugarRemoveEmptyFolder(chosen.path);
        const latest = dialogs.get(state.card) === state && selection(state);
        if (removed && latest?.path === chosen.path) latest.parent?.click();
      } catch (error) {
        if (dialogs.get(state.card) === state && selection(state)?.path === chosen.path) {
          state.error.textContent = error instanceof Error ? error.message : String(error);
          state.footer.before(state.error);
        }
      } finally {
        state.pending = false;
        if (dialogs.get(state.card) === state) refresh(state);
      }
    }, options);
    return state;
  }

  function setText(node, value) { if (node.textContent !== value) node.textContent = value; }

  function refresh(state) {
    const current = selection(state);
    if (current?.path !== state.previousPath) { state.error.remove(); state.previousPath = current?.path; }
    const canPick = typeof window.__dshSugarChooseLocal === 'function';
    if (canPick) {
      setText(state.phone, ctx.locale.resolveText(copy.phone));
      if (!state.phone.isConnected) state.header.append(state.phone);
      const disabled = state.pending || state.cancel.disabled;
      if (state.phone.disabled !== disabled) state.phone.disabled = disabled;
    } else state.phone.remove();
    if (current?.parent && removable(current.path)) {
      const label = ctx.locale.resolveText(copy.remove);
      setText(state.remove, label);
      state.remove.title = current.path;
      state.remove.setAttribute('aria-label', label + ' ' + current.path);
      if (state.remove.disabled !== state.pending) state.remove.disabled = state.pending;
      if (!state.remove.isConnected) state.toggle.before(state.remove);
    } else state.remove.remove();
  }

  function scan() {
    if (stopped) return;
    for (const state of dialogs.values()) if (!state.card.isConnected) cleanup(state);
    for (const card of document.querySelectorAll('[role="dialog"]')) {
      const view = inspect(card);
      let state = dialogs.get(card);
      if (state && (!view || state.footer !== view.footer || state.create !== view.create)) { cleanup(state); state = null; }
      if (view) refresh(state || mount(view));
    }
    reportVisibility();
  }
  const observer = new MutationObserver(entries => {
    if (dialogs.size || entries.some(entry => entry.target.parentElement?.closest('[role="dialog"]') ||
        entry.target.closest?.('[role="dialog"]') || [...entry.addedNodes].some(node =>
          node.nodeType === 1 && (node.matches('[role="dialog"]') || node.querySelector('[role="dialog"]'))))) scan();
  });
  observer.observe(document.body, {subtree:true, childList:true, characterData:true,
    attributes:true, attributeFilter:['disabled','aria-pressed','aria-current','role']});
  const releaseAccess = ctx.remoteAccess.whenAccess('directoryPicker/createDirectory', () => {
    granted = true; scan();
    return () => { granted = false; scan(); };
  });
  const releaseList = ctx.workspaces.list.subscribe(scan);
  const releaseLocale = ctx.locale.subscribe(scan);
  window.addEventListener('sugar-picker-back', onBack);
  scan();
  return () => {
    stopped = true;
    observer.disconnect();
    releaseAccess(); releaseList(); releaseLocale();
    for (const state of dialogs.values()) cleanup(state);
    window.removeEventListener('sugar-picker-back', onBack);
    reportVisibility();
    css.remove();
  };
}
