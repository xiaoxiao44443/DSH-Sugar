window.__ModuleLoader__.load({
  id: 'dsh-sugar-workspace',
  factory: () => {
    const inject = ['workspaces', 'uiWorkspace', 'remoteAccess', 'contextMenus', 'locale'];
    function apply(ctx) {
      const {workspaces, uiWorkspace, remoteAccess} = ctx;
      const rows = () => workspaces.list.getSnapshot().items;

      // Register only a host-generated mount identity. A project process never receives the tree URI.
      async function registerLocal(slug, title, existing) {
        if (!/^local-[0-9a-f]{64}$/.test(slug)) throw Error('无效的手机工作区。');
        const path = '/workspace/' + slug;
        let row = rows().find(item => item.path === path);
        if (existing && (!row || row.workspaceId !== existing)) throw Error('原工作区已移除。');
        if (!row) {
          try { await uiWorkspace.createDirectory('/workspace', slug); }
          catch (error) {
            try { await uiWorkspace.listDirectory(path); } catch { throw error; }
          }
          row = await workspaces.create({path});
        }
        if (row.path !== path) throw Error('工作区登记不匹配。');
        if (row.title === slug && remoteAccess.source('workspace',row.workspaceId,'workspace/rename').getSnapshot()) {
          const name = String(title || slug).trim() || slug;
          const used = new Set(rows().filter(other => other.workspaceId !== row.workspaceId).map(other => other.title));
          let label = name;
          for (let index = 2; used.has(label); index++) label = name + ' (' + index + ')';
          await workspaces.rename(row.workspaceId,label);
        }
        return row.workspaceId;
      }
      ctx.effect(() => remoteAccess.whenAccess('workspaceFiles/read', () => {
        window.__dshSugarRegisterLocal = registerLocal;
        const open = id => uiWorkspace.openWorkspace(id);
        window.__dshSugarOpenLocal = open;
        return () => {
          if (window.__dshSugarRegisterLocal === registerLocal) delete window.__dshSugarRegisterLocal;
          if (window.__dshSugarOpenLocal === open) delete window.__dshSugarOpenLocal;
        };
      }), 'Sugar: select a local phone directory');
      ctx.effect(() => installSugarDirectoryPicker(ctx), 'Sugar: Android directory picker');
      ctx.effect(() => installWorkspaceUiExtras(ctx), 'Sugar: Android workspace controls');
    }
    return {inject, apply};
  }
});
