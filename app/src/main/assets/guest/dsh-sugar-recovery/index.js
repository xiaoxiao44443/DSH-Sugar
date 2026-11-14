export const inject = ['workspaceRegistry'];

const unavailable = Object.freeze({kind: 'unavailable'});

export function apply(ctx) {
  // The workspace UI needs the directoryPicker Remote namespace to mount. An unavailable
  // capability serves none of its verbs and has no native or browse UI contribution.
  ctx.provide('directoryPicker', {capability: () => unavailable});

  const registry = ctx.workspaceRegistry;
  async function rejectMutation() {
    throw new Error('The diagnostic session uses a single fixed workspace.');
  }
  // DSH's first-use initializeDefault registers via its own queued operation. Leave
  // that operation and the entire session/file API usable in the repair workspace.
  for (const name of ['create','delete','remove']) {
    if (typeof registry[name] !== 'function') continue;
    ctx.effect(() => {
      const previous = Object.getOwnPropertyDescriptor(registry,name);
      Object.defineProperty(registry,name,{value:rejectMutation,writable:true,configurable:true});
      return () => {
        if (Object.getOwnPropertyDescriptor(registry,name)?.value !== rejectMutation) return;
        if (previous) Object.defineProperty(registry,name,previous);
        else delete registry[name];
      };
    }, 'Sugar recovery: fixed workspace ' + name);
  }
}
