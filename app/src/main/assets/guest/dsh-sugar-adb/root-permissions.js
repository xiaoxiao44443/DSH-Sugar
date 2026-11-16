// This uses DSH's trusted live services. Shared .dsh and host plugins are trusted
// control-plane code, so these checks are not an isolation boundary against them.
function turnId(session) {
  for (let i = session.seq - 1; i >= 0; i--) {
    const event = session.eventAt(i);
    if (event.type === 'turn/end') break;
    if (event.type === 'turn/start') return i;
  }
  return -1;
}

export async function authorizeRoot(ctx, agent, command, signal, manual = false) {
  signal.throwIfAborted();
  // manual is supplied by the host's terminal-entry scope, never a CLI field.
  // A command typed by the user in that terminal is the user's direct request.
  if (manual) return;
  const session = agent.session;
  const turn = turnId(session);
  if (turn < 0) throw Error('Root 命令需要当前仍在进行的会话轮次。请在对话中发起操作。');
  const preset = ctx.permissionPresets.current(session);
  if (preset !== 'danger-full-access') {
    const outcome = await ctx.approval.request({agent, toolName:'sugar-root', signal,
      reason:'请求以 Sugar 的 Root 权限在 Android 上执行以下完整脚本。路径属于手机系统。\n\n' + command});
    if (outcome !== 'allowed-once') {
      const message = {rejected:'本次 Root 命令未获批准。', cancelled:'Root 审批已取消。',
        unavailable:'当前无法取得 Root 操作审批，请在支持审批的会话中重试。'};
      throw Error(message[outcome] || 'Root 操作审批未通过。');
    }
  }
  signal.throwIfAborted();
  if (turnId(session) !== turn || ctx.permissionPresets.current(session) !== preset)
    throw Error('会话轮次或权限已改变，请重新发起 Root 命令。');
}
