/** Only the Workspace picker has an Add workspace action pinned below its project list. */
function installWorkspaceUiExtras(ctx) {
  const workspace = ctx.locale.bind('workspace');
  const plugins = ctx.locale.bind('pluginManager');
  const menus = '[role="menu"]:not([data-context-menu])';
  const surfaces = menus + ', [role="dialog"]';
  const phoneAction = '[data-sugar-pick-phone]';

  function addPhoneAction(menu) {
    const regions = menu.querySelectorAll(':scope > [role="presentation"]');
    const footer = regions.length === 2 ? regions[1] : null;
    const stock = footer?.querySelectorAll('button[role="menuitem"]:not([data-sugar-pick-phone])');
    const add = stock?.length === 1 ? stock[0] : null;
    const existing = menu.querySelector(phoneAction);
    if (typeof window.__dshSugarChooseLocal !== 'function' || !add || add.textContent.trim() !== workspace('menu.addWorkspace')) {
      existing?.remove();
      return;
    }
    const button = existing || document.createElement('button');
    if (!existing) {
      button.type = 'button';
      button.setAttribute('role', 'menuitem');
      button.dataset.sugarPickPhone = '';
      button.className = add.className;
      button.addEventListener('click', event => {
        if (!event.isTrusted || add.disabled || !menu.isConnected) return;
        if (window.__dshSugarChooseLocal?.() === true) {
          event.stopPropagation();
          menu.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true, cancelable: true}));
        }
      });
      footer.append(button);
    }
    const title = ctx.locale.bind('sugarMobileUi')('pickPhone');
    if (button.textContent !== title) button.textContent = title;
    if (button.disabled !== add.disabled) button.disabled = add.disabled;
  }

  function refresh(surface) {
    if (surface.matches(menus)) {
      addPhoneAction(surface);
    } else if (surface.getAttribute('aria-label') === plugins('installTitle')) {
      const description = surface.querySelector(':scope > div > p');
      description?.toggleAttribute('data-sugar-install-note', description.textContent === plugins('installDescription'));
    }
  }

  // Portalled menus mount directly in the document body. Ignore conversation streaming updates.
  const observer = new MutationObserver(records => {
    const changed = new Set();
    for (const record of records) {
      const parent = record.target.nodeType === Node.ELEMENT_NODE ? record.target : record.target.parentElement;
      const surface = parent?.closest(surfaces);
      if (surface) changed.add(surface);
      if (record.target !== document.body) continue;
      for (const node of record.addedNodes) {
        if (!(node instanceof Element)) continue;
        if (node.matches(surfaces)) changed.add(node);
        else node.querySelectorAll(surfaces).forEach(item => changed.add(item));
      }
    }
    for (const surface of changed) if (surface.isConnected) refresh(surface);
  });

  // These DSH-owned semantic attributes are present only on the global Plugins panel.
  const style = document.createElement('style');
  style.textContent = `
    :lang(zh) [data-plugin-scope="global"][data-plugin-group="bundles"] > div:first-child > h3::after { content: "（所有工作区共享）" }
    :lang(en) [data-plugin-scope="global"][data-plugin-group="bundles"] > div:first-child > h3::after { content: " (shared across workspaces)" }
    :lang(zh) [data-plugin-header-part="intro"] > span:first-child::after { content: "。更改对所有工作区生效。" }
    :lang(en) [data-plugin-header-part="intro"] > span:first-child::after { content: ". Changes apply to all workspaces." }
    :lang(zh) [data-sugar-install-note]::after { content: "安装后，所有工作区都可使用此插件。" }
    :lang(en) [data-sugar-install-note]::after { content: " Installed plugins are available in every workspace." }
  `;
  const unregister = ctx.locale.register('sugarMobileUi', {
    zh: {pickPhone: '选择手机文件夹'}, en: {pickPhone: 'Choose phone folder'}
  });
  document.head.append(style);
  observer.observe(document.body, {childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['disabled']});
  document.querySelectorAll(surfaces).forEach(refresh);
  return () => {
    observer.disconnect();
    document.querySelectorAll(phoneAction).forEach(button => button.remove());
    document.querySelectorAll('[data-sugar-install-note]').forEach(node => node.removeAttribute('data-sugar-install-note'));
    style.remove();
    unregister();
  };
}
