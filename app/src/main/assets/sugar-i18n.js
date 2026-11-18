(() => {
  'use strict';
  let language = 'zh';
  const locales = ['zh', 'en', 'ja'];
  const messages = new Map();
  const renderers = new Set();
  function add(catalog) {
    for (const [key, translations] of Object.entries(catalog)) messages.set(key, translations);
  }
  function text(source) {
    return language === 'zh' ? source : messages.get(source)?.[language === 'en' ? 0 : 1] ?? source;
  }
  function setLanguage(value) {
    if (!locales.includes(value) || value === language) return;
    language = value;
    for (const render of renderers) render();
    window.dispatchEvent(new CustomEvent('sugar-language-changed', {detail: value}));
  }
  // Bind only the initial text of a Sugar-owned template. User input and DSH content are never observed.
  function bindStatic(scope) {
    const bindings = [];
    const walker = document.createTreeWalker(scope, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
    for (let node = walker.currentNode; node; node = walker.nextNode()) {
      if (node.nodeType === Node.TEXT_NODE) {
        const source = node.data;
        const key = source.trim();
        if (messages.has(key) && !node.parentElement?.closest('[translate=no]')) {
          const offset = source.indexOf(key);
          bindings.push(() => { node.data = source.slice(0, offset) + text(key) + source.slice(offset + key.length); });
        }
      } else if (node.nodeType === Node.ELEMENT_NODE && !node.closest('[translate=no]')) {
        for (const attr of ['aria-label', 'title', 'placeholder', 'alt']) {
          const key = node.getAttribute(attr);
          if (messages.has(key)) bindings.push(() => node.setAttribute(attr, text(key)));
        }
      }
    }
    const render = () => bindings.forEach(update => update());
    renderers.add(render);
    render();
    return () => renderers.delete(render);
  }
  window.SugarI18n = {text, add, bindStatic, setLanguage, get language() { return language; }};
  add({
    '手机文件传输尚未就绪': ['Phone file transfer is not ready yet.', '端末とのファイル転送の準備ができていません。'],
    '等待手机写入文件超时': ['Timed out waiting for the phone to write the file.', '端末への書き込みがタイムアウトしました。'],
    '无法获取产物大小或内容': ['Could not read the file size or contents.', 'ファイルのサイズまたは内容を読み取れません。'],
    '文件在导出时发生变化': ['The file changed during export.', '書き出し中にファイルが変更されました。'],
    '请从产物菜单打开或下载文件': ['Open or download the file from its menu.', 'ファイルのメニューから開くかダウンロードしてください。'],
    '请点击删除空文件夹按钮。': ['Use the Delete empty folder button.', '「空のフォルダを削除」ボタンを押してください。'],
    '请先完成当前操作。': ['Finish the current action first.', '先に現在の操作を完了してください。'],
    '设置': ['Settings', '設定'],
    '概览': ['Overview', '概要'],
    '前进': ['Forward', '進む'],
    '外观': ['Appearance', '外観'],
    '手势': ['Gestures', 'ジェスチャー'],
    '启动页': ['Startup screen', '起動画面'],
    '消息通知': ['Notifications', '通知'],
    '无线 ADB': ['Wireless ADB', 'ワイヤレス ADB'],
    '预览与摆放': ['Preview and layout', 'プレビューと配置'],
    '界面语言': ['Display language', '表示言語'],
    '桌宠': ['Desktop pet', 'デスクトップペット'],
    '选择宠物、预览动作与调整大小': ['Choose a pet, preview animations and adjust its size', 'ペットの選択・アニメーションのプレビュー・サイズ調整'],
    '亮白': ['Soft white', 'オフホワイト'],
    '深灰': ['Charcoal', 'チャコール'],
    '浅蓝': ['Sky blue', 'ライトブルー'],
    '深海': ['Deep sea', 'ディープブルー'],
    '莓紫': ['Berry', 'ベリー'],
    '暖沙': ['Warm sand', 'サンド'],
    '仅在 App 后台时': ['Only in the background', 'バックグラウンド時のみ'],
    '始终通知': ['Always notify', '常に通知'],
    '关闭': ['Off', 'オフ'],
    '侧边导航': ['Side navigation', 'サイドナビゲーション'],
    '导航配色与磨砂背景': ['Navigation colors and background blur', 'ナビゲーションの配色と背景のぼかし'],
    '停靠位置与切换动效': ['Dock position and transitions', '表示位置と切り替え効果'],
    '应用': ['App', 'アプリ'],
    '通用': ['General', '一般'],
    '偏好设置': ['Preferences', '基本設定'],
    '应用信息': ['App information', 'アプリ情報'],
    '关于': ['About', 'このアプリについて'],
    '版本': ['Version', 'バージョン'],
    '在 GitHub 查看源码': ['View source on GitHub', 'GitHub でソースコードを見る'],
    '界面语言与任务时常亮': ['App language and screen wake settings', '表示言語とタスク中の画面点灯'],
    '任务时常亮': ['Screen on during tasks', 'タスク中は常時点灯'],
    '闲置后自动调暗': ['Dims when idle', '未操作時は自動で減光'],
    '启动图片、加载文字与退出转场': ['Image, loading text, and exit animation', '起動画像、読み込みテキスト、終了アニメーション'],
    '轮次完成、权限审批与提问': ['Completed turns, approvals, and questions', 'タスクの完了、承認リクエスト、質問'],
    '配对并连接本机': ['Pair and connect to this device', 'この端末とペア設定して接続'],
    '导航配色': ['Navigation colors', 'ナビゲーションの配色'],
    '随背景': ['Match background', '背景に合わせる'],
    '随机色': ['Random', 'ランダム'],
    '换一个颜色': ['Pick another color', '別の色に変更'],
    '磨砂背景': ['Background blur', '背景のぼかし'],
    '背景透明度': ['Background transparency', '背景の透明度'],
    '配色测试': ['Color preview', '配色プレビュー'],
    '停靠位置': ['Dock position', '表示位置'],
    '左侧': ['Left', '左'],
    '右侧': ['Right', '右'],
    '整条侧边交给 Sugar': ['Use the full screen edge for Sugar', '画面の端全体で Sugar を操作'],
    '开启后底部手势条暂时收起，从屏幕底部上滑可唤回。': ['Temporarily hides the system gesture bar. Swipe up from the bottom to show it again.', 'システムのジェスチャーバーを一時的に隠します。画面の下端から上にスワイプすると再表示できます。'],
    '减少动效': ['Reduce motion', 'アニメーションを減らす'],
    '收起后的边缘手势': ['Edge gestures when collapsed', 'ナビゲーションを閉じているとき'],
    '从屏幕边缘向内快扫可展开导航。按住边缘再慢慢向内拖，可以预览历史页面；拖过中线后松手切换，拖回可取消。': ['Swipe in quickly from the edge to open navigation. Hold the edge and drag slowly to preview the previous page. Release past the center to switch, or drag back to cancel.', '画面の端から内側へすばやくスワイプするとナビゲーションが開きます。端を長押ししてゆっくりドラッグすると前のページをプレビューできます。中央を越えて離すと切り替わり、引き戻すとキャンセルできます。'],
    '横向切换': ['Switch pages horizontally', '左右のスワイプで切り替え'],
    '拖过屏幕中线，或在导航条展开后朝对侧快甩。从任一侧滑动都回到刚才的页面，再滑一次回到当前页。打开其他页面后，这两页随之更新。返回和前进按钮可以继续翻看更早的记录。只有一个页面时，拖动可调整导航停靠位置。': ['Drag past the center, or flick the open navigation bar toward the opposite edge. Swipe from either side to switch between your last two pages. Opening another page updates the pair. Use Back and Forward for older history. With just one page, drag to move the bar.', '画面中央を越えてドラッグするか、開いたナビゲーションバーを反対側へフリックします。どちらの端からでも直前の2ページを行き来できます。別のページを開くと対象が変わります。さらに前の履歴は「戻る」と「進む」でたどれます。1ページしかない場合はバーの位置を変更できます。'],
    '上下滑动设置页': ['Swipe between settings pages', '上下のスワイプで切り替え'],
    '在设置的相邻页面间切换。': ['Swipe up or down to move to the next settings page.', '上下にスワイプすると隣の設定ページに切り替わります。'],
    '轮次完成通知': ['Turn completion', 'タスク完了通知'],
    'Agent 完成本轮任务时提醒': ['Notify when the agent finishes a turn', 'エージェントが今回のタスクを終えたときに通知'],
    '系统通知已关闭': ['System notifications are off', 'システム通知はオフです'],
    '打开系统通知设置': ['Open notification settings', 'システムの通知設定を開く'],
    '权限审批通知': ['Approval requests', '承認リクエスト'],
    '需要你批准操作时，在前台和后台都提醒': ['Notify whenever an action needs your approval', '操作の承認が必要なときは、アプリの表示状態を問わず通知'],
    '提问通知': ['Questions', '質問の通知'],
    '需要你回答问题时，在前台和后台都提醒': ['Notify whenever the agent needs an answer', '回答が必要なときは、アプリの表示状態を問わず通知'],
    '手机连接': ['Phone connection', '端末への接続'],
    '无线 ADB 与 Root': ['Wireless ADB and Root', 'ワイヤレス ADB と Root'],
    'Root（可选）': ['Root (optional)', 'Root（オプション）'],
    '使用 Root': ['Use Root', 'Root を使用'],
    'Root 未启用': ['Root is off', 'Root はオフです'],
    '等待 Root 管理器授权…': ['Waiting for the Root manager…', 'Root 管理アプリの承認を待っています…'],
    'Sugar 已获 Root 授权': ['Sugar has Root access', 'Sugar に Root 権限があります'],
    'Root 暂不可用': ['Root is unavailable', 'Root を利用できません'],
    'Root 授权检查超时': ['Root access check timed out', 'Root 権限の確認がタイムアウトしました'],
    'Root 授权需要重新检查': ['Check Root access again', 'Root 権限を再確認してください'],
    '正在读取授权状态…': ['Checking access status…', '権限の状態を確認しています…'],
    '请确认设备已启用 Root，并在管理器中允许 DSH Sugar。': ['Check that Root is active on this device and allow DSH Sugar in your Root manager.', '端末で Root が有効になっていることを確認し、Root 管理アプリで DSH Sugar を許可してください。'],
    '授权后，手机操作会自动使用 Root。工作区命令遵循会话权限。': ['Phone controls automatically use Root when authorized. Workspace commands follow conversation permissions.', '承認後は端末操作で Root を自動的に使用します。ワークスペースのコマンドには会話の権限設定が適用されます。'],
    '检查中': ['Checking', '確認中'],
    '检查授权': ['Check access', '権限を確認'],
    '正在检查本机无线调试…': ['Checking wireless debugging…', 'ワイヤレス デバッグを確認しています…'],
    '正在准备配对…': ['Preparing to pair…', 'ペア設定を準備しています…'],
    '等待系统授权本地网络访问': ['Waiting for local network permission', 'ローカルネットワークへのアクセス許可を待っています'],
    '等待开启无线调试': ['Waiting for wireless debugging', 'ワイヤレス デバッグが有効になるのを待っています'],
    '正在搜索本机配对端口…': ['Looking for the pairing port…', 'ペア設定用ポートを探しています…'],
    '等待输入六位配对码': ['Waiting for the six-digit pairing code', '6桁のペア設定コードを待っています'],
    '正在搜索本机连接端口…': ['Looking for the connection port…', '接続用ポートを探しています…'],
    '正在与本机配对…': ['Pairing with this device…', 'この端末とペア設定しています…'],
    '正在连接本机…': ['Connecting to this device…', 'この端末に接続しています…'],
    '正在取消…': ['Canceling…', 'キャンセルしています…'],
    '正在断开连接…': ['Disconnecting…', '切断しています…'],
    '暂时无法读取连接状态': ['Connection status is unavailable', '接続状態を取得できません'],
    '正在读取本机连接状态…': ['Checking the connection…', '接続状態を確認しています…'],
    '此设备不支持无线调试，需要 Android 11 或更高版本。': ['Wireless debugging requires Android 11 or later.', 'ワイヤレス デバッグには Android 11 以降が必要です。'],
    'DSH Sugar 已连接本机': ['DSH Sugar is connected to this device', 'DSH Sugar はこの端末に接続しています'],
    '已配对，尚未连接': ['Paired, disconnected', 'ペア設定済み・未接続'],
    '尚未配对': ['Not paired', '未ペア設定'],
    '请到系统开发者选项开启「无线调试」。开启后，Sugar 会继续连接。': ['Turn on Wireless debugging in Developer options. Sugar will then connect automatically.', '開発者向けオプションで「ワイヤレス デバッグ」をオンにすると、Sugar が自動で接続します。'],
    '保持系统配对窗口打开，在 Sugar 通知中输入六位配对码。': ['Keep the pairing dialog open and enter the six-digit code in the Sugar notification.', 'システムのペア設定画面を開いたまま、Sugar の通知に6桁のコードを入力してください。'],
    '重新连接': ['Reconnect', '再接続'],
    '连接本机': ['Connect to this device', 'この端末に接続'],
    '断开连接': ['Disconnect', '切断'],
    '取消': ['Cancel', 'キャンセル'],
    '请输入 1 到 65535 之间的端口号。': ['Enter a port number between 1 and 65535.', '1～65535 のポート番号を入力してください。'],
    '刷新连接状态': ['Refresh connection status', '接続状態を更新'],
    '连接后，所有工作区的 Agent 均可执行本机 ADB 命令，断开后停止。': ['While connected, agents in all workspaces can run ADB commands on this device.', '接続中はすべてのワークスペースのエージェントがこの端末で ADB コマンドを実行できます。'],
    '打开无线调试设置': ['Open wireless debugging settings', 'ワイヤレス デバッグ設定を開く'],
    '打开「无线调试 → 使用配对码配对设备」。保持配对弹窗打开，下拉通知栏，在 Sugar 通知中输入 6 位配对码。': ['Open Wireless debugging and choose Pair device with pairing code. Leave the dialog open, pull down the notification shade, and enter the six-digit code in the Sugar notification.', '「ワイヤレス デバッグ」で「ペア設定コードによるデバイスのペア設定」を選びます。画面を開いたまま通知パネルを下ろし、Sugar の通知に6桁のコードを入力してください。'],
    '通知栏配对': ['Pair via notification', '通知からペア設定'],
    '配对完成后，Sugar 会自动查找本机连接端口。': ['After pairing, Sugar will automatically find the connection port.', 'ペア設定が完了すると、Sugar は端末の接続用ポートを自動で見つけます。'],
    '更多连接方式': ['More connection options', 'その他の接続方法'],
    '用通知栏重新配对': ['Pair again via notification', '通知からもう一度ペア設定'],
    '配对本机': ['Pair with this device', 'この端末とペア設定'],
    '可用分屏同时打开系统设置和 Sugar，填写配对弹窗中的端口和 6 位配对码。': ['Open system settings alongside Sugar in split screen. Enter the port and six-digit code shown in the pairing dialog.', '画面分割でシステム設定と Sugar を開き、ペア設定画面に表示されたポートと6桁のコードを入力してください。'],
    '配对端口': ['Pairing port', 'ペア設定用ポート'],
    '配对码': ['Pairing code', 'ペア設定コード'],
    '配对': ['Pair', 'ペア設定'],
    '配对完成后，在「无线调试」主页面查看「IP 地址和端口」，填写冒号后的连接端口。': ['After pairing, find IP address & port on the Wireless debugging screen. Enter the port shown after the colon.', 'ペア設定後、「ワイヤレス デバッグ」画面の「IP アドレスとポート」で、コロンの後にあるポート番号を入力してください。'],
    '连接端口': ['Connection port', '接続用ポート'],
    '连接': ['Connect', '接続']
  });
})();
