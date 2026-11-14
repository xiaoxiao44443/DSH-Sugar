'use strict';
// Node 24+. Supply the pinned distribution's node_modules/@deepseek-ai directory.
// Add --read-image to exercise the official attachment store with sharp for the current platform.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {createRequire, registerHooks} = require('node:module');
const {pathToFileURL} = require('node:url');
const {posix} = path;
const imageTest = process.argv.includes('--read-image');
const args = process.argv.slice(2).filter(arg => arg !== '--read-image');
const packages = path.resolve(args[0]);
const assets = path.resolve(args[1] || path.join(__dirname, '../app/src/main/assets/guest'));
const fromAssets = createRequire(path.join(assets, 'project-routing.cjs'));
const base = pathToFileURL(packages + path.sep).href;
const guest = 'file:///opt/dsh/node_modules/@deepseek-ai/';
const mockFiles = new Map();
const projects = [
  {id:'one', path:'/workspace/aaa'},
  {id:'two', path:'/home/sugar/older-layout'},
];
const trees = new Map(projects.map(p => [p.id,new Map()]));
const sharedProfile = new Map();
const profileFile = '/home/sugar/.dsh/shared-profile-fixture.txt';
let load;

function adapterRequire(name) {
  if (name === 'node:module') return {registerHooks: hooks => { load = hooks.load; }};
  if (name === 'node:url') return {fileURLToPath: url => new URL(url).pathname};
  if (name === './picker-folders.cjs') return {start: async () => {}};
  if (name === 'node:fs') return {
    readFileSync(file) {
      if (file === '/opt/sugar/workspace-bridge.json') return JSON.stringify({url:'http://127.0.0.1:9000'});
      if (mockFiles.has(file)) return mockFiles.get(file);
      throw Object.assign(Error('absent'),{code:'ENOENT'});
    },
    unlinkSync() {}, mkdirSync() {},
    writeFileSync(file,value) { mockFiles.set(file,value); },
    renameSync(file,to) { mockFiles.set(to,mockFiles.get(file)); mockFiles.delete(file); },
    promises:{readdir:async () => []},
  };
  return fromAssets(name);
}
let adbTransport;
const activity = [];
async function transport(url,opts) {
  if (new URL(url).pathname === '/activity') {
    const id = opts.headers.Authorization.slice('Bearer '.length);
    assert.equal(new URL(url).port, id === 'one' ? '9001' : '9002');
    activity.push(id);
    return new Response(null, {status:204});
  }
  const body = JSON.parse(opts.body);
  if (url === 'http://127.0.0.1:9000') {
    if (adbTransport && (body.op === 'adbShell' || body.op === 'adbStatus')) return adbTransport(body, opts);
    const port = body.id === 'one' ? 9001 : 9002;
    return Response.json({ok:true,result:{id:body.id,root:body.root,url:'http://127.0.0.1:' + port,token:body.id,envId:'env-' + body.id}});
  }
  const project = opts.headers.Authorization.slice('Bearer '.length);
  assert.ok(trees.has(project));
  const [arg, options] = body;
  const resolved = typeof arg === 'string' ? posix.resolve(options?.cwd ?? '/workspace',arg) : arg.targetKey;
  const tree = resolved === profileFile ? sharedProfile : trees.get(project);
  const info = () => tree.has(resolved) ? {type:'file',version:'v1',size:Buffer.byteLength(tree.get(resolved))} : undefined;
  switch (new URL(url).pathname) {
    case '/fs/resolve': return Response.json({result:{displayPath:resolved,targetKey:resolved}});
    case '/fs/lstat':
    case '/fs/stat': return Response.json({result:info()});
    case '/fs/writeText': tree.set(resolved,options); return Response.json({result:{version:'v1',kind:'created'}});
    case '/fs/readText': return Response.json({result:tree.get(resolved)});
    case '/fs/readBytes': return new Response(tree.get(resolved));
    default: throw Error('Unexpected worker operation ' + url);
  }
}
async function main(imageHome) {
  const code = fs.readFileSync(path.join(assets,'project-routing.cjs'),'utf8');
  new Function('require','process','fetch',code)(adapterRequire,{
    argv:['node','/opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js'],
    env:{SUGAR_MANAGEMENT_TOKEN:'test'},
  },transport);
  const router = globalThis[Symbol.for('dsh.sugar.projects')];
  await router.setRegistry({list:()=>projects});
  await require('./check-project-turns.cjs')(packages, assets, router, projects, activity);
  const knownRoot = projects[0].path;
  // Exercise the same router calls the model tool makes, outside AsyncLocalStorage.
  const caller = {id:'device-test', session:{header:{cwd:knownRoot}}};
  assert.equal(router.current(), undefined);
  assert.equal((await router.adbStatus(caller)).id, projects[0].id);
  assert.equal((await router.adbShell(caller,'id')).root, knownRoot);
  assert.throws(() => router.adbStatus({id:caller.id,session:{header:{cwd:projects[1].path}}}), /original project/);
  assert.throws(() => router.adbStatus(), /Missing original workspace/);
  let attempts = 0;
  adbTransport = () => { attempts++; throw TypeError('fetch failed'); };
  await assert.rejects(router.adbShell(caller,'input keyevent BACK'), /本次命令执行结果未知/);
  assert.equal(attempts, 1, 'a lost response must never replay a phone action');
  await assert.rejects(router.adbStatus(caller), /当前无线调试状态未知/);
  adbTransport = () => Response.json({ok:false,error:{code:'adb_shell_error',message:'ADB shell 未返回退出状态'}});
  await assert.rejects(router.adbShell(caller,'id'), {message:'ADB shell 未返回退出状态'});
  const canceled = new AbortController();
  adbTransport = (_body, opts) => new Promise((_,reject) => {
    opts.signal.addEventListener('abort', () => reject(opts.signal.reason), {once:true});
  });
  const pending = router.adbShell(caller,'id',canceled.signal);
  canceled.abort();
  await assert.rejects(pending, {name:'AbortError'});
  adbTransport = undefined;
  const write = {mode:'workspace-write',workspaceRoot:'/workspace'};
  assert.equal(router.projectPolicy(knownRoot,write),write);
  assert.throws(()=>router.projectPolicy(knownRoot,{...write,workspaceRoot:'/workspace/sub'}),/two filesystems/);
  assert.throws(()=>router.projectPolicy(knownRoot,{...write,additionalWritableRoots:['/extra']}),/two filesystems/);
  // Check all namespace adapters, including the merged Creator recipe.
  for (const module of ['dsh-working-directory','dsh-sandbox-policy','dsh-api-workspace-files','dsh-tool-present','dsh-subagent','dsh-creation-recommendations','dsh-client-modules','dsh-tool-bash','dsh-terminal-bash','dsh-api-terminal-controller']) {
    const name=module+'/lib/index.js';
    const patched = load(guest+name,{},()=>({source:fs.readFileSync(path.join(packages,name))}));
    if (['dsh-tool-bash','dsh-terminal-bash','dsh-api-terminal-controller'].includes(module)) {
      require('node:child_process').execFileSync(process.execPath,['--check','--input-type=module'],{input:patched.source});
    }
  }
  registerHooks({load(url,ctx,next) {
    const result = next(url,ctx);
    return url.startsWith(base) ? load(guest+url.slice(base.length),ctx,()=>result) : result;
  }});
  await require('./check-project-subagents.cjs')(packages, router, projects);
  await require('./check-project-model-outputs.cjs')(packages, projects);
  const pkg = name => require(path.join(packages,name,'lib/index.js'));
  const {WorkingDirectoryService} = pkg('dsh-working-directory');
  const {WorkspaceFiles} = pkg('dsh-api-workspace-files');
  class ProjectFs {}
  router.routeFs(ProjectFs,Error);
  const fileSystem = new ProjectFs();
  const registration = new Map();
  const working = Object.create(WorkingDirectoryService.prototype);
  // Exercise actual DSH projection init, history replay, wire view and current directory.
  let projection, origin;
  Object.defineProperty(working,'ctx',{value:{
    sessionProjections:{register(value) {
      if (value.key === 'sugarWorkspaceRoot') { origin=value; return {}; }
      projection=value;
      return {stateOf:s=>registration.get(s) ?? value.init(s.header)};
    }},
    systemPrompt:{context(){},getContextOrder(){}},on(){},effect(){},
  }});
  const {Service} = require(path.join(packages,'cordis'));
  await working[Service.init]();
  const preview = Object.create(WorkspaceFiles.prototype);
  Object.defineProperties(preview,{
    ctx:{value:{fs:fileSystem}}, config:{value:{maxFileBytes:1024}},
  });
  const registeredTools = new Map();
  const attachments = imageTest && new (pkg('dsh-attachment-local').LocalAttachmentStore)(
    new (require(path.join(packages,'cordis')).Context)(), {dshHome:imageHome});
  const llm = {resolveModelInfo: async () => ({inputModalities:['image']})};
  const sharp = imageTest && require(path.join(packages,'../sharp'));
  let committed;
  const ctx = {
    fs:fileSystem,tools:{register(tool){registeredTools.set(tool.name,tool);}},
    inject(_names, activate) { activate(this); },
    get: name => ({attachments, llm})[name],
    agents:{turnBoundaryOf:()=>({openTurnStartSeq:1,lastTurn:1})},
    workingDirectory:{ensure:async agent=>working.get(agent.session)},
    on(name,fn){assert.equal(name,'tools/result');committed=fn;},
    systemPrompt:{section(){},getSectionOrder(){}}, emit(){}, waterfall:async()=>undefined,
  };
  pkg('dsh-tool-present').apply(ctx,{maxFiles:8});
  pkg('dsh-tool-fs').apply(ctx,{read:true,readImage:imageTest,write:true,readLimit:10,readMaxLineLength:100,readMaxBytes:1024,readStreamMinSize:1024});
  const present=registeredTools.get('present');
  const profileTools = new Map();
  let profileMode;
  const sandboxPolicy = {resolve: () => ({mode:profileMode, workspaceRoot:'/workspace'})};
  const sandboxFs = Object.create(pkg('dsh-fs-sandbox').SandboxedFileSystem.prototype);
  Object.defineProperties(sandboxFs,{ctx:{value:{sandboxPolicy}},defaultMode:{value:'workspace-write'}});
  const sandboxCtx = {...ctx, fs:sandboxFs,
    tools:{register:tool=>profileTools.set(tool.name,tool)},
    get:name=>name === 'sandboxPolicy' ? sandboxPolicy : ctx.get(name)};
  pkg('dsh-tool-fs').apply(sandboxCtx,{read:true,write:true,readLimit:10,readMaxLineLength:100,readMaxBytes:1024,readStreamMinSize:1024});
  // Exercise the actual browser tap/open callbacks with a session that has since changed cwd.
  function browserSource(module) {
    const id='@deepseek-ai/'+module;
    assert.notEqual(router.clientRevision(id,'000000000000'),'000000000000');
    return router.clientBundle(id,fs.readFileSync(path.join(packages,module,'lib/client.js'))).toString();
  }
  const chat=browserSource('dsh-client-ui-chat'), cards=browserSource('dsh-client-ui-deliverables');
  const tree=browserSource('dsh-client-ui-sidebar-files');
  const treeFns=tree.match(/function treeRequestPath\([\s\S]*?(?=function pane\()/)[0];
  const treeRequest=new Function('windowsRoot',treeFns+'\nreturn treeRequestPath;')(()=>false);
  for (const {path:root} of projects) {
    assert.equal(treeRequest(root,root),'.');
    assert.equal(treeRequest(root+'/aaa',root),'aaa');
    assert.equal(treeRequest(root+'/aaa/index.html',root),'aaa/index.html');
    assert.throws(()=>treeRequest(root+'-sibling',root),/outside/);
  }
  const openBody=chat.match(/openFile:(async\(path,options\)=>\{[\s\S]*?await Promise.resolve\(\)\}),/)[1];
  const tapBody=cards.match(/onPreview:(\(\)=>\{openFile\(file\.path[^;]*;\}),/)[1];
  let opened;
  const addressBody=chat.match(/function fileAddressFor\([\s\S]*?(?=function fileMediaUrl\()/)[0];
  const address=new Function('sessionFileAddress','isAbsoluteWorkspacePath',addressBody+'\nreturn fileAddressFor;')((_id,p)=>p,p=>p.startsWith('/'));
  const open = new Function('ctx','session','sessionId','resolveWorkspacePath','fileAddressFor','return '+openBody)(
    {sidebarRight:{openResource:address=>{opened=address;}}},
    {projections:{faceOf:key=>({getSnapshot:()=>key === 'sugarWorkspaceRoot' ? '/workspace' : '/workspace/moved'})}},'session-one',
    (cwd,file)=>posix.resolve(cwd,file),address,
  );
  const tap=new Function('file','openFile','return '+tapBody);
  const signal = AbortSignal.timeout(10000);
  let firstTarget;
  for (const project of projects) {
    let delivery;
    const session = {header:{cwd:project.path},requestHeader:()=>({config:{provider:'fixture',model:'vision'}}),
      append(event,data) {assert.equal(event,'deliverables/presented');delivery=data;}};
    const agent = {session,id:'session-'+project.id};
    const exec = {agent,signal,callId:'deliver'};
    const scope = {workspaceRoot:project.path,sessionId:agent.id};
    assert.equal(working.get(session),'/workspace');
    assert.equal(origin.wire.view(origin.init(session.header)),'/workspace');
    assert.equal(projection.wire.view(projection.init(session.header)),'/workspace');
    const historical = projection.apply(projection.init(session.header),{type:'working-directory/change',data:{cwd:'/workspace/aaa'}});
    assert.equal(projection.wire.view(historical),'/workspace/aaa');
    assert.equal(router.sessionRoot(project.path+'/aaa'),'/workspace/aaa');
    assert.throws(()=>router.childDirectory(project.path+'/aaa','/workspace'),/parent workspace/);
    const names=['index.html','/workspace/aaa/index.html'];
    const expected=['/workspace/index.html','/workspace/aaa/index.html'];
    await router.within(project.path,async () => {
      const attachment = '/home/sugar/.dsh/attachments/v1/objects/aa/' + 'a'.repeat(64);
      assert.equal(fileSystem.processPathFromHostPath(attachment),attachment);
      assert.equal(fileSystem.processPathFromHostPath('/home/sugar/.dsh/unrelated.txt'),undefined);
      const writeProfile = text => profileTools.get('write').execute({file_path:profileFile, content:text},exec);
      const readProfile = () => profileTools.get('read').execute({file_path:profileFile},exec);
      if (project === projects[0]) {
        profileMode = 'danger-full-access';
        assert.equal((await writeProfile('shared')).path,profileFile);
      }
      for (profileMode of ['workspace-write','read-only']) {
        assert.equal((await readProfile()).lines[0].text,'shared');
        await assert.rejects(writeProfile('denied'),/file access denied/);
      }
      profileMode = 'danger-full-access';
      if (project === projects[1]) {
        assert.equal((await writeProfile('updated')).path,profileFile);
        assert.equal(sharedProfile.get(profileFile),'updated');
      }
      if (imageTest) {
        const width = project.id === 'one' ? 2 : 4;
        const bytes = await sharp({create:{width,height:3,channels:4,background:'#29c'}}).png().toBuffer();
        const tree = trees.get(project.id);
        tree.set('/workspace/q1.png', bytes);
        if (project.id === 'one') tree.set('/workspace/only-in-one.png', bytes);
        const tool = registeredTools.get('read_image');
        for (const file_path of ['q1.png', '/workspace/q1.png']) {
          const result = await tool.execute({file_path},exec);
          assert.equal(result.path,'/workspace/q1.png');
          const [text, picture] = tool.output.render({file_path},result);
          assert.match(text.text,/image\/png/);
          assert.equal(picture.type,'image');
          const stored = await attachments.readImage(picture.attachment);
          const metadata = await sharp(stored.data).metadata();
          assert.deepEqual([metadata.width,metadata.height], [width,3]);
        }
        if (project.id === 'two') await assert.rejects(
          tool.execute({file_path:'only-in-one.png'},exec),/not found/);
      }
      for (const [i,name] of names.entries()) {
        const target=await fileSystem.resolve(name,{cwd:working.get(session)});
        assert.equal(fileSystem.processPath(target),expected[i]);
        const written=await registeredTools.get('write').execute({file_path:name,content:project.id+'-'+i},exec);
        assert.equal(written.path,expected[i]);
        const read=await registeredTools.get('read').execute({file_path:name},exec);
        assert.equal(read.path,expected[i]);
        assert.equal(read.lines[0].text,project.id+'-'+i);
        if (i === 0 && !firstTarget) firstTarget=target;
      }
      const nested = await registeredTools.get('read').execute({file_path:'aaa/index.html'},exec);
      assert.equal(nested.path,expected[1]);
      assert.equal(nested.lines[0].text,project.id+'-1');
      if (project.id === 'two') assert.throws(()=>fileSystem.processPath(firstTarget),/another workspace/);
      const result=await present.execute({files:names.map(path=>({path}))},exec);
      assert.deepEqual(result.files.map(f=>f.path),expected);
      committed(exec,{isError:false});
    },agent.id);
    assert.deepEqual(delivery.files.map(f=>f.path),['index.html','aaa/index.html']);
    for (const [i,file] of delivery.files.entries()) {
      const reply=await preview.readBytes(scope,file.path,{},signal);
      assert.equal(reply.absolutePath,expected[i]);
      assert.equal(reply.data.toString(),project.id+'-'+i);
      for (const saved of [file.path,expected[i]]) {
        tap({path:saved},open)();
        assert.equal(opened,file.path);
        assert.equal((await preview.readBytes(scope,opened,{},signal)).data.toString(),project.id+'-'+i);
      }
    }
    registration.set(session,historical);
    await router.within(project.path,async()=>{
      assert.equal(working.get(session),'/workspace/aaa');
      const currentFile = await registeredTools.get('read').execute({file_path:'index.html'},exec);
      assert.equal(currentFile.path,expected[1]);
      assert.equal(currentFile.lines[0].text,project.id+'-1');
      assert.equal((await present.execute({files:[{path:'index.html'}]},exec)).files[0].path,expected[1]);
    },agent.id);
  }
  await assert.rejects(router.within(projects[1].path,async()=>{},'session-one'),/original project/);
  console.log('Project paths passed: pinned adapters, working-directory replay, relative and absolute I/O, same-name directories, persisted deliverables, preview bytes and cross-project isolation.');
  if (imageTest) console.log('read_image passed: per-project PNG bytes, relative and absolute paths, native sharp normalization, durable model attachment and cross-project denial.');
}
const imageHome = imageTest && fs.mkdtempSync(path.join(require('node:os').tmpdir(),'sugar-read-image-'));
main(imageHome).catch(error=>{console.error(error);process.exitCode=1;}).finally(()=>{
  if (imageHome) fs.rmSync(imageHome,{recursive:true,force:true});
});
