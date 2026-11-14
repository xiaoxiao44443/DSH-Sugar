(() => {
  'use strict';
  const i18n = window.SugarI18n;
  i18n.add({
    '桌宠':['Desktop pet','デスクトップペット'],
    '显示桌宠':['Show desktop pet','デスクトップペットを表示'],
    '宠物':['Pets','ペット'],
    '放大预览':['Expand preview','拡大プレビュー'],
    '返回':['Back','戻る'],
    '返回桌宠':['Back to desktop pet','ペット設定に戻る'],
    '点气泡打开对话':['Tap a bubble to open its chat','吹き出しをタップして会話を開く'],
    '仅对关联对话的气泡生效。点空闲气泡会收起。':['Applies to bubbles linked to a chat. Tapping an idle bubble dismisses it.','会話に紐づく吹き出しが対象です。待機中の吹き出しはタップで閉じます。'],
    '角色大小':['Character size','キャラクターのサイズ'],
    '动作预览':['Animation preview','アニメーションプレビュー'],
    '回答便签':['Answer note','回答メモ'],
    '看手机':['Phone','スマホを見る'], '写代码':['Coding','コーディング'], '思考':['Thinking','考え中'],
    '无所事事':['Idle','のんびり'], '询问':['Asking','質問'], '工作中':['Working','作業中'],
    '空闲台词':['Idle lines','待機中のセリフ'], '自动闲聊':['Idle chatter','ひとりごと'], '戳一戳':['Poke','つつく'],
    '换一句':['Next line','次のセリフ'],
    '气泡台词':['Bubble lines','吹き出しのセリフ'],
    '快捷回复':['Quick replies','定型返信'],
    '双击桌宠，可从便签中选择一句回复。':['Double-tap your pet to choose a reply from its note.','ペットをダブルタップすると、メモから返事を選べます。'],
    '语音回复':['Voice replies','音声で返信'],
    '正在检查语音状态…':['Checking voice replies…','音声入力の状態を確認中…'],
    '已就绪。点便签上的麦克风即可录音。':['Ready. Tap the microphone on the note to record a reply.','準備完了。メモのマイクをタップすると返信を録音できます。'],
    '尚未安装「语音输入」插件。':['The Voice input plugin is not installed.','「音声入力」プラグインがインストールされていません。'],
    '需先启用「语音输入」插件。':['Enable the Voice input plugin to use voice replies.','「音声入力」プラグインを有効にしてください。'],
    '请选择本地语音模型，然后点「下载并准备」。':['Choose a local speech model, then tap “Download and prepare”.','ローカル音声認識モデルを選び、「ダウンロードして準備」をタップしてください。'],
    '正在准备本地语音模型…':['Preparing the local speech model…','ローカル音声認識モデルを準備中…'],
    '语音回复暂不可用。点此查看设置。':['Voice replies are unavailable. Tap to check settings.','現在、音声で返信できません。タップして設定を確認できます。'],
    '请从 DSH 侧栏打开「插件」，选择「语音输入」。':['Open Plugins in the DSH sidebar and select Voice input.','DSH のサイドバーで「プラグイン」を開き、「音声入力」を選んでください。'],
    '每行一句。留空保存可清空列表。':['Enter one phrase per line. Save an empty list to clear it.','1行に1件ずつ入力します。空欄で保存すると一覧を消去できます。'],
    '恢复默认':['Restore defaults','初期値に戻す'], '保存':['Save','保存'], '取消':['Cancel','キャンセル'],
    '没有可用的宠物。':['No pets available.','利用できるペットがありません。'],
    '无法播放这段动画。':['This animation could not be played.','アニメーションを再生できません。'],
    '请在系统设置中允许悬浮窗。':['Allow Sugar to display over other apps in system settings.','システム設定で他のアプリの上に重ねて表示することを許可してください。'],
    '暂时无法打开系统悬浮窗设置。':['Could not open overlay settings.','オーバーレイの設定を開けませんでした。'],
    '台词太长，请缩短后再保存。':['Shorten the lines, then save again.','セリフを短くしてから保存してください。'],
    '回复列表太长，请缩短后再保存。':['Shorten the replies, then save again.','定型返信を短くしてから保存してください。'],
  });
  const poses = [
    ['phone','看手机'], ['coding','写代码'], ['thinking','思考'],
    ['idle','无所事事'], ['asking','询问'], ['working','工作中'],
  ];
  const voiceHints = new Map([
    ['loading','正在检查语音状态…'],
    ['ready','已就绪。点便签上的麦克风即可录音。'],
    ['not-installed','尚未安装「语音输入」插件。'],
    ['disabled','需先启用「语音输入」插件。'],
    ['model-needed','请选择本地语音模型，然后点「下载并准备」。'],
    ['preparing','正在准备本地语音模型…'],
    ['unavailable','语音回复暂不可用。点此查看设置。'],
  ]);
  const asset = (id, pose, ext) => '/assets/sugar/pets/' + encodeURIComponent(id) + '/' + pose + '.' + ext;
  const icon = path => '<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">' + path + '</svg>';
  const expand = icon('<path d="M14 5h5v5m0-5-6 6M10 19H5v-5m0 5 6-6"/>');
  const next = icon('<path d="m11 5 7 7-7 7M6 6l6 6-6 6"/>');
  const pencil = icon('<path d="m15 5 4 4M4 20l4-1L20 7l-4-4L4.8 15z"/>');
  const controls = {
    previewBack: `<button type="button" class="pet-preview-back" data-go="back" aria-label="返回桌宠">${icon('<path d="m13 6-6 6 6 6"/>')}<span>返回</span></button>`,
    pet: `<div class="pet-settings">
      <div class="row pet-enable-row"><span id="sugar-pet-enable-label">显示桌宠</span>
        <button type="button" role="switch" aria-checked="false" aria-labelledby="sugar-pet-enable-label" data-pet-enabled disabled></button></div>
      <p hidden role="status" class="pet-hint" data-pet-permission></p>
      <div class="pet-section-head"><h2>宠物</h2><button type="button" data-pet-expand disabled>${expand}<span>放大预览</span></button></div>
      <div class="pet-gallery" role="group" aria-label="宠物"></div><p class="pet-hint" hidden data-pet-empty>没有可用的宠物。</p>
      <div class="slider-row pet-size-row"><label for="sugar-pet-size">角色大小</label><output for="sugar-pet-size"></output>
        <input id="sugar-pet-size" data-pet-size type="range" min="64" max="192" step="1" disabled></div>
      <div class="row pet-bubble-tap-row"><span><span id="sugar-pet-bubble-tap-label">点气泡打开对话</span>
        <small id="sugar-pet-bubble-tap-hint">仅对关联对话的气泡生效。点空闲气泡会收起。</small></span>
        <button type="button" role="switch" aria-checked="false" aria-labelledby="sugar-pet-bubble-tap-label" aria-describedby="sugar-pet-bubble-tap-hint" data-pet-bubble-tap disabled></button></div>
      <button type="button" class="row pet-reply-row pet-quick-toggle" data-pet-quick aria-expanded="false" aria-controls="sugar-pet-editor" disabled>
        <span><span id="sugar-pet-quick-label">快捷回复</span><small>双击桌宠，可从便签中选择一句回复。</small></span>
        ${icon('<path d="m6 9 6 6 6-6"/>')}</button>
      <div data-pet-quick-slot></div>
      <button type="button" class="row pet-reply-row" data-pet-voice-settings aria-labelledby="sugar-pet-voice-label" aria-describedby="sugar-pet-voice-status">
        <span><span id="sugar-pet-voice-label">语音回复</span><small id="sugar-pet-voice-status" role="status"></small></span>
        ${icon('<path d="m9 6 6 6-6 6"/>')}</button>
    </div>`,
    preview: `<div class="pet-preview" data-tone="phone">
      <div class="pet-stage">
        <div class="pet-bubble-slot"><div class="pet-bubble" data-pet-bubble hidden>
          <span class="pet-bubble-width" aria-hidden="true"></span><span class="pet-bubble-clip" aria-hidden="true"><span class="pet-bubble-ink"></span></span>
        </div></div>
        <img data-pet-actor alt="" draggable="false"><p hidden class="pet-hint" data-pet-failure>无法播放这段动画。</p>
      </div>
      <div class="pet-poses" role="group" aria-label="动作预览">${poses.map(([id,label]) =>
        `<button type="button" class="pet-pose" data-pet-state="${id}" data-tone="${id}" disabled>
          <img alt="" draggable="false"><span>${label}</span></button>`).join('')}</div>
      <div class="pet-idle-speech" role="group" aria-label="空闲台词" hidden>
        <button type="button" data-pet-idle-speech="idle" aria-pressed="true">自动闲聊</button>
        <button type="button" data-pet-idle-speech="poke" aria-pressed="false">戳一戳</button></div>
      <div class="pet-speech-actions"><button type="button" data-pet-edit aria-expanded="false" aria-controls="sugar-pet-editor" disabled>${pencil}<span id="sugar-pet-speech-label">气泡台词</span></button>
        <button type="button" data-pet-answer-preview hidden><span>回答便签</span></button>
        <button type="button" data-pet-next disabled>${next}<span>换一句</span></button></div>
      <div class="pet-speech-editor" id="sugar-pet-editor" hidden>
        <label for="sugar-pet-lines" class="pet-hint">每行一句。留空保存可清空列表。</label>
        <textarea id="sugar-pet-lines" rows="3" data-pet-lines aria-labelledby="sugar-pet-speech-label"></textarea>
        <p role="alert" class="pet-hint" data-pet-error hidden></p>
        <div class="pet-editor-actions"><button type="button" data-pet-reset>恢复默认</button>
          <button type="button" data-pet-cancel>取消</button><button type="button" data-pet-save>保存</button></div>
      </div>
    </div>`,
  };

  function mount(main, initial, post, go) {
    const $ = selector => main.querySelector(selector);
    const gallery = $('.pet-gallery'), preview = $('.pet-preview'), actor = $('[data-pet-actor]');
    const cards = new Map(), drafts = new Map(), poseButtons = [...preview.querySelectorAll('[data-pet-state]')];
    const enabled = $('[data-pet-enabled]'), size = $('[data-pet-size]'), sizeValue = $('output[for=sugar-pet-size]');
    const bubbleTap = $('[data-pet-bubble-tap]');
    const idleGroup = $('.pet-idle-speech'), idleButtons = [...idleGroup.querySelectorAll('button')];
    const editor = $('.pet-speech-editor'), lines = $('[data-pet-lines]'), issue = $('[data-pet-error]');
    const quick = $('[data-pet-quick]'), editSpeech = $('[data-pet-edit]');
    const voiceStatus = $('#sugar-pet-voice-status');
    const quickCategory = 'quick-replies';
    const bubble = $('[data-pet-bubble]'), measure = $('.pet-bubble-width'), viewport = $('.pet-bubble-clip'), ink = $('.pet-bubble-ink');
    const reducedMotion = () => matchMedia('(prefers-reduced-motion: reduce)').matches || main.getRootNode().host?.hasAttribute('data-reduced');
    const events = new AbortController();
    const listen = (target, name, action) => target.addEventListener(name, action, {signal:events.signal});
    const t = key => i18n.text(key);
    let data = initial, route = null, state = 'phone', idleSpeech = 'idle', playing, reelTimer, reelGeneration = 0, pendingPack;
    let activeLine, contentsKey, typeTimer, scrollMotion, permissionError, pendingEditor, pendingSize, sizeTimer;
    let clipWidth = -1, draftKey = null, actorKey = null, disposed = false;
    let galleryLayout = '', revealSelection = false;
    let voiceState = 'loading', awaitingPetState = false, showVoicePath = false, voiceRetry;
    function paintVoiceStatus() {
      let hint = t(voiceHints.get(voiceState) ?? voiceHints.get('unavailable'));
      if (showVoicePath) hint += '\n' + t('请从 DSH 侧栏打开「插件」，选择「语音输入」。');
      if (voiceStatus.textContent !== hint) voiceStatus.textContent = hint;
    }
    const content = gallery.closest('.settings-content');
    const landscape = matchMedia('(orientation: landscape)');
    function layoutGallery() {
      if (route !== 'pet' || !data?.packs?.length || cards.size !== data.packs.length || !gallery.clientWidth) return;
      const style = getComputedStyle(gallery);
      const width = gallery.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
      if (width <= 0) return;
      const gap = parseFloat(style.columnGap);
      const columns = Math.max(1, Math.floor((width + gap) / (88 + gap)));
      const rowLimit = landscape.matches && (content?.clientHeight || innerHeight) < 460 ? 2 : 3;
      const rows = Math.min(rowLimit, Math.ceil(cards.size / columns));
      const perPage = columns * rows;
      const peek = cards.size > perPage ? Math.min(22, width * .075) : 0;
      const cardWidth = Math.min(136, (width - peek - (columns - 1) * gap) / columns);
      const key = [width,columns,rows,...data.packs.map(p => p.id)].join('/');
      if (key !== galleryLayout) {
        galleryLayout = key;
        gallery.style.setProperty('--pet-card',cardWidth + 'px');
        data.packs.forEach(({id}, index) => {
          const withinPage = index % perPage;
          const card = cards.get(id);
          // Keep each screen in reading order before continuing horizontally.
          card.style.gridRow = 1 + Math.floor(withinPage / columns);
          card.style.gridColumn = 1 + Math.floor(index / perPage) * columns + withinPage % columns;
        });
      }
      if (!revealSelection) return;
      const selected = cards.get(data.selected);
      if (!selected) return;
      revealSelection = false;
      const left = selected.offsetLeft - parseFloat(style.paddingLeft);
      const right = selected.offsetLeft + selected.offsetWidth + parseFloat(style.paddingRight);
      if (left < gallery.scrollLeft) gallery.scrollLeft = left;
      else if (right > gallery.scrollLeft + gallery.clientWidth) gallery.scrollLeft = right - gallery.clientWidth;
    }
    listen(gallery,'pointerdown',() => { revealSelection = false; });
    listen(gallery,'wheel',() => { revealSelection = false; });
    function setImage(image, url) {
      if (image.getAttribute('src') !== url) image.src = url;
    }
    function stopReel() {
      clearTimeout(reelTimer);
      reelGeneration++;
      if (playing) {
        playing.removeAttribute('data-playing');
        playing.querySelector('.pet-reel').removeAttribute('src');
        playing = null;
      }
    }
    async function playReel(button) {
      stopReel();
      if (!button || route !== 'pet' || document.hidden) return;
      playing = button;
      const reel = button.querySelector('.pet-reel'), id = button.dataset.petPack;
      const generation = reelGeneration;
      // Leave the contact sheet in place until the same six local stills are decoded.
      try { await Promise.all([...button.querySelectorAll('img[data-pose]')].map(img => img.decode())); }
      catch { if (generation === reelGeneration) stopReel(); return; }
      if (generation !== reelGeneration) return;
      const frameDuration = reducedMotion() ? 600 : 430;
      let index = 0;
      function advance() {
        if (index === poses.length) { stopReel(); return; }
        const pose = poses[index++][0];
        button.dataset.playing = pose;
        setImage(reel, asset(id, pose, 'png'));
        reelTimer = setTimeout(advance, frameDuration);
      }
      advance();
    }
    function syncGallery() {
      for (const [id,card] of cards) if (!data.packs.some(pack => pack.id === id)) {
        if (playing === card) stopReel();
        card.remove(); cards.delete(id);
      }
      for (const pack of data.packs) {
        let button = cards.get(pack.id);
        if (!button) {
          button = document.createElement('button'); button.type = 'button';
          button.className = 'pet-pack'; button.dataset.petPack = pack.id;
          const sheet = document.createElement('span'); sheet.className = 'pet-sheet';
          for (const [pose] of poses) {
            const cell = document.createElement('span'); cell.dataset.tone = pose;
            const poster = document.createElement('img');
            poster.alt = ''; poster.draggable = false; poster.dataset.pose = pose;
            poster.src = asset(pack.id, pose, 'png'); cell.append(poster); sheet.append(cell);
          }
          const reel = document.createElement('img'); reel.className = 'pet-reel'; reel.alt = ''; reel.draggable = false;
          button.append(sheet, reel); gallery.append(button); cards.set(pack.id, button);
        }
        button.setAttribute('aria-label', pack.name || pack.id);
        button.setAttribute('aria-pressed', String(data.selected === pack.id));
      }
      layoutGallery();
    }
    function stopText() {
      clearTimeout(typeTimer);
      scrollMotion?.cancel(); scrollMotion = null;
      ink.style.transform = '';
    }
    function pan() {
      scrollMotion?.cancel(); scrollMotion = null;
      if (document.hidden || route !== 'pet-preview' || bubble.hidden || !activeLine || activeLine !== ink.textContent) return;
      const width = Math.max(0, ink.offsetWidth - viewport.clientWidth);
      ink.style.transform = '';
      if (!width || reducedMotion()) { viewport.scrollLeft = 0; return; }
      const travel = width * 1000 / 35, hold = 950, duration = travel + 2 * hold;
      const atEnd = 'translateX(-' + width + 'px)';
      scrollMotion = ink.animate([
        {transform:atEnd}, {transform:atEnd,offset:hold/duration}, {transform:'none',offset:1-hold/duration}, {transform:'none'},
      ], {duration,iterations:Infinity,direction:'alternate',easing:'linear'});
    }
    function showLine(value) {
      stopText();
      activeLine = value || '';
      measure.textContent = activeLine; ink.textContent = '';
      bubble.hidden = !activeLine; bubble.setAttribute('aria-label', activeLine);
      if (!activeLine || document.hidden || route !== 'pet-preview') return;
      if (reducedMotion()) { ink.textContent = activeLine; return; }
      bubble.animate([{opacity:0,transform:'translateY(3px) scale(.97)'},{opacity:1,transform:'none'}],{duration:150,easing:'ease-out'});
      const chars = typeof Intl.Segmenter === 'function' ? Array.from(new Intl.Segmenter().segment(activeLine), s => s.segment) : Array.from(activeLine);
      let index = 0;
      function type() {
        ink.textContent += chars[index++];
        ink.style.transform = 'translateX(-' + Math.max(0, ink.offsetWidth - viewport.clientWidth) + 'px)';
        if (index < chars.length) typeTimer = setTimeout(type, 48);
        else pan();
      }
      typeTimer = setTimeout(type,150);
    }
    const resize = new ResizeObserver(() => {
      if (viewport.clientWidth !== clipWidth) { clipWidth = viewport.clientWidth; pan(); }
      layoutGallery();
    });
    resize.observe(viewport);
    resize.observe(gallery);
    if (content) resize.observe(content);
    listen(window,'resize',layoutGallery);
    function speechCategory() { return state === 'idle' ? idleSpeech : state; }
    function choices() { return data?.speech?.[speechCategory()] || []; }
    function chooseLine() {
      const options = choices();
      const fresh = options.filter(value => value !== activeLine);
      const pool = fresh.length ? fresh : options;
      showLine(pool.length ? pool[Math.floor(Math.random()*pool.length)] : '');
    }
    function closeEditor(remember) {
      if (!editor.hidden && draftKey && remember) drafts.set(draftKey, lines.value);
      editor.hidden = true; draftKey = null; issue.hidden = true;
      quick.setAttribute('aria-expanded','false');
      editSpeech.setAttribute('aria-expanded','false');
    }
    function openEditor(category) {
      if (!editor.hidden) closeEditor(true);
      const isQuick = category === quickCategory;
      (isQuick ? $('[data-pet-quick-slot]') : preview).append(editor);
      (isQuick ? quick : editSpeech).setAttribute('aria-expanded','true');
      lines.setAttribute('aria-labelledby',isQuick ? 'sugar-pet-quick-label' : 'sugar-pet-speech-label');
      draftKey = category; editor.hidden = false;
      lines.value = drafts.get(category) ?? (data.speech?.[category] || []).join('\n');
      lines.focus({preventScroll:true});
      editor.scrollIntoView({block:'nearest'});
    }
    function editorLengthError() {
      issue.textContent = t(draftKey === quickCategory ? '回复列表太长，请缩短后再保存。' : '台词太长，请缩短后再保存。');
      issue.hidden = false;
    }
    function paintPreview() {
      const id = data.selected;
      const pack = data.packs.find(pack => pack.id === id);
      const hasPack = Boolean(pack);
      bubble.toggleAttribute('data-custom-frame', Boolean(pack?.customBubble));
      if (pack?.customBubble) bubble.style.setProperty('--pet-bubble-image', 'url("' + asset(id,'bubble','png') + '")');
      else bubble.style.removeProperty('--pet-bubble-image');
      preview.dataset.tone = state;
      $('[data-pet-answer-preview]').hidden = !hasPack || state !== 'asking';
      idleGroup.hidden = state !== 'idle';
      for (const button of idleButtons) {
        button.disabled = !hasPack;
        button.setAttribute('aria-pressed', String(button.dataset.petIdleSpeech === idleSpeech));
      }
      for (const button of poseButtons) {
        const pose = button.dataset.petState;
        button.disabled = !hasPack;
        button.setAttribute('aria-pressed', String(pose === state));
        if (hasPack) setImage(button.querySelector('img'),asset(id,pose,'png'));
      }
      // The enlarged view keeps small desktop pets legible while still reflecting their relative size.
      actor.style.width = (102 + (pendingSize ?? data.size) * .7) + 'px';
      if (hasPack) {
        const key = asset(id, state, 'webp');
        if (actorKey !== key) {
          actorKey = key; actor.hidden = false;
          $('[data-pet-failure]').hidden = true;
          setImage(actor,key);
        }
      } else {
        actorKey = null; actor.removeAttribute('src'); actor.hidden = true;
      }
      $('[data-pet-edit]').disabled = !hasPack;
      $('[data-pet-next]').disabled = !hasPack || !choices().length;
      const speechKey = JSON.stringify([id,speechCategory(),choices()]);
      if (contentsKey !== speechKey) { contentsKey = speechKey; chooseLine(); }
    }
    function paint() {
      if (!data || !['pet','pet-preview'].includes(route) || document.hidden) return;
      if (route === 'pet-preview') { paintPreview(); return; }
      const available = data.packs.length > 0;
      const sizeDp = pendingSize ?? data.size;
      size.disabled = !available; size.value = sizeDp; sizeValue.value = sizeDp + ' dp';
      size.setAttribute('aria-valuetext', sizeValue.value);
      enabled.disabled = !available || !!data.permissionPending;
      enabled.setAttribute('aria-checked',String(!!data.enabled && !!data.allowed));
      bubbleTap.disabled = false;
      quick.disabled = !Array.isArray(data.speech?.[quickCategory]);
      bubbleTap.setAttribute('aria-checked',String(!!data.openConversationOnBubbleTap));
      const hint = $('[data-pet-permission]');
      hint.hidden = !permissionError && !data.permissionPending;
      hint.textContent = t(permissionError === 'overlay-unavailable' ? '暂时无法打开系统悬浮窗设置。' : '请在系统设置中允许悬浮窗。');
      $('[data-pet-expand]').disabled = !available;
      $('[data-pet-empty]').hidden = available;
      syncGallery();
      if (playing && playing.dataset.petPack !== data.selected) stopReel();
    }
    function receive(message) {
      if (message?.type === 'petVoiceNavigation') {
        if (route === 'pet') {
          showVoicePath = message.opened === false;
          paintVoiceStatus();
        }
        return;
      }
      if (message?.type === 'petVoiceStatus') {
        if (route === 'pet' && !awaitingPetState) {
          voiceState = message.state;
          paintVoiceStatus();
          clearTimeout(voiceRetry);
          if (voiceState === 'preparing' && !document.hidden)
            voiceRetry = setTimeout(() => post({type:'petVoiceStatus'}), 5000);
        }
        return;
      }
      const saved = message?.pet || message;
      if (!Array.isArray(saved?.packs)) return;
      if (route === 'pet' && awaitingPetState) {
        awaitingPetState = false;
        post({type:'petVoiceStatus'});
      }
      if (pendingPack === saved.selected || !saved.packs.some(pack => pack.id === pendingPack)) pendingPack = null;
      data = pendingPack ? {...saved,selected:pendingPack} : saved;
      if (pendingSize === data.size) pendingSize = null;
      if (message.error === 'speech-too-long') {
        pendingEditor = null;
        editorLengthError();
      } else if (message.error) permissionError = message.error;
      else if (saved.allowed) permissionError = null;
      if (pendingEditor && !message.error && (pendingEditor.reset || JSON.stringify(saved.speech?.[pendingEditor.state] || []) === pendingEditor.expected)) {
        drafts.delete(pendingEditor.state);
        if (draftKey === pendingEditor.state) closeEditor(false);
        pendingEditor = null;
      }
      paint();
    }
    function sendSize() {
      clearTimeout(sizeTimer); sizeTimer = undefined;
      if (pendingSize != null) post({type:'petChange',changes:{size:pendingSize}});
    }
    listen(size,'input',() => {
      pendingSize = Number(size.value); sizeValue.value = pendingSize + ' dp';
      size.setAttribute('aria-valuetext',sizeValue.value);
      if (!sizeTimer) sizeTimer = setTimeout(sendSize,70);
    });
    listen(size,'change',sendSize);
    listen($('[data-pet-voice-settings]'),'click',() => post({type:'petVoiceSettings'}));
    listen(main,'click',event => {
      const button = event.target.closest('button');
      if (!button || button.disabled || !data) return;
      if (button.hasAttribute('data-pet-enabled')) {
        permissionError = null;
        post({type:'petChange',changes:{enabled:!(data.enabled && data.allowed)}});
      } else if (button === bubbleTap) {
        post({type:'petChange',changes:{openConversationOnBubbleTap:!data.openConversationOnBubbleTap}});
      } else if (button.dataset.petPack) {
        const id = button.dataset.petPack;
        if (id !== data.selected) {
          pendingPack = id; data = {...data,selected:id}; revealSelection = true; syncGallery();
          post({type:'petChange',changes:{selected:id}});
        }
        playReel(button);
      } else if (button.hasAttribute('data-pet-expand')) go('pet-preview');
      else if (button.dataset.petState) {
        if (state !== button.dataset.petState) { closeEditor(true); state = button.dataset.petState; paintPreview(); }
      } else if (button.dataset.petIdleSpeech) {
        if (idleSpeech !== button.dataset.petIdleSpeech) { closeEditor(true); idleSpeech = button.dataset.petIdleSpeech; paintPreview(); }
      } else if (button.hasAttribute('data-pet-answer-preview')) {
        if (route === 'pet-preview' && state === 'asking' && data.selected)
          post({type:'petAnswerPreview', pack:data.selected});
      } else if (button.hasAttribute('data-pet-next')) chooseLine();
      else if (button === editSpeech || button === quick) {
        const category = button === quick ? quickCategory : speechCategory();
        if (editor.hidden || draftKey !== category) openEditor(category); else closeEditor(true);
      } else if (button.hasAttribute('data-pet-cancel')) {
        drafts.delete(draftKey); closeEditor(false);
      } else if (button.hasAttribute('data-pet-reset') || button.hasAttribute('data-pet-save')) {
        const reset = button.hasAttribute('data-pet-reset');
        const phrases = lines.value.split(/\r?\n/).map(line=>line.trim()).filter(Boolean);
        const state = draftKey;
        if (!state) return;
        const request = reset ? {type:'petSpeech',state,reset:true} : {type:'petSpeech',state,lines:phrases};
        if (JSON.stringify(request).length > 32768) {
          editorLengthError(); return;
        }
        pendingEditor = {state,reset,expected:JSON.stringify(phrases)}; post(request);
      }
    });
    listen(lines,'input',() => { issue.hidden = true; });
    listen(actor,'error',() => {
      if (actorKey && actor.getAttribute('src') === actorKey) {
        actor.hidden = true; $('[data-pet-failure]').hidden = false;
      }
    });
    function suspend() {
      stopReel(); stopText(); contentsKey = null;
      if (actorKey && data) { actorKey = null; setImage(actor,asset(data.selected,state,'png')); }
    }
    function show(tab) {
      if (disposed) return;
      if (route !== tab) {
        clearTimeout(voiceRetry);
        revealSelection = tab === 'pet' && route !== 'pet-preview';
        if (tab === 'pet') {
          awaitingPetState = true; voiceState = 'loading'; showVoicePath = false; paintVoiceStatus();
        }
        suspend(); closeEditor(true); permissionError = null;
      }
      route = tab;
      if (route === 'pet' || route === 'pet-preview') { paint(); post({type:'petStatus'}); }
    }
    listen(document,'visibilitychange',() => {
      if (document.hidden) { clearTimeout(voiceRetry); suspend(); return; }
      if (route === 'pet' && !awaitingPetState) post({type:'petVoiceStatus'});
      if (route) paint();
    });
    listen(window,'sugar-language-changed',() => {
      if (route === 'pet') paintVoiceStatus();
      if (route === 'pet' || route === 'pet-preview') post({type:'petStatus'});
    });
    return {show, receive, dispose() {
      disposed = true; events.abort(); resize.disconnect(); clearTimeout(sizeTimer); clearTimeout(voiceRetry); suspend();
    }};
  }
  window.SugarPets = {controls,mount};
})();
