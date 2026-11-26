const assert = require('node:assert/strict');
const {createServer} = require('node:http');
const {readFileSync, mkdirSync, writeFileSync} = require('node:fs');
const {join, resolve} = require('node:path');

// Runs inside the dedicated, disposable Chromium instance from check-edge-nav.cjs.
module.exports = async function checkLicenses({assets, send, evaluate, paint, screen}) {
  const read = name => readFileSync(join(assets, name), 'utf8');
  const actual = JSON.parse(read('open-source-notices.json'));
  const text = '<script>window.licenseExecuted = true</script>\nCopyright (c) Test owner\n' + 'Long-license-token-'.repeat(40);
  const name = '<img src=x onerror="window.licenseExecuted=true">';
  const probe = {schema:1, components:[
    {id:'safe-probe', name, version:'1.0.0', license:'MIT', group:'android', sourceUrl:'https://example.org/source', documents:[{title:'LICENSE',text}]},
    {id:'other-probe', name:'Other component', version:'2.0', license:'Apache-2.0', group:'runtime', documents:[{title:'NOTICE',text:'A separate copyright notice.'}]}
  ]};
  let payload = actual, failures = 0, requests = 0, loads = 0;
  const html = '<!doctype html><html><meta charset="utf-8"><style>body{margin:0;background:#fff}</style><main>DSH fixture</main>';
  const server = createServer((req,res) => {
    if (new URL(req.url, 'http://localhost').pathname === '/__sugar__/open-source-notices.json') {
      requests++;
      if (failures > 0) { failures--; res.writeHead(503,{'Content-Type':'text/plain'}).end('unavailable'); return; }
      res.writeHead(200,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'}).end(JSON.stringify(payload));
    } else res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'}).end(html);
  });
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  const origin = 'http://127.0.0.1:' + server.address().port;
  const pane = "window.settings().querySelector('[data-pane=licenses]')";
  const detail = "window.settings().querySelector('[data-pane=license-detail]')";
  const listScroll = pane + '.querySelector("[data-pane-scroll]")';
  const wait = expression => evaluate(`new Promise((ok,fail)=>{const until=performance.now()+5000;function next(){
    if(${expression})return ok();if(performance.now()>until)return fail(Error('License UI condition timed out: '+${JSON.stringify(expression)}));
    setTimeout(next,15)}next()})`);
  async function load() {
    const target = origin + '/?load=' + ++loads;
    await send('Page.navigate',{url:target});
    await wait(`location.href===${JSON.stringify(target)} && document.querySelector('main')`);
    for (const file of ['sugar-i18n.js','sugar-edge-nav.js','sugar-page-motion.js','sugar-pane-deck.js','sugar-splash-preview.js',
      'sugar-splash-settings.js','sugar-mobile-controls.js','sugar-pets.js','sugar-storage.js','sugar-workspace-files.js',
      'sugar-session-storage.js','sugar-recovery-page.js','sugar-shell.js']) await evaluate(read(file));
    const styles = ['sugar-settings.css','sugar-pets.css','sugar-workspace-files.css'].map(read).join('\n');
    await evaluate(`SugarShell.mount({language:'zh',reducedMotion:true,appInfo:{name:'DSH Sugar',version:'9.8.7-test'},
      splash:{showProgress:true,loadingText:'Loading',backgroundColor:'#ffffff'}},${JSON.stringify(styles)});
      window.settings=()=>document.getElementById('sugar-shell-settings').shadowRoot;
      window.rail=document.getElementById('sugar-edge-navigation').shadowRoot;window.messages=[];window.licenseExecuted=false;
      new Promise(ok=>{const c=new MessageChannel();window.native=c.port1;native.onmessage=({data})=>{
        const message=JSON.parse(data);messages.push(message);if(message.type==='state')ok();};
        postMessage('dsh-sugar/edge-shell','*',[c.port2]);});`);
    await evaluate("SugarEdgeNav.setExpanded(true);rail.querySelector('[data-action=settings]').click()");
    await paint();
    await evaluate("settings().querySelector('[data-go=general]').click()");
    await paint();
    await evaluate("settings().querySelector('[data-go=about]').click()");
    await paint();
  }
  async function open() {
    await evaluate("settings().querySelector('[data-go=licenses]').click()");
    await paint();
    assert.equal(await evaluate(pane+'.hidden'),false);
  }
  const componentButton = id => pane + '.querySelector(' + JSON.stringify('[data-license-component="' + id + '"]') + ')';
  async function openComponent(id) {
    await evaluate(componentButton(id)+'.click()');
    await wait(`!${detail}.hidden && ${detail}.dataset.licenseId===${JSON.stringify(id)}`);
    await paint();
  }
  async function checkDetail(component) {
    const shown=await evaluate(`(()=>{const p=${detail},body=p.querySelector('[data-license-detail]');
      return {id:p.dataset.licenseId,hidden:p.hidden,title:p.querySelector('h1').textContent,
        originalTitle:!p.querySelector('h1').translate,
        texts:[...body.querySelectorAll('pre')].map(n=>n.textContent),folded:body.querySelectorAll('details').length};})()`);
    assert.equal(shown.hidden,false,'selecting a component opens its own detail page');
    assert.equal(await evaluate(pane+'.hidden'),true,'the component list yields to the detail page');
    assert.equal(shown.id,component.id);
    assert.equal(shown.title,component.name);
    assert.equal(shown.originalTitle,true,'component names are kept in their original language');
    assert.deepEqual(shown.texts,component.documents.map(doc=>doc.text),'all original notices are available directly');
    assert.equal(shown.folded,0,'notice originals are not hidden behind another disclosure');
  }
  async function backToList(expectedScroll) {
    await evaluate("rail.querySelector('[data-action=back]').click()");
    await wait(`!${pane}.hidden`);await paint();
    if (expectedScroll!==undefined) assert(Math.abs(await evaluate(listScroll+'.scrollTop')-expectedScroll)<=1,
      'returning from a component restores the list position');
  }
  const visibleIds = () => evaluate(`[...${pane}.querySelectorAll('[data-license-component]')]
    .filter(n=>n.getBoundingClientRect().height>0).map(n=>n.dataset.licenseComponent)`);
  const shot = async name => {
    if (!process.env.SUGAR_LICENSE_SCREENSHOTS) return;
    const folder=resolve(process.env.SUGAR_LICENSE_SCREENSHOTS);mkdirSync(folder,{recursive:true});
    const result=await send('Page.captureScreenshot',{format:'png'});
    writeFileSync(join(folder,name+'.png'),Buffer.from(result.data,'base64'));
  };
  try {
    // All content is loaded from the local packaged-asset route; external HTTPS is unavailable.
    await send('Network.enable');
    await send('Network.setBlockedURLs',{urls:['https://*']});
    await load();
    assert.equal(requests,0,'license data is read only when its page is opened');
    await open();
    await wait(`${pane}.querySelector('[data-license-component]')`);
    await paint();
    const ids=await visibleIds();
    assert.equal(ids.length,actual.components.length,'the installed notice index is available without third-party services');
    assert.equal(requests,1);
    assert(await evaluate(`[...${pane}.querySelectorAll('[data-license-component]')].every(n=>n.tagName==='BUTTON')`),
      'components are navigation entries rather than inline disclosures');
    assert.equal(await evaluate(pane+".querySelector('input[type=search]')===null"),true,'the notice page stays a simple browsable list without a search box');
    await shot('licenses-light');
    for (const [language,title] of [['zh','开源许可证'],['en','Open-source licenses'],['ja','オープンソースライセンス']]) {
      await evaluate(`SugarI18n.setLanguage(${JSON.stringify(language)})`);await paint();
      assert.equal(await evaluate(pane+".querySelector('h1').textContent"),title);
      assert.equal((await visibleIds()).length,actual.components.length);
    }
    await evaluate("SugarI18n.setLanguage('zh')");await paint();
    await evaluate(pane+".querySelector('[data-license-release]').click()");await paint();
    assert(await evaluate("messages.some(m=>m.type==='openLicenseRelease')"));
    await evaluate("rail.querySelector('[data-action=back]').click()");await paint();
    assert.equal(await evaluate("settings().querySelector('[data-pane=about]').hidden"),false);
    await open();await paint();assert.equal(requests,1,'returning to the license page reuses its local data');
    const first=actual.components.find(item=>item.id===ids[12]);
    const second=actual.components.find(item=>item.id===ids[13]);
    await evaluate(componentButton(first.id)+".scrollIntoView({block:'center'})");await paint();
    const firstScroll=await evaluate(listScroll+'.scrollTop');
    assert(firstScroll>0,'the history check starts from a scrolled component list');
    await openComponent(first.id);await checkDetail(first);
    await shot('license-detail-light');
    await backToList(firstScroll);
    await evaluate(componentButton(second.id)+".scrollIntoView({block:'center'})");await paint();
    const secondScroll=await evaluate(listScroll+'.scrollTop');
    await openComponent(second.id);await checkDetail(second);
    await backToList(secondScroll);
    await evaluate("rail.querySelector('[data-action=forward]').click()");
    await wait(`!${detail}.hidden`);await paint();await checkDetail(second);
    await backToList(secondScroll);
    await evaluate(listScroll+'.scrollTop=0');await paint();
    for (const [width,height,dark] of [[320,740,false],[390,844,true],[740,360,true]]) {
      await screen(width,height);
      await send('Emulation.setEmulatedMedia',{features:[{name:'prefers-color-scheme',value:dark?'dark':'light'}]});
      await evaluate(`document.documentElement.dataset.dsDarkTheme=${JSON.stringify(dark?'true':'false')};
        document.body.style.background=${JSON.stringify(dark?'#151519':'#fff')};
        document.body.style.color=${JSON.stringify(dark?'#f5f5f5':'#222')}`);await paint();
      const layout=await evaluate(`(()=>{const p=${pane}, c=p.querySelector('[data-pane-scroll]');
        return {width:p.clientWidth,scroll:p.scrollWidth,content:c.clientWidth,contentScroll:c.scrollWidth,
          dark:settings().host.classList.contains('dark')};})()`);
      assert(layout.scroll<=layout.width+1,'license pane must not scroll horizontally');
      assert(layout.contentScroll<=layout.content+1,'component labels fit the narrow content column');
      assert.equal(layout.dark,dark);
      await shot('licenses-'+width+'-'+(dark?'dark':'light'));
    }
    await screen(390,844);
    await send('Emulation.setEmulatedMedia',{features:[{name:'prefers-color-scheme',value:'light'}]});
    payload=probe; failures=1;
    await load();await open();
    await wait(`${pane}.querySelector('[data-license-retry]') && ${pane}.querySelector('[data-license-retry]').getBoundingClientRect().height>0`);
    await shot('licenses-retry');
    const failedRequests=requests;
    await evaluate(pane+".querySelector('[data-license-retry]').click()");
    await wait(`${pane}.querySelector('[data-license-component="safe-probe"]')`);
    assert.equal(requests,failedRequests+1,'retry rereads the local notice asset');
    await openComponent('safe-probe');await checkDetail(probe.components[0]);
    assert.equal(await evaluate('window.licenseExecuted'),false);
    assert.equal(await evaluate(detail+".querySelector('img,script')===null"),true,
      'untrusted component names and legal text are never interpreted as HTML');
    await evaluate(detail+".querySelector('[data-license-source]').click()");await paint();
    assert(await evaluate("messages.some(m=>m.type==='openLicenseSource'&&m.id==='safe-probe')"),'source selection sends only a component ID');
    await evaluate("SugarI18n.setLanguage('ja')");await paint();await checkDetail(probe.components[0]);
    for (const [width,height,dark] of [[320,740,false],[390,844,true]]) {
      await screen(width,height);
      await evaluate(`document.documentElement.dataset.dsDarkTheme=${JSON.stringify(dark?'true':'false')}`);await paint();
      const fits=await evaluate(`(()=>{const p=${detail},c=p.querySelector('[data-pane-scroll]');
        return p.scrollWidth<=p.clientWidth+1 && c.scrollWidth<=c.clientWidth+1;})()`);
      assert(fits,'long names and legal tokens wrap on the standalone detail page');
      await shot('license-detail-escaped-'+width+'-'+(dark?'dark':'light'));
    }
    await backToList();await openComponent('other-probe');await checkDetail(probe.components[1]);
    await backToList();
    await evaluate("rail.querySelector('[data-action=forward]').click()");
    await wait(`!${detail}.hidden`);await paint();await checkDetail(probe.components[1]);

  } finally {
    await evaluate('window.SugarShell?.dispose()').catch(()=>{});
    await new Promise(resolve=>{server.close(resolve);server.closeAllConnections();});
  }
};
