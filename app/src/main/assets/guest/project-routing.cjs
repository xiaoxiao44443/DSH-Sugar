'use strict';

const fs = require('node:fs');
const path = require('node:path').posix;
const { fileURLToPath } = require('node:url');
const { AsyncLocalStorage } = require('node:async_hooks');
const { registerHooks } = require('node:module');
const { createHash } = require('node:crypto');
const { isMainThread } = require('node:worker_threads');
const { Readable } = require('node:stream');
if (!isMainThread || process.argv[1] !== '/opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js') return;

const configPath = '/opt/sugar/workspace-bridge.json';
const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
config.token = process.env.SUGAR_MANAGEMENT_TOKEN;
delete process.env.SUGAR_MANAGEMENT_TOKEN;
fs.unlinkSync(configPath);
const local = /^http:\/\/127\.0\.0\.1:[1-9]\d{0,4}$/;
if (!local.test(new URL(config.url).origin) || new URL(config.url).username || new URL(config.url).password || !config.token) {
  throw Error('Invalid workspace coordinator configuration');
}
const active = new AsyncLocalStorage();
const pluginExecution = new AsyncLocalStorage();
const passiveWatch = new AsyncLocalStorage();
const deviceOwner = new AsyncLocalStorage();
const mobileBindings = new Map();
const {currentTurnId} = require('./sugar-user-turn.cjs');
let hasReadAdbGuide;
let rootPolicy;
const adbCli = require('./adb-cli-sessions.cjs').create(management, (owner, project, needGuide, ui) => {
  const record = binding(owner.agent.session.header.cwd);
  pin(owner.agent.id, record);
  if (record.id !== project.id || record.path !== project.root) throw Error('此终端的原工作区已移除。');
  if (needGuide && !owner.manual && !hasReadAdbGuide?.(owner.agent.session))
    throw Error('请先在本对话调用 skill({name:"android-adb"}) 阅读 ADB 命令说明。');
  if (ui && !owner.manual) return {turn:currentTurnId(owner.agent.session)};
}, (owner, project, command, signal) => {
  if (!rootPolicy) throw Error('Root 会话权限服务未就绪。');
  return rootPolicy(owner.agent, command, signal, owner.manual === true);
});
const opening = new Map();
const latestWorker = new Map();
const sessionPins = new Map();
const routingState = '/home/sugar/.sugar-project-state';
const pinDir = path.join(routingState, 'sugar-session-projects');
const {isWorkspaceRoot} = require('./workspace-root.cjs');
let registry;
let pickerServer;
let petReplies;
let sessionStorage;

class ManagementError extends Error {}
async function management(body, signal) {
  const response = await fetch(config.url, { method: 'POST', redirect: 'error', signal,
    headers: { Authorization: 'Bearer ' + config.token, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const envelope = await response.json();
  if (!response.ok || !envelope.ok) {
    if (envelope.error?.message) throw new ManagementError(envelope.error.message);
    throw Error('Project coordinator unavailable');
  }
  return envelope.result;
}
function worker(record) {
  const pending = opening.get(record.id);
  if (pending) return pending;
  const task = management({ op: 'worker', id: record.id, root: record.path }).then(result => {
    if (!local.test(result.url) || !result.token || !result.envId || result.id !== record.id ||
        result.root !== record.path) throw Error('Invalid project worker binding');
    const ep = { ...result, root: record.path, id: record.id };
    latestWorker.set(ep.id, ep);
    return ep;
  }).finally(() => opening.delete(record.id));
  opening.set(record.id, task);
  return task;
}
function registeredRoot(canonical) {
  const records = registry.list();
  const exact = records.find(item => item.path === canonical);
  if (exact) return exact;
  const owners = records.filter(item => canonical.startsWith(item.path + '/'));
  return owners.length === 1 ? owners[0] : undefined;
}
function binding(root) {
  if (!registry) throw Error('Workspace registry is not ready');
  if (typeof root !== 'string' || !path.isAbsolute(root)) throw Error('Missing original workspace');
  const owner = registeredRoot(path.resolve(root));
  if (!owner) throw Error('The original workspace is unavailable or ambiguous.');
  return owner;
}
function pin(sessionId, record, requireMatch = true) {
  if (!sessionId) throw Error('Missing original session identity');
  let id = sessionPins.get(sessionId);
  if (!id) {
    const file = path.join(pinDir, createHash('sha256').update(sessionId).digest('hex'));
    fs.mkdirSync(pinDir,{recursive:true,mode:0o700});
    try { id = fs.readFileSync(file, 'utf8'); }
    catch(error) {
      if (error.code !== 'ENOENT') throw error;
      const staging = file + '.tmp';
      fs.writeFileSync(staging, record.id, {mode:0o600});
      fs.renameSync(staging, file);
      id = record.id;
    }
    sessionPins.set(sessionId,id);
  }
  if (requireMatch && id !== record.id) throw Error('The original project for this session was removed');
}
let registryQueue = Promise.resolve();
let firstRegistrySent = false;
let registryRetry;
let retryDelay = 1000;

function syncRegistry(firstSnapshotOnly = false, settledRetainedId) {
  // A startup retry and an ordinary workspace change must publish snapshots in order.
  registryQueue = registryQueue.then(async () => {
    if (!registry || (firstSnapshotOnly && firstRegistrySent)) return;
    try {
      // Tuples keep dated registry snapshots within the existing management request budget.
      const items = registry.list().map(({id,path,title,createdAt}) => [id,path,title,Date.parse(createdAt)]);
      await management({op:'registry',items,settledRetainedId});
      firstRegistrySent = true;
      clearTimeout(registryRetry);
      registryRetry = undefined;
    } catch {
      if (!firstSnapshotOnly) console.warn('DSH Sugar: workspace registrations will be synchronized again');
      if (!registryRetry) {
        registryRetry = setTimeout(() => {
          registryRetry = undefined;
          syncRegistry(false, settledRetainedId);
        }, retryDelay);
        registryRetry.unref();
        retryDelay = Math.min(retryDelay * 2, 30000);
      }
    }
  });
  return registryQueue;
}
const cleanupDir = path.join(routingState, 'sugar-project-removals');
function cleanupFile(id) {
  if (!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(id)) throw Error('Invalid stored workspace identity');
  return path.join(cleanupDir,id+'.json');
}
async function cleanup(record) {
  adbCli.revokeProject(record.id);
  await management({op:'delete',id:record.id,root:record.path,title:record.title});
  latestWorker.delete(record.id);
  await fs.promises.rm(cleanupFile(record.id),{force:true});
}
async function setRegistry(value) {
  registry = value;
  await syncRegistry();
  if (!pickerServer) {
    pickerServer = require('./picker-folders.cjs').start(() => registry, management, request => {
      if (!petReplies) throw Error('桌宠回复服务尚未就绪。');
      return petReplies(request);
    }, request => {
      if (!sessionStorage) throw Error('会话管理尚未就绪。');
      return sessionStorage(request);
    }).catch(() => {
      pickerServer = undefined;
      console.warn('DSH Sugar: workspace directory actions are unavailable');
    });
  }
  await pickerServer;
  let names;
  try { names = await fs.promises.readdir(cleanupDir); }
  catch (error) { if (error.code==='ENOENT') return; throw error; }
  for (const name of names) {
    const id = name.slice(0,-5);
    if (!name.endsWith('.json') || !/^[0-9a-f-]{36}$/i.test(id)) continue;
    const file = cleanupFile(id);
    if (registry.get(id)) { await fs.promises.rm(file,{force:true}); continue; }
    const record = JSON.parse(await fs.promises.readFile(file,'utf8'));
    if (record.id !== id || typeof record.path !== 'string') throw Error('Invalid project removal receipt');
    try { await cleanup(record); }
    catch { console.warn('DSH Sugar: pending project cleanup will be retried'); }
  }
}
async function removeWorkspace(id, action) {
  const entity = registry?.get(id);
  if (!entity) return action();
  const record = {id,path:entity.path,title:entity.title};
  for (const sessionId of entity.sessionIds) pin(sessionId,record,false);
  const receipt = cleanupFile(id);
  await fs.promises.mkdir(cleanupDir,{recursive:true,mode:0o700});
  await fs.promises.writeFile(receipt+'.tmp',JSON.stringify(record),{mode:0o600});
  await fs.promises.rename(receipt+'.tmp',receipt);
  try {
    const removed = await action();
    if (removed || !registry.get(id)) await cleanup(record);
    return removed;
  } finally {
    if (registry.get(id)) await fs.promises.rm(receipt,{force:true});
    else await syncRegistry();
  }
}

async function retainedWorkspace(root, sessionPaths, entities) {
  const stored = await management({op:'retainedWorkspace', root});
  if (!stored.id) return;
  // Wait for createCanonical's registry operation (including a rejected create). Publish its
  // actual result before releasing the native reservation for an explicit file-manager deletion.
  if (registry) void registry.enqueueOperation(() => syncRegistry(false, stored.id)).catch(() => {});
  if (!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(stored.id) || entities.has(stored.id))
    throw Error('Invalid retained workspace identity');
  const assigned = new Set(Array.from(entities.values()).flatMap(entity => entity.sessionIds));
  const sessionIds = [];
  for (const [session, cwd] of sessionPaths) {
    if (cwd !== root || assigned.has(session)) continue;
    const file = path.join(pinDir,createHash('sha256').update(session).digest('hex'));
    try { if (fs.readFileSync(file,'utf8') === stored.id) sessionIds.push(session); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  return {id:stored.id,sessionIds};
}
async function exportBundle(source, originalRoot) {
  const ep = active.getStore();
  const record = originalRoot ? binding(originalRoot) : ep && { id:ep.id,path:ep.root };
  if (!record || (ep && ep.id!==record.id)) throw Error('Missing original workspace for local plugin');
  const result = await management({op:'exportBundle',id:record.id,root:record.path,path:source});
  return result.path;
}
async function prepareCreator(root, directory) {
  const record = binding(root);
  const current = active.getStore();
  if (current && current.id !== record.id) throw Error('Creator belongs to another workspace');
  return remoteFs(current || await worker(record),'prepareCreator',[directory],undefined,Error);
}
async function within(root, fn, sessionId) {
  const record = binding(root);
  if (sessionId) pin(sessionId,record);
  if (active.getStore()?.id === record.id) return fn();
  const endpoint = await worker(record);
  return active.run(endpoint, fn);
}
async function* withinStream(root, make, sessionId) {
  const record = binding(root);
  if (sessionId) pin(sessionId,record);
  const endpoint = await worker(record);
  const iterator = active.run(endpoint, make)[Symbol.asyncIterator]();
  try {
    for (;;) {
      const item = await active.run(endpoint, () => iterator.next());
      if (item.done) return;
      yield item.value;
    }
  } finally {
    if (iterator.return) await active.run(endpoint, () => iterator.return());
  }
}
function guestPath(ep, value) {
  if (typeof value !== 'string') return value;
  if (value === ep.root) return '/workspace';
  if (value.startsWith(ep.root + '/')) return '/workspace' + value.slice(ep.root.length);
  return value;
}
// Session headers identify a coordinator project. Everything a tool sees or passes
// to a worker uses absolute paths in that project's Linux filesystem.
function sessionRoot(headerPath) {
  if (headerPath == null) return headerPath;
  if (!registry || typeof headerPath !== 'string' || !path.isAbsolute(headerPath)) return '/workspace';
  const canonical = path.resolve(headerPath);
  const owner = registeredRoot(canonical);
  return owner ? guestPath({root: owner.path}, canonical) : '/workspace';
}
function workspaceMediaPath(value) {
  return typeof value === 'string' && value.startsWith('/workspace/') &&
    path.resolve(value).startsWith('/workspace/') && !/[\\\x00-\x1f\x7f]/.test(value);
}

async function serveSessionFile(ctx, request, serve) {
  const params = new URL(request.url).searchParams;
  if (!params.has('sessionId')) return serve();
  const unavailable = status => new Response(null, {status, headers:{'Cache-Control':'private, no-store'}});
  const id = params.get('sessionId');
  if (!id || !/^[a-zA-Z0-9_-]{1,160}$/.test(id) || params.getAll('sessionId').length !== 1) return unavailable(400);
  if (!workspaceMediaPath(params.get('path'))) return unavailable(403);
  const lookup = ctx.get('typert')?.lookups.get('workspaceFileScope');
  if (!lookup) return unavailable(503);
  const scope = await lookup.resolve(id);
  if (scope?.sessionId !== id || !scope.workspaceRoot) return unavailable(404);
  try {
    return await within(scope.workspaceRoot, serve, id);
  } catch {
    return unavailable(request.signal.aborted ? 499 : 503);
  }
}
function workingPath(state) {
  return state.original ? sessionRoot(state.cwd) : state.cwd;
}
function childDirectory(parentHeader, guestCwd) {
  const owner = binding(parentHeader);
  const parentRoot = guestPath({root: owner.path}, path.resolve(parentHeader));
  const child = path.resolve(guestCwd);
  if (child !== parentRoot && !child.startsWith(parentRoot + '/')) {
    throw Error('The child directory must be in the parent workspace');
  }
  return child;
}
// Admitted attachment objects use the same path on the shared profile mount.
const attachmentRoot = '/home/sugar/.dsh/attachments/v1/';
function attachmentPath(value) {
  if (typeof value !== 'string' || !value.startsWith(attachmentRoot) || path.resolve(value) !== value) return false;
  return /^(?:objects\/[0-9a-f]{2}\/[0-9a-f]{64}|files\/[0-9a-f]{2}\/[0-9a-f]{64}\/[^/]+)$/.test(value.slice(attachmentRoot.length));
}
function deliveryPath(headerPath, file) {
  const root = sessionRoot(headerPath);
  return root && file.startsWith(root + '/') ? file.slice(root.length + 1) : file;
}
const targetMark = 'sugar-project:';
function mark(ep, target) {
  return { displayPath: target.displayPath, targetKey: targetMark + ep.envId + ':' + target.targetKey };
}
function unmark(ep, target) {
  const prefix = targetMark + ep.envId + ':';
  if (!target?.targetKey?.startsWith(prefix)) throw Error('File belongs to another workspace');
  return { displayPath: target.displayPath, targetKey: target.targetKey.slice(prefix.length) };
}
async function remoteFs(ep, method, args, signal, FsError) {
  const current = latestWorker.get(ep.id);
  if (current?.envId === ep.envId) ep = current;
  const response = await fetch(ep.url + '/fs/' + method, { method: 'POST', redirect: 'error', signal,
    headers: { Authorization: 'Bearer ' + ep.token, 'Content-Type': 'application/json',
      ...(passiveWatch.getStore() ? {'X-Sugar-Watch':'1'} : {}) }, body: JSON.stringify(args) });
  if (!response.ok) {
    let error;
    try { error = (await response.json()).error; } catch { /* closed worker */ }
    throw new FsError(error?.message || 'Project filesystem unavailable', error?.code || 'FS_IO_ERROR');
  }
  if (method === 'streamText') {
    const source = Readable.fromWeb(response.body);
    source.setEncoding('utf8');
    return source;
  }
  if (method === 'readBytes' || method === 'readByteRange') return Buffer.from(await response.arrayBuffer());
  return (await response.json()).result;
}
function routeFs(type, FsError) {
  const signals = { resolve: -1, stat: 1, lstat: 2, readText: 1, streamText: 1, readBytes: 1, readByteRange: 2,
    listDir: 1, writeText: 3, editText: 3, patchText: 3, deleteFile: 2, moveFile: 3 };
  for (const [method, signalIndex] of Object.entries(signals)) {
    const original = type.prototype[method];
    type.prototype[method] = async function(...args) {
      const ep = active.getStore();
      if (!ep) {
        if (args[0]?.targetKey?.startsWith(targetMark)) throw Error('Missing file project context');
        return original.apply(this, args);
      }
      const signal = signalIndex === -1 ? args[1]?.signal : args[signalIndex];
      if (signalIndex >= 0) {
        if (method === 'readBytes') args[1] = null;
        else args.length = signalIndex;
      }
      if (method === 'resolve' || method === 'lstat') {
        args[1] = { cwd: args[1]?.cwd || '/workspace' };
      } else {
        args[0] = unmark(ep, args[0]);
        if (method === 'moveFile') args[1] = unmark(ep, args[1]);
      }
      const result = await remoteFs(ep, method, args, signal, FsError);
      if (method === 'resolve') return mark(ep,result);
      if (method === 'listDir') return result.map(entry => ({...entry, target: mark(ep, entry.target)}));
      return result;
    };
  }
  const original = type.prototype.processPath;
  type.prototype.processPath = function(target) {
    if (!target.targetKey?.startsWith(targetMark)) return original.call(this, target);
    const ep = active.getStore();
    if (!ep) throw Error('Missing file project context');
    return unmark(ep, target).targetKey;
  };
  const hostPath = type.prototype.processPathFromHostPath;
  type.prototype.processPathFromHostPath = function(value) {
    if (!active.getStore()) return hostPath.call(this,value);
    if (attachmentPath(value)) return value;
    if (/^\/opt\/dsh\/node_modules\/@deepseek-ai\/dsh-ptc-runtime-node\/lib\/(?:persistent-)?process\.js$/.test(value)) {
      return value.replace('/opt/dsh/','/opt/sugar/');
    }
    return undefined;
  };
  const watch = type.prototype.watch;
  type.prototype.watch = async function(target, changed, signal) {
    const ep = active.getStore();
    if (!ep) return watch.call(this, target, changed, signal);
    const inspect = async () => {
      const info = await this.stat(target, signal);
      if (info?.type !== 'directory') return info?.version;
      return JSON.stringify((await this.listDir(target,signal)).map(e => [e.name,e.type,e.version]));
    };
    let prior = await inspect(), timer, stopped = false, offlineToken;
    const stop = () => { stopped = true; clearTimeout(timer); signal.removeEventListener('abort', stop); };
    const check = async () => {
      const current = latestWorker.get(ep.id);
      try {
        if (current && current.token !== offlineToken) {
          const now = await passiveWatch.run(true,inspect);
          if (!stopped && now !== prior) { prior = now; changed(); }
        }
      } catch(error) {
        if (['ECONNREFUSED','ECONNRESET','UND_ERR_SOCKET'].includes(error.cause?.code)) offlineToken = current?.token;
        else { if (!stopped) changed(error); return; }
      }
      if (!stopped) timer = setTimeout(check,1500);
    };
    signal.throwIfAborted(); signal.addEventListener('abort',stop,{once:true});
    timer = setTimeout(check,1500);
    return stop;
  };
}
// Some DSH tool executors supply their caller without inheriting the project's
// async scope. Bind device operations to the caller's immutable session header.
function adbRequest(agent, action, signal) {
  const record = binding(agent?.session?.header?.cwd);
  pin(agent.id, record);
  const timeout = AbortSignal.timeout(20_000);
  const requestSignal = signal ? AbortSignal.any([signal, timeout]) : timeout;
  requestSignal.throwIfAborted();
  return management({...action, id:record.id, root:record.path}, requestSignal).catch(error => {
    if (signal?.aborted) throw signal.reason;
    if (error instanceof ManagementError) throw error;
    const reason = timeout.aborted ? '宿主响应超时' : '未能取得 Sugar 宿主响应';
    const result = action.op === 'adbShell' ? '本次命令执行结果未知，请先核实后再操作。' : '当前无线调试状态未知。';
    throw Error(reason + '，' + result, {cause:error});
  });
}
const api = { within, withinStream, workspaceMediaPath, serveSessionFile,
  executeTools(agent, ctx, run) {
    if (!agent) return pluginExecution.run(undefined,run);
    return within(agent.session.header.cwd, () => {
      const endpoint = active.getStore();
      // Resolve policy at each operation, including continuations after a tool
      // returns, so changing the session to read-only also affects its plugin.
      const current = () => ({...endpoint,
        writable: (() => {
          const policy = ctx.get('sandboxPolicy')?.resolve({session:agent.session});
          return !!policy && policy.mode !== 'read-only';
        })()});
      return pluginExecution.run(current,run);
    },agent.id);
  },
  async touchActiveWorkspace(root, sessionId) {
    // Only renew a running worker. In particular, a model-only turn in a cold project stays lazy.
    if (!registry || typeof root !== 'string' || !path.isAbsolute(root)) return;
    try {
      const record = registeredRoot(path.resolve(root));
      const ep = record && latestWorker.get(record.id);
      if (!ep || ep.root !== record.path) return;
      pin(sessionId, record); // A removed project's old session must not renew its replacement.
      if (ep.activity) return;
      ep.activity = true;
      try {
        const response = await fetch(ep.url + '/activity', {method:'POST', redirect:'error',
          headers:{Authorization:'Bearer ' + ep.token}, signal:AbortSignal.timeout(5000)});
        await response.body?.cancel();
      } finally { delete ep.activity; }
    } catch { /* A stale session or retired worker has nothing to renew. */ }
  },
  registerAdbGuide(check) {
    hasReadAdbGuide = check;
    return () => { if (hasReadAdbGuide === check) hasReadAdbGuide = undefined; };
  },
  registerRootPolicy(authorize) {
    rootPolicy = authorize;
    return () => {
      if (rootPolicy !== authorize) return;
      rootPolicy = undefined;
      adbCli.revokeRootSession();
    };
  },
  revokeRootSession(session) { adbCli.revokeRootSession(session); },
  deviceProcess(agent, manual, run) {
    if (!agent?.session) return deviceOwner.exit(run);
    return within(agent.session.header.cwd, async () => {
      await adbCli.start();
      const ep = active.getStore();
      return deviceOwner.run({agent, manual, id:ep.id, root:ep.root}, run);
    },agent.id);
  }, routeFs, setRegistry, syncRegistry, removeWorkspace, retainedWorkspace, isWorkspaceRoot, exportBundle, prepareCreator, sessionRoot, workingPath, childDirectory, deliveryPath, pin,
  adbStatus(agent, signal) { return adbRequest(agent, {op:'adbStatus'}, signal); },
  async mobileTask(agent, method, options = {}, signal) {
    const record = binding(agent.session.header.cwd);
    pin(agent.id, record);
    const timeout = AbortSignal.timeout(10000);
    const task = await management({op:'mobileTask', id:record.id, root:record.path, conversation:agent.id,
      method, screen:options.screen ?? 'ask', taskId:options.taskId ?? '', turn:currentTurnId(agent.session)},
      signal ? AbortSignal.any([signal, timeout]) : timeout);
    if (task.ok && task.taskId) {
      if (['parked','completed','stopped','failed'].includes(task.phase)) {
        if (mobileBindings.get(agent.id) === task.taskId) mobileBindings.delete(agent.id);
      } else {
        mobileBindings.set(agent.id, task.taskId);
      }
    }
    return task;
  },
  adbShell(agent, command, signal) {
    return adbRequest(agent, {op:'adbShell', command, conversation:agent.id, route:{turn:currentTurnId(agent.session)}}, signal);
  },
  agentNotification(kind, sessionId, eventId, title, dismiss) {
    return management({ op: dismiss ? 'dismissAgentNotification' : 'agentNotification',
      kind, sessionId, eventId, title }, AbortSignal.timeout(5000));
  },
  attachPetReplies(handler) {
    petReplies = handler;
    return () => { if (petReplies === handler) petReplies = undefined; };
  },
  attachSessionStorage(handler) {
    sessionStorage = handler;
    return () => { if (sessionStorage === handler) sessionStorage = undefined; };
  },
  petState(state, sessionId, result, phase, caption, phoneStep, captionAt) {
    return management({op:'petState', state, sessionId, result, phase, caption, phoneStep, captionAt}, AbortSignal.timeout(3000));
  },
  clientRevision(id, upstream) {
    const recipe = clientRecipes.get(id);
    // DSH caches client code by this revision, also when combining multiple bundles.
    return recipe ? createHash('sha1').update(upstream + recipe.fingerprint).digest('hex').slice(0,12) : upstream;
  },
  clientBundle(id, bytes) {
    const entry = clientRecipes.get(id);
    return entry ? Buffer.from(applyRecipe(entry.recipe, bytes)) : bytes;
  },
  instructionFiles(provider, homes) {
    if (!provider) return;
    const shared = new Set(homes.map(home => path.join(home,'AGENTS.md')));
    const sharedTargets = new WeakSet();
    const scoped = {};
    scoped.resolve = async (file, options) => {
      if (!shared.has(file)) return provider.resolve(file,options);
      const target = await active.exit(() => provider.resolve(file,options));
      sharedTargets.add(target);
      return target;
    };
    for (const method of ['stat','readText','streamText']) {
      scoped[method] = (target, ...args) => {
        const call = () => provider[method](target,...args);
        return sharedTargets.has(target) ? active.exit(call) : call();
      };
    }
    return scoped;
  },
  ownsPath(value) {
    if (!registry || typeof value !== 'string' || !path.isAbsolute(value)) return false;
    const name = path.resolve(value);
    return registry.list().filter(({path: root}) =>
      isWorkspaceRoot(root) && (name === root || name.startsWith(root + '/'))).length === 1;
  },
  async fileMention(method, name, signal) {
    const ep = active.getStore();
    if (!ep) throw Error('Missing file reference project');
    const args = [name,{cwd:'/workspace'}];
    const kind = (type,name) => ({name,isDirectory:()=>type === 'directory',isFile:()=>type === 'file',isSymbolicLink:()=>type === 'symlink'});
    if (method === 'lstat') {
      const info = await remoteFs(ep, 'lstat',args,signal,Error);
      if (!info) throw Object.assign(Error('Path not found'),{code:'ENOENT'});
      return kind(info.type);
    }
    const target = await remoteFs(ep, 'resolve',args,signal,Error);
    const entries = await remoteFs(ep, 'listDir',[target],signal,Error);
    return entries.map(entry => kind(entry.type,entry.name));
  },
  host: fn => active.exit(fn),
  async resolveExecutable(command, env, signal, Missing) {
    const ep = active.getStore();
    const result = await remoteFs(ep, 'resolveExecutable', [command, env?.PATH], signal, Error);
    if (result === null) throw new Missing('Executable not found in project: ' + command);
    return result;
  },
  current: () => active.getStore(),
  projectPolicy(header, policy) {
    if (!header || !registry || !registeredRoot(path.resolve(header))) return policy;
    // The local provider checks grant paths on the coordinator, while the command
    // resolves them in another filesystem. Only the common project root is supplied.
    if (policy.mode === 'workspace-write' &&
        (policy.workspaceRoot !== '/workspace' || policy.additionalWritableRoots?.length)) {
      throw Error('Only the project workspace root can be granted across the two filesystems');
    }
    return policy;
  },
  spawn(ep, spec, terminal) {
    const project = require('./project-process.cjs');
    const call = terminal ? project.spawnTerminal : project.spawn;
    const owner = deviceOwner.getStore();
    const lease = owner?.id === ep.id && owner.root === ep.root
      ? adbCli.issue(owner,{id:ep.id,root:ep.root}, mobileBindings.get(owner.agent.id)) : undefined;
    try {
      const handle = call(ep, {...spec, cwd: spec.cwd || '/workspace'}, lease);
      if (terminal && lease) return handle.catch(error => { lease.revoke(); throw error; });
      return handle;
    } catch (error) { lease?.revoke(); throw error; }
  }
};
Object.defineProperty(globalThis, Symbol.for('dsh.sugar.projects'), { value: Object.freeze(api) });
const hook = 'globalThis[Symbol.for("dsh.sugar.projects")]';
const patches = require('./project-hooks.json');
const creator = require('./project-creator-hooks.json');
for (const [name, recipe] of Object.entries(creator)) {
  if (patches[name] && recipe.hash !== patches[name].hash) throw Error('Conflicting project module hashes');
  if (patches[name]) patches[name].edits.push(...recipe.edits);
  else patches[name] = recipe;
}
const clientRecipes = new Map();
for (const [name, recipe] of Object.entries(patches)) {
  if (name.endsWith('/lib/client.js')) {
    clientRecipes.set('@deepseek-ai/' + recipe.name, {
      recipe, fingerprint: createHash('sha256').update(JSON.stringify(recipe)).digest('hex')
    });
  }
}
function applyRecipe(recipe, bytes) {
  let source = Buffer.from(bytes).toString('utf8');
  if (createHash('sha256').update(source).digest('hex') !== recipe.hash) throw Error('Unsupported DSH project adapter source: '+recipe.name);
  for (const [before,raw] of recipe.edits) {
    if (source.indexOf(before) < 0 || source.indexOf(before) !== source.lastIndexOf(before)) throw Error('Ambiguous project adapter: '+recipe.name);
    source = source.replace(before, () => raw.replaceAll('SUGAR_HOOK', hook));
  }
  return source;
}
const modules = '/opt/dsh/node_modules/@deepseek-ai/';
const pluginTransport = require('./plugin-file-transport.cjs').create();
const pluginModules = require('./plugin-module-view.cjs').create({
  current: () => pluginExecution.getStore()?.(),
  transport: pluginTransport,
  globalEndpoint: () => pluginTransport.managementSync(config,{op:'pluginGlobalWorker'})
});
registerHooks({ resolve: pluginModules.resolve, load(url, context, nextLoad) {
  if (url.startsWith('sugar-plugin:')) return pluginModules.load(url,context,nextLoad);
  const result = pluginModules.load(url,context,nextLoad);
  if (!url.startsWith('file://' + modules)) return result;
  const recipe = patches[fileURLToPath(url).slice(modules.length)];
  return recipe ? { ...result, source: applyRecipe(recipe, result.source) } : result;
}});
