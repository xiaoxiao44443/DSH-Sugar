window.__ModuleLoader__.load({
  id: 'dsh-sugar-mobile-use',
  factory: () => {
    function attach({sessions, sidebarRight, layout}) {
      if (window !== window.top || location.protocol !== 'http:' || location.hostname !== '127.0.0.1') return;
      let pageHidden = false;
      let previous;
      // mounted is DSH's selected on-screen session, including a reusable blank draft on Home.
      function current() {
        if (pageHidden || document.hidden || layout.panelInfo.getSnapshot().activePanelId != null) return null;
        if (document.querySelector('[data-rightbar-fullscreen]')) return null;
        const id = sidebarRight.mounted.getSnapshot();
        const list = sessions.list.getSnapshot();
        return typeof id === 'string' && list.phase === 'ready' && list.byId[id]?.blank === false ? id : null;
      }
      // Only the conversations owning screens in the open native manager are observed.
      // This is the same session summary title displayed in DSH's workspace sidebar.
      function watchTitles(ids, changed) {
        let previousTitles;
        function read() {
          const list = sessions.list.getSnapshot();
          const titles = Object.fromEntries(ids.flatMap(id => {
            const row = list.phase === 'ready' && list.byId[id];
            const title = row && !row.blank && row.title?.trim();
            return title ? [[id, Array.from(title).slice(0,120).join('')]] : [];
          }));
          const snapshot = JSON.stringify(titles);
          if (snapshot === previousTitles) return;
          previousTitles = snapshot;
          changed(titles);
        }
        const stop = sessions.list.subscribe(read);
        read();
        return stop;
      }
      const source = {current, watchTitles};
      function announce(conversation) {
        window.dispatchEvent(new CustomEvent('sugar-mobile-conversation', {detail: {conversation}}));
      }
      function publish() {
        if (window.SugarMobileSession !== source) return;
        const id = current();
        if (id === previous) return;
        previous = id;
        announce(id);
      }
      function hide() { pageHidden = true; publish(); }
      function show() { pageHidden = false; publish(); }
      window.SugarMobileSession = source;
      const subscriptions = [sessions.list, sidebarRight.mounted, layout.panelInfo].map(store => store.subscribe(publish));
      // Fullscreen file previews keep the session mounted. DSH exposes this geometry on its layout frame.
      const geometry = new MutationObserver(publish);
      geometry.observe(document.documentElement, {subtree: true, attributes: true, attributeFilter: ['data-rightbar-fullscreen']});
      document.addEventListener('visibilitychange', publish);
      window.addEventListener('pagehide', hide);
      window.addEventListener('pageshow', show);
      publish();
      return () => {
        geometry.disconnect();
        for (const unsubscribe of subscriptions) unsubscribe();
        document.removeEventListener('visibilitychange', publish);
        window.removeEventListener('pagehide', hide);
        window.removeEventListener('pageshow', show);
        if (window.SugarMobileSession === source) {
          delete window.SugarMobileSession;
          announce(null);
        }
      };
    }
    return {inject: ['sessions', 'sidebarRight', 'layout'], apply(ctx) {
      ctx.effect(() => attach(ctx), 'Sugar: visible phone-control conversation');
    }};
  }
});
