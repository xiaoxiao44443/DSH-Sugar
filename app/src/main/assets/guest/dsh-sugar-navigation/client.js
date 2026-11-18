window.__ModuleLoader__.load({
  id: 'dsh-sugar-navigation',
  factory: () => {
    const inject = ['sessions', 'workspaces', 'uiWorkspace', 'layout', 'sidebarRight', 'locale'];

    function attach(ctx) {
      if (window !== window.top || location.protocol !== 'http:' || location.hostname !== '127.0.0.1') return;
      const {sessions, workspaces, uiWorkspace, layout, sidebarRight} = ctx;
      const past = [], future = [];
      let current, waitingHome, busy = false, disposed = false, frame = 0, lastState = '';

      // DSH's start page is a workspace's reusable blank session, including its unsent draft.
      // Keep its workspace identity when the first prompt turns that session into a conversation.
      const home = workspace => ({home: true, workspace, panel: null, right: false});
      const same = (a, b) => a && b && a.home === b.home && a.panel === b.panel && a.right === b.right && a.tab === b.tab &&
        (a.home ? a.workspace === b.workspace : a.id === b.id);
      const base = page => ({...page, panel: null, right: false, tab: undefined});
      const drawerOpen = () => Boolean(document.querySelector('[data-sidebar-drawer]:not([data-sidebar-collapsed])'));
      const entries = () => workspaces.list.getSnapshot().items;

      function read() {
        const list = sessions.list.getSnapshot();
        if (list.phase !== 'ready' || workspaces.list.getSnapshot().phase !== 'ready') return null;
        const panel = layout.panelInfo.getSnapshot().activePanelId ?? null;
        const id = sidebarRight.mounted.getSnapshot() ??
          Object.values(list.byId).find(row => row.retainedBy.mainView > 0)?.id;
        const row = id && list.byId[id];
        const workspace = id ? entries().find(item => item.sessionIds.includes(id))?.workspaceId :
          entries()[0]?.workspaceId;
        const right = !panel && sidebarRight.isExpanded();
        const page = {id, workspace, home: !id || row?.blank === true, panel, right,
          tab: right ? sidebarRight.active()?.id : undefined};
        if (page.home && !right && !panel && workspace) {
          if (!id) {
            // All bookmarks taken during this same visible, unbound home visit share
            // its eventual session identity. The public route key is workspace-based.
            if (waitingHome?.workspace !== workspace) waitingHome = page;
            return waitingHome;
          }
          if (waitingHome?.workspace === workspace && canReopenBlank(page)) waitingHome.id = id;
        }
        waitingHome = null;
        return page;
      }

      function reachable(page) {
        if (page.tab && !sidebarRight.openTabs.getSnapshot().some(tab => tab.sessionId === page.id && tab.tabId === page.tab)) return false;
        // The unassigned home exists only until the default workspace is initialized.
        if (page.home) return page.workspace ? entries().some(item => item.workspaceId === page.workspace) : entries().length === 0;
        return !workspaces.list.getSnapshot().archivedSessionIds.includes(page.id) &&
          Boolean(sessions.list.getSnapshot().byId[page.id] || sessions.subagentAddress(page.id));
      }

      function peek(stack) {
        while (stack.length && (!reachable(stack.at(-1)) || same(stack.at(-1), current))) stack.pop();
        return stack.at(-1);
      }

      function push(stack, page) {
        if (page && !same(stack.at(-1), page)) stack.push(page);
        // Navigation is scoped to this WebView lifetime. Retain only lightweight route identities.
        if (stack.length > 100) stack.shift();
      }

      function parent(page) {
        if (page.right || page.panel) return base(page);
        return page.home ? undefined : home(page.workspace ?? entries()[0]?.workspaceId);
      }

      function backTarget(page) {
        const last = peek(past);
        if (page.right && last?.right && same(base(page), base(last))) return last;
        if (page.right || page.panel) return base(page);
        return last ?? parent(page);
      }

      function routeKey(page, drawer) {
        return page ? JSON.stringify([page.home ? 'home' : 'session',
          page.home ? page.workspace : page.id, page.panel, page.right, drawer, page.tab ?? null]) : '';
      }

      function state() {
        const drawer = drawerOpen();
        return {
          canBack: Boolean(!busy && current && (drawer || parent(current) || peek(past))),
          canForward: Boolean(!busy && current && peek(future)),
          busy, drawerOpen: drawer, rightPanelOpen: Boolean(current?.right),
          previewable: Boolean(current && !current.right && !drawer && !busy),
          isHome: Boolean(current?.home && !current.panel && !current.right && !drawer),
          routeKey: routeKey(current, drawer)
        };
      }

      function publish() {
        const detail = state();
        const key = JSON.stringify(detail);
        if (key === lastState) return;
        lastState = key;
        window.dispatchEvent(new CustomEvent('sugar-navigation-state', {detail}));
      }

      // A notification may open a cold WebView before DSH has hydrated its session list.
      let revealingConversation;
      function readyToReveal() {
        return !disposed && !busy && sessions.list.getSnapshot().phase === 'ready' && workspaces.list.getSnapshot().phase === 'ready';
      }
      function finishReveal() {
        if (revealingConversation && sidebarRight.mounted.getSnapshot() === revealingConversation) {
          revealingConversation = undefined;
          if (sidebarRight.isExpanded()) sidebarRight.toggleExpanded();
        }
      }
      function openConversation(id) {
        if (!readyToReveal() || typeof id !== 'string' || !id) return false;
        const address = sessions.subagentAddress(id);
        if (!sessions.list.getSnapshot().byId[id] && !address) return false;
        delete window.__sugarNotificationSession;
        window.SugarShell?.closeForNotification?.();
        if (drawerOpen()) layout.toggleSidebar();
        revealingConversation = id;
        uiWorkspace.openSession(address ?? id);
        finishReveal();
        return true;
      }
      function openPetVoiceSettings() {
        if (disposed || busy) return false;
        // The official voice input's own setup button uses this optional client service.
        const plugins = ctx.get('pluginNavigation');
        if (typeof plugins?.openBundle === 'function') plugins.openBundle('@deepseek-ai/dsh-experimental-voice-input-bundle');
        else if (typeof plugins?.openList === 'function') plugins.openList();
        else return false;
        window.SugarShell?.closeForNotification?.();
        window.SugarEdgeNav?.setExpanded(false);
        return true;
      }
      function openNotification() {
        if (!readyToReveal()) return;
        const id = window.__sugarNotificationSession;
        if (id) {
          delete window.__sugarNotificationSession;
          openConversation(id);
        }
        finishReveal();
      }

      function observe() {
        if (disposed || busy) return;
        openNotification();
        const page = read();
        if (page && !same(page, current)) {
          // Closing DSH's own file panel or global page should leave a forward entry as well.
          if (current && same(parent(current), page) &&
              (current.right || current.panel || same(past.at(-1), page))) {
            if (same(past.at(-1), page)) past.pop();
            push(future, current);
          } else {
            if (current) push(past, current);
            future.length = 0;
          }
        }
        current = page;
        publish();
      }

      function schedule() {
        if (disposed) return;
        // A mounted blank may be left again before the coalesced paint callback. Only
        // capture the identity while this specific home is still the visible route.
        if (waitingHome && !busy) read();
        if (!frame) frame = requestAnimationFrame(() => { frame = 0; observe(); });
      }

      async function show(target) {
        const here = read();
        if (!here) return false;
        if (!same(base(here), base(target))) {
          if (target.home) {
            if (canReopenBlank(target)) {
              uiWorkspace.openSession(target.id);
            } else if (target.workspace) {
              // DSH cancels a pending open when the user goes elsewhere; never commit over it.
              let opened;
              await uiWorkspace.openWorkspace(target.workspace, id => { opened = id; });
              if (disposed || !opened || read()?.id !== opened) return false;
            } else {
              uiWorkspace.startSession();
            }
          } else {
            uiWorkspace.openSession(sessions.subagentAddress(target.id) ?? target.id);
          }
        }
        const selected = read();
        if (disposed || !selected || !same(base(selected), base(target))) return false;
        if (selected.panel !== target.panel) layout.selectPanel(target.panel);
        if (!target.panel && sidebarRight.isExpanded() !== target.right) {
          // The slot acquires the target session's right-panel store during React's commit.
          const surface = document.querySelector('[data-sidebar-right-session]');
          if (surface?.getAttribute('data-sidebar-right-session') !== selected.id) {
            await new Promise(resolve => requestAnimationFrame(resolve));
          }
          if (disposed || sidebarRight.mounted.getSnapshot() !== selected.id) return false;
          if (sidebarRight.isExpanded() !== target.right) sidebarRight.toggleExpanded();
        }
        if (target.right && target.tab && sidebarRight.active()?.id !== target.tab) sidebarRight.focus(target.tab);
        return same(read(), target);
      }

      function action(command) {
        if (disposed || busy) return false;
        observe();
        if (!current || !['home', 'back', 'forward'].includes(command)) return false;
        if (command === 'back' && drawerOpen()) {
          layout.toggleSidebar();
          schedule();
          return true;
        }
        const dismissDrawer = command === 'home' && drawerOpen();
        if (dismissDrawer) layout.toggleSidebar();
        const previous = current;
        let target;
        if (command === 'home') target = home(previous.workspace ?? entries()[0]?.workspaceId);
        else if (command === 'forward') target = peek(future);
        else target = backTarget(previous);
        if (!target || same(target, previous)) return dismissDrawer;
        busy = true;
        publish();
        show(target).then(applied => {
          if (disposed || !applied) return;
          if (command === 'back') {
            if (same(past.at(-1), target)) past.pop();
            push(future, previous);
          } else {
            if (command === 'forward') future.pop();
            else future.length = 0;
            push(past, previous);
          }
          current = read();
        }).catch(error => {
          if (!disposed) console.warn('DSH navigation failed:', error);
        }).finally(() => {
          busy = false;
          if (!disposed) observe();
        });
        return true;
      }

      function canReopenBlank(target) {
        const row = sessions.list.getSnapshot().byId[target.id];
        return Boolean(target.id && row?.blank && !workspaces.list.getSnapshot().archivedSessionIds.includes(target.id) &&
          entries().some(entry => entry.workspaceId === target.workspace && entry.sessionIds.includes(target.id)));
      }

      function previewTarget(command) {
        if (disposed || busy || !current || drawerOpen()) return null;
        const target = command === 'back' ? backTarget(current) : command === 'forward' ? peek(future) : null;
        return target ? {key: routeKey(target, false), previewable: !target.right} : null;
      }
      function canStage(command) {
        if (disposed || busy) return false;
        observe();
        if (!current || drawerOpen()) return false;
        let target;
        if (command === 'home') target = home(current.workspace ?? entries()[0]?.workspaceId);
        else if (command === 'back') target = backTarget(current);
        else if (command === 'forward') target = peek(future);
        if (!target || same(target, current)) return false;
        // Selecting a main-slot panel unmounts its local React state. Preview an inert
        // picture, but do not change the live route until the gesture is committed.
        if (current.panel || target.panel) return false;
        if (!target.home || same(base(current), base(target))) return true;
        // An exact retained blank can be reopened without a remote create. A gesture that might
        // create a new session must wait for release, even if an old cached blank is listed.
        return canReopenBlank(target);
      }

      function checkpoint() {
        observe();
        const original = current, previous = past.slice(), next = future.slice();
        return () => {
          if (disposed || busy || !same(read(), original)) return false;
          past.splice(0, past.length, ...previous);
          future.splice(0, future.length, ...next);
          current = read();
          publish();
          return true;
        };
      }

      // DSH keeps docked tabs (including their iframe) in the right panel when it closes.
      // Its CSS animates the dock hosts inside that panel. Own only these transforms during a scrub.
      const markedPages = new WeakMap();
      let panelSheet, activePanelDrag;
      const dockParts = ':is([data-dockkit-host="dock"],[data-dockkit-empty],[data-dockkit-divider])';
      function installPanelStyle() {
        if (panelSheet) return;
        panelSheet = document.createElement('style');
        panelSheet.textContent = `
          [data-sugar-panel-quiet="right"] ${dockParts},
          [data-sugar-panel-quiet="drawer"] > [data-sidebar-mask],
          [data-sugar-panel-quiet="drawer"] > [data-sidebar-mask] + div,
          [data-sugar-track-quiet] { transition:none!important; }
          [data-sugar-panel-drag="right"] { isolation:isolate; }
          /* A DSH empty dock has transparent areas. Move a backing surface with every dock
             so its icons and text form one opaque sheet over the other live page. */
          [data-sugar-panel-drag="right"]::before {
            content:''; position:absolute; inset:0; z-index:-1; pointer-events:none;
            background-color:Canvas; background-image:linear-gradient(var(--dsw-alias-bg-base,Canvas),var(--dsw-alias-bg-base,Canvas));
            transform:translate3d(var(--sugar-panel-x),0,0); opacity:var(--sugar-panel-opacity);
          }
          [data-sugar-dock-behind] { isolation:isolate; z-index:0!important; }
          [data-sugar-chat-front] { position:relative; isolation:isolate; z-index:1!important; pointer-events:none!important;
            transform:translate3d(var(--sugar-chat-x),0,0)!important; opacity:var(--sugar-chat-opacity)!important; }
          [data-sugar-chat-front="fullscreen"] { grid-column:2 / -1; }
          [data-sugar-chat-front][data-sugar-chat-paper] { background-color:var(--dsw-alias-bg-base,Canvas)!important; }
          [data-sugar-panel-drag="right"] ${dockParts},
          [data-sugar-panel-drag="drawer"] > [data-sidebar-mask] + div {
            visibility:visible!important; transform:translate3d(var(--sugar-panel-x),0,0)!important;
            opacity:var(--sugar-panel-opacity)!important; pointer-events:none!important;
          }
          [data-sugar-panel-drag="drawer"] > [data-sidebar-mask] + div { clip-path:var(--sugar-panel-clip)!important; }
          [data-sugar-panel-drag="drawer"] > [data-sidebar-mask] { opacity:var(--sugar-drawer-mask)!important; }
          [data-sugar-panel-track] {
            grid-template-columns:var(--sugar-left-track) minmax(0,1fr) var(--sugar-right-track)!important;
          }
        `;
        document.head.appendChild(panelSheet);
      }

      function beginPanelDrag(direction, targetMark, side, onProgress) {
        if (disposed || busy || activePanelDrag || !['back','forward'].includes(direction)) return null;
        observe();
        const origin = current;
        if (!origin) return null;
        const drawer = drawerOpen();
        let anchor, rightColumn, chatFront, trackFrame, width, opening = false;
        if (drawer) {
          if (direction !== 'back') return null;
          anchor = document.querySelector('[data-sidebar-drawer]:not([data-sidebar-collapsed])');
          const mask = anchor?.querySelector(':scope > [data-sidebar-mask]');
          width = mask?.nextElementSibling?.offsetWidth;
          if (!width) return null;
        } else {
          const target = targetMark ? markedPages.get(targetMark) :
            direction === 'back' ? backTarget(origin) : peek(future);
          if (!target || !reachable(target) || origin.panel || target.panel || origin.right === target.right ||
              !same(base(origin), base(target))) return null;
          // The first workspace home can be bookmarked before DSH mounts its reusable blank
          // session. Restoring that home only closes the dock of the now-mounted blank.
          const initialHome = origin.right && origin.home && origin.id && origin.workspace && !target.id;
          if (origin.id !== target.id && !initialHome) return null;
          opening = target.right;
          // A collapsed surface may now retain another file. Let normal navigation restore that tab.
          if (opening && target.tab !== sidebarRight.active()?.id) return null;
          anchor = [...document.querySelectorAll('[data-sidebar-right-panel][data-sidebar-right-session]')]
            .find(node => node.getAttribute('data-sidebar-right-session') === origin.id && node.offsetWidth > 0);
          if (!anchor?.querySelector(dockParts)) return null;
          width = anchor.offsetWidth;
          rightColumn = anchor.closest('[data-rightbar-col]');
          if (!opening) {
            // AppFrame mounts CenterColumn immediately before RightbarColumn. Keeping the
            // real center in front also preserves its draft, scroll and embedded controls.
            chatFront = rightColumn?.previousElementSibling;
            if (!chatFront?.offsetWidth) return null;
          }
          if (anchor.getAttribute('data-sidebar-right-panel') === 'push') {
            trackFrame = rightColumn?.parentElement;
            if (!trackFrame) return null;
            const firstTrack = getComputedStyle(trackFrame).gridTemplateColumns.split(' ')[0];
            if (!/^\d+(?:\.\d+)?px$/.test(firstTrack)) return null;
            trackFrame.style.setProperty('--sugar-left-track', firstTrack);
            trackFrame.setAttribute('data-sugar-track-quiet','');
            trackFrame.setAttribute('data-sugar-panel-track','');
          }
        }
        installPanelStyle();
        const motionPreference = matchMedia('(prefers-reduced-motion:reduce)');
        const rail = document.getElementById('sugar-edge-navigation');
        const reduced = () => document.hidden || motionPreference.matches || rail?.hasAttribute('data-motion') === false;
        const sign = side === 'right' ? -1 : 1;
        const kind = drawer ? 'drawer' : 'right';
        let position = 0, lastQuiet, finishing, settleFrame = 0, resolveSettle;
        anchor.setAttribute('data-sugar-panel-quiet',kind);
        anchor.setAttribute('data-sugar-panel-drag',kind);
        if (chatFront) {
          rightColumn.setAttribute('data-sugar-dock-behind','');
          const unpainted = ['transparent','rgba(0, 0, 0, 0)'].includes(getComputedStyle(chatFront).backgroundColor);
          if (unpainted) chatFront.setAttribute('data-sugar-chat-paper','');
          chatFront.setAttribute('data-sugar-chat-front', trackFrame ? 'push' : 'fullscreen');
        }

        function draw(progress) {
          const next = Math.min(1,Math.max(0,progress)), quiet = reduced();
          if (position === next && lastQuiet === quiet) return;
          position = next; lastQuiet = quiet;
          const uncovered = opening ? 1 - position : position;
          const shift = (opening ? -sign : sign) * uncovered * width;
          anchor.style.setProperty('--sugar-panel-x', (quiet || chatFront ? 0 : shift) + 'px');
          anchor.style.setProperty('--sugar-panel-opacity', String(quiet && !chatFront ? 1 - uncovered : 1));
          if (chatFront) {
            // In push mode the grid widens the chat only within the space occupied by the file pane.
            const incoming = quiet || trackFrame ? 0 : -sign * (1 - position) * width;
            chatFront.style.setProperty('--sugar-chat-x', incoming + 'px');
            chatFront.style.setProperty('--sugar-chat-opacity', String(quiet && !trackFrame ? position : 1));
          }
          if (drawer) {
            anchor.style.setProperty('--sugar-drawer-mask', String(1 - position));
            // Keep the moved portion within the original drawer footprint on either gesture edge.
            const cut = (quiet ? 0 : 100 * position) + '%';
            anchor.style.setProperty('--sugar-panel-clip', sign > 0 ? 'inset(0 ' + cut + ' 0 0)' : 'inset(0 0 0 ' + cut + ')');
          }
          if (trackFrame) trackFrame.style.setProperty('--sugar-right-track', width * (1 - uncovered) + 'px');
          onProgress?.(position);
        }
        function cleanup() {
          if (activePanelDrag !== cleanup) return;
          activePanelDrag = null;
          cancelAnimationFrame(settleFrame);
          resolveSettle?.();
          anchor.removeAttribute('data-sugar-panel-drag');
          for (const key of ['x','opacity','clip']) anchor.style.removeProperty('--sugar-panel-' + key);
          anchor.style.removeProperty('--sugar-drawer-mask');
          if (chatFront) {
            chatFront.removeAttribute('data-sugar-chat-front');
            chatFront.removeAttribute('data-sugar-chat-paper');
            chatFront.style.removeProperty('--sugar-chat-x');
            chatFront.style.removeProperty('--sugar-chat-opacity');
            rightColumn.removeAttribute('data-sugar-dock-behind');
          }
          if (trackFrame) {
            trackFrame.removeAttribute('data-sugar-panel-track');
            trackFrame.style.removeProperty('--sugar-left-track');
            trackFrame.style.removeProperty('--sugar-right-track');
          }
          // Flush the actual DSH endpoint with its native transitions suppressed. Removing both
          // overrides together would animate from the scrubbed edge back to the offscreen dock.
          anchor.getBoundingClientRect();
          anchor.removeAttribute('data-sugar-panel-quiet');
          trackFrame?.removeAttribute('data-sugar-track-quiet');
          document.removeEventListener('visibilitychange', visibilityChanged);
        }
        function visibilityChanged() { if (document.hidden) cleanup(); }
        function settle(end, release) {
          const from = position, remaining = Math.abs(end - from);
          if (reduced() || remaining < .005) { draw(end); return; }
          const timing = release || window.SugarPageMotion?.releaseTiming?.(remaining);
          // Older shells can still load this plugin without a release clock.
          const start = performance.now(), duration = Math.max(24,140 * remaining);
          if (timing?.duration === 0) { draw(end); return; }
          return new Promise(resolve => {
            resolveSettle = resolve;
            function step(now) {
              const t = Math.max(0, Math.min(1, (now - start) / duration));
              const fraction = reduced() ? 1 : timing?.fractionAt(now) ?? 1 - (1 - t) ** 2;
              draw(fraction >= 1 ? end : from + (end - from) * fraction);
              if (fraction < 1) settleFrame = requestAnimationFrame(step);
              else { resolveSettle = null; resolve(); }
            }
            settleFrame = requestAnimationFrame(step);
          });
        }
        function waitForPresentation() {
          // The store can update before React's panel attributes and push-grid track. Hold
          // the final frame while they catch up, so DSH cannot start a second transition.
          function ready() {
            if (!anchor.isConnected || document.hidden) return true;
            if (drawer) return anchor.hasAttribute('data-sidebar-collapsed');
            return anchor.hasAttribute('data-sidebar-right-open') === opening &&
              (!trackFrame || trackFrame.hasAttribute('data-rightbar-collapsed') !== opening);
          }
          if (ready()) return;
          return new Promise(resolve => {
            const observer = new MutationObserver(() => { if (ready()) done(); });
            const timer = setTimeout(done, 300);
            function done() {
              observer.disconnect(); clearTimeout(timer); resolveSettle = null; resolve();
            }
            resolveSettle = done;
            observer.observe(anchor, {attributes:true, attributeFilter:[drawer ? 'data-sidebar-collapsed' : 'data-sidebar-right-open']});
            if (trackFrame) observer.observe(trackFrame, {attributes:true, attributeFilter:['data-rightbar-collapsed']});
          });
        }
        function finish(commit, onCommit, release) {
          if (finishing) return finishing;
          if (activePanelDrag !== cleanup) return Promise.resolve(false);
          finishing = Promise.resolve().then(async () => {
            try {
              if (activePanelDrag !== cleanup) return false;
              await settle(commit ? 1 : 0, release);
              if (!commit || activePanelDrag !== cleanup || !anchor.isConnected || !same(read(),origin) || drawerOpen() !== drawer) return false;
              if (!await onCommit()) {
                if (activePanelDrag === cleanup && same(read(),origin) && drawerOpen() === drawer) await settle(0);
                return false;
              }
              if (activePanelDrag === cleanup) await waitForPresentation();
              return true;
            } finally { cleanup(); }
          });
          return finishing;
        }
        activePanelDrag = cleanup;
        document.addEventListener('visibilitychange', visibilityChanged);
        draw(0);
        return {update(progress) {
          if (activePanelDrag === cleanup && !finishing && Number.isFinite(progress)) draw(progress);
        }, finish, cancel:cleanup};
      }

      function bookmark({includeDrawer = true} = {}) {
        if (disposed || busy) return null;
        observe();
        // The shell's page history excludes the temporary workspace menu. An exact bookmark
        // still retains the open drawer when a caller explicitly needs to restore that UI.
        const page = current, drawer = includeDrawer && drawerOpen();
        if (!page) return null;
        const canReach = () => !disposed && reachable(page);
        const mark = {
          key: routeKey(page, drawer),
          previewable: !page.right && !drawer,
          reachable: canReach,
          canStage: () => canReach() && !busy && !page.panel && !read()?.panel &&
            (!page.home || same(base(read() || {}), base(page)) || canReopenBlank(page)),
          async restore() {
            if (busy || !canReach()) return false;
            busy = true; publish();
            try {
              if (!await show(page)) return false;
              if (drawerOpen() !== drawer) {
                if (!document.querySelector('[data-sidebar-drawer]')) return !drawer;
                layout.toggleSidebar();
                await new Promise(resolve => requestAnimationFrame(resolve));
              }
              return !disposed && drawerOpen() === drawer;
            } catch { return false; }
            finally { busy = false; if (!disposed) {current = read(); publish();} }
          }
        };
        markedPages.set(mark, page);
        return mark;
      }

      // DSH restores the draft focus on mousedown. Suppress touch-generated mouse
      // events on Stop so opening the IME cannot move the button before its click.
      const conversationText = ctx.locale.bind('conversation');
      function onComposerPointerDown(event) {
        if (event.pointerType !== 'touch' || !event.isPrimary || event.button !== 0) return;
        const button = event.target.closest?.('[data-composer-card] button[aria-label]');
        if (button && !button.disabled && button.getAttribute('aria-label') === conversationText('input.stop')) {
          event.preventDefault();
        }
      }
      document.addEventListener('pointerdown', onComposerPointerDown, {capture:true, passive:false});
      const sources = [sessions.list, workspaces.list, layout.panelInfo, sidebarRight.mounted, sidebarRight.openTabs];
      const subscriptions = sources.map(source => source.subscribe(schedule));
      // DSH publishes panel selection, but the right-panel expansion and mobile drawer have no
      // public observable. Read their documented presentation attributes after a frame commit.
      const observer = new MutationObserver(schedule);
      observer.observe(document.documentElement, {subtree: true, attributes: true,
        attributeFilter: ['data-sidebar-right-open', 'data-sidebar-collapsed', 'data-sidebar-drawer', 'aria-selected']});
      function sessionRemoved(id) {
        if (!id || disposed) return;
        const selected = read();
        if (selected?.id === id || sidebarRight.mounted.getSnapshot() === id) {
          uiWorkspace.startSession(selected?.workspace, {clearPreviousDraft:false});
        }
        for (const stack of [past, future]) {
          for (let i = stack.length - 1; i >= 0; i--) if (stack[i].id === id) stack.splice(i, 1);
        }
        void sessions.refresh().catch(error => console.warn('DSH session list refresh failed:', error));
      }
      const navigation = {action, state, dispose, canStage, previewTarget, checkpoint, bookmark, beginPanelDrag, sessionRemoved,
        openNotification, openConversation, openPetVoiceSettings};
      window.SugarNavigation = navigation;
      observe();

      function dispose() {
        if (disposed) return;
        disposed = true;
        activePanelDrag?.(); panelSheet?.remove();
        document.removeEventListener('pointerdown', onComposerPointerDown, true);
        for (const stop of subscriptions) stop();
        observer.disconnect();
        cancelAnimationFrame(frame);
        if (window.SugarNavigation === navigation) {
          delete window.SugarNavigation;
          window.dispatchEvent(new CustomEvent('sugar-navigation-state', {
            detail: {canBack: false, canForward: false, busy: false, drawerOpen: false, rightPanelOpen: false,
              previewable: false, isHome: false, routeKey: ''}
          }));
        }
      }
      return dispose;
    }

    return {inject, apply(ctx) { ctx.effect(() => attach(ctx), 'Sugar: app navigation'); }};
  }
});
