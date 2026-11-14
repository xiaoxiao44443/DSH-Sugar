import AgentPresetRegistry from '/opt/dsh/node_modules/@deepseek-ai/dsh-agent-preset-registry/lib/index.js';

// Keep DSH's session projections, tools, RPCs and "already started" semantics.
// This registry belongs exclusively to the temporary repair host.
export default class RecoveryPresetRegistry extends AgentPresetRegistry {
  get defaultId() {
    return 'sugar-recovery';
  }

  async register(definition) {
    if (definition.id !== this.defaultId) {
      throw new Error('Only the Sugar recovery preset can be registered during diagnosis.');
    }
    return super.register(definition);
  }
}
