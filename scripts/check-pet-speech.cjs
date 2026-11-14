const assert = require('node:assert/strict');

// Run with `node scripts/check-edge-nav.cjs --language` in the real settings shadow root.
module.exports = async function checkPetSpeech({send, evaluate, paint}) {
  await evaluate(`{
    const defaults = ['嗯。','别戳。','干嘛。'];
    const pet = {selected:'sugar',packs:[{id:'sugar'}],size:112,speech:{idle:['Existing idle'],poke:defaults}};
    window.petSpeechWrites = [];
    native.addEventListener('message', event => {
      const msg = JSON.parse(event.data);
      if (msg.type === 'petSpeech') {
        petSpeechWrites.push(msg);
        pet.speech[msg.state] = msg.reset ? defaults : msg.lines;
      } else if (msg.type !== 'petStatus') return;
      native.postMessage(JSON.stringify({type:'petState',pet}));
    });
    settings().querySelector('[data-go=pet]').click();
  }`);
  await paint();
  const field = '[data-pet-lines]', save = '[data-pet-save]';
  const get = selector => 'settings().querySelector('+JSON.stringify(selector)+')';
  const click = async selector => { await evaluate(get(selector)+'.click()'); await paint(); };
  const value = () => evaluate(get(field)+'.value');
  const type = text => evaluate(get(field)+'.value='+JSON.stringify(text));
  const size = height => send('Emulation.setDeviceMetricsOverride',{width:390,height,deviceScaleFactor:1,mobile:false});
  const viewport = async height => {
    await evaluate(height === undefined ? 'delete visualViewport.height' :
      'Object.defineProperty(visualViewport,"height",{configurable:true,value:'+height+'})');
    await evaluate('visualViewport.dispatchEvent(new Event("resize"))'); await paint();
  };
  async function visibleEditor(description) {
    const geometry = await evaluate(`(() => {
      const input=${get(field)}, buttons=${get('.pet-editor-actions')}, pane=input.closest('.settings-content');
      const f=input.getBoundingClientRect(), b=buttons.getBoundingClientRect(), p=pane.getBoundingClientRect();
      return {focused:settings().activeElement===input,fieldTop:f.top,fieldBottom:f.bottom,
        actionsTop:b.top,actionsBottom:b.bottom,top:Math.max(p.top,visualViewport.offsetTop),
        bottom:Math.min(p.bottom,visualViewport.offsetTop+visualViewport.height)};
    })()`);
    assert.ok(geometry.focused && geometry.fieldTop >= geometry.top-1 && geometry.fieldBottom <= geometry.bottom+1 &&
      geometry.actionsTop >= geometry.top-1 && geometry.actionsBottom <= geometry.bottom+1,
      description+' '+JSON.stringify(geometry));
  }
  await click('[data-pet-expand]');
  assert.equal(await evaluate("settings().querySelectorAll('[data-pet-state]').length"),6);
  await click('[data-pet-state=idle]');
  await evaluate("native.postMessage(JSON.stringify({type:'languageState',language:'zh'}))");
  await paint();
  for (const [language, labels] of [['en',['Idle chatter','Poke']],['ja',['ひとりごと','つつく']],['zh',['自动闲聊','戳一戳']]]) {
    await evaluate(`native.postMessage(JSON.stringify({type:'languageState',language:${JSON.stringify(language)}}))`);
    await paint();
    assert.deepEqual(await evaluate("[...settings().querySelectorAll('[data-pet-idle-speech]')].map(b=>b.textContent)"),labels);
  }
  await click('[data-pet-edit]');
  assert.equal(await value(),'Existing idle');
  await size(430); await paint(); await visibleEditor('Idle editor after WebView resize');
  await size(844); await paint(); await type('Idle draft');
  await click('[data-pet-idle-speech=poke]'); await click('[data-pet-edit]');
  assert.equal(await value(),'嗯。\n别戳。\n干嘛。');
  // Some Android keyboards resize only the visual viewport of a full-height WebView.
  try { await viewport(430); await visibleEditor('Poke editor above the visual keyboard'); }
  finally { await viewport(); }
  await type('My poke'); await click(save);
  assert.deepEqual(await evaluate('petSpeechWrites.at(-1)'),{type:'petSpeech',state:'poke',lines:['My poke']});
  assert.equal(await evaluate(get('[data-pet-bubble]')+".getAttribute('aria-label')"),'My poke');
  await click('[data-pet-idle-speech=idle]'); await click('[data-pet-edit]');
  assert.equal(await value(),'Idle draft');
  await click('[data-pet-cancel]'); await click('[data-pet-edit]');
  assert.equal(await value(),'Existing idle');
  await click('[data-pet-idle-speech=poke]'); await click('[data-pet-edit]');
  await type(''); await click(save);
  assert.equal(await evaluate(get('[data-pet-bubble]')+'.hidden'),true);
  await click('[data-pet-edit]'); await click('[data-pet-reset]');
  assert.deepEqual(await evaluate('petSpeechWrites.at(-1)'),{type:'petSpeech',state:'poke',reset:true});
  await click('[data-pet-state=coding]');
  assert.equal(await evaluate(get('.pet-idle-speech')+'.hidden'),true);
  await evaluate("rail.querySelector('[data-action=back]').click()"); await paint();
  await evaluate("rail.querySelector('[data-action=back]').click()"); await paint();
};
