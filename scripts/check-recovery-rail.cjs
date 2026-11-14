const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');

module.exports = async function checkRecoveryRail({assets,evaluate,touch,tap,center,touchSwipe,paint}) {
  await evaluate("document.body.innerHTML='<main class=pI_x6G_centerCol>Temporary workspace</main>'");
  for (const file of ['sugar-i18n.js','sugar-edge-nav.js','sugar-page-motion.js','sugar-recovery-scrub.js','sugar-recovery-rail.js']) {
    await evaluate(readFileSync(join(assets,file),'utf8'));
  }
  await evaluate(`SugarRecoveryRail.mount({language:'en',home:'Diagnostic session home',forward:'Forward',
    end:'End diagnostic session',side:'right',colorMode:'adaptive',blur:true,transparency:65,reducedMotion:true});
    window.hostRail=document.getElementById('sugar-edge-navigation');
    window.el=selector=>hostRail.shadowRoot.querySelector(selector);
    window.actions=[]; window.turnSignals=[]; window.nativeRail=new MessageChannel();
    new Promise(resolve=>{nativeRail.port1.onmessage=e=>{
      if(e.data==='ready') resolve(); else if(e.data.startsWith('{')) turnSignals.push(JSON.parse(e.data).turnActive); else actions.push(e.data);
    };postMessage('sugar-recovery/rail','*',[nativeRail.port2])})`);
  await paint();
  assert.equal(await evaluate("turnSignals.at(-1)"),false);
  await evaluate("window.SugarRecoveryTurnActive=true; dispatchEvent(new Event('sugar-recovery-turn'))");
  await paint();
  assert.equal(await evaluate("turnSignals.at(-1)"),true,'live diagnostic status reaches the native rail');
  await evaluate("window.SugarRecoveryTurnActive=false; dispatchEvent(new Event('sugar-recovery-turn')); document.dispatchEvent(new Event('beforeinput'))");
  await paint();
  assert.equal(await evaluate("turnSignals.at(-1)"),false);
  assert.deepEqual(await evaluate("actions"),[],'synthetic editing cannot renew native activity');
  await touchSwipe('.grip',-48); await paint();
  assert.equal(await evaluate('SugarEdgeNav.isExpanded()'),true);
  assert.deepEqual(await evaluate("[...hostRail.shadowRoot.querySelectorAll('.item')].map(b=>[b.getAttribute('aria-label'),b.disabled,b.hidden])"),[
    ['End diagnostic session',false,false], ['Forward',true,false], ['Diagnostic session home',false,false], ['Back',true,false]
  ]);
  assert.match(await evaluate("getComputedStyle(el('.band')).backgroundColor"),/0\.35\)/);
  assert.match(await evaluate("getComputedStyle(el('.band')).backdropFilter"),/blur\(/);
  const [x,y] = await center('.grip');
  await touch('touchStart',x,y);
  await touch('touchMove',x-115,y); await paint();
  assert.match(await evaluate("el('.track').style.transform"),/translate3d\(-1\d\dpx/);
  await touch('touchMove',x-242,y);
  await touch('touchEnd'); await paint();
  assert.equal(await evaluate('hostRail.dataset.side'),'left');
  assert.deepEqual(await evaluate('actions'),[],'crossing with no history re-docks without navigating');
  await evaluate("nativeRail.port1.postMessage('1')"); await paint();
  await touchSwipe('.grip',245); await paint();
  assert.deepEqual(await evaluate('actions'),['back'],'one history command per cross-screen swipe');
  await evaluate("nativeRail.port1.postMessage('2')"); await paint();
  await touchSwipe('.grip',-245); await paint();
  assert.deepEqual(await evaluate('actions'),['back','forward']);
  await evaluate("el('[data-action=end]').click()"); await paint();
  assert.deepEqual(await evaluate('actions'),['back','forward'],'synthetic activation cannot request native confirmation');
  await tap(...await center('[data-action=end]')); await paint();
  assert.deepEqual(await evaluate('actions'),['back','forward','end']);
  assert.equal(await evaluate('SugarEdgeNav.isExpanded()'),true,'cancelling the native prompt leaves the rail in place');
  await evaluate("nativeRail.port1.postMessage('system-back')"); await paint();
  assert.equal(await evaluate('SugarEdgeNav.isExpanded()'),false);
  await evaluate("nativeRail.port1.postMessage('system-back')"); await paint();
  assert.deepEqual(await evaluate('actions'),['back','forward','end','back']);

  // At the middle route both history buttons are available. Crossing again restores
  // the page just left, even though an older page is still reachable.
  await evaluate(`window.route=2; window.routeActions=[];
    SugarNavigation = {state:()=>({routeKey:String(route),isHome:route===0,canBack:route>0,canForward:route<2}),
      bookmark:()=>({key:String(route)}), previewTarget:()=>null, checkpoint:()=>()=>true,
      action: command => {
        route = command==='home' ? 0 : route+(command==='back' ? -1 : 1);
        routeActions.push(command);
        dispatchEvent(new Event('sugar-navigation-state'));
        return true;
      },
      beginPanelDrag:()=>({update(){},cancel(){},finish:(commit,navigate)=>commit && navigate()})
    };
    dispatchEvent(new Event('sugar-navigation-state'));
    nativeRail.port1.postMessage('0'); SugarEdgeNav.revealFromSystemEdge('right');`);
  await paint();
  await touchSwipe('.grip',-245); await paint();
  assert.deepEqual(await evaluate('[route,routeActions,actions.length]'),[1,['back'],4]);
  await touchSwipe('.grip',68); await paint();
  assert.equal(await evaluate('route'),1,'releasing a short drag leaves route and history in place');
  await touchSwipe('.grip',245); await paint();
  assert.deepEqual(await evaluate('[route,routeActions,actions.length]'),[2,['back','forward'],4],
    'staged SPA travel commits once in each direction without native document fallback');
  await evaluate(`window.tapTransitions=[];
    window.SugarPageMotion={...SugarPageMotion,play:options=>tapTransitions.push(options.direction)};
    SugarEdgeNav.setExpanded(true)`);
  await paint();
  await tap(...await center('[data-action=back]')); await paint();
  assert.deepEqual(await evaluate('[route,tapTransitions,actions.length]'),[1,['back'],4],
    'a diagnostic history button animates the new route once');
  await evaluate('SugarEdgeNav.setExpanded(true)'); await paint();
  await tap(...await center('[data-action=home]')); await paint();
  assert.deepEqual(await evaluate('[route,tapTransitions]'),[0,['back','forward']]);
  await evaluate('SugarEdgeNav.setExpanded(true)'); await paint();
  await tap(...await center('[data-action=home]')); await paint();
  assert.deepEqual(await evaluate('[route,tapTransitions,actions.length]'),[0,['back','forward'],4],
    'Home at the diagnostic root neither animates again nor leaves the viewer');
};
