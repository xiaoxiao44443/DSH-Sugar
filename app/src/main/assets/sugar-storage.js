(() => {
  'use strict';
  const i18n = window.SugarI18n, t = i18n.text;
  i18n.add({
    '存储占用':['Storage usage','ストレージ使用量'],
    '工作区与共享工具':['Workspaces and shared tools','ワークスペースと共有ツール'],
    '诊断与恢复':['Diagnostics and recovery','診断と復旧'],
    '检查启动问题与恢复 DSH':['Troubleshoot startup and restore DSH','起動の問題を診断し、DSH を復旧'],
    '运行时占用':['Runtime storage','ランタイムの使用量'],
    '重新统计':['Refresh usage','再集計'],
    '正在统计…':['Calculating…','集計中…'],
    '共享内置工具':['Shared built-in tools','共有の組み込みツール'],
    'DSH 主环境':['DSH environment','DSH 本体の環境'],
    '浏览内部文件':['Browse internal files','内部ファイルを開く'],
    '其他运行时数据':['Other runtime data','その他のランタイムデータ'],
    '工作区':['Workspaces','ワークスペース'],
    '全部工作区':['All workspaces','すべてのワークスペース'],
    '排序方式':['Sort by','並べ替え'],
    '大小':['Size','サイズ'],
    '创建时间':['Created','作成日時'],
    '修改时间':['Modified','更新日時'],
    '升序':['Ascending','昇順'],
    '降序':['Descending','降順'],
    '时间未知':['Date unknown','日時不明'],
    '{n} 个工作区':['{n} workspaces','ワークスペース {n} 件'],
    '工作区名称或路径':['Workspace name or path','ワークスペース名またはパス'],
    '没有匹配的工作区':['No matching workspaces','一致するワークスペースがありません'],
    '上一页':['Previous','前へ'],
    '下一页':['Next','次へ'],
    '暂无工作区':['No workspaces yet','ワークスペースはありません'],
    '沙箱未初始化':['Sandbox not initialized','サンドボックスは未初期化です'],
    '手机文件夹':['Phone folder','端末上のフォルダ'],
    '统计手机文件':['Measure files','ファイルを集計'],
    '无法统计，请检查文件夹授权。':['Cannot measure. Check folder access.','集計できません。フォルダのアクセス権を確認してください。'],
    '无法读取存储用量。':['Could not read storage usage.','使用量を読み取れませんでした。'],
    '至少':['At least','最小'],
    '工作区包含项目文件、依赖及私有环境。手机文件夹按文件大小单列。':['Workspaces include project files, dependencies and private environments. Phone folder file sizes are listed separately.','ワークスペースにはプロジェクトのファイル、依存関係、専用環境が含まれます。端末フォルダのファイルサイズは別に表示します。'],
  });
  const controls = `<div class="storage-summary"><div><h2>运行时占用</h2><strong data-storage-total>—</strong></div>
    <button type="button" class="storage-action" data-storage-refresh>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 10a8 8 0 1 0-1.8 7.6M20 4v6h-6"/></svg><span>重新统计</span></button></div>
    <p role="status" data-storage-status></p><div class="group" data-storage-base>
      <div class="row"><span>共享内置工具</span><span class="storage-size" data-usage="shared">—</span></div>
      <button type="button" class="row storage-browse" data-browse-internal>
        <span class="storage-label">DSH 主环境<small>浏览内部文件</small></span>
        <span class="storage-size" data-usage="control">—</span>
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="m6 4 4 4-4 4"/></svg>
      </button>
      <div class="row"><span>其他运行时数据</span><span class="storage-size" data-usage="other">—</span></div>
      <button type="button" class="row storage-browse" data-go="storage-sessions"><span class="storage-label">会话管理<small>对话记录与会话附件</small></span>
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="m6 4 4 4-4 4"/></svg></button>
    </div>
    <div class="storage-section"><div><h2>工作区</h2><small data-storage-subtotal></small></div>
    <button type="button" class="storage-link" data-go="storage-workspaces" hidden><span>全部工作区</span>
      <svg viewBox="0 0 16 16" aria-hidden="true"><path d="m6 4 4 4-4 4"/></svg></button></div>
    <div data-storage-rows></div>
    <p class="storage-note">工作区包含项目文件、依赖及私有环境。手机文件夹按文件大小单列。</p>`;
  const workspaces = `<label class="storage-search">
    <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10" cy="10" r="6"/><path d="m15 15 5 5"/></svg>
    <input type="search" data-storage-search aria-label="工作区名称或路径" placeholder="工作区名称或路径"></label>
    <div class="storage-order"><div class="choices" role="group" aria-label="排序方式">
      <button type="button" data-storage-sort="size" aria-pressed="true">大小</button>
      <button type="button" data-storage-sort="createdAt" aria-pressed="false">创建时间</button>
      <button type="button" data-storage-sort="modifiedAt" aria-pressed="false">修改时间</button>
    </div><button type="button" data-storage-direction aria-label="降序">
      <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 3v14m-5-5 5 5 5-5"/></svg>
    </button></div>
    <p role="status" data-storage-status></p><div data-storage-details></div>
    <div class="storage-pager" hidden><button type="button" class="storage-action" data-storage-prev>上一页</button>
    <span data-storage-page aria-live="polite"></span><button type="button" class="storage-action" data-storage-next>下一页</button></div>`;
  const isStoragePage = page => page === 'storage' || page === 'storage-workspaces';
  const pageSize = 20;
  function format(amount) {
    let size = amount.bytes, unit = 0;
    const units = ['B','KiB','MiB','GiB','TiB'];
    while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit++; }
    return (amount.partial ? t('至少') + ' ' : '') +
      size.toLocaleString(i18n.language, {maximumFractionDigits: size >= 100 ? 0 : 1}) + ' ' + units[unit];
  }
  function element(tag, text, className) {
    const node = document.createElement(tag);
    if (text != null) node.textContent = text;
    if (className) node.className = className;
    return node;
  }
  function sortValue(project, field) {
    if (field === 'size') return project.usage?.bytes;
    const time = project[field];
    return Number.isFinite(time) && time > 0 ? time : null;
  }
  function sortProjects(projects, field, ascending) {
    const collator = new Intl.Collator(i18n.language,{numeric:true});
    return [...projects].sort((a,b) => {
      const av = sortValue(a,field), bv = sortValue(b,field);
      if (av == null && bv != null) return 1;
      if (bv == null && av != null) return -1;
      if (av !== bv && av != null && bv != null) return ascending ? av - bv : bv - av;
      return collator.compare(a.title || a.root,b.title || b.root) || a.id.localeCompare(b.id);
    });
  }
  function mount(pane, detail, post, openWorkspace) {
    const refresh = pane.querySelector('[data-storage-refresh]'), rows = pane.querySelector('[data-storage-rows]');
    const base = pane.querySelector('[data-storage-base]'), link = pane.querySelector('[data-go="storage-workspaces"]');
    const details = detail.querySelector('[data-storage-details]'), search = detail.querySelector('[data-storage-search]');
    const sortButtons = detail.querySelectorAll('[data-storage-sort]'), direction = detail.querySelector('[data-storage-direction]');
    const pager = detail.querySelector('.storage-pager'), prev = pager.querySelector('[data-storage-prev]'), next = pager.querySelector('[data-storage-next]');
    let active = null, pageNumber = 0, busy = null, token = 0, usage = null, failed = false;
    let largestFirst = [], ordered = [], privateTotal = {bytes:0, partial:false};
    let sortField = 'size', ascending = false;
    const external = new Map();
    function request(project = '') {
      busy = project; failed = false;
      paint();
      post({type:'storageQuery', project, token:++token});
    }
    function projectRow(project, full) {
      const group = element('div',null,'storage-project'), line = element('button',null,'row storage-browse');
      line.type = 'button'; line.dataset.storageProject = project.id;
      line.addEventListener('click',() => openWorkspace(project.id));
      const label = element('div',project.title || project.root,'storage-label');
      if (full && !project.external && project.title && project.root !== project.title) label.append(element('small',project.root));
      if (full && sortField !== 'size') {
        const time = sortValue(project,sortField);
        if (time == null) label.append(element('small',t('时间未知')));
        else {
          const date = new Date(time);
          const stamp = element('time',date.toLocaleString(i18n.language,{year:'numeric',month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'}));
          stamp.dateTime = date.toISOString();
          label.append(stamp);
        }
      }
      line.append(label,element('span',project.usage ? format(project.usage) : t('沙箱未初始化'),'storage-size'));
      line.append(link.querySelector('svg').cloneNode(true));
      group.append(line);
      if (full && project.external) {
        const phone = element('div',null,'row storage-phone');
        phone.append(element('span',t('手机文件夹') + (project.location ? ' · ' + project.location : '')));
        const data = external.get(project.id);
        if (data && data !== 'failed') phone.append(element('span',format(data),'storage-size'));
        else {
          const button = element('button',busy === project.id ? t('正在统计…') : t('统计手机文件'),'storage-action');
          button.type = 'button'; button.disabled = busy !== null;
          button.addEventListener('click', () => request(project.id));
          phone.append(button);
        }
        group.append(phone);
        if (data === 'failed') group.append(element('small',t('无法统计，请检查文件夹授权。')));
      }
      return group;
    }
    function paintDetails() {
      const needle = search.value.trim().toLocaleLowerCase();
      const filtered = needle ? ordered.filter(p => [p.title,p.root,p.location].some(v => v?.toLocaleLowerCase().includes(needle))) : ordered;
      const pages = Math.max(1,Math.ceil(filtered.length / pageSize));
      pageNumber = Math.min(pageNumber,pages - 1);
      const offset = pageNumber * pageSize;
      details.replaceChildren(...filtered.slice(offset,offset + pageSize).map(p => projectRow(p,true)));
      if (!filtered.length) details.append(element('p',t(needle ? '没有匹配的工作区' : '暂无工作区'),'storage-note'));
      pager.hidden = pages === 1;
      prev.disabled = pageNumber === 0; next.disabled = pageNumber === pages - 1;
      pager.querySelector('[data-storage-page]').textContent = (pageNumber + 1) + ' / ' + pages;
    }
    function paint() {
      if (!active) return;
      const overview = active === 'storage';
      if (!overview) {
        for (const b of sortButtons) b.setAttribute('aria-pressed',String(b.dataset.storageSort === sortField));
        direction.dataset.ascending = String(ascending);
        direction.setAttribute('aria-label',t(ascending ? '升序' : '降序'));
        direction.title = t(ascending ? '升序' : '降序');
      }
      const status = (overview ? pane : detail).querySelector('[data-storage-status]');
      status.textContent = failed ? t('无法读取存储用量。') : busy === '' ? t('正在统计…') : '';
      refresh.disabled = busy !== null;
      refresh.setAttribute('aria-busy',String(busy === ''));
      if (!usage) return;
      if (!overview) { paintDetails(); return; }
      let sum = privateTotal.bytes, partial = privateTotal.partial;
      for (const field of ['shared', 'control', 'other']) {
        const amount = usage[field];
        base.querySelector('[data-usage="'+field+'"]').textContent = format(amount);
        sum += amount.bytes; partial ||= amount.partial;
      }
      pane.querySelector('[data-storage-total]').textContent = format({bytes:sum,partial});
      pane.querySelector('[data-storage-subtotal]').textContent =
        t('{n} 个工作区').replace('{n}',largestFirst.length.toLocaleString(i18n.language)) + ' · ' + format(privateTotal);
      link.hidden = !largestFirst.length;
      rows.replaceChildren(...largestFirst.slice(0,3).map(p => projectRow(p,false)));
      if (!largestFirst.length) rows.append(element('p',t('暂无工作区'),'storage-note'));
    }
    function orderDetails() {
      ordered = sortField === 'size' && !ascending ? largestFirst : sortProjects(largestFirst,sortField,ascending);
    }
    for (const b of sortButtons) b.addEventListener('click', () => {
      if (b.dataset.storageSort === sortField) return;
      sortField = b.dataset.storageSort; ascending = false; pageNumber = 0;
      orderDetails(); paint();
    });
    direction.addEventListener('click', () => {
      ascending = !ascending; pageNumber = 0;
      orderDetails(); paint();
    });
    search.addEventListener('input', () => { pageNumber = 0; paint(); });
    function turnPage(delta) {
      pageNumber += delta;
      paint();
      detail.querySelector('.settings-content').scrollTop = 0;
    }
    prev.addEventListener('click', () => turnPage(-1));
    next.addEventListener('click', () => turnPage(1));
    refresh.addEventListener('click', () => request());
    base.querySelector('[data-browse-internal]').addEventListener('click', () => post({type:'browseInternalFiles'}));
    return {
      show(page, reconnect = false) {
        const before = active;
        active = isStoragePage(page) ? page : null;
        if (active && (!before || reconnect)) request();
        else if (active) paint();
        else if (before) {
          if (page === 'storage-workspace-detail') { busy = null; token++; return; }
          busy = null; token++; pageNumber = 0; search.value = '';
        }
      },
      receive(message) {
        if (!active || message.token !== token || busy !== message.project) return;
        busy = null;
        if (message.project) {
          external.set(message.project, message.external || 'failed');
          if (message.external && Number.isFinite(message.modifiedAt)) {
            const project = largestFirst.find(p => p.id === message.project);
            if (project) project.modifiedAt = message.modifiedAt;
            if (sortField === 'modifiedAt') orderDetails();
          }
        }
        else if (message.usage) {
          usage = message.usage;
          external.clear();
          largestFirst = sortProjects(usage.rows,'size',false);
          orderDetails();
          privateTotal = {bytes:0,partial:false};
          for (const p of largestFirst) if (p.usage) { privateTotal.bytes += p.usage.bytes; privateTotal.partial ||= p.usage.partial; }
        }
        else failed = true;
        paint();
      },
      paint,
    };
  }
  window.SugarStorage = {mount, controls, workspaces, isStoragePage};
})();
