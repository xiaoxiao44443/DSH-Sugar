import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import { pathToFileURL } from 'node:url';
import { join } from 'node:path';

// Tests can run with the APK's extracted @deepseek-ai packages. Without a
// guest runtime installed, exercise the plugin against the tool/skill contracts.
const toolsModule = '/opt/dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js';
const modules = process.env.DSH_TEST_NODE_MODULES;
const sdk = modules ? pathToFileURL(join(modules,'@deepseek-ai/dsh-tools/lib/index.js')).href
  : 'data:text/javascript,export const defineTool = spec => spec';
registerHooks({resolve(spec, context, next) {
  return spec === toolsModule ? {url:sdk, shortCircuit:true} : next(spec,context);
}});
const { apply } = await import('../app/src/main/assets/guest/dsh-sugar-adb/index.js');
const tools = new Map(), skills = new Map(), calls = [];
let connected = true, statusReads = 0, failure, guideCheck;
globalThis[Symbol.for('dsh.sugar.projects')] = {
  registerAdbGuide(check) { guideCheck=check; return () => { guideCheck=undefined; }; },
  async adbStatus() {
    statusReads++;
    return {supported:true, paired:true, enabled:connected, connected, busy:false,
      phase:connected ? 'connected' : 'paired', message:connected ? '已连接本机无线调试' : '上次命令超时'};
  },
  async adbShell(agent, command, signal) {
    assert(agent.session);
    signal.throwIfAborted();
    if (!connected) throw Error('请先在手机控制设置中连接本机无线调试');
    if (failure) throw failure;
    calls.push(command);
    return {stdout:'shell\n',stderr:'',exitCode:0,truncated:false};
  }
};
apply({effect:setup => setup(),tools:{register:t => tools.set(t.name,t)}, skills:{register:s => skills.set(s.name,s)}});
const guide = skills.get('android-adb');
assert(guide.content.includes('adb_shell'));
assert(guide.description.includes('mobile_use({})'));
assert(guide.content.includes('an explicit ADB request') && guide.content.includes('selected driver lacks'));
assert(guide.content.includes('input -d ID') && guide.content.includes('displayId'));
const imports = modules ? await import(pathToFileURL(join(modules,'@deepseek-ai/dsh-skill/lib/index.js'))) : null;
const rendered = imports ? imports.renderSkillContent(guide) : '<skill_content name="android-adb">\n' + guide.content;
const command = 'getprop ro.build.version.release';
function agent(events=[]) {
  return {session:{get seq(){return events.length;},eventAt:i=>events[i]},events};
}
const exec = (a,signal=new AbortController().signal) => ({agent:a,signal,callId:'caller'});
const shell=(a,cmd=command,signal) => tools.get('adb_shell').execute({command:cmd},exec(a,signal));
const first=agent(), other=agent();
assert.equal((await tools.get('adb_status').execute({},exec(first))).connected,true);
await assert.rejects(shell(first),/android-adb/);
function read(a,id,name='skill',text=rendered,isError=false) {
  a.events.push({type:'tool/call', data:{name,callId:id}},
    {type:'tool/result', data:{message:{toolCallId:id, isError, content:[{type:'text',text}]}}});
}
read(first,'untrusted','bash');
read(first,'failure','skill',rendered,true);
read(first,'collision','skill','<skill_content name="android-adb">another provider');
await assert.rejects(shell(first),/android-adb/);
const oldSession=agent();
read(oldSession,'old','skill','<skill_content name="android-adb">\n' + "Control the Android phone running DSH Sugar. Start with adb_status. If disconnected, ask the user to open Sugar’s ADB settings and finish wireless debugging pairing/connection there. Keep the pairing code out of the conversation.\n\nUse adb_shell with the device shell command itself, for example \"id\" or \"getprop ro.build.version.release\". Each call runs once as Android’s shell user on the phone, outside the Linux project workspace. It returns stdout, stderr, exitCode and truncated.\n\nA connection established manually in Sugar authorizes commands on this device until it is disconnected. For UI actions, verify the current screen first, for example with \"wm size\" and \"dumpsys activity activities\". A command such as \"input tap X Y\" affects the user’s real foreground app. Verify the result before another action.\n\nUse short text commands. The device enforces a 15-second deadline and a combined 64 KiB output cap. Do not use interactive commands or binary image output with this text tool. Device paths refer to Android storage; project files live in a separate sandbox.");
assert(guideCheck(oldSession.session), 'sessions using the previous shipped guide stay authorized');
assert(!guideCheck(first.session));
read(first,'normal');
assert(guideCheck(first.session));
assert(guide.content.includes('adb exec-out screencap -p > screen.png'));
assert(guide.content.includes('adb ui-dump > ui.xml'));
assert(guide.content.includes('current project workspace'));
assert(guide.content.includes('adb push LOCAL') && guide.content.includes('adb pull /absolute/device/file'));
const previousDeviceSession = agent();
const previousCapabilitiesSession = agent();
// Reconstruct the two shipped guide versions using their frozen routing prose.
const oldKeyRouting = "Use connected ADB directly for diagnostics, package checks (adb shell pm path PACKAGE), file transfers and explicit ADB requests. For ordinary UI steps first call mobile_use({}) and use the selected screen’s supported phone actions. Background UI steps use the virtual screen even when ADB is requested. With an active screen, use connected adb_shell for a key the driver lacks (Enter, Tab, D-pad or Delete). On main use input keyevent KEYCODE_ENTER; on virtual use input -d ID keyevent KEYCODE_ENTER with the current task’s displayId.";
const oldResult = "Re-observe after a timeout, stale observation or uncertain result before deciding whether to act again. With both screen modes off, phase=\"adb\" permits default-display ADB for an unspecified target.";
const oldCapture = "Only on Android 10 main with capabilities.image=false, use adb exec-out screencap -p > screen.png for the missing capture.";
const captureGuide = "If the main driver explicitly reports capabilities.image=false and Sugar has connected ADB, use adb exec-out screencap -p > screen.png in project Bash. On Android 10 use the main screen UI tree; Sugar wireless ADB requires Android 11 or later. A denied capture, secure window or timeout does not authorize switching capture drivers.";
const virtualText = "If the active virtual screen explicitly reports capabilities.text=false, focus the target field and use adb_shell to enter short ASCII text, e.g. input -d ID text hello%sworld. Use the current task’s displayId and a single argument (up to 512 characters: ASCII letters, digits, ._@+/-, and %s for a space).";
const content = guide.content.split('\n\n').slice(1);
const previousCapabilitiesGuide = [[oldKeyRouting,oldCapture,oldResult].join(' '), ...content].join('\n\n');
const oldVirtualGuide = [[oldKeyRouting,captureGuide,oldResult].join(' '), ...content,virtualText].join('\n\n');
const previousVirtual = agent();
read(previousVirtual,'previous-virtual','skill',rendered.replace(guide.content,oldVirtualGuide));
assert(guideCheck(previousVirtual.session), 'sessions using the previous virtual text guide stay authorized');
read(previousCapabilitiesSession,'previous-capabilities','skill',rendered.replace(guide.content,previousCapabilitiesGuide));
assert(guideCheck(previousCapabilitiesSession.session), 'previous capability guides stay valid after adding virtual text');
const previousDeviceIntro = 'Use connected ADB directly for steps that need no UI, including package checks (adb shell pm path PACKAGE), diagnostics and file transfer. For UI steps first call mobile_use({}) to let Sugar select the screen. A background UI or a request to leave the main screen undisturbed needs the virtual display, including when ADB is requested. Keep screenshots and input on that display through mobile-use. For explicit ADB without this constraint, use the device commands below. When both screen modes are off, phase="adb" permits default-display ADB for an unspecified target.';
const oldDeviceGuide = [previousDeviceIntro, ...previousCapabilitiesGuide.split('\n\n').slice(1,-1)].join('\n\n');
read(previousDeviceSession,'previous-device','skill',rendered.replace(guide.content,oldDeviceGuide));
assert(guideCheck(previousDeviceSession.session), 'the new UI dump instructions keep the previously shipped guide valid');
connected=false;
assert.deepEqual(await tools.get('adb_status').execute({},exec(first)), {
  supported:true, paired:true, enabled:false, connected:false, busy:false, phase:'paired', message:'上次命令超时'
});
await assert.rejects(shell(first),/连接/);
assert.equal(calls.length,0);
connected=true;
failure = Error('ADB shell 流标识无效');
await assert.rejects(shell(first),error => error === failure);
failure = undefined;
assert.equal((await shell(first)).exitCode,0);
assert.equal(statusReads, 2, 'shell must use the atomic host operation, without a stale status preflight');
await assert.rejects(shell(other),/android-adb/);
const restored=agent(first.events.slice());
assert.equal((await shell(restored)).stdout,'shell\n');
for (const invalid of ['  ','two\nlines', 'rm\u202e', '界'.repeat(3000)])
  await assert.rejects(shell(first, invalid),/8192/);
const abort=new AbortController();
abort.abort();
await assert.rejects(shell(first,command,abort.signal), {name:'AbortError'});
assert.deepEqual(calls,[command,command]);
console.log('PASS DSH Android ADB skill, resumed sessions and device connection gating');
