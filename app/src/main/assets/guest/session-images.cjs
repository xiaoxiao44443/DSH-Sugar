"use strict";

// The DSH v4 journal is the source of attachment ownership. Stable paths and
// hashes mentioned in chat text or tool arguments are not attachment records.
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

const digitHash = /^[0-9a-f]{64}$/;
function validName(name) {
  return typeof name === "string" && name.length > 0 && Buffer.byteLength(name) <= 255 &&
    !/[<>:"|?*\/\\\x00-\x1f\x7f]/.test(name) && !/[. ]$/.test(name) && name !== ".";
}
function objectKey(ref) { return ref.kind + ":" + ref.hash; }
function aliasKey(ref) { return JSON.stringify([ref.kind, ref.hash, ref.name]); }
function attachmentId(id, ref) {
  return crypto.createHash("sha256").update(JSON.stringify([id, ref.kind, ref.hash, ref.name])).digest("hex");
}
function fromBlock(block, seq) {
  if (!block || (block.type !== "image" && block.type !== "file") || !block.attachment) return;
  const { attachmentId, name } = block.attachment;
  const hash = /^sha256:([0-9a-f]{64})$/.exec(attachmentId)?.[1];
  if (!hash || (block.type === "file" && !validName(name))) throw Error("Invalid stored session attachment reference");
  const ref = { kind: block.type, hash, name: block.type === "file" ? name : "", seq };
  if (block.type === "image") {
    const { mediaType, width, height } = block.attachment;
    if (validName(name)) ref.displayName = name;
    if (/^image\/(?:png|jpeg|webp|gif)$/.test(mediaType)) ref.mediaType = mediaType;
    if (Number.isSafeInteger(width) && width > 0 && Number.isSafeInteger(height) && height > 0) {
      ref.width = width;
      ref.height = height;
    }
  }
  return ref;
}
function collect(blocks, seq, refs) {
  if (!Array.isArray(blocks)) return;
  for (const block of blocks) {
    const ref = fromBlock(block, seq);
    if (ref) {
      const key = aliasKey(ref);
      refs.set(key, Object.assign(refs.get(key) || {}, ref));
    }
  }
}
function collectEvent(event, refs) {
  const data = event.data;
  if (!data) return;
  const seq = event.seq;
  switch (event.type) {
    case "user/message": case "tool/ptc-dispatch": return collect(data.content, seq, refs);
    case "system/message": case "developer/message": case "tool/result": case "team/message/queued":
      return collect(data.message?.content, seq, refs);
    case "agent/inbox/spliced":
      for (const message of data.inserted || []) collect(message?.content, seq, refs);
      return;
    case "compaction/summary":
      collect(data.summary, seq, refs);
      return collect(data.rawOutput, seq, refs);
    case "assistant/message": collect(data.message?.content, seq, refs); break;
    case "assistant/attempt": break;
    default: return;
  }
  for (const entry of data.stream || []) {
    if (entry.type === "chunk" && entry.chunk?.type === "block-end") collect([entry.chunk.block], seq, refs);
  }
}
function checkRef(ref) {
  return ref && (ref.kind === "image" || ref.kind === "file") && digitHash.test(ref.hash) &&
    Number.isSafeInteger(ref.seq) && ref.seq >= 0 &&
    (ref.kind === "file" ? validName(ref.name) : ref.name === "");
}
function checkedPath(root, components, directory = false) {
  let file = root;
  for (let index = 0; index < components.length; ++index) {
    file = path.join(file, components[index]);
    const info = fs.lstatSync(file, { throwIfNoEntry: false });
    if (!info) return;
    if ((index < components.length - 1 || directory) ? !info.isDirectory() : !info.isFile())
      throw Error("Unexpected file type in DSH attachment storage");
    if (index === components.length - 1) return { file, info };
  }
}
function uniqueObjects(refs) {
  return new Map(refs.map(ref => [objectKey(ref), ref]));
}

// Writes publish objects before a prompt or tool result takes ownership in the
// journal. Watch the writer boundary as well as staged receipts / live tails.
function trackAttachmentWrites(ctx) {
  let pending = 0;
  const watches = new Map();
  function attach(key, methods) {
    const service = ctx?.get ? ctx.get(key) : ctx?.[key];
    const store = service?.[Symbol.for("cordis.original")] || service;
    if (!store || watches.has(store)) return;
    const release = [];
    for (const name of methods) {
      const original = store[name];
      if (typeof original !== "function") continue;
      const descriptor = Object.getOwnPropertyDescriptor(store, name);
      if (descriptor && !descriptor.configurable) throw Error("Cannot track DSH attachment writes");
      async function watched(...args) {
        pending++;
        try { return await original.apply(this, args); } finally { pending--; }
      }
      Object.defineProperty(store, name, {value: watched, writable: true, configurable: true});
      release.push(() => {
        if (store[name] !== watched) return;
        if (descriptor) Object.defineProperty(store, name, descriptor);
        else delete store[name];
      });
    }
    watches.set(store, release);
  }
  function refresh() {
    attach("fileUploads", ["upload", "uploadStream", "uploadImageStream"]);
    attach("attachments", ["saveImages", "saveImage", "saveFile", "saveFileStream"]);
  }
  refresh();
  ctx?.effect?.(() => () => {
    for (const callbacks of watches.values()) for (const release of callbacks) release();
    watches.clear();
  });
  return { get pending() { refresh(); return pending > 0; } };
}

function createSessionImages(dshHome, { ctx, sessionApi, writes } = {}) {
  const root = fs.realpathSync(dshHome);
  const versionRoot = ["attachments", "v1"];
  const ledger = ["sugar-session-storage", "attachments-v1.json"];
  const cache = new Map();

  function readLedger() {
    const file = checkedPath(root, ledger);
    if (!file) return new Map();
    const obj = JSON.parse(fs.readFileSync(file.file, "utf8"));
    if (obj.version !== 1 || !Array.isArray(obj.sessions)) throw Error("Unsupported DSH attachment index");
    const records = new Map();
    for (const row of obj.sessions) {
      if (!row || typeof row.id !== "string" || records.has(row.id) || !Number.isSafeInteger(row.clearedThrough) ||
          row.clearedThrough < -1 || !Array.isArray(row.refs) || !row.refs.every(checkRef))
        throw Error("Invalid DSH attachment index");
      records.set(row.id, row);
    }
    return records;
  }
  function saveLedger(records) {
    const dir = path.join(root, ledger[0]);
    if (!checkedPath(root, [ledger[0]], true)) fs.mkdirSync(dir, { mode: 0o700 });
    const target = path.join(root, ...ledger);
    const tmp = path.join(dir, ".attachments-" + crypto.randomUUID() + ".tmp");
    try {
      const fd = fs.openSync(tmp, "wx", 0o600);
      try {
        fs.writeFileSync(fd, JSON.stringify({ version: 1, sessions: [...records.values()] }));
        fs.fsyncSync(fd);
      } finally { fs.closeSync(fd); }
      fs.renameSync(tmp, target);
      if (process.platform !== "win32") {
        const directory = fs.openSync(dir, "r");
        try { fs.fsyncSync(directory); } finally { fs.closeSync(directory); }
      }
    } finally {
      try { fs.unlinkSync(tmp); } catch (e) { if (e.code !== "ENOENT") throw e; }
    }
  }

  function canonical(ref) {
    return checkedPath(root, [...versionRoot, ref.kind === "file" ? "file-objects" : "objects", ref.hash.slice(0, 2), ref.hash]);
  }
  function namesDir(ref) {
    return [...versionRoot, "files", ref.hash.slice(0, 2), ref.hash];
  }
  function alias(ref) { return checkedPath(root, [...namesDir(ref), ref.name]); }
  function describe(id, ref, current) {
    return { fileId: attachmentId(id, ref), kind: ref.kind, name: ref.name || ref.displayName || ref.hash,
      bytes: current.info.size, mediaType: ref.mediaType, width: ref.width, height: ref.height,
      relativePath: path.relative(path.join(root, ...versionRoot), current.file).split(path.sep).join("/") };
  }
  function physicalPlan(refs, others) {
    const protectedObjects = uniqueObjects(others);
    const protectedNames = new Set(others.map(aliasKey));
    const removedNames = new Set();
    const files = [];
    for (const ref of refs) {
      if (ref.kind !== "file" || protectedNames.has(aliasKey(ref))) continue;
      const current = alias(ref);
      if (current && !removedNames.has(current.file)) {
        files.push(current);
        removedNames.add(current.file);
      }
    }
    for (const [id, ref] of uniqueObjects(refs)) {
      if (protectedObjects.has(id)) continue;
      if (ref.kind === "file") {
        const dir = checkedPath(root, namesDir(ref), true);
        if (dir && fs.readdirSync(dir.file).some(name => !removedNames.has(path.join(dir.file, name)))) continue;
      }
      const current = canonical(ref);
      if (current) files.push(current);
    }
    return files;
  }
  function physicalBytes(files) {
    const links = new Map();
    for (const { file, info } of files) {
      const key = info.ino ? info.dev + ":" + info.ino : file;
      const item = links.get(key) || { info, count: 0 };
      item.count++;
      links.set(key, item);
    }
    let total = 0;
    for (const { info, count } of links.values()) if (count >= info.nlink) total += info.size;
    return total;
  }
  function livePending() {
    const pending = new Map();
    const uploads = ctx?.get ? ctx.get("fileUploads") : ctx?.fileUploads;
    for (const agent of ctx?.agents?.list?.() || []) {
      for (const message of [...(agent.inbox?.nextTurn || []), ...(agent.inbox?.nextStep || [])]) collect(message.content, 0, pending);
      const staged = uploads?.stagedFiles?.get(agent.session);
      for (const receipt of staged?.values() || []) collect([receipt.attachment], 0, pending);
    }
    return [...pending.values()];
  }
  async function readRefs(record, persistence) {
    const id = record.header.id;
    if (record.formatStatus === "migration-required") throw Error("Conversation requires migration");
    if (record.revision != null && cache.get(id)?.revision === record.revision) return cache.get(id);
    const handle = await persistence.open(id, "read");
    try {
      const { events } = await handle.read();
      const refs = new Map();
      for (const event of events) collectEvent(event, refs);
      const scan = { revision: record.revision, refs: [...refs.values()], last: events.at(-1)?.seq ?? -1 };
      cache.set(id, scan);
      return scan;
    } finally { await handle.close(); }
  }
  async function resolveAttachment(id, fileId, persistence) {
    // Revalidate a single owner at open time. A shared blob may survive cleanup
    // even after this particular conversation has lost its reference to it.
    const record = await persistence.stat(id);
    const session = ctx?.sessions?.get?.(id);
    const retained = readLedger().get(id);
    const cutoff = retained?.clearedThrough ?? -1;
    const log = record && await readRefs(record, persistence);
    const refs = new Map();
    if (record || session) {
      for (const ref of log?.refs || []) if (ref.seq > cutoff) refs.set(aliasKey(ref), ref);
      if (session) for (const event of session.snapshotEvents(Math.max(cutoff, log?.last ?? -1) + 1))
        if (event.seq > cutoff) collectEvent(event, refs);
    } else {
      for (const ref of retained?.refs || []) refs.set(aliasKey(ref), ref);
    }
    for (const ref of refs.values()) if (attachmentId(id, ref) === fileId) {
      const current = canonical(ref) || (ref.kind === "file" && alias(ref));
      if (current) return { id, ...describe(id, ref, current) };
    }
    throw Error("Attachment is no longer available in this conversation.");
  }
  async function snapshot(rows, persistence) {
    await persistence.flush();
    const ledgerState = readLedger();
    const entries = await persistence.list();
    const owners = new Map();
    const live = new Set(entries.map(entry => entry.header.id));
    const ui = new Map(rows.map(row => [row.id, row]));
    let partial = false;
    for (const id of cache.keys()) if (!live.has(id)) cache.delete(id);
    for (const entry of entries) {
      const id = entry.header.id;
      try {
        const log = await readRefs(entry, persistence);
        const cutoff = ledgerState.get(id)?.clearedThrough ?? -1;
        owners.set(id, { refs: log.refs.filter(ref => ref.seq > cutoff), last: log.last, revision: entry.revision });
      } catch {
        partial = true;
      }
    }
    for (const session of ctx?.sessions?.list?.() || []) {
      if (!live.has(session.id)) {
        const refs = new Map();
        for (const event of session.snapshotEvents()) collectEvent(event, refs);
        owners.set(session.id, { refs: [...refs.values()].filter(r => r.seq > (ledgerState.get(session.id)?.clearedThrough ?? -1)),
          last: session.seq - 1 });
        live.add(session.id);
      }
    }
    for (const [id, detached] of ledgerState) {
      if (live.has(id) || !detached.refs.length) continue;
      owners.set(id, { refs: detached.refs, last: detached.clearedThrough, detached: true });
      ui.set(id, { id, title: detached.title, workspace: detached.workspace, workspaceKey: detached.workspaceKey, updatedAt: detached.updatedAt,
        active: false, archived: detached.archived, attachmentsOnly: true, canDelete: true });
    }
    const pending = livePending();
    const occurrence = new Map();
    const firstOwner = new Map();
    const sharedObjects = new Set();
    for (const [id, { refs }] of owners) for (const ref of refs) {
      const key = aliasKey(ref);
      occurrence.set(key, (occurrence.get(key) || 0) + 1);
      const object = objectKey(ref);
      if (!firstOwner.has(object)) firstOwner.set(object, id);
      else if (firstOwner.get(object) !== id) sharedObjects.add(object);
    }
    const all = new Map(pending.map(ref => [aliasKey(ref), ref]));
    for (const { refs } of owners.values()) for (const ref of refs) all.set(aliasKey(ref), ref);
    const held = new Set(pending.map(aliasKey));
    const result = [];
    const filesBySession = new Map();
    for (const row of ui.values()) {
      const refs = owners.get(row.id)?.refs || [];
      const mine = new Set(refs.map(aliasKey));
      const others = refs.length ? [...all].filter(([key]) => !mine.has(key) || occurrence.get(key) > 1 || held.has(key)).map(([, ref]) => ref) : [];
      let attachmentBytes = 0;
      let reclaimableBytes = 0;
      const existing = [];
      try {
        const found = new Map();
        for (const ref of refs) {
          const object = canonical(ref);
          const named = ref.kind === "file" && alias(ref);
          if (object || named) {
            const bytes = (object || named).info.size;
            const key = objectKey(ref);
            existing.push({ ...describe(row.id, ref, object || named), shared: sharedObjects.has(key) });
            found.set(key, bytes);
          }
        }
        attachmentBytes = [...found.values()].reduce((n, size) => n + size, 0);
        reclaimableBytes = physicalBytes(physicalPlan(refs, others));
      } catch { partial = true; }
      filesBySession.set(row.id, existing.sort((a, b) => b.bytes - a.bytes || a.name.localeCompare(b.name)));
      result.push({ ...row, attachmentsOnly: row.attachmentsOnly === true,
        imageCount: existing.filter(ref => ref.kind === "image").length,
        fileCount: existing.filter(ref => ref.kind === "file").length, attachmentBytes, reclaimableBytes });
    }
    if (partial) for (const row of result) row.reclaimableBytes = 0;
    return { rows: result, filesBySession, owners, partial, persistence, ledgerState, live, inputRows: rows };
  }
  function owner(id, snap) {
    if (snap.partial || !snap.owners.has(id)) throw Error("Cannot verify attachment ownership across all conversations");
    return snap.owners.get(id);
  }
  async function retainBeforeDelete(row, snap) {
    const state = owner(row.id, await snapshot(snap.inputRows, snap.persistence));
    const records = readLedger();
    const old = records.get(row.id);
    const refs = state.refs.filter(ref => canonical(ref) || (ref.kind === "file" && alias(ref)));
    if (refs.length) {
      records.set(row.id, { id: row.id, title: row.title, workspace: row.workspace, workspaceKey: row.workspaceKey, archived: !!row.archived,
        updatedAt: row.updatedAt, clearedThrough: old?.clearedThrough ?? -1, refs });
      saveLedger(records);
    } else if (old?.refs.length) {
      // A rolled-back attempt may have captured attachments which no longer
      // exist. Keep the cutoff until the transcript rename actually commits.
      records.set(row.id, { id: row.id, clearedThrough: old.clearedThrough, refs: [] });
      saveLedger(records);
    }
    return { revision: state.revision };
  }
  function finishDelete(id) {
    const records = readLedger();
    const entry = records.get(id);
    if (entry && !entry.refs.some(ref => canonical(ref) || (ref.kind === "file" && alias(ref)))) {
      records.delete(id);
      saveLedger(records);
    }
  }
  function offloadTargets(refs, nodes, getEvent, getMessage) {
    const wanted = new Set(refs.filter(ref => ref.kind === "image").map(ref => ref.hash));
    const targets = [];
    if (!wanted.size) return targets;
    for (const seq of nodes) {
      const event = getEvent(seq);
      const message = getMessage(event);
      const imageIndexes = [];
      let index = 0;
      for (const block of message?.content || []) {
        if (block.type !== "image") continue;
        if (wanted.has(fromBlock(block, seq)?.hash) && !block.offloaded) imageIndexes.push(index);
        index++;
      }
      if (!imageIndexes.length) continue;
      if (event.type !== "user/message" && event.type !== "tool/result")
        throw Error("Conversation contains a model image which this DSH version cannot offload");
      targets.push({ seq, imageIndexes });
    }
    return targets;
  }
  async function withKeptConversation(id, refs, persistence, work) {
    const hasImages = refs.some(ref => ref.kind === "image");
    const projections = ctx?.sessions?.messageProjections || [];
    if (hasImages && !projections.some(p => p.type === "image/offload"))
      throw Error("Image offload unavailable; kept conversation cannot be safely cleaned");
    const agent = ctx?.agents?.get?.(id);
    if (agent) {
      return agent.runMaintenance(async signal => {
        const session = agent.session;
        const targets = offloadTargets(refs, session.surface.nodes, seq => session.eventAt(seq), e => session.deriveEventMessage(e));
        if (targets.length) session.append("image/offload", { targets });
        return work(signal);
      });
    }
    // Keep archived and subagent conversations cold. A storage write handle
    // owns the normal DSH writer lease without resuming or unarchiving an Agent.
    const handle = await persistence.open(id, "write");
    try {
      if (hasImages) {
        const api = sessionApi || require("/opt/dsh/node_modules/@deepseek-ai/dsh-session/lib/index.js");
        const { events } = await handle.read();
        const surface = api.foldSurface(events, projections);
        const targets = offloadTargets(refs, surface.nodes, seq => events[seq], e => api.deriveEventMessage(e, surface.projectedMessages));
        if (targets.length) {
          const event = { type: "image/offload", seq: events.length, data: { targets }, time: Date.now() };
          api.foldSurface([...events, event], projections);
          await handle.append([event]);
          await handle.flush();
        }
      }
      return await work();
    } finally { await handle.close(); }
  }
  async function clear(id, snap) {
    const state = owner(id, snap);
    const intended = new Map(state.refs.map(ref => [aliasKey(ref), ref]));
    const isDetached = state.detached === true;
    const execute = async signal => {
      // Flush also commits the model-visible image omissions before deleting blobs.
      const fresh = await snapshot(snap.inputRows, snap.persistence);
      const current = owner(id, fresh);
      if ((current.detached === true) !== isDetached ||
          current.refs.length !== intended.size || current.refs.some(r => intended.get(aliasKey(r))?.seq !== r.seq))
        throw Error("Conversation changed; refresh and retry");
      // A new cold import, a newly committed fork, or another service writer
      // may have published a reference while the snapshots were being read.
      const committed = await snap.persistence.list();
      const observed = [...fresh.owners].filter(([, p]) => p.revision != null);
      if (committed.length !== observed.length ||
          committed.some(entry => fresh.owners.get(entry.header.id)?.revision !== entry.revision))
        throw Error("Session storage changed during inspection; retry attachment cleanup");
      const others = livePending();
      for (const [key, peer] of fresh.owners) if (key !== id) others.push(...peer.refs);
      // Include event tails accepted during the asynchronous scan but not flushed
      // yet. No async work between this last live check and the unlink operations.
      for (const session of ctx?.sessions?.list?.() || []) {
        const tailRefs = new Map();
        const start = (fresh.owners.get(session.id)?.last ?? -1) + 1;
        for (const event of session.snapshotEvents(start)) collectEvent(event, tailRefs);
        if (session.id === id && tailRefs.size) throw Error("Conversation changed; refresh and retry");
        others.push(...tailRefs.values());
      }
      if (writes?.pending) throw Error("An attachment is being saved; retry when the upload finishes");
      const files = physicalPlan(current.refs, others);
      const records = readLedger();
      const bytesFreed = physicalBytes(files);
      signal?.throwIfAborted();
      for (const item of files) fs.unlinkSync(item.file);
      if (isDetached) records.delete(id);
      else records.set(id, { id, clearedThrough: current.last, refs: [] });
      saveLedger(records);
      return { bytesFreed, imageCount: current.refs.filter(r => r.kind === "image").length,
        fileCount: current.refs.filter(r => r.kind === "file").length };
    };
    return isDetached ? execute() : withKeptConversation(id, state.refs, snap.persistence, execute);
  }
  return { snapshot, resolveAttachment, retainBeforeDelete, finishDelete, clear };
}

module.exports = { createSessionImages, trackAttachmentWrites };
