const assert = require('node:assert/strict');
const {join} = require('node:path');
const {pathToFileURL} = require('node:url');

module.exports = async function checkManager(driver) {
  for (const dark of [false,true]) await checkTheme(driver,dark);
};

async function checkTheme({assets,evaluate,send,touchSwipe,paint},dark) {
  // Load the same offline document as the failure screen, with no running DSH host.
  const url = pathToFileURL(join(assets,'sugar-recovery-management.html')).href;
  await send('Page.navigate',{url:url+(dark?'?dark':'?light')});
  await evaluate(`new Promise((resolve,reject)=>{
    const deadline = performance.now()+5000;
    function wait() {
      if (window.SugarRecoveryManagement) return resolve();
      if (performance.now()>=deadline) return reject(Error('Offline recovery page failed to load'));
      setTimeout(wait,20);
    }
    wait();
  })`);
  const config = {language:'en',side:'right',blur:true,transparency:40,reducedMotion:true,
    accent:'#88beb7',colorMode:'adaptive',dark,
    labels:{diag_title:'Diagnostics and recovery',diag_nav_home:'Retry DSH',diag_intro:'Recovery options',
      diag_current:'Temporary profile',diag_builtin:'Bundled program',diag_restore:'Restore program',diag_reset:'Reset profile'}};
  await evaluate(`SugarRecoveryManagement.mount(${JSON.stringify(config)});
    window.railHost = document.getElementById('sugar-edge-navigation');
    window.el = selector=>railHost.shadowRoot.querySelector(selector);
    window.requests = []; window.channel = new MessageChannel();
    new Promise((resolve,reject)=>{
      const timeout=setTimeout(()=>reject(Error('Offline manager bridge did not connect')),3500);
      channel.port1.onmessage=({data})=>{
        if(data==='ready'){ clearTimeout(timeout); resolve(); }
        else if(!data.startsWith('{')) requests.push(data);
      };
      postMessage('sugar-recovery/management','*',[channel.port2]);
    })`);
  assert.equal(await evaluate("document.getElementById('sugar-shell-settings').shadowRoot.querySelectorAll('[data-recovery-action]').length"),4);
  await evaluate("channel.port1.postMessage('system-back')");
  await paint();
  assert.deepEqual(await evaluate('requests.slice()'),[], 'a running recovery operation holds the manager open');
  for (const mode of ['current','builtin']) {
    const starting = {busy:true,message:'Starting DSH',loadingMode:mode};
    await evaluate(`channel.port1.postMessage(${JSON.stringify(JSON.stringify(starting))})`);
    await paint();
    const selected = "document.getElementById('sugar-shell-settings').shadowRoot.querySelector('[data-loading]')";
    assert.deepEqual(await evaluate(`[ ${selected}.dataset.recoveryAction, ${selected}.querySelector('.recovery-stage').textContent,
      getComputedStyle(${selected}.querySelector('.recovery-spinner')).animationName ]`),[mode,'Starting DSH','none'],
      'reduced motion keeps the active mode and its progress readable');
    await evaluate("document.getElementById('sugar-shell-settings').removeAttribute('data-reduced')");
    await paint();
    assert.equal(await evaluate(`${selected}.querySelector('.recovery-spinner').getAnimations()[0]?.playState`),'running');
    await evaluate("document.getElementById('sugar-shell-settings').setAttribute('data-reduced','')");
  }
  await evaluate("channel.port1.postMessage(JSON.stringify({busy:false,message:'Startup failed',loadingMode:null}))");
  await paint();
  assert.deepEqual(await evaluate(`(() => {
    const root=document.getElementById('sugar-shell-settings').shadowRoot;
    return [root.querySelectorAll('[data-loading]').length,root.querySelector('.recovery-status').textContent,
      root.querySelectorAll('[data-recovery-action]:disabled').length];
  })()`),[0,'Startup failed',0],'failed startup releases the actions and replaces the busy indicator with its error');
  await evaluate("channel.port1.postMessage(JSON.stringify({busy:false,message:''}))");
  await paint();
  const paper = dark ? 'rgb(28, 34, 31)' : 'rgb(246, 247, 244)';
  assert.equal(await evaluate("getComputedStyle(document.getElementById('sugar-shell-settings')).backgroundColor"),paper);
  assert.equal(await evaluate("getComputedStyle(document.documentElement).backgroundColor"),paper);
  await evaluate('SugarEdgeNav.setExpanded(true)');
  await paint();
  await touchSwipe('.grip',-246);
  await paint();
  assert.equal(await evaluate('railHost.dataset.side'),'left');
  await touchSwipe('.grip',246);
  await paint();
  assert.equal(await evaluate('railHost.dataset.side'),'right');
  assert.deepEqual(await evaluate('requests.slice()'),[], 're-docking on a single page never exits');
  await evaluate("channel.port1.postMessage('system-back')");
  await paint();
  assert.equal(await evaluate('SugarEdgeNav.isExpanded()'),false);
  assert.deepEqual(await evaluate('requests.slice()'),[]);
  await evaluate("channel.port1.postMessage('system-back')");
  await paint();
  assert.deepEqual(await evaluate('requests.slice()'),['back']);
}
