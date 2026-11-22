'use strict';

// Exercise the public plugin fs view over the real binary RPC protocol. The
// backend HTTP server must live on another thread so globSync cannot block it.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const http = require('node:http');
const { once } = require('node:events');
const { pathToFileURL } = require('node:url');
const { AsyncLocalStorage } = require('node:async_hooks');
const { Worker, isMainThread, parentPort, workerData } = require('node:worker_threads');
const assets = path.resolve(process.env.SUGAR_PLUGIN_TEST_ASSETS || process.argv[2] ||
  path.join(__dirname, '../app/src/main/assets/guest'));

if (!isMainThread && workerData?.sugarGlobServer) {
  const { createBackend } = require(path.join(workerData.assets, 'plugin-files-worker.cjs'));
  const backends = new Map(['a', 'b'].map(id =>
    [id, createBackend({ root: path.join(workerData.root, id) })]));
  const server = http.createServer((req, res) => {
    const backend = backends.get(req.headers.authorization?.slice(7));
    if (!backend) { res.writeHead(403).end(); return; }
    void backend.handle(req, res);
  });
  server.listen(0, '127.0.0.1', () =>
    parentPort.postMessage('http://127.0.0.1:' + server.address().port));
  parentPort.once('message', async () => {
    server.close();
    server.closeAllConnections();
    await Promise.all([...backends.values()].map(backend => backend.close()));
    parentPort.close();
  });
} else {
  async function collect(iterator) {
    const results = [];
    for await (const entry of iterator) results.push(entry);
    return results;
  }

  function callbackGlob(view, pattern, options) {
    return new Promise((resolve, reject) => {
      const callback = (error, matches) => error ? reject(error) : resolve(matches);
      if (options === undefined) view.glob(pattern, callback);
      else view.glob(pattern, options, callback);
    });
  }

  async function main() {
    assert.equal(typeof fs.globSync, 'function', 'Run with Node 24 or newer');
    // Windows CI TEMP may use an 8.3 name or junction. Internal fixture links
    // must target the same canonical root that the file backend validates.
    const fixtureBase = fs.realpathSync(os.tmpdir());
    const root = fs.mkdtempSync(path.join(fixtureBase, 'sugar-plugin-glob-'));
    const actualA = path.join(root, 'a');
    for (const directory of ['a', 'b', 'host', 'outside']) {
      fs.mkdirSync(path.join(root, directory));
    }
    const files = {
      'a.txt': 'A',
      'b.js': 'B',
      '.hidden.txt': 'hidden',
      'src/main.js': 'main',
      'src/helper.ts': 'helper',
      'src/.secret.js': 'secret',
      'src/deep/nested.txt': 'deep',
      'images/one.png': 'png',
      'skip/ignored.js': 'skip',
      'other/keep.json': '{}',
      'levels/one/two/three/four/five/six/seven/eight/deep.txt': 'deep',
      'only-a/marker.txt': 'A workspace'
    };
    for (const [name, content] of Object.entries(files)) {
      const actual = path.join(actualA, name);
      fs.mkdirSync(path.dirname(actual), { recursive: true });
      fs.writeFileSync(actual, content);
    }
    fs.mkdirSync(path.join(actualA, 'empty'));
    fs.mkdirSync(path.join(root, 'b', 'only-b'));
    fs.writeFileSync(path.join(root, 'b', 'only-b', 'marker.txt'), 'B workspace');
    fs.mkdirSync(path.join(root, 'host', 'nested'));
    fs.writeFileSync(path.join(root, 'host', 'host.txt'), 'host');
    fs.writeFileSync(path.join(root, 'host', 'nested', 'child.txt'), 'child');
    fs.writeFileSync(path.join(root, 'outside', 'private.txt'), 'private');

    let server, transport;
    const checks = [];
    try {
      server = new Worker(__filename, { workerData: { sugarGlobServer: true, root, assets } });
      const [url] = await once(server, 'message');
      transport = require(path.join(assets, 'plugin-file-transport.cjs')).create();
      const { create } = require(path.join(assets, 'plugin-files.cjs'));
      const active = new AsyncLocalStorage();
      const options = {
        current: () => active.getStore(),
        request: transport.request,
        requestSync: transport.requestSync
      };
      const facade = create(options);
      const a = { id: 'a', envId: 'a', url, token: 'a', writable: true };
      const b = { ...a, id: 'b', envId: 'b', token: 'b' };
      const slash = value => String(value).replaceAll('\\', '/');
      const nativeRoot = slash(actualA);
      const virtualPath = value => {
        const normalized = slash(value);
        const same = process.platform === 'win32' ?
          normalized.toLowerCase().startsWith(nativeRoot.toLowerCase() + '/') :
          normalized.startsWith(nativeRoot + '/');
        if (normalized === nativeRoot || process.platform === 'win32' &&
            normalized.toLowerCase() === nativeRoot.toLowerCase()) return '/workspace';
        return same ? '/workspace' + normalized.slice(nativeRoot.length) : normalized;
      };
      const kindMethods = ['isFile', 'isDirectory', 'isSymbolicLink', 'isBlockDevice',
        'isCharacterDevice', 'isFIFO', 'isSocket'];
      const normalize = matches => matches.map(entry => {
        if (typeof entry === 'string') return virtualPath(entry);
        assert(entry instanceof fs.Dirent, 'withFileTypes returns public fs.Dirent objects');
        return {
          name: slash(entry.name),
          parentPath: virtualPath(entry.parentPath),
          kind: kindMethods.filter(method => entry[method]())
        };
      }).sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right)));
      const skipDirectory = entry => {
        const name = typeof entry === 'string' ? slash(entry).split('/').at(-1) : entry.name;
        return name === 'skip';
      };
      const cases = [
        { label: 'default cwd', pattern: '*' },
        { label: 'globstar and deep paths', pattern: '**/*' },
        { label: 'globstar alone', pattern: '**' },
        { label: 'multiple patterns and dedup', pattern: ['src/**/*.js', '**/*.txt', 'a.txt'] },
        { label: 'braces', pattern: '{src,images}/**/*.{js,png}' },
        { label: 'extglob alternatives', pattern: 'src/@(main|helper).*' },
        { label: 'extglob extension', pattern: 'src/*.+(js|ts)' },
        { label: 'character class', pattern: '[ab].*' },
        { label: 'root dotfiles', pattern: '.*' },
        { label: 'nested dotfiles', pattern: '**/.*' },
        { label: 'relative cwd', pattern: '**/*', options: { cwd: 'src' },
          nativeCwd: path.join(actualA, 'src') },
        { label: 'absolute cwd', pattern: '**/*', options: { cwd: '/workspace/src' },
          nativeCwd: path.join(actualA, 'src') },
        { label: 'URL cwd', pattern: '*', options: { cwd: new URL('file:///workspace/src') },
          nativeCwd: pathToFileURL(path.join(actualA, 'src')) },
        { label: 'absolute pattern', pattern: '/workspace/src/**/*.js',
          nativePattern: nativeRoot + '/src/**/*.js' },
        { label: 'mixed relative and absolute patterns', pattern: ['a.txt', '/workspace/src/*.js'],
          nativePattern: ['a.txt', nativeRoot + '/src/*.js'] },
        { label: 'literal file', pattern: 'a.txt' },
        { label: 'literal directory', pattern: 'src' },
        { label: 'no matches', pattern: 'missing/**/*.txt' },
        { label: 'missing cwd', pattern: '**/*', options: { cwd: '/workspace/missing' },
          nativeCwd: path.join(actualA, 'missing') },
        { label: 'ENOTDIR empty match', pattern: 'a.txt/*' },
        { label: 'empty patterns', pattern: [] },
        { label: 'exclude patterns', pattern: '**/*',
          options: { exclude: ['skip/**', '**/*.ts'] } },
        { label: 'exclude predicate and pruning', pattern: '**/*',
          options: { exclude: skipDirectory } },
        { label: 'Dirent results', pattern: '**/*', options: { withFileTypes: true } },
        { label: 'Dirent literal', pattern: 'src/main.js', options: { withFileTypes: true } },
        { label: 'Dirent predicate', pattern: '**/*',
          options: { withFileTypes: true, exclude: skipDirectory } }
      ];
      async function differential(testCase) {
        let api = 'native baseline';
        try {
          const nativeOptions = { ...testCase.options, cwd: testCase.nativeCwd ?? actualA };
          const expected = normalize(fs.globSync(testCase.nativePattern ?? testCase.pattern, nativeOptions));
          await active.run(a, async () => {
            api = 'sync';
            const actualSync = facade.globSync(testCase.pattern, testCase.options);
            assert(Array.isArray(actualSync));
            assert.deepEqual(normalize(actualSync), expected, testCase.label + ': sync');
            api = 'callback';
            assert.deepEqual(normalize(await callbackGlob(facade, testCase.pattern, testCase.options)),
              expected, testCase.label + ': callback');
            api = 'async iterator';
            const iterator = facade.promises.glob(testCase.pattern, testCase.options);
            assert.equal(typeof iterator[Symbol.asyncIterator], 'function');
            assert.equal(typeof iterator.then, 'undefined', 'promises.glob returns an iterator, not a Promise');
            assert.deepEqual(normalize(await collect(iterator)), expected, testCase.label + ': async iterator');
          });
        } catch (cause) {
          throw new Error('Glob differential "' + testCase.label + '" (' + api + ', Node ' +
            process.version + ', ' + process.platform + ') failed', { cause });
        }
      }
      for (const testCase of cases) await differential(testCase);
      checks.push(cases.length + ' native-glob differential cases across sync/callback/async iterator');

      await active.run(a, async () => {
        let invalidCallbackCalls = 0;
        for (const pattern of [null, 42]) {
          assert.throws(() => facade.glob(pattern, {}, () => { invalidCallbackCalls++; }),
            { code: 'ERR_INVALID_ARG_TYPE' }, 'callback glob validates pattern synchronously');
          assert.throws(() => facade.globSync(pattern), { code: 'ERR_INVALID_ARG_TYPE' });
          const invalidIterator = facade.promises.glob(pattern);
          assert.equal(typeof invalidIterator[Symbol.asyncIterator], 'function',
            'promises.glob defers validation until iteration');
          await assert.rejects(collect(invalidIterator), { code: 'ERR_INVALID_ARG_TYPE' });
        }
        await new Promise(resolve => setImmediate(resolve));
        assert.equal(invalidCallbackCalls, 0, 'invalid patterns must throw instead of invoking callbacks');
      });
      checks.push('native argument-error timing for sync, callback and async iterator APIs');

      // None of these operations may adopt the next caller's workspace or
      // return cached entries gathered while a different workspace was active.
      const [inA, inB] = await Promise.all([
        active.run(a, () => collect(facade.promises.glob('only-*/**/*.txt'))),
        active.run(b, () => collect(facade.promises.glob('only-*/**/*.txt')))
      ]);
      assert.deepEqual(inA, ['only-a/marker.txt']);
      assert.deepEqual(inB, ['only-b/marker.txt']);
      await active.run({ ...a, writable: false }, async () => {
        assert.deepEqual(facade.globSync('a.txt'), ['a.txt']);
        assert.deepEqual(await callbackGlob(facade, 'a.txt'), ['a.txt']);
        assert.deepEqual(await collect(facade.promises.glob('a.txt')), ['a.txt']);
      });
      assert.throws(() => facade.globSync('*'), { code: 'ENOENT' });
      await assert.rejects(callbackGlob(facade, '*'), { code: 'ENOENT' });
      await assert.rejects(collect(facade.promises.glob('*')), { code: 'ENOENT' });
      const beforeFirstRead = active.run(a, () => facade.promises.glob('*'));
      await active.run(b, () => assert.rejects(beforeFirstRead.next(), { code: 'EACCES' }));
      await beforeFirstRead.return?.();
      const cached = active.run(a, () => facade.promises.glob('*'));
      const first = await active.run(a, () => cached.next());
      assert.equal(first.done, false);
      await active.run(b, () => assert.rejects(cached.next(), { code: 'EACCES' }));
      await cached.return?.();
      const lostContext = active.run(a, () => facade.promises.glob('*'));
      await assert.rejects(lostContext.next(), { code: 'EACCES' });
      await lostContext.return?.();
      checks.push('concurrent workspaces, read-only reads, absent context and iterator identity including cache');

      // Absolute Host paths remain available; virtual cwd rules must not turn
      // a Windows drive into a directory below /workspace.
      const hostRoot = path.join(root, 'host');
      const hostPattern = slash(hostRoot) + '/**/*.txt';
      const hostExpected = fs.globSync(hostPattern).map(slash).sort();
      assert.deepEqual(facade.globSync(hostPattern).map(slash).sort(), hostExpected);
      assert.deepEqual((await callbackGlob(facade, hostPattern)).map(slash).sort(), hostExpected);
      assert.deepEqual((await collect(facade.promises.glob(hostPattern))).map(slash).sort(), hostExpected);
      // Absolute patterns still evaluate exclusions relative to the public
      // default cwd, even when no conversation workspace exists.
      const relativeExcluded = slash(path.relative('/workspace', path.join(hostRoot, 'nested', 'child.txt')));
      const hostExclusionOptions = { exclude: [relativeExcluded] };
      const excludedExpected = fs.globSync(hostPattern, { ...hostExclusionOptions, cwd: '/workspace' }).map(slash).sort();
      assert.deepEqual(excludedExpected, [slash(path.join(hostRoot, 'host.txt'))]);
      assert.deepEqual(facade.globSync(hostPattern, hostExclusionOptions).map(slash).sort(), excludedExpected);
      assert.deepEqual((await callbackGlob(facade, hostPattern, hostExclusionOptions)).map(slash).sort(), excludedExpected);
      assert.deepEqual((await collect(facade.promises.glob(hostPattern, hostExclusionOptions))).map(slash).sort(), excludedExpected);
      for (const cwd of [hostRoot, pathToFileURL(hostRoot)]) {
        const expected = fs.globSync('**/*.txt', { cwd }).map(slash).sort();
        assert.deepEqual(facade.globSync('**/*.txt', { cwd }).map(slash).sort(), expected);
        assert.deepEqual((await collect(facade.promises.glob('**/*.txt', { cwd }))).map(slash).sort(), expected);
      }
      checks.push('absolute Host patterns and string/URL cwd without a workspace');

      // The realpath guard must reject a Host alias before the filesystem can
      // expose the coordinator's /workspace. Emulate that alias portably.
      const alias = path.join(root, 'hidden-host-workspace');
      const aliasFs = Object.defineProperties({}, Object.getOwnPropertyDescriptors(fs));
      Object.defineProperty(aliasFs, 'realpathSync', {
        configurable: true, enumerable: true, writable: true,
        value(name, ...args) {
          const candidate = path.resolve(String(name));
          if (candidate === alias || candidate.startsWith(alias + path.sep)) {
            return '/workspace' + slash(candidate.slice(alias.length));
          }
          return fs.realpathSync(name, ...args);
        }
      });
      const aliasView = create({ ...options, hostFs: aliasFs });
      assert.throws(() => aliasView.globSync('**/*', { cwd: alias }), { code: 'EACCES' });
      await assert.rejects(callbackGlob(aliasView, '**/*', { cwd: alias }), { code: 'EACCES' });
      await assert.rejects(collect(aliasView.promises.glob('**/*', { cwd: alias })), { code: 'EACCES' });
      checks.push('Host aliases cannot reveal the coordinator workspace');

      // Junctions do not require Windows developer-mode privileges. Compare
      // ordinary and recursive symlink handling before adding an escaping link.
      let symlinks = false;
      try {
        const linkType = process.platform === 'win32' ? 'junction' : 'dir';
        fs.symlinkSync(path.join(actualA, 'src'), path.join(actualA, 'linked-src'), linkType);
        fs.symlinkSync(path.join(actualA, 'src'), path.join(actualA, 'src', 'cycle'), linkType);
        symlinks = true;
      } catch (error) {
        if (!['EPERM', 'ENOTSUP', 'EACCES'].includes(error.code)) throw error;
        checks.push('symlink cases skipped: filesystem denied symlink creation');
      }
      if (symlinks) {
        await differential({ label: 'explicit symlink path', pattern: 'linked-src/*.js' });
        await differential({ label: 'default globstar symlinks', pattern: '**/*.js' });
        const [major, minor] = process.versions.node.split('.').map(Number);
        if (major > 24 || major === 24 && minor >= 16) {
          await differential({ label: 'followSymlinks and cycle detection', pattern: '**/*.js',
            options: { followSymlinks: true } });
          await differential({ label: 'followSymlinks Dirent', pattern: '**/*.js',
            options: { followSymlinks: true, withFileTypes: true } });
        }
        checks.push('explicit symlinks, globstar defaults and followSymlinks cycle handling');
      }

      // A failed RPC is not the same as an absent match. Probe each backend
      // operation used by the matcher, including failures in deeper traversal.
      const failureProbes = [
        { method: 'lstat', code: 'EIO', pattern: '**/*' },
        { method: 'readdir', code: 'ETIMEDOUT', pattern: '**/*' },
        { method: 'readdir', code: 'EACCES', pattern: '**/*', suffix: '/src' }
      ];
      if (symlinks) {
        failureProbes.push(
          { method: 'stat', code: 'EIO', pattern: '**/*', followSymlinks: true },
          { method: 'realpath', code: 'EIO', pattern: '**/*', followSymlinks: true });
      }
      for (const probe of failureProbes) {
        let hits = 0;
        const intercept = operation => {
          if (operation.method === probe.method &&
              (!probe.suffix || String(operation.args[0]).endsWith(probe.suffix))) {
            hits++;
            throw Object.assign(Error('Simulated RPC ' + probe.method + ' failure'), { code: probe.code });
          }
        };
        const broken = create({
          ...options,
          request: async (endpoint, operation) => {
            intercept(operation);
            return transport.request(endpoint, operation);
          },
          requestSync: (endpoint, operation) => {
            intercept(operation);
            return transport.requestSync(endpoint, operation);
          }
        });
        await active.run(a, async () => {
          const matchOptions = { followSymlinks: probe.followSymlinks === true };
          assert.throws(() => broken.globSync(probe.pattern, matchOptions), { code: probe.code });
          await assert.rejects(callbackGlob(broken, probe.pattern, matchOptions), { code: probe.code });
          await assert.rejects(collect(broken.promises.glob(probe.pattern, matchOptions)), { code: probe.code });
        });
        assert(hits >= 3, probe.method + ' failure was reached by all three APIs');
      }
      checks.push('RPC errors from lstat/readdir/stat/realpath propagate instead of becoming empty matches');

      if (symlinks) {
        fs.symlinkSync(path.join(root, 'outside'), path.join(actualA, 'escape'),
          process.platform === 'win32' ? 'junction' : 'dir');
        await active.run(a, async () => {
          const matchOptions = { followSymlinks: true };
          assert.throws(() => facade.globSync('escape/**/*.txt', matchOptions), { code: 'EACCES' });
          await assert.rejects(callbackGlob(facade, 'escape/**/*.txt', matchOptions), { code: 'EACCES' });
          await assert.rejects(collect(facade.promises.glob('escape/**/*.txt', matchOptions)), { code: 'EACCES' });
        });
        checks.push('escaping workspace symlinks fail explicitly');
      }
      console.log('Plugin glob passed:\n- ' + checks.join('\n- '));
    } finally {
      await transport?.close();
      if (server) {
        const exited = once(server, 'exit');
        server.postMessage('close');
        await exited;
      }
      const resolved = path.resolve(root);
      const prefix = fixtureBase + path.sep + 'sugar-plugin-glob-';
      if (!resolved.startsWith(prefix)) throw Error('Unsafe glob fixture cleanup');
      fs.rmSync(resolved, { recursive: true, force: true });
    }
  }
  main().catch(error => { console.error(error); process.exitCode = 1; });
}
