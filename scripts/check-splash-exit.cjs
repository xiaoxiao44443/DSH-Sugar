const assert = require('node:assert/strict');
const {createServer} = require('node:http');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');

// Inspect pixels of the packaged launch document over a second, contrasting
// surface. Checking WAAPI's list alone misses an opaque cover hiding the reveal.
module.exports = async function checkSplashExit({assets, send, evaluate, paint}) {
  const paths = {
    '/': ['text/html', 'sugar-splash/index.html'],
    '/motion.js': ['application/javascript', 'sugar-page-motion.js'],
    '/layout.js': ['application/javascript', 'sugar-splash-layout.js'],
    '/default.png': ['image/png', 'sugar-splash/default.png']
  };
  const server = createServer((req,res) => {
    const entry = paths[req.url];
    if (!entry) { res.writeHead(404).end(); return; }
    res.writeHead(200, {'Content-Type':entry[0]}).end(readFileSync(join(assets,entry[1])));
  });
  await new Promise(ok => server.listen(0,'127.0.0.1',ok));
  try {
    const url = 'http://127.0.0.1:'+server.address().port+'/';
    await send('Page.navigate',{url});
    for (let i=0;i<100;i++) {
      if (await evaluate("location.href==="+JSON.stringify(url)+" && typeof exitSplash==='function'")) break;
      await new Promise(ok=>setTimeout(ok,30));
    }
    await evaluate(`document.body.style.background='#183cd6';
      window.dismissals=0;
      window.SugarLaunch={painted(){},dismissed(){dismissals++}};
      configure({portraitPercent:50,landscapePercent:50,portraitFit:'contain',landscapeFit:'contain',
        backgroundColor:'#ffffff',loadingText:'',showProgress:false,custom:false});
      // Isolate the opacity/shape of the paper from animation inside the APNG.
      layout.art.style.visibility='hidden';
      window.selected='iris';
      const actualPlay=SugarPageMotion.play;
      SugarPageMotion.play=options=>actualPlay({...options,effect:selected});`);
    const localized=await evaluate(`(() => {
      const options={portraitPercent:50,landscapePercent:50,backgroundColor:'#ffffff',loadingText:'Custom 🐋',showProgress:true};
      return ['en','ja','zh'].map(language=>{
        configure({...options,language});
        return [page.lang,page.shadowRoot.querySelector('[part=progress]').getAttribute('aria-label'),
          page.shadowRoot.querySelector('[part=caption]').textContent];
      });
    })()`);
    assert.deepEqual(localized,[['en','Loading progress','Custom 🐋'],['ja','読み込み状況','Custom 🐋'],['zh','加载进度','Custom 🐋']]);
    await evaluate("configure({portraitPercent:50,landscapePercent:50,backgroundColor:'#ffffff',loadingText:'',showProgress:false})");
    async function pixels() {
      const {data} = await send('Page.captureScreenshot',{format:'png',clip:{x:0,y:0,width:320,height:600,scale:.20}});
      return evaluate(`(async()=>{
        const bytes=Uint8Array.from(atob('${data}'),c=>c.charCodeAt(0));
        const img=await createImageBitmap(new Blob([bytes],{type:'image/png'}));
        const ctx=new OffscreenCanvas(img.width,img.height).getContext('2d');
        ctx.drawImage(img,0,0);img.close();
        const rgba=ctx.getImageData(0,0,ctx.canvas.width,ctx.canvas.height).data;
        let old=0,under=0,blend=0;
        for(let i=0;i<rgba.length;i+=4) {
          if(rgba[i]>248)old++;
          else if(rgba[i]<30)under++;
          else blend++;
        }
        const count=rgba.length/4;
        return [old/count,under/count,blend/count];
      })()`);
    }
    for (const name of ['iris','split','diagonal','focus','slide']) {
      await evaluate(`selected=${JSON.stringify(name)};page.style.display='';dismissals=0;
        for(const a of document.getAnimations())a.cancel();`);
      await paint();
      assert((await pixels())[0]>.98,name+' starts with the launch paper');
      await evaluate(`exitSplash();for(const a of document.getAnimations()){
        a.pause();a.currentTime=a.effect.getTiming().duration*.50;
      }`);
      await paint();
      const [solid,,mixed]=await pixels();
      assert(solid<.02 && mixed>.025,name+' blends the underlying content during exit: '+[solid,mixed]);
      await evaluate("for(const a of document.getAnimations())a.finish()");
      await paint();
      assert.equal(await evaluate('dismissals'),1);
      assert((await pixels())[1]>.98,name+' hands off the whole destination');
    }
    await send('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
    await evaluate("page.style.display='';dismissals=0;exitSplash()");
    assert.equal(await evaluate('dismissals'),1,'reduced motion dismisses immediately');
  } finally {
    await send('Emulation.setEmulatedMedia',{features:[]});
    await new Promise(ok=>{server.close(ok);server.closeAllConnections()});
  }
};
