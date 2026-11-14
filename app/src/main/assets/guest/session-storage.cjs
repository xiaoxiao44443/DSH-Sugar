'use strict';

const fs = require('node:fs/promises');
const path = require('node:path');
const {createHash} = require('node:crypto');

const cordisOriginal = Symbol.for('cordis.original');
const busyError = () => new Error('This conversation still has active work.');

// A DSH AgentHandle, returned to the creator, owns shutdown, log flush and removal
// from both live registries. The bare Agent deliberately has no dispose method.
function trackHandles(ctx) {
  const handles = new Map();
  const pending = new Set();
  const deleting = new Set();
  const registry = ctx.agents[cordisOriginal] ?? ctx.agents;
  const originals = new Map();
  for (const name of ['create', 'resume']) {
    const original = registry[name];
    if (typeof original !== 'function') continue;
    const descriptor = Object.getOwnPropertyDescriptor(registry, name);
    async function tracked(options) {
      const id = name === 'create' ? options.sessionId : options.resumeSessionId;
      if (deleting.has(id)) throw busyError();
      const key = {id};
      pending.add(key);
      try {
        const handle = await original.apply(this, arguments);
        handles.set(handle.agent.id, handle);
        return handle;
      } finally {
        pending.delete(key);
      }
    }
    Object.defineProperty(registry, name, {value: tracked, configurable: true, writable: true});
    originals.set(name, {descriptor, tracked});
  }
  ctx.on('agent/disposed', ({agent}) => {
    if (handles.get(agent.id)?.agent.session === agent.session) handles.delete(agent.id);
  });
  ctx.effect(() => () => {
    for (const [name, {descriptor, tracked}] of originals) {
      if (registry[name] !== tracked) continue;
      if (descriptor) Object.defineProperty(registry, name, descriptor);
      else delete registry[name];
    }
    handles.clear();
  });
  return {handles, deleting, starting: id => [...pending].some(call => call.id === id)};
}

function service(ctx, key) {
  return ctx.get(key);
}

function directoryKey(id) {
  return createHash('sha256').update(id).digest('hex');
}

async function checkDirectory(dir) {
  if (!(await fs.lstat(dir)).isDirectory()) throw new Error('Invalid conversation storage directory.');
}

async function checkedRecordDirectory(persistence, record) {
  const root = path.resolve(persistence.root);
  const source = record.location;
  const canonical = persistence.locate(record.header);
  if (source?.kind !== 'jsonl' || canonical?.kind !== 'jsonl') throw new Error('Unsupported conversation storage format.');
  const dir = path.dirname(source.path);
  if (path.dirname(canonical.path) !== dir || path.dirname(path.dirname(dir)) !== root ||
      !/^session(?:\.v[1-9][0-9]*)?\.jsonl(?:\.zstd)?$/.test(path.basename(source.path))) {
    throw new Error('Invalid conversation file location.');
  }
  await checkDirectory(root);
  await checkDirectory(path.dirname(dir));
  await checkDirectory(dir);
  if (!(await fs.lstat(source.path)).isFile() || await fs.realpath(dir) !== path.join(await fs.realpath(root), path.relative(root, dir))) {
    throw new Error('Conversation path no longer identifies its own folder.');
  }
  return dir;
}

function createSessionStorage(ctx, makeImages = (...args) => require('./session-images.cjs').createSessionImages(...args)) {
  const {handles, starting, deleting} = trackHandles(ctx);
  const writes = require('./session-images.cjs').trackAttachmentWrites(ctx);
  let queue = Promise.resolve();
  let images;
  let imagesRoot;
  let inventory;

  async function active(id) {
    if (starting(id) || ctx.agents.get(id)?.status === 'running') return true;
    const activity = await ctx.waterfall('workspace/session-activity', {sessionId: id}, () => Promise.resolve([]));
    return activity.length > 0;
  }

  function ownsLive(id) {
    const session = ctx.sessions.get(id);
    if (!session) return true;
    return handles.get(id)?.agent.session === session;
  }

  async function reconcile(id, homeDir) {
    const workspaces = ctx.workspaceRegistry;
    // Use unfiltered entity methods; a deleted log can already have disappeared
    // from the public filtered sessionIds while its durable membership remains.
    for (const workspace of workspaces.list()) await workspace.detachSession(id);
    await workspaces.unpinSession(id);
    await workspaces.unarchiveSession(id);
    // rc.3 holds per-id header hints after files disappear. Drop only this id;
    // rebuilding all indexes from a prior snapshot can lose concurrent creates.
    for (const hints of [workspaces.headers, workspaces.sessionPaths, workspaces.missingSessionPaths, workspaces.invalidSessionPaths]) {
      hints?.delete(id);
    }
    const cache = service(ctx, 'sessionProjectionCache');
    if (cache?.requireTable) await cache.requireTable().delete(id);
    images.finishDelete(id);
    ctx.emit('api-session/removed', id);
    const entry = path.join(homeDir, 'sugar-session-removals', directoryKey(id));
    await fs.rm(path.join(entry, 'record'), {recursive: true, force: true});
    await fs.rm(path.join(entry, 'id.json'), {force: true});
    await fs.rmdir(entry);
  }

  async function restoreArchive(id, archived, pinned) {
    if (archived) return;
    const registry = ctx.workspaceRegistry;
    await registry.unarchiveSession(id);
    if (pinned && !registry.pinnedSessionIds.includes(id)) await registry.pinSession(id);
  }

  async function recover(homeDir, persistence) {
    const base = path.join(homeDir, 'sugar-session-removals');
    const children = await fs.readdir(base, {withFileTypes: true}).catch(error => {
      if (error.code === 'ENOENT') return [];
      throw error;
    });
    if (children.length) await checkDirectory(base);
    for (const child of children) {
      if (!child.isDirectory() || !/^[0-9a-f]{64}$/.test(child.name)) continue;
      const entry = path.join(base, child.name);
      const marker = await fs.readFile(path.join(entry, 'id.json'), 'utf8').catch(error => {
        if (error.code === 'ENOENT') return null;
        throw error;
      });
      if (marker === null) {
        // An interruption on either side of creating/removing the marker can
        // leave an empty directory. Never remove unknown files to fix it.
        await fs.rmdir(entry);
        continue;
      }
      const {id, archived, pinned} = JSON.parse(marker);
      if (typeof id !== 'string' || directoryKey(id) !== child.name) throw new Error('Invalid conversation removal journal.');
      const committed = await fs.lstat(path.join(entry, 'record')).catch(error => {
        if (error.code === 'ENOENT') return undefined;
        throw error;
      });
      if (committed) {
        if (!committed.isDirectory() || ctx.sessions.get(id)) throw new Error('Could not safely finish the prior conversation removal.');
        await reconcile(id, homeDir);
      } else {
        // Restore the admission gate if the process stopped before moving a log.
        if (await persistence.stat(id)) await restoreArchive(id, archived, pinned);
        await fs.unlink(path.join(entry, 'id.json'));
        await fs.rmdir(entry);
      }
    }
  }

  async function collect(persistence) {
    const archived = new Set(ctx.workspaceRegistry.archivedSessionIds);
    const workspaceById = new Map();
    const workspaces = ctx.workspaceRegistry.list();
    const workspaceByPath = new Map();
    const workspaceByTitle = new Map();
    for (const workspace of workspaces) {
      if (workspace.path) workspaceByPath.set(workspace.path, workspace);
      const title = workspace.title || workspace.path;
      workspaceByTitle.set(title, workspaceByTitle.has(title) ? null : workspace);
      for (const id of workspace.sessionIds) workspaceById.set(id, workspace);
    }
    const stored = new Map((await persistence.list()).map(record => [record.header.id, record]));
    const headers = new Map([...stored].map(([id, record]) => [id, record.header]));
    for (const session of ctx.sessions.list()) headers.set(session.id, session.header);
    const rows = [];
    const cache = service(ctx, 'sessionProjectionCache');
    const projections = service(ctx, 'sessionProjections');
    const keys = ['title', 'sessionListMetadata'];
    for (const [id, header] of headers) {
      const live = ctx.sessions.get(id);
      const block = (live && projections?.snapshot(live, keys)) || cache?.cachedSnapshot(header, keys) || cache?.cachedPredecessorTitle(header);
      const values = block?.values ?? {};
      const running = await active(id);
      const workspace = workspaceById.get(id) || workspaceByPath.get(header.cwd);
      rows.push({
        id, title: values.title || id, workspace: workspace?.title || workspace?.path || header.cwd || '',
        workspaceKey: workspace?.path || header.cwd || '',
        archived: archived.has(id), active: running, attachmentsOnly: false,
        updatedAt: Math.max(header.createdAt, values.sessionListMetadata?.lastPromptAt ?? 0),
        transcriptBytes: stored.get(id)?.sizeBytes ?? 0,
        canDelete: !running && ownsLive(id) && (stored.has(id) || Boolean(handles.get(id)))
      });
    }
    const snapshot = await images.snapshot(rows, persistence);
    // Older retained records saved a display title or path without a workspace key.
    for (const row of snapshot.rows) if (row.attachmentsOnly && row.workspaceKey == null) {
      const workspace = workspaceByPath.get(row.workspace) || workspaceByTitle.get(row.workspace);
      row.workspaceKey = workspace?.path || row.workspace || '';
    }
    return snapshot;
  }

  async function deleteRecord(id, row, snapshot, persistence, homeDir) {
    const registry = ctx.workspaceRegistry;
    const pinned = registry.pinnedSessionIds.includes(id);
    let journal;
    let committed = false;
    let claim = false;
    let lease;
    try {
      if (await active(id) || !ownsLive(id)) throw busyError();
      const base = path.join(homeDir, 'sugar-session-removals');
      await fs.mkdir(base, {recursive: true, mode: 0o700});
      await checkDirectory(base);
      const entry = path.join(base, directoryKey(id));
      await fs.mkdir(entry, {mode: 0o700});
      journal = entry;
      await fs.writeFile(path.join(entry, 'id.json'), JSON.stringify({id, archived: row.archived, pinned}), {flag: 'wx', mode: 0o600});
      await registry.archiveSession(id);  // Rechecks activity and prevents further wakes.
      if (await active(id) || !ownsLive(id)) throw busyError();
      const handle = handles.get(id);
      if (handle) await ctx.sessions.flush(handle.agent.session);
      const retained = await images.retainBeforeDelete(row, snapshot);
      if (await active(id) || !ownsLive(id)) throw busyError();
      if (handle) await handle.dispose();
      if (ctx.sessions.get(id)) throw busyError();
      persistence.tracker.claimWrite(id);
      claim = true;
      const record = await persistence.stat(id);
      if (!record?.location) throw new Error('The conversation has no committed transcript to remove.');
      const dir = await checkedRecordDirectory(persistence, record);
      lease = await persistence.acquireLease(id, undefined, dir);
      const stable = await persistence.stat(id);
      if (!stable || stable.revision !== record.revision || stable.revision !== retained.revision) {
        throw new Error('Conversation changed. Refresh the list and retry.');
      }
      await fs.rename(dir, path.join(entry, 'record'));
      committed = true;
      await lease.release();
      lease = undefined;
      persistence.coldLogMemo?.delete(id);
      await reconcile(id, homeDir);
    } catch (error) {
      if (!committed && journal) {
        await restoreArchive(id, row.archived, pinned);
        await fs.rm(path.join(journal, 'id.json'), {force: true});
        await fs.rmdir(journal);
      }
      throw error;
    } finally {
      try { await lease?.release(); }
      finally { if (claim) persistence.tracker.releaseClaim(id); }
    }
  }

  async function handle(request) {
    const persistence = service(ctx, 'sessionPersistence');
    const registry = service(ctx, 'workspaceRegistry');
    if (persistence?.name !== 'session-persistence-jsonl' || typeof persistence.tracker?.claimWrite !== 'function' ||
        !registry || !service(ctx, 'sessions')) throw new Error('DSH conversation storage is unavailable.');
    const homeDir = path.dirname(persistence.root);
    if (imagesRoot !== homeDir) {
      imagesRoot = homeDir;
      images = makeImages(homeDir, {ctx, writes});
      inventory = undefined;
    }
    if (request.op === 'list' || request.op === 'detail' || request.op === 'resolveAttachment') {
      if (request.op === 'resolveAttachment' && (typeof request.id !== 'string' || !request.id ||
          typeof request.fileId !== 'string' || !/^[0-9a-f]{64}$/.test(request.fileId)))
        throw new Error('Invalid attachment reference.');
      if (request.op === 'detail' && (typeof request.id !== 'string' || !request.id ||
          !Number.isSafeInteger(request.offset ?? 0) || (request.offset ?? 0) < 0))
        throw new Error('Invalid conversation detail request.');
      if (request.op === 'list' || !inventory) {
        inventory = undefined;
        await recover(homeDir, persistence);
        const {rows, filesBySession, partial} = await collect(persistence);
        inventory = {rows, filesBySession, partial: Boolean(partial)};
      }
      if (request.op === 'list') return {rows: inventory.rows, partial: inventory.partial};
      const {id} = request;
      const row = inventory.rows.find(item => item.id === id);
      if (!row) throw new Error('Conversation not found. Refresh the list.');
      const files = inventory.filesBySession.get(id) || [];
      if (request.op === 'resolveAttachment') {
        if (!files.some(item => item.fileId === request.fileId)) throw new Error('Attachment not found in this conversation.');
        return images.resolveAttachment(id, request.fileId, persistence);
      }
      const offset = Math.min(request.offset ?? 0, files.length);
      return {id, row, offset, totalFiles: files.length, files: files.slice(offset, offset + 24), partial: inventory.partial};
    }
    if (request.op !== 'delete' || !['conversation','attachments','both'].includes(request.mode) ||
        typeof request.id !== 'string' || !request.id) throw new Error('Invalid conversation storage request.');
    inventory = undefined;
    await recover(homeDir, persistence);
    const snapshot = await collect(persistence);
    const id = request.id;
    const row = snapshot.rows.find(item => item.id === id);
    if (!row) throw new Error('Conversation not found. Refresh the list.');
    if (snapshot.partial) throw new Error('Conversation inventory is incomplete. Refresh the list.');
    if (!row.canDelete || await active(id)) throw busyError();
    if (row.attachmentsOnly && request.mode === 'conversation') throw new Error('This conversation was already removed.');
    if (request.mode === 'attachments' || row.attachmentsOnly) {
      const result = await images.clear(id, snapshot);
      return {success: true, freedBytes: result.bytesFreed ?? 0};
    }
    deleting.add(id);
    try {
      await deleteRecord(id, row, snapshot, persistence, homeDir);
      let freedBytes = row.transcriptBytes;
      if (request.mode === 'both' && (row.imageCount || row.fileCount)) {
        const latest = await collect(persistence);
        if (latest.partial) throw new Error('Conversation removed. Refresh to finish clearing its attachments.');
        const result = await images.clear(id, latest);
        freedBytes += result.bytesFreed ?? 0;
      }
      return {success: true, freedBytes};
    } finally {
      deleting.delete(id);
    }
  }
  return request => {
    const result = queue.then(() => handle(request));
    queue = result.catch(() => {});
    return result;
  };
}

module.exports = {createSessionStorage};
