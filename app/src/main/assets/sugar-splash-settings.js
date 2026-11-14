(() => {
  'use strict';
  window.SugarI18n?.add({
    "启动页设置": ["Startup screen settings", "起動画面の設定"],
    "启动图片": ["Startup image", "起動画像"],
    "PNG、APNG 或 GIF": ["PNG, APNG or GIF", "PNG、APNG、GIF"],
    "选择图片…": ["Choose image…", "画像を選択…"],
    "更换图片…": ["Change image…", "画像を変更…"],
    "恢复默认图片": ["Restore default image", "既定の画像に戻す"],
    "预览与摆放": ["Preview and position", "プレビューと配置"],
    "图片布局": ["Image layout", "画像の配置"],
    "编辑屏幕方向": ["Orientation to edit", "編集する画面の向き"],
    "竖屏": ["Portrait", "縦向き"],
    "横屏": ["Landscape", "横向き"],
    "屏幕占比": ["Image size", "画面に占める割合"],
    "图片适配": ["Image fit", "画像の表示方法"],
    "完整显示": ["Fit", "全体を表示"],
    "填满裁切": ["Fill and crop", "画面に合わせて切り抜く"],
    "背景与文字": ["Background and text", "背景とテキスト"],
    "背景颜色": ["Background color", "背景色"],
    "十六进制背景色": ["Hex background color", "背景色の16進数コード"],
    "恢复白色": ["Reset to white", "白に戻す"],
    "请输入六位十六进制色值，如 #B8D8EA。": ["Enter a six-digit hex color, such as #B8D8EA.", "#B8D8EA のような6桁のカラーコードを入力してください。"],
    "加载文字": ["Loading text", "読み込み時のテキスト"],
    "留空可隐藏文字": ["Leave blank to hide the text", "空欄にすると非表示"],
    "显示加载进度": ["Show loading progress", "読み込み状況を表示"],
    "结束启动页": ["Closing the startup screen", "起動画面の終了"],
    "展示时长": ["Display duration", "表示時間"],
    "加载完成": ["Until ready", "読み込み完了まで"],
    "固定时长": ["Fixed duration", "時間を指定"],
    "持续时间": ["Duration", "表示時間"],
    "秒": ["s", "秒"],
    "最长 60 秒。时间到后若仍在加载，会继续显示连接进度。": ["Up to 60 seconds. If loading takes longer, connection progress stays visible.", "最大60秒です。読み込みが続く場合は、その後も接続状況を表示します。"],
    "随机退出转场": ["Random exit animation", "終了時のアニメーションをランダムにする"],
    "默认动画": ["Default animation", "既定のアニメーション"],
    "预览方向": ["Preview orientation", "プレビューの向き"],
    "恢复自动布局": ["Reset to automatic layout", "自動配置に戻す"],
    "启动页预览暂时不可用": ["Startup preview is unavailable", "起動画面をプレビューできません"],
    "拖动图片或加载文字调整位置，松手自动保存。进度条随文字移动。": ["Drag the image or text to position it. Release to save. The progress bar moves with the text.", "画像やテキストをドラッグし、指を離すと位置が保存されます。進捗バーはテキストと一緒に移動します。"],
    "可拖动的启动页预览": ["Draggable startup preview", "ドラッグで調整できる起動画面のプレビュー"]
  });
  const t = value => window.SugarI18n?.text(value) ?? value;
  const choices = (name, title, options) => `<div class="choices" role="group" aria-label="${title}">${options.map(
    ([value, label]) => `<button type="button" data-${name}="${value}">${label}</button>`).join('')}</div>`;
  const toggle = (key, label) => `<div class="row"><span id="splash-${key}">${label}</span>
    <button type="button" role="switch" aria-labelledby="splash-${key}" data-toggle="${key}"></button></div>`;
  const controls = `<fieldset class="splash-controls" disabled aria-label="启动页设置">
    <h2>启动图片</h2>
    <div class="group splash-image">
      <span class="image-name"></span><small>PNG、APNG 或 GIF</small>
      <div class="image-actions"><button type="button" data-image="pick">选择图片…</button>
        <button type="button" data-image="reset" hidden>恢复默认图片</button></div>
      <small class="image-error" role="status" hidden></small>
    </div>
    <button type="button" class="palette-link" data-go="splash-preview">预览与摆放<i aria-hidden="true">›</i></button>
    <div class="row splash-section"><h2>图片布局</h2>
      ${choices('orientation', '编辑屏幕方向', [['portrait','竖屏'],['landscape','横屏']])}</div>
    <div class="group">
      <div class="slider-row"><label for="splash-scale">屏幕占比</label><output for="splash-scale"></output>
        <input id="splash-scale" type="range" min="10" max="100" step="1"></div>
      <div class="row"><span>图片适配</span>
        ${choices('fit','图片适配',[['contain','完整显示'],['cover','填满裁切']])}</div>
    </div>
    <h2 class="splash-section">背景与文字</h2>
    <div class="group">
      <div class="row"><label for="splash-color">背景颜色</label>
        <div class="color-choice"><input type="color" id="splash-color">
        <input type="text" id="splash-hex" aria-label="十六进制背景色" aria-describedby="splash-hex-error" maxlength="7" autocapitalize="characters" spellcheck="false">
        <button type="button" data-white hidden>恢复白色</button></div></div>
      <small class="color-error" id="splash-hex-error" role="status" hidden>请输入六位十六进制色值，如 #B8D8EA。</small>
      <div class="splash-text"><label for="splash-text">加载文字</label>
        <input id="splash-text" type="text" maxlength="160" placeholder="留空可隐藏文字"></div>
      ${toggle('showProgress','显示加载进度')}
    </div>
    <h2 class="splash-section">结束启动页</h2>
    <div class="group">
      <div class="row"><span>展示时长</span>
        ${choices('timing','展示时长',[['ready','加载完成'],['fixed','固定时长']])}</div>
      <div class="splash-timer" hidden>
        <div class="row"><label for="splash-seconds">持续时间</label><span>
          <input type="number" inputmode="decimal" id="splash-seconds" min="0.001" max="60" step="0.001" aria-describedby="splash-timer-hint"> 秒</span></div>
        <small id="splash-timer-hint">最长 60 秒。时间到后若仍在加载，会继续显示连接进度。</small>
      </div>
      ${toggle('randomExit','随机退出转场')}
    </div></fieldset>`;

  function mount(pane, initial, post, connected, previewPane, previewUrl) {
    const find = selector => pane.querySelector(selector);
    const fields = find('fieldset');
    const scale = find('#splash-scale'), text = find('#splash-text'), color = find('#splash-color'), seconds = find('#splash-seconds');
    const hex = find('#splash-hex'), colorError = find('.color-error');
    let composingText = false;
    let orientation = matchMedia('(orientation: landscape)').matches ? 'landscape' : 'portrait';
    let state = initial, lastDuration = initial?.durationMs || 3000;
    const pending = new Map();
    function same(a,b) { return a === b || Boolean(a && b && typeof a === 'object' && a.x === b.x && a.y === b.y); }
    const preview = window.SugarSplashPreview.mount(previewPane,previewUrl,commit);
    function render() {
      fields.disabled = !state || !connected;
      preview.configure(state);
      if (!state) return;
      const percent = state[orientation + 'Percent'];
      scale.value = percent;
      scale.setAttribute('aria-valuetext', percent + '%');
      find('output[for=splash-scale]').value = percent + '%';
      const selected = {orientation, fit:state[orientation + 'Fit'], timing:state.durationMs ? 'fixed' : 'ready'};
      for (const key of Object.keys(selected)) {
        for (const b of pane.querySelectorAll('[data-' + key + ']'))
          b.setAttribute('aria-pressed', String(b.dataset[key] === selected[key]));
      }
      for (const b of pane.querySelectorAll('[data-toggle]')) b.setAttribute('aria-checked', String(state[b.dataset.toggle]));
      color.value = state.backgroundColor;
      if (hex !== pane.getRootNode().activeElement && !hex.hasAttribute('aria-invalid')) hex.value = state.backgroundColor.toUpperCase();
      find('[data-white]').hidden = state.backgroundColor.toUpperCase() === '#FFFFFF';
      if (!composingText && text.value !== state.loadingText) text.value = state.loadingText;
      if (state.durationMs) lastDuration = state.durationMs;
      if (seconds !== pane.getRootNode().activeElement) seconds.value = String(lastDuration / 1000);
      find('.splash-timer').hidden = !state.durationMs;
      find('.image-name').textContent = state.imageName || t('默认动画');
      find('[data-image=pick]').textContent = t(state.imageName ? '更换图片…' : '选择图片…');
      find('[data-image=reset]').hidden = !state.imageName;
    }
    function commit(changes) {
      if (!state || !connected || Object.entries(changes).every(([k,v]) => same(state[k],v))) return;
      for (const [key,value] of Object.entries(changes)) pending.set(key,value);
      state = {...state,...changes};
      post({type:'splashSettings',changes});
      render();
    }
    function save(key,value) { commit({[key]:value}); }
    const error = find('.image-error');
    pane.addEventListener('click', event => {
      const b = event.target.closest('button');
      if (!b || fields.disabled) return;
      const {image, orientation:side, fit, toggle, timing} = b.dataset;
      if (image) {
        error.hidden = true;
        post({type:image === 'pick' ? 'splashPick' : 'splashResetImage'});
      } else if (side) { orientation = side; render(); }
      else if (fit) save(orientation + 'Fit', fit);
      else if (toggle) save(toggle, !state[toggle]);
      else if (timing) save('durationMs', timing === 'fixed' ? lastDuration : 0);
      else if (b.hasAttribute('data-white')) setColor('#FFFFFF');
    });
    scale.addEventListener('input', () => {
      find('output[for=splash-scale]').value = scale.value + '%';
      scale.setAttribute('aria-valuetext', scale.value + '%');
    });
    scale.addEventListener('change', () => save(orientation + 'Percent', Number(scale.value)));
    text.addEventListener('compositionstart', () => { composingText = true; });
    text.addEventListener('input', e => { if (!composingText && !e.isComposing) save('loadingText', text.value); });
    text.addEventListener('compositionend', () => { composingText = false; save('loadingText', text.value); });
    function setColor(value) {
      hex.removeAttribute('aria-invalid');
      colorError.hidden = true;
      hex.value = value;
      save('backgroundColor', value);
    }
    color.addEventListener('change', () => setColor(color.value.toUpperCase()));
    hex.addEventListener('input', () => { hex.removeAttribute('aria-invalid'); colorError.hidden = true; });
    hex.addEventListener('change', () => {
      const value = hex.value.trim().replace(/^#/, '').toUpperCase();
      if (/^[A-F0-9]{6}$/.test(value)) setColor('#' + value);
      else { hex.setAttribute('aria-invalid','true'); colorError.hidden = false; }
    });
    seconds.addEventListener('change', () => {
      if (seconds.validity.valid && seconds.valueAsNumber > 0) save('durationMs', Math.round(seconds.valueAsNumber * 1000));
      seconds.value = String(lastDuration / 1000);
    });
    window.addEventListener('sugar-language-changed', render);
    render();
    return {
      connect() { connected = true; render(); },
      dispose() { window.removeEventListener('sugar-language-changed', render); preview.dispose(); },
      receive(message) {
        // A picker or an older save can reply while the next edit is already in flight.
        if (state?.imageName !== message.splash.imageName) preview.imageChanged();
        state = {...message.splash};
        for (const [key, value] of pending) {
          if (same(state[key],value)) pending.delete(key);
          else state[key] = value;
        }
        error.textContent = message.error || '';
        error.hidden = !message.error;
        render();
      }
    };
  }
  window.SugarSplashSettings = {controls, mount};
})();
