# Plugin glob upstream

The plugin file view uses the traversal engine from **Node.js v24.22.2**, matching
the bundled guest Node version, and its self-contained minimatch distribution.
These are build-time vendored sources: no runtime internal Node APIs, npm install,
dynamic source evaluation or global `fs` monkey-patching is used.

- `plugin-glob-engine.cjs`: https://github.com/nodejs/node/blob/v24.22.2/lib/internal/fs/glob.js
- `plugin-minimatch.cjs`: https://github.com/nodejs/node/blob/v24.22.2/deps/minimatch/index.js
- `plugin-glob-LICENSE.txt`: https://github.com/nodejs/node/blob/v24.22.2/LICENSE

The full upstream license and dependency notices are shipped with the assets.
Sugar's own license does not replace these notices.

Local engine adaptations:

1. A factory injects the plugin filesystem and the appropriate path implementation.
2. Small public-API equivalents replace Node's internal helpers and primordials.
3. Only missing-path / not-a-directory errors are treated as empty matches;
   workspace scope, permission, transport and other errors remain observable.
4. Absolute patterns redirect to their root before reading cwd, so a global
   plugin can search Host paths without requiring a workspace. The original cwd
   is retained for relative exclusion patterns.
5. The unused standalone `matchGlobPattern` entry point is omitted.

`plugin-glob.cjs` supplies `/workspace` as the default directory and checks the
original workspace before/after filesystem operations and each iterator yield.
`exclude` callbacks execute in the plugin, so functions do not cross the file RPC.

Regression: `node scripts/check-plugin-glob.cjs` compares against Node's native
glob and verifies workspace isolation over the real file RPC.

Original SHA-256 digests:

- `glob.js`: `ecee340045b8d9d2f4ebca8f20ffa69306613c838e28d916d89eecb78ef12a64`
- `minimatch.js`: `f96315be454e1579bb685f035b015aca6c33d7275f03707f74d6c82089250c30`
- `LICENSE`: `5888dbb9a1d2b18f2c3e6c5f6af1b39de658372b402a0577b002777f14c62ace`
