'use strict';
const {fileURLToPath}=require('node:url');
// Upgrade fixtures inspect Linux guest URLs on Windows. Keep real Windows
// drive/UNC paths native for require.cache, but parse guest URLs as POSIX paths.
function modulePath(url){
  const value=new URL(url);
  const windows=process.platform==='win32' && (value.hostname!==''||/^\/[a-z]:\//i.test(value.pathname));
  return fileURLToPath(value,{windows});
}

// A compatibility view for installed Host plugin code. DSH internals keep their
// original builtins: their registry and picker intentionally use the control fs.
// This is not a hostile-code boundary (native syscalls are outside Node's fs API).
exports.create=({current,transport,globalEndpoint,home='/home/sugar/.dsh',pluginRoots=[]})=>{
  const files=require('./plugin-files.cjs').create({current,...transport});
  const children=require('./plugin-process.cjs').create({current,globalEndpoint});
  const imported=new Set();
  const hostLoaded=new Set();
  const key=Symbol.for('dsh.sugar.pluginBuiltins');
  const views={fs:files,promises:files.promises,child_process:children,
    worker_threads:{...require('node:worker_threads'),Worker:class {
      constructor(){throw Object.assign(Error('Plugin worker_threads cannot inherit a project filesystem; use a project subprocess'),{code:'ENOTSUP'});}
    }}};
  Object.defineProperty(globalThis,key,{value:views,configurable:true});
  function privateModule(url){
    if(!url?.startsWith('file:'))return false;
    const file=modulePath(url).replaceAll('\\','/');
    return file.startsWith(home+'/')||pluginRoots.some(root=>file.startsWith(root+'/'));
  }
  function external(url){return privateModule(url)||imported.has(url);}
  const identity=url=>modulePath(url).replaceAll('\\','/');
  function sharedDependencyError(url){
    return Object.assign(Error('This dependency is already loaded by the DSH host and cannot inherit a plugin workspace. Install a private copy inside the plugin package: '+identity(url)),{code:'ENOTSUP'});
  }
  function hostDependency(url){
    if(!url.startsWith('file:'))return true;
    const file=modulePath(url).replaceAll('\\','/');
    return file.startsWith('/opt/sugar/')||file.startsWith('/opt/dsh/node_modules/@deepseek-ai/')||
      /\/node_modules\/(?:cordis|cordis-plugin-[^/]+|@cordisjs\/[^/]+)(?:\/|$)/.test(file);
  }
  const names=new Map([['fs','fs'],['fs/promises','promises'],['child_process','child_process'],['worker_threads','worker_threads']]);
  return {
    views,
    resolve(specifier,context,next){
      if(external(context.parentURL)){
        const name=names.get(specifier.replace(/^node:/,''));
        if(name)return {url:'sugar-plugin:'+name,shortCircuit:true};
        const result=next(specifier,context);
        if(!hostDependency(result.url)){
          // CJS ignores an ESM URL query for its require.cache identity. Merely
          // marking a cached dependency cannot replace its captured host fs.
          // Private package modules are classified from their path from the start;
          // SDK modules deliberately remain host services. Only shared external
          // dependencies need this fail-closed check.
          if(!privateModule(result.url)&&!imported.has(result.url)&&
              (hostLoaded.has(identity(result.url))||require.cache[modulePath(result.url)]))throw sharedDependencyError(result.url);
          imported.add(result.url);
        }
        return result;
      }
      return next(specifier,context);
    },
    load(url,context,next){
      if(!url.startsWith('sugar-plugin:')){
        if(url.startsWith('file:')&&!external(url)&&!hostDependency(url))hostLoaded.add(identity(url));
        return next(url,context);
      }
      const name=url.slice('sugar-plugin:'.length),value=views[name];
      if(!value)throw Error('Unknown plugin builtin');
      const exports=Object.keys(value).filter(key=>/^[A-Za-z_$][\w$]*$/.test(key)&&key!=='default');
      const source=`const value=globalThis[Symbol.for('dsh.sugar.pluginBuiltins')][${JSON.stringify(name)}];\n`+
        exports.map(key=>`export const ${key}=value[${JSON.stringify(key)}];`).join('\n')+
        '\nexport default value; export {value as "module.exports"};';
      return {format:'module',source,shortCircuit:true};
    }
  };
};
