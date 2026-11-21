window.__ModuleLoader__.load({
  id: 'dsh-sugar-file-actions',
  factory: require => {
    const React = require('react');
    const {IconDownloadOutlineRegular, IconRightUpOutlineRegular, IconFolderOpenOutlineRegular} = require('@deepseek-ai/dsh-client-ui-primitives');
    const inject = ['locale', 'contextMenus', 'remote', 'remote.workspaceFiles', 'remoteAccess', 'slots', 'workspaces'];

    // Keep the file in its original session. Read bounded DSH windows even when the
    // WebView transport requests much smaller chunks. Reject a file changed during export.
    function fileSource(files, target, info, signal) {
      const blockSize = 512 * 1024;
      let cached = new Uint8Array(0);
      let base = -1;
      async function read(start, end) {
        signal.throwIfAborted();
        if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) ||
            start < 0 || end > info.bytes || end < start) throw Error('Invalid file range');
        const bytes = new Uint8Array(end - start);
        for (let offset = start; offset < end;) {
          if (base < 0 || offset < base || offset >= base + cached.length) {
            const length = Math.min(blockSize, info.bytes - offset);
            const response = await files.readBytes(target.sessionId, target.path, {range:{offset, length}}, signal);
            signal.throwIfAborted();
            const part = response.ok && response.value;
            if (!part || part.offset !== offset || part.version !== info.version || part.bytes !== info.bytes ||
                part.absolutePath !== info.absolutePath || part.data?.length !== length ||
                part.eof !== (offset + length === info.bytes)) throw Error('File changed or unavailable');
            base = offset;
            cached = Uint8Array.from(part.data);
          }
          const index = offset - base;
          const count = Math.min(end - offset, cached.length - index);
          bytes.set(cached.subarray(index, index + count), offset - start);
          offset += count;
        }
        return bytes.buffer;
      }
      return {size:info.bytes, type:target.mediaType || '', slice:(start, end) => ({arrayBuffer:() => read(start, end)})};
    }

    // DSH exposes menu contributions by ID, but its Web renderer exposes only role
    // and label for the original rows. Replace a stock row only beside our own action.
    function watchStockRows(ctx) {
      const menus = ctx.contextMenus;
      const opening = ctx.locale.bind('open-in-app');
      const saving = ctx.locale.bind('fileActions');
      const labels = {open:() => opening('menu.defaultApp'), reveal:() => opening('menu.reveal'), download:() => saving('menu.download')};
      const changed = new Map();
      let observer;
      function restore() {
        observer?.disconnect();
        for (const [button, wasDisabled] of changed) {
          button.removeAttribute('data-sugar-stock-action');
          button.disabled = wasDisabled;
        }
        changed.clear();
      }
      function hide() {
        const menu = document.querySelector('[data-context-menu][role="menu"]');
        if (!menu) return;
        const buttons = [...menu.querySelectorAll('button[role="menuitem"]')];
        for (const [mode, label] of Object.entries(labels)) {
          const mobile = menu.querySelector('[data-sugar-file-action="' + mode + '"]')?.closest('button');
          if (!mobile) continue;
          const original = label();
          const stock = buttons.filter(button => button !== mobile && button.textContent.trim() === original);
          if (stock.length !== 1 || changed.has(stock[0])) continue;
          changed.set(stock[0], stock[0].disabled);
          stock[0].setAttribute('data-sugar-stock-action', '');
          stock[0].disabled = true;
        }
      }
      function update() {
        restore();
        const kind = menus.active.getSnapshot()?.kind;
        if (!['file', 'image', 'directory', 'workspace'].includes(kind)) return;
        observer = new MutationObserver(hide);
        observer.observe(document.body, {childList:true, subtree:true, characterData:true});
        hide();
      }
      const style = document.createElement('style');
      style.textContent = '[data-context-menu] button[data-sugar-stock-action]{display:none}';
      document.head.append(style);
      const unsubscribe = menus.active.subscribe(update);
      update();
      return () => { unsubscribe(); restore(); style.remove(); };
    }

    function apply(ctx) {
      const t = ctx.locale.bind('sugarFileActions');
      ctx.effect(() => ctx.locale.register('sugarFileActions', {
        zh:{open:'使用默认应用打开', download:'下载', reveal:'在文件夹显示', failed:'无法导出此文件', revealFailed:'无法打开文件夹', noViewer:'手机上没有可打开此类文件的应用。', saved:'已保存到手机的下载目录'},
        en:{open:'Open with default app', download:'Download', reveal:'Show in folder', failed:'Could not export file', revealFailed:'Could not open folder', noViewer:'No installed app can open this file type.', saved:'Saved to Downloads'}
      }), 'Sugar: file action labels');
      ctx.slots.inject('contextMenu.icon', function* () {
        for (const [mode, Icon] of Object.entries({open:IconRightUpOutlineRegular, download:IconDownloadOutlineRegular, reveal:IconFolderOpenOutlineRegular})) {
          yield ctx.slots.register({name:'contextMenu.icon', key:'sugar-file.' + mode},
            () => React.createElement('span', {'data-sugar-file-action':mode, style:{display:'inline-flex'}}, React.createElement(Icon)));
        }
      });
      ctx.effect(() => ctx.remoteAccess.whenAccess('workspaceFiles/read', () => {
        const access = new AbortController();
        const files = ctx.remote.workspaceFiles;
        async function resolve(target, signal) {
          if (typeof window.__dshSugarExportBlob !== 'function' || !target.sessionId || !target.path) return [];
          const found = await files.stat(target.sessionId, target.path, signal);
          signal.throwIfAborted();
          if (!found.ok || !Number.isSafeInteger(found.value.bytes) || found.value.bytes < 0) return [];
          const name = target.name || target.path.split('/').pop();
          const actions = ['open', 'download'].map(mode => ({
            id:mode, icon:{key:'sugar-file.' + mode}, label:t(mode), failureLabel:t('failed'),
            run() {
              return window.__dshSugarExportBlob(mode, async () => {
                access.signal.throwIfAborted();
                const current = await files.stat(target.sessionId, target.path, access.signal);
                if (!current.ok || current.value.version !== found.value.version ||
                    current.value.absolutePath !== found.value.absolutePath || current.value.bytes !== found.value.bytes) {
                  throw Error('File changed or unavailable');
                }
                return fileSource(files, target, current.value, access.signal);
              }, name, target.mediaType || '').then(() => {
                if (mode === 'download' && !access.signal.aborted) ctx.contextMenus.notify(t('saved'), true);
              }).catch(error => {
                if (access.signal.aborted) return;
                if (error?.message === '手机上没有可打开此类文件的应用。') {
                  ctx.contextMenus.notify(t('noViewer'), false);
                } else throw error;
              });
            }
          }));
          const project = () => {
            const rows = ctx.workspaces.list.getSnapshot().items.filter(row => row.sessionIds.includes(target.sessionId));
            return rows.length === 1 ? rows[0].workspaceId : undefined;
          };
          const prefix = '/workspace/';
          const absolute = found.value.absolutePath;
          const id = project();
          if (typeof window.__dshSugarRevealFolder === 'function' && id && absolute.startsWith(prefix)) {
            actions.push({
              id:'reveal', icon:{key:'sugar-file.reveal'}, label:t('reveal'), failureLabel:t('revealFailed'),
              run() {
                if (id !== project() || !window.__dshSugarRevealFolder(id, absolute.slice(prefix.length))) {
                  throw Error('Workspace unavailable');
                }
              }
            });
          }
          return actions;
        }
        // Directories have their own menu target and cannot use stat(), which
        // only accepts regular files. list() verifies the session's access and
        // returns a workspace-relative path, including '' for the root.
        function workspaceOf(sessionId) {
          const rows = ctx.workspaces.list.getSnapshot().items.filter(row => row.sessionIds.includes(sessionId));
          return rows.length === 1 ? rows[0] : undefined;
        }
        async function resolveDirectory(target, signal) {
          if (typeof window.__dshSugarRevealFolder !== 'function' || !target.sessionId || typeof target.path !== 'string') return [];
          const owner = workspaceOf(target.sessionId);
          if (!owner) return [];
          // The Files page header still names the registered Host root; rows
          // already carry project-relative paths. Never strip an arbitrary prefix.
          const path = target.path === owner.path || target.path === '' ? '.' : target.path;
          const found = await files.list(target.sessionId, path, signal);
          signal.throwIfAborted();
          if (!found.ok || typeof found.value.path !== 'string' || owner.workspaceId !== workspaceOf(target.sessionId)?.workspaceId) return [];
          return [{id:'reveal', icon:{key:'sugar-file.reveal'}, label:t('reveal'), failureLabel:t('revealFailed'),
            run() {
              if (access.signal.aborted || owner.workspaceId !== workspaceOf(target.sessionId)?.workspaceId ||
                  !window.__dshSugarRevealFolder(owner.workspaceId, found.value.path, 'directory')) {
                throw Error('Workspace unavailable');
              }
            }
          }];
        }
        const remove = ['file', 'image'].map(target => ctx.contextMenus.register({
          id:'sugar-file-actions.' + target, target, order:190, resolve
        }));
        remove.push(ctx.contextMenus.register({
          id:'sugar-file-actions.directory', target:'directory', order:90, resolve:resolveDirectory
        }));
        remove.push(ctx.contextMenus.register({
          id:'sugar-file-actions.workspace', target:'workspace', order:190,
          resolve({workspaceId}) {
            if (typeof window.__dshSugarRevealFolder !== 'function') return [];
            return [{id:'reveal', icon:{key:'sugar-file.reveal'}, label:t('reveal'), failureLabel:t('revealFailed'),
              run() { if (!window.__dshSugarRevealFolder(workspaceId,'')) throw Error('Workspace unavailable'); }
            }];
          }
        }));
        const stopWatching = watchStockRows(ctx);
        return () => { access.abort(); remove.forEach(dispose => dispose()); stopWatching(); };
      }), 'Sugar: Android file actions');
    }
    return {inject, apply};
  }
});
