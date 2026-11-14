"use strict";

const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const assert = require("node:assert/strict");
const { test } = require("node:test");
const { createSessionImages, trackAttachmentWrites } = require("../app/src/main/assets/guest/session-images.cjs");
const dshModules = process.env.DSH_MODULES;
const black = "a".repeat(64);
const red = "b".repeat(64);
const blue = "c".repeat(64);
const image = (hash) => ({ type: "image", attachment: { attachmentId: "sha256:" + hash } });
const file = (hash, name) => ({ type: "file", attachment: { attachmentId: "sha256:" + hash, name } });
const user = (seq, blocks) => ({ type: "user/message", seq, data: { role: "user", content: blocks }, time: 10, surfaceOp: "append" });

function fixture(t, { useDSH = false } = {}) {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), "sugar-session-attachments-"));
  t.after(() => fs.rmSync(home, { recursive: true, force: true }));
  const logs = new Map();
  const broken = new Set();
  const sessions = new Map();
  const agents = new Map();
  const leases = new Set();
  let api;
  const projections = [];
  if (useDSH) {
    api = require(path.join(dshModules, "@deepseek-ai/dsh-session/lib/index.js"));
    require(path.join(dshModules, "@deepseek-ai/dsh-compaction-image-offload/lib/index.js")).apply({
      sessions: { registerMessageProjection(p) { projections.push(p); } }, on() {}
    });
  }
  const ctx = {sessions: {list: () => [...sessions.values()], get: id => sessions.get(id), messageProjections: projections},
    agents: {get: id => agents.get(id), list: () => [...agents.values()]}, fileUploads: {stagedFiles: new WeakMap()} };
  const persistence = {
    async flush() {},
    async list() {return [...logs].map(([id, log]) => ({header: {id}, revision: String(log.length)}));},
    async stat(id) {if (logs.has(id)) return {header: {id}, revision: String(logs.get(id).length)};},
    async open(id, mode) {
      if (broken.has(id)) throw Error("Unreadable log");
      if (mode === "write") {
        assert.ok(!leases.has(id)); leases.add(id);
      }
      return { async read() { return {events: logs.get(id).slice()}; },
        async append(rows) { assert.equal(mode, "write"); logs.get(id).push(...rows); },
        async flush() {}, async close() { if (mode === "write") leases.delete(id); } };
    }
  };
  function make(...parts) {
    const full = path.join(home, ...parts);
    fs.mkdirSync(path.dirname(full), {recursive: true});
    return full;
  }
  function canonical(hash, kind) {return make("attachments", "v1", kind === "image" ? "objects" : "file-objects", hash.slice(0,2), hash);}
  function alias(hash, name) {return make("attachments","v1","files", hash.slice(0,2), hash, name);}
  function put(hash, kind, size, names = []) {
    const root = canonical(hash, kind);
    fs.writeFileSync(root, Buffer.alloc(size, 17));
    for (const name of names) fs.linkSync(root, alias(hash,name));
    return root;
  }
  const rows = () => [...logs.keys()].map(id => ({id,title:id + " title",workspace:"example",archived:true,active:false,updatedAt:31,canDelete:true}));
  const service = writes => createSessionImages(home, {ctx,sessionApi:api,writes});
  const snap = s => s.snapshot(rows(), persistence);
  return { home, logs, broken, api, sessions, agents, ctx, projections, persistence, put, canonical, alias, rows, service, snap, make, leases };
}
test("removed conversations survive restart by title; duplicate names, namespaces, unknown objects and project files are protected", async t => {
  const f = fixture(t);
  const photo = f.put(black, "image", 23);
  const document = f.put(black, "file", 19, ["a.txt", "b.txt"]);
  const independent = f.put(red, "file", 7, ["single.pdf"]);
  const unknown = f.put(blue, "image", 11);
  const workspace = f.make("workspace","private.png");
  fs.writeFileSync(workspace, "keep-me");
  f.logs.set("one", [user(0,[image(black), file(black,"a.txt"), file(red,"single.pdf"), {type:"text",text:workspace}])]);
  f.logs.set("two", [user(0,[file(black,"b.txt")])]);
  let service = f.service();
  let current = await f.snap(service);
  const one = current.rows.find(row => row.id === "one");
  assert.equal(one.attachmentBytes, 49);
  assert.equal(one.reclaimableBytes, 30);
  const files = current.filesBySession.get("one");
  assert.deepEqual(files.map(({kind,name,bytes,shared}) => [kind,name,bytes,shared]), [
    ["image",black,23,false], ["file","a.txt",19,true], ["file","single.pdf",7,false]
  ]);
  assert.equal(current.filesBySession.get("two")[0].shared,true);
  one.workspaceKey = "/workspace/example";
  await service.retainBeforeDelete(one, current);
  f.logs.delete("one");
  service = f.service();
  current = await f.snap(service);
  assert.equal(current.rows.find(row => row.id === "one").title, "one title");
  assert.equal(current.rows.find(row => row.id === "one").attachmentsOnly,true);
  assert.equal(current.rows.find(row => row.id === "one").workspaceKey,"/workspace/example");
  assert.deepEqual(current.filesBySession.get("one"),files);
  assert.equal((await service.clear("one", current)).bytesFreed, 30);
  assert.ok(!fs.existsSync(photo) && !fs.existsSync(independent) && !fs.existsSync(f.alias(black,"a.txt")));
  assert.ok(fs.existsSync(document) && fs.existsSync(f.alias(black,"b.txt")) && fs.existsSync(unknown));
  assert.equal(fs.readFileSync(workspace,"utf8"), "keep-me");
  assert.equal((await f.snap(f.service())).rows.length, 1);
});
test("image details preserve recorded names and dimensions and exclude absent objects and plain-text paths", async t => {
  const f = fixture(t);
  f.put(black, "image", 23);
  f.put(red, "file", 11, ["first.txt","second.txt"]);
  const photo = image(black);
  Object.assign(photo.attachment, {name:"计算器.png", mediaType:"image/png", width:300, height:500});
  f.logs.set("one",[user(0,[photo,file(red,"first.txt"),file(red,"second.txt"),image(blue)]),
    user(1,[image(black),{type:"text",text:"/workspace/report.png"}])]);
  let s = f.service();
  let snap = await f.snap(s);
  const details = snap.filesBySession.get("one");
  assert.equal(details.length,3);
  const {fileId, relativePath, ...meta} = details[0];
  assert.match(fileId,/^[0-9a-f]{64}$/);
  assert.equal(relativePath,"objects/aa/"+black);
  assert.deepEqual(meta,{kind:"image",name:"计算器.png",bytes:23,mediaType:"image/png",width:300,height:500,shared:false});
  assert.ok(details.every(item => !item.shared));
  assert.equal(snap.rows[0].attachmentBytes,34);
  await s.retainBeforeDelete(snap.rows[0],snap);
  f.logs.delete("one");
  s = f.service(); snap = await f.snap(s);
  assert.deepEqual(snap.filesBySession.get("one"),details);
});
test("file IDs bind the owner and alias, resolve current stored bytes and survive detachment", async t => {
  const f = fixture(t);
  const original = f.put(red, "file", 13, ["report.pdf","other.pdf"]);
  const block = file(red,"report.pdf");
  f.logs.set("one",[user(0,[block,file(red,"other.pdf")])]);
  f.logs.set("two",[user(0,[block])]);
  let s = f.service();
  const snap = await f.snap(s);
  const a = snap.filesBySession.get("one").find(x => x.name === "report.pdf");
  const otherAlias = snap.filesBySession.get("one").find(x => x.name === "other.pdf");
  const b = snap.filesBySession.get("two")[0];
  assert.notEqual(a.fileId,b.fileId);
  assert.notEqual(a.fileId,otherAlias.fileId);
  const resolve = (id,fileId) => s.resolveAttachment(id,fileId,f.persistence);
  const first = await resolve("one",a.fileId);
  assert.deepEqual([first.id,first.fileId,first.name,first.bytes,first.relativePath],["one",a.fileId,"report.pdf",13,"file-objects/bb/"+red]);
  await assert.rejects(resolve("two",a.fileId), /no longer available/);
  await assert.rejects(resolve("one","../../workspace"), /no longer available/);
  await s.retainBeforeDelete(snap.rows[0],snap);
  f.logs.delete("one");
  s = f.service();
  fs.unlinkSync(original);
  assert.equal((await resolve("one",a.fileId)).relativePath,"files/bb/"+red+"/report.pdf");
  await s.clear("one",await f.snap(s));
  await assert.rejects(resolve("one",a.fileId),/no longer available/);
  assert.equal((await resolve("two",b.fileId)).bytes,13);
  fs.unlinkSync(f.alias(red,"report.pdf"));
  await assert.rejects(resolve("two",b.fileId), /no longer available/);
});
test("file aliases unknown to any transcript and files recovered with same hash remain intact", async t => {
  const f = fixture(t);
  f.put(black, "file", 10, ["a.txt","external.txt"]);
  f.logs.set("one", [user(0,[file(black,"a.txt")])]);
  let s = f.service();
  assert.equal((await s.clear("one",await f.snap(s))).bytesFreed, 0);
  assert.ok(fs.existsSync(f.canonical(black,"file")) && fs.existsSync(f.alias(black,"external.txt")));
  assert.equal((await f.snap(s)).rows[0].fileCount,0);
  fs.linkSync(f.canonical(black,"file"),f.alias(black,"a.txt"));
  f.logs.get("one").push(user(1,[file(black,"a.txt")]));
  s = f.service();
  assert.equal((await f.snap(s)).rows[0].fileCount,1);
});
test("unreadable peer logs or malformed file names protect all attachment bytes", async t => {
  const f = fixture(t);
  const target = f.put(black, "file", 10, ["a.txt"]);
  f.logs.set("one", [user(0,[file(black,"a.txt")])]);
  f.logs.set("other", []);
  f.broken.add("other");
  const s = f.service();
  let current = await f.snap(s);
  assert.ok(current.partial);
  await assert.rejects(s.clear("one",current), /Cannot verify/);
  f.broken.clear();
  f.logs.set("other",[user(0,[file(black,"../../escape")])]);
  current = await f.snap(s);
  assert.ok(current.partial);
  await assert.rejects(s.retainBeforeDelete(current.rows[0],current), /Cannot verify/);
  assert.ok(fs.existsSync(target));
});
test("cold archived session drops images from subsequent real DSH model input; shared object survives, unread file history remains", {skip: !dshModules}, async t => {
  const f = fixture(t, {useDSH:true});
  const shared = f.put(black,"image",21);
  const unique = f.put(red,"image",11);
  f.put(blue,"file",7,["report.txt"]);
  const old = user(0,[{type:"text",text:"one"},image(black),image(red),file(blue,"report.txt")]);
  f.logs.set("one",[old]); f.logs.set("two",[user(0,[image(black)])]);
  const s = f.service();
  assert.equal((await s.clear("one",await f.snap(s))).bytesFreed,18);
  assert.ok(fs.existsSync(shared) && !fs.existsSync(unique));
  assert.equal(f.agents.size,0);
  assert.equal(f.leases.size,0);
  const log = f.logs.get("one");
  log.push(user(log.length,[{type:"text",text:"follow up"}]));
  const surface = f.api.foldSurface(log, f.projections);
  assert.deepEqual(f.api.deriveEventMessage(log[0],surface.projectedMessages).content.filter(b=>b.type==="image").map(b=>b.offloaded),[true,true]);
  assert.equal(f.api.deriveEventMessage(log.at(-1),surface.projectedMessages).content[0].text,"follow up");
  assert.ok(old.data.content.filter(b=>b.type==="image").every(b=>!b.offloaded));
  const current = await f.snap(s);
  assert.equal(current.rows[0].archived,true);
  assert.equal(current.rows[0].imageCount,0);
  assert.equal(current.rows[1].imageCount,1);
  await s.clear("two",current);
  assert.ok(!fs.existsSync(shared));
});
test("upload in flight, pending receipts, queued messages and accepted live tails protect reused blobs", async t => {
  const f = fixture(t);
  const target = f.put(black, "file", 10, ["a.txt"]);
  const block = file(black,"a.txt");
  f.logs.set("one", [user(0,[block])]);
  f.logs.set("two", []);
  const live = { id:"two", seq:0, snapshotEvents: () => [] };
  f.sessions.set("two",live);
  const inbox = {nextTurn:[],nextStep:[]};
  f.agents.set("two",{ id:"two", session:live,inbox });
  const receipts = new Map();
  f.ctx.fileUploads.stagedFiles.set(live,receipts);
  let finish;
  f.ctx.attachments = { saveFile() { return new Promise(resolve => {finish = resolve;}); } };
  const writes = trackAttachmentWrites(f.ctx);
  const service = f.service(writes);
  const save = f.ctx.attachments.saveFile();
  await assert.rejects(service.clear("one",await f.snap(service)), /attachment is being saved/);
  assert.ok(fs.existsSync(target));
  finish(); await save;
  receipts.set("upload",{attachment:block});
  assert.equal((await service.clear("one",await f.snap(service))).bytesFreed,0);
  assert.ok(fs.existsSync(target));
  receipts.clear();
  f.logs.get("one").push(user(1,[block]));
  inbox.nextStep.push({content:[block]});
  assert.equal((await service.clear("one",await f.snap(service))).bytesFreed,0);
  inbox.nextStep.length = 0;
  f.logs.get("one").push(user(2,[block]));
  live.snapshotEvents = () => [user(0,[block])];
  assert.equal((await service.clear("one",await f.snap(service))).bytesFreed,0);
  assert.ok(fs.existsSync(target));
});
test("failed unlink leaves retained ownership recoverable across restarts", async t => {
  const f = fixture(t);
  const obj = f.put(red,"file",7,["one.txt"]);
  f.logs.set("one",[user(0,[file(red,"one.txt")])]);
  let s = f.service();
  let current = await f.snap(s);
  await s.retainBeforeDelete(current.rows[0],current);
  f.logs.delete("one");
  current = await f.snap(s);
  const unlink = fs.unlinkSync;
  try {
    fs.unlinkSync = function(file) {
      if (file === obj) throw Error("simulated failure after first unlink");
      return unlink.call(this,file);
    };
    await assert.rejects(s.clear("one",current),/simulated/);
  } finally { fs.unlinkSync = unlink; }
  s = f.service();
  current = await f.snap(s);
  assert.equal(current.rows[0].title,"one title");
  assert.equal(current.rows[0].fileCount,1);
  assert.equal((await s.clear("one",current)).bytesFreed,7);
  assert.deepEqual((await f.snap(s)).rows,[]);
});
test("no ledger identity retained after deleting an empty or previously cleared conversation", async t => {
  const f = fixture(t);
  const s = f.service();
  const ledger = path.join(f.home,"sugar-session-storage","attachments-v1.json");
  f.logs.set("empty",[user(0,[{type:"text",text:"message"}])]);
  let current = await f.snap(s);
  await s.retainBeforeDelete(current.rows[0],current);
  f.logs.delete("empty"); s.finishDelete("empty");
  assert.equal(fs.existsSync(ledger),false);
  f.put(red,"file",7,["x"]);
  f.logs.set("cleared",[user(0,[file(red,"x")])]);
  await s.clear("cleared",await f.snap(s));
  current = await f.snap(s);
  await s.retainBeforeDelete(current.rows[0],current);
  assert.equal(JSON.parse(fs.readFileSync(ledger)).sessions[0].id,"cleared");
  f.logs.delete("cleared"); s.finishDelete("cleared");
  assert.deepEqual(JSON.parse(fs.readFileSync(ledger)).sessions,[]);
  const lost = f.put(blue,"image",12);
  f.logs.set("missing",[user(0,[image(blue)])]);
  current = await f.snap(s);
  await s.retainBeforeDelete(current.rows[0],current);
  fs.unlinkSync(lost);
  await s.retainBeforeDelete(current.rows[0],current);
  f.logs.delete("missing"); s.finishDelete("missing");
  assert.deepEqual(JSON.parse(fs.readFileSync(ledger)).sessions,[]);
});
