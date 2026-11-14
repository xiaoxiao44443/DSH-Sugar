const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(process.argv[2] ||
  path.resolve(__dirname, '../app/src/main/assets/sugar-page-motion.js'), 'utf8');

class EventTargetMock {
  constructor() { this.listeners = new Map(); }
  addEventListener(key, cb) { (this.listeners.get(key) || this.listeners.set(key, new Set()).get(key)).add(cb); }
  removeEventListener(key, cb) { this.listeners.get(key)?.delete(cb); }
  fire(key) { for (const cb of this.listeners.get(key) || []) cb(); }
}

const window = new EventTargetMock(), document = new EventTargetMock(), media = new EventTargetMock();
const frames = new Map(), traces = [], lights = new Set();
let serial = 0, clock = 500;
window.top = window;
window.CSS = {supports: () => true};
document.getElementById = () => null;
document.body = {appendChild: node => lights.add(node), removeAttribute() {}};
function animate(keyframes, options) {
  const animation = {keyframes, options, cancelled: false, pause() {}, cancel() { this.cancelled = true; }};
  traces.push(animation);
  return animation;
}
document.createElement = () => {
  const node = {setAttribute() {}, style: {}, animate, remove: () => lights.delete(node)};
  return node;
};
const target = {isConnected: true, animate, contains: () => false, matches: () => false, closest: () => null,
  getRootNode: () => document, getBoundingClientRect: () => ({left: 0, top: 0, width: 380, height: 720})};
document.querySelector = () => target;
let seed = 121;
const random = Object.create(Math);
random.random = () => (seed = seed * 1664525 + 1013904223 >>> 0) / 4294967296;
const runtime = {window, document, console, devicePixelRatio: 3, Math: random,
  performance: {now: () => clock}, matchMedia: () => media, getComputedStyle: () => ({transform: 'none'}),
  requestAnimationFrame: cb => { frames.set(++serial, cb); return serial; }, cancelAnimationFrame: id => frames.delete(id)};
vm.runInNewContext(source,runtime);
const api = window.SugarPageMotion;
function drainFrame() { for (const [id, cb] of [...frames]) { frames.delete(id); cb(clock); } }
function play(direction, extras) {
  const index = traces.length;
  assert.equal(api.play({direction, ...extras}), true);
  drainFrame();
  return traces[index];
}
function preset(animation) {
  const first = animation.keyframes[0];
  if (first.clipPath?.startsWith('circle')) return 'iris';
  if (first.clipPath?.startsWith('polygon')) return 'shutter';
  if (first.transform?.includes('perspective')) return 'push';
  if (first.transform?.includes('scale')) return 'focus';
  return 'custom';
}
const sequence = [];
for (let i = 0; i < 20; i++) {
  const animation = play('forward', {origin: {x: 30, y: 90}});
  const kind = preset(animation);
  sequence.push(kind);
  if (kind === 'iris') assert.match(animation.keyframes[0].clipPath, /at 30px 90px/);
  if (kind === 'focus') {
    assert.equal(animation.keyframes[0].filter, undefined, 'no full-screen blur');
    assert.equal(lights.size, 1);
  }
  animation.onfinish();
  assert.equal(lights.size, 0);
}
for (let i = 0; i < sequence.length; i += 4) assert.equal(new Set(sequence.slice(i, i + 4)).size, 4);
for (let i = 1; i < sequence.length; i++) assert.notEqual(sequence[i], sequence[i - 1]);
for (const direction of ['back', 'forward', 'up', 'down']) {
  const seen = new Map();
  for (let i = 0; i < 8 && seen.size < 4; i++) {
    const animation = play(direction);
    seen.set(preset(animation), animation.keyframes[0]);
  }
  const push = seen.get('push').transform.match(/translate3d\(([-.\d]+)px,([-.\d]+)px,0\)/);
  const axes = {back: [-1, 0], forward: [1, 0], up: [0, 1], down: [0, -1]};
  assert.deepEqual([Math.sign(+push[1]), Math.sign(+push[2])], axes[direction]);
  const iris = seen.get('iris').clipPath.match(/ at (\d+)px (\d+)px/);
  assert.deepEqual([Math.sign(+iris[1] - 190), Math.sign(+iris[2] - 360)], axes[direction]);
}
const previous = play('back'); play('forward'); assert.equal(previous.cancelled, true);
const last = play('up'); api.setReduced(true); assert.equal(last.cancelled, true);
assert.equal(api.play(), false); api.setReduced(false);
media.matches = true; media.fire('change'); assert.equal(api.play(), false); media.matches = false;
document.fire('compositionstart'); assert.equal(api.play(), false); document.fire('compositionend'); clock += 250;
document.fire('input'); assert.equal(api.play(), false); clock += 250;
const interrupted = play('down'); document.fire('pointerdown'); assert.equal(interrupted.cancelled, true);
assert.equal(api.play({direction: 'constructor'}), false);
const unregister = api.register('page-turn', () => ({frames: [{opacity: .01}, {opacity: 1}], duration: 999}));
assert.throws(() => api.register('page-turn', () => ({})));
let found = false;
for (let i = 0; i < 5; i++) {
  const a = play('back');
  if (preset(a) === 'custom') { found = true; assert.equal(a.options.duration, 450); }
}
assert.ok(found); unregister();
function checkTapEffects() {
  const band={left:318,right:380,top:0,bottom:720,width:62};
  const rail={matches:()=>true,shadowRoot:{querySelector:()=>({getBoundingClientRect:()=>band})}};
  const container={}, pane=()=>({...target,style:{},parentElement:container,scrollTop:0,scrollLeft:0,clientLeft:0,clientTop:0,
    appendChild(node){lights.add(node);},animate(keyframes,options){const flight=animate(keyframes,options);flight.owner=this;return flight;}});
  const from=pane(),to=pane(), names=['diagonal','focus','iris','slide','split'];
  const pair=(effect,onFinish,direction='forward')=>{
    const index=traces.length;
    const stop=api.playBetween(from,to,{effect,direction,onFinish});
    assert.equal(typeof stop,'function');
    const [main,rim]=traces.slice(index);
    assert.equal(main.owner,to);
    return {stop,main,rim,name:main.options.id.replace('sugar-pane-','')};
  };
  document.getElementById=()=>rail;
  const iris=pair('iris');
  assert.equal(iris.main.keyframes[0].clipPath,'circle(0px at 159px 302px)');
  assert(iris.main.keyframes.every(frame=>frame.opacity===undefined && !frame.transform),'the real incoming page stays opaque and stationary inside the aperture');
  assert.equal(iris.rim.options.id,'sugar-iris-rim');assert.equal(iris.rim.startTime,iris.main.startTime);
  assert.equal(iris.rim.options.duration,iris.main.options.duration);assert.equal(iris.rim.options.easing,iris.main.options.easing);
  const radius=frame=>Number(frame.clipPath.match(/^circle\(([.\d]+)px/)[1]);
  assert(radius(iris.main.keyframes.at(-1))>=Math.hypot(380-159,720-302));
  assert(radius(iris.rim.keyframes.at(-1))>radius(iris.main.keyframes.at(-1)));
  assert.equal(lights.size,1);iris.stop();assert.equal(lights.size,0);
  band.left=0;band.right=62;
  const left=pair('iris');assert.match(left.main.keyframes[0].clipPath,/at 221px 302px/);left.stop();
  document.getElementById=()=>null;
  const noRail=pair('iris');assert.match(noRail.main.keyframes[0].clipPath,/at 190px 302px/);noRail.stop();
  const seq=[];
  for(let i=0;i<15;i++) {
    if(i%2===0)pair('iris').stop(); // Explicit auditions leave the random deck untouched.
    let main,stop;
    if(i%3===1) {
      const next=traces.length;
      assert(api.play({navigationTap:true,direction:'back',target:from}));
      main=traces[next];stop=main.onfinish;
    } else ({main,stop}=pair('random',undefined,'back'));
    const name=main.options.id.replace('sugar-pane-','');seq.push(name);
    const first=main.keyframes[0];
    if(name==='split')assert.match(first.clipPath,/inset\(0% 50% 0% 50%\)/);
    if(name==='diagonal')assert.match(first.clipPath,/^polygon\(/);
    if(name==='focus'){assert.match(first.transform,/scale/);assert.deepEqual([first.opacity,main.keyframes.at(-1).opacity],[0,1]);}
    if(name==='slide')assert.match(first.transform,/translate3d\(-100%,0%,0\)/);
    stop();
  }
  for(let i=0;i<seq.length;i+=5)assert.deepEqual(seq.slice(i,i+5).sort(),names);
  assert(seq.every((name,i)=>!i || seq[i-1]!==name),'mixed single/pair clicks never repeat at adjacent cycle boundaries');

  let calls=0;
  const old=pair('iris',()=>calls++),late=old.main.onfinish;
  const next=pair('slide',()=>calls++);
  assert.equal(calls,1);assert.equal(lights.size,0);late();old.stop();assert.equal(calls,1,'old completions never cancel a newer transition');
  next.main.onfinish();next.stop();assert.equal(calls,2);
  const exitIndex=traces.length;
  assert.equal(api.play({navigationTap:true,exiting:true,target:from,effect:'iris',onFinish:()=>calls++}),true);
  assert.equal(traces[exitIndex].options.direction,'reverse');
  assert.equal(traces[exitIndex].options.id,'sugar-pane-iris');
  document.fire('pointerdown');assert.equal(calls,3);
  const taps=[];
  for(let i=0;i<5;i++) {
    const n=traces.length;api.play({navigationTap:true,target:to});taps.push(traces[n].options.id.slice(11));
    traces[n].onfinish();
  }
  assert.deepEqual(taps.sort(),names);
  api.setReduced(true);
  assert.equal(api.playBetween(from,to,{onFinish:()=>calls++}),null);
  assert.equal(api.play({navigationTap:true,target:from,onFinish:()=>calls++}),false);
  assert.equal(calls,3,'declined transitions leave cleanup to the caller');api.setReduced(false);
  const reducedWindow=new EventTargetMock(),reducedDocument=new EventTargetMock(),settings=new EventTargetMock();
  reducedWindow.top=reducedWindow;reducedWindow.CSS={supports:()=>false};
  Object.assign(reducedDocument,{body:document.body,querySelector:document.querySelector,getElementById:()=>null});
  vm.runInNewContext(source,{...runtime,window:reducedWindow,document:reducedDocument,matchMedia:()=>settings});
  const n=traces.length, fallback=reducedWindow.SugarPageMotion.playBetween(from,to,{effect:'iris'});
  assert.equal(traces[n].options.id,'sugar-pane-slide','without clip-path a supported transition takes over');
  fallback();reducedWindow.SugarPageMotion.dispose();
}
checkTapEffects();
(async () => {
  let calls = 0, reverts = 0, arrival, page = target, bodyCaptures = 0;
  // A DSH conversation can contain an iframe file preview. Even an overly permissive
  // caller must never start a capture on the WebView document body.
  document.body.startViewTransition = () => { bodyCaptures++; throw Error('unsafe document capture'); };
  const next = {...target};
  const config = {direction: 'back', target: () => page, allowCapture: true, navigate: async () => {
    calls++; await new Promise(ok => { arrival = ok; }); page = next; return true;
  }, restore: () => {reverts++; page = target; return true;}};
  let gesture = api.beginDrag(config);
  gesture.update(.1);
  assert.equal(traces.at(-1).currentTime, 100, 'the outgoing page tracks the finger before navigation');
  assert.equal(calls, 0);
  gesture.update(.23);
  assert.equal(calls, 0, 'short edge reveals never stage a second page');
  gesture.update(.4); gesture.update(.53); assert.equal(calls, 1);
  assert.equal(api.play(), false, 'the same drag never starts a random click effect');
  const cancel = gesture.finish(false, true);
  assert.equal(gesture.finish(false), cancel);
  arrival();
  assert.equal(await cancel, false);
  assert.equal(page, target); assert.equal(reverts, 1);
  gesture = api.beginDrag({...config, canStage:false});
  gesture.update(.78); assert.equal(calls, 1, 'irreversible work waits for release');
  const commit = gesture.finish(true, true);
  await Promise.resolve();
  arrival(); assert.equal(await commit, true);
  assert.equal(calls, 2); assert.equal(page, next); assert.equal(reverts, 1);
  assert.equal(bodyCaptures, 0);
  api.setReduced(true);
  gesture = api.beginDrag(config); gesture.update(.8);
  assert.equal(calls, 2, 'reduced motion keeps browsing available without staged navigation');
  await gesture.finish(false, true);
  api.setReduced(false);
  page = target;
  let drawn = 0;
  gesture = api.beginDrag({...config, canStage:false, onProgress:p => {drawn=p;}});
  gesture.update(.55);
  for (const speed of [undefined, 0, .0004, .004, .04]) {
    const timing = api.releaseTiming(.45, speed);
    const values = Array.from({length: 121}, (_, i) => timing.fractionAt(timing.startTime + i * timing.duration / 100));
    assert.equal(values[0], 0);
    assert.equal(values.at(-1), 1);
    assert(values.every((v,i) => v >= 0 && v <= 1 && (!i || v >= values[i-1])), 'no overrun or bounce');
    assert(timing.fractionAt(timing.startTime + timing.duration * .999) > .99999, 'dock at rest');
  }
  assert.equal(api.releaseTiming(0).duration,0);
  const release = api.releaseTiming(.45, .003);
  const slowCommit = gesture.finish(true, false, release);
  await Promise.resolve();
  clock += 32; drainFrame();
  assert(drawn > .55 && drawn < 1, 'release advances immediately while a DSH route is still pending');
  assert(Math.abs(drawn - (.55 + .45 * release.fractionAt(clock))) < 1e-7, 'page uses the actual rail release clock');
  clock = Math.ceil(release.startTime + release.duration); drainFrame();
  assert.equal(drawn,1, 'page stops at the dock on the same frame as the rail');
  assert.equal(page,target,'route is still pending');
  arrival();
  assert.equal(await slowCommit,true);
  assert.equal(page,next);
  api.play(); api.dispose(); drainFrame();
  assert.equal(lights.size, 0); assert.equal(window.SugarPageMotion, undefined); assert.equal(api.play(), false);
  console.log('Page motion: presets, direction, drag scrub, rollback, commit-only navigation, cancellation and reduced motion passed.');
})().catch(error => {console.error(error);process.exitCode = 1;});
