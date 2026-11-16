(() => {
  'use strict';
  const i18n = window.SugarI18n;
  i18n.add({
    '手机操作':['Phone control','端末操作'],
    '主屏操作':['Main screen','メイン画面操作'],
    '虚拟屏操作':['Virtual screen','仮想画面操作'],
    '虚拟屏':['Virtual screen','仮想画面'],
    '停止':['Stop','停止'],
    '取消':['Cancel','キャンセル'],
    '无障碍主屏与虚拟屏':['Accessibility and virtual display','メイン画面と仮想画面'],
    '允许主屏操作':['Allow main screen control','メイン画面操作を許可'],
    '允许虚拟屏操作':['Allow virtual screen control','仮想画面操作を許可'],
    '显示触点':['Show touches','タップ位置を表示'],
    '在主屏操作位置显示短暂的圆环':['Briefly highlight touches on the main screen','メイン画面の操作位置にリングを一時表示します'],
    '系统无障碍服务':['System accessibility service','システムのユーザー補助サービス'],
    '已开启':['Enabled','有効'],
    '未开启':['Off','無効'],
    '无线 ADB 已连接':['Wireless ADB connected','ワイヤレス ADB 接続済み'],
    '无线 ADB 尚未连接':['Wireless ADB disconnected','ワイヤレス ADB 未接続'],
    '无线 ADB 设置':['Wireless ADB settings','ワイヤレス ADB 設定'],
    '手机连接':['Phone connection','端末の接続'],
    'Root 已连接':['Root connected','Root 接続済み'],
    '连接 Root 或无线 ADB':['Connect Root or wireless ADB','Root またはワイヤレス ADB に接続'],
    '管理虚拟屏':['Manage virtual screens','仮想画面の管理'],
  });
  const row = (key, label, hint='') => '<div class="row"><span id="mobile-pref-' + key + '">' + label +
    (hint ? '<small>' + hint + '</small>' : '') + '</span><button type="button" role="switch" data-mobile-pref="' +
    key + '" aria-checked="false" aria-labelledby="mobile-pref-' + key + '" disabled></button></div>';
  const controls = {
    main:'<div class="group">' + row('main','允许主屏操作') + row('touches','显示触点','在主屏操作位置显示短暂的圆环') +
      '</div><div class="links"><button type="button" data-mobile-system><span>系统无障碍服务<small data-mobile-grant></small></span><i aria-hidden="true">›</i></button>' +
      '<button type="button" data-go="adb"><span>手机连接<small data-mobile-connection></small></span><i aria-hidden="true">›</i></button></div>',
    virtual:'<div class="group">' + row('virtual','允许虚拟屏操作') +
      '</div><div class="links"><button type="button" data-go="adb"><span>手机连接<small data-mobile-connection></small></span><i aria-hidden="true">›</i></button>' +
      '<button type="button" data-mobile-preview><span>管理虚拟屏</span><i aria-hidden="true">›</i></button></div>'
  };
  function mount(post, navigate) {
    let prefs, root, obscured = false;
    let titlesRequest, titleSource, stopTitles;
    function bindTitles() {
      const source = window.SugarMobileSession;
      if (titleSource === source || !titlesRequest) return;
      stopTitles?.();
      titleSource = source;
      const {watch, conversations} = titlesRequest;
      const reported = new Map();
      const send = titles => {
        // The Android port caps incoming messages at 2048 characters. Updates are independently
        // keyed, including empty titles when a DSH rename/refresh removes a previously known one.
        for (const conversation of conversations) {
          const title = (titles[conversation] || '').replace(/[\p{Cc}\p{Cf}]/gu,' ').trim();
          if (reported.get(conversation) === title) continue;
          reported.set(conversation,title);
          post({type:'mobileScreenTitles', watch, conversation, title});
        }
      };
      stopTitles = source?.watchTitles?.(conversations, send);
      if (!source?.watchTitles) send({});
    }
    function sendConversation() {
      const conversation = obscured ? null : window.SugarMobileSession?.current();
      post({type:'mobileConversation', conversation:conversation || ''});
      bindTitles();
    }
    function paint() {
      if (!prefs || !root) return;
      for (const button of root.querySelectorAll('[data-mobile-pref]')) {
        const key = button.dataset.mobilePref;
        button.disabled = key === 'touches' && !prefs.mainEnabled;
        button.setAttribute('aria-checked',String(Boolean(prefs[{main:'mainEnabled',virtual:'virtualEnabled',touches:'showTouches'}[key]])));
      }
      root.querySelector('[data-mobile-grant]').textContent = i18n.text(prefs.accessibilityEnabled ? '已开启' : '未开启');
      root.querySelectorAll('[data-mobile-connection]').forEach(node => {
        node.textContent = i18n.text(prefs.rootAvailable ? 'Root 已连接' : prefs.adbConnected ? '无线 ADB 已连接' : '连接 Root 或无线 ADB');
      });
    }
    function openAccessibility() { post({type:'mobileAccessibility'}); }
    function preview() { post({type:'mobilePreview'}); }
    function receive(message) {
      if (message.type === 'mobileSettings') { prefs = message; paint(); }
      if (message.type === 'rootState' && prefs) { prefs.rootAvailable = message.available; paint(); }
      if (message.type === 'mobileTitleWatch' && typeof message.watch === 'string' && Array.isArray(message.conversations)) {
        stopTitles?.();
        stopTitles = undefined;
        titleSource = null;
        titlesRequest = message.conversations.length ? message : null;
        bindTitles();
      }
    }
    function click(event) {
      const button = event.target.closest('button');
      if (!button || !event.isTrusted) return;
      if (button.hasAttribute('data-mobile-system')) openAccessibility();
      if (button.hasAttribute('data-mobile-preview')) preview();
      if (button.dataset.mobilePref) {
        button.disabled = true;
        post({type:'mobilePreference', preference:button.dataset.mobilePref, enabled:button.getAttribute('aria-checked') !== 'true'});
      }
    }
    window.addEventListener('sugar-language-changed',paint);
    window.addEventListener('sugar-mobile-conversation',sendConversation);
    const bridge = {setup:route => {
      if (['mobile-main','mobile-virtual','adb'].includes(route)) navigate(route);
    }};
    window.SugarMobile = bridge;
    sendConversation();
    return {receive, sendConversation, setObscured(value) {
      if (obscured !== value) { obscured = value; sendConversation(); }
    }, attach(element) { root = element; root.addEventListener('click',click); paint(); }, dispose() {
      if (window.SugarMobile === bridge) delete window.SugarMobile;
      stopTitles?.();
      root?.removeEventListener('click',click);
      window.removeEventListener('sugar-language-changed',paint);
      window.removeEventListener('sugar-mobile-conversation',sendConversation);
      post({type:'mobileConversation', conversation:''});
    }};
  }
  window.SugarMobileControls = {controls,mount};
})();
