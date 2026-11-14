// User-facing conversations exclude sessions created by subagents.
export function rootAgents(agents) {
  // Existing custom installations may still provide the older registry API.
  if (typeof agents.roots === 'function') return agents.roots();
  return agents.list().filter(agent => agent.session.header.origin !== 'subagent');
}
