import { rootAgents } from '../root-agents.mjs';
import sessionStorage from '../session-storage.cjs';
export const inject = ['agents'];

// DSH marks a root agent running throughout model inference and approval waits.
export function apply(ctx) {
  const router = globalThis[Symbol.for('dsh.sugar.projects')];
  if (!router?.touchActiveWorkspace) return;
  if (router.attachSessionStorage) {
    ctx.inject(['agents', 'sessions', 'sessionPersistence', 'workspaceRegistry'], storageCtx => {
      const manage = sessionStorage.createSessionStorage(storageCtx);
      storageCtx.effect(() => router.attachSessionStorage(manage));
    });
  }
  let active = new Set();
  let timer;
  function refresh(heartbeat = false) {
    const running = new Set(rootAgents(ctx.agents).filter(agent => agent.status === 'running'));
    for (const agent of running) {
      if (heartbeat || !active.has(agent)) void router.touchActiveWorkspace(agent.session.header.cwd, agent.id);
    }
    active = running;
    if (running.size) {
      timer ??= setInterval(() => refresh(true), 5 * 60 * 1000).unref();
    } else {
      clearInterval(timer);
      timer = undefined;
    }
  }
  ctx.on('agent/status', () => refresh());
  ctx.on('agent/disposed', () => refresh());
  refresh(); // A plugin can be reloaded while a turn is already running.
  ctx.effect(() => () => { clearInterval(timer); active.clear(); });
}
