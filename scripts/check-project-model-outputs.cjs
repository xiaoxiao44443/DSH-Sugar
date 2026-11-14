'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');

// Called after the Sugar module loader has installed the pinned package edits.
module.exports = async function checkProjectModelOutputs(packages, projects) {
  const pkg = name => require(path.join(packages, name, 'lib/index.js'));
  const agent = {id:'viewer', session:{id:'viewer', header:{cwd:projects[0].path}},
    options:{}, ctx:{get() {}}};
  const signal = AbortSignal.timeout(10000);
  const originalText = 'Keep the example /workspace/aaa unchanged.';

  const snapshots = new Map();
  const sources = [[projects[1].path + '/aaa', '/workspace/aaa'], ['/home/deleted/project', '/workspace'], [undefined, null]];
  for (const [index, [cwd]] of sources.entries()) {
    const id = 'reference-' + index;
    snapshots.set(id, {session:{id, cwd, version:2}, capturedThroughSeq:1, events:[
      {type:'user/message', data:{source:{kind:'user'}, content:[{type:'text',text:originalText}]}},
    ]});
  }
  const spills = [];
  const resolver = Object.create(pkg('dsh-session-reference').SessionReferenceResolver.prototype);
  const config = {maxReferences:3, maxReferenceBytes:600};
  Object.defineProperties(resolver, {
    config:{value:config},
    ctx:{value:{sessionQuery:{readSurface:async id => snapshots.get(id)}, get:key => key === 'spillStore' && {
      saveText:async request => { spills.push(request.content); return {path:'/tmp/reference'}; },
    }}},
  });
  const references = [...snapshots.keys()].map(sessionId => ({sessionId}));
  const recall = async () => {
    const {additionalContext} = await resolver.prepare(agent, [], references, {}, signal);
    return JSON.parse(additionalContext.content[0].text.match(/<referenced-sessions>\s*([\s\S]*?)\s*<\/referenced-sessions>/u)[1]);
  };
  const visible = await recall();
  assert.deepEqual(visible.map(s => s.cwd), sources.map(([, expected]) => expected));
  assert.ok(visible.every(s => s.conversation[0].text === originalText));
  snapshots.get('reference-0').events[0].data.content[0].text += 'x'.repeat(1200);
  await recall();
  assert.equal(spills.length, 1);
  assert.ok(spills[0].includes('"cwd": "/workspace/aaa"'));
  assert.ok(!spills[0].includes(projects[1].path));

  const byName = new Map();
  let saved;
  const task = (id, target) => ({id, target, kind:'daily', timeZone:'UTC', scheduledAt:'2026-11-15T00:00:00Z',
    prompt:originalText, title:'Directory fixture'});
  const past = task('archived', {kind:'new_once', sessionId:'reserved', create:{cwd:'/home/deleted/project'}});
  const fixed = task('fixed', {kind:'session', sessionId:agent.id});
  const inheriting = task('default', {kind:'new', create:{}});
  const other = task('other', {kind:'new', create:{cwd:projects[1].path + '/aaa', agentPreset:'test'}});
  const schedule = {
    deliveryLimits:() => ({newSessionIntervalSeconds:60}),
    create:async request => saved = task('created', request.target),
    list:async () => ({items:[saved, other, past, fixed, inheriting]}),
    view:async () => saved,
    edit:async request => saved = {...saved, ...request.patch},
  };
  pkg('dsh-tool-schedule').apply({schedule, inject(_names, activate) { activate(this); },
    tools:{register:tool => byName.set(tool.name, tool)}, systemPrompt:{section(){}, getSectionOrder(){}}});
  const call = async (name, args) => {
    const tool = byName.get(name);
    const result = await tool.execute(args, {agent, signal});
    return JSON.parse(tool.output.render(args, result)[0].text);
  };
  const created = await call('schedule_create', {prompt:originalText, title:'title', target:'new', rrule:'unused-by-fixture'});
  assert.equal(created.target.create.cwd, '/workspace');
  assert.equal(created.prompt, originalText);
  assert.equal(saved.target.create.cwd, projects[0].path);
  assert.equal((await call('schedule_view', {id:saved.id})).target.create.cwd, '/workspace');
  const {items} = await call('schedule_list', {});
  assert.deepEqual(items.map(item => item.target), [created.target,
    {kind:'new', create:{cwd:'/workspace/aaa', agentPreset:'test'}},
    {kind:'new_once', sessionId:'reserved', create:{cwd:'/workspace'}}, fixed.target, inheriting.target]);
  assert.equal(past.target.create.cwd, '/home/deleted/project');
  const edited = await call('schedule_edit', {id:saved.id, action:'update', target:'new_once'});
  assert.equal(edited.target.create.cwd, '/workspace');
  assert.equal(saved.target.create.cwd, projects[0].path);
  console.log('Model outputs passed for schedule tools, referenced-session context and full transcript spills.');
};
