const assert = require('node:assert/strict');
const {createServer} = require('node:http');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');

// The fake native port owns the saved language. Loading a new document clears all Sugar JS state.
module.exports = async function checkLanguage({assets, send, evaluate, paint}) {
  const fixture = '<!doctype html><html lang="fr"><meta charset="utf-8"><main aria-label="Conversations">Conversation française</main>';
  const server = createServer((_req, res) => res.writeHead(200, {'Content-Type':'text/html; charset=utf-8'}).end(fixture));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = 'http://127.0.0.1:' + server.address().port;
  const read = name => readFileSync(join(assets,name),'utf8');
  const texts = {zh:['通用','界面语言','任务时常亮'], en:['General','Display language','Screen on during tasks'],
    ja:['一般','表示言語','タスク中は常時点灯']};
  const routes = ['adb','appearance','back','forward','gestures','home','overview','pet','splash'];
  const storage = 'dsh.language';
  async function load(language, round, dimScreenDuringTasks) {
    const target = url + '/?load=' + round;
    await send('Page.navigate',{url:target});
    await evaluate(`new Promise((ok,fail)=>{const until=performance.now()+3000;function next(){
      if(location.href===${JSON.stringify(target)} && document.querySelector('main'))return ok();
      if(performance.now()>until)return fail(Error('Missing DSH fixture'));setTimeout(next,10)}next()})`);
    if (round === 1) await evaluate(`localStorage.setItem(${JSON.stringify(storage)},'fr')`);
    for (const file of ['sugar-i18n.js','sugar-edge-nav.js','sugar-page-motion.js','sugar-pane-deck.js','sugar-splash-preview.js','sugar-splash-settings.js','sugar-mobile-controls.js','sugar-pets.js','sugar-storage.js','sugar-session-storage.js','sugar-workspace-files.js','sugar-recovery-page.js','sugar-shell.js']) await evaluate(read(file));
    const prefs = {language,dimScreenDuringTasks,reducedMotion:true,splash:{showProgress:true,loadingText:'Custom 🐋',backgroundColor:'#FFFFFF'}};
    await evaluate(`SugarShell.mount(${JSON.stringify(prefs)},${JSON.stringify([read('sugar-settings.css'),read('sugar-pets.css'),read('sugar-workspace-files.css')].join('\n'))});
      window.rail=document.querySelector('#sugar-edge-navigation').shadowRoot;
      window.settings=()=>document.querySelector('#sugar-shell-settings').shadowRoot;
      window.writes=[]; window.storageRequests=[]; window.sessionMessages=[]; window.workspaceMessages=[]; window.rootMessages=[]; window.editingSignals=0;
      new Promise(ok=>{const c=new MessageChannel(); window.native=c.port1;
        native.onmessage=({data})=>{const msg=JSON.parse(data);if(msg.type==='state')ok();
          if(msg.type==='state'||msg.type==='sessionStorageQuery'||msg.type==='sessionStorageDelete'||msg.type==='sessionStorageOpen')sessionMessages.push(msg);
          if(msg.type==='settings')window.dimPreference=msg.dimScreenDuringTasks;
          if(msg.type==='editingInteraction')window.editingSignals++;
          if(msg.type==='storageQuery')storageRequests.push(msg);
          if(msg.type.startsWith('workspaceFiles'))workspaceMessages.push(msg);
          if(msg.type.startsWith('root'))rootMessages.push(msg);
          if(msg.type==='recoveryAction')window.recoveryRequested=true;
          if(msg.type==='setLanguage')writes.push(msg.language)};
        postMessage('dsh-sugar/edge-shell','*',[c.port2]);})`);
    if (round === 1) await checkEditingInteraction();
    await evaluate(`SugarEdgeNav.setExpanded(true);rail.querySelector('[data-action=settings]').click()`);
    await paint();
    const overview = "settings().querySelector('[data-pane=overview]')";
    assert.equal(await evaluate(overview+'.hidden'),false);
    assert.equal(await evaluate(overview+".querySelector('[data-language],#sugar-task-screen')===null"),true);
    await evaluate(overview+".querySelector('[data-go=general]').click()");
    await paint();
  }
  async function checkEditingInteraction() {
    await evaluate(`{
      const field=document.createElement('textarea'); field.id='ime-fixture';
      document.body.append(field); field.focus(); field.value='draft';
      field.dispatchEvent(new InputEvent('beforeinput',{bubbles:true}));
      field.dispatchEvent(new CompositionEvent('compositionupdate',{bubbles:true}));
      document.querySelector('main').append(document.createTextNode(''));
      document.dispatchEvent(new Event('scroll'));
    }`);
    await paint();
    assert.equal(await evaluate('editingSignals'),0,'page updates must not reset the screen timer');
    await send('Input.insertText',{text:'x'});
    await send('Input.imeSetComposition',{text:'あ',selectionStart:1,selectionEnd:1});
    await send('Input.insertText',{text:'あ'});
    await paint();
    assert.equal(await evaluate('editingSignals'),1,'the first IME edit should reach Android immediately');
    await evaluate('new Promise(ok=>setTimeout(ok,1100))');
    assert.equal(await evaluate('editingSignals'),2,'the last edit in a typing burst should renew the timer once');
    await evaluate("document.getElementById('ime-fixture').remove()");
  }
  async function state(language) {
    const snapshot = await evaluate(`(() => { const root=settings();
      const general=root.querySelector('[data-pane=general]');
      return {visible:!general.hidden,titles:[general.querySelector('#title-general').textContent,general.querySelector('#sugar-language-label').textContent,general.querySelector('#task-screen-label').textContent],
        choices:[...root.querySelectorAll('[data-language]')].map(b=>[b.dataset.language,b.textContent.trim()]),
        active:[...root.querySelectorAll('[data-language][aria-pressed=true]')].map(b=>b.dataset.language),
        routes:[...rail.querySelectorAll('button[data-action]')].filter(b=>!b.hidden).map(b=>b.dataset.action).sort(),
        dsh:[document.documentElement.lang,document.querySelector('main').textContent,document.querySelector('main').getAttribute('aria-label'),
          localStorage.getItem(${JSON.stringify(storage)})]};})()`);
    assert.ok(snapshot.visible,'changing language keeps General open');
    assert.deepEqual(snapshot.titles,texts[language]);
    assert.deepEqual(snapshot.choices,[['zh','中文'],['en','English'],['ja','日本語']]);
    assert.deepEqual(snapshot.active,[language]);
    // The disabled forward action may be hidden. Language never adds a page action.
    assert.deepEqual(snapshot.routes.filter(id=>id!=='forward'),routes.filter(id=>id!=='forward'));
    assert.deepEqual(snapshot.dsh,['fr','Conversation française','Conversations','fr']);
  }
  async function checkWorkspaceSelection() {
    const result = await evaluate(`(async () => {
      const pane = document.createElement('section');
      pane.innerHTML = SugarWorkspaceFiles.controls;
      const calls = [], listed = [];
      const files = SugarWorkspaceFiles.mount(pane,m => calls.push(m),() => {},(id,path) => listed.push([id,path]));
      const settle = () => new Promise(ok => setTimeout(ok,5));
      const click = name => pane.querySelector('[data-wsf-'+name+']').click();
      files.show('storage-workspace-detail',false,'test');
      await settle();
      const oldToken = calls.at(-1).token;
      files.show('storage-workspace-detail',false,'test','second');
      await settle();
      const token = calls.at(-1).token, size = 78033;
      const rows = Array.from({length:size},(_,i) => ({name:'f'+i,path:'second/f'+i,kind:'file',bytes:i,canDelete:i%3!==1}));
      const reply = {type:'workspaceFiles',id:'test',path:'second',token,success:true,rows};
      files.receive({...reply,path:'',token:oldToken});
      files.receive(reply);
      click('select'); click('all');
      click('entry="second/f78032"'); click('next'); click('entry="second/f78002"');
      click('delete'); await settle();
      const msg = calls.at(-1), bytes = atob(msg.selectionBits);
      const matches = rows.every((row,i) => Boolean(bytes.charCodeAt(i >> 3) & (1 << (i & 7))) ===
        (row.canDelete && i !== 78032 && i !== 78002));
      return {bytes:bytes.length,chars:JSON.stringify(msg).length,count:msg.rowCount,matches,listed};
    })()`);
    assert.equal(result.count,78033);
    assert.equal(result.bytes,Math.ceil(result.count/8));
    assert.ok(result.chars < 262144);
    assert.ok(result.matches,'unsorted source indexes survive paging and compact encoding');
    assert.deepEqual(result.listed,[['test','second']],'delayed listing cannot invalidate history for the current folder');
  }
  async function checkStorage() {
    const find = selector => 'settings().querySelector(' + JSON.stringify(selector) + ')';
    const get = (selector,property='textContent') => evaluate(find(selector) + '.' + property);
    async function click(selector) { await evaluate(find(selector) + '.click()'); await paint(); }
    async function reply(fields) {
      await evaluate(`native.postMessage(JSON.stringify({...storageRequests.at(-1),type:'storageUsage',...${JSON.stringify(fields)}}))`);
      await paint();
    }
    async function search(value) {
      await evaluate(`{const field=${find('[data-storage-search]')};field.value=${JSON.stringify(value)};field.dispatchEvent(new Event('input'));}`);
      await paint();
    }
    const count = () => evaluate('storageRequests.length');
    const names = area => evaluate(`[...settings().querySelectorAll('[data-storage-${area}] .storage-label')].map(n=>n.firstChild.textContent)`);
    const detail = '[data-pane="storage-workspaces"]', summary = '[data-pane="storage"]';
    const sortLabels = {
      zh:['大小','创建时间','修改时间'], en:['Size','Created','Modified'], ja:['サイズ','作成日時','更新日時'],
    };
    const directions = {zh:['降序','升序'],en:['Descending','Ascending'],ja:['降順','昇順']};
    const sortKeys = ['size','createdAt','modifiedAt'];
    const directionButton = '[data-storage-direction]';
    async function setLanguage(language) {
      await evaluate(`native.postMessage(JSON.stringify({type:'languageState',language:${JSON.stringify(language)}}))`);
      await paint();
    }
    async function checkSortControls(language,key,descending) {
      const controls = await evaluate(`[...settings().querySelectorAll('button[data-storage-sort]')].map(b =>
        [b.dataset.storageSort,b.textContent.trim(),b.getAttribute('aria-pressed')])`);
      assert.deepEqual(controls,sortKeys.map((value,index) =>
        [value,sortLabels[language][index],String(value===key)]));
      assert.equal(await evaluate(find(directionButton)+".getAttribute('aria-label')"),directions[language][descending ? 0 : 1]);
    }
    async function checkWorkspaceLink(label) {
      const parts = await evaluate(`(() => {
        const link=${find('[data-go=storage-workspaces]')};
        const text=link.querySelector('span'), arrow=link.querySelector('svg');
        const labelBox=text.getBoundingClientRect(), iconBox=arrow.getBoundingClientRect(), buttonBox=link.getBoundingClientRect();
        return {label:text.textContent, iconWidth:iconBox.width, spacing:iconBox.left-labelBox.right,
          rightInset:buttonBox.right-iconBox.right, centerOffset:(iconBox.top+iconBox.bottom-labelBox.top-labelBox.bottom)/2};
      })()`);
      assert.equal(parts.label,label);
      assert.ok(parts.iconWidth>0 && parts.spacing>=0 && parts.rightInset>=0 && Math.abs(parts.centerOffset)<=1,
        'workspace link alignment: '+JSON.stringify(parts));
    }
    async function readPages() {
      const seen = [];
      for (let page=0;page<4;page++) {
        const shown = await names('details');
        assert.equal(shown.length,page===3 ? 5 : 20);
        assert.equal(await get('[data-storage-page]'),(page+1)+' / 4');
        assert.equal(await get('[data-storage-prev]','disabled'),page===0);
        assert.equal(await get('[data-storage-next]','disabled'),page===3);
        seen.push(...shown);
        if (page<3) await click('[data-storage-next]');
      }
      return seen;
    }
    async function checkNarrowDates(language) {
      const clipped = await evaluate(`(() => {
        const pane=${find('[data-pane=storage-workspaces]')};
        const content=pane.querySelector('.settings-content').getBoundingClientRect();
        const elements=pane.querySelectorAll('[data-storage-sort], [data-storage-direction] svg, .storage-label time, .storage-label small');
        return [...elements].filter(node=>{
          const box=node.getBoundingClientRect();
          if (box.width<=0 || box.left<content.left || box.right>content.right) return true;
          if (node.tagName.toLowerCase()==='svg') {
            const button=node.parentElement.getBoundingClientRect();
            return box.left<button.left || box.right>button.right || box.top<button.top || box.bottom>button.bottom;
          }
          const range=document.createRange();
          range.selectNodeContents(node);
          return [...range.getClientRects()].some(text=>text.left<box.left-1 || text.right>box.right+1 || text.bottom>box.bottom+1);
        }).map(node=>node.outerHTML);
      })()`);
      assert.deepEqual(clipped,[],language+' date sort at 320px');
      const dates = await evaluate(`[...settings().querySelectorAll('[data-storage-details] .storage-label')].map(label=>{
        const time=label.querySelector('time');
        return [label.firstChild.textContent,time?.dateTime, time ? time.textContent : label.querySelector('small:last-child')?.textContent];
      })`);
      const unknown = {zh:'时间未知',en:'Date unknown',ja:'日時不明'};
      for (const [name,iso,display] of dates) {
        const row = usage.rows.find(row=>row.title===name);
        if (row.modifiedAt==null) assert.equal(display,unknown[language]);
        else {
          assert.equal(iso,new Date(row.modifiedAt).toISOString());
          assert.ok(display.length>0);
        }
      }
    }
    const ordered = await evaluate(`[...settings().querySelectorAll('[data-pane="overview"] .links button')].map(n=>n.dataset.go)`);
    assert.ok(ordered.indexOf('mobile-main') >= 0);
    assert.equal(ordered[ordered.indexOf('mobile-main')+1],'adb');
    await click('[data-go=recovery]');
    assert.equal(await get('#title-recovery'),'診断と復旧');
    assert.equal(await evaluate('Boolean(window.recoveryRequested)'),false,'opening the pane starts no maintenance action');
    await evaluate("rail.querySelector('[data-action=back]').click()");
    await paint();
    await click('[data-go=storage]');
    assert.equal(await get('#title-storage'),'ストレージ使用量');
    assert.equal(await evaluate("!!rail.querySelector('[data-action=storage],[data-action=storage-workspaces],[data-action=recovery]')"),false);
    assert.equal(await count(),1);
    assert.equal(await get('[data-storage-refresh]','disabled'),true);
    const day = 86400000, epoch = Date.UTC(2026,0,1);
    const usage = {shared:{bytes:2*1024**2},control:{bytes:1024**2},other:{bytes:0},
      rows:Array.from({length:65},(_,i)=>({id:'w'+i,root:'/workspace/project-'+i,title:'Project '+i,
        createdAt:epoch+((i*7)%65)*day,modifiedAt:epoch+(65+(i*11)%65)*day,external:false,usage:{bytes:(i+1)*1024}}))};
    delete usage.rows[9].createdAt;
    usage.rows[20].createdAt = null;
    delete usage.rows[6].modifiedAt;
    usage.rows[17].modifiedAt = null;
    Object.assign(usage.rows[64],{title:'<b>Phone</b>',external:true,location:'primary:Documents/Phone folder'});
    await reply({usage});
    assert.equal(await get('[data-storage-total]'),'5.1 MiB');
    assert.equal(await get('[data-storage-subtotal]'),'ワークスペース 65 件 · 2.1 MiB');
    const largest = ['<b>Phone</b>','Project 63','Project 62'];
    assert.deepEqual(await names('rows'),largest);
    assert.equal(await evaluate(find('[data-storage-rows]')+".querySelector('b,.storage-phone')===null"),true);
    for (const width of [320,390]) {
      await send('Emulation.setDeviceMetricsOverride',{width,height:844,deviceScaleFactor:1,mobile:false});
      for (const [lang,label] of [['zh','全部工作区'],['en','All workspaces'],['ja','すべてのワークスペース']]) {
        await setLanguage(lang);
        await checkWorkspaceLink(label);
      }
    }
    await click('[data-go=storage-workspaces]');
    assert.equal(await get(detail,'hidden'),false);
    assert.equal(await get('[data-storage-search]','placeholder'),'ワークスペース名またはパス');
    for (const lang of ['zh','en','ja']) {
      await setLanguage(lang);
      await checkSortControls(lang,'size',true);
      if (lang==='zh') assert.equal(await evaluate(find('[data-storage-sort]')+".closest('[role=group]').getAttribute('aria-label')"),'排序方式');
    }
    const sizeOrder = usage.rows.map(p=>p.title);
    assert.deepEqual(await readPages(),[...sizeOrder].reverse());
    await click('[data-storage-prev]');
    assert.equal(await get('[data-storage-page]'),'3 / 4');
    await click(directionButton);
    await checkSortControls('ja','size',false);
    assert.deepEqual(await readPages(),sizeOrder);
    await click(directionButton);
    for (const key of ['createdAt','modifiedAt']) {
      await click('[data-storage-sort='+key+']');
      const known = usage.rows.filter(row=>row[key]!=null).sort((a,b)=>a[key]-b[key]).map(row=>row.title);
      const missing = usage.rows.filter(row=>row[key]==null).map(row=>row.title);
      for (const descending of [true,false]) {
        if (!descending) await click(directionButton);
        await checkSortControls('ja',key,descending);
        const shown = await readPages();
        assert.deepEqual(shown.slice(0,known.length),descending ? [...known].reverse() : known);
        assert.deepEqual(shown.slice(known.length).sort(),[...missing].sort());
      }
      if (key==='createdAt') await click(directionButton);
    }
    await setLanguage('zh');
    await checkSortControls('zh','modifiedAt',false);
    await send('Emulation.setDeviceMetricsOverride',{width:320,height:650,deviceScaleFactor:1,mobile:false});
    for (const lang of ['zh','en','ja']) {
      await setLanguage(lang);
      await checkNarrowDates(lang);
    }
    const projectFour = usage.rows.filter(row=>row.title.startsWith('Project 4')).sort((a,b)=>a.modifiedAt-b.modifiedAt);
    for (const [needle,expected] of [['PROJECT 4',projectFour.map(row=>row.title)],['PROJECT 47',['Project 47']],['/workspace/project-29',['Project 29']],
      ['Documents/Phone',['<b>Phone</b>']],['no such project',[]]]) {
      await search(needle);
      assert.deepEqual(await names('details'),expected);
      assert.equal(await get('[data-storage-page]'),'1 / 1');
    }
    assert.equal(await get('[data-storage-details]'),'一致するワークスペースがありません');
    await setLanguage('en');
    await checkSortControls('en','modifiedAt',false);
    assert.equal(await get('[data-storage-details]'),'No matching workspaces');
    assert.equal(await get('[data-storage-search]','placeholder'),'Workspace name or path');
    await search('Phone');
    assert.equal(await count(),1,'paging, filtering and language changes reuse the scan');
    await click('.storage-phone button');
    assert.equal(await evaluate('storageRequests.at(-1).project'),'w64');
    await reply({});
    assert.ok((await get('[data-storage-details]')).includes('Cannot measure. Check folder access.'));
    await click('.storage-phone button');
    const phoneModified = epoch+400*day;
    await reply({external:{bytes:8192},modifiedAt:phoneModified});
    assert.equal(await get('.storage-phone .storage-size'),'8 KiB');
    assert.equal(await get('[data-storage-details] time','dateTime'),new Date(phoneModified).toISOString());
    await evaluate("rail.querySelector('[data-action=back]').click()");
    await paint();
    assert.equal(await get(summary,'hidden'),false);
    assert.equal(await get('[data-storage-total]'),'5.1 MiB','external storage is separate');
    assert.equal(await get('[data-storage-subtotal]'),'65 workspaces · 2.1 MiB');
    assert.deepEqual(await names('rows'),largest,'overview always shows the largest three');
    await click('[data-go=storage-workspaces]');
    await checkSortControls('en','modifiedAt',false);
    assert.equal(await get('[data-storage-search]','value'),'Phone');
    assert.equal(await get('.storage-phone .storage-size'),'8 KiB');
    await search('');
    assert.equal((await readPages()).at(-3),'<b>Phone</b>','the phone measurement updates its position before unknown dates');
    await evaluate("rail.querySelector('[data-action=back]').click()");
    await paint();
    assert.equal(await count(),3,'parent/child navigation does not rescan');
    await click('[data-storage-refresh]');
    await reply({});
    assert.equal(await get(summary+' [data-storage-status]'),'Could not read storage usage.');
    assert.equal(await get('[data-storage-refresh]','disabled'),false);
    await click('[data-storage-refresh]');
    await reply({usage:{...usage,rows:[]}});
    assert.equal(await get('[data-storage-rows]'),'No workspaces yet');
    assert.equal(await get('[data-storage-total]'),'3 MiB');
    assert.equal(await get('[data-go=storage-workspaces]','hidden'),true);
    await click('[data-go=storage-sessions]');
    const lastRequest = "(sessionMessages.filter(m=>m.type==='sessionStorageQuery'||m.type==='sessionStorageDelete'||m.type==='sessionStorageOpen').at(-1))";
    const sessionRows = [
      {id:'one',title:'<b>Shared</b>',workspace:'aaa',updatedAt:30,imageCount:1,fileCount:2,attachmentBytes:4096,reclaimableBytes:2048,canDelete:true,archived:true},
      {id:'two',title:'Pending',workspace:'bbb',updatedAt:10,imageCount:0,fileCount:0,attachmentBytes:0,reclaimableBytes:0,canDelete:false,active:true},
      {id:'three',title:'Old files',updatedAt:20,imageCount:0,fileCount:1,attachmentBytes:512,reclaimableBytes:512,canDelete:true,attachmentsOnly:true}
    ];
    const sessionReply = async fields => {
      await evaluate(`native.postMessage(JSON.stringify({token:${lastRequest}.token,...${JSON.stringify(fields)}}))`);
      await paint();
    };
    const queryState = await evaluate("sessionMessages.slice(0,sessionMessages.findLastIndex(m=>m.type==='sessionStorageQuery')).filter(m=>m.type==='state').at(-1)");
    assert.equal(queryState.sessionStorage,true,'page authorization must precede the query');
    const detailPane = '[data-pane=storage-session-detail]';
    const listPane = '[data-pane=storage-sessions]';
    const back = async () => {
      await evaluate("rail.querySelector('[data-action=back]').click()");
      await paint();
    };
    const modes = () => evaluate("Array.from(settings().querySelectorAll('[data-session-mode]:not(:disabled)'), b=>b.dataset.sessionMode)");
    const refresh = '[data-session-refresh]';
    const busy = () => evaluate(find(refresh)+".getAttribute('aria-busy')==='true'");
    assert.equal(await busy(),true,'statistics refresh indicates pending query');
    await sessionReply({type:'sessionStorage',rows:sessionRows});
    assert.equal(await busy(),false);
    assert.equal(await evaluate("settings().querySelectorAll('[data-session-group]').length"),3,'groups include unattached files');
    assert.match(await get('[data-session-manage=one]'),/<b>Shared<\/b>/);
    assert.equal(await evaluate(find('[data-session-manage=one]')+".querySelector('b')===null"),true,'conversation names are plain text');

    async function openDetail(id, files = [], totalFiles = files.length, partial = false) {
      await click('[data-session-manage="' + id + '"]');
      assert.equal(await get(detailPane,'hidden'),false);
      assert.equal(await get(listPane,'hidden'),true);
      const query = await evaluate(lastRequest);
      assert.equal(query.id,id);
      assert.equal(query.type,'sessionStorageQuery');
      await sessionReply({type:'sessionStorage',id,files,offset:0,totalFiles,partial,success:true});
    }
    await click('[data-session-manage=one]');
    const previousDetail = await evaluate(lastRequest);
    await back();
    await click('[data-session-filter=active]');
    await click('[data-session-manage=two]');
    assert.equal(await evaluate(lastRequest+'.token'),previousDetail.token,'native reads are serialized');
    await sessionReply({type:'sessionStorage',id:'one',files:[{kind:'file',name:'stale.txt',bytes:700}],offset:0,totalFiles:1,success:true});
    assert.equal(await evaluate(lastRequest+'.id'),'two','returning to another detail queues that conversation');
    assert.doesNotMatch(await get('[data-session-files]'),/stale\.txt/);
    await sessionReply({type:'sessionStorage',id:'two',files:[],offset:0,totalFiles:0,success:true});
    assert.deepEqual(await modes(),[],'running conversations can be inspected without exposing delete actions');
    await back();
    await click('[data-session-filter=attachments]');
    await openDetail('three',[{kind:'file',name:'notes.txt',bytes:512,shared:false}]);
    assert.deepEqual(await modes(),['attachments']);
    await back();
    await click('[data-session-filter=archived]');
    const imageId = '1'.repeat(64), fileId = '2'.repeat(64);
    const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/0b0AAAAASUVORK5CYII=';
    const files = Array.from({length:24},(_,i)=>({kind:i===0?'image':'file',name:i===0?'<b>scene.png</b>':'document-'+i+'.txt',bytes:128,shared:i===0}));
    Object.assign(files[0],{fileId:imageId,thumbnail:png});
    Object.assign(files[1],{fileId,thumbnail:'https://test.invalid/private'});
    await openDetail('one',files,25);
    assert.match(await get('[data-session-files]'),/<b>scene.png<\/b>/);
    assert.equal(await evaluate(find('[data-session-files]')+".querySelector('b')===null"),true,'attachment names are plain text');
    assert.equal(await evaluate(find('[data-session-files]')+".querySelectorAll('img').length"),1,'thumbnails are limited to inline native image data');
    assert.equal(await get('[data-session-files] img','loading'),'lazy');
    assert.equal(await get('#sugar-session-cleanup-panel','hidden'),true,'cleanup is collapsed on entry');
    assert.equal(await evaluate(find('[data-session-choices]')+'.compareDocumentPosition('+find('[data-session-files]')+') & Node.DOCUMENT_POSITION_FOLLOWING'),4,'cleanup precedes the file list');
    await click('[data-session-open="'+imageId+'"]');
    assert.deepEqual(await evaluate('[ '+lastRequest+'.type,'+lastRequest+'.id,'+lastRequest+'.fileId ]'),['sessionStorageOpen','one',imageId]);
    await sessionReply({type:'sessionStorageOpened',success:true,id:'one',fileId:imageId});
    await click('[data-session-open="'+fileId+'"]');
    await sessionReply({type:'sessionStorageOpened',success:false,id:'one',fileId});
    assert.match(await get('[data-session-detail-status]'),/Could not open/);
    await evaluate(find('[data-session-files] img')+".dispatchEvent(new Event('error'))");
    assert.equal(await evaluate(find('[data-session-files]')+".querySelectorAll('img').length"),0,'failed thumbnails fall back to icons');
    await click('[data-session-file-next]');
    const secondPage = await evaluate(lastRequest);
    assert.deepEqual([secondPage.id,secondPage.offset],['one',24]);
    await sessionReply({type:'sessionStorage',id:'one',offset:24,totalFiles:25,files:[{kind:'file',name:'last.txt',bytes:20,shared:false}],success:true});
    assert.match(await get('[data-session-files]'),/last\.txt/);
    assert.equal(await evaluate(find('[data-session-files]')+".querySelectorAll('[data-session-open],img').length"),0,'previous page images and actions are released');
    assert.deepEqual((await modes()).sort(),['attachments','both','conversation']);

    await click('[data-session-cleanup]');
    assert.equal(await get('#sugar-session-cleanup-panel','hidden'),false);
    await click('[data-session-mode=both]');
    const firstRemove = await evaluate(lastRequest);
    assert.deepEqual([firstRemove.type,firstRemove.id,firstRemove.mode],['sessionStorageDelete','one','both']);
    await sessionReply({type:'sessionStorageResult',success:false,cancelled:true});
    assert.equal(await get('[data-session-mode=both]','disabled'),false,'canceling native confirmation preserves the detail');
    await click('[data-session-mode=attachments]');
    await sessionReply({type:'sessionStorageResult',id:'one',success:true});
    assert.equal(await evaluate(lastRequest+'.type'),'sessionStorageQuery','successful cleanup refreshes inventory');
    await sessionReply({type:'sessionStorage',rows:[{...sessionRows[0],imageCount:0,fileCount:0,attachmentBytes:0,reclaimableBytes:0}]});
    if (!await get(detailPane,'hidden')) {
      if (await evaluate(lastRequest+'.id')==='one') await sessionReply({type:'sessionStorage',id:'one',files:[],offset:0,totalFiles:0,success:true});
      await back();
    }
    assert.equal(await get(listPane,'hidden'),false);
    assert.match(await get('[data-session-manage=one]'),/<b>Shared<\/b>/,'attachment cleanup keeps the conversation');
    await click(refresh);
    assert.equal(await busy(),true);
    await sessionReply({type:'sessionStorage',rows:sessionRows,partial:true});
    await openDetail('one',[],0,true);
    assert.deepEqual(await modes(),[],'incomplete inventories allow inspection only');
    await back();
    await back();
    assert.equal(await get(summary,'hidden'),false);
    assert.equal(await evaluate("sessionMessages.filter(m=>m.type==='state').at(-1).sessionStorage"),false);
    await click('[data-storage-refresh]');
    await reply({usage});
    await click('[data-go=storage-workspaces]');
    await search('Project 4');
    await click('[data-storage-details] [data-storage-project="w47"]');
    const filePane = '[data-pane=storage-workspace-detail]';
    const latestFileRequest = () => evaluate('workspaceMessages.at(-1)');
    const fileReply = async (type,fields) => {
      const req = await latestFileRequest();
      await evaluate(`native.postMessage(JSON.stringify(${JSON.stringify({type,token:req.token,id:req.id,path:req.path,...fields})}))`);
      await paint();
    };
    assert.equal(await get(filePane,'hidden'),false);
    assert.equal((await latestFileRequest()).id,'w47');
    assert.equal((await latestFileRequest()).path,'');
    const directory = {path:'docs',name:'docs',kind:'directory',canDelete:true};
    const note = {path:'info.txt',name:'info.txt',kind:'file',bytes:520,createdAt:1000,modifiedAt:2000,canDelete:true};
    const readonly = {path:'locked',name:'locked',kind:'link',bytes:16,createdAt:1500,canDelete:false};
    const list = () => fileReply('workspaceFiles',{success:true,title:'Project 47',rows:[directory,note,readonly]});
    await list();
    assert.match(await get('[data-wsf-path]'),/Project 47/);
    assert.equal(await get('[data-wsf-rows] button','dataset.wsfEntry'),'info.txt');
    await click('[data-wsf-sort=createdAt]'); await click('[data-wsf-direction]');
    assert.equal(await get('[data-wsf-rows] button','dataset.wsfEntry'),'info.txt','files sort by creation time and direction');
    await click('[data-wsf-entry="info.txt"]');
    const open = await latestFileRequest();
    assert.deepEqual([open.type,open.entry],['workspaceFilesOpen','info.txt']);
    await fileReply('workspaceFilesOpened',{success:true});
    await click('[data-wsf-select]');
    await click('[data-wsf-all]');
    assert.equal(await get('[data-wsf-entry="locked"]','disabled'),true);
    assert.equal(await get('[data-wsf-clear]','disabled'),true);
    await click('[data-wsf-delete]');
    const deletion = await latestFileRequest();
    assert.deepEqual([deletion.type,deletion.all,deletion.clear],['workspaceFilesDelete',true,false]);
    assert.equal(deletion.indexes,undefined,'select all operates on eligible entries from the listing');
    await fileReply('workspaceFilesResult',{cancelled:true,success:false});
    assert.equal(await get('[data-wsf-entry="docs"]','ariaPressed'),'true');
    await click('[data-wsf-entry="docs"]');
    await click('[data-wsf-delete]');
    assert.deepEqual((await latestFileRequest()).indexes,[1],'small selections keep original row indexes after sorting');
    await fileReply('workspaceFilesResult',{cancelled:true,success:false});
    await click('[data-wsf-select]');
    await click('[data-wsf-entry="docs"]');
    assert.equal((await latestFileRequest()).path,'docs');
    await fileReply('workspaceFiles',{success:true,title:'Project 47',rows:[{...note,path:'docs/info.txt'}]});
    await click('[data-wsf-select]'); await click('[data-wsf-clear]');
    const clearFolder = await latestFileRequest();
    assert.deepEqual([clearFolder.path,clearFolder.clear,clearFolder.indexes,clearFolder.paths],['docs',true,undefined,undefined]);
    await fileReply('workspaceFilesResult',{success:true});
    assert.equal((await latestFileRequest()).type,'workspaceFilesQuery');
    await fileReply('workspaceFiles',{success:true,title:'Project 47',rows:[]});
    assert.match(await get('[data-wsf-status]'),/empty/i);
    await back();
    assert.equal((await latestFileRequest()).path,'','back visits the parent folder');
    await list();
    await back();
    assert.equal(await get(detail,'hidden'),false);
    assert.equal(await get('[data-storage-search]','value'),'Project 4');
    await reply({usage});
    await send('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:false});
  }
  async function checkRootSettings() {
    await evaluate("settings().querySelector('[data-go=adb]').click()");
    await paint();
    const query = selector => `settings().querySelector(${JSON.stringify(selector)})`;
    const reply = async state => {
      await evaluate(`native.postMessage(JSON.stringify(${JSON.stringify({type:'rootState',...state})}))`);
      await paint();
    };
    const click = async selector => {
      const center = await evaluate(`(() => { const element=${query(selector)}; element.scrollIntoView({block:'nearest'});
        const box=element.getBoundingClientRect(); return {x:box.x+box.width/2,y:box.y+box.height/2}; })()`);
      await send('Input.dispatchMouseEvent',{type:'mousePressed',button:'left',clickCount:1,...center});
      await send('Input.dispatchMouseEvent',{type:'mouseReleased',button:'left',clickCount:1,...center});
      await paint();
    };
    const writes = () => evaluate("rootMessages.filter(message=>message.type!=='rootStatus')");
    const switchState = () => evaluate(query('[data-root-enable]')+".getAttribute('aria-checked')");
    const summary = () => evaluate(query('[data-root-status]')+'.textContent');
    await reply({enabled:false,available:false,busy:false,code:'disabled'});
    assert.equal(await switchState(),'false');
    assert.equal(await evaluate(query('.root-options')+'.open'),false,'optional Root settings start collapsed');
    assert.equal(await evaluate(query('.adb-controls')+'.nextElementSibling.matches(".root-options")'),true,'wireless ADB comes first');
    await click('.root-options > summary');
    await click('.root-options > summary');
    assert.equal(await switchState(),'false','collapsing Root preserves its setting');
    assert.deepEqual(await writes(),[],'opening and closing Root settings never requests authority');
    await click('.root-options > summary');
    await evaluate(query('[data-root-enable]')+'.click()'); await paint();
    assert.deepEqual(await writes(),[],'page-generated clicks cannot enable host Root');
    await click('[data-root-enable]');
    assert.deepEqual((await writes()).at(-1),{type:'rootSetEnabled',enabled:true});
    assert.equal(await switchState(),'true');
    assert.match(await summary(),/承認を待っています/,'enabling Root waits for native authorization');
    await reply({enabled:true,available:false,busy:true,code:'checking'});
    assert.equal(await evaluate(query('[data-root-check]')+'.disabled'),true);
    await click('[data-root-enable]');
    assert.deepEqual((await writes()).at(-1),{type:'rootSetEnabled',enabled:false},'Root can be disabled while awaiting authorization');
    await reply({enabled:false,available:false,busy:false,code:'disabled'});
    await click('[data-root-enable]');
    await reply({enabled:true,available:false,busy:false,code:'unavailable'});
    assert.match(await summary(),/利用できません/);
    await click('[data-root-check]');
    assert.deepEqual((await writes()).at(-1),{type:'rootProbe'});
    await reply({enabled:true,available:true,busy:false,code:'ready'});
    for (const [language,text] of [['zh','Sugar 已获 Root 授权'],['en','Sugar has Root access'],['ja','Sugar に Root 権限があります']]) {
      await evaluate(`native.postMessage(JSON.stringify({type:'languageState',language:'${language}'}))`);
      await paint();
      assert.equal(await summary(),text);
    }
    await reply({enabled:true,available:false,busy:false,code:'disconnected'});
    assert.match(await summary(),/再確認/,'lost root access updates the visible state');
    assert.equal(await evaluate(query('[data-root-check]')+'.disabled'),false);
    await reply({enabled:true,available:false,busy:false,code:'ready'});
    assert.match(await summary(),/利用できません/,'unavailable always wins over a stale ready label');
    assert.equal((await writes()).length,4,'state updates and language changes never request new authority');
    await evaluate("rail.querySelector('[data-action=back]').click()");
    await paint();
  }
  try {
    await load(undefined,1);
    await state('zh');
    const screenSwitch = "settings().querySelector('#sugar-task-screen')";
    assert.equal(await evaluate(screenSwitch+".getAttribute('aria-checked')"),'true');
    await evaluate(screenSwitch+'.click()'); await paint();
    const storedDim = await evaluate('window.dimPreference');
    assert.equal(storedDim,false,'disabling task dimming reaches the native settings bridge');
    for (const language of ['en','ja']) {
      await evaluate(`settings().querySelector('[data-language=${language}]').click()`);
      await paint();
      await state(language);
      assert.equal(await evaluate('writes.at(-1)'),language,'selection reaches the native preference');
      await evaluate(`native.postMessage(JSON.stringify({type:'languageState',language:'${language}'}))`);
      await paint();
    }
    await evaluate("rail.querySelector('[data-action=back]').click()");
    await paint();
    assert.equal(await evaluate("settings().querySelector('[data-pane=overview]').hidden"),false,'Back from General returns to overview');
    await checkRootSettings();
    await checkStorage();
    await checkWorkspaceSelection();
    const writes = await evaluate('writes');
    assert.deepEqual(writes,['en','ja'],'native acknowledgements never rewrite the preference');
    await load(writes.at(-1),2,storedDim);
    assert.equal(await evaluate(screenSwitch+".getAttribute('aria-checked')"),'false');
    await state('ja');
    await evaluate(`native.postMessage(JSON.stringify({type:'languageState',language:'en'}))`);
    await paint();
    await state('en');
    await evaluate(`settings().querySelector('[data-language=zh]').click()`);
    await paint();
    await state('zh');
    assert.deepEqual(await evaluate('writes'),['zh']);
    await evaluate("rail.querySelector('[data-action=back]').click()");
    await paint();
    await require('./check-pet-speech.cjs')({send,evaluate,paint});
    await evaluate("settings().querySelector('[data-go=mobile-main]').click()");
    await paint();
    assert.equal(await evaluate("settings().querySelector('[data-pane=mobile-main]').hidden"), false);
    await evaluate("native.postMessage(JSON.stringify({type:'mobileSettings',mainEnabled:true,virtualEnabled:false,showTouches:true,accessibilityEnabled:false,adbConnected:false}))");
    await paint();
    assert.equal(await evaluate("settings().querySelector('[data-mobile-pref=touches]').getAttribute('aria-checked')"), 'true');
    assert.equal(await evaluate("settings().querySelector('[data-mobile-grant]').textContent"), '未开启');
    assert.equal(await evaluate("rail.querySelector('[data-action=mobile-main]').hidden || rail.querySelector('[data-action=mobile-virtual]').hidden"), false);
    await evaluate("rail.querySelector('[data-action=mobile-virtual]').click()");
    await paint();
    assert.equal(await evaluate("settings().querySelector('[data-pane=mobile-virtual]').hidden"), false);
  } finally {
    await new Promise(resolve=>{server.close(resolve);server.closeAllConnections()});
  }
};
