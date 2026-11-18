// node scripts/check-edge-nav.cjs [--language|--shell-history|--notification-glass|--pane-deck|--splash-preview]. Set CHROME_BIN to select a Chromium executable.
const { spawn } = require('node:child_process');
const { existsSync, mkdtempSync, readFileSync } = require('node:fs');
const { rm } = require('node:fs/promises');
const { join, resolve, dirname } = require('node:path');
const { tmpdir } = require('node:os');
const { once } = require('node:events');
const assert = require('node:assert/strict');

const executable = [process.env.CHROME_BIN,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/chromium', '/usr/bin/chromium-browser', '/usr/bin/google-chrome'
].find(name => name && existsSync(name));
assert(executable, 'Set CHROME_BIN to an installed Chromium executable');
const assets = resolve(__dirname, '../app/src/main/assets');
const noticeMode = process.argv[2] === '--notification-glass';
const recoverySettingsMode = process.argv[2] === '--recovery-settings';
const historyMode = process.argv[2] === '--shell-history' || noticeMode || recoverySettingsMode;
const languageMode = process.argv[2] === '--language';
const recoveryMode = process.argv[2] === '--recovery';
const recoveryManagerMode = process.argv[2] === '--recovery-manager';
const deckMode = process.argv[2] === '--pane-deck';
const exitMode = process.argv[2] === '--splash-exit';
const previewMode = process.argv[2] === '--splash-preview';
const source = readFileSync(!recoveryMode && !recoveryManagerMode && !languageMode && !historyMode && !deckMode && !previewMode && !exitMode && process.argv[2] || join(assets, 'sugar-edge-nav.js'), 'utf8');
const profile = mkdtempSync(join(tmpdir(), 'sugar-edge-test-'));
const browser = spawn(executable, ['--headless', '--remote-debugging-pipe', '--no-first-run',
  '--disable-background-networking', '--disable-extensions', '--user-data-dir=' + profile],
{ windowsHide: true, stdio: ['ignore', 'ignore', 'ignore', 'pipe', 'pipe'] });
const closed = once(browser, 'exit');
let nextId = 0, buffer = '';
const waiting = new Map();
browser.stdio[4].setEncoding('utf8');
browser.stdio[4].on('data', chunk => {
  buffer += chunk;
  let separator;
  while ((separator = buffer.indexOf('\0')) !== -1) {
    const answer = JSON.parse(buffer.slice(0, separator));
    buffer = buffer.slice(separator + 1);
    const reply = waiting.get(answer.id);
    if (!reply) continue;
    waiting.delete(answer.id);
    if (answer.error) reply.reject(new Error(answer.error.message)); else reply.resolve(answer.result);
  }
});
browser.on('exit', () => {
  for (const reply of waiting.values()) reply.reject(new Error('Chromium exited before replying'));
});
function call(method, params = {}, sessionId) {
  const id = ++nextId;
  return new Promise((resolve, reject) => {
    waiting.set(id, { resolve, reject });
    browser.stdio[3].write(JSON.stringify({id, method, params, sessionId}) + '\0');
  });
}
async function checkShellHistory({evaluate, touch, tap, swipeAt, paint, screen, send}) {
  const shell = process.argv[3] || join(assets, 'sugar-shell.js');
  const css = ['sugar-settings.css', 'sugar-workspace-files.css'].map(name => readFileSync(join(dirname(shell), name), 'utf8')).join('\n');
  await evaluate(`
    document.documentElement.style.overflow = 'hidden'; document.body.style.margin = '0';
    document.body.innerHTML = '<main class="pI_x6G_centerCol" style="width:100vw;height:100vh">A</main>';
    (() => {
      let route = 'A', busy = false;
      const output = document.querySelector('main');
      const inspector = document.createElement('aside');
      inspector.dataset.sidebarRightPanel = 'fullscreen';
      inspector.style.cssText = 'position:fixed;inset:0;background:white';
      inspector.hidden = true;
      document.body.append(inspector);
      const publish = () => window.dispatchEvent(new Event('sugar-navigation-state'));
      async function restore(key) {
        if (busy) return false;
        busy = true; publish();
        await new Promise(resolve => requestAnimationFrame(resolve));
        route = key; output.textContent = route;
        inspector.hidden = !key.startsWith('right/');
        if (inspector.hidden) inspector.removeAttribute('data-sidebar-right-open');
        else { inspector.dataset.sidebarRightOpen = 'true'; inspector.textContent = key; }
        busy = false; publish();
        return true;
      }
      window.openDshPage = restore;
      // Only this interface is mocked. Both real setting panes and their real host keep their own history.
      window.SugarNavigation = {
        bookmark() {
          const key = route;
          return busy ? null : {key, reachable: () => true, canStage: () => !busy, restore: () => restore(key)};
        },
        action(command) {
          if (busy || command !== 'home' || route === 'home') return false;
          restore('home'); return true;
        },
        state: () => ({busy,isHome:route === 'home',rightPanelOpen:!inspector.hidden,routeKey:route,canBack:false,canForward:false}),
        canStage: () => !busy, checkpoint: () => () => true
      };
    })();
    window.routeEvents = [];
    for (const type of ['sugar-edge-action','sugar-edge-scrub','sugar-edge-dock','sugar-navigation-state']) {
      window.addEventListener(type, e => routeEvents.push([type,e.detail]));
    }
    window.pageNow = () => {
      const h = document.getElementById('sugar-shell-settings');
      return h && !h.hidden ? h.shadowRoot.querySelector('[data-pane]:not([hidden])').dataset.pane :
        'dsh/' + document.querySelector('main').textContent;
    };
    window.awaitPage = (target, settled) => new Promise((resolve, reject) => {
      const until = performance.now() + 2800;
      function check() {
        const preview = settled && document.getAnimations().some(a => a.playState === 'paused');
        if (pageNow() === target && !SugarNavigation.state().busy && !preview) return resolve();
        if (performance.now() >= until) return reject(Error('Expected ' + target + ', saw ' + pageNow() +
          ', recent events ' + JSON.stringify(routeEvents.slice(-10))));
        requestAnimationFrame(check);
      }
      check();
    });`);
  await evaluate(source);
  await evaluate(readFileSync(join(assets, 'sugar-page-motion.js'), 'utf8'));
  await evaluate(readFileSync(join(assets, 'sugar-pane-deck.js'), 'utf8'));
  await evaluate(readFileSync(join(assets, 'sugar-splash-preview.js'), 'utf8'));
  await evaluate(readFileSync(join(assets, 'sugar-splash-settings.js'), 'utf8'));
  await evaluate(readFileSync(join(assets, 'sugar-mobile-controls.js'), 'utf8'));
  await evaluate(readFileSync(join(assets, 'sugar-pets.js'), 'utf8'));
  await evaluate(readFileSync(join(assets, 'sugar-storage.js'), 'utf8'));
  await evaluate(readFileSync(join(assets, 'sugar-workspace-files.js'), 'utf8'));
  await evaluate(readFileSync(join(assets, 'sugar-session-storage.js'), 'utf8'));
  await evaluate(readFileSync(join(assets, 'sugar-recovery-page.js'), 'utf8'));
  await evaluate(readFileSync(shell, 'utf8'));
  const splash = {portraitPercent:65,landscapePercent:55,portraitFit:'contain',landscapeFit:'contain',
    loadingText:'正在加载 DSH Sugar…',showProgress:true,durationMs:0,randomExit:false,imageName:null,backgroundColor:'#FFFFFF'};
  async function mount(saved = {}) {
    const prefs = {side:'right',colorMode:'adaptive',accent:'#88beb7',transparency:16,blur:true,reducedMotion:true,splash,...saved};
    await evaluate(`window.testSplash=${JSON.stringify(splash)};SugarShell.mount(${JSON.stringify(prefs)},${JSON.stringify(css)});
      window.railRoot = document.getElementById('sugar-edge-navigation').shadowRoot;
      window.paneRoot = () => document.getElementById('sugar-shell-settings').shadowRoot;`);
  }
  await mount();

  async function waitPage(expected, settled = false) {
    await evaluate('awaitPage(' + JSON.stringify(expected) + ',' + settled + ')');
    await paint();
  }
  async function point(expression) {
    const position = await evaluate(`(() => {
      const n = ${expression}, r = n.getBoundingClientRect();
      if (!r.width || !r.height || n.disabled || n.hidden) throw Error('Action is unavailable: '+n.outerHTML);
      return [r.x+r.width/2,r.y+r.height/2];
    })()`);
    await tap(...position);
  }
  async function click(name, expected) {
    await evaluate('Promise.all(railRoot.getAnimations().map(a=>a.finished.catch(()=>{})))');
    if (!await evaluate('SugarEdgeNav.isExpanded()')) await point("railRoot.querySelector('.tab')");
    await evaluate('Promise.all(railRoot.getAnimations().map(a=>a.finished.catch(()=>{})))');
    await point("railRoot.querySelector('[data-action=" + name + "]')");
    await waitPage(expected);
  }
  async function fromEdge(side, destination) {
    await swipeAt(side==='right'?382:8,111,side==='right'?-259:259);
    await waitPage(destination,true);
  }
  if (recoverySettingsMode) {
    await click('settings','overview');
    const pageSurface = () => evaluate("getComputedStyle(paneRoot().host).backgroundColor");
    const railActions = () => evaluate("[...railRoot.querySelectorAll('button[data-action]:not([hidden])')].map(b=>b.dataset.action)");
    const color = await pageSurface(), actions = await railActions();
    await evaluate("window.originalRailRoot = railRoot; window.originalSettingsHost = paneRoot().host");
    const entry = "paneRoot().querySelector('[data-go=recovery]')";
    await evaluate(entry+".scrollIntoView({block:'center'})");
    await point(entry);
    await waitPage('recovery');
    assert(await evaluate("originalRailRoot === document.getElementById('sugar-edge-navigation').shadowRoot && originalSettingsHost === paneRoot().host"));
    assert.equal(await evaluate('SugarEdgeNav.isExpanded()'), true, 'entering diagnostics preserves the open rail');
    assert.equal(await pageSurface(), color);
    assert.deepEqual(await railActions(), actions, 'diagnostics inherits the overview controls');
    assert.equal(await evaluate("paneRoot().querySelectorAll('[data-pane=recovery] [data-recovery-action]').length"), 4);
    const side = () => evaluate("railRoot.host.dataset.side");
    const start = await side();
    await fromEdge(start, 'overview');
    const opposite = await side();
    assert.notEqual(start, opposite);
    await fromEdge(opposite, 'recovery');
    assert.equal(await side(), start);
    await click('back','overview');
    await click('forward','recovery');
    return;
  }
  if (noticeMode) {
    await evaluate("const link=new MessageChannel();window.postMessage('dsh-sugar/edge-shell','*',[link.port2]);link.port1.start()");
    await paint();
    await click('settings','overview');
    await evaluate("paneRoot().querySelector('[data-go=notifications]').click()");
    await paint();
    await evaluate("paneRoot().querySelector('#notice-completion').click()");
    return checkNotificationGlass({evaluate,send,paint});
  }
  const checked = [];
  await waitPage('dsh/A');
  await fromEdge('right','dsh/A'); await fromEdge('left','dsh/A');
  assert.equal(await evaluate("routeEvents.filter(([e])=>e==='sugar-edge-action'||e==='sugar-edge-scrub').length"),0,'a single page only reveals or moves the rail');
  await click('settings', 'overview');
  // The live DSH page and Sugar settings are siblings. A held finger moves the
  // seam while both layouts keep their final coordinates, even across halfway.
  await evaluate(`window.shellPulse=(direction,side,phase,progress)=>dispatchEvent(new CustomEvent('sugar-edge-scrub',
    {detail:{direction,side,phase,progress,release:{duration:0}}}));
    window.shellPose=()=>{const h=document.getElementById('sugar-shell-settings'), st=getComputedStyle(h);
      return [st.clipPath,st.transform,h.hidden,h.inert,
        h.shadowRoot.querySelector('[data-pane=overview] h1').getBoundingClientRect().x,
        document.querySelector('main').getBoundingClientRect().x]};`);
  for (const [direction,side,from,to] of [['back','right','overview','dsh/A'],['forward','left','dsh/A','overview']]) {
    let firstHeading;
    for (const p of [.03,.28,.54,.75,.12]) {
      await evaluate(`shellPulse('${direction}','${side}','move',${p})`);
      await paint();
      const [clip,transform,hidden,inert,headingX,dshX]=await evaluate('shellPose()');
      const percent=direction==='back'?p*100:(1-p)*100;
      assert.equal(clip,`inset(0% ${Math.round(percent)}% 0% 0%)`,'seam follows each move and reverses continuously');
      assert.equal(transform,'none'); assert.equal(dshX,0);
      firstHeading??=headingX;
      assert.equal(headingX,firstHeading,'text keeps its horizontal position when crossing halfway');
      assert.equal(hidden,direction==='forward'); assert.equal(inert,direction==='forward');
      assert.equal(await evaluate('pageNow()'),from,'logical page and focus stay at the source during a hold');
    }
    await evaluate(`shellPulse('${direction}','${side}','cancel',.12)`); await waitPage(from,true);
    assert.equal((await evaluate('shellPose()'))[0],'none');
    await evaluate(`shellPulse('${direction}','${side}','move',.7);shellPulse('${direction}','${side}','commit',.7)`);
    await waitPage(to,true);
  }
  await point("paneRoot().querySelector('[data-go=appearance]')");
  await waitPage('appearance');
  await evaluate(`
    const slider = paneRoot().querySelector('input[name=transparency]');
    for (const value of [31,64,83]) {
      slider.value = value; slider.dispatchEvent(new Event('input',{bubbles:true}));
      slider.dispatchEvent(new Event('change',{bubbles:true}));
    }`);
  await paint();
  assert.equal(await evaluate("paneRoot().querySelector('output[for=sugar-transparency]').textContent"), '83%');
  await click('gestures','gestures');
  const edgeSwitch = "paneRoot().querySelector('[name=fullEdgeGestures]')";
  assert.equal(await evaluate(edgeSwitch + ".getAttribute('aria-checked')"),'false','the system bar is visible by default');
  await evaluate(`new Promise(ready => {
    const channel = new MessageChannel(); window.testPort = channel.port1; window.savedEdges = [];
    testPort.onmessage = ({data}) => {
      const msg = JSON.parse(data);
      if (msg.type === 'state') ready();
      if (msg.type === 'settings') savedEdges.push(msg.fullEdgeGestures);
    };
    window.postMessage('dsh-sugar/edge-shell','*',[channel.port2]);
  })`);
  for (const checked of [true,false]) {
    await point(edgeSwitch);
    await paint();
    assert.equal(await evaluate(edgeSwitch + ".getAttribute('aria-checked')"),String(checked));
  }
  assert.deepEqual(await evaluate('savedEdges'),[true,false],'each change is sent once through the native settings port');
  const savedEdge = await evaluate('savedEdges[0]');
  await click('home','dsh/home');
  for (const page of ['gestures','appearance','overview','dsh/A']) {
    await click('back',page); checked.push(page);
  }
  for (const page of ['overview','appearance','gestures','dsh/home']) {
    await click('forward',page); checked.push(page);
  }
  // Start a new visit from the middle of the previous chain and verify that the old future was pruned.
  await click('back','gestures');
  await click('back','appearance');
  await click('overview','overview');
  assert.equal(await evaluate("railRoot.querySelector('[data-action=forward]').disabled"), true);
  await fromEdge('right','appearance');
  await fromEdge('left','overview');
  await fromEdge('left','appearance'); // Same starting edge still toggles the pair.
  assert.equal(await evaluate("paneRoot().querySelector('output[for=sugar-transparency]').textContent"), '83%');

  // Check a staged gesture with the real page-transition engine, including its rollback.
  await click('gestures','gestures');
  await point("paneRoot().querySelector('button[name=reducedMotion]')");
  assert.equal(await evaluate("paneRoot().querySelector('button[name=reducedMotion]').getAttribute('aria-checked')"), 'false');
  await touch('touchStart',8,107,Date.now()/1000-.24); await touch('touchMove',149,107);
  await paint();
  if (await evaluate("Boolean(paneRoot().querySelector('[data-sugar-pane-deck]'))")) {
    assert.equal(await evaluate('pageNow()'), 'gestures', 'real incoming page can be seen without changing history');
    assert.equal(await evaluate("paneRoot().querySelectorAll('[data-deck-layer]').length"), 2);
  } else await waitPage('appearance');
  await touch('touchCancel'); await waitPage('gestures');
  await click('back','appearance');
  assert.deepEqual(checked, ['gestures','appearance','overview','dsh/A','overview','appearance','gestures','dsh/home']);

  // A swipe starting on the actual right-hand grip must go back inside the inspector.
  await click('home','dsh/home');
  for (const route of ['A','right/files','right/index.html']) {
    await evaluate('openDshPage(' + JSON.stringify(route) + ')');
    await waitPage('dsh/' + route);
  }
  async function dragInspectorBack(expected, cancel = false) {
    const [x,y] = await evaluate(`(async () => {
      SugarEdgeNav.revealFromSystemEdge('right');
      await Promise.all(railRoot.getAnimations().map(a=>a.finished.catch(()=>{})));
      const r=railRoot.querySelector('.tab').getBoundingClientRect();
      return [r.x+r.width/2,r.y+r.height/2];
    })()`);
    await touch('touchStart',x,y); await touch('touchMove',x-150,y);
    await waitPage(expected);
    const last = await evaluate("routeEvents.filter(([name])=>name==='sugar-edge-scrub').at(-1)[1]");
    assert.equal(last.phase,'move'); assert.equal(last.direction,'back'); assert.equal(last.side,'right');
    const incomingX = await evaluate(`(() => {
      const target = document.querySelector('[data-sidebar-right-open]') || document.querySelector('main');
      return new DOMMatrixReadOnly(getComputedStyle(target).transform).m41;
    })()`);
    assert(incomingX > 0,'the previous page slides in from the right while the finger moves left');
    if (cancel) return touch('touchCancel');
    await touch('touchMove',90,y); await touch('touchEnd');
    await waitPage(expected,true);
  }
  await dragInspectorBack('dsh/right/files', true);
  await waitPage('dsh/right/index.html', true);
  await dragInspectorBack('dsh/right/files');
  assert.equal(await evaluate("railRoot.querySelector('[data-action=forward]').disabled"),false);
  await fromEdge('left','dsh/right/index.html'); // Reverse from the landed edge even though an older page is available.
  await fromEdge('left','dsh/right/files');
  await fromEdge('right','dsh/right/index.html');
  await click('back','dsh/right/files');await click('back','dsh/A'); // Buttons can traverse past the pair.
  await evaluate("openDshPage('right/index.html')");await waitPage('dsh/right/index.html');
  await dragInspectorBack('dsh/A');await fromEdge('left','dsh/right/index.html'); // Reopen the exact file from its conversation.
  // Make an old panel's commit callback arrive after the user explicitly opens another page.
  await evaluate(`(() => {
    SugarNavigation.beginPanelDrag=()=>({update(){},finish(commit,apply){
      return new Promise(resolve=>{window.finishOldPanel=async()=>{const ok=await apply();resolve(ok);return ok;};});
    }});
    for(const phase of ['move','commit']) dispatchEvent(new CustomEvent('sugar-edge-scrub',{
      detail:{phase,direction:'back',side:'left',progress:.6}
    }));
  })()`);
  await evaluate("openDshPage('other')");await waitPage('dsh/other');
  assert.equal(await evaluate('finishOldPanel()'),false,'late panel completion cannot replace an explicitly opened page');
  await evaluate('delete SugarNavigation.beginPanelDrag;delete window.finishOldPanel');
  await fromEdge('right','dsh/right/index.html');await fromEdge('left','dsh/other');
  await click('back','dsh/right/index.html');
  await evaluate('testPort.close();SugarShell.dispose()');
  await evaluate(readFileSync(shell,'utf8'));
  await mount({fullEdgeGestures:savedEdge,notifications:{completion:'off',permission:false,question:true}});
  await click('settings','overview'); await click('gestures','gestures');
  assert.equal(await evaluate(edgeSwitch + ".getAttribute('aria-checked')"),'true','restored native preference');
  await checkColorPage({evaluate,click,point,waitPage,paint,screen,fromEdge});
  // Exercise the caller as well as the deck. Only a rail tap requests a random paired transition.
  await click('settings','overview'); await click('gestures','gestures');
  const reducedSwitch = "paneRoot().querySelector('button[name=reducedMotion]')";
  if(await evaluate(reducedSwitch + ".getAttribute('aria-checked')") === 'true') await point(reducedSwitch);
  await click('appearance','appearance');
  await evaluate("Promise.all(paneRoot().getAnimations().filter(a=>a.id.startsWith('sugar-pane-')).map(a=>a.finished.catch(()=>{})))");
  await paint();
  await evaluate(`window.pairedFrames=[];
    const inspectPane=()=>{
      const pane=paneRoot().querySelector('[data-slide-front]');
      if(!pane)return;
      const m=new DOMMatrix(getComputedStyle(pane).transform);
      pairedFrames.push({page:pane.dataset.pane,ids:paneRoot().getAnimations().map(a=>a.id),x:m.m41,y:m.m42});
    };
    window.pairObserver=new MutationObserver(()=>{inspectPane();requestAnimationFrame(inspectPane);});
    pairObserver.observe(paneRoot(),{subtree:true,attributes:true,attributeFilter:['data-slide-front']});`);
  try {
    const entry="paneRoot().querySelector('[data-go=palette]')";
    await evaluate(entry+".scrollIntoView({block:'nearest'})");
    await point(entry); await paint();
    const entryRoute=await evaluate("pageNow()");
    assert(entryRoute.startsWith('tone-'));
    assert.equal(await evaluate("pairedFrames.some(f=>f.page===pageNow()&&f.ids.some(id=>id.startsWith('sugar-pane-')))"),false,
      'the in-page palette link does not start a random rail transition');
    const chosen=entryRoute==='tone-sky'?'tone-charcoal':'tone-sky';
    await click(chosen,chosen);
    assert(await evaluate("pairedFrames.some(f=>f.page===pageNow()&&f.ids.some(id=>/^sugar-pane-(iris|diagonal|split|focus|slide)$/.test(id)))"),
      'a real color button in the rail starts a named paired page transition');
    await evaluate("Promise.all(paneRoot().getAnimations().filter(a=>a.id.startsWith('sugar-pane-')).map(a=>a.finished.catch(()=>{})))");
    const verticalDestination=chosen==='tone-sky'?'tone-ocean':'tone-sky';
    await swipeAt(185,545,0,-170); await waitPage(verticalDestination);
    assert(await evaluate("pairedFrames.some(f=>f.page===pageNow()&&Math.abs(f.x)<1&&Math.abs(f.y)>1)&&!pairedFrames.some(f=>f.page===pageNow()&&f.ids.some(id=>id.startsWith('sugar-pane-')))"),
      'an upward swipe inside a color page still moves the next page vertically');
  } finally {
    await evaluate('pairObserver.disconnect();delete window.pairObserver;delete window.pairedFrames');
  }

  await checkHomeBackdrop({evaluate,click,point,waitPage,paint});
  await checkNotificationSettings({evaluate,click,point,tap,paint,send});
  await checkAdbSettings({evaluate,click});
  await checkSplashSettings({evaluate,click,paint});
  await evaluate('SugarShell.dispose(); SugarPaneDeck.dispose(); SugarPageMotion.dispose(); SugarEdgeNav.dispose()');
}
async function checkNotificationGlass({evaluate,send,paint}) {
  const before=await evaluate("SugarEdgeNav.getPopupPalette()");
  const proofStyle="paneRoot().querySelector('#notice-glyph-proof')";
  async function capture(clip,rules='') {
    await evaluate(proofStyle+'.textContent='+JSON.stringify(rules));
    return (await send('Page.captureScreenshot',{format:'png',clip})).data;
  }
  async function readRows(clip,rows,mask) {
    const real=await capture(clip);
    // Retain the production glyph halo and remove only the ink. Underlying RGB at
    // each opaque letter is then measurable, even when the rest of the row is clear.
    const beneath=await capture(clip,'#notice-options button {color:transparent!important;}');
    await evaluate(proofStyle+".textContent=''");
    return evaluate(`(async()=>{
      const image=async url=>{
        const i=new Image();i.src='data:image/png;base64,'+url;await i.decode();
        const c=document.createElement('canvas');c.width=i.width;c.height=i.height;
        const ctx=c.getContext('2d');ctx.drawImage(i,0,0);
        return ctx.getImageData(0,0,c.width,c.height);
      };
      const [normal,clear,core]=await Promise.all(${JSON.stringify([real,beneath,mask])}.map(image));
      const pixel=(frame,x,y)=>[...frame.data.slice((y*frame.width+x)*4,(y*frame.width+x)*4+3)];
      const rows=${JSON.stringify(rows)}.map(r=>{
        const colors=new Map(),rim=new Map();let coreCount=0,minInkDistance=255;
        for(let y=r.text[1];y<r.text[3];y++) for(let x=r.text[0];x<r.text[2];x++) {
          if(pixel(core,x,y)[0]<235) continue;
          coreCount++;
          const back=pixel(clear,x,y),ink=pixel(normal,x,y);
          colors.set(back.join(','),back);
          for(const [dx,dy] of [[-1,0],[1,0],[0,-1],[0,1]]) {
            if(pixel(core,x+dx,y+dy)[0]!==0) continue;
            const edge=pixel(normal,x+dx,y+dy);
            rim.set(edge.join(','),edge);
          }
          minInkDistance=Math.min(minInkDistance,Math.max(...ink.map((v,i)=>Math.abs(v-r.ink[i]))));
        }
        return {base:pixel(normal,r.x,r.y),colors:[...colors.values()],rim:[...rim.values()],coreCount,minInkDistance};
      });
      return {rows,shadow:pixel(normal,Math.floor(normal.width/2),normal.height-8)};
    })()`);
  }
  await evaluate("const proof=document.createElement('style');proof.id='notice-glyph-proof';paneRoot().append(proof)");
  const masks=new Map();

  const hex=rgb=>'#'+rgb.map(c=>c.toString(16).padStart(2,'0')).join('');
  function luminance(rgb) {
    const [r,g,b]=rgb.map(c=>c/255).map(c=>c<=.04045 ? c/12.92 : ((c+.055)/1.055)**2.4);
    return r*.2126+g*.7152+b*.0722;
  }
  await evaluate("const field=document.createElement('div'); field.id='notice-test-field'; field.style.cssText='position:fixed;inset:0;pointer-events:none;z-index:10'; paneRoot().append(field)");
  try {
    const fields=[['#ffffff'],['#25272c'],['#dfd3f0'],['#8fcfc2'],
      ['#ffffff','#000000'],['#25272c','#ffffff'],['#727272','#ffffff'],['#828282','#000000']];
    for(const blur of [true,false]) for(const [bg,block] of fields) {
      await evaluate(`(() => {
        const field=paneRoot().querySelector('#notice-test-field'), rect=paneRoot().querySelector('#notice-options').getBoundingClientRect();
        field.style.background=${JSON.stringify(bg)};
        field.replaceChildren();
        const color=${JSON.stringify(block || null)};
        if(color) {
          // Oversize the underlying control so both rows see its face even after blur.
          const control=document.createElement('div');
          control.style.cssText='position:absolute;left:'+(rect.left-60)+'px;top:'+(rect.top-60)+'px;width:'+(rect.width+120)+'px;height:'+(rect.height+120)+'px';
          control.style.background=color;
          field.append(control);
        }
        SugarEdgeNav.configure(${JSON.stringify({backdrop:bg,blur})});
      })()`);
      await paint();
      const styles=await evaluate(`(() => {
        const d=paneRoot().querySelector('#notice-options'), s=getComputedStyle(d), menu=d.querySelector('[role=menu]'), r=d.getBoundingClientRect();
        const rows=[false,true].map(selected=>{
          const b=menu.querySelector('[aria-checked='+selected+']'), rect=b.getBoundingClientRect();
          const range=document.createRange();range.selectNodeContents(b);const t=range.getBoundingClientRect();
          return {text:[Math.floor(t.left-r.left),Math.floor(t.top-r.top),Math.ceil(t.right-r.left),Math.ceil(t.bottom-r.top)],
            x:Math.floor(rect.left+rect.width/2)-Math.floor(r.left), y:Math.floor(rect.top+5)-Math.floor(r.top),
            ink:getComputedStyle(b).color.match(/[0-9.]+/g).map(Number)};
        });
        return {rows,shadow:s.boxShadow,edge:s.borderTopColor,border:s.borderTopWidth,scale:devicePixelRatio,film:s.backgroundColor,filter:s.backdropFilter,
          clip:{x:Math.floor(r.left),y:Math.floor(r.top),width:Math.ceil(r.width),height:Math.ceil(r.height)+12,scale:1},
          plain:d.open && d.children.length===1 && menu===d.firstElementChild && menu.children.length===3 &&
            [...menu.children].every(b=>b.matches('button[role=menuitemradio]')) && !d.querySelector('img,svg,canvas,picture') &&
            s.backgroundImage==='none' && ['none','normal'].includes(getComputedStyle(d,'::before').content)};
      })()`);
      const alpha=color=>Number(color.match(/[0-9.]+/g)[3] ?? 1);
      assert(styles.plain,'three options without header artwork: '+JSON.stringify(styles));
      const rimRgb=styles.edge.match(/[0-9.]+/g).slice(0,3).map(Number);
      const rimPx=Math.round(parseFloat(styles.border) * styles.scale);
      if(bg==='#ffffff') {
        assert.equal(rimPx, Math.round(1.5*styles.scale)); assert.deepEqual(rimRgb,[255,255,255]);
      }
      if(bg==='#25272c') {
        assert.equal(rimPx, Math.max(1,Math.round(1.08*styles.scale)));
        assert(rimRgb[0]<164 && rimRgb[2]>rimRgb[0],'charcoal uses a soft, cool gray rim');
      }
      assert(alpha(styles.edge)>=.5 && alpha(styles.edge)<=.95,'translucent frame');
      assert(!styles.shadow.includes('inset'),'the menu has a single rim and only an outer shadow');
      assert.notEqual(styles.shadow,'none');
      const filmAlpha=alpha(styles.film);
      if(bg==='#ffffff') assert(blur ? filmAlpha>=.65 && filmAlpha<=.68 : filmAlpha>=.80 && filmAlpha<=.82);
      if(bg==='#25272c') assert(blur ? filmAlpha>=.76 && filmAlpha<=.82 : filmAlpha>=.8 && filmAlpha<=.85);
      if(blur) {
        const radius=parseFloat(styles.filter.match(/[0-9.]+/)[0]);
        assert(radius>=18 && radius<=22);
        if(bg==='#ffffff' || bg==='#25272c') assert.equal(radius,bg==='#ffffff'?22:18);
      } else assert(['none','blur(0px)'].includes(styles.filter));
      // Capture a binary letter mask at the precise layout positions of the real options.
      const layoutKey=JSON.stringify([styles.clip,styles.rows.map(r=>r.text)]);
      if(!masks.has(layoutKey)) masks.set(layoutKey,await capture(styles.clip,'#notice-options button {color:white!important;background:black!important;text-shadow:none!important;border-color:black!important;box-shadow:none!important} #notice-options button::after {visibility:hidden}'));
      const pixels=await readRows(styles.clip,styles.rows,masks.get(layoutKey));
      assert.notDeepEqual(pixels.rows[0].base,pixels.rows[1].base,'selected option paints a distinct surface');
      assert.notEqual(hex(pixels.shadow),block || bg,'outer shadow is visible below the picker');
      if(blur && bg==='#ffffff' && block==='#000000') assert(pixels.rows[0].base.every(channel=>channel>=160 && channel<=185),'light glass softens a black control while retaining some of its color');
      const values=styles.rows.map(({ink},i)=>{
        const {base,colors,rim,coreCount,minInkDistance}=pixels.rows[i];
        assert(coreCount>10 && minInkDistance<=25,'rendered text is present in the expected ink');
        const level=luminance(ink)+.05;
        const contrast=rgb=>{const t=luminance(rgb)+.05;return Math.max(level,t)/Math.min(level,t)};
        const min=Math.min(...colors.map(contrast));
        assert(rim.length,'rendered glyphs have an exposed edge');
        const edgeMin=Math.min(...rim.map(contrast));
        assert(edgeMin>=4.5,'glyph edge contrast '+edgeMin.toFixed(2)+' over '+(block || bg));
        assert(min>=4.5,'glyph contrast '+min.toFixed(2)+' over '+(block || bg));
        return hex(ink)+' over row '+hex(base)+', glyph min '+min.toFixed(2)+', edge '+edgeMin.toFixed(2);
      });
      console.log('notification',bg,block || 'plain',blur?'blur':'fallback',...values);
    }
  } finally {
    await evaluate("paneRoot().querySelector('#notice-glyph-proof')?.remove();paneRoot().querySelector('#notice-test-field')?.remove();SugarEdgeNav.configure("+JSON.stringify({backdrop:before.pageSurface,blur:before.blurDp>0})+")");
  }
}
async function checkNotificationSettings({evaluate,click,point,tap,paint,send}) {
  await click('settings','overview');
  await evaluate("paneRoot().querySelector('[data-go=notifications]').click()");
  await paint();
  const read = () => evaluate(`(() => {
    const pane=paneRoot().querySelector('[data-pane=notifications]');
    return [pane.hidden,pane.querySelector('#notice-completion').value,...[...pane.querySelectorAll('[role=switch]')].map(b=>b.getAttribute('aria-checked')),pane.querySelector('.system-notice').hidden];
  })()`);
  assert.deepEqual(await read(),[false,'off','false','true',true], 'persisted Android preferences hydrate the controls');
  await evaluate(`window.noticeMessages=[];window.recordNotice=({data})=>noticeMessages.push(JSON.parse(data));
    palettePort.addEventListener('message',recordNotice);`);
  const trigger = "paneRoot().querySelector('#notice-completion')";
  const picker = "paneRoot().querySelector('#notice-options')";
  await point(trigger);
  assert.deepEqual(await evaluate(`(()=>{
    const d=${picker}, r=d.getBoundingClientRect();
    return [d.open, getComputedStyle(d,'::backdrop').backgroundColor,
      getComputedStyle(d.querySelector('button')).fontSize, paneRoot().activeElement.value,
      r.left>=0 && r.top>=0 && r.right<=innerWidth && r.bottom<=innerHeight];
  })()`),[true,'rgba(0, 0, 0, 0)','13px','off',true]);
  await checkNotificationGlass({evaluate,send,paint});
  await tap(12,65); await paint();
  assert.equal(await evaluate(picker+".open"),false,'outside tap closes just the menu');
  await point(trigger);
  await evaluate('SugarShell.back()');
  assert.equal(await evaluate("pageNow()"),'notifications');
  assert.equal(await evaluate("paneRoot().activeElement.id"),'notice-completion');
  await point(trigger);
  await evaluate("paneRoot().activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'Home',bubbles:true})); paneRoot().activeElement.click()");
  await point(trigger);
  await point(picker+".querySelector('[value=always]')");
  await evaluate(`const pane=paneRoot().querySelector('[data-pane=notifications]');
    pane.querySelector('[data-notice=permission]').click(); pane.querySelector('[data-notice=question]').click();
    palettePort.postMessage(JSON.stringify({type:'notificationStatus',enabled:false}));`);
  await paint();
  assert.deepEqual(await read(),[false,'always','true','false',false], 'permission warning coexists with independent switches');
  await evaluate("paneRoot().querySelector('[data-system-notifications]').click()"); await paint();
  const msgs=await evaluate("palettePort.removeEventListener('message',recordNotice);noticeMessages");
  assert.deepEqual(msgs.filter(m=>m.type==='notificationSettings').map(m=>m.changes),
    [{completion:'background'},{completion:'always'},{permission:true},{question:false}]);
  assert(msgs.some(m=>m.type==='openNotificationSettings'));
  assert(!msgs.some(m=>m.type==='settings'));
  await click('back','overview'); await click('back','dsh/home');
}
async function checkAdbSettings({evaluate,click}) {
  await click('settings','overview');
  await evaluate(`window.adbMessages=[];
    window.recordAdb=e=>{const m=JSON.parse(e.data);if(/^adb|^openWireless/.test(m.type)) adbMessages.push(m)};
    palettePort.addEventListener('message',recordAdb);`);
  await click('adb','adb');
  const result = await evaluate(`(async () => {
    const pane = paneRoot().querySelector('[data-pane=adb]');
    const [pair,connect] = pane.querySelectorAll('form'), control = key => pane.querySelector('[data-adb-'+key+']');
    const tick = () => new Promise(r => setTimeout(r,20));
    async function state(values) {
      palettePort.postMessage(JSON.stringify({type:'adbState',supported:true,busy:false,paired:false,connected:false,...values}));
      await tick();
    }
    await state({supported:false});
    const unavailable = pane.querySelector('.adb-setup').hidden;
    await state({});
    const details = pane.querySelector('details'), manualStartsClosed = !details.open;
    const title = () => pane.querySelector('.adb-status').textContent;
    control('notification').click();
    await state({busy:true,cancellable:true,phase:'awaitingCode'});
    const needsCode = title().includes('配对码') && !control('enable').hidden && !control('disconnect').hidden;
    control('enable').click(); control('disconnect').click(); await state({});
    details.open = true;
    pair.elements.port.value='70000'; pair.elements.code.value='012345';
    pair.requestSubmit(); await tick();
    const invalidBlocked = !adbMessages.some(m=>m.type==='adbPair');
    pair.elements.port.value='37001'; pair.elements.port.dispatchEvent(new Event('input'));
    pair.requestSubmit(); const cleared = pair.elements.code.value === '';
    await state({paired:true});
    details.open = false;
    const autoAvailable = !control('auto').hidden && control('first-pair').hidden && !control('repair').hidden &&
      !control('repair').checkVisibility();
    control('auto').click();
    const checking = title().includes('检查');
    await state({paired:true,busy:true,phase:'requestingPermission',cancellable:false});
    const permission = title().includes('授权') && control('disconnect').hidden;
    await state({paired:true,busy:true,phase:'waitingForWireless',cancellable:true});
    const waiting = title().includes('开启无线调试') && !control('enable').hidden && control('disconnect').textContent==='取消';
    control('enable').click();
    const statusCount = () => adbMessages.filter(m=>m.type==='adbStatus').length;
    const before = statusCount();
    window.dispatchEvent(new Event('focus')); await new Promise(r => setTimeout(r,850));
    const resume = statusCount() >= before + 2;
    await state({paired:true,busy:true,phase:'discoveringConnect',cancellable:true});
    const searching = title().includes('搜索');
    await state({paired:true,busy:true,phase:'connecting',cancellable:true});
    const connecting = title().includes('正在连接') && !control('disconnect').hidden;
    await state({paired:true,message:'未找到连接端口'});
    const retry = !control('auto').disabled && control('auto').textContent==='重新连接' &&
      pane.querySelector('.adb-message').textContent==='未找到连接端口';
    details.open = true;
    connect.elements.port.value='37999'; connect.requestSubmit();
    await state({paired:true,connected:true,busy:true,phase:'connected'});
    const ready = !control('disconnect').hidden && !control('disconnect').disabled && pane.querySelector('.adb-steps').hidden;
    control('disconnect').click(); await state({paired:true});
    control('auto').click();
    const now=Date.now; Date.now=()=>now()+10000;
    try { await new Promise(r=>setTimeout(r,2000)); } finally { Date.now=now; }
    const stale = !control('refresh').hidden && title().includes('无法读取');
    control('refresh').click();
    await state({paired:true,connected:true,phase:'connected'});
    const recovered = control('refresh').hidden && title().includes('已连接');
    palettePort.removeEventListener('message',recordAdb);
    return {unavailable,manualStartsClosed,needsCode,invalidBlocked,cleared,autoAvailable,checking,permission,
      waiting,resume,searching,connecting,retry,ready,stale,recovered,requests:adbMessages};
  })()`);
  for (const [key,value] of Object.entries(result)) if (key!=='requests') assert.equal(value,true,key);
  assert(result.requests.some(m=>m.type==='adbStatus'));
  assert.deepEqual(result.requests.filter(m=>m.type!=='adbStatus'),[
    {type:'adbPairNotification'}, {type:'openWirelessSettings'}, {type:'adbDisconnect'},
    {type:'adbPair',port:37001,code:'012345'}, {type:'adbAutoConnect'}, {type:'openWirelessSettings'},
    {type:'adbConnect',port:37999}, {type:'adbDisconnect'}, {type:'adbAutoConnect'}
  ]);
  await click('back','overview'); await click('back','dsh/home');
}
async function checkSplashSettings({evaluate,click,paint}) {
  await click('settings','overview');
  await click('splash','splash');
  const result = await evaluate(`(async () => {
    const pane = paneRoot().querySelector('[data-pane=splash]');
    const field = selector => pane.querySelector(selector);
    const press = selector => field(selector).click();
    const input = (selector,value,type) => {
      field(selector).value=value; field(selector).dispatchEvent(new Event(type,{bubbles:true}));
    };
    const nextFrame = () => new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
    const writes=[], native={...window.testSplash};
    const deliver=error=>palettePort.postMessage(JSON.stringify({type:'splashState',splash:native,error}));
    const handle=({data})=>{
      const m=JSON.parse(data);
      if(!m.type.startsWith('splash')) return;
      writes.push(m);
      if(m.type==='splashSettings') Object.assign(native,m.changes);
      if(m.type==='splashPick') native.imageName='蓝色 <02>.apng';
      if(m.type==='splashResetImage') native.imageName=null;
      deliver();
    };
    palettePort.addEventListener('message',handle);
    try {
      const original=[field('#splash-scale').value,field('#splash-color').value,field('#splash-text').value];
      input('#splash-scale','84','change');
      press('[data-fit=cover]');
      press('[data-orientation=landscape]');
      const landscape=field('#splash-scale').value;
      input('#splash-scale','38','change');
      press('[data-orientation=portrait]');
      input('#splash-text','','input');
      press('[data-toggle=showProgress]');
      press('[data-timing=fixed]');
      input('#splash-seconds','2.5','change');
      press('[data-toggle=randomExit]');
      input('#splash-color','#789abc','change');
      input('#splash-hex','123Z','change');
      if(field('.color-error').hidden) throw Error('Invalid hex input needs a visible error');
      input('#splash-hex','654321','change');
      press('[data-image=pick]');
      await nextFrame();
      const custom=[field('.image-name').textContent,field('#splash-scale').value,field('[data-fit=cover]').getAttribute('aria-pressed'),
        field('#splash-hex').value,field('#splash-text').value,field('.splash-timer').hidden];
      press('[data-image=reset]');
      press('[data-white]');
      press('[data-timing=ready]');
      await nextFrame();
      deliver('图片不可用'); await nextFrame();
      return {original,landscape,custom,native,writes,final:[field('.image-name').textContent,
        field('.image-error').textContent,field('[data-image=reset]').hidden,field('.splash-timer').hidden]};
    } finally {palettePort.removeEventListener('message',handle);palettePort.close();}
  })()`);
  assert.deepEqual(result.original,['65','#ffffff','正在加载 DSH Sugar…']);
  assert.equal(result.landscape,'55');
  assert.deepEqual(result.custom,['蓝色 <02>.apng','84','true','#654321','',false]);
  assert.deepEqual(result.native,{portraitPercent:84,landscapePercent:38,portraitFit:'cover',landscapeFit:'contain',
    loadingText:'',showProgress:false,durationMs:0,randomExit:true,imageName:null,backgroundColor:'#FFFFFF'});
  assert.deepEqual(result.final,['默认动画','图片不可用',true,true]);
  assert(result.writes.some(m=>m.changes?.durationMs===2500));
  assert(result.writes.every(m=>!m.changes||Object.keys(m.changes).length===1),'changes only write the corresponding startup field');
  await click('back','overview'); await paint();
}

async function checkColorPage({evaluate, click, point, waitPage, paint, screen, fromEdge}) {
  await click('appearance','appearance');
  await evaluate(`new Promise(ready => {
    const c=new MessageChannel();window.palettePort=c.port1;window.paletteWrites=[];
    palettePort.onmessage=({data})=>{const message=JSON.parse(data);
      if(message.type==='settings')paletteWrites.push(message);
      if(message.type==='state')ready();
    };
    postMessage('dsh-sugar/edge-shell','*',[c.port2]);
  })`);
  const ids = ['paper','charcoal','sky','ocean','berry','sand'];
  const route = id => 'tone-' + id;
  async function tapControl(selector) {
    const expression = 'paneRoot().querySelector(' + JSON.stringify(selector) + ')';
    await evaluate(expression + ".scrollIntoView({block:'nearest',inline:'nearest'})");
    await point(expression); await paint();
  }
  async function open(expected) {
    await tapControl('[data-go=palette]');
    const active = await evaluate("document.querySelector('#sugar-shell-settings').dataset.tone");
    if (expected) assert.equal(active,expected);
    assert(ids.includes(active),'palette entry opens a color page');
    await waitPage(route(active));
    assert.equal(await evaluate('SugarEdgeNav.isExpanded()'),true,'the real rail opens for the color test');
    return active;
  }
  async function checkMenu(actions,selected) {
    const actual = await evaluate(`(() => {
      const buttons = [...railRoot.querySelectorAll('button[data-action]')].filter(b => b.getBoundingClientRect().height > 0);
      return {actions:buttons.map(b => b.dataset.action).filter(name => name !== 'forward').sort(),
        selected:buttons.filter(b => b.getAttribute('aria-current') === 'page').map(b => b.dataset.action)};
    })()`);
    assert.deepEqual(actual.actions,[...actions,'home','back'].sort(),'the rail uses exactly the nearest page menu and fixed controls');
    assert.deepEqual(actual.selected,[selected]);
  }
  const settingsActions = ['overview','appearance','gestures','splash','pet','adb'];
  await checkMenu(settingsActions,'appearance');

  async function readTone(id) {
    const state = await evaluate(`(() => {
      const host=document.querySelector('#sugar-shell-settings'), pane=paneRoot().querySelector('[data-pane]:not([hidden])');
      const actions=[...railRoot.querySelectorAll('.item[data-action^=tone-]')].filter(el=>el.getBoundingClientRect().height>0);
      const selected=[...railRoot.querySelectorAll('.item[aria-current=page]')];
      const channels=(el,prop)=>getComputedStyle(el)[prop].match(/[0-9.]+/g).map(Number);
      const item=selected[0];
      return {id:host.dataset.tone,route:pane.dataset.pane,code:pane.querySelector('.tone-code').textContent.trim(),
        actions:actions.map(el=>el.dataset.action),selected:selected.map(el=>el.dataset.action),
        bg:channels(host,'backgroundColor'),glass:channels(railRoot.querySelector('.band'),'backgroundColor'),
        clock:channels(railRoot.querySelector('.clock'),'color'),ink:channels(item,'color'),
        indicator:channels(item.querySelector('i'),'backgroundColor'),stripe:getComputedStyle(host).backgroundImage};
    })()`);
    assert.equal(state.id,id); assert.equal(state.route,route(id));
    assert.deepEqual(state.actions.slice().sort(),ids.map(route).sort(),'all six destinations are on the real rail');
    assert.deepEqual(state.selected,[route(id)],'only the active color page has the rail indicator');
    assert.match(state.code,/^#[a-f0-9]{6}$/i);
    const rgb=state.code.slice(1).match(/../g).map(c=>parseInt(c,16));
    assert.deepEqual(state.bg.slice(0,3),rgb,'the color printed on this page is also rendered behind its rail');
    return state;
  }
  async function choose(id) {
    const button="railRoot.querySelector('[data-action="+route(id)+"]')";
    await evaluate(button + ".scrollIntoView({block:'nearest',inline:'nearest'})");
    await click(route(id),route(id));
    return readTone(id);
  }
  const luma = rgb => rgb.slice(0,3).map(c=>c/255).map(c=>c<=.04045?c/12.92:((c+.055)/1.055)**2.4)
    .reduce((acc,c,i)=>acc+c*[.2126,.7152,.0722][i],0);
  function checkPaint(s,alpha) {
    assert.equal(s.bg[3]??1,1,'the selected color paints an opaque page behind the rail');
    assert(Math.abs((s.glass[3]??1)-alpha)<.015,'rail material keeps the selected transparency');
    const surface=s.glass.slice(0,3).map((c,i)=>c*alpha+s.bg[i]*(1-alpha));
    const [lo,hi]=[luma(surface),luma(s.clock)].sort((a,b)=>a-b);
    assert((hi+.05)/(lo+.05)>4,'readable real clock against the composited rail');
    assert.deepEqual(s.ink,s.clock); assert.deepEqual(s.indicator,s.clock);
  }
  await open('paper');
  await checkMenu(ids.map(route),'tone-paper');
  const paper=await readTone('paper'), charcoal=await choose('charcoal'), sky=await choose('sky');
  for(const s of [paper,charcoal,sky])checkPaint(s,.84);
  assert(luma(paper.bg)>.65 && luma(charcoal.bg)<.15 && Math.max(...sky.bg)-Math.min(...sky.bg)>25);
  assert(paper.clock.every(c=>c<50) && charcoal.clock.every(c=>c>225),'real foreground flips on light and dark pages');
  assert.notDeepEqual(paper.glass,charcoal.glass,'adaptive rail material follows the active page');
  assert.equal(await evaluate('paletteWrites.length'),0,'choosing colors leaves preferences intact');
  await fromEdge('right',route('charcoal')); await readTone('charcoal');
  await fromEdge('left',route('sky')); await readTone('sky');
  const dockWrites=await evaluate('paletteWrites.length'); // Crossing the screen persists the chosen rail side.
  await screen(844,390); await paint();
  for (const id of ['ocean','berry','sand']) {
    const s=await choose(id); checkPaint(s,.84);
    if(id==='berry')assert.match(s.stripe,/repeating-linear-gradient/,'berry page keeps the fine blur comparison stripe');
  }
  assert(await evaluate(`(() => {
    const p=paneRoot().querySelector('[data-pane]:not([hidden])'), r=document.querySelector('#sugar-shell-settings').getBoundingClientRect();
    return r.left===0 && r.top===0 && r.width===innerWidth && r.height===innerHeight && p.scrollWidth<=p.clientWidth+1;
  })()`),'landscape backdrop covers the viewport and controls fit the page width');
  await screen(390,844); await paint();
  await click('home','dsh/home'); await checkMenu(['settings'],'home');
  await click('back',route('sand')); await readTone('sand');
  await choose('sand'); // Selecting the current page must not insert a duplicate history entry.
  for(const id of ids.slice(0,-1).reverse()) {
    await click('back',route(id)); await readTone(id);
  }
  await click('back','appearance');
  await checkMenu(settingsActions,'appearance');
  assert.equal(await evaluate('paletteWrites.length'),dockWrites,'subsequent color and history buttons do not edit user preferences');

  await tapControl('[data-choice=colorMode][value=random]');
  for(const t of [0,50]) {
    await evaluate(`{const slider=paneRoot().querySelector('[name=transparency]');slider.value=${t};
      slider.dispatchEvent(new Event('input',{bubbles:true}));slider.dispatchEvent(new Event('change',{bubbles:true}));}`);
    await paint();
    const count=await evaluate('paletteWrites.length');
    const first=await open();
    const white=await choose('paper'), dark=await choose('charcoal'),alpha=1-t/100;
    checkPaint(white,alpha);checkPaint(dark,alpha);
    assert.deepEqual(white.glass,dark.glass,'random tint stays fixed across color pages');
    if(!t) {
      assert.deepEqual(white.clock,dark.clock,'opaque random material determines expanded foreground');
      await point("railRoot.querySelector('.cap')");await paint();
      const idle=await evaluate("getComputedStyle(railRoot.querySelector('.tab')).backgroundColor.match(/[0-9.]+/g).map(Number)");
      assert(idle.slice(0,3).every(c=>c>225),'closed grip contrasts with the dark color page');
      await point("railRoot.querySelector('.tab')");await paint();
    }
    await click('back',route('paper'));
    if(first!=='paper')await click('back',route(first));
    await click('back','appearance');
    assert.equal(await evaluate("paneRoot().querySelector('[data-choice=colorMode][value=random]').getAttribute('aria-pressed')"),'true');
    assert.equal(await evaluate("paneRoot().querySelector('[name=transparency]').value"),String(t));
    assert.equal(await evaluate('paletteWrites.length'),count,'switching pages leaves preferences intact');
  }
  await click('back','gestures');await click('back','overview');await click('back','dsh/right/index.html');
  assert.equal(await evaluate("document.querySelector('#sugar-shell-settings').hidden"),true);
  await evaluate('SugarEdgeNav.setExpanded(false)');
  assert(await evaluate("getComputedStyle(railRoot.querySelector('.tab')).backgroundColor.match(/[0-9.]+/g).slice(0,3).every(c=>+c<50)"),
    'the ordinary white document regains its dark grip after the color test');
  await checkNearbySamples(evaluate);
  const material = await evaluate(`(async () => {
    const tick = () => new Promise(r => setTimeout(r,32));
    const surface = () => SugarEdgeNav.getPopupPalette().surface;
    palettePort.postMessage('#494949');
    await tick();
    const scrim = surface();
    const overlay = document.createElement('div'), panel = document.createElement('div');
    panel.dataset.shortcutModal = 'settings';
    panel.setAttribute('role','dialog');
    panel.style.backgroundColor = 'white';
    overlay.append(panel); document.body.append(overlay);
    try {
      await Promise.resolve(); // DSH has committed the portal; the dock is still folded.
      const first = surface();
      palettePort.postMessage('#494949'); // stale PixelCopy of the outer gutter
      await tick();
      const late = surface();
      SugarEdgeNav.setExpanded(true);
      await tick();
      return [scrim,first,late,surface()];
    } finally {
      overlay.remove(); SugarEdgeNav.setExpanded(false);
    }
  })()`);
  assert.notEqual(material[0],material[1],'the settings card supplies the initial editor tint before touching the rail');
  assert.deepEqual(material.slice(1),Array(3).fill(material[1]),'a late edge sample and unfolding the dock preserve the first editor tint');

}
async function checkHomeBackdrop({evaluate,click,point,waitPage,paint}) {
  await click('home','dsh/home');
  await evaluate(`(() => {
    const home=document.querySelector('main'), host=document.querySelector('#sugar-shell-settings');
    const events=[];
    let version=0,exits=0,settings=false;
    const timerIds=new Set();
    const initial=home.style.backgroundColor;
    const read=()=>{
      const channels=css=>css.match(/[0-9.]+/g).slice(0,3).map(Number);
      return {grip:channels(railRoot.host.style.getPropertyValue('--idle-handle')),
        clock:channels(getComputedStyle(railRoot.querySelector('.clock')).color),
        glass:getComputedStyle(railRoot.querySelector('.band')).backgroundColor};
    };
    // Stand in for Android PixelCopy: the earlier compositor read contains the light
    // settings sheet. Only a subsequent read with the sheet hidden contains the dark home.
    const sample=({data})=>{
      const msg=JSON.parse(data);
      if(msg.type==='invalidate'){version++;return;}
      if(msg.type==='state'){settings=Boolean(msg.settings);return;}
      if(msg.type!=='sample'||settings)return;
      const exiting=!host.hidden && host.hasAttribute('data-exiting');
      const css=getComputedStyle(exiting?host:home).backgroundColor;
      const hex='#'+css.match(/[0-9]+/g).slice(0,3).map(n=>Number(n).toString(16).padStart(2,'0')).join('');
      const epoch=version;
      events.push({exiting,hex});
      const timer=setTimeout(()=>{
        timerIds.delete(timer);
        if(epoch===version)palettePort.postMessage(hex);
      },16);
      timerIds.add(timer);
    };
    const observer=new MutationObserver(()=>{if(host.hasAttribute('data-exiting'))exits++;});
    observer.observe(host,{attributes:true,attributeFilter:['data-exiting']});
    palettePort.addEventListener('message',sample);
    window.homeSamples={events,read,reset(){events.length=0;},exits:()=>exits,
      background:color=>{home.style.backgroundColor=color;},
      waitFor:async(hex)=>{
        const until=performance.now()+2200;
        while(performance.now()<until){
          if(events.some(e=>!e.exiting&&e.hex===hex) && read().grip.every(n=>n>220))return;
          await new Promise(r=>setTimeout(r,20));
        }
        throw Error('Home backdrop did not settle while idle: '+JSON.stringify({events,ink:read(),exits}));
      },dispose(){
        observer.disconnect();palettePort.removeEventListener('message',sample);
        for(const t of timerIds)clearTimeout(t);
        home.style.backgroundColor=initial;
      }};
  })()`);
  async function finishPageMotion() {
    await evaluate("Promise.all([...paneRoot().getAnimations(),...document.querySelector('#sugar-shell-settings').getAnimations()].filter(a=>a.id.startsWith('sugar-pane-')).map(a=>a.finished.catch(()=>{})))");
    await paint();
  }
  try {
    for(const [mode,homeColor] of [['adaptive','#182433'],['random','#202f46']]) {
      await click('settings','overview');await click('appearance','appearance');await finishPageMotion();
      const modeControl="paneRoot().querySelector('[data-choice=colorMode][value="+mode+"]')";
      await point(modeControl);
      const entry="paneRoot().querySelector('[data-go=palette]')";
      await evaluate(entry+".scrollIntoView({block:'nearest'})");await point(entry);
      if(await evaluate('pageNow()')!=='tone-paper')await click('tone-paper','tone-paper');
      await waitPage('tone-paper');await finishPageMotion();
      await evaluate("homeSamples.background("+JSON.stringify(homeColor)+");homeSamples.reset()");
      const before=await evaluate('homeSamples.read()'),exits=await evaluate('homeSamples.exits()');
      assert(before.grip.every(n=>n<50),'the light outgoing page uses dark grip ink');
      await click('home','dsh/home');
      // Only read state from this point. Another pointerup, resize, scroll or route
      // event could accidentally repair the stale sample and hide the regression.
      await evaluate("homeSamples.waitFor("+JSON.stringify(homeColor)+")");
      const landed=await evaluate('homeSamples.read()');
      assert.equal(await evaluate('homeSamples.exits()'),exits+1,'Home ran the actual settings exit');
      assert(landed.grip.every(n=>n>220),'the dark Home updates the closed grip without requiring another gesture');
      if(mode==='adaptive'){
        assert.notEqual(landed.glass,before.glass,'adaptive rail material follows the settled home');
        assert(landed.clock.every(n=>n>220),'dark home and its adaptive material use light text');
      }else assert.equal(landed.glass,before.glass,'random tint stays fixed when the sampled page changes');
      await click('back','tone-paper'); await finishPageMotion();
      assert.deepEqual(await evaluate('homeSamples.read()'),before,'back restores the color page independently of the home sample');
      await click('home','dsh/home');
    }
  }finally{
    await evaluate('homeSamples.dispose();delete window.homeSamples');
  }
}

async function checkNearbySamples(evaluate) {
  const counts = await evaluate(`(async () => {
    const overlay=document.createElement('div');
    overlay.style.cssText='position:fixed;inset:0;display:flex;z-index:1000';
    function half() {
      const panel=document.createElement('section'), nested=document.createElement('div'), leaf=document.createElement('div');
      panel.style.flex='1'; leaf.style.height=nested.style.height='100%';
      nested.append(leaf);panel.append(nested);overlay.append(panel);
      return nested;
    }
    const left=half(),right=half(),onLeft=document.querySelector('#sugar-edge-navigation').dataset.side==='left';
    const near=onLeft?left:right, far=onLeft?right:left;
    const quiet=()=>new Promise(resolve=>setTimeout(resolve,350));
    let count=0,resolveNext,deadline;
    const sample=({data})=>{if(JSON.parse(data).type==='sample'){count++;resolveNext?.();}};
    function nextSample(change) {
      return new Promise((resolve,reject)=>{
        deadline=setTimeout(()=>reject(Error('No rendered-strip sample after page color changed')),2000);
        resolveNext=()=>{clearTimeout(deadline);resolveNext=null;resolve();};
        change();
      });
    }
    palettePort.addEventListener('message',sample);
    try {
      document.body.append(overlay);
      await nextSample(()=>window.dispatchEvent(new Event('sugar-navigation-state')));
      await quiet();
      const initial=count;
      await nextSample(()=>{near.style.backgroundColor='#273642';});
      const changed=count;
      await quiet();
      const idle=count;
      far.style.backgroundColor='#d0b654'; await quiet();
      return {initial,changed,idle,far:count};
    } finally {
      clearTimeout(deadline);palettePort.removeEventListener('message',sample);overlay.remove();
    }
  })()`);
  assert.equal(counts.changed,counts.initial+1,'in-place color change in a nested container beside the rail requests a sample');
  assert.equal(counts.idle,counts.changed,'an idle page does not poll the rendered strip');
  assert.equal(counts.far,counts.idle,'a style-only change on the far side leaves the strip untouched');
}
// Capture actual compositor pixels over a transparent DSH column. Its page color normally
// comes from the frame underneath it, so blending the two texture layers would double the text.
async function checkScopedPages({evaluate, paint, send}) {
  await evaluate(readFileSync(join(assets,'sugar-page-motion.js'),'utf8'));
  if (!await evaluate("typeof Element.prototype.startViewTransition==='function'")) {
    await evaluate('SugarPageMotion.dispose()'); return;
  }
  await evaluate(`document.body.style.margin='0';document.body.innerHTML='<main class="pI_x6G_centerCol"></main>';
    window.scopedPage=document.querySelector('main');
    scopedPage.style.cssText='width:100vw;height:100vh;position:relative';
    window.colors={old:'rgb(140, 55, 40)',next:'rgb(25, 65, 165)'};
    window.setFixturePage=name=>{document.body.style.backgroundColor=colors[name];scopedPage.textContent=name;};
    setFixturePage('old');`);
  for (const direction of ['back','forward']) {
    await evaluate(`window.sliding=SugarPageMotion.beginDrag({direction:'${direction}',target:()=>scopedPage,
      captureScope:scopedPage,captureAllowed:()=>true,navigate:async()=>{setFixturePage('next');return true},
      restore:async()=>{setFixturePage('old');return true}});sliding.update(.5);`);
    await evaluate(`new Promise((done,fail)=>{const until=performance.now()+2500;function next(){
      if(scopedPage.getAnimations({subtree:true}).some(a=>a.effect.pseudoElement==='::view-transition-new(root)' && a.currentTime===500))return done();
      if(performance.now()>until)return fail(Error('the incoming DSH layer was not staged: '+JSON.stringify(scopedPage.getAnimations({subtree:true}).map(a=>[a.animationName,a.effect.pseudoElement,a.currentTime]))));
      requestAnimationFrame(next)}next()})`);
    await paint();
    const {data}=await send('Page.captureScreenshot',{format:'png',clip:{x:0,y:110,width:390,height:8,scale:1}});
    const sides=await evaluate(`(async()=>{
      const img=new Image();img.src='data:image/png;base64,${data}';await img.decode();
      const ctx=new OffscreenCanvas(img.width,img.height).getContext('2d');ctx.drawImage(img,0,0);
      return [65,320].map(x=>Array.from(ctx.getImageData(x,3,1,1).data).slice(0,3));
    })()`);
    const old=[140,55,40], next=[25,65,165];
    assert.deepEqual(sides,direction==='back'?[next,old]:[old,next], 'destination enters as a solid foreground from '+direction);
    await evaluate("sliding.finish(false,true)");
    assert.deepEqual(await evaluate("[scopedPage.textContent,scopedPage.hasAttribute('data-sugar-drag-scope')]"),['old',false]);
  }
  // Rollback can restore the selected conversation before DSH mounts any of its message rows.
  await evaluate(`window.chatShell='<section data-conversation-region="chat" data-conversation-session="old" style="height:100%"></section>';
    scopedPage.innerHTML=chatShell;scopedPage.firstElementChild.innerHTML='<article data-chat-turn>Old message</article>';
    window.stagedChat=SugarPageMotion.beginDrag({direction:'back',target:()=>scopedPage,captureScope:scopedPage,
      captureAllowed:()=>true,navigate:async()=>{setFixturePage('next');return true},restore:async()=>{
        scopedPage.innerHTML=chatShell;window.restoringChat=true;return true}});
    stagedChat.update(.5);`);
  await evaluate(`new Promise((ok,fail)=>{const until=performance.now()+2000;function poll(){
    if(scopedPage.getAnimations({subtree:true}).some(a=>a.effect.pseudoElement==='::view-transition-new(root)' && a.currentTime===500))return ok();
    if(performance.now()>until)return fail(Error('conversation transition failed to stage'));requestAnimationFrame(poll)}poll()})`);
  await evaluate("window.rollbackDone=stagedChat.finish(false,true);void 0");
  await paint();
  assert.equal(await evaluate("restoringChat && scopedPage.hasAttribute('data-sugar-drag-scope')"),true,
    'the old compositor image covers the restored chat while its rows are loading');
  await evaluate("scopedPage.firstElementChild.innerHTML='<article data-chat-turn>Old message</article>';rollbackDone");
  assert.equal(await evaluate("scopedPage.hasAttribute('data-sugar-drag-scope')"),false);
  await evaluate(`window.heroMarkup='<div data-conversation-content data-conversation-region="chat" data-content-phase="hero" style="background:rgb(25,65,165);height:100%"><span>Home</span><textarea>draft fixture</textarea><div data-composer-seat><div class="_54WpYG_rail">private file</div></div></div>';
    scopedPage.innerHTML=heroMarkup;SugarPageMotion.watchHome('dsh/home');`);
  await paint();
  await evaluate(`document.head.append(Object.assign(document.createElement('style'),{id:'home-inset-test',
      textContent:'[data-sugar-home-preview] > main {padding-bottom:16px!important}'}));
    SugarPageMotion.watchHome(null);setFixturePage('old');
    window.openedHome=0;window.startHomeSwipe=(direction,canStage)=>SugarPageMotion.beginDrag({direction,target:()=>scopedPage,
      homeTarget:'dsh/home',canStage,captureScope:scopedPage,captureAllowed:()=>true,
      restore:()=>{throw Error('a cancelled home preview reopened the live chat')},navigate:async()=>{++openedHome;scopedPage.innerHTML=heroMarkup;return true;}});
    window.untouched=SugarPageMotion.beginDrag({direction:'back',target:()=>scopedPage,canStage:false,homeTarget:'another workspace',navigate:()=>false});
    untouched.update(.4)`);
  assert.equal(await evaluate("!!document.querySelector('[data-sugar-home-preview]')"),false);
  await evaluate("untouched.finish(false,true)");
  for (const direction of ['back','forward']) {
    await evaluate(`window.homeSwipe=startHomeSwipe('${direction}',${direction==='back'});homeSwipe.update(.5)`);
    await paint();
    assert.deepEqual(await evaluate(`(()=>{const el=document.querySelector('[data-sugar-home-preview]');return [
      el?.inert,el?.querySelector('textarea')?.value,!!el?.querySelector('._54WpYG_rail'),
      getComputedStyle(el.firstElementChild).paddingBottom,scopedPage.textContent,openedHome]})()`),
      [true,'',false,'0px','old',0],'an unallocated home is visible and never opens a session during the drag');
    const {data}=await send('Page.captureScreenshot',{format:'png',clip:{x:0,y:110,width:390,height:8,scale:1}});
    const pixels=await evaluate(`(async()=>{const i=new Image();i.src='data:image/png;base64,${data}';await i.decode();
      const c=new OffscreenCanvas(i.width,i.height).getContext('2d');c.drawImage(i,0,0);
      return [65,320].map(x=>Array.from(c.getImageData(x,3,1,1).data).slice(0,3))})()`);
    const old=[140,55,40], home=[25,65,165];
    assert.deepEqual(pixels,direction==='back'?[home,old]:[old,home], 'home appears under the finger before release from '+direction);
    await evaluate('homeSwipe.finish(false,true)');
    assert.deepEqual(await evaluate("[openedHome,!!document.querySelector('[data-sugar-home-preview]'),scopedPage.textContent]"),[0,false,'old']);
  }
  await evaluate("window.homeSwipe=startHomeSwipe('forward',true);homeSwipe.update(.65);homeSwipe.finish(true,true)");
  assert.deepEqual(await evaluate("[openedHome,!!document.querySelector('[data-sugar-home-preview]'),!!scopedPage.querySelector('[data-content-phase=hero]')]"),[1,false,true]);
  // Put actual message pixels at BOTH edges. A color-only page would also pass
  // if the entire destination slid sideways, leaving its first exposed strip empty.
  await evaluate(`window.chatMarkup='<section data-conversation-content data-conversation-region="chat" data-conversation-session="A" style="height:100%;background:#234"><div data-chat-turn style="position:absolute;left:4px;right:4px;top:130px;height:11px;background:#ee2635"></div><textarea>private draft</textarea><div class="scroller" style="height:20px;overflow:auto"><p style="height:200px">scroll</p></div></section>';
    scopedPage.innerHTML=chatMarkup;scopedPage.querySelector('.scroller').scrollTop=48;`);
  await paint();
  await evaluate("SugarPageMotion.rememberPeer(scopedPage,'dsh/session-A');setFixturePage('old');window.commitCount=0");
  for (const direction of ['back','forward']) {
    await evaluate(`window.peer=SugarPageMotion.beginDrag({direction:'${direction}',target:()=>scopedPage,peerTarget:'dsh/session-A',
      navigate:()=>{++commitCount;scopedPage.innerHTML=chatMarkup;return true}});`);
    for (const p of [.05,.5,.22]) {
      const positions=await evaluate(`(()=>{peer.update(${p});const layer=document.querySelector('[data-sugar-peer-preview]');
        const mark=layer.querySelector('[data-chat-turn]').getBoundingClientRect();
        return [Math.round(mark.left),Math.round(mark.right),layer.querySelector('textarea').value,
          layer.querySelector('.scroller').scrollTop,scopedPage.textContent,commitCount];})()`);
      assert.deepEqual(positions,[4,386,'',48,'old',0],'message edges stay anchored even on reversal');
      const {data}=await send('Page.captureScreenshot',{format:'png',clip:{x:0,y:133,width:390,height:4,scale:1}});
      const edges=await evaluate(`(async()=>{const img=new Image();img.src='data:image/png;base64,${data}';await img.decode();
        const c=new OffscreenCanvas(img.width,img.height).getContext('2d');c.drawImage(img,0,0);
        return [8,382].map(x=>[...c.getImageData(x,1,1,1).data].slice(0,3))})()`);
      assert.deepEqual(edges, direction==='back'?[[238,38,53],[140,55,40]]:[[140,55,40],[238,38,53]],
        'the first revealed strip already contains the destination text position');
    }
    await evaluate('peer.finish(false,true)');
  }
  // A WebView without scoped transitions can uncover the real new route beneath a
  // replica of the old chat. When neither is safe, the route stays put until lift.
  await evaluate(`scopedPage.innerHTML=chatMarkup;scopedPage.startViewTransition=undefined;
    window.legacy=SugarPageMotion.beginDrag({direction:'back',target:()=>scopedPage,originKey:'chat',
      peerTarget:'uncached',captureScope:scopedPage,captureAllowed:()=>true,
      navigate:()=>{setFixturePage('next');return true},restore:()=>{scopedPage.innerHTML=chatMarkup;return true}});
    legacy.update(.05);`);
  await paint();
  assert.deepEqual(await evaluate(`[scopedPage.textContent,document.querySelector('[data-sugar-peer-preview]').inert,
    getComputedStyle(document.querySelector('[data-sugar-peer-preview]')).clipPath,
    Math.round(document.querySelector('[data-sugar-peer-preview] [data-chat-turn]').getBoundingClientRect().left)]`),
    ['next',true,'inset(0% 0% 0% 5%)',4],'older WebViews also reveal from the first move at fixed coordinates');
  await evaluate("legacy.finish(false,true)");
  assert.equal(await evaluate("!!scopedPage.querySelector('[data-chat-turn]') && !document.querySelector('[data-sugar-peer-preview]')"),true);
  await evaluate(`setFixturePage('old');window.unsafeChanges=0;window.unsafeDrag=SugarPageMotion.beginDrag({
    direction:'forward',target:()=>scopedPage,webRoute:true,navigate:()=>{++unsafeChanges;setFixturePage('next');return true},
    restore:()=>{setFixturePage('old');return true}});
    unsafeDrag.update(.15);unsafeDrag.update(.7)`);
  assert.deepEqual(await evaluate("[unsafeChanges,scopedPage.textContent,getComputedStyle(scopedPage).transform]"),[0,'old','none']);
  await evaluate("unsafeDrag.finish(true,true)");
  assert.deepEqual(await evaluate("[unsafeChanges,scopedPage.textContent]"),[1,'next']);
  await evaluate("document.getElementById('home-inset-test').remove();SugarPageMotion.dispose();scopedPage.remove();document.body.style.removeProperty('background-color')");
}
// Global panels unmount their local editor state when another route is selected.
// Exercise the inert preview path on both current and older WebViews; this check
// deliberately does not depend on scoped view-transition support or a CSS hash.
async function checkGlobalPages({evaluate, paint, send}) {
  await evaluate(readFileSync(join(assets,'sugar-page-motion.js'),'utf8'));
  await evaluate(`document.body.style.cssText='margin:0;background:rgb(140,55,40)';
    document.body.innerHTML='<main class="upstream-column-renamed" style="width:100vw;height:100vh"><div data-global-panel="true" style="height:100%"><div data-slot="main" style="height:100%"></div></div></main>';
    window.globalColumn=document.querySelector('[data-slot="main"]').parentElement.parentElement;
    window.globalSlot=document.querySelector('[data-slot="main"]');
    window.globalCommits=0;
    window.renderGlobalPage=id=>{
      globalSlot.innerHTML='<section data-panel-page="'+id+'" style="position:relative;height:100%;background:rgb(140,55,40)">'+
        '<h1 style="margin:0">'+id+'</h1><input class="panel-query" value="unsaved panel text">'+
        '<input type="password" value="private secret">'+
        '<div class="panel-mark" style="position:absolute;left:4px;right:4px;top:130px;height:11px;background:rgb(238,38,53)"></div>'+
        '<div class="panel-scroll" style="position:absolute;top:180px;height:40px;width:200px;overflow:auto"><div style="height:300px">panel list</div></div></section>';
      const input=globalSlot.querySelector('.panel-query'), scroll=globalSlot.querySelector('.panel-scroll');
      input.value='unsaved '+id;scroll.scrollTop=62;scroll.dispatchEvent(new Event('scroll'));
      return input;
    };
    window.nativeGlobalTransition=globalColumn.startViewTransition;`);
  const previewPositions=[.03,.24,.26,.55,.12,.8];
  for (const legacy of [false,true]) {
    await evaluate(`globalColumn.startViewTransition=${legacy?'undefined':'nativeGlobalTransition'};`);
    for (const panelId of ['plugins','dsh-market','schedules','external.vendor.toolbox']) {
      await evaluate(`renderGlobalPage(${JSON.stringify(panelId)});
        SugarPageMotion.rememberPeer(globalColumn,${JSON.stringify('panel/'+panelId)});`);
      for (const direction of ['back','forward']) {
        await evaluate(`window.originalEditor=renderGlobalPage('source');
          globalSlot.querySelector('.panel-mark').style.backgroundColor='rgb(140,55,40)';
          window.originalPanel=globalSlot.firstElementChild;
          window.panelPreview=SugarPageMotion.beginDrag({direction:'${direction}',target:()=>globalColumn,
            peerTarget:${JSON.stringify('panel/'+panelId)},webRoute:true,canStage:false,
            captureScope:globalColumn,captureAllowed:()=>true,
            navigate:()=>{++globalCommits;renderGlobalPage(${JSON.stringify(panelId)});return true},
            restore:()=>{throw Error('a cancelled global panel preview must not remount the editor')}});`);
        const before=await evaluate('globalCommits');
        for (const p of previewPositions) {
          const pose=await evaluate(`(()=>{panelPreview.update(${p});
            const layer=document.querySelector('[data-sugar-peer-preview]');
            if(!layer)throw Error('No generic panel preview for ${panelId}');
            const mark=layer.querySelector('.panel-mark').getBoundingClientRect();
            return [layer.inert,layer.querySelector('[data-panel-page]').dataset.panelPage,
              Math.round(mark.left),Math.round(mark.right),layer.querySelector('.panel-query').value,
              !!layer.querySelector('input[type=password]'),layer.querySelector('.panel-scroll').scrollTop,
              globalCommits,originalEditor===globalSlot.querySelector('.panel-query'),originalEditor.value,
              globalSlot.querySelector('.panel-scroll').scrollTop,getComputedStyle(globalColumn).transform];})()`);
          assert.deepEqual(pose,[true,panelId,4,386,'',false,62,before,true,'unsaved source',62,'none'],
            `${panelId}: ${legacy?'legacy':'current'} ${direction} at ${p} keeps the editor mounted and the preview fixed`);
          await paint();
          const {data}=await send('Page.captureScreenshot',{format:'png',clip:{x:0,y:133,width:390,height:4,scale:1}});
          const edges=await evaluate(`(async()=>{const img=new Image();img.src='data:image/png;base64,${data}';await img.decode();
            const c=new OffscreenCanvas(img.width,img.height).getContext('2d');c.drawImage(img,0,0);
            return [8,382].map(x=>[...c.getImageData(x,1,1,1).data].slice(0,3))})()`);
          const old=[140,55,40], next=[238,38,53];
          assert.deepEqual(edges,direction==='back'?[next,old]:[old,next],
            `${panelId}: no duplicate page or jump around the former 25% route threshold`);
        }
        await evaluate('panelPreview.finish(false,true)');
        assert.deepEqual(await evaluate(`[globalCommits,originalPanel===globalSlot.firstElementChild,
          originalEditor===globalSlot.querySelector('.panel-query'),originalEditor.value,
          globalSlot.querySelector('.panel-scroll').scrollTop,!!document.querySelector('[data-sugar-peer-preview]')]`),
          [before,true,true,'unsaved source',62,false],'cancel preserves the exact source form and its scroll');
      }
      await evaluate(`window.committedPanel=SugarPageMotion.beginDrag({direction:'back',target:()=>globalColumn,
        peerTarget:${JSON.stringify('panel/'+panelId)},webRoute:true,canStage:false,
        navigate:()=>{++globalCommits;renderGlobalPage(${JSON.stringify(panelId)});return true}});
        committedPanel.update(.6);`);
      const before=await evaluate('globalCommits');
      await evaluate('Promise.all([committedPanel.finish(true,true),committedPanel.finish(true,true)])');
      assert.deepEqual(await evaluate(`[globalCommits,globalSlot.firstElementChild.dataset.panelPage,
        !!document.querySelector('[data-sugar-peer-preview]')]`),[before+1,panelId,false],
        'a committed panel route is selected once, after release');
    }
  }
  // Retaining the page being left is independent of permission to stage its route.
  // A cancelled first visit must not show a clipped copy of the source over itself,
  // yet that retained picture should be usable for a later return to the panel.
  for (const legacy of [false,true]) {
    const origin='retained-origin-'+legacy, key='panel/'+origin;
    await evaluate(`globalColumn.startViewTransition=${legacy?'undefined':'nativeGlobalTransition'};
      window.retainedEditor=renderGlobalPage(${JSON.stringify(origin)});window.retainedChanges=0;
      window.retaining=SugarPageMotion.beginDrag({direction:'back',target:()=>globalColumn,
        originKey:${JSON.stringify(key)},peerTarget:'uncached-return-target',webRoute:true,canStage:false,
        captureScope:globalColumn,captureAllowed:()=>true,
        navigate:()=>{++retainedChanges;renderGlobalPage('uncached-return-target');return true},
        restore:()=>{throw Error('retaining an inert panel picture must not switch routes')}});
      retaining.update(.24);retaining.update(.26);retaining.update(.7);`);
    assert.deepEqual(await evaluate(`[retainedChanges,retainedEditor===globalSlot.querySelector('.panel-query'),
      retainedEditor.value,!!document.querySelector('[data-sugar-peer-preview]'),
      globalColumn.hasAttribute('data-sugar-drag-scope'),getComputedStyle(globalColumn).transform]`),
      [0,true,'unsaved '+origin,false,false,'none'],
      'canStage=false caches the source without manufacturing a source-only transition');
    await evaluate('retaining.finish(false,true)');
    assert.deepEqual(await evaluate(`[retainedChanges,retainedEditor===globalSlot.querySelector('.panel-query'),
      globalSlot.querySelector('.panel-scroll').scrollTop]`),[0,true,62]);
    await evaluate(`renderGlobalPage('after-cancel');window.returningToPanel=SugarPageMotion.beginDrag({
      direction:'forward',target:()=>globalColumn,peerTarget:${JSON.stringify(key)},webRoute:true,canStage:false,
      navigate:()=>{throw Error('the cached return remains a preview')}});returningToPanel.update(.4);`);
    assert.deepEqual(await evaluate(`(()=>{const layer=document.querySelector('[data-sugar-peer-preview]');return [
      layer?.querySelector('[data-panel-page]').dataset.panelPage,layer?.querySelector('.panel-query').value,
      layer?.querySelector('.panel-scroll').scrollTop,globalSlot.firstElementChild.dataset.panelPage,retainedChanges]})()`),
      [origin,'',62,'after-cancel',0],
      'originKey refreshes the panel cache even when the earlier drag could not stage and was cancelled');
    await evaluate('returningToPanel.finish(false,true)');
  }
  await evaluate(`window.originalEditor=renderGlobalPage('source');window.missingCommit=0;
    window.missingPanel=SugarPageMotion.beginDrag({direction:'forward',target:()=>globalColumn,
      peerTarget:'uncached-panel',webRoute:true,canStage:false,
      navigate:()=>{++missingCommit;renderGlobalPage('uncached-panel');return true}});
    missingPanel.update(.24);missingPanel.update(.26);missingPanel.update(.8);`);
  assert.deepEqual(await evaluate(`[missingCommit,originalEditor===globalSlot.querySelector('.panel-query'),
    getComputedStyle(globalColumn).transform,!!document.querySelector('[data-sugar-peer-preview]')]`),
    [0,true,'none',false],'an uncached global panel waits for release without staging or shifting the live editor');
  await evaluate('missingPanel.finish(true,true)');
  assert.deepEqual(await evaluate('[missingCommit,globalSlot.firstElementChild.dataset.panelPage]'),[1,'uncached-panel']);
  await evaluate('SugarPageMotion.dispose();globalColumn.remove();document.body.style.removeProperty("background")');
}
async function checkPaneDeck({evaluate, paint, send}) {
  await evaluate(readFileSync(join(assets,'sugar-page-motion.js'),'utf8'));
  await evaluate(readFileSync(join(assets,'sugar-pane-deck.js'),'utf8'));
  await evaluate(`
    document.body.style.margin = '0';
    const h = document.createElement('div'); document.body.append(h);
    const shadow = h.attachShadow({mode:'open'});
    shadow.innerHTML = '<style>[hidden]{display:none!important}.viewport{width:262px;height:204px}.sheet{min-height:500px} [data-pane=a] .sheet{background:#d63c37}[data-pane=b] .sheet{background:#325bc8}[data-pane=c] .sheet{background:#585}</style><div class="viewport"></div>';
    const container = shadow.querySelector('.viewport');
    for (const id of ['a','b','c']) {
      const section = document.createElement('section'); section.dataset.pane=id; section.hidden = id !== 'a';
      section.innerHTML = '<div class="sheet"><input aria-label="value"></div><span class="entry"></span>'; container.append(section);
    }
    const idleStyle=document.createElement('style');
    idleStyle.textContent='@keyframes panel-entry{from{transform:translateY(5px)}}[data-pane]:not([hidden]) > .entry{animation:panel-entry .22s both}';
    shadow.append(idleStyle);
    window.deck=SugarPaneDeck.prepare(container,container.children);
    window.getPane = id => shadow.querySelector('[data-pane='+id+']');
    window.deckPose = id => {const el=getPane(id); const st=getComputedStyle(el); const m=new DOMMatrix(st.transform);
      return [m.m41,m.m42,Number(st.opacity)];};
    window.commits = 0;`);
  assert.deepEqual(await evaluate(`['a','b','c'].map(id=>[getPane(id).getBoundingClientRect().width,
    getPane(id).getBoundingClientRect().height,getComputedStyle(getPane(id)).visibility,getPane(id).inert])`),
    [[262,204,'visible',false],[262,204,'hidden',true],[262,204,'hidden',true]], 'three live panes are prelaid, only one is painted');
  await evaluate("getPane('b').querySelector('input').value='keep'; getPane('a').scrollTop=42; getPane('b').scrollTop=23; window.g=deck.begin('a','b','forward'); g.update(.35)");
  await paint();
  const outward = await evaluate("[deckPose('a'),deckPose('b')]");
  assert.equal(outward[0][0],0);
  assert.equal(outward[1][0],0);
  assert.equal(await evaluate("getComputedStyle(getPane('b')).clipPath"),'inset(0% 0% 0% 65%)');
  assert.deepEqual(outward.map(p=>p[2]),[1,1],'an incoming opaque page covers a stationary origin');
  assert.deepEqual(await evaluate("[deck.scrollElement().dataset.pane,getPane('b').hidden,getPane('b').inert,getComputedStyle(getPane('b')).visibility,commits]"),
    ['a',true,true,'visible',0], 'previewing a target does not navigate or expose its controls');
  // The parked palette uses flex on the real product page. Preparing it as a block would
  // reflow its children when the logical hidden attribute changes at commit.
  assert.equal(await evaluate("getPane('c').style.setProperty('--sugar-pane-display','flex');getComputedStyle(getPane('c')).display"),'flex');
  // Samples from the left/right of the same compositor frame must show different real sections.
  const screenshot = await send('Page.captureScreenshot',{format:'png',clip:{x:0,y:70,width:254,height:3,scale:1}});
  const rgb = await evaluate(`(async()=>{const img=new Image();img.src='data:image/png;base64,${screenshot.data}';await img.decode();
    const ctx=new OffscreenCanvas(img.width,img.height).getContext('2d');ctx.drawImage(img,0,0);
    return [16,222].map(x=>Array.from(ctx.getImageData(x,1,1,1).data).slice(0,3));})()`);
  assert(rgb[0][0]>rgb[0][2] && rgb[1][2]>rgb[1][0],'old and new live content overlap in the same painted frame');
  assert.equal(await evaluate("g.finish(false,()=>++commits)"), false);
  assert.deepEqual(await evaluate("[getPane('a').scrollTop,getPane('b').scrollTop,commits]"),[42,23,0]);
  await evaluate("window.g=deck.begin('a','b','forward');g.update(.35)");
  const applied = await evaluate(`(async()=>{
    const done=g.finish(true,()=>{commits++;deck.activate('b');});
    const duplicate=g.finish(true,()=>++commits);
    return [(done===duplicate),await done,commits];
  })()`);
  assert.deepEqual(applied,[true,true,1]);
  assert.equal(await evaluate("getComputedStyle(getPane('b').querySelector('.entry')).animationName"),'none','finishing a scrub does not replay a five-pixel child entrance');
  await evaluate("deck.activate('b')");
  assert.equal(await evaluate("getComputedStyle(getPane('b').querySelector('.entry')).animationName"),'panel-entry','ordinary navigation retains the entrance');
  await evaluate("window.samples=[];window.g=deck.begin('b','c','forward',p=>samples.push(p));g.update(.4)");
  assert.equal(await evaluate("g.finish(false,()=>{throw Error('cancel navigated');},{duration:96,fractionAt:()=>samples.includes(.2)?1:.5})"),false);
  assert.deepEqual(await evaluate("samples"),[0,.4,.2,0],'settings use the same release progress as the rail');
  await evaluate("window.g=deck.begin('b','a','back');g.update(.65)");
  await paint();
  const inward=await evaluate("[deckPose('a'),deckPose('b')]");
  assert.equal(inward[0][0],0);
  assert.equal(await evaluate("getComputedStyle(getPane('a')).clipPath"),'inset(0% 35% 0% 0%)');
  assert.equal(inward[1][0],0,'the old pane stays still in both directions');
  assert.deepEqual(await evaluate("[getPane('a').dataset.deckLayer,getPane('b').dataset.deckLayer]"),['front','under']);
  await evaluate("g.finish(false)");
  assert.equal(await evaluate("getPane('b').querySelector('input').value"),'keep');
  // Isolate the actual destination content from the visual treatment: the small yellow
  // square can only come from the live blue page. The origin is a solid red sheet.
  await evaluate(`deck.activate('a'); getPane('a').scrollTop=42;
    const blueSheet=getPane('b').querySelector('.sheet'); blueSheet.style.position='relative';
    const pin=document.createElement('i'); pin.style.cssText='position:absolute;left:126px;top:97px;width:10px;height:10px;background:#f6d756';
    blueSheet.append(pin);
    const mainIds=['iris','split','diagonal','focus','slide'].map(n=>'sugar-pane-'+n);
    window.deckTransitions=()=>shadow.getAnimations().filter(a=>mainIds.includes(a.id));
    deck.activate('b','iris');`);
  const frame = await evaluate(`(() => {
    const primary=deckTransitions(), iris=primary[0];
    if(primary.length!==1 || iris.id!=='sugar-pane-iris')throw Error('Expected only the iris page transition');
    const moving=shadow.getAnimations().filter(a=>a.effect?.getKeyframes);
    const rims=moving.filter(a=>a.id==='sugar-iris-rim');
    if(rims.length!==1)throw Error('Expected a single synchronized iris border');
    window.irisRim=rims[0].effect.target;
    if (moving.some(a=>a instanceof CSSAnimation && a.animationName==='panel-entry'))
      throw Error('The pane entry animation is playing on top of the iris');
    moving.forEach(a=>a.pause());
    const match=getComputedStyle(getPane('b')), bounds=getPane('b').getBoundingClientRect();
    for(let p=.01;p<.99;p+=.01){
      for(const a of moving) {
        const t=a.effect.getTiming();
        if(typeof t.duration==='number')a.currentTime=t.delay+p*t.duration;
      }
      const circle=/^circle\\(([\\d.]+)(px|%)/.exec(match.clipPath);
      if(!circle)continue;
      const radius=Number(circle[1])*(circle[2]==='%'?Math.hypot(bounds.width,bounds.height)/Math.SQRT2/100:1);
      if(radius>=48&&radius<=67) return {radius,from:getPane('a').hasAttribute('data-slide-under'),
        to:getPane('b').hasAttribute('data-slide-front'),transform:match.transform};
    }
    throw Error('Iris never exposes a circle centered within this viewport: '+match.clipPath);
  })()`);
  assert(frame.from && frame.to,'the origin stays underneath the live destination throughout the reveal');
  assert.deepEqual(await evaluate("[deckPose('a'),deckPose('b')]"),[[0,0,1],[0,0,1]],
    'a circular reveal does not translate or fade either page');
  await paint();
  const crop=await evaluate(`(()=>{const r=container.getBoundingClientRect();
    return {x:r.x,y:r.y,width:r.width,height:r.height,scale:1};})()`);
  const irisImage=await send('Page.captureScreenshot',{format:'png',clip:crop});
  const crossSection=await evaluate(`(async()=>{
    const img=new Image();img.src='data:image/png;base64,${irisImage.data}';await img.decode();
    const ctx=new OffscreenCanvas(img.width,img.height).getContext('2d');ctx.drawImage(img,0,0);
    const pixels=ctx.getImageData(0,0,img.width,img.height).data;
    const x=Math.floor(img.width/2), y=Math.floor(img.height/2), radius=${frame.radius};
    const color=(cx,cy)=>{const i=4*(Math.round(cy)*img.width+Math.round(cx));return Array.from(pixels.slice(i,i+3)).join(',');};
    const rays=[[1,0],[-1,0],[0,1],[0,-1],[Math.SQRT1_2,Math.SQRT1_2]];
    const samples=rays.map(([dx,dy])=>{
      const ray=r=>color(x+dx*r,y+dy*r);
      const distinct=new Set(['214,60,55','50,91,200']);
      let edgePixels=0;
      for(let r=radius-20;r<=radius+20;r++)if(!distinct.has(ray(r)))edgePixels++;
      return {inside:ray(radius*.45),outside:ray(radius+32),edgePixels};
    });
    return {center:color(x,y),samples,corner:color(8,img.height-8)};
  })()`);
  assert.equal(crossSection.center,'246,215,86','the circle center contains the destination’s own painted detail');
  assert.equal(crossSection.corner,'214,60,55','the distant corner remains the original page');
  for(const {inside,outside,edgePixels} of crossSection.samples) {
    assert.equal(inside,'50,91,200');assert.equal(outside,'214,60,55');
    assert(edgePixels>=2,'a visible ring separates the destination and origin along each radial direction');
  }
  await evaluate("deckTransitions()[0].finish()"); await paint();
  assert.deepEqual(await evaluate(`[deck.scrollElement().dataset.pane,deckTransitions().length,
    container.querySelectorAll('[data-slide-under],[data-slide-front]').length,
    getComputedStyle(getPane('b')).clipPath,irisRim.isConnected]`),['b',0,0,'none',false],'the final viewport is the unobstructed destination and its decorative ring is detached');

  const interrupt=await evaluate(`(() => {
    deck.activate('a','iris');const first=deckTransitions()[0];deck.activate('c','iris');
    const current=deckTransitions(),second=current[0],previous=container.querySelector('[data-slide-under]')?.dataset.pane;
    const gesture=deck.begin('c','a','back');gesture.update(.4);
    const under=getPane('c').dataset.deckLayer,front=getPane('a').dataset.deckLayer;
    gesture.cancel();
    return [first.playState,current.length,second.playState,previous,under,front,deck.scrollElement().dataset.pane,
      container.querySelectorAll('[data-slide-under],[data-slide-front],[data-deck-layer]').length,deckTransitions().length];
  })()`);
  assert.deepEqual(interrupt,['idle',1,'idle','a','under','front','c',0,0],
    'a second tap supersedes the first iris, then a cancelled gesture restores the last selected page');
  const draws=await evaluate(`(() => {
    const items=[];
    for(let i=0;i<10;i++){
      deck.activate(i%2?'c':'b',i%2?'random-down':'random-up');
      items.push(deckTransitions().map(a=>a.id));
    }
    return items;
  })()`);
  const expected=['diagonal','focus','iris','slide','split'].map(n=>'sugar-pane-'+n).sort();
  assert(draws.every(a=>a.length===1),'each tap has exactly one main page transition');
  for(let offset=0;offset<draws.length;offset+=expected.length)
    assert.deepEqual(draws.slice(offset,offset+expected.length).flat().sort(),expected,
      'up/down taps share a shuffle bag and show all five effects before repeating');
  await evaluate("h.setAttribute('data-reduced','');deck.activate('b','iris');deck.activate('a','random-up')");
  assert.deepEqual(await evaluate(`[deck.scrollElement().dataset.pane,deckTransitions().length,
    container.querySelectorAll('[data-slide-under],[data-slide-front]').length,getComputedStyle(getPane('a')).clipPath]`),
    ['a',0,0,'none'],'reduced motion immediately selects both explicit and random destinations');
  await evaluate("h.removeAttribute('data-reduced');deck.activate('b')");

  await evaluate("h.setAttribute('data-reduced','');window.g=deck.begin('b','c','up');g.update(.5)");
  await paint();
  assert.deepEqual(await evaluate("deckPose('c').slice(0,2)"),[0,0],'reduced motion uses opacity only');
  await evaluate("deck.dispose();SugarPaneDeck.dispose();SugarPageMotion.dispose()");
  assert.deepEqual(await evaluate("[container.hasAttribute('data-sugar-pane-deck'),container.querySelectorAll('[data-deck-layer]').length, getPane('a').getAnimations().length, getPane('c').getAnimations().length]"),
    [false,0,0,0],'disposal leaves no moving layers');
}
async function checkForeground({evaluate}) {
  const tones = await evaluate(`(() => {
    SugarEdgeNav.setPage('dsh', 'home');
    const read = (selector, property) => getComputedStyle(el(selector))[property].match(/[0-9.]+/g).map(Number);
    return [['#eeeeee','#eeeeee',100], ['#16191b','#16191b',100],
      ['#16191b','#ececf2',0], ['#eeeeee','#17191c',0]].map(([backdrop,accent,transparency]) => {
      SugarEdgeNav.configure({backdrop,accent,transparency,colorMode:'random',reducedMotion:true});
      SugarEdgeNav.setExpanded(true);
      const clock = read('.clock','color'), icon = read('[data-action=home] svg','stroke');
      const marker = read('[data-action=home] i','backgroundColor'), grip = read('.tab','backgroundColor');
      SugarEdgeNav.setExpanded(false);
      const popup = SugarEdgeNav.getPopupPalette();
      const rootStyle = getComputedStyle(document.documentElement);
      const popupInk = rootStyle.getPropertyValue('--sugar-popup-ink').trim();
      const popupBlur = rootStyle.getPropertyValue('--sugar-popup-blur').trim();
      return {clock,icon,marker,grip,popup,popupInk,popupBlur,idle:read('.tab','backgroundColor'),opacity:getComputedStyle(el('.tab')).opacity};
    });
  })()`);
  const [dark, light] = [tones[0].clock, tones[1].clock];
  assert(dark.every(n=>n<40) && light.every(n=>n>235), 'clock uses dark text on light, light text on dark');
  for (const [i, tone] of tones.entries()) {
    const onRail = [dark, light, dark, light][i], onPage = [dark, light, light, dark][i];
    for (const value of [tone.clock,tone.icon,tone.marker,tone.grip.slice(0,3)]) assert.deepEqual(value,onRail);
    assert.deepEqual(tone.idle,onPage,'collapsed grip uses the exposed page color, including an opposite opaque tint');
    const ink = '#' + onRail.map(n => n.toString(16).padStart(2,'0')).join('');
    assert.equal(tone.popupInk, tone.popup.pageInk, 'Sugar menus use the uncolored page foreground, including with an opposite rail tint');
    assert.equal(tone.popup.ink, ink, 'the rail keeps its own foreground');
    assert.equal(tone.popup.pageSurface, ['#eeeeee','#16191b','#16191b','#eeeeee'][i], 'floating task uses the page before rail tint');
    assert.equal(tone.popup.pageInk, '#' + onPage.map(n => n.toString(16).padStart(2,'0')).join(''), 'task controls remain legible behind an opposite-color rail');
    assert.equal(tone.popupBlur, tone.popup.blurDp + 'px');
    assert(tone.popup.opacity > 0 && tone.popup.opacity < 1, 'popup surface remains translucent at both rail transparency extremes');
    assert(+tone.opacity > .1 && +tone.opacity <= .22);
  }
}
async function checkTaskRail({evaluate, paint}) {
  await evaluate(`SugarEdgeNav.configure({side:'right',reducedMotion:true}); SugarEdgeNav.setExpanded(false);
    window.captureTaskRail = e => window.lastTaskRail = e.detail;
    window.addEventListener('sugar-task-rail', captureTaskRail);
    SugarEdgeNav.setTaskLayerTracking(true);`);
  await paint();
  const folded = await evaluate('lastTaskRail');
  assert.equal(folded.visible.length, 1, 'only the folded grip paints over a task');
  assert(folded.visible[0][4] > 0 && folded.touch[0][2]-folded.touch[0][0] > folded.visible[0][2]-folded.visible[0][0]);
  for (const side of ['right','left']) {
    await evaluate(`SugarEdgeNav.configure({side:'${side}'});SugarEdgeNav.setExpanded(true)`);
    await paint();
    const {visible,touch,width} = await evaluate('lastTaskRail');
    const band = visible.find(rect => rect[4] === 0);
    assert(band && touch.some(rect => rect.every((value,i) => value === band[i])), 'navigation band paints and receives touches');
    assert(side === 'left' ? Math.abs(band[0]) < 1 : Math.abs(band[2]-width) < 1, 'coverage follows the active edge');
  }
  await evaluate('SugarEdgeNav.setTaskLayerTracking(false);window.removeEventListener("sugar-task-rail",captureTaskRail);SugarEdgeNav.setExpanded(false);');
  assert.deepEqual(await evaluate('lastTaskRail.visible'), [], 'closing the task releases the native clip');
}
async function checkGlassPixels({send, evaluate, paint}) {
  // Chromium's actual composited pixels, using a clipped in-memory screenshot with 3px dark/light stripes.
  const clip = {x:351, y:234, width:19, height:66, scale:1};
  async function capture() {
    await paint();
    const {data} = await send('Page.captureScreenshot', {format:'png',clip});
    return evaluate(`(async () => {
      const image = new Image(); image.src = 'data:image/png;base64,${data}'; await image.decode();
      const canvas = new OffscreenCanvas(image.width, image.height); const ctx = canvas.getContext('2d');
      ctx.drawImage(image,0,0); return Array.from(ctx.getImageData(0,0,image.width,image.height).data);
    })()`);
  }
  await evaluate(`(() => {
    const stripes = document.createElement('div'); stripes.id = 'sugar-glass-stripes';
    stripes.style.cssText = 'position:fixed;inset:0;background:repeating-linear-gradient(to bottom,#151515 0px,#151515 3px,#ececec 3px,#ececec 6px)';
    document.body.append(stripes);
    SugarEdgeNav.configure({side:'right',colorMode:'random',accent:'#929292',backdrop:'#909090',reducedMotion:true});
    SugarEdgeNav.setExpanded(true); SugarEdgeNav.setCovered(true);
  })()`);
  const baseline = await capture();
  await evaluate('SugarEdgeNav.setCovered(false)');
  for (const side of ['left','right']) {
    const edge = await evaluate(`(() => {
      SugarEdgeNav.configure({side:'${side}',transparency:0});
      SugarEdgeNav.setExpanded(true);
      const r = el('.band').getBoundingClientRect();
      return Math.round('${side}' === 'left' ? r.right : r.left);
    })()`);
    // The bright stripe at y=5 continues across both sides of the full-height rail.
    const clip = {x:side === 'left' ? edge-1 : edge-11,y:5,width:12,height:1,scale:1};
    async function sampleEdge() {
      await paint();
      const {data} = await send('Page.captureScreenshot',{format:'png',clip});
      const points = await evaluate(`(async()=>{
        const img=new Image();img.src='data:image/png;base64,${data}';await img.decode();
        const ctx=new OffscreenCanvas(img.width,1).getContext('2d');ctx.drawImage(img,0,0);
        return Array.from(ctx.getImageData(0,0,img.width,1).data).filter((_,i)=>i%4===0);
      })()`);
      return side === 'left' ? points : points.reverse();
    }
    const visible = await sampleEdge();
    await evaluate('SugarEdgeNav.configure({transparency:75})');
    const light = await sampleEdge();
    await evaluate('SugarEdgeNav.configure({transparency:100})');
    const clear = await sampleEdge();
    await evaluate('SugarEdgeNav.setExpanded(false)');
    const hidden = await sampleEdge();
    assert(visible[1]+5<visible[4] && visible[4]<visible[10], 'shadow fades toward the page on ' + side);
    assert(visible[1]+6<light[1] && light[1]<hidden[1], 'the shadow lightens with the rail material on ' + side);
    assert.deepEqual(clear,hidden,'the border and shadow reveal the unmodified page at full transparency on ' + side);
    assert(hidden.every(v => v === hidden[0]), 'the collapsed rail leaves no shadow');
  }
  await evaluate("SugarEdgeNav.configure({side:'right'});SugarEdgeNav.setExpanded(true)");
  function contrast(pixels) {
    let hi = 0, lo = 0, nh = 0, nl = 0;
    for (let i = 0; i < pixels.length; i += 4) {
      const luma = (pixels[i] + pixels[i+1] + pixels[i+2]) / 3;
      if (baseline[i] > 128) { hi += luma; nh++; } else { lo += luma; nl++; }
    }
    assert(nl > 0 && nh > 0, 'screenshot contains real background stripes');
    return hi / nh - lo / nl;
  }
  const original = contrast(baseline), visibility = [];
  for (const t of [0,16,50,85,99,100]) {
    await evaluate(`SugarEdgeNav.configure({transparency:${t},blur:true})`);
    const glass = await capture();
    const ratio = contrast(glass) / original;
    visibility.push(Math.round(100 * ratio));
    if (t === 100) assert.deepEqual(glass, baseline, '100% with frost enabled exactly reproduces the background pixels');
    if (t === 16 || t === 50 || t === 85) {
      await evaluate('SugarEdgeNav.configure({blur:false})');
      const plain = await capture();
      assert(contrast(glass) < .14 * contrast(plain), 'frost softens fine detail at ' + t + '%');
      await evaluate("hostNav.setAttribute('data-moving','');SugarEdgeNav.configure({blur:true})");
      const moving = await capture();
      assert(contrast(moving) < .14 * contrast(plain), 'fine detail stays blurred while moving at ' + t + '%');
      await evaluate("hostNav.removeAttribute('data-moving')");
    }
  }
  assert(visibility[0] < 3 && visibility[4] > visibility[3] && visibility[4] < 100 && visibility[5] === 100,
    'fine detail returns near full transparency: ' + visibility.join(', '));
  console.log('Painted stripe contrast at 0/16/50/85/99/100% transparency: ' + visibility.join('/') + '% of original');
  await evaluate("document.querySelector('#sugar-glass-stripes').remove()");
}
async function checkRevealPixels({send,evaluate,paint}) {
  await evaluate(`{
    const stripes=document.createElement('div'); stripes.id='sugar-reveal-test';
    stripes.style.cssText='position:fixed;inset:0;background:repeating-linear-gradient(#303030 0 3px,#ddd 3px 6px)';
    document.body.append(stripes);
    SugarEdgeNav.configure({colorMode:'random',accent:'#4560aa',transparency:45,reducedMotion:false});
    SugarEdgeNav.setExpanded(true);
    el('.track').style.transition='none';
  }`);
  async function strip(x) {
    await paint();
    const {data}=await send('Page.captureScreenshot',{format:'png',clip:{x,y:120,width:1,height:24,scale:1}});
    return evaluate(`(async()=>{
      const i=new Image();i.src='data:image/png;base64,${data}';await i.decode();
      const c=new OffscreenCanvas(1,24).getContext('2d');c.drawImage(i,0,0);
      const px=c.getImageData(0,0,1,24).data;
      const r=Array.from(px).filter((_,i)=>i%4===0).slice(6,-6); // Ignore the clipped bitmap's blur boundary.
      return {contrast:Math.max(...r)-Math.min(...r),tint:px[2]-px[0]};
    })()`);
  }
  for (const side of ['left','right']) {
    const x = await evaluate(`(()=>{
      SugarEdgeNav.configure({side:'${side}',blur:true,transparency:45});
      const t=el('.track'), d=el('.dock').getBoundingClientRect();
      t.style.transform='translateX('+(innerWidth/2-d.width/2-d.left)+'px)';
      SugarEdgeNav.setReveal('${side}',.5);
      const r=t.getBoundingClientRect();
      return Math.round('${side}'==='right'?r.right:r.left);
    })()`);
    const sign=side==='right'?1:-1;
    const near=await strip(x+sign*5), far=await strip(x+sign*75);
    assert(far.contrast>=166 && Math.abs(far.tint)<2,'new page stays sharp beyond the gradient on '+side);
    assert(near.tint>15 && near.contrast>far.contrast*.40,'the tinted seam preserves underlying detail on '+side);
    await evaluate('SugarEdgeNav.configure({blur:false})');
    const sharp=await strip(x+sign*5);
    assert(near.contrast+3<sharp.contrast,'real page detail is subtly blurred at the seam on '+side);
    await evaluate('SugarEdgeNav.configure({transparency:100,blur:true})');
    assert.deepEqual(await strip(x+sign*5),far,'transparent moving gradient preserves page pixels on '+side);
    await evaluate("SugarEdgeNav.setReveal(null,0)");
    assert.equal(await evaluate("el('.track').hasAttribute('data-reveal')"),false);
  }
  await evaluate(`SugarEdgeNav.configure({reducedMotion:true});SugarEdgeNav.setReveal('right',.5);
    el('.track').style.removeProperty('transform');el('.track').style.removeProperty('transition');
    document.getElementById('sugar-reveal-test').remove();`);
  assert.equal(await evaluate("el('.track').hasAttribute('data-reveal')"),false,'reduced motion omits the transient band');
}
async function checkClock({evaluate,screen}) {
  await evaluate(`SugarEdgeNav.setPage('settings','overview');SugarEdgeNav.setExpanded(true);
    document.documentElement.style.setProperty('--sugar-safe-top','34px');
    document.documentElement.style.setProperty('--sugar-safe-bottom','21px');`);
  for (const [width,height,minimum,maximum] of [
    [390,844,35,39],[424,920,37,39],[320,568,23,26],[850,620,23,26],[850,395,0,0],[320,450,0,0]
  ]) {
    await screen(width,height);
    for (const side of ['left','right']) {
      const clock = await evaluate(`(() => {
        SugarEdgeNav.configure({side:'${side}',reducedMotion:true});
        const time=el('.clock'),css=getComputedStyle(time);
        if(css.display==='none')return {size:0};
        const r=document.createRange();r.selectNodeContents(time);
        const glyphs=r.getBoundingClientRect(),band=el('.band').getBoundingClientRect();
        return {size:parseFloat(css.fontSize),above:glyphs.top-el('.cap').getBoundingClientRect().bottom,
          grip:el('.tab').getBoundingClientRect().top-glyphs.bottom,actions:el('.actions').getBoundingClientRect().top-glyphs.bottom,
          inside:glyphs.left>=band.left && glyphs.right<=band.right};
      })()`);
      assert(clock.size>=minimum && clock.size<=maximum,`clock size on ${width}×${height}: ${clock.size}`);
      if(clock.size)assert(clock.above>=4 && clock.grip>=5 && clock.actions>=5 && clock.inside,
        'time glyphs stay clear of the seal, handle and buttons on '+side+': '+JSON.stringify(clock));
    }
  }
}
async function checkLandingPath({evaluate,send,center,paint}) {
  await evaluate(readFileSync(join(assets,'sugar-page-motion.js'),'utf8'));
  await evaluate(`window.readRailX=()=>{const track=el('.track').getBoundingClientRect();return [track.left,el('.tab').getBoundingClientRect().left,track.right];};
    window.takeRelease=e=>{if(e.detail.release)window.lastRailRelease=e.detail.release;};
    addEventListener('sugar-edge-scrub',takeRelease);
    window.seekRailRelease=()=>{
      const first=readRailX(), flights=[el('.track'),el('.grip')].flatMap(n=>n.getAnimations())
        .filter(a=>a.id==='sugar-rail-land'), release=window.lastRailRelease;
      const competing=el('.track').getAnimations().some(a=>a.transitionProperty==='transform');
      const at=f=>{for(const a of flights){a.pause();a.currentTime=f*a.effect.getTiming().duration;}return readRailX();};
      const synced=flights.every(a=>a.startTime===release?.startTime && a.effect.getTiming().duration===release?.duration);
      const path=[0,.2,.5,.8,.999].map(at);
      for(const a of flights)a.finish();
      window.railTrace={first,path,competing,synced,count:flights.length};
    };`);
  let now=Date.now()/1000;
  for (const [side,kind] of [['left','near'],['right','near'],['left','flick'],['right','reverse']]) {
    await evaluate(`document.documentElement.style.setProperty('--sugar-safe-left','24px');
      document.documentElement.style.setProperty('--sugar-safe-right','4px');SugarEdgeNav.configure({side:'${side}',reducedMotion:true});SugarEdgeNav.setPage('dsh');
      SugarEdgeNav.setNavigation({canBack:true,canForward:true});SugarEdgeNav.setExpanded(true);
      SugarEdgeNav.configure({reducedMotion:false});window.lastRailRelease=null;
      window.railTrace=null;logNav.length=0;`);
    await paint();
    const [x,y]=await center('.tab'),sign=side==='left'?1:-1;
    const d=await evaluate("Math.abs(el('.dock').getBoundingClientRect().left-el('.other-dock').getBoundingClientRect().left)");
    const steps=kind==='near'?[[25,12],[d*.52,160],[d*.82,340],[d-10,500]] :
      kind==='flick'?[[20,12],[48,29],[80,45]]:[[d*.34,100],[d*.68,190],[d*.32,340]];
    now+=1;
    const mouse=(type,distance,ms)=>send('Input.dispatchMouseEvent',{type,x:x+sign*distance,y,button:'left',
      buttons:type==='mouseReleased'?0:1,clickCount:1,timestamp:now+ms/1000});
    await mouse('mousePressed',0,0);
    const positions=[];
    for(const [distance,time] of steps) {
      await mouse('mouseMoved',distance,time);await paint();positions.push(await evaluate('readRailX()'));
    }
    const start=positions.at(-1);
    await evaluate("window.addEventListener('pointerup',seekRailRelease,{once:true})");
    const [distance,time]=steps.at(-1);
    await mouse('mouseReleased',distance-(kind==='flick'?8:0),time+8);
    await paint();
    const trace=await evaluate('railTrace'),end=await evaluate('readRailX()'),toward=kind==='reverse'?-sign:sign;
    assert(trace.count>0 && !trace.competing,'exactly one landing mechanism owns the track');
    assert(trace.synced,'rail flights use the release timestamp and duration published to the page');
    for(const axis of [0,1,2]) {
      assert(Math.abs(start[axis]-trace.path[0][axis])<1.1,'no displacement between the last painted drag and first landing frame');
      const coords=[...trace.path.map(p=>p[axis]),end[axis]];
      for(let i=1;i<coords.length;i++)assert((coords[i]-coords[i-1])*toward>=-.5,
        side+' '+kind+' rail/grip path reverses: '+coords);
      assert(Math.abs(coords.at(-2)-end[axis])<1,'no residual CSS animation after docking');
      if(kind==='near') for(let i=1;i<positions.length;i++) assert((positions[i][axis]-positions[i-1][axis])*sign>0);
    }
    assert.deepEqual(await evaluate('logNav.slice()'),kind==='reverse'?[]:[side==='left'?'back':'forward']);
  }
  // Pause the compositor between frames and catch the moving handle for a second drag.
  await evaluate(`SugarEdgeNav.configure({side:'left',reducedMotion:true});SugarEdgeNav.setExpanded(true);
    SugarEdgeNav.configure({reducedMotion:false});
    window.pauseLanding=()=>{for(const a of hostNav.shadowRoot.getAnimations().filter(a=>a.id==='sugar-rail-land')){
      a.pause();a.currentTime=a.effect.getTiming().duration*.36;
    }};`);
  await paint();
  const [x,y]=await center('.tab');
  const input=(type,cx,ms)=>send('Input.dispatchMouseEvent',{type,x:cx,y,button:'left',
    buttons:type==='mouseReleased'?0:1,clickCount:1,timestamp:now+.7+ms/1000});
  await input('mousePressed',x,0);await input('mouseMoved',x+28,12);await paint();
  await input('mouseMoved',x+80,36);await paint();
  await evaluate("addEventListener('pointerup',pauseLanding,{once:true})");
  await input('mouseReleased',x+80,42);
  const frozen=await evaluate('readRailX()'), gripCenter=frozen[1]+4.25;
  await input('mousePressed',gripCenter,80);
  await input('mouseMoved',gripCenter-9,110);await paint();
  const resumed=await evaluate('readRailX()');
  for(const i of [0,1])assert(Math.abs(resumed[i]-(frozen[i]-9))<3,
    're-grabbing a landing rail moves nine pixels from its currently painted geometry: '+[frozen[i],resumed[i]]);
  await input('mouseReleased',gripCenter-9,160);await paint();
  await evaluate("delete window.pauseLanding;document.documentElement.style.removeProperty('--sugar-safe-left');document.documentElement.style.removeProperty('--sugar-safe-right')");
  await evaluate(`removeEventListener('sugar-edge-scrub',takeRelease);SugarPageMotion.dispose();
    delete window.readRailX;delete window.takeRelease;delete window.seekRailRelease;delete window.railTrace;delete window.lastRailRelease;`);
}
async function checkPageActions({evaluate,screen,center,tap,paint}) {
  await evaluate(`window.qaActions=[
    ['paper','亮白','#F7F6F2'],['charcoal','深灰','#25272C'],['sky','浅蓝','#BEDBEA'],
    ['ocean','深海','#193C51'],['berry','莓紫','#773E69'],['sand','暖沙','#DAB77D']
  ].map(([id,label,color])=>({id:'tone-'+id,label,color}));
  window.qaSelect=({detail})=>{if(qaActions.some(action=>action.id===detail))SugarEdgeNav.setPage('arbitrary-gallery',detail,qaActions);};
  addEventListener('sugar-edge-action',qaSelect);
  SugarEdgeNav.configure({side:'right',reducedMotion:true}); SugarEdgeNav.setNavigation({canBack:true,canForward:false});
  SugarEdgeNav.setExpanded(true);`);
  try {
    for (const [width,height] of [[390,844],[844,390],[600,184]]) {
      await screen(width,height);await paint();
      await evaluate("SugarEdgeNav.setPage('other-tool','choose',[{id:'choose',label:'选择',icon:'appearance'}])");
      const fixed = () => Promise.all(['home','back'].map(n=>center('[data-action='+n+']')));
      const anchors = await fixed();
      assert.equal(await evaluate("el('[data-action=choose] svg path').getAttribute('d')"),await evaluate(
        "(()=>{SugarEdgeNav.setPage('settings','appearance');return el('[data-action=appearance] svg path').getAttribute('d')})()"),
        'a previously unknown action can use a built-in icon');
      await evaluate("SugarEdgeNav.setPage('arbitrary-gallery','tone-paper',qaActions)");
      assert.deepEqual(await evaluate("Array.from(hostNav.shadowRoot.querySelectorAll('.item:not([hidden])'),n=>n.dataset.action)"),
        ['tone-paper','tone-charcoal','tone-sky','tone-ocean','tone-berry','tone-sand','home','back']);
      for(const id of ['paper','charcoal','sky','ocean','berry','sand']) {
        const selector='[data-action=tone-'+id+']';
        await evaluate("el("+JSON.stringify(selector)+").scrollIntoView({block:'nearest'})");await paint();
        const [x,y]=await center(selector);
        assert.equal(await evaluate(`hostNav.shadowRoot.elementFromPoint(${x},${y}).closest('button').dataset.action`),'tone-'+id);
        await tap(x,y);
        const detail=await evaluate(`(()=>{
          const active=el('.item[aria-current=page]'),data=qaActions.find(a=>a.id===active?.dataset.action);
          const color=data?.color.slice(1).match(/../g).map(n=>parseInt(n,16)).join(', ');
          return [active?.dataset.action,active?.textContent.trim(),active?.title===data?.label,
            active?.getAttribute('aria-label')===data?.label,getComputedStyle(active.querySelector('circle')).fill==='rgb('+color+')',
            getComputedStyle(active.querySelector('i')).visibility];
        })()`);
        assert.deepEqual(detail,['tone-'+id,'',true,true,true,'visible']);
        assert.deepEqual(await fixed(),anchors,'Home and Back remain anchored while scrolling the contextual list');
      }
    }
    await evaluate(`SugarEdgeNav.setPage('inherited-tools','tone-sand',qaActions);
      window.qaSame=el('[data-action=tone-paper]');SugarEdgeNav.setPage('child-tools','tone-paper',qaActions.map(a=>({...a})))`);
    assert.equal(await evaluate("qaSame===el('[data-action=tone-paper]')"),true,'equivalent inherited lists reuse the visible controls');
    await evaluate("SugarEdgeNav.setPage('plain-tool',null,[])");
    assert.deepEqual(await evaluate("Array.from(hostNav.shadowRoot.querySelectorAll('.item:not([hidden])'),n=>n.dataset.action)"),['home','back']);
  } finally {
    await evaluate("removeEventListener('sugar-edge-action',qaSelect);delete window.qaSelect;delete window.qaSame;delete window.qaActions;SugarEdgeNav.setPage('dsh')");
    await screen(390,844);
  }
}
async function checkMarkerAndLift({evaluate,send,center,paint}) {
  // Seek on the compositor timeline so a late test frame cannot skip the interruption.
  await evaluate("SugarEdgeNav.configure({side:'left',reducedMotion:true});SugarEdgeNav.setPage('settings','overview');SugarEdgeNav.setExpanded(true)");
  await paint();
  const mark=await evaluate(`(async()=>{
    const slides=()=>hostNav.shadowRoot.getAnimations().filter(a=>a.id==='sugar-active-marker');
    const y=node=>node.getBoundingClientRect().top, moving=el('.moving-mark'), active=()=>el('.item[aria-current=page] i');
    const from=y(active());SugarEdgeNav.configure({reducedMotion:false});SugarEdgeNav.setPage('settings','gestures');
    const first=slides()[0];first.pause();first.currentTime=0;
    const start=y(moving),target=y(active()),duration=first.effect.getTiming().duration;
    first.currentTime=duration/3;
    const interrupted=y(moving);
    SugarEdgeNav.setPage('dsh','home');
    const second=slides()[0],retargeted=slides().length===1 && second!==first && first.playState==='idle';
    second.pause();second.currentTime=0;
    const resumed=y(moving), home=y(active());
    second.currentTime=duration-1;const almost=y(moving);
    const finished=new Promise(done=>second.addEventListener('finish',done,{once:true}));second.finish();await finished;
    const landed=slides().length===0 && !el('.band').hasAttribute('data-marker-sliding') &&
      el('.item[aria-current=page]').dataset.action==='home' && getComputedStyle(active()).visibility==='visible';
    SugarEdgeNav.setPage('settings','overview');
    const running=slides().length;
    SugarEdgeNav.configure({reducedMotion:true});
    SugarEdgeNav.setPage('settings','appearance');
    return {duration,from,start,interrupted,target,resumed,almost,home,retargeted,landed,running,
      reduced:slides().length===0 && !el('.band').hasAttribute('data-marker-sliding') &&
        el('.item[aria-current=page]').dataset.action==='appearance'};
  })()`);
  assert.equal(mark.duration,90);
  assert(Math.abs(mark.from-mark.start)<1 && (mark.interrupted-mark.start)*(mark.target-mark.interrupted)>0);
  assert(mark.retargeted && Math.abs(mark.resumed-mark.interrupted)<1,'rapid reselection continues from the painted marker');
  assert(mark.landed && Math.abs(mark.almost-mark.home)<1,'the final button owns the visible indicator');
  assert(mark.running===1 && mark.reduced,'enabling reduced motion cancels the flight and subsequent markers snap');
  await send('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
  try {
    assert(await evaluate(`(() => {
      SugarEdgeNav.configure({reducedMotion:false});SugarEdgeNav.setPage('settings','gestures');
      return el('.item[aria-current=page]').dataset.action==='gestures' &&
        !hostNav.shadowRoot.getAnimations().some(a=>a.id==='sugar-active-marker');
    })()`),'system reduced motion also suppresses the marker slide');
  } finally {await send('Emulation.setEmulatedMedia',{features:[]});}

  // Chromium accepts a distinct release coordinate for mouse pointers. Exercise the real
  // captured pointer handlers with the overshooting final move followed by the corrected up.
  let base=Date.now()/1000;
  async function release(side,correction) {
    await evaluate(`SugarEdgeNav.configure({side:'${side}',reducedMotion:true});SugarEdgeNav.setPage('dsh','home');
      SugarEdgeNav.setNavigation({canBack:true,canForward:true,rightPanelOpen:false});SugarEdgeNav.setExpanded(true);
      logNav.length=0;`);
    await paint();
    const [x,y]=await center('.tab'),sign=side==='left'?1:-1;
    base+=.3;
    const mouse=(type,dist,ms)=>send('Input.dispatchMouseEvent',{type,x:x+sign*dist,y,button:'left',
      buttons:type==='mouseReleased'?0:1,clickCount:1,timestamp:base+ms/1000});
    await mouse('mousePressed',0,0);await mouse('mouseMoved',24,13);await mouse('mouseMoved',52,27);
    await mouse('mouseMoved',84,42);await mouse('mouseReleased',84-correction,49);
    await paint();return evaluate('[hostNav.dataset.side,logNav.slice()]');
  }
  assert.deepEqual(await release('left',8),['right',['back']],'8px release correction commits once and stays on the far edge');
  assert.deepEqual(await release('right',8),['left',['forward']],'right-hand lift correction is symmetric');
  assert.deepEqual(await release('left',28),['left',[]],'a deliberate turn-back leaves the rail on its original edge');
}
async function checkHiddenPull({evaluate,touch,center,paint,screen}) {
  let clock = Date.now()/1000;
  for (const [w,h,side,reduced,grip] of [[390,844,'right',true,true],[844,390,'left',false,false]]) {
    await screen(w,h);
    const sign=side==='left'?1:-1, direction='back';
    async function start(history=true) {
      await evaluate(`SugarEdgeNav.setPage('dsh');SugarEdgeNav.configure({side:'${side}',reducedMotion:${reduced}});
        SugarEdgeNav.setExpanded(false);SugarEdgeNav.setNavigation({canBack:${history},canForward:true,rightPanelOpen:${side==='right'}});
        logNav.length=0;scrubs.length=0;`);
      await paint();
      const [x,y]=grip?await center('.tab'):[7,60];clock+=1;
      await touch('touchStart',x,y,clock);
      return async(distance,elapsed)=>{await touch('touchMove',x+sign*distance,y,clock+elapsed/1000);await paint();};
    }
    async function release(at) {
      await touch('touchEnd',undefined,undefined,clock+at/1000);await paint();
      return evaluate('[SugarEdgeNav.isExpanded(),hostNav.dataset.side,logNav.slice(),scrubs.map(s=>s.phase)]');
    }
    for (const history of [true,false]) {
      const move=await start(history);await move(32,16);await move(w*.62,55);
      assert.deepEqual(await release(290),[true,side,[],[]],'fast hidden-edge sweep only reveals, even when held after the sweep');
    }
    const move=await start();await move(28,45);await move(w*.31,230);
    const first=await evaluate('scrubs.at(-1)');assert.equal(first.direction,direction);assert.equal(first.side,side);
    await move(w*.42,330);
    assert((await evaluate('scrubs.at(-1).progress'))>first.progress,'same-finger slow pull continuously exposes the destination');
    await move(w*.59,430);
    const [open,docked,actions,phases]=await release(450);
    assert(open);assert.notEqual(docked,side);assert.deepEqual(actions,[direction]);assert.equal(phases.at(-1),'commit');
    const reverse=await start();await reverse(25,80);await reverse(w*.38,245);await reverse(10,355);
    const [,backSide,backActions,backPhases]=await release(375);
    assert.equal(backSide,side);assert.deepEqual(backActions,[]);assert.equal(backPhases.at(-1),'cancel');
    const missing=await start(false);await missing(28,65);await missing(w*.65,295);
    assert.deepEqual(await release(315),[true,side,[],[]],'slow pull without history reveals this side');
  }
  await screen(390,844);
}
async function checkFlicks({evaluate, touch, center, paint}) {
  // CDP timestamps keep velocity and the pause before release independent of test-runner speed.
  const fast = [[14,18],[27,42],[42,76]];
  let begin = Date.now()/1000;
  async function drag(side, samples, lift, {open=true, opposite=false, inspector=false}={}) {
    await evaluate(`SugarEdgeNav.setPage('dsh');SugarEdgeNav.configure({side:'${side}',reducedMotion:true});
      SugarEdgeNav.setExpanded(${open});SugarEdgeNav.setNavigation({canBack:true,canForward:true,rightPanelOpen:${inspector}});
      logNav.length=0;`);
    await paint();
    const [x,y] = opposite ? [360,235] : await center('.tab');
    const sign = side==='left' && !opposite ? 1 : -1;
    begin += .6;
    await touch('touchStart',x,y,begin);
    for (const [ms, distance] of samples) await touch('touchMove',x+sign*distance,y,begin+ms/1000);
    await touch('touchEnd',undefined,undefined,begin+lift/1000);
    await paint();
    return evaluate('logNav.slice()');
  }
  assert.deepEqual(await drag('left', fast,47),['back']);
  assert.deepEqual(await drag('right',fast,47),['forward']);
  assert.deepEqual(await drag('right',fast,47,{inspector:true}),['back'],'right inspector keeps its back action for a flick');
  assert.deepEqual(await drag('left',[[100,20],[230,50],[350,76]],359),[],'slow short drag');
  assert.deepEqual(await drag('left',fast,225),[],'holding at the last point cancels the flick');
  assert.deepEqual(await drag('left',[...fast,[54,48]],60),[],'returning toward the starting edge cancels the flick');
  assert.deepEqual(await drag('left',fast,47,{open:false}),[],'a short pull on the closed grip only reveals the rail');
  assert.equal(await evaluate('SugarEdgeNav.isExpanded()'),true);
  assert.deepEqual(await drag('left',fast,47,{opposite:true}),[],'a short swipe in page content cannot trigger a flick');
}
async function checkEmbeddedPreview({evaluate, tap, swipeAt, paint}) {
  const frameHtml = `<style>body{margin:0;height:200vh}button{width:100%;height:70px}</style>
    <button onclick="parent.postMessage('tap','*')">Open</button>
    <script>
      addEventListener('touchstart',()=>parent.postMessage('touch','*'),{passive:true});
      addEventListener('scroll',()=>parent.postMessage('scroll','*'));
      parent.postMessage('ready','*');
    <\/script>`;
  await evaluate(`new Promise(resolve=>{
    SugarEdgeNav.configure({side:'right'}); SugarEdgeNav.setExpanded(false); SugarEdgeNav.setPage('dsh');
    SugarEdgeNav.setNavigation({canBack:true,canForward:true});
    const panel=document.createElement('div'); panel.id='qa-frame-panel';
    panel.dataset.sidebarRightPanel='fullscreen';panel.dataset.sidebarRightOpen='true';
    panel.style.cssText='position:fixed;inset:100px 0 50px;z-index:2';
    const slot=document.createElement('div');slot.dataset.slot='sidebar.right.tab.document';panel.append(slot);
    const iframe=document.createElement('iframe');iframe.style.cssText='position:absolute;inset:0;width:100%;height:100%;border:0';
    iframe.sandbox='allow-scripts';iframe.srcdoc=${JSON.stringify(frameHtml)};slot.append(iframe);
    const events=[];const listen=e=>{if(e.source===iframe.contentWindow){events.push(e.data);if(e.data==='ready')resolve();}};
    window.addEventListener('message',listen);
    document.body.append(panel);
    window.qaFrame={panel,slot,iframe,events,dispose:()=>{removeEventListener('message',listen);panel.remove();}};
  })`);
  assert.equal(await evaluate('qaFrame.iframe.contentDocument'),null,'preview keeps its opaque sandbox');
  assert.deepEqual(await evaluate(`[[30,120],[360,430],[30,780],[30,70],[190,430],[30,810]].map(([x,y])=>SugarEdgeNav.isEmbeddedEdge(x,y))`),
    [true,true,true,false,false,false],'only the visible iframe intersecting the two edges qualifies');
  const received = type => evaluate(`new Promise(resolve=>{const until=performance.now()+1000;
    const check=()=>qaFrame.events.includes('${type}')?resolve(true):performance.now()>until?resolve(false):requestAnimationFrame(check);check();})`);
  await paint();
  await tap(30,128);
  assert(await received('tap'),'the preview receives a real tap within the edge: '+JSON.stringify(await evaluate('qaFrame.events')));
  await swipeAt(30,600,0,-80);
  assert(await received('scroll'),'a vertical drag scrolls the real opaque preview');
  assert.deepEqual(await evaluate(`(() => {
    const cover=document.createElement('button');cover.style.cssText='position:fixed;top:100px;left:0;width:42px;height:60px;z-index:7';
    document.body.append(cover);const covered=SugarEdgeNav.isEmbeddedEdge(30,120);cover.remove();
    qaFrame.slot.dataset.slot='sidebar.right.tab.terminal';const other=SugarEdgeNav.isEmbeddedEdge(30,120);
    qaFrame.slot.dataset.slot='sidebar.right.tab.document';qaFrame.panel.dataset.sidebarRightOpen='false';const closed=SugarEdgeNav.isEmbeddedEdge(30,120);
    qaFrame.panel.dataset.sidebarRightOpen='true';SugarEdgeNav.setPage('settings');const settings=SugarEdgeNav.isEmbeddedEdge(30,120);
    SugarEdgeNav.setPage('dsh');return [covered,other,closed,settings];
  })()`),[false,false,false,false],'overlays, other panels and settings retain input');
  assert.equal(await evaluate("SugarEdgeNav.embeddedDrag('start',32,244,30,230,1)"),false);
  for (const [phase, x, y, startX, endX, direction] of [
    ['end',30,120,30,250,'back'],['cancel',360,460,360,65,'forward'],['end',30,780,30,265,'back']]) {
    await evaluate(`SugarEdgeNav.setExpanded(false);SugarEdgeNav.configure({side:'${direction==='back'?'right':'left'}'});order.length=0`);
    const event = (type, current, id = 3) => evaluate(`SugarEdgeNav.embeddedDrag('${type}',${current},${y},${startX},${y},${id},${type==='start'?30:230})`);
    assert(await event('start',x+(direction==='back'?9:-9)));
    assert.equal(await event('cancel',x,99),false,'a delayed cancel cannot terminate another native sequence');
    await event('move',endX);await paint();
    assert.equal(await evaluate("order.at(-1)"),'move');
    await evaluate("qaFrame.panel.remove()");
    assert(await event(phase,endX),'route replacement preserves the native sequence');
    assert.equal(await event(phase,endX),false,'end is consumed once');
    assert.deepEqual(await evaluate("order.filter(e=>e!=='move')"),phase==='end'?['commit',direction]:['cancel']);
    await evaluate("document.body.append(qaFrame.panel)");
  }
  await evaluate("SugarEdgeNav.configure({side:'left'});SugarEdgeNav.setExpanded(false);order.length=0");
  const embedded = (type,x,elapsed) => evaluate("SugarEdgeNav.embeddedDrag('"+type+"',"+x+",120,30,120,17,"+elapsed+")");
  assert(await embedded('start',41,16));
  assert(await embedded('move',265,58));
  await paint();
  assert(await embedded('end',265,265));
  assert.deepEqual(await evaluate('[hostNav.dataset.side,SugarEdgeNav.isExpanded(),order.slice()]'),['left',true,[]],
    'the native iframe bridge uses physical down-to-move time for a fast reveal');
  await evaluate("qaFrame.dispose()");
}
async function checkFullscreenPanelInsets({evaluate, screen}) {
  const chrome = readFileSync(join(assets, '../kotlin/app/dsh/sugar/WorkspaceChrome.kt'), 'utf8');
  const sheet = chrome.match(/sheet\.textContent = `([\s\S]*?)`;/)[1];
  // DSH AppFrame has a second grid row for shell.bottom. Its right column anchors the dock in row one.
  const fixture = `
    <style>body{margin:0}.pI_x6G_frame{height:100vh;position:relative;box-sizing:border-box;display:grid;
      grid-template: minmax(0,1fr) auto / 0 minmax(0,1fr) 0}
    .pI_x6G_centerCol{grid-area:1/2}[data-rightbar-col]{grid-area:1/3;position:relative}
    [data-shell-bottom]{grid-column:1/-1;height:24px;background:red}
    [data-sidebar-right-panel]{position:absolute;inset:0 0 0 auto;display:flex;width:100vw;pointer-events:none}
    [data-dockkit-host]{flex:1;display:flex;z-index:40}
    [data-sidebar-right-panel]:not([data-sidebar-right-open]) [data-dockkit-host]{visibility:hidden}
    [data-dockkit-pane]{pointer-events:auto;display:flex;flex:1;background:white}
    [data-slot]{flex:1;min-height:0}iframe{display:block;height:100%;width:100%;border:0}</style>
    <main class="pI_x6G_frame"><div class="pI_x6G_centerCol"></div>
    <div data-rightbar-col><aside data-sidebar-right-panel="fullscreen" data-sidebar-right-open="true">
    <div data-dockkit-host="dock"><section data-dockkit-pane><div data-slot="sidebar.right.tab.document">
    <iframe title="index.html"></iframe></div></section></div></aside></div>
    <footer data-shell-bottom>chat stats</footer></main>`;
  await evaluate(`document.body.innerHTML = ${JSON.stringify(fixture + '<style>' + sheet + '</style>')}`);
  for (const [width, height, left, right, bottom] of [[423,931,0,0,16],[931,423,8,32,12],[423,466,0,0,0]]) {
    await screen(width, height);
    const result = await evaluate(`(() => {
      const root = document.documentElement, panel = document.querySelector('aside');
      const slot = document.querySelector('[data-slot]'), frame = slot.firstElementChild, footer = document.querySelector('footer');
      for (const [name, value] of Object.entries({top:24,right:${right},bottom:${bottom},left:${left}}))
        root.style.setProperty('--sugar-safe-' + name, value + 'px');
      root.toggleAttribute('data-sugar-landscape', innerWidth > innerHeight);
      const hit = () => document.elementFromPoint(innerWidth / 2, innerHeight - 1);
      const rect = () => frame.getBoundingClientRect();
      const preview = [rect().left, rect().right, rect().bottom, hit() === frame];
      slot.setAttribute('data-slot','sidebar.right.tab.terminal');
      const controls = [rect().bottom, Boolean(hit().closest('[data-dockkit-pane]'))];
      slot.setAttribute('data-slot','sidebar.right.tab.document');
      panel.removeAttribute('data-sidebar-right-open');
      const closed = hit() === footer;
      panel.setAttribute('data-sidebar-right-open','true');
      panel.setAttribute('data-sidebar-right-panel','push');
      const push = panel.getBoundingClientRect().bottom === footer.getBoundingClientRect().top;
      panel.setAttribute('data-sidebar-right-panel','fullscreen');
      return {preview, controls, closed, push};
    })()`);
    assert.deepEqual(result, {preview:[left,width - right,height,true], controls:[height - bottom,true], closed:true, push:true},
      'fullscreen document and terminal edges, closed hit testing, split pane bounds at ' + width + '×' + height);
  }
  await evaluate("document.body.replaceChildren(); document.documentElement.removeAttribute('data-sugar-landscape');" +
    "for(const edge of ['top','right','bottom','left'])document.documentElement.style.removeProperty('--sugar-safe-'+edge)");
  await screen(390,844);
}

async function run() {
  const { targetId } = await call('Target.createTarget', {url: 'about:blank'});
  const { sessionId } = await call('Target.attachToTarget', {targetId, flatten: true});
  const send = (m, p) => call(m, p, sessionId);
  const evaluate = async expression => {
    const result = await send('Runtime.evaluate', {expression, returnByValue: true, awaitPromise: true});
    if (result.exceptionDetails) throw Error(result.exceptionDetails.exception?.description || 'JS exception');
    return result.result.value;
  };
  const screen = (width, height) => send('Emulation.setDeviceMetricsOverride',
    {width, height, deviceScaleFactor: 1, mobile: false});
  const center = selector => evaluate(`(() => {const r = el('${selector}').getBoundingClientRect(); return [r.x+r.width/2,r.y+r.height/2];})()`);
  const mouse = (type, x, y) => send('Input.dispatchMouseEvent', {type, x, y,
    button: type === 'mouseMoved' ? 'none' : 'left', buttons: type === 'mouseReleased' ? 0 : 1, clickCount: 1});
  const swipe = async (selector, delta) => {
    const [x, y] = await center(selector);
    await mouse('mousePressed', x, y);
    await mouse('mouseMoved', x + delta / 2, y);
    await mouse('mouseMoved', x + delta, y);
    await mouse('mouseReleased', x + delta, y);
  };
  const touch = (type, x, y, timestamp) => send('Input.dispatchTouchEvent',
    {type, timestamp, touchPoints: x === undefined ? [] : [{x, y, radiusX: 2, radiusY: 2}]});
  const touchSwipe = async (selector, delta) => {
    const [x, y] = await center(selector);
    const end = Date.now()/1000;
    await touch('touchStart', x, y, end-.36);
    await touch('touchMove', x + delta / 2, y, end-.19);
    await touch('touchMove', x + delta, y, end-.03);
    await touch('touchEnd',undefined,undefined,end);
  };
  const tap = async (x, y) => {
    await touch('touchStart', x, y);
    await touch('touchEnd');
  };
  const swipeAt = async (x, y, dx, dy = 0) => {
    await touch('touchStart', x, y, Date.now()/1000-.24);
    await touch('touchMove', x + dx / 2, y + dy / 2);
    await touch('touchMove', x + dx, y + dy);
    await touch('touchEnd');
  };
  const paint = () => evaluate("new Promise(done => requestAnimationFrame(() => requestAnimationFrame(done)))");
  await screen(390, 844);
  await send('Emulation.setTouchEmulationEnabled', {enabled: true});
  if (exitMode) return require('./check-splash-exit.cjs')({assets,send,evaluate,paint});
  if (previewMode) return require('./check-splash-preview.cjs')({assets,send,evaluate,touch,paint});
  if (languageMode) return require('./check-language.cjs')({assets,send,evaluate,paint});
  if (recoveryMode) return require('./check-recovery-rail.cjs')({assets,evaluate,touch,tap,center,touchSwipe,paint});
  if (recoveryManagerMode) return require('./check-recovery-management.cjs')({assets,evaluate,send,tap,center,touchSwipe,paint});
  await evaluate(readFileSync(join(assets,'sugar-i18n.js'),'utf8'));
  if (historyMode) return checkShellHistory({evaluate,touch,tap,swipeAt,paint,screen,send});
  if (deckMode) {
    await checkGlobalPages({evaluate,paint,send});
    await checkScopedPages({evaluate,paint,send});
    return checkPaneDeck({evaluate,paint,send});
  }
  await checkFullscreenPanelInsets({evaluate, screen});
  await evaluate(source);
  await evaluate(`window.hostNav = document.querySelector('#sugar-edge-navigation');
    window.el = s => hostNav.shadowRoot.querySelector(s);
    window.logNav = []; window.docks = [];
    window.addEventListener('sugar-edge-action', e => logNav.push(e.detail));
    window.addEventListener('sugar-edge-dock', e => docks.push(e.detail));
    SugarEdgeNav.configure({reducedMotion:true});`);
  assert.equal(await evaluate('SugarEdgeNav.isExpanded()'), false);
  const handle = await evaluate('SugarEdgeNav.getBounds()');
  assert.equal(handle.right, 390);
  assert(handle.right - handle.left < 32 && handle.bottom - handle.top <= 176);
  const box = selector => evaluate(`JSON.parse(JSON.stringify(el('${selector}').getBoundingClientRect()))`);
  const closedBar = await box('.tab');
  assert.equal(closedBar.width, 8.5);
  assert.equal(closedBar.height, 44);
  assert.equal(closedBar.top + closedBar.height / 2, 844 / 2);
  assert(+await evaluate("getComputedStyle(el('.tab')).opacity") < .7);
  assert.equal(handle.right - closedBar.right, 7);
  assert.equal(await evaluate("el('.tab').childElementCount"), 0, 'solid handle without chevron');
  const hiddenStrip = await box('.band');
  assert.equal(hiddenStrip.left, 390, 'collapsed right rail is completely outside of the screen');
  assert.equal(await evaluate("getComputedStyle(el('.other-grip'),'::after').content"), 'none');
  const passive = (await evaluate('SugarEdgeNav.getGestureBounds()'))[1];
  assert.equal(passive.left, 0);
  assert(passive.right <= 20 && passive.bottom - passive.top <= 176);
  assert.equal(await evaluate("el('[data-action=back]').disabled"), true);
  const visibleActions = () => evaluate("Array.from(hostNav.shadowRoot.querySelectorAll('.item:not([hidden])'), n => n.dataset.action)");
  assert.deepEqual(await visibleActions(), ['settings', 'home', 'back']);
  await touchSwipe('.grip', -50);
  assert.equal(await evaluate('SugarEdgeNav.isExpanded()'), true, 'right-edge inward drag opens');
  await evaluate(`(() => {
    const button = document.createElement('button');
    button.id = 'profile-launch-test';
    button.setAttribute('data-sugar-open-settings', 'true');
    button.style.cssText = 'position:fixed;top:120px;left:80px;width:120px;height:44px';
    button.innerHTML = '<span>Open configuration</span>';
    document.body.append(button);
  })()`);
  await tap(142,142); await paint();
  assert.equal(await evaluate('SugarEdgeNav.isExpanded()'), true, 'trusted native-popup launch preserves the dock');
  assert.deepEqual(await evaluate('SugarEdgeNav.getPopupDock()'), {dockSide:'right',dockWidth:62});
  await evaluate("document.querySelector('#profile-launch-test').removeAttribute('data-sugar-open-settings')");
  await tap(142,142); await paint();
  assert.deepEqual(await evaluate('SugarEdgeNav.getPopupDock()'), {dockSide:null,dockWidth:0}, 'ordinary page tap still folds the dock');
  await evaluate("document.querySelector('#profile-launch-test').remove();SugarEdgeNav.setExpanded(true)");
  const [capX,capY] = await center('.cap');
  await evaluate(`(() => {
    const close = document.createElement('button'); close.id='file-panel-close';
    close.style.cssText='position:fixed;right:0;top:0;width:60px;height:90px';
    window.previewCloses=0;close.onclick=()=>++previewCloses;document.body.append(close);
    hostNav.shadowRoot.addEventListener('touchend',e=>{window.capEndPrevented=e.defaultPrevented;},{once:true});
  })()`);
  await tap(capX,capY); await paint();
  assert.deepEqual(await evaluate('[SugarEdgeNav.isExpanded(),previewCloses,capEndPrevented]'),[false,0,true],
    'collapsing the rail consumes this touch without activating the DSH close button directly below');
  await tap(capX,capY);
  assert.equal(await evaluate('previewCloses'),1,'a new tap at exactly the same position still reaches the preview close button');
  await evaluate("document.querySelector('#file-panel-close').remove();SugarEdgeNav.setExpanded(true)");
  const fullStrip = await box('.band');
  assert(fullStrip.width > 55,'portrait rail provides extra space around its controls');
  assert.equal((await box('.cap')).width,54,'the wider rail keeps the original button width');
  assert.equal((await box('[data-action=home]')).width,54);
  assert.deepEqual([fullStrip.top, fullStrip.bottom, fullStrip.right], [0, 844, 390], 'portrait painted strip spans system bar areas');
  assert.equal(await evaluate("getComputedStyle(el('.band')).borderTopLeftRadius"), '0px');
  assert.equal(await evaluate("Array.from(hostNav.shadowRoot.querySelectorAll('.item')).every(b => !b.textContent.trim() && b.title && b.getAttribute('aria-label'))"), true);
  assert.equal((await box('.tab')).left, fullStrip.left + 5, 'expanded grip sits inside the page-facing border');
  assert.equal(await evaluate('logNav.length'), 0, 'opening does not dispatch navigation');
  await evaluate('SugarEdgeNav.setNavigation({canBack:true,canForward:true})');
  assert.deepEqual(await visibleActions(), ['settings','forward','home','back']);
  assert.match(await evaluate("el('time.clock').textContent"), /^[0-2]\d:[0-5]\d$/);
  await touchSwipe('.grip', -65);
  assert.equal(await evaluate('logNav.length'), 0, 'short swipe never moves the page');
  await touchSwipe('.grip', -255);
  assert.deepEqual(await evaluate('logNav'), ['forward']);
  assert.deepEqual(await evaluate('docks'), ['left']);
  await touchSwipe('.grip', 255);
  assert.deepEqual(await evaluate('logNav'), ['forward','back']);
  assert.deepEqual(await evaluate('docks'), ['left','right']);
  await swipe('.band', 25);
  assert.equal(await evaluate('SugarEdgeNav.isExpanded()'), false, 'outward drag closes');
  await evaluate('SugarEdgeNav.setNavigation({canBack:false,canForward:false})');
  await touchSwipe('.grip', -255);
  assert.deepEqual(await evaluate('logNav'), ['forward','back'], 'a closed edge with no destination only reveals');
  assert.deepEqual(await evaluate('docks'), ['left','right']);
  await evaluate("SugarEdgeNav.setExpanded(false); SugarEdgeNav.configure({side:'left',colorMode:'random',accent:'#cc6688',blur:false});");
  assert.equal((await evaluate('SugarEdgeNav.getBounds()')).left, 0);
  await touchSwipe('.grip', 50);
  await touchSwipe('.other-grip', -64);
  assert.equal(await evaluate('hostNav.dataset.side'), 'right', 'short pull on passive edge re-docks');
  await touchSwipe('.other-grip', 66);
  assert.equal(await evaluate('hostNav.dataset.side'), 'left');
  assert.deepEqual(await evaluate('logNav'), ['forward', 'back']);
  const pink = await evaluate("getComputedStyle(el('.band')).backgroundColor");
  await evaluate("SugarEdgeNav.configure({colorMode:'adaptive',backdrop:'#262733',blur:true})");
  assert.notEqual(await evaluate("getComputedStyle(el('.band')).backgroundColor"), pink);
  assert.match(await evaluate("getComputedStyle(el('.band')).backdropFilter"), /blur/);
  assert.match(await evaluate("hostNav.style.getPropertyValue('--ink')"), /255,254,251/);
  for (const blur of [true,false]) for (const value of [0,16,65,100]) {
    const actual = await evaluate(`(() => {
      SugarEdgeNav.configure({blur:${blur},transparency:${value}});
      const style = getComputedStyle(el('.band'));
      const channels = style.backgroundColor.slice(style.backgroundColor.indexOf('(')+1,-1).split(',');
      return [channels.length === 3 ? 1 : Number(channels[3]),
        getComputedStyle(el('.band')).backdropFilter !== 'none', style.opacity];
    })()`);
    assert.deepEqual(actual, [1-value/100, blur && value < 100, '1'], 'fade the material, keep foreground opaque');
  }
  await evaluate('SugarEdgeNav.configure({blur:true,transparency:16})');
  await evaluate("el('[data-action=home]').click(); el('[data-action=settings]').click()");
  assert.deepEqual(await evaluate('logNav'), ['forward', 'back', 'home', 'settings']);
  const fixedBefore = await Promise.all(['home','back'].map(name => center('[data-action='+name+']')));
  await evaluate("SugarEdgeNav.setPage('settings')");
  assert.equal(await evaluate("el('[aria-current=page]').dataset.action"), 'overview');
  await evaluate("SugarEdgeNav.setPage('settings','appearance')");
  assert.deepEqual(await visibleActions(), ['overview','appearance','gestures','splash','pet','adb','home','back']);
  assert.deepEqual(await Promise.all(['home','back'].map(name => center('[data-action='+name+']'))), fixedBefore,
    'Home and Back stay at the same coordinates across pages');
  assert.equal(await evaluate("el('[aria-current=page]').dataset.action"), 'appearance');
  const marker = await box('[aria-current=page] i');
  assert.deepEqual([marker.width, marker.height], [4, 20]);
  assert.equal(await evaluate("el('[data-action=back]').disabled"), false, 'settings always allows return');
  await evaluate("window.addEventListener('sugar-edge-action', e => { if (e.detail === 'gestures') SugarEdgeNav.setPage('settings','gestures'); });");
  await tap(...await center('[data-action=gestures]'));
  assert.equal(await evaluate("el('[aria-current=page]').dataset.action"), 'gestures');
  assert.equal(await evaluate('SugarEdgeNav.isExpanded()'), true, 'changing sections leaves the rail open');
  await evaluate(`
    const control = document.createElement('button'); control.id = 'motion-setting';
    control.style.cssText = 'position:fixed;left:200px;top:140px;width:100px;height:50px';
    control.onclick = () => { control.setAttribute('aria-pressed','true'); SugarEdgeNav.configure({reducedMotion:true}); };
    document.body.append(control);`);
  await tap(250, 165);
  assert.equal(await evaluate("document.querySelector('#motion-setting').getAttribute('aria-pressed')"), 'true');
  assert.equal(await evaluate('SugarEdgeNav.isExpanded()'), true, 'configuration stays reachable after a tap');
  await evaluate("document.querySelector('#motion-setting').remove()");
  await tap(...await center('.tab'));
  assert.equal(await evaluate('SugarEdgeNav.isExpanded()'), false, 'explicit settings handle tap closes');
  await touchSwipe('.grip', 40);
  await touchSwipe('.band', -24);
  assert.equal(await evaluate('SugarEdgeNav.isExpanded()'), false, 'explicit settings outward swipe closes');
  await tap(...await center('.tab'));
  assert.equal(await evaluate('SugarEdgeNav.isExpanded()'), true, 'a fresh tap immediately after a drag is accepted');
  await touchSwipe('.grip', 245);
  assert.deepEqual(await evaluate('logNav.slice(-2)'), ['gestures','back']);
  await evaluate("SugarEdgeNav.configure({side:'left'}); SugarEdgeNav.setPage('dsh','home'); SugarEdgeNav.setNavigation({canBack:true,canForward:true}); SugarEdgeNav.setExpanded(false); el('.grip').click()");
  assert.equal(await evaluate("el('[aria-current=page]').dataset.action"), 'home');
  assert.equal(await evaluate("hostNav.shadowRoot.activeElement === el('.cap')"), true);
  await send('Input.dispatchKeyEvent', {type:'keyDown',key:'ArrowDown'});
  assert.equal(await evaluate("hostNav.shadowRoot.activeElement === el('[data-action=settings]')"), true);
  await send('Input.dispatchKeyEvent', {type:'keyDown',key:'Escape'});
  assert.equal(await evaluate('SugarEdgeNav.isExpanded()'), false);
  await screen(800, 330);
  await evaluate('SugarEdgeNav.setExpanded(true)');
  assert.equal(await evaluate("getComputedStyle(el('.clock')).display"), 'none');
  const landscapeBounds = await box('.band');
  for (const item of ['back','forward','home','settings']) {
    const [x, y] = await center('[data-action=' + item + ']');
    assert(x > landscapeBounds.left && x < landscapeBounds.right && y > 0 && y < 330, 'landscape bounds ' + item);
    assert.equal(await evaluate(`hostNav.shadowRoot.elementFromPoint(${x},${y}).closest('button')?.dataset.action`), item);
  }
  for (const height of [375, 425]) {
    await screen(850, height);
    await evaluate("SugarEdgeNav.setPage('settings','overview'); document.documentElement.style.setProperty('--sugar-safe-top','34px'); document.documentElement.style.setProperty('--sugar-safe-bottom','24px'); document.documentElement.style.setProperty('--sugar-safe-left','45px')");
    const bar = await box('.tab');
    const hit = await box('.grip');
    const first = await box('[data-action=overview]');
    const landscapeStrip = await box('.band');
    assert.deepEqual([landscapeStrip.left, landscapeStrip.top, landscapeStrip.bottom], [0, 0, height], 'strip covers the cutout edge continuously');
    assert((await box('.cap')).top >= 34 && (await box('.fixed')).bottom <= height - 24, 'only actions avoid system insets');
    assert(first.left >= 45 && bar.left > first.left && bar.left >= hit.left && bar.right <= hit.right,
      'visible grip and items clear the left cutout; hit area contains painted grip');
    assert.equal(await evaluate("getComputedStyle(el('.clock')).display"), 'none');
    for (const item of ['overview','appearance','gestures','splash','pet','adb','home','back']) {
      // On short displays the contextual list scrolls while the Home and Back slots stay fixed.
      if (height < 400) await evaluate(`el('[data-action=${item}]').scrollIntoView({block:'nearest'})`);
      const [x, y] = await center('[data-action='+item+']');
      assert.equal(await evaluate(`hostNav.shadowRoot.elementFromPoint(${x},${y}).closest('button')?.dataset.action`), item);
      assert.equal(await evaluate(`el('[data-action=${item}]').hasAttribute('aria-label')`), true);
    }
    await evaluate("SugarEdgeNav.setExpanded(false)");
    for (const s of ['left','right']) {
      await evaluate("SugarEdgeNav.configure({side:'"+s+"'})");
      const mark = await box('.tab');
      const areas = await evaluate('SugarEdgeNav.getGestureBounds()');
      assert(Math.abs(mark.top + mark.height / 2 - height / 2) <= .01, 'landscape visible mark centered');
      assert(areas.every(area => Math.abs((area.top + area.bottom) / 2 - height / 2) <= .01), 'both exclusion rects centered');
    }
    await evaluate("SugarEdgeNav.configure({side:'left'}); SugarEdgeNav.setExpanded(true)");
  }
  await screen(800, 245);
  await evaluate("SugarEdgeNav.setPage('settings','gestures'); document.documentElement.style.setProperty('--sugar-safe-top','36px'); document.documentElement.style.setProperty('--sugar-safe-bottom','24px')");
  for (const item of ['overview','appearance','gestures','splash','pet','adb','home','back']) {
    await evaluate(`el('[data-action=${item}]').scrollIntoView({block:'nearest'})`);
    const [x, y] = await center('[data-action='+item+']');
    assert.equal(await evaluate(`hostNav.shadowRoot.elementFromPoint(${x},${y}).closest('button')?.dataset.action`), item, 'short screen ' + item);
  }
  await evaluate("SugarEdgeNav.setPage('dsh')");
  await evaluate('SugarEdgeNav.setCovered(true)');
  assert.equal(await evaluate('SugarEdgeNav.getBounds().right'), 0);
  await evaluate('SugarEdgeNav.setCovered(false)');
  await mouse('mousePressed', 250, 150); await mouse('mouseReleased', 250, 150);
  assert.equal(await evaluate('SugarEdgeNav.isExpanded()'), false);
  for (const [w,h] of [[390,844],[850,395],[800,245]]) {
    await screen(w,h);
    for (const side of ['left','right']) {
      await evaluate(`SugarEdgeNav.configure({side:'${side}'}); SugarEdgeNav.setExpanded(false);
        document.documentElement.style.setProperty('--sugar-safe-left','17px');document.documentElement.style.setProperty('--sugar-safe-right','11px');`);
      const folded = await box('.tab');
      await evaluate('SugarEdgeNav.setExpanded(true)');
      const open = await box('.tab');
      const strip = await box('.band');
      assert.equal(side==='left'?strip.right-open.right:open.left-strip.left,5,'open mark hugs the page-facing side');
      assert.equal(side==='left'?folded.left:w-folded.right,side==='left'?24:18,'folded mark stays 7px from safe edge');
      assert.deepEqual([open.width,open.height,open.y],[8.5,44,folded.y]);
      assert(Math.abs(open.y+open.height/2-h/2)<.6,'grip stays vertically centered even on a short screen');
      const [x,y] = await center('.tab');
      assert.equal(await evaluate(`hostNav.shadowRoot.elementFromPoint(${x},${y}).closest('button').className`),'grip');
      await touch('touchStart',x,y);await touch('touchMove',x+(side==='left'?67:-67),y);await paint();
      const moving = await box('.tab'), movingStrip = await box('.band');
      assert(Math.abs(moving.x-open.x)>55,'the visible grip travels with the strip');
      assert.equal(side==='left'?movingStrip.right-moving.right:moving.left-movingStrip.left,5);
      assert.equal(await evaluate("getComputedStyle(el('.grip')).opacity"),'1','one visible grip during drag');
      const exclusions = await evaluate('SugarEdgeNav.getGestureBounds()');
      assert(exclusions.some(zone=>zone.left===0) && exclusions.some(zone=>zone.right===w),'native exclusions stay on the screen edges');
      await touch('touchCancel');
    }
  }
  for (const side of ['left','right']) {
    await evaluate(`SugarEdgeNav.configure({side:'${side}',reducedMotion:true});SugarEdgeNav.setExpanded(false);`);
    await paint();
    for (const show of [true,false]) {
      const samples = await evaluate(`(() => {
        const thumb = el('.tab'), before=thumb.getBoundingClientRect().x;
        SugarEdgeNav.configure({reducedMotion:false}); SugarEdgeNav.setExpanded(${show});
        const animations=[el('.track'),el('.grip')].map(n=>n.getAnimations().find(a=>a.transitionProperty==='transform'));
        if(animations.some(a=>!a))throw Error('Missing synchronized track or handle transition');
        const at=ms=>{for(const a of animations){a.pause();a.currentTime=ms;} return thumb.getBoundingClientRect().x;};
        const duration=animations[0].effect.getTiming().duration;
        if(duration>165)throw Error('Edge rail takes too long to open or close: '+duration);
        if(animations.some(a=>a.effect.getTiming().duration!==duration))throw Error('Mismatched track and grip timing');
        const result=[before,at(0),at(duration*.35),at(duration-1)];
        for(const a of animations)a.finish();
        return result;
      })()`);
      assert(Math.abs(samples[0]-samples[1])<1,'no handle jump at the start of a transition');
      assert((samples[2]-samples[1])*(samples[3]-samples[2])>0,'the same mark moves continuously in both directions');
    }
  }
  await evaluate(source);
  assert.equal(await evaluate("document.querySelectorAll('#sugar-edge-navigation').length"), 1);
  await screen(390, 844);
  await evaluate(`window.hostNav = document.querySelector('#sugar-edge-navigation');
    SugarEdgeNav.configure({reducedMotion:true}); document.documentElement.removeAttribute('style');
    window.scrubs=[]; window.edgeTaps=0;
    window.addEventListener('sugar-edge-scrub', e => scrubs.push(e.detail));
    const edgeTarget = document.createElement('button'); edgeTarget.id='under-edge';
    edgeTarget.style.cssText='position:fixed;left:0;top:0;width:36px;height:100%';
    edgeTarget.onclick=()=>++edgeTaps; document.body.append(edgeTarget);`);
  await tap(8,22); await tap(8,422);
  assert.equal(await evaluate('edgeTaps'), 2, 'real buttons under edge and transparent secondary handle receive taps');
  assert.equal(await evaluate('SugarEdgeNav.isExpanded()'), false);
  for (const [x,y,dx,side] of [[8,12,43,'left'], [382,12,-43,'right'],[8,832,43,'left'],[382,832,-43,'right']]) {
    await evaluate('SugarEdgeNav.setExpanded(false)');
    await swipeAt(x,y,dx);
    assert.deepEqual(await evaluate('[SugarEdgeNav.isExpanded(),hostNav.dataset.side]'), [true,side],
      'short pull near top or bottom opens the starting edge');
  }
  assert.equal(await evaluate('edgeTaps'), 2, 'claimed swipes are not delivered as clicks');
  assert.deepEqual(await evaluate('scrubs'), [], 'no history gives no page preview');
  // Replay the Android 15 WebView sample at 30dp from the edge. Desktop CDP otherwise jumps
  // tens of pixels with a cancellable move and never exercises the compositor handoff.
  for (const initialCancelable of [true, false]) {
    const result = await evaluate(`(() => {
      SugarEdgeNav.setExpanded(false); const node = document.getElementById('under-edge');
      const fire = (type, x, cancelable) => {
        const t = new Touch({identifier:18,target:node,clientX:x,clientY:800});
        const e = new TouchEvent(type,{bubbles:true,composed:true,cancelable,
          touches:type==='touchend'?[]:[t], changedTouches:[t]});
        node.dispatchEvent(e); return e.defaultPrevented;
      };
      fire('touchstart',29.80,false);
      const owned = fire('touchmove',38.16,${initialCancelable});
      node.dispatchEvent(new PointerEvent('pointercancel',{pointerType:'touch',pointerId:19,bubbles:true,composed:true}));
      for (const x of [40.45,42.75,45.04,47.33,76]) fire('touchmove',x,false);
      fire('touchend',108.24,false);
      return [owned,SugarEdgeNav.isExpanded(),hostNav.dataset.side];
    })()`);
    assert.deepEqual(result,[initialCancelable,true,'left'],'Android fractional first move and noncancellable continuation');
  }
  await evaluate(`document.querySelector('#under-edge').remove(); SugarEdgeNav.setExpanded(false);
    const h=document.createElement('div'); h.id='h-file-preview';
    h.style.cssText='position:fixed;right:0;top:100px;width:110px;height:60px;overflow-x:auto';
    h.innerHTML='<div style="width:600px;height:36px"></div>'; document.body.append(h);
    const v=document.createElement('div'); v.id='v-file-list';
    v.style.cssText='position:fixed;left:0;top:180px;width:80px;height:150px;overflow-y:auto';
    v.innerHTML='<div style="height:800px"></div>'; document.body.append(v);`);
  await swipeAt(381,125,-76); await paint();
  assert(await evaluate("document.getElementById('h-file-preview').scrollLeft > 0"), 'horizontal preview scroll works at screen edge');
  await swipeAt(13,287,0,-90); await paint();
  assert(await evaluate("document.getElementById('v-file-list').scrollTop > 0"), 'vertical file list scroll works at screen edge');
  assert.equal(await evaluate('SugarEdgeNav.isExpanded()'), false);
  await evaluate(`document.querySelector('#h-file-preview').remove(); document.querySelector('#v-file-list').remove();
    SugarEdgeNav.setNavigation({canBack:true,canForward:true});`);
  await touch('touchStart',9, 23,Date.now()/1000-.24); await touch('touchMove', 131, 23); await paint();
  const preview = await evaluate('scrubs.at(-1)');
  assert.equal(preview.phase, 'move'); assert.equal(preview.direction, 'back');
  const openedWidth = (await box('.band')).width;
  assert(Math.abs(preview.progress - (122-openedWidth)/(390-openedWidth)) < .015,'hidden rail unfolds before moving the page');
  await evaluate('SugarEdgeNav.setPage("dsh"); SugarEdgeNav.setNavigation({canBack:false})');
  await touch('touchMove', 248,23); await touch('touchEnd');
  assert.equal(await evaluate('scrubs.at(-1).phase'), 'commit', 'snapshot still commits after early target updates history');
  await evaluate('SugarEdgeNav.setExpanded(false); window.scrubs=[]; SugarEdgeNav.setNavigation({canBack:true})');
  await touch('touchStart',382,800,Date.now()/1000-.24); await touch('touchMove', 284,800); await paint();
  await touch('touchCancel');
  assert.equal(await evaluate('scrubs.at(-1).phase'), 'cancel');
  await evaluate(`window.order=[];
    window.addEventListener('sugar-edge-scrub', e=>order.push(e.detail.phase));
    window.addEventListener('sugar-edge-action', e=>order.push(e.detail));`);
  await touch('touchStart',7, 46,Date.now()/1000-.24); await touch('touchMove', 138,46); await paint();
  await touch('touchMove', 20,46); await paint(); await touch('touchEnd');
  assert.equal(await evaluate("order.at(-1)"), 'cancel', 'returning toward starting edge cancels the preview');
  await evaluate('order.length=0');
  await swipeAt(7,46,228);
  assert.deepEqual(await evaluate('order.slice(-2)'), ['commit','back'], 'commit precedes navigation action');
  const beforeNative = await evaluate('logNav.length');
  await evaluate("SugarEdgeNav.revealFromSystemEdge('left'); SugarEdgeNav.revealFromSystemEdge('left')");
  assert.deepEqual(await evaluate('[SugarEdgeNav.isExpanded(),hostNav.dataset.side,logNav.length]'), [true, 'left', beforeNative]);
  // A DSH route commit may replace the very button that began a passive-edge touch.
  await evaluate(`(() => {
    const region = document.createElement('div');
    region.style.cssText='position:fixed;left:0;top:360px;width:36px;height:190px';
    document.body.append(region);
    const model = window.qaMenu = {page:'home',drawer:false,commits:0,cancels:0};
    function render(page) {
      model.page=page;
      const button=document.createElement('button');
      button.style.cssText='width:100%;height:100%'; button.setAttribute('aria-label','打开侧边栏');
      button.onclick=()=>{model.drawer=!model.drawer;}; region.replaceChildren(button);
    }
    function received({detail}) {
      if (detail.direction!=='back') return;
      if (detail.phase==='move' && detail.progress>.18 && model.page==='home') render('session');
      if (detail.phase==='cancel') { ++model.cancels; render('home'); }
      if (detail.phase==='commit') ++model.commits;
    }
    model.dispose=()=>{window.removeEventListener('sugar-edge-scrub',received);region.remove();};
    model.reset=()=>{SugarEdgeNav.configure({side:'right'});SugarEdgeNav.setExpanded(false);
      SugarEdgeNav.setNavigation({canBack:true,canForward:false});render('home');};
    model.reset(); window.addEventListener('sugar-edge-scrub',received);
  })()`);
  await tap(18,465);
  assert.equal(await evaluate('qaMenu.drawer'),true,'an explicit DSH menu tap reaches its actual button');
  await tap(18,465);
  await swipeAt(18,465,33);
  assert.deepEqual(await evaluate('[qaMenu.drawer,SugarEdgeNav.isExpanded()]'),[false,true],
    'short edge swipe opens the rail without activating the DSH menu');
  for (const ending of ['touchCancel','orphanPointerCancel','touchEnd']) {
    await evaluate('qaMenu.reset()');
    await touch('touchStart',18,465,Date.now()/1000-.24); await touch('touchMove',73,465); await paint();
    await touch('touchMove',155,465); await paint();
    assert.equal(await evaluate('qaMenu.page'),'session','preview replaces the original touch target');
    if (ending==='touchCancel') {
      await touch('touchCancel');
    } else if (ending==='orphanPointerCancel') {
      await evaluate("window.dispatchEvent(new PointerEvent('pointercancel',{pointerType:'touch',isPrimary:true}))");
    } else {
      await touch('touchMove',280,465); await touch('touchEnd');
    }
    if (ending!=='touchEnd') {
      await evaluate(`new Promise((ok,fail)=>{
        const until=performance.now()+550;
        const check=()=>qaMenu.page==='home'?ok():performance.now()>until?fail(Error('Detached preview did not roll back')):requestAnimationFrame(check);check();
      })`);
    }
    if (ending==='orphanPointerCancel') await touch('touchCancel');
  }
  assert.deepEqual(await evaluate('[qaMenu.page,qaMenu.commits,qaMenu.cancels,qaMenu.drawer]'),
    ['session',1,2,false], 'each termination has one outcome even after the original target was detached');
  await evaluate('qaMenu.dispose()');
  await checkEmbeddedPreview({evaluate, tap, swipeAt, paint});
  await evaluate("SugarEdgeNav.setExpanded(false); SugarEdgeNav.configure({side:'right'}); SugarEdgeNav.setNavigation({canBack:false,canForward:false})");
  await evaluate(`
    window.hostNav = document.querySelector('#sugar-edge-navigation');
    document.documentElement.removeAttribute('style');
    SugarEdgeNav.configure({reducedMotion:true});
    window.reads = 0; window.writes = 0;
    for (const selector of ['.band','.dock','.other-dock']) {
      const node = el(selector);
      const original = node.getBoundingClientRect.bind(node);
      node.getBoundingClientRect = () => { ++reads; return original(); };
    }
    const originalWidth = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth').get;
    Object.defineProperty(el('.band'), 'offsetWidth', {get() { ++reads; return originalWidth.call(this); }});
    new MutationObserver(records => { writes += records.length; }).observe(el('.track'),{attributes:true,attributeFilter:['style']});
    document.addEventListener('pointerdown', e => { window.testPointer = e.pointerId; }, {capture:true, once:true});
  `);
  const [gx, gy] = await center('.grip');
  await touch('touchStart', gx, gy);
  await evaluate(`(() => {
    for (let step = 10; step <= 220; step += 3) el('.grip').dispatchEvent(new PointerEvent('pointermove',{
      pointerId: testPointer, pointerType:'touch', bubbles: true, composed: true, clientX:${gx} - step, clientY:${gy}}));
  })()`);
  await evaluate("new Promise(done => requestAnimationFrame(() => requestAnimationFrame(done)))");
  assert.deepEqual(await evaluate('[reads,writes]'), [2,1], '71 moves read both anchors once and commit one transform');
  const movingStrip = await box('.band');
  assert.deepEqual([movingStrip.top, movingStrip.bottom], [0,844], 'entire moving strip remains full-height');
  assert.match(await evaluate("getComputedStyle(el('.band')).backdropFilter"), /blur/, 'the moving rail still filters the page');
  assert.match(await evaluate("el('.track').style.transform"), /^translate3d/);
  await touch('touchMove', gx - 225, gy);
  await touch('touchEnd');
  assert.equal(await evaluate("hostNav.dataset.side"), 'right');
  assert.match(await evaluate("getComputedStyle(el('.band')).backdropFilter"), /blur/, 'glass remains active after unfolding');
  await checkForeground({evaluate});
  await checkTaskRail({evaluate, paint});
  await checkRevealPixels({send,evaluate,paint});
  await checkGlassPixels({send,evaluate,paint});
  await checkFlicks({evaluate,touch,center,paint});
  await checkHiddenPull({evaluate,touch,center,paint,screen});
  await checkMarkerAndLift({evaluate,send,center,paint});
  await checkLandingPath({evaluate,send,center,paint});
  await checkClock({evaluate,screen});
  await checkPageActions({evaluate,screen,center,tap,paint});
  await evaluate('SugarEdgeNav.dispose()');
  assert.equal(await evaluate("document.querySelector('#sugar-edge-navigation')"), null);
}
(async () => {
  try {
    await run();
    console.log(recoveryManagerMode ? 'PASS offline recovery manager: docking gestures, limited actions and back handling' :
      recoverySettingsMode ? 'PASS recovery settings: shared surface and rail, inherited controls and bidirectional history' :
      recoveryMode ? 'PASS recovery rail: drag, glass, isolated history, four controls and explicit end request' :
      noticeMode ? 'PASS notification glass: text contrast, surfaces, shadows and filter fallback on plain and mixed backdrops' : languageMode ? 'PASS Sugar language: live controls, navigation, native preference restore and DSH isolation' : exitMode ? 'PASS splash exit: five composited reveals and reduced motion' : previewMode ? 'PASS splash preview: cross-origin layout, scaled drags, rotation, cancel and reset' :
      deckMode ? 'PASS pane deck: prelayout, overlap pixels, reverse path, rollback, once-only commit, reduced motion':
      historyMode ? 'PASS shell history: settings, home, back/forward, inspector swipes and staged rollback' :
      'PASS edge rail: Chromium Android touch, history, contextual pages, active markers, clock, keyboard and landscape');
  } finally {
    if (browser.exitCode === null && browser.signalCode === null) {
      await call('Browser.close').catch(() => browser.kill());
    }
    await closed;
    await rm(profile, {recursive:true, force:true, maxRetries:10, retryDelay:160});
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
