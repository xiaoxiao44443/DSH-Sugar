window.__ModuleLoader__.load({
  id: 'dsh-sugar-recovery',
  factory: () => ({
    inject: ['remoteAccess', 'sessions', 'connection'],
    apply(ctx) {
      // The session catalog is seeded from a Host snapshot and tracks live Agent
      // status across every diagnostic conversation, including approval waits.
      ctx.effect(() => {
        const list = ctx.sessions.list;
        const generation = ctx.connection.generation;
        function update() {
          const snapshot = list.getSnapshot();
          const running = generation.getSnapshot() !== undefined && snapshot.phase === 'ready' &&
            Object.values(snapshot.byId).some(row => row.origin !== 'subagent' && row.running === true);
          if (window.SugarRecoveryTurnActive === running) return;
          window.SugarRecoveryTurnActive = running;
          window.dispatchEvent(new Event('sugar-recovery-turn'));
        }
        const offList = list.subscribe(update);
        const offGeneration = generation.subscribe(update);
        update();
        return () => {
          offList();
          offGeneration();
          delete window.SugarRecoveryTurnActive;
          window.dispatchEvent(new Event('sugar-recovery-turn'));
        };
      }, 'Sugar recovery: active root turns');
      const access = ctx.remoteAccess;
      const denied = {getSnapshot: () => false, subscribe: () => () => {}};
      // The built-in workspace menu queries this source every time it resolves
      // its actions. Other permissions, including session archive, remain live.
      ctx.effect(() => {
        const previous = Object.getOwnPropertyDescriptor(access,'source');
        const source = access.source;
        function diagnosticSource(kind, id, action) {
          if (kind === 'workspace' && action === 'workspace/delete') return denied;
          return source.call(this,kind,id,action);
        }
        Object.defineProperty(access,'source',{value:diagnosticSource,writable:true,configurable:true});
        return () => {
          if (Object.getOwnPropertyDescriptor(access,'source')?.value !== diagnosticSource) return;
          if (previous) Object.defineProperty(access,'source',previous);
          else delete access.source;
        };
      }, 'Sugar recovery: workspace menu');
    }
  })
});
