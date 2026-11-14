'use strict';
// node scripts/check-project-markdown-images.cjs <node_modules/@deepseek-ai> [guest-assets]
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {createHash} = require('node:crypto');
const {AsyncLocalStorage} = require('node:async_hooks');
const packages = path.resolve(process.argv[2]);
const assets = path.resolve(process.argv[3] || path.join(__dirname, '../app/src/main/assets/guest'));
const rules = JSON.parse(fs.readFileSync(path.join(assets, 'project-hooks.json'), 'utf8'));

function patched(name) {
  let code = fs.readFileSync(path.join(packages, name), 'utf8');
  assert.equal(createHash('sha256').update(code).digest('hex'), rules[name].hash);
  for (const [source, replacement] of rules[name].edits) {
    assert.equal(code.split(source).length, 2, 'ambiguous or absent anchor: ' + source);
    code = code.replace(source, () => replacement);
  }
  return code;
}
function section(code, start, end) {
  const i = code.indexOf(start), j = code.indexOf(end, i);
  assert.ok(i >= 0 && j > i, 'missing source section ' + start);
  return code.slice(i, j);
}

const chat = patched('dsh-client-ui-chat/lib/client.js');
const refs = new Function(section(chat, 'function isWindowsStylePath(', 'const EMPTY_LIST') +
  section(chat, 'function localPathMediaUrl(', 'const AssistantMarkdown=') +
  'return {fileMediaUrl, resolveWorkspacePath, localPathMediaUrl}')();
const buildImages = new Function('react', 't', 'document', 'fileDrag', 'sessionId', 'useProjection',
  'fileMediaUrl', 'resolveWorkspacePath', 'sessionFileAddress',
  section(chat, 'const mediaRoot = useProjection(', 'const codeLabels=') + 'return fileImages');
const useMemo = fn => fn();
const browser = {baseURI:'https://example.test/prefix/'};
function fileImages(id, root) {
  return buildImages({useMemo}, s => s, browser, {}, id, () => root,
    refs.fileMediaUrl, refs.resolveWorkspacePath, (session, uri) => [session, uri]);
}
const buildStreaming = new Function('react', 'document', 'localPathMediaUrl', '_deepseek_ai_dsh_client_ui_primitives',
  section(chat, 'const { fileImages } = (0, _deepseek_ai_dsh_client_ui_primitives.useMarkdownDelegate)();',
    'const last=blocks.length') + 'return pathImages');
const images = fileImages('one', '/workspace');
const stream = buildStreaming({useMemo}, browser, refs.localPathMediaUrl, {
  useMarkdownDelegate: () => ({fileImages: images})
});
const url = new URL(images.resolve('/workspace/result.png'));
assert.equal(url.pathname, '/prefix/api/file');
assert.equal(url.searchParams.get('path'), '/workspace/result.png');
assert.equal(url.searchParams.get('sessionId'), 'one');
assert.equal(images.resolve('result.png'), url.href);
assert.equal(stream.resolve('/workspace/result.png#frame'), url.href);
assert.equal(stream.resolve('result.png?version=1'), url.href);
assert.equal(stream.resolve('/workspace/%E7%8C%AB.png'), images.resolve('/workspace/猫.png'));
assert.equal(stream.resolve('https://images.example.test/image.png'), undefined); // Let the upstream remote-image renderer handle it.
for (const value of ['file:///tmp/x.png', '/home/sugar/.dsh/profile', '//otherhost/image.png']) {
  assert.equal(images.resolve(value), undefined);
  assert.equal(stream.resolve(value), undefined);
}
assert.equal(fileImages('two','/workspace/sub').resourceAddress('image.png')[1], '/workspace/sub/image.png');
assert.equal(new URL(fileImages('two','/workspace/sub').resolve('image.png')).searchParams.get('path'), '/workspace/sub/image.png');

const store = new AsyncLocalStorage();
const rootFor = id => ({one:'/workspace/aaa', two:'/home/sugar/bbb'})[id];
const routing = fs.readFileSync(path.join(assets, 'project-routing.cjs'), 'utf8');
const hook = new Function('path', 'within',
  section(routing, 'function workspaceMediaPath(', 'function workingPath(') +
  'return {workspaceMediaPath,serveSessionFile}')(path.posix, async (root, fn, id) => {
  assert.equal(root, rootFor(id));
  return store.run(id, fn);
});
const provider = {
  async resolve(file) {
    const canonical = file === '/workspace/bad.png' ? '/home/sugar/.dsh/profile' : path.posix.resolve(file);
    return {displayPath:file, targetKey:canonical};
  },
  processPath(target) { return target.targetKey; },
  async stat(target) {
    assert.notEqual(target.targetKey, '/home/sugar/.dsh/profile');
    if (target.targetKey === '/workspace/missing.png') return undefined;
    return {version:'same-v', size:target.targetKey === '/workspace/big.png' ? 20 : 4, type:'file'};
  },
  async readBytes() { return Buffer.from(store.getStore() || 'host'); }
};
let serve;
const controller = patched('dsh-api-session-controller/lib/index.js');
const media = new Function('SUGAR_HOOK', 'isAbsolute', 'mime', 'FsError',
  section(controller, 'const BASE_HEADERS = {', '//#endregion\n//#region lib/types/archived-session-gate') +
  'return SessionMediaReferences')(hook, path.posix.isAbsolute, {lookup:()=>'image/png'}, Error);
media.apply({
  attachments:{imageLimits:{maxImageBytes:12}},
  fs:provider,
  get(name) {
    assert.equal(name, 'typert');
    return {lookups:{get(key) {
      assert.equal(key,'workspaceFileScope');
      return {resolve:async id => rootFor(id) && {sessionId:id, workspaceRoot:rootFor(id)}};
    }}};
  },
  effect:fn=>fn(),
  connection:{fetch:{register(route) { assert.deepEqual(route.methods,['GET','HEAD']); serve=route.fetch; }}}
});
async function fetchPath(file, session, method='GET') {
  const u = new URL('api/file', browser.baseURI);
  u.searchParams.set('path', file);
  if (session !== undefined) u.searchParams.set('sessionId',session);
  return serve(new Request(u,{method}));
}
(async () => {
  for (const [session, payload] of [['one','one'],['two','two'],[undefined,'host']]) {
    const start = await fetchPath('/workspace/result.png', session);
    assert.equal(start.status, 302);
    assert.match(start.headers.get('cache-control'), /no-store/);
    const next = new URL(start.headers.get('location'),url);
    assert.equal(next.searchParams.get('sessionId'),session ?? null);
    assert.equal(next.searchParams.get('v'),'same-v');
    const bytes = await serve(new Request(next));
    assert.equal(bytes.headers.get('content-type'),'image/png');
    assert.match(bytes.headers.get('content-security-policy'),/sandbox/);
    assert.equal(await bytes.text(),payload);
    const head = await serve(new Request(next,{method:'HEAD'}));
    assert.equal(head.status,200);
    assert.equal(await head.text(),'');
  }
  for (const file of ['/home/sugar/.dsh/profile','/workspace/../home/sugar/.dsh/profile','/workspace/bad.png']) {
    assert.equal((await fetchPath(file,'one')).status,403);
  }
  assert.equal((await fetchPath('/workspace/missing.png','one')).status,404);
  assert.equal((await fetchPath('/workspace/big.png','one')).status,413);
  assert.equal((await fetchPath('/workspace/result.png','unknown')).status,404);
  assert.equal((await fetchPath('/workspace/result.png','')).status,400);
  console.log('Session Markdown image URLs, streaming, isolated lookup, redirect, HEAD, byte limit and denials passed.');
})().catch(err => { console.error(err); process.exitCode=1; });
