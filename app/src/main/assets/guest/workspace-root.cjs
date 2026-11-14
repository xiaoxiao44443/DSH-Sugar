'use strict';

// Keep in sync with WorkspaceRoot.kt. A registered root becomes a separate Linux environment.
function isWorkspaceRoot(path) {
  if (typeof path !== 'string' || Buffer.byteLength(path) > 4095 ||
      !(path.startsWith('/workspace/') || path.startsWith('/home/'))) return false;
  const parts = path.slice(1).split('/');
  if (parts.some(part => !part.trim() || part.startsWith('.') || /[\\\x00-\x1f\x7f-\x9f]/u.test(part))) return false;
  if (path === '/home/sugar' || path === '/workspace/deepseek-harness') return false;
  return !/^\/workspace\/(?:local|phone)-[a-f0-9]{64}\//u.test(path);
}

module.exports = {isWorkspaceRoot};
