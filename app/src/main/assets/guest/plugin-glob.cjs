'use strict';

// Match in the plugin's file view. The official engine keeps Node's pattern and
// pruning semantics; only its filesystem access is redirected to this facade.
const path = require('node:path');
const {create: createEngine} = require('./plugin-glob-engine.cjs');

exports.create = ({fs,current,sameEndpoint,filename,inWorkspace,error}) => {
  function prepare(pattern, options, scope) {
    const guard = () => {
      if (!sameEndpoint(scope,current())) throw error('EACCES','glob','/workspace','glob iterator belongs to another workspace');
    };
    guard();
    if (options === undefined) options = {};
    if (!options || typeof options !== 'object' || Array.isArray(options))
      throw Object.assign(new TypeError('The options argument must be an object'),{code:'ERR_INVALID_ARG_TYPE'});
    const cwd = filename(options.cwd ?? '/workspace');
    const patterns = Array.isArray(pattern) ? pattern : [pattern];
    const windowsAbsolute = value => typeof value === 'string' && /^(?:[a-z]:[\\/]|\\\\)/i.test(value);
    const windowsPattern = options.cwd == null && patterns.length > 0 && patterns.every(windowsAbsolute);
    const paths = process.platform === 'win32' && (windowsAbsolute(cwd) || windowsPattern) ? path.win32 : path.posix;
    const checkPath = name => {
      guard();
      if (!scope && inWorkspace(filename(name))) {
        // A missing workspace is a scope error, not a pattern with no matches.
        throw Object.assign(error('ENOENT','glob',name),{sugarWorkspaceDenied:true});
      }
    };
    const access = {Dirent:fs.Dirent,promises:{}};
    for (const method of ['lstat','readdir','realpath','stat']) {
      access[method+'Sync'] = (...args) => {
        checkPath(args[0]);
        const value=fs[method+'Sync'](...args);
        guard();return value;
      };
      access.promises[method] = async (...args) => {
        checkPath(args[0]);
        const value=await fs.promises[method](...args);
        guard();return value;
      };
    }
    const {Glob}=createEngine(access,paths);
    return {engine:new Glob(pattern,{...options,cwd}),guard};
  }
  function globSync(pattern, options) {
    const {engine,guard}=prepare(pattern,options,current());
    const result=engine.globSync();guard();return result;
  }
  function iterate({engine,guard}) {
    return (async function* () {
      // Guard each next(), including cache-only yields, so moving an iterator to
      // a different session cannot return the original workspace's filenames.
      const iterator=engine.glob();
      try {
        for (;;) {
          guard();const item=await iterator.next();guard();
          if(item.done)return;
          yield item.value;
        }
      } finally {await iterator.return?.();}
    })();
  }
  function promisesGlob(pattern, options) {
    const scope=current();
    return (async function* () {
      yield* iterate(prepare(pattern,options,scope));
    })();
  }
  function glob(pattern, options, callback) {
    if (typeof options === 'function') {callback=options;options=undefined;}
    if (typeof callback !== 'function')
      throw Object.assign(new TypeError('The callback must be a function'),{code:'ERR_INVALID_ARG_TYPE'});
    // Node's callback API validates arguments synchronously, before returning.
    const iterator=iterate(prepare(pattern,options,current()));
    (async()=>{const results=[];for await(const item of iterator)results.push(item);return results;})()
      .then(result=>callback(null,result),failure=>callback(failure));
  }
  return {glob,globSync,promisesGlob};
};
