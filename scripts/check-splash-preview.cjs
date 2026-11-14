const {createServer} = require('node:http');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const assert = require('node:assert/strict');

// Capture only the waiting bar. A painted but motionless native indeterminate control can
// expose the correct accessibility state and still look frozen throughout a long startup.
async function checkProgress({send,evaluate,assetsFor,prefs}) {
  await evaluate(assetsFor('sugar-splash-layout.js').toString());
  const clip = await evaluate(`(() => {
    const host = document.createElement('div');
    host.style.cssText = 'position:fixed;inset:0';
    document.body.append(host);
    const layout = SugarSplashLayout.create(host), options = ${JSON.stringify(prefs)};
    const bar = host.shadowRoot.querySelector('[part=progress]'), fill = bar.firstElementChild;
    layout.configure(options);
    layout.setStage('正在启动 DSH WebUI');
    window.testProgress = {layout,bar,fill,options,
      value: () => [bar.getAttribute('aria-valuenow'),fill.getBoundingClientRect().width / bar.getBoundingClientRect().width],
      dispose: () => {layout.dispose();host.remove();delete window.testProgress;}
    };
    const r = bar.getBoundingClientRect();
    return {x:Math.floor(r.x),y:Math.floor(r.y),width:Math.ceil(r.width),height:Math.ceil(r.height)+1,scale:1};
  })()`);
  const frames = new Set();
  for (let i=0;i<5;i++) {
    await new Promise(resolve=>setTimeout(resolve,115));
    frames.add((await send('Page.captureScreenshot',{clip})).data);
  }
  assert(frames.size > 1,'the waiting indicator must move between stage updates');
  for (const n of [0,37,100]) {
    const [value,fraction] = await evaluate(`testProgress.layout.setStage('准备',${n});testProgress.value()`);
    assert.equal(value,String(n));
    assert(Math.abs(fraction-n/100) < .002, 'actual progress fills the matching track fraction');
  }
  assert(await evaluate(`testProgress.layout.setStage('正在连接 DSH');
    testProgress.value()[0]===null && testProgress.fill.getAnimations().length===1`));
  await send('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
  assert.equal(await evaluate('testProgress.fill.getAnimations().length'),0,'system reduced motion');
  await send('Emulation.setEmulatedMedia',{features:[]});
  assert.equal(await evaluate(`testProgress.layout.configure({...testProgress.options,reducedMotion:true});
    testProgress.fill.getAnimations().length`),0,'app reduced motion');
  assert.equal(await evaluate(`testProgress.layout.configure({...testProgress.options,showProgress:false});
    testProgress.bar.getBoundingClientRect().height`),0,'progress can be hidden');
  await evaluate('testProgress.dispose()');
}

// Run with node scripts/check-edge-nav.cjs --splash-preview. CDP reads each frame independently;
// the application page receives only normalized drag coordinates from the cross-origin child.
module.exports = async function checkPreview({assets,send,evaluate,touch,paint}) {
  const assetsFor = name => readFileSync(join(assets,name));
  const html = '<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><body>';
  const host = createServer((_req,res) => res.end(html));
  const preview = createServer((req,res) => {
    const routes = {'/doc/index.html':['text/html','sugar-splash/preview.html'],
      '/media/layout.js':['application/javascript','sugar-splash-layout.js'],'/media/image':['image/png','sugar-splash/default.png']};
    const asset = routes[req.url.split('?')[0]];
    if (!asset) {res.writeHead(404).end(); return;}
    const content=assetsFor(asset[1]);
    res.writeHead(200,{'Content-Type':asset[0]});
    res.end(asset[0]==='text/html' ? content.toString().replace('<head>','<head><base href="/media/">') : content);
  });
  const open = server => new Promise(ok=>server.listen(0,'127.0.0.1',ok));
  const close = server => new Promise(ok=>{server.close(ok);server.closeAllConnections();});
  const url = server => 'http://127.0.0.1:'+server.address().port;
  const prefs = {portraitPercent:65,landscapePercent:55,portraitFit:'contain',landscapeFit:'contain',
    showProgress:true,loadingText:'加载动画',backgroundColor:'#274957',durationMs:0,randomExit:false,imageName:null};
  async function wait(fn) {
    for(let i=0;i<100;i++){if(await fn())return; await new Promise(done=>setTimeout(done,35));}
    throw Error('Timed out waiting for splash preview');
  }
  await Promise.all([open(host),open(preview)]);
  try {
    const origin = url(host), frameUrl = url(preview)+'/doc/index.html';
    await send('Page.navigate',{url:origin});
    await wait(()=>evaluate('location.origin==='+JSON.stringify(origin)+' && document.readyState==="complete"'));
    await checkProgress({send,evaluate,assetsFor,prefs});
    await evaluate(assetsFor('sugar-i18n.js').toString());
    await evaluate(assetsFor('sugar-splash-preview.js').toString());
    await evaluate(`document.documentElement.style.setProperty('--sugar-safe-top','25px');
      const h=document.body.appendChild(document.createElement('div')),root=h.attachShadow({mode:'open'});
      root.innerHTML='<style>'+ ${JSON.stringify(assetsFor('sugar-settings.css').toString())}+'</style><section></section>';
      const pane=root.querySelector('section');pane.innerHTML=SugarSplashPreview.controls;
      const state=${JSON.stringify(prefs)}, writes=[];
      const ui=SugarSplashPreview.mount(pane,${JSON.stringify(frameUrl)},patch=>{writes.push(patch);Object.assign(state,patch);ui.configure(state);});
      ui.configure(state);
      window.testPreview={pane,state,writes,ui,iframe:()=>pane.querySelector('iframe')};`);
    let contextId;
    async function child(expression) {
      const reply=await send('Runtime.evaluate',{contextId,expression,returnByValue:true});
      if(reply.exceptionDetails)throw Error(reply.exceptionDetails.exception?.description||'Child evaluation failed');
      return reply.result.value;
    }
    await wait(async()=>{
      const {frameTree}=await send('Page.getFrameTree');
      const frame=frameTree.childFrames?.find(x=>x.frame.url===frameUrl)?.frame;
      if(!frame)return false;
      contextId=(await send('Page.createIsolatedWorld',{frameId:frame.id,worldName:'sugar-layout-regression'})).executionContextId;
      return child("document.querySelector('#preview-page')?.shadowRoot?.querySelector('img')?.naturalWidth > 0");
    });
    const read = part => child(`(()=>{
      const root=document.querySelector('#preview-page').shadowRoot;
      const a=root.querySelector('[part=area]').getBoundingClientRect(), r=root.querySelector('[part=${part}]').getBoundingClientRect();
      return {cx:r.x+r.width/2,cy:r.y+r.height/2,spanX:a.width-r.width,spanY:a.height-r.height,
        x:(r.x-a.x)/(a.width-r.width)*100,y:(r.y-a.y)/(a.height-r.height)*100};
    })()`);
    async function drag(part, dx, dy, cancel) {
      const body=await read(part);
      const frame=await evaluate(`(()=>{const f=testPreview.iframe(),r=f.getBoundingClientRect();
        return {left:r.left,top:r.top,scale:r.width/f.clientWidth};})()`);
      const x=frame.left+body.cx*frame.scale,y=frame.top+body.cy*frame.scale;
      await touch('touchStart',x,y);
      await touch('touchMove',x+body.spanX*dx*frame.scale,y+body.spanY*dy*frame.scale);
      await touch(cancel?'touchCancel':'touchEnd');
      await paint();
      return body;
    }
    assert.deepEqual(await child('[innerWidth,innerHeight]'),[390,844]);
    assert.equal(await evaluate('testPreview.iframe().contentDocument === null'),true);
    for (const [language,expected] of [['ja','DSH WebUI を起動中'],['en','Starting DSH WebUI'],['zh','正在启动 DSH WebUI']]) {
      await evaluate(`SugarI18n.setLanguage('${language}');testPreview.ui.configure(testPreview.state)`);
      await wait(async()=>(await child("document.querySelector('#preview-page').lang"))===language);
      const [stage, caption, label] = await child(`(() => {
        const root=document.querySelector('#preview-page').shadowRoot;
        return [root.querySelector('[part=stage]').textContent, root.querySelector('[part=caption]').textContent,
          root.querySelector('[part=image]').getAttribute('aria-label')];})()`);
      assert.equal(stage,expected,'the cross-origin preview changes its loading status');
      assert.equal(caption,prefs.loadingText,'user-authored text remains verbatim');
      assert.match(label,language==='ja' ? /矢印キー/ : language==='en' ? /arrow keys/ : /方向键/);
    }
    const img=await drag('image',-.22,-.16,false);
    const stored=await evaluate('testPreview.state.portraitImagePosition');
    assert(stored && Math.abs(stored.x-(img.x-22))<2 && Math.abs(stored.y-(img.y-16))<2,'scaled pointer positions persist in the full-size image range');
    const copy=await read('text');
    await drag('text',.15,-.09,true);
    assert.equal(await evaluate('testPreview.writes.length'),1,'cancel does not write');
    assert(Math.abs((await read('text')).y-copy.y)<.1,'cancel restores the previous position');
    await evaluate("testPreview.pane.querySelector('[data-preview-orientation=landscape]').click()");
    await wait(async()=>(await child('innerWidth'))===844); await paint();
    const wide=await drag('text',.2,-.15,false);
    const text=await evaluate('testPreview.state.landscapeTextPosition');
    assert(text && Math.abs(text.x-(wide.x+20))<2 && Math.abs(text.y-(wide.y-15))<2);
    await evaluate("testPreview.pane.querySelector('.preview-reset').click()");
    assert.deepEqual(await evaluate('testPreview.writes.at(-1)'),{landscapeImagePosition:null,landscapeTextPosition:null});
    assert.deepEqual(await evaluate('testPreview.state.portraitImagePosition'),stored);
    await evaluate('testPreview.pane.hidden=true'); await paint();
    assert.equal(await evaluate('testPreview.iframe()'),null,'leaving unloads the animated frame');
    await evaluate('testPreview.ui.dispose()');
  } finally { await Promise.all([close(host),close(preview)]); }
};
