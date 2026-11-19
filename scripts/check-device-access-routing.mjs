import assert from 'node:assert/strict';
import {copyFile, mkdir, mkdtemp, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {registerHooks} from 'node:module';

// Prompt contract checks: these protect the routing advice and existing screen /
// Root permissions; they do not claim to prove a model will follow that advice.
registerHooks({resolve(spec, context, next) {
  return spec === '/opt/dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js'
    ? {url:'data:text/javascript,export const defineTool = spec => spec',shortCircuit:true}
    : next(spec, context);
}});
const assets = fileURLToPath(new URL('../app/src/main/assets/guest/', import.meta.url));
const skills = new Map(), tools = new Map();
let policy, guideCheck;
globalThis[Symbol.for('dsh.sugar.projects')] = {
  registerAdbGuide(check) { guideCheck = check; return () => {}; },
  registerRootPolicy() { return () => {}; },
};
const ctx = {
  tools:{register:tool => tools.set(tool.name,tool)},
  skills:{register:skill => skills.set(skill.name,skill)},
  systemPrompt:{getSectionOrder:() => 3000,section:section => {policy=section.text;}},
  effect:setup => setup(), on() {},
};
const {apply:applyAdb} = await import('../app/src/main/assets/guest/dsh-sugar-adb/index.js');
applyAdb(ctx);
const work = await mkdtemp(join(tmpdir(),'sugar-device-routing-'));
try {
  const pluginDir = join(work,'mobile');
  await mkdir(pluginDir);
  for (const name of ['index.js','package.json'])
    await copyFile(join(assets,'dsh-sugar-mobile-use',name),join(pluginDir,name));
  await copyFile(join(assets,'mobile-use-guide.md'),join(pluginDir,'guide.md'));
  const {apply:applyMobile} = await import(pathToFileURL(join(pluginDir,'index.js')));
  applyMobile(ctx);
  for (const [name, text] of [
    ['system prompt',policy],
    ...['android-adb','android-root','mobile-use'].map(name => [name,skills.get(name).content]),
  ]) {
    assert.match(text,/diagnostics.*SoC.*RAM.*Android version/,name);
    assert.match(text,/adb_status.*sugar-root --status.*already enabled, authorized Root/,name);
    assert.match(text,/neither.*non-UI\/background.*stop.*connect ADB/,name);
    assert.match(text,/Accessibility.*alternative.*current screen.*visible actions/,name);
    assert.match(text,/Ask before switching.*mobile_use.*unless.*already approved.*task/,name);
    assert.match(text,/Do not automatically request Root authorization/,name);
  }
  assert.match(skills.get('android-root').description,/Read-only device diagnostics/);
  assert.match(skills.get('android-root').content,/ordinary sessions request approval.*Full-access sessions use their existing permission/);
  assert.match(policy,/open or operate an app, call mobile_use\(\{\}\).*choose the screen/);
  assert.match(skills.get('mobile-use').content,/app name alone does not specify the main screen/);
  assert.equal(tools.get('mobile_use').parameters.screen.default,'ask');
  // An updated guide must not revoke ADB access in a restored conversation that
  // read the previous shipped guide. Use its frozen intro and unchanged commands.
  const current = skills.get('android-adb').content;
  const oldIntro = 'Prefer mobile_use({}) for phone interaction. Use connected ADB for device diagnostics, an explicit ADB request, or an action the selected driver lacks. For virtual input use input -d ID ... with this task’s current displayId; use mobile-use for virtual screenshots and launches. Observe an uncertain result before acting again.';
  const previous = [oldIntro,...current.split('\n\n').slice(1)].join('\n\n');
  const events = [
    {type:'tool/call',data:{name:'skill',callId:'old'}},
    {type:'tool/result',data:{message:{toolCallId:'old',content:[{type:'text',text:'<skill_content name="android-adb">\n'+previous}]}}},
  ];
  assert(guideCheck({seq:events.length,eventAt:i=>events[i]}));
  console.log('PASS device access routing: foreground fallback requires consent, task consent persists, screen choice and Root approvals remain intact');
} finally {
  await rm(work,{recursive:true,force:true});
}
