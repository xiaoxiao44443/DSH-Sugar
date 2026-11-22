'use strict';

// File-system view exposed to plugin modules. The host keeps its original fs.
const path = require('node:path');
const { fileURLToPath } = require('node:url');
const { EventEmitter } = require('node:events');

exports.create = function create({ current, request, requestSync, hostFs = require('node:fs') }) {
  const fs = hostFs;
  const promises = Object.create(null);
  const facade = Object.create(null);
  const handles = new Map();
  let nextFd = 0x40000000;
  const constants = fs.constants;
  const chunkSize = 64 * 1024;
  const mutations = new Set(['writeFile','appendFile','mkdir','mkdtemp','rm','rmdir','unlink','rename','copyFile','cp','chmod','chown','utimes','truncate','symlink','link','write','writev','sync','datasync']);
  const supported = ['readFile','writeFile','appendFile','stat','lstat','readdir','realpath','access','mkdir','mkdtemp','rm','rmdir','unlink','rename','copyFile','cp','chmod','chown','utimes','truncate','readlink','symlink','link'];
  for (const name of Reflect.ownKeys(fs)) {
    if (name !== 'promises') Object.defineProperty(facade, name, { ...Object.getOwnPropertyDescriptor(fs,name), configurable:true });
  }
  for (const name of Reflect.ownKeys(fs.promises)) {
    Object.defineProperty(promises, name, { ...Object.getOwnPropertyDescriptor(fs.promises,name), configurable:true });
  }
  Object.defineProperty(facade, 'promises', { value:promises, enumerable:true });
  function set(object, name, value) { Object.defineProperty(object,name,{value,writable:true,configurable:true,enumerable:true}); }
  function error(code, syscall, name, detail) {
    const result = new Error(`${code}: ${detail || (code === 'ENOENT' ? 'current operation has no workspace' : code === 'EACCES' ? 'workspace access is not available for this operation' : code === 'EXDEV' ? 'operation crosses the workspace boundary' : 'operation is not supported in the plugin workspace')}${name === undefined ? '' : `, ${syscall} '${name}'`}`);
    Object.assign(result,{code,syscall});
    if (name !== undefined) result.path = name;
    return result;
  }
  function filename(value) {
    let name = value;
    if (value instanceof URL) {
      const posixName=fileURLToPath(value,{windows:false});
      name=inWorkspace(posixName)?posixName:fileURLToPath(value);
    }
    else if (Buffer.isBuffer(value)) name = value.toString();
    if (typeof name !== 'string') throw Object.assign(new TypeError('The path must be a string, Buffer, or file URL'),{code:'ERR_INVALID_ARG_TYPE'});
    if (name.includes('\0')) throw Object.assign(new TypeError('Path must not contain null bytes'),{code:'ERR_INVALID_ARG_VALUE'});
    // Host paths in Windows tests remain host paths; production runs on Linux.
    if (process.platform === 'win32' && /^(?:[a-z]:[\\/]|\\\\)/i.test(name)) return path.win32.normalize(name);
    return path.posix.resolve('/workspace', name);
  }
  function inWorkspace(name) { return name === '/workspace' || name.startsWith('/workspace/'); }
  function hostPath(name,method) {
    // A host alias must not reopen the coordinator's /workspace behind the view.
    // Resolve the nearest existing ancestor as output files may not exist yet.
    let candidate=name;
    for(let attempts=0;attempts<256;attempts++) {
      try {
        const real=fs.realpathSync(candidate);
        if(inWorkspace(real)) throw error('EACCES',method,name,'the host workspace is not exposed to plugins');
        return name;
      } catch(failure) {
        if(!['ENOENT','ENOTDIR'].includes(failure.code)) throw failure;
        // realpath cannot resolve a dangling alias. Inspect the link itself so
        // creating its future target cannot reach the hidden host workspace.
        let info;
        try {info=fs.lstatSync(candidate);} catch(cause) {if(!['ENOENT','ENOTDIR'].includes(cause.code))throw cause;}
        if(info?.isSymbolicLink()) {
          candidate=path.resolve(path.dirname(candidate),fs.readlinkSync(candidate));
          if(inWorkspace(candidate))throw error('EACCES',method,name,'the host workspace is not exposed to plugins');
          continue;
        }
        const parent=path.dirname(candidate);
        if(parent===candidate) return name;
        candidate=parent;
      }
    }
    throw error('ELOOP',method,name);
  }
  function endpoint(method, name, writable) {
    const ep = current();
    if (!ep) throw error(writable ? 'EACCES' : 'ENOENT',method,name);
    if (writable && ep.writable === false) throw error('EACCES',method,name,'this conversation has read-only file access');
    return ep;
  }
  function sameEndpoint(left, right) {
    return left === right || !!left && !!right && left.id !== undefined && left.id === right.id && left.envId !== undefined && left.envId === right.envId;
  }
  function descriptor(fd, method, writable) {
    const entry = handles.get(fd);
    if (!entry) {
      if (typeof fd === 'number' && fd >= 0x40000000) throw error('EBADF',method);
      return undefined;
    }
    if (!sameEndpoint(entry.ep,current())) throw error('EACCES',method,'/workspace','file handle belongs to another workspace');
    if (writable && current()?.writable === false) throw error('EACCES',method,'/workspace','this conversation has read-only file access');
    if (writable && !entry.writable) throw error('EBADF',method);
    return entry;
  }
  function writes(flags = 'r') {
    return typeof flags === 'number'
      ? !!(flags & (constants.O_WRONLY | constants.O_RDWR | constants.O_CREAT | constants.O_TRUNC | constants.O_APPEND))
      : /[wa+]/.test(flags);
  }
  function restore(value) {
    if (Array.isArray(value)) return value.map(restore);
    if (!value || typeof value !== 'object' || Buffer.isBuffer(value)) return value;
    if (value.$type === 'Stats' || value.$type === 'Dirent') {
      const result = Object.assign(Object.create(value.$type === 'Stats' ? fs.Stats.prototype : fs.Dirent.prototype), value);
      delete result.$type;
      const kind = result.kind;
      delete result.kind;
      for (const [method,type] of Object.entries({isFile:'file',isDirectory:'directory',isSymbolicLink:'symlink',isBlockDevice:'blockDevice',isCharacterDevice:'characterDevice',isFIFO:'fifo',isSocket:'socket'})) {
        Object.defineProperty(result,method,{value:()=>kind === type});
      }
      if (value.$type === 'Stats') for (const field of ['atime','mtime','ctime','birthtime']) {
        if (!(result[field] instanceof Date)) result[field] = new Date(Number(result[field+'Ms'] ?? result[field] ?? 0));
      }
      return result;
    }
    return value;
  }
  async function remote(ep, method, args, writable, handle) {
    return restore(await request(ep,{method,args,writable:ep.writable !== false,...handle === undefined ? {} : {handle}}));
  }
  function remoteSync(ep, method, args, writable, handle) {
    return restore(requestSync(ep,{method,args,writable:ep.writable !== false,...handle === undefined ? {} : {handle}}));
  }
  function callback(args) {
    const cb = args.pop();
    if (typeof cb !== 'function') throw Object.assign(new TypeError('The callback must be a function'),{code:'ERR_INVALID_ARG_TYPE'});
    return cb;
  }
  function withCallback(task, cb, result = value => [value]) {
    Promise.resolve(task).then(value=>cb(null,...result(value)),failure=>cb(failure));
  }
  function plan(method,args) {
    args = args.slice();
    if (typeof args[0] === 'number' && ['readFile','writeFile','appendFile','truncate','stat','chmod','chown','utimes'].includes(method)) {
      const entry = descriptor(args[0],method,mutations.has(method));
      return {args:args.slice(1),entry,raw:args};
    }
    const index = method === 'symlink' ? 1 : 0;
    args[index] = filename(args[index]);
    const name = args[index];
    const first = inWorkspace(name);
    if (method === 'readdir' && name === '/' && args[1]?.recursive) throw error('ENOTSUP',method,name,'recursive enumeration of the host root is not supported');
    if (['rename','link','copyFile','cp'].includes(method)) {
      args[1] = filename(args[1]);
      const second = inWorkspace(args[1]);
      if(!second) hostPath(args[1],method);
      if (first !== second) {
        if (method === 'copyFile') return {args,crossCopy:true};
        throw error('EXDEV',method,name);
      }
    }
    if(!first) hostPath(name,method);
    if(method==='cp' && name==='/') throw error('ENOTSUP',method,name,'copying the host root is not supported');
    if (method === 'symlink') {
      const target = Buffer.isBuffer(args[0]) ? args[0].toString() : args[0];
      if (typeof target !== 'string') throw Object.assign(new TypeError('The symlink target must be a string or Buffer'),{code:'ERR_INVALID_ARG_TYPE'});
      // Relative link contents are resolved by the destination filesystem.
      if (path.posix.isAbsolute(target) && inWorkspace(filename(target)) !== first) throw error('EXDEV',method,name);
      args[0] = target;
    }
    return {args,ep:first ? endpoint(method,name,mutations.has(method) || method==='access' && !!(args[1]&constants.W_OK)) : undefined,name};
  }
  function filterRoot(method, name, value) {
    if (method !== 'readdir' || name !== '/' || current()) return value;
    return value.filter(entry => {
      const name = Buffer.isBuffer(entry) ? entry.toString() : typeof entry === 'string' ? entry : Buffer.isBuffer(entry.name) ? entry.name.toString() : entry.name;
      return name !== 'workspace' && !name.startsWith('workspace/');
    });
  }
  async function call(method, originalArgs) {
    const p = plan(method,originalArgs);
    if (p.crossCopy) return copyAcross(...p.args);
    if (p.entry) return remote(p.entry.ep,method,p.args,mutations.has(method),p.entry.handle);
    if (p.raw) return new Promise((resolve,reject)=>fs[method](...p.raw,(err,result)=>err ? reject(err) : resolve(result)));
    const result = p.ep ? await remote(p.ep,method,p.args,mutations.has(method)) : await fs.promises[method](...p.args);
    return filterRoot(method,p.name,result);
  }
  function callSync(method, originalArgs) {
    const p = plan(method,originalArgs);
    if (p.crossCopy) return copyAcrossSync(...p.args);
    if (p.entry) return remoteSync(p.entry.ep,method,p.args,mutations.has(method),p.entry.handle);
    const result = p.raw ? fs[method+'Sync'](...p.raw) : p.ep ? remoteSync(p.ep,method,p.args,mutations.has(method)) : fs[method+'Sync'](...p.args);
    return filterRoot(method,p.name,result);
  }
  for (const method of supported) {
    set(promises,method,(...args)=>call(method,args));
    set(facade,method,(...args)=>{ const cb=callback(args); withCallback(call(method,args),cb); });
    set(facade,method+'Sync',(...args)=>callSync(method,args));
  }
  function localFd(ep,result,writable) {
    if (!result || typeof result.handle !== 'string') throw error('EIO','open','/workspace','invalid workspace file handle');
    const fd = nextFd++;
    handles.set(fd,{ep,handle:result.handle,writable});
    return fd;
  }
  async function open(name,flags='r',mode) {
    name=filename(name);
    if (!inWorkspace(name)) return new Promise((resolve,reject)=>fs.open(hostPath(name,'open'),flags,mode,(err,fd)=>err ? reject(err) : resolve(fd)));
    const writable=writes(flags), ep=endpoint('open',name,writable);
    return localFd(ep,await remote(ep,'open',[name,flags,mode],writable),writable);
  }
  function openSync(name,flags='r',mode) {
    name=filename(name);
    if (!inWorkspace(name)) return fs.openSync(hostPath(name,'open'),flags,mode);
    const writable=writes(flags),ep=endpoint('open',name,writable);
    return localFd(ep,remoteSync(ep,'open',[name,flags,mode],writable),writable);
  }
  async function fdCall(method,fd,args=[],writable=false) {
    const entry=descriptor(fd,method,writable);
    if (!entry) return new Promise((resolve,reject)=>fs[fdName(method)](fd,...args,(err,result)=>err ? reject(err) : resolve(result)));
    const result=await remote(entry.ep,method,args,writable,entry.handle);
    if (method === 'close') handles.delete(fd);
    return result;
  }
  function fdCallSync(method,fd,args=[],writable=false) {
    const entry=descriptor(fd,method,writable);
    if (!entry) return fs[fdName(method)+'Sync'](fd,...args);
    const result=remoteSync(entry.ep,method,args,writable,entry.handle);
    if (method === 'close') handles.delete(fd);
    return result;
  }
  function fdName(method) { return ({stat:'fstat',chmod:'fchmod',chown:'fchown',utimes:'futimes',truncate:'ftruncate',sync:'fsync',datasync:'fdatasync'})[method] || method; }
  set(facade,'open',(...args)=>{const cb=callback(args);withCallback(open(...args),cb);});
  set(facade,'openSync',openSync);
  set(facade,'close',(fd,cb)=>{if(typeof cb!=='function') throw new TypeError('A callback is required');withCallback(fdCall('close',fd),cb,()=>[]);});
  set(facade,'closeSync',fd=>fdCallSync('close',fd));
  for (const method of ['stat','chmod','chown','utimes','truncate','sync','datasync']) {
    const name=fdName(method), writable=method!=='stat';
    set(facade,name,(...args)=>{const cb=callback(args);const fd=args.shift();withCallback(fdCall(method,fd,args,writable),cb);});
    set(facade,name+'Sync',(fd,...args)=>fdCallSync(method,fd,args,writable));
  }
  function readOptions(buffer,offset,length,position) {
    if (buffer === undefined || !ArrayBuffer.isView(buffer)) {
      const options=buffer || {};
      buffer=options.buffer || Buffer.alloc(16384); offset=options.offset; length=options.length; position=options.position;
    } else if (offset && typeof offset === 'object') {
      ({offset,length,position}=offset);
    }
    offset ??= 0; length ??= buffer.byteLength-offset; position ??= null;
    if (!ArrayBuffer.isView(buffer) || !Number.isInteger(offset) || !Number.isInteger(length) || offset<0 || length<0 || offset+length>buffer.byteLength) throw Object.assign(new RangeError('Invalid read buffer range'),{code:'ERR_OUT_OF_RANGE'});
    return {buffer,offset,length,position};
  }
  function copyRead(result,options) {
    const bytes=Buffer.from(result.buffer);
    if (!Number.isInteger(result.bytesRead) || result.bytesRead<0 || result.bytesRead>options.length || bytes.length<result.bytesRead) throw error('EIO','read',undefined,'invalid workspace read result');
    bytes.copy(Buffer.from(options.buffer.buffer,options.buffer.byteOffset,options.buffer.byteLength),options.offset,0,result.bytesRead);
    return {bytesRead:result.bytesRead,buffer:options.buffer};
  }
  async function read(fd,...args) {
    const options=readOptions(...args),entry=descriptor(fd,'read',false);
    if (!entry) return new Promise((resolve,reject)=>fs.read(fd,options.buffer,options.offset,options.length,options.position,(err,n)=>err?reject(err):resolve({bytesRead:n,buffer:options.buffer})));
    return copyRead(await remote(entry.ep,'read',[options.length,options.position],false,entry.handle),options);
  }
  function readSync(fd,...args) {
    const options=readOptions(...args),entry=descriptor(fd,'read',false);
    if (!entry) return fs.readSync(fd,options.buffer,options.offset,options.length,options.position);
    return copyRead(remoteSync(entry.ep,'read',[options.length,options.position],false,entry.handle),options).bytesRead;
  }
  function writeOptions(data,offset,length,position) {
    if (typeof data==='string') return {buffer:Buffer.from(data,typeof length==='string'?length:'utf8'),position:offset??null,original:data};
    if (!ArrayBuffer.isView(data)) throw Object.assign(new TypeError('Expected a string or buffer'),{code:'ERR_INVALID_ARG_TYPE'});
    if (offset && typeof offset==='object') ({offset,length,position}=offset);
    offset??=0;length??=data.byteLength-offset;position??=null;
    if (!Number.isInteger(offset)||!Number.isInteger(length)||offset<0||length<0||offset+length>data.byteLength) throw Object.assign(new RangeError('Invalid write buffer range'),{code:'ERR_OUT_OF_RANGE'});
    return {buffer:Buffer.from(data.buffer,data.byteOffset+offset,length),position,original:data};
  }
  async function write(fd,...args) {
    const options=writeOptions(...args),entry=descriptor(fd,'write',true);
    if (!entry) return new Promise((resolve,reject)=>fs.write(fd,options.buffer,0,options.buffer.length,options.position,(err,n)=>err?reject(err):resolve({bytesWritten:n,buffer:options.original})));
    const result=await remote(entry.ep,'write',[options.buffer,options.position],true,entry.handle);
    return {bytesWritten:result.bytesWritten,buffer:options.original};
  }
  function writeSync(fd,...args) {
    const options=writeOptions(...args),entry=descriptor(fd,'write',true);
    return entry ? remoteSync(entry.ep,'write',[options.buffer,options.position],true,entry.handle).bytesWritten : fs.writeSync(fd,options.buffer,0,options.buffer.length,options.position);
  }
  set(facade,'read',(...args)=>{const cb=callback(args);withCallback(read(...args),cb,result=>[result.bytesRead,result.buffer]);});
  set(facade,'readSync',readSync);
  set(facade,'write',(...args)=>{const cb=callback(args);withCallback(write(...args),cb,result=>[result.bytesWritten,result.buffer]);});
  set(facade,'writeSync',writeSync);
  async function vector(method,fd,buffers,position=null) {
    if (!Array.isArray(buffers) || buffers.some(buffer=>!ArrayBuffer.isView(buffer))) throw new TypeError('Expected an array of buffers');
    const writable=method==='writev',entry=descriptor(fd,method,writable);
    if (!entry) return new Promise((resolve,reject)=>fs[method](fd,buffers,position,(err,n)=>err?reject(err):resolve({[writable?'bytesWritten':'bytesRead']:n,buffers})));
    const result=await remote(entry.ep,method,[writable?buffers.map(b=>Buffer.from(b.buffer,b.byteOffset,b.byteLength)):buffers.map(b=>b.byteLength),position],writable,entry.handle);
    if (!writable) copyVector(result,buffers);
    return {[writable?'bytesWritten':'bytesRead']:result[writable?'bytesWritten':'bytesRead'],buffers};
  }
  function vectorSync(method,fd,buffers,position=null) {
    if (!Array.isArray(buffers) || buffers.some(buffer=>!ArrayBuffer.isView(buffer))) throw new TypeError('Expected an array of buffers');
    const writable=method==='writev',entry=descriptor(fd,method,writable);
    if (!entry) return fs[method+'Sync'](fd,buffers,position);
    const result=remoteSync(entry.ep,method,[writable?buffers.map(b=>Buffer.from(b.buffer,b.byteOffset,b.byteLength)):buffers.map(b=>b.byteLength),position],writable,entry.handle);
    if (!writable) copyVector(result,buffers);
    return result[writable?'bytesWritten':'bytesRead'];
  }
  function copyVector(result,buffers) {
    let remaining=result.bytesRead;
    if(!Number.isSafeInteger(remaining)||remaining<0||remaining>buffers.reduce((sum,b)=>sum+b.byteLength,0)||!Array.isArray(result.buffers))throw error('EIO','readv',undefined,'invalid workspace read result');
    for(let i=0;i<buffers.length&&remaining>0;i++) {
      const count=Math.min(remaining,buffers[i].byteLength),bytes=Buffer.from(result.buffers[i]);
      if(bytes.length<count)throw error('EIO','readv',undefined,'invalid workspace read result');
      bytes.copy(Buffer.from(buffers[i].buffer,buffers[i].byteOffset,buffers[i].byteLength),0,0,count);remaining-=count;
    }
  }
  for(const method of ['readv','writev']) {
    set(facade,method,(...args)=>{const cb=callback(args);withCallback(vector(method,...args),cb,result=>[result[method==='writev'?'bytesWritten':'bytesRead'],result.buffers]);});
    set(facade,method+'Sync',(...args)=>vectorSync(method,...args));
  }
  set(facade,'existsSync',name=>{try{facade.accessSync(name);return true;}catch{return false;}});
  set(facade,'exists',(name,cb)=>{if(typeof cb!=='function') throw new TypeError('A callback is required');call('access',[name]).then(()=>cb(true),()=>cb(false));});
  set(facade,'createReadStream',(name,options={})=>fs.createReadStream(name,{...typeof options==='string'?{encoding:options}:options,fs:facade}));
  set(facade,'createWriteStream',(name,options={})=>fs.createWriteStream(name,{...typeof options==='string'?{encoding:options}:options,fs:facade}));
  facade.realpath.native=facade.realpath;
  facade.realpathSync.native=facade.realpathSync;

  async function copyAcross(source,destination,flags=0) {
    if(flags & constants.COPYFILE_FICLONE_FORCE) throw error('ENOTSUP','copyFile',source);
    // Opening the source first avoids creating a destination for a missing source.
    const sourceFd=await open(source,'r');
    let targetFd;
    try {
      const info=await fdCall('stat',sourceFd);
      if(!info.isFile()) throw error('EISDIR','copyFile',source);
      targetFd=await open(destination,flags & constants.COPYFILE_EXCL?'wx':'w',info.mode);
      const bytes=Buffer.allocUnsafe(chunkSize);
      for(;;) {
        const result=await read(sourceFd,bytes,0,bytes.length,null);
        if(!result.bytesRead) break;
        for(let offset=0;offset<result.bytesRead;) {
          const written=await write(targetFd,bytes,offset,result.bytesRead-offset,null);
          if(!written.bytesWritten) throw error('EIO','copyFile',destination);
          offset+=written.bytesWritten;
        }
      }
    } finally {
      try {if(targetFd!==undefined) await fdCall('close',targetFd);} finally {await fdCall('close',sourceFd);}
    }
  }
  function copyAcrossSync(source,destination,flags=0) {
    if(flags & constants.COPYFILE_FICLONE_FORCE) throw error('ENOTSUP','copyFile',source);
    const sourceFd=openSync(source,'r');let targetFd;
    try {
      const info=fdCallSync('stat',sourceFd);
      if(!info.isFile()) throw error('EISDIR','copyFile',source);
      targetFd=openSync(destination,flags & constants.COPYFILE_EXCL?'wx':'w',info.mode);
      const bytes=Buffer.allocUnsafe(chunkSize);
      for(;;) {
        const count=readSync(sourceFd,bytes,0,bytes.length,null);if(!count)break;
        for(let offset=0;offset<count;) {const written=writeSync(targetFd,bytes,offset,count-offset,null);if(!written)throw error('EIO','copyFile',destination);offset+=written;}
      }
    } finally {try{if(targetFd!==undefined)fdCallSync('close',targetFd);}finally{fdCallSync('close',sourceFd);}}
  }

  class FileHandle extends EventEmitter {
    constructor(fd) {super();this.fd=fd;}
    async close() {if(this.fd===-1)return;await fdCall('close',this.fd);this.fd=-1;this.emit('close');}
    read(...args) {return read(this.fd,...args);}
    write(...args) {return write(this.fd,...args);}
    readv(...args) {return vector('readv',this.fd,...args);}
    writev(...args) {return vector('writev',this.fd,...args);}
    readFile(...args) {return fdCall('readFile',this.fd,args);}
    writeFile(...args) {return fdCall('writeFile',this.fd,args,true);}
    appendFile(...args) {return fdCall('appendFile',this.fd,args,true);}
    stat(...args) {return fdCall('stat',this.fd,args);}
    chmod(...args) {return fdCall('chmod',this.fd,args,true);}
    chown(...args) {return fdCall('chown',this.fd,args,true);}
    utimes(...args) {return fdCall('utimes',this.fd,args,true);}
    truncate(...args) {return fdCall('truncate',this.fd,args,true);}
    sync() {return fdCall('sync',this.fd,[],true);}
    datasync() {return fdCall('datasync',this.fd,[],true);}
    createReadStream(options={}) {const stream=facade.createReadStream('/workspace',{...options,fd:this.fd});if(options.autoClose!==false)stream.once('close',()=>{this.fd=-1;this.emit('close');});return stream;}
    createWriteStream(options={}) {const stream=facade.createWriteStream('/workspace',{...options,fd:this.fd});if(options.autoClose!==false)stream.once('close',()=>{this.fd=-1;this.emit('close');});return stream;}
    [Symbol.asyncDispose]() {return this.close();}
  }
  set(promises,'open',async(name,flags='r',mode)=>{
    const resolved=filename(name);
    if(!inWorkspace(resolved)) return fs.promises.open(hostPath(resolved,'open'),flags,mode);
    return new FileHandle(await open(resolved,flags,mode));
  });
  for(const method of ['readFile','writeFile','appendFile']) {
    set(promises,method,(name,...args)=>name instanceof FileHandle ? name[method](...args) : call(method,[name,...args]));
  }

  // Extra fs entry points must never silently fall back to the host workspace.
  const exempt=new Set([...supported,...supported.map(name=>name+'Sync'),'open','openSync','close','closeSync','read','readSync','write','writeSync','readv','readvSync','writev','writevSync','exists','existsSync','createReadStream','createWriteStream','fstat','fstatSync','fchmod','fchmodSync','fchown','fchownSync','futimes','futimesSync','ftruncate','ftruncateSync','fsync','fsyncSync','fdatasync','fdatasyncSync','Stats','Dirent','ReadStream','WriteStream','FileReadStream','FileWriteStream']);
  function unsupported(method,args) {
    const name=filename(args[0]);
    if(inWorkspace(name)) {endpoint(method,name,/^(?:lchmod|lchown|lutimes|mkdir|rm|rename)/.test(method));throw error('ENOTSUP',method,name);}
    if(method.startsWith('glob') || name==='/' && ['opendir','opendirSync','watch','watchFile'].includes(method)) throw error('ENOTSUP',method,name);
    args[0]=hostPath(name,method);return args;
  }
  for(const method of Object.keys(fs)) if(typeof fs[method]==='function'&&!exempt.has(method)) {
    set(facade,method,(...args)=>{
      const cb=typeof args.at(-1)==='function'?args.at(-1):undefined;
      try{return fs[method](...unsupported(method,args));}
      catch(failure){if(cb&&!method.endsWith('Sync')&&!['watch','watchFile','unwatchFile'].includes(method)){queueMicrotask(()=>cb(failure));return;}throw failure;}
    });
  }
  for(const method of Object.keys(fs.promises)) if(typeof fs.promises[method]==='function'&&!supported.includes(method)&&method!=='open') {
    if(method==='watch'||method==='glob') set(promises,method,async function*(...args){yield* fs.promises[method](...unsupported(method,args));});
    else set(promises,method,async(...args)=>fs.promises[method](...unsupported(method,args)));
  }
  // Constructor aliases need the same injected implementation as factory calls.
  function ReadStream(name,options) {return facade.createReadStream(name,options);}
  function WriteStream(name,options) {return facade.createWriteStream(name,options);}
  ReadStream.prototype=fs.ReadStream.prototype;WriteStream.prototype=fs.WriteStream.prototype;
  set(facade,'ReadStream',ReadStream);set(facade,'FileReadStream',ReadStream);
  set(facade,'WriteStream',WriteStream);set(facade,'FileWriteStream',WriteStream);
  const directories = require('./plugin-directories.cjs').create({
    fs,current,filename,inWorkspace,hostPath,endpoint,sameEndpoint,remote,remoteSync,error
  });
  set(facade,'Dir',directories.Dir);
  set(facade,'opendir',directories.opendir);
  set(facade,'opendirSync',directories.opendirSync);
  set(promises,'opendir',directories.promisesOpendir);
  const glob = require('./plugin-glob.cjs').create({fs:facade,current,sameEndpoint,filename,inWorkspace,error});
  set(facade,'glob',glob.glob);
  set(facade,'globSync',glob.globSync);
  set(promises,'glob',glob.promisesGlob);
  return facade;
};
