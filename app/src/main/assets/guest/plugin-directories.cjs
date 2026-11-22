'use strict';

// Iterative directories use real directory handles, rather than caching readdir's
// entire result. Resolve every descended path through the plugin's file view.
const path = require('node:path');
const {fileURLToPath} = require('node:url');

exports.create = ({fs,current,filename,inWorkspace,hostPath,endpoint,sameEndpoint,remote,remoteSync,error}) => {
  const token = Symbol('plugin directory');
  function options(value) {
    if (value == null) value = {};
    if (typeof value === 'string') value = {encoding:value};
    if (typeof value !== 'object' || Array.isArray(value))
      throw Object.assign(new TypeError('The options argument must be an object or string'),{code:'ERR_INVALID_ARG_TYPE'});
    const encoding = value.encoding ?? 'utf8', bufferSize = value.bufferSize === undefined ? 32 : value.bufferSize;
    if (encoding !== 'buffer' && (typeof encoding !== 'string' || !Buffer.isEncoding(encoding)))
      throw Object.assign(new TypeError('Invalid directory encoding'),{code:'ERR_INVALID_ARG_VALUE'});
    if (typeof bufferSize !== 'number')
      throw Object.assign(new TypeError('Directory bufferSize must be a number'),{code:'ERR_INVALID_ARG_TYPE'});
    if (!Number.isInteger(bufferSize) || bufferSize < 1 || bufferSize > 0xffffffff)
      throw Object.assign(new RangeError('Directory bufferSize must be between 1 and 4294967295'),{code:'ERR_OUT_OF_RANGE'});
    return {encoding,bufferSize,recursive:!!value.recursive};
  }
  function publicPath(value) {
    if (value instanceof URL) {
      const posix=fileURLToPath(value,{windows:false});
      return inWorkspace(posix) ? posix : fileURLToPath(value);
    }
    return Buffer.isBuffer(value) ? Buffer.from(value) : value;
  }
  function dirFailure(code) {
    return Object.assign(new Error(code === 'ERR_DIR_CLOSED' ? 'Directory handle was closed' :
      'Cannot perform a synchronous directory operation while an asynchronous operation is pending'),{code});
  }
  function callback(value) {
    if (typeof value !== 'function') throw Object.assign(new TypeError('The callback must be a function'),{code:'ERR_INVALID_ARG_TYPE'});
    return value;
  }
  function deliver(task, cb, closing = false) {
    if (cb === undefined) return task;
    callback(cb);
    task.then(value=>closing ? cb(null) : cb(null,value),err=>cb(err));
  }
  function destination(name) {
    name = filename(name);
    return {name,ep:inWorkspace(name) ? endpoint('opendir',name,false) : undefined};
  }
  async function openOne(name, opts) {
    const location = destination(name);
    if (location.ep) {
      const result = await remote(location.ep,'opendir',[location.name,{...opts,encoding:'buffer',recursive:false}],false);
      if (typeof result?.handle !== 'string') throw error('EIO','opendir',location.name,'invalid workspace directory handle');
      return {...location,handle:result.handle};
    }
    return {...location,dir:await fs.promises.opendir(hostPath(location.name,'opendir'),{...opts,encoding:'buffer',recursive:false})};
  }
  function openOneSync(name, opts) {
    const location = destination(name);
    if (location.ep) {
      const result = remoteSync(location.ep,'opendir',[location.name,{...opts,encoding:'buffer',recursive:false}],false);
      if (typeof result?.handle !== 'string') throw error('EIO','opendir',location.name,'invalid workspace directory handle');
      return {...location,handle:result.handle};
    }
    return {...location,dir:fs.opendirSync(hostPath(location.name,'opendir'),{...opts,encoding:'buffer',recursive:false})};
  }
  const readOne = entry => entry.ep ? remote(entry.ep,'readDir',[],false,entry.handle) : entry.dir.read();
  const readOneSync = entry => entry.ep ? remoteSync(entry.ep,'readDir',[],false,entry.handle) : entry.dir.readSync();
  const closeOne = entry => entry.ep ? remote(entry.ep,'closeDir',[],false,entry.handle) : entry.dir.close();
  const closeOneSync = entry => entry.ep ? remoteSync(entry.ep,'closeDir',[],false,entry.handle) : entry.dir.closeSync();

  class Dir {
    #scope; #options; #stack; #pendingPath; #closed = false; #closing = false; #pending = 0; #tail = Promise.resolve();
    constructor(key, entry, opts, name, scope) {
      if (key !== token) throw Object.assign(new TypeError('Use opendir() to create a directory'),{code:'ERR_INVALID_ARG_TYPE'});
      this.#scope=scope; this.#options=opts; this.#stack=[entry];
      Object.defineProperty(this,'path',{enumerable:true,get:()=>name});
    }
    #check() {
      if (!sameEndpoint(this.#scope,current())) throw error('EACCES','readdir','/workspace','directory handle belongs to another workspace');
      if (this.#closed || this.#closing) throw dirFailure('ERR_DIR_CLOSED');
    }
    #syncCheck() {this.#check();if(this.#pending)throw dirFailure('ERR_DIR_CONCURRENT_OPERATION');}
    #entry(item, parent) {
      // Keep the real UTF-8 path separate from a caller-selected presentation
      // encoding (latin1/hex/etc.) when descending into a directory.
      const bytes = Buffer.isBuffer(item.name) ? item.name : Buffer.from(item.name);
      const name = bytes.toString();
      item.name = this.#options.encoding === 'buffer' ? bytes : bytes.toString(this.#options.encoding);
      item.parentPath = Buffer.isBuffer(this.path) ? Buffer.from(parent.name) : parent.name;
      if (Object.hasOwn(item,'path')) item.path=item.parentPath;
      if (parent.name === '/' && !this.#scope && name === 'workspace') return false;
      if (this.#options.recursive && item.isDirectory() && !item.isSymbolicLink()) {
        this.#pendingPath = inWorkspace(parent.name) || parent.name.startsWith('/')
          ? path.posix.join(parent.name,name) : path.join(parent.name,name);
      }
      return true;
    }
    async #read() {
      if (this.#pendingPath) {
        this.#stack.push(await openOne(this.#pendingPath,this.#options));
        this.#pendingPath=undefined;
      }
      for (;;) {
        const current=this.#stack.at(-1), item=await readOne(current);
        if (item !== null) {if(this.#entry(item,current))return item;continue;}
        if(this.#stack.length===1)return null;
        this.#stack.pop(); await closeOne(current);
      }
    }
    #readSync() {
      if (this.#pendingPath) {
        this.#stack.push(openOneSync(this.#pendingPath,this.#options));
        this.#pendingPath=undefined;
      }
      for (;;) {
        const current=this.#stack.at(-1), item=readOneSync(current);
        if(item!==null){if(this.#entry(item,current))return item;continue;}
        if(this.#stack.length===1)return null;
        this.#stack.pop(); closeOneSync(current);
      }
    }
    read(cb) {
      if(cb!==undefined)callback(cb);
      let task;
      try {
        this.#check();this.#pending++;
        task=this.#tail.then(()=>this.#read());
        this.#tail=task.catch(()=>{});
        task=task.finally(()=>{this.#pending--;});
      } catch(err) {task=Promise.reject(err);}
      return deliver(task,cb);
    }
    readSync(){this.#syncCheck();return this.#readSync();}
    close(cb) {
      if(cb!==undefined)callback(cb);
      let task;
      try {
        this.#check();this.#closing=true;
        task=this.#tail.then(async()=>{
          let first;
          for(const entry of this.#stack.reverse())try{await closeOne(entry);}catch(err){first??=err;}
          this.#stack=[];this.#closed=true;this.#closing=false;
          if(first)throw first;
        });
        this.#tail=task.catch(()=>{});
      } catch(err) {task=Promise.reject(err);}
      return deliver(task,cb,true);
    }
    closeSync(){
      this.#syncCheck();let first;
      for(const entry of this.#stack.reverse())try{closeOneSync(entry);}catch(err){first??=err;}
      this.#stack=[];this.#closed=true;
      if(first)throw first;
    }
    async *[Symbol.asyncIterator](){
      try {for(;;){const item=await this.read();if(item===null)return;yield item;}}
      finally {if(!this.#closed&&!this.#closing)await this.close();}
    }
    [Symbol.asyncDispose](){return this.#closed ? Promise.resolve() : this.close();}
    [Symbol.dispose](){if(!this.#closed)this.closeSync();}
  }
  Object.setPrototypeOf(Dir.prototype,fs.Dir.prototype);
  async function promisesOpendir(name, value) {
    const opts=options(value),resolved=filename(name),scope=current();
    return new Dir(token,await openOne(resolved,opts),opts,publicPath(name),scope);
  }
  function opendirSync(name,value) {
    const opts=options(value),resolved=filename(name),scope=current();
    return new Dir(token,openOneSync(resolved,opts),opts,publicPath(name),scope);
  }
  function opendir(name,value,cb) {
    if(typeof value==='function'){cb=value;value=undefined;}
    callback(cb);deliver(promisesOpendir(name,value),cb);
  }
  return {Dir,opendir,opendirSync,promisesOpendir};
};
