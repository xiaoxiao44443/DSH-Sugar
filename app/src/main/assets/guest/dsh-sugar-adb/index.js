import { defineTool } from '/opt/dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js';

export const inject = ['tools', 'skills'];
const skillName = 'android-adb';
const previousGuide = [
  'Control the Android phone running DSH Sugar. Start with adb_status. If disconnected, ask the user to open Sugar’s ADB settings and finish wireless debugging pairing/connection there. Keep the pairing code out of the conversation.',
  'Use adb_shell with the device shell command itself, for example "id" or "getprop ro.build.version.release". Each call runs once as Android’s shell user on the phone, outside the Linux project workspace. It returns stdout, stderr, exitCode and truncated.',
  'A connection established manually in Sugar authorizes commands on this device until it is disconnected. For UI actions, verify the current screen first, for example with "wm size" and "dumpsys activity activities". A command such as "input tap X Y" affects the user’s real foreground app. Verify the result before another action.',
  'Use short text commands. The device enforces a 15-second deadline and a combined 64 KiB output cap. Do not use interactive commands or binary image output with this text tool. Device paths refer to Android storage; project files live in a separate sandbox.'
].join('\n\n');

const previousCliGuide = [
  'Control the Android phone running DSH Sugar from your existing Bash tool. First use adb devices or adb get-state to check whether the user has connected their phone in Sugar’s ADB settings. Pairing, connection and disconnection are managed in Sugar. Keep pairing codes out of the conversation.',
  'Run adb shell COMMAND to execute a command as Android’s shell user. For example, adb shell getprop ro.build.version.release or adb shell wm size. The phone is separate from the Linux project workspace. A manually connected phone authorizes commands until disconnected. An input tap or keyevent affects the real foreground app; inspect the current screen before acting and verify the result.',
  'Use adb exec-out screencap -p > screen.png to save a binary screenshot directly in the project. adb push LOCAL /absolute/device/file copies a project file to the phone. adb pull /absolute/device/file [LOCAL] copies a phone file to the project. Each transfer handles one file. Quote paths containing spaces. A remote path is an absolute filename, including the filename when pushing.',
  'Use stdout, stderr and the command exit code to check results. The CLI supports one connected phone and noninteractive commands. The older adb_status and adb_shell tools remain available; adb_shell returns bounded text and is unsuitable for screenshots.'
].join('\n\n');
const previousRoutingIntro = 'Use wireless ADB for diagnostics, package inventory, file transfer, or when the user explicitly requests ADB. For ordinary requests to operate the phone, including launching an app, first call mobile_use({}) so Sugar can offer screen selection. If both screen modes are off and Sugar returns phase="adb", use the connected ADB device. Use the mobile-use Python API for UI actions and screenshots on that screen. A connected phone does not select the main screen.';
const commandGuide = [
  'Run adb devices or adb get-state to check the connection. Pairing, connection and disconnection are managed in Sugar. Keep pairing codes out of the conversation. Run adb shell COMMAND for Android shell diagnostics such as adb shell getprop ro.build.version.release. The connected phone and Linux project have separate filesystems.',
  'For an explicit ADB screenshot use adb exec-out screencap -p > screen.png. Use adb push LOCAL /absolute/device/file and adb pull /absolute/device/file [LOCAL] to transfer one file. Quote paths containing spaces and include the destination filename when pushing.',
  'Check stdout, stderr and the exit code. adb_status reports connection state and adb_shell runs bounded text commands. A manually connected phone authorizes ADB commands until disconnected.'
].join('\n\n');
const routingIntro = 'Use connected ADB directly for steps that need no UI, including package checks (adb shell pm path PACKAGE), diagnostics and file transfer. For UI steps first call mobile_use({}) to let Sugar select the screen. A background UI or a request to leave the main screen undisturbed needs the virtual display, including when ADB is requested. Keep screenshots and input on that display through mobile-use. For explicit ADB without this constraint, use the device commands below. When both screen modes are off, phase="adb" permits default-display ADB for an unspecified target.';
const previousDeviceGuide = [routingIntro, commandGuide].join('\n\n');
const observationGuide = 'For the default display’s UI hierarchy, run adb ui-dump > ui.xml in project Bash. Sugar streams the XML to stdout. Keep temporary screenshots and UI trees for your own inspection in the current project workspace. Redirecting PNG or XML output in project Bash writes it there directly. Reserve Android shared storage (such as /sdcard/Download) for files the user asks to transfer or export there.';
const keyRouting = 'Use connected ADB directly for diagnostics, package checks (adb shell pm path PACKAGE), file transfers and explicit ADB requests. For ordinary UI steps first call mobile_use({}) and use the selected screen’s supported phone actions. Background UI steps use the virtual screen even when ADB is requested. With an active screen, use connected adb_shell for a key the driver lacks (Enter, Tab, D-pad or Delete). On main use input keyevent KEYCODE_ENTER; on virtual use input -d ID keyevent KEYCODE_ENTER with the current task’s displayId.';
const uncertainOutcome = 'Re-observe after a timeout, stale observation or uncertain result before deciding whether to act again. With both screen modes off, phase="adb" permits default-display ADB for an unspecified target.';
const previousCapture = 'Only on Android 10 main with capabilities.image=false, use adb exec-out screencap -p > screen.png for the missing capture.';
const previousCapabilitiesGuide = [[keyRouting, previousCapture, uncertainOutcome].join(' '), commandGuide, observationGuide].join('\n\n');
const mainCapture = 'If the main driver explicitly reports capabilities.image=false and Sugar has connected ADB, use adb exec-out screencap -p > screen.png in project Bash. On Android 10 use the main screen UI tree; Sugar wireless ADB requires Android 11 or later. A denied capture, secure window or timeout does not authorize switching capture drivers.';
const virtualTextGuide = 'If the active virtual screen explicitly reports capabilities.text=false, focus the target field and use adb_shell to enter short ASCII text, e.g. input -d ID text hello%sworld. Use the current task’s displayId and a single argument (up to 512 characters: ASCII letters, digits, ._@+/-, and %s for a space).';
const previousVirtualTextGuide = [[keyRouting, mainCapture, uncertainOutcome].join(' '), commandGuide, observationGuide, virtualTextGuide].join('\n\n');
const guide = [
  'Prefer mobile_use({}) for phone interaction. Use connected ADB for device diagnostics, an explicit ADB request, or an action the selected driver lacks. For virtual input use input -d ID ... with this task’s current displayId; use mobile-use for virtual screenshots and launches. Observe an uncertain result before acting again.',
  commandGuide, observationGuide
].join('\n\n');
const previousRoutingGuide = [previousRoutingIntro, commandGuide].join('\n\n');
const guideReceived = text => [guide, previousVirtualTextGuide, previousCapabilitiesGuide, previousDeviceGuide, previousRoutingGuide, previousCliGuide, previousGuide].some(version => text.includes(version));

// Read the committed DSH session log, including sessions restored after restart.
// A failed load, a similarly named workspace skill, or text printed by another
// tool does not count as delivery of this plugin's instructions.
function hasReadGuide(session, sessions) {
  let seen = sessions.get(session);
  if (!seen || seen.next > session.seq) {
    seen = {next:0, loaded:false, calls:new Set()};
    sessions.set(session, seen);
  }
  while (!seen.loaded && seen.next < session.seq) {
    const event = session.eventAt(seen.next++);
    if (event.type === 'tool/call' && event.data.name === 'skill') seen.calls.add(event.data.callId);
    if (event.type === 'tool/result') {
      const msg = event.data.message;
      if (!seen.calls.delete(msg.toolCallId) || msg.isError) continue;
      seen.loaded = msg.content.some(block => block.type === 'text' &&
        block.text.startsWith('<skill_content name="android-adb">') && guideReceived(block.text));
    } else if (event.type === 'user/message' && event.data.source?.kind === 'skill-invocation' &&
        event.data.source.name === skillName && event.data.source.form === 'instructions') {
      seen.loaded = event.data.content.some(block => block.type === 'text' && guideReceived(block.text));
    }
  }
  return seen.loaded;
}

const required = type => ({type, required:true});
function toolOutput(properties) {
  return {schema:{type:'object', additionalProperties:false, properties},
    render: (_args, result) => [{type:'text', text:JSON.stringify(result)}]};
}

export function apply(ctx) {
  const bridge = globalThis[Symbol.for('dsh.sugar.projects')];
  const sessions = new WeakMap();
  ctx.effect(() => bridge.registerAdbGuide(session => hasReadGuide(session,sessions)), 'Sugar: ADB guide reader');
  ctx.skills.register({name:skillName, provider:'dsh-sugar-adb', source:'bundled',
    description:'Device diagnostics, transfers, explicit ADB requests and missing phone-driver capabilities. For ordinary UI use mobile_use({}) on the selected screen.',
    content:guide});
  ctx.tools.register(defineTool({
    name:'adb_status',
    description:'Check wireless ADB availability and the connection to the phone running DSH Sugar.',
    parameters:{},
    output:toolOutput({supported:required('boolean'), paired:required('boolean'),
      connected:required('boolean'), enabled:required('boolean'), busy:required('boolean'),
      phase:required('string'), message:required('string')}),
    isConcurrencySafe: () => true,
    async execute(_args, exec) {
      return bridge.adbStatus(exec.agent, exec.signal);
    }
  }));
  ctx.tools.register(defineTool({
    name:'adb_shell',
    description:'Run a text Android shell command for diagnostics, explicit ADB requests or supported fallbacks on the selected mobile_use screen. Load skill({name:"android-adb"}) before running ADB commands.',
    parameters:{command:{type:'string', required:true,
      description:'The exact one-line Android shell command to run on the phone, without an adb shell prefix.'}},
    output:toolOutput({stdout:required('string'), stderr:required('string'),
      exitCode:required('integer'), truncated:required('boolean')}),
    async execute({command}, exec) {
      const {agent, signal} = exec;
      signal?.throwIfAborted();
      if (!agent || !hasReadGuide(agent.session, sessions))
        throw Error('先在本对话调用 skill({name:"android-adb"}) 阅读 ADB 命令说明，然后重试。');
      if (typeof command !== 'string' || !command.trim() || Buffer.byteLength(command) > 8192 ||
          /[\u0000-\u001f\u007f\u202a-\u202e\u2066-\u2069]/u.test(command))
        throw Error('ADB 命令须为单行可显示文本，最长 8192 字节。');
      // The host checks the live connection and reserves the command in one operation.
      return bridge.adbShell(agent, command, signal);
    }
  }));
}
