(() => {
  'use strict';
  const i18n = window.SugarI18n, t = i18n.text;
  i18n.add({
    '工作区文件':['Workspace files','ワークスペースのファイル'],
    '文件名':['Name','ファイル名'],
    '目录路径':['Folder path','フォルダのパス'],
    '选择':['Select','選択'],
    '全选':['Select all','すべて選択'],
    '取消全选':['Deselect all','すべて解除'],
    '已选 {n} 项':['{n} selected','{n} 件を選択中'],
    '{n} 项':['{n} items','{n} 件'],
    '删除所选':['Delete selected','選択項目を削除'],
    '清空当前目录':['Empty folder','フォルダを空にする'],
    '退出选择':['Done','選択を終了'],
    '此目录为空。':['This folder is empty.','このフォルダは空です。'],
    '读取目录中…':['Loading folder…','フォルダを読み込み中…'],
    '无法读取目录。':['Could not load folder.','フォルダを読み込めませんでした。'],
    '无法打开文件。':['Could not open file.','ファイルを開けませんでした。'],
    '文件夹':['Folder','フォルダ'],
    '符号链接':['Symbolic link','シンボリックリンク'],
    '只读':['Read-only','読み取り専用'],
    '所选项目过多，请分批删除。':['Too many items selected. Delete them in smaller batches.','選択項目が多すぎます。数回に分けて削除してください。'],
  });
  const art = {
    folder:'M3 7V5.5A1.5 1.5 0 0 1 4.5 4H9l2 2h8.5A1.5 1.5 0 0 1 21 7.5v10a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5V7Z',
    file:'M6 3.5h8l4 4v13H6z M14 3.5v4.8h4 M9 12h6 M9 16h4',
    link:'m9 15 6-6 M8 13l-2 2a2.8 2.8 0 0 0 4 4l3-3 M16 11l2-2a2.8 2.8 0 0 0-4-4l-3 3',
    arrow:'m9 5 7 7-7 7', sort:'M10 4v15m-5-5 5 5 5-5', check:'m5 12 4 4 10-10'
  };
  const svg = name => '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="'+art[name]+'"/></svg>';
  const controls = `<nav class="workspace-file-path" data-wsf-path aria-label="目录路径"></nav>
    <div class="storage-order workspace-file-order"><div class="choices" role="group" aria-label="排序方式">
      <button type="button" data-wsf-sort="bytes" aria-pressed="true">大小</button>
      <button type="button" data-wsf-sort="name" aria-pressed="false">文件名</button>
      <button type="button" data-wsf-sort="modifiedAt" aria-pressed="false">修改时间</button></div>
      <button type="button" class="workspace-file-direction" data-wsf-direction aria-label="降序">${svg('sort')}</button></div>
    <div class="workspace-file-toolbar"><span data-wsf-count></span><div>
      <button type="button" data-wsf-all hidden>全选</button><button type="button" data-wsf-select>选择</button></div></div>
    <div data-wsf-actions class="workspace-file-actions" hidden>
      <button type="button" data-wsf-delete>删除所选</button><button type="button" data-wsf-clear>清空当前目录</button></div>
    <p data-wsf-status role="status" class="storage-note"></p>
    <button type="button" class="storage-action" data-wsf-retry hidden>重试</button><div data-wsf-rows></div>
    <div data-wsf-pager class="storage-pager" hidden><button type="button" data-wsf-prev class="storage-action">上一页</button>
    <span data-wsf-page></span><button type="button" data-wsf-next class="storage-action">下一页</button></div>`;
  function el(tag, name, value) {
    const n = document.createElement(tag);
    if (name) n.className = name;
    if (value != null) n.textContent = value;
    return n;
  }
  function bytesLabel(bytes) {
    const units = ['B','KiB','MiB','GiB','TiB'];
    let n = bytes, u = 0;
    while (n >= 1024 && u < units.length-1) { n /= 1024; u++; }
    return n.toLocaleString(i18n.language,{maximumFractionDigits:n >= 100 ? 0 : 1})+' '+units[u];
  }
  const pageSize = 30;
  const route = 'storage-workspace-detail';
  const bridgeLimit = 262144;
  function selectionData(rows, selection) {
    const indexes = [];
    let all = true;
    for (let i = 0; i < rows.length; i++) {
      if (!rows[i].canDelete) continue;
      if (selection.has(rows[i].path)) indexes.push(i);
      else all = false;
    }
    if (!indexes.length) return null;
    if (all) return {all:true,clear:false};
    const bitBytes = Math.ceil(rows.length / 8);
    const encodedLength = 4 * Math.ceil(bitBytes / 3);
    if (indexes.length < 8192 || JSON.stringify(indexes).length < encodedLength || encodedLength > bridgeLimit)
      return {indexes,clear:false};
    const bits = new Uint8Array(bitBytes);
    for (const index of indexes) bits[index >> 3] |= 1 << (index & 7);
    let binary = '';
    for (let i = 0; i < bits.length; i += 8192)
      binary += String.fromCharCode(...bits.subarray(i,i+8192));
    return {selectionBits:btoa(binary),rowCount:rows.length,clear:false};
  }
  function mount(pane, post, openDir, onListed) {
    const field = name => pane.querySelector('[data-wsf-'+name+']');
    const pathBar = field('path'), entries = field('rows'), status = field('status'), count = field('count');
    const direction = field('direction'), select = field('select'), all = field('all');
    const actions = field('actions'), remove = field('delete'), clear = field('clear'), retry = field('retry');
    const pager = field('pager'), prev = field('prev'), next = field('next');
    const sortButtons = [...pane.querySelectorAll('[data-wsf-sort]')];
    const selection = new Set();
    let id = null, path = '', title = '', rows = [], partial = false, loaded = false, editing = false;
    let page = 0, fieldName = 'bytes', ascending = false, token = 0, pending = null, needList = false;
    let failure = '', notice = '';
    const types = {query:'workspaceFilesQuery',open:'workspaceFilesOpen',delete:'workspaceFilesDelete'};
    const replies = {query:'workspaceFiles',open:'workspaceFilesOpened',delete:'workspaceFilesResult'};
    function issue(kind, params = {}) {
      const msg = {type:types[kind],token:++token,id,path,...params};
      if (kind === 'delete' && JSON.stringify(msg).length > bridgeLimit) {
        notice = '所选项目过多，请分批删除。';
        return;
      }
      pending = {kind,msg};
      // Page state (including the active directory) reaches Android before the read.
      setTimeout(() => { if (pending?.msg === msg) post(msg); },0);
    }
    function sync() {
      if (id && !pending && needList) { needList = false; issue('query'); }
    }
    function order() {
      const collator = new Intl.Collator(i18n.language,{numeric:true});
      const key = row => Number.isFinite(row[fieldName]) && (fieldName === 'bytes' || row[fieldName] > 0) ? row[fieldName] : null;
      return [...rows].sort((a,b) => {
        if (fieldName === 'name') return ascending ? collator.compare(a.name,b.name) : collator.compare(b.name,a.name);
        const av = key(a), bv = key(b);
        if (av == null && bv != null) return 1;
        if (bv == null && av != null) return -1;
        if (av !== bv) return ascending ? av-bv : bv-av;
        return collator.compare(a.name,b.name);
      });
    }
    function drawPath() {
      pathBar.replaceChildren();
      const parts = path ? path.split('/') : [];
      const labels = [title || t('工作区文件'),...parts];
      labels.forEach((label,i) => {
        const last = i === parts.length;
        if (i) { const slash = el('span','workspace-file-slash','/'); slash.setAttribute('aria-hidden','true'); pathBar.append(slash); }
        const part = el(last ? 'span' : 'button','workspace-file-segment',label);
        if (last) part.setAttribute('aria-current','page');
        else { part.type = 'button'; part.addEventListener('click',() => openDir(parts.slice(0,i).join('/'))); }
        pathBar.append(part);
      });
    }
    function describe(row) {
      const parts = [];
      if (row.kind === 'directory') parts.push(t('文件夹'));
      else if (row.kind === 'link') parts.push(t('符号链接'));
      if (Number.isFinite(row.bytes)) parts.push(bytesLabel(row.bytes));
      const time = row.modifiedAt;
      if (Number.isFinite(time) && time > 0) parts.push(new Date(time).toLocaleString(i18n.language,{year:'numeric',month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'}));
      else if (fieldName === 'modifiedAt') parts.push(t('时间未知'));
      if (row.canDelete === false) parts.push(t('只读'));
      return parts.join(' · ');
    }
    function changeSelection(row, button) {
      if (selection.has(row.path)) selection.delete(row.path); else selection.add(row.path);
      button.setAttribute('aria-pressed',String(selection.has(row.path))); paintActions();
    }
    function drawRow(row) {
      const btn = el('button','workspace-file-entry'); btn.type = 'button'; btn.dataset.wsfEntry = row.path;
      const badge = el('span','workspace-file-badge');
      badge.innerHTML = svg(row.kind === 'directory' ? 'folder' : row.kind === 'link' ? 'link' : 'file');
      const label = el('span','workspace-file-label');
      label.append(el('strong','',row.name),el('small','',describe(row)));
      const end = el('span','workspace-file-accessory'); end.setAttribute('aria-hidden','true');
      if (editing) {
        btn.setAttribute('aria-pressed',String(selection.has(row.path)));
        end.innerHTML = svg('check');
      } else if (row.kind !== 'link') end.innerHTML = svg('arrow');
      btn.append(badge,label,end);
      btn.disabled = Boolean(pending) || (editing ? !row.canDelete : row.kind === 'link');
      btn.addEventListener('click',() => {
        if (pending) return;
        if (editing) { changeSelection(row,btn); return; }
        if (row.kind === 'directory') { openDir(row.path); return; }
        notice = ''; issue('open',{entry:row.path}); paint();
      });
      return btn;
    }
    function eligible() { return rows.filter(row => row.canDelete); }
    function paintActions() {
      const any = rows.some(row => row.canDelete);
      const everything = rows.length > 0 && rows.every(row => row.canDelete);
      const selectedAll = any && rows.every(row => !row.canDelete || selection.has(row.path));
      select.textContent = t(editing ? '退出选择' : '选择'); select.setAttribute('aria-pressed',String(editing));
      select.disabled = Boolean(pending) || !loaded || (!any && !editing);
      all.hidden = !editing; all.disabled = Boolean(pending) || !any;
      all.textContent = t(selectedAll ? '取消全选' : '全选');
      actions.hidden = !editing; remove.disabled = Boolean(pending) || !selection.size;
      clear.disabled = Boolean(pending) || !everything || partial;
      count.textContent = loaded ? t(editing ? '已选 {n} 项' : '{n} 项').replace('{n}',(editing ? selection.size : rows.length).toLocaleString(i18n.language)) : '';
    }
    function paint() {
      if (!id) return;
      drawPath(); paintActions();
      for (const b of sortButtons) b.setAttribute('aria-pressed',String(b.dataset.wsfSort === fieldName));
      const dirLabel = t(ascending ? '升序' : '降序');
      direction.title = dirLabel; direction.setAttribute('aria-label',dirLabel); direction.dataset.ascending = String(ascending);
      const pages = Math.max(1,Math.ceil(rows.length/pageSize)); page = Math.min(page,pages-1);
      const sorted = order();
      entries.replaceChildren(...sorted.slice(page*pageSize,(page+1)*pageSize).map(drawRow));
      const reading = needList || pending?.kind === 'query';
      status.textContent = [notice, failure, reading && '读取目录中…',pending?.kind === 'delete' && '正在删除…',
        partial && '部分数据未能读取。',loaded && !rows.length && !partial && '此目录为空。'].filter(Boolean).map(t).join(' ');
      retry.hidden = !failure || Boolean(pending);
      pager.hidden = pages === 1;
      prev.disabled = page === 0 || Boolean(pending); next.disabled = page+1 >= pages || Boolean(pending);
      field('page').textContent = (page+1)+' / '+pages;
    }
    function deleteFiles(clearFolder) {
      if (pending || !editing) return;
      if (clearFolder && (!rows.length || partial || rows.some(row => !row.canDelete))) return;
      const params = clearFolder ? {clear:true} : selectionData(rows,selection);
      if (!params) return;
      notice = ''; failure = ''; issue('delete',params); paint();
    }
    select.addEventListener('click',() => { editing = !editing; selection.clear(); paint(); });
    all.addEventListener('click',() => {
      const candidates = eligible(), add = candidates.some(row => !selection.has(row.path));
      selection.clear(); if (add) for (const row of candidates) selection.add(row.path); paint();
    });
    remove.addEventListener('click',() => deleteFiles(false)); clear.addEventListener('click',() => deleteFiles(true));
    retry.addEventListener('click',() => { failure = ''; needList = true; sync(); paint(); });
    for (const b of sortButtons) b.addEventListener('click',() => {
      if (fieldName !== b.dataset.wsfSort) { fieldName = b.dataset.wsfSort; ascending = fieldName === 'name'; page = 0; paint(); }
    });
    direction.addEventListener('click',() => { ascending = !ascending; page = 0; paint(); });
    function turn(n) { page += n; paint(); entries.scrollIntoView({block:'start',behavior:'instant'}); }
    prev.addEventListener('click',() => turn(-1)); next.addEventListener('click',() => turn(1));
    return {
      show(nextRoute,reconnect = false,workspaceId = null,relativePath = '') {
        const nextId = nextRoute === route ? workspaceId : null, nextPath = nextId ? relativePath || '' : '';
        if (!reconnect && nextId === id && nextPath === path) return;
        if (nextId !== id || reconnect) title = '';
        pending = null;
        id = nextId; path = nextPath; page = 0; rows = []; loaded = false; partial = false;
        selection.clear(); editing = false; failure = ''; notice = ''; needList = Boolean(id);
        sync(); paint();
      },
      receive(msg) {
        if (!id || !pending || msg.token !== pending.msg.token || msg.type !== replies[pending.kind]) return;
        const {kind,msg:sent} = pending; pending = null;
        if (id === sent.id && path === sent.path && !needList) {
          const same = id === msg.id && path === msg.path;
          if (kind === 'query') {
            loaded = same && msg.success !== false && Array.isArray(msg.rows);
            failure = loaded ? '' : '无法读取目录。'; partial = loaded && msg.partial === true;
            rows = loaded ? msg.rows : []; title = msg.title || title;
            if (loaded && !partial) onListed?.(id,path,rows);
          } else if (kind === 'delete' && !msg.cancelled) {
            notice = same && msg.success ? '删除完成。' : '删除失败，请刷新后重试。';
            selection.clear(); editing = false; needList = true;
          } else if (kind === 'open' && (!same || !msg.success)) notice = '无法打开文件。';
        }
        sync(); paint();
      },paint
    };
  }
  window.SugarWorkspaceFiles = {controls,mount};
})();
