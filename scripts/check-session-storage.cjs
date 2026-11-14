'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const {createHash} = require('node:crypto');
const {createSessionStorage} = require('../app/src/main/assets/guest/session-storage.cjs');

async function verify(home) {
  const root = path.join(home, 'sessions');
  const group = path.join(root, '--project--');
  const headers = new Map(), claims = new Set(), live = new Map(), retained = new Set();
  const callbacks = new Map(), destructors = [];
  const names = ['old','normal','resident','active','other','combined','interrupted','interrupted-before',
    'archived-media','persist-changing','linked','starting'];
  const canonical = id => path.join(group, id, 'session.v4.jsonl.zstd');
  const actual = id => id === 'old' ? path.join(group, id, 'session.jsonl') : canonical(id);
  for (const [i, id] of names.entries()) {
    headers.set(id, {id, cwd: '/workspace', createdAt: i});
    await fs.mkdir(path.dirname(actual(id)), {recursive: true});
    await fs.writeFile(actual(id), 'test transcript');
  }
  const work = {title: 'Workspace', path: '/workspace/one', sessionIds: [...names], async detachSession(id) {
    this.sessionIds = this.sessionIds.filter(other => other !== id);
  }};
  const registry = {archivedSessionIds: ['old','archived-media'], pinnedSessionIds: ['normal','interrupted-before'], list: () => [work],
    async archiveSession(id) {
      if (!this.archivedSessionIds.includes(id)) this.archivedSessionIds.push(id);
      await this.unpinSession(id);
    },
    async unarchiveSession(id) {this.archivedSessionIds = this.archivedSessionIds.filter(other => other !== id);},
    async unpinSession(id) {this.pinnedSessionIds = this.pinnedSessionIds.filter(other => other !== id);},
    async pinSession(id) {this.pinnedSessionIds.push(id);}
  };
  const p = {root, name: 'session-persistence-jsonl', locate: ({id}) => ({kind: 'jsonl', path: canonical(id)}),
    tracker: {claimWrite(id) {assert(!claims.has(id)); claims.add(id);}, releaseClaim(id) {claims.delete(id);}},
    async stat(id) {
      if (!headers.has(id)) return;
      const f = await fs.lstat(actual(id)).catch(error => {if (error.code !== 'ENOENT') throw error;});
      if (f) return {header: headers.get(id), location: {kind:'jsonl', path:actual(id)}, revision:String(f.size), sizeBytes:f.size};
    },
    async list() {return (await Promise.all(names.map(id => this.stat(id)))).filter(Boolean);},
    async acquireLease(id) {assert(claims.has(id)); return {release: async () => {}};}
  };
  let releaseStart;
  const agents = {get: id => live.get(id)?.agent, async create({sessionId: id}) {
    if (id === 'starting') await new Promise(resolve => {releaseStart = resolve;});
    const session = {header: headers.get(id), id};
    const agent = {id, session, status:'idle'};
    const handle = {agent, async dispose() {live.delete(id); callbacks.get('agent/disposed')({agent});}};
    live.set(id, handle);
    return handle;
  }, async resume({resumeSessionId}) {return this.create({sessionId:resumeSessionId});}};
  const sessions = {list:() => [...live.values()].map(h => h.agent.session), get:id => live.get(id)?.agent.session,
    flush:async session => {assert(live.has(session.id));return true;}};
  const ctx = {agents, sessions, sessionPersistence:p, workspaceRegistry:registry, get(k) {return this[k];},
    sessionProjectionCache:{cachedSnapshot: header => ({values:{title:'Title '+header.id}}), requireTable:()=>({delete:async()=>{}})},
    on(event, cb) {callbacks.set(event, cb);}, effect(fn) {destructors.push(fn());},
    emit() {}, async waterfall(event, {sessionId}) {return sessionId === 'active' ? [{kind:'job'}] : [];}
  };
  const cleared = new Set();
  const finalized = new Set();
  let partial = false;
  let scans = 0;
  const files = Array.from({length:53}, (_, index) => ({fileId:index.toString(16).padStart(64,'0'),kind:'file', name:'document'+index, bytes:17, shared:false}));
  const images = {
    async snapshot(rows) {
      scans++;
      const seen = new Set(rows.map(r => r.id));
      return {rows:[...rows.map(r=>({...r, fileCount:1, imageCount:0})),
        ...[...retained].filter(id => !seen.has(id) && !cleared.has(id)).map(id=>({id,workspace:'Workspace',attachmentsOnly:true,canDelete:true}))],
        filesBySession:new Map([['old',files]]),partial};
    },
    async retainBeforeDelete({id}) {
      const revision = (await p.stat(id)).revision;
      if (id === 'persist-changing') await fs.appendFile(actual(id),'tail');
      if (id === 'normal') await assert.rejects(agents.resume({resumeSessionId:id}),/active/);
      retained.add(id);
      return {revision};
    },
    finishDelete(id) {finalized.add(id);},
    async clear(id) {cleared.add(id); return {bytesFreed:17};}
  };
  images.resolveAttachment = async (id,fileId,persistence) => {
    assert.equal(persistence,p);
    assert.equal(id,'old');
    return {id,fileId,relativePath:'file-objects/ab/'+fileId};
  };
  const api = createSessionStorage(ctx, () => images);
  const remove = (id,mode='conversation') => api({op:'delete',id,mode});
  await agents.resume({resumeSessionId:'resident'});
  assert((await api({op:'list'})).rows.some(r=>r.id === 'old' && r.archived && r.title === 'Title old'));
  assert((await api({op:'list'})).rows.some(r=>r.id === 'active' && r.active && !r.canDelete));
  const byWorkspace = registry.list;
  registry.list = () => [work, {title:work.title, path:'/workspace/two', sessionIds:['other']}];
  const grouped = (await api({op:'list'})).rows;
  const firstWorkspace = grouped.find(r => r.id === 'active');
  const secondWorkspace = grouped.find(r => r.id === 'other');
  assert.equal(firstWorkspace.workspace, secondWorkspace.workspace);
  assert.notEqual(firstWorkspace.workspaceKey, secondWorkspace.workspaceKey);
  registry.list = byWorkspace;
  const scansBeforeDetail = scans;
  const detail = (id, offset) => api({op:'detail',id,offset});
  const firstPage = await detail('old',0);
  assert.equal(firstPage.row.workspaceKey,work.path);
  assert.equal(firstPage.totalFiles,53);
  assert.equal(firstPage.files.length,24);
  assert.deepEqual([...(firstPage.files), ...(await detail('old',24)).files, ...(await detail('old',48)).files], files);
  assert.deepEqual((await detail('old',61)).files,[]);
  assert.deepEqual((await detail('active')).files,[]);
  const attachment = (id,fileId) => api({op:'resolveAttachment',id,fileId});
  assert.equal((await attachment('old',files[17].fileId)).fileId,files[17].fileId);
  await assert.rejects(attachment('active',files[17].fileId),/not found/);
  await assert.rejects(attachment('../../unlisted',files[17].fileId),/not found/);
  await assert.rejects(attachment('old','../../foreign'),/Invalid/);
  await assert.rejects(detail('old',-1),/Invalid/);
  await assert.rejects(detail('other-workspace-id'),/not found/);
  assert.equal(scans,scansBeforeDetail);
  await assert.rejects(remove('active'), /active/);
  const starter = agents.resume({resumeSessionId:'starting'});
  await assert.rejects(remove('starting'), /active/);
  releaseStart();
  await starter;
  await assert.rejects(remove('../../unknown'), /not found/);
  partial = true;
  await assert.rejects(remove('old'), /incomplete/);
  partial = false;
  await remove('archived-media','attachments');
  assert(registry.archivedSessionIds.includes('archived-media'));
  assert.equal(agents.get('archived-media'),undefined);
  assert(await p.stat('archived-media'));
  await assert.rejects(remove('persist-changing'),/changed/);
  assert(await p.stat('persist-changing'));
  const diverted = path.join(home, 'foreign-record');
  await fs.rename(path.dirname(actual('linked')), diverted);
  await fs.symlink(diverted, path.dirname(actual('linked')), process.platform === 'win32' ? 'junction' : 'dir');
  await assert.rejects(remove('linked'),/directory/);
  assert.equal(await fs.readFile(path.join(diverted,path.basename(actual('linked'))),'utf8'),'test transcript');
  await remove('old');
  assert.equal(await p.stat('old'), undefined);
  assert(!registry.archivedSessionIds.includes('old'));
  assert(!work.sessionIds.includes('old'));
  assert((await api({op:'list'})).rows.find(r=>r.id === 'old').attachmentsOnly);
  assert.equal((await detail('old')).row.workspaceKey, work.path);
  await remove('old','attachments');
  assert(!(await api({op:'list'})).rows.some(r=>r.id === 'old'));
  await remove('resident');
  assert.equal(sessions.get('resident'), undefined);
  assert.equal(await p.stat('resident'), undefined);
  await remove('combined','both');
  assert(cleared.has('combined'));
  assert.equal(await p.stat('combined'), undefined);
  await remove('normal','attachments');
  assert(await p.stat('normal'));
  assert(registry.pinnedSessionIds.includes('normal'));
  await remove('normal');
  for (const [id, commit] of [['interrupted',true],['interrupted-before',false]]) {
    const entry = path.join(home,'sugar-session-removals',createHash('sha256').update(id).digest('hex'));
    await fs.mkdir(entry,{recursive:true});
    await fs.writeFile(path.join(entry,'id.json'),JSON.stringify({id,archived:false,pinned:!commit}));
    await registry.archiveSession(id);
    if (commit) await fs.rename(path.dirname(actual(id)),path.join(entry,'record'));
  }
  await api({op:'list'});
  assert(!work.sessionIds.includes('interrupted'));
  assert(finalized.has('interrupted') && !finalized.has('interrupted-before'));
  assert(!registry.archivedSessionIds.includes('interrupted-before'));
  assert(registry.pinnedSessionIds.includes('interrupted-before'));
  assert(await p.stat('other'));
  assert.equal(claims.size,0);
  // Concurrent requests are ordered. The second sees only the retained attachments.
  const [removed,also] = await Promise.allSettled([remove('other'), remove('other')]);
  assert.equal(removed.status,'fulfilled');
  assert.equal(also.status,'rejected');
  destructors.reverse().forEach(done=>done());
}

(async () => {
  const temp = await fs.mkdtemp(path.join(os.tmpdir(),'sugar-check-session-storage-'));
  try {await verify(temp); console.log('Session storage fixtures passed.');}
  finally {await fs.rm(temp,{force:true,recursive:true});}
})().catch(error=>{console.error(error);process.exitCode=1;});
