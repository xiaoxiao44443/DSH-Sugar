(() => {
  'use strict';
  const i18n = window.SugarI18n;
  const t = i18n.text;
  i18n.add({
    '会话管理':['Conversation storage','会話の管理'],
    '会话详情':['Conversation details','会話の詳細'],
    '对话记录与会话附件':['Conversation history and attachments','会話履歴と添付ファイル'],
    '搜索对话或工作区':['Search conversations or workspaces','会話・ワークスペースを検索'],
    '会话状态':['Conversation status','会話の状態'],
    '全部':['All','すべて'],
    '未归档':['Unarchived','未アーカイブ'],
    '已归档':['Archived','アーカイブ済み'],
    '仅剩附件':['Attachments only','添付のみ'],
    '运行中':['In use','実行中'],
    '未命名对话':['Untitled conversation','無題の会話'],
    '未分组':['Ungrouped','未分類'],
    '图片 {n}':['Images {n}','画像 {n}'],
    '文件 {n}':['Files {n}','ファイル {n}'],
    '再显示 {n} 条对话':['Show {n} more','さらに {n} 件表示'],
    '未找到匹配的对话。':['No matching conversations.','一致する会話はありません。'],
    '无法读取会话。':['Could not load conversations.','会話を読み込めませんでした。'],
    '部分数据未能读取。':['Some data could not be read.','一部のデータを読み込めませんでした。'],
    '附件明细':['Attachments','添付ファイル'],
    '附件占用':['Attachment storage','添付の容量'],
    '对话记录':['Conversation history','会話履歴'],
    '这条会话没有保存的附件。':['No attachments saved for this conversation.','この会話に保存された添付はありません。'],
    '读取附件中…':['Loading attachments…','添付を読み込み中…'],
    '无法读取附件。':['Could not load attachments.','添付を読み込めませんでした。'],
    '其他会话也在使用':['Also used by other conversations','ほかの会話でも使用中'],
    '图片':['Image','画像'],
    '文件':['File','ファイル'],
    '存储文件名':['Stored filename','保存ファイル名'],
    '查看附件':['View attachment','添付を開く'],
    '无法打开附件。':['Could not open the attachment.','添付を開けませんでした。'],
    '释放空间':['Free up space','空き容量を増やす'],
    '会话运行中，结束后可清理。':['Cleanup is available after this conversation finishes.','会話の終了後に整理できます。'],
    '暂不可管理':['Unavailable','現在管理できません'],
    '仅删除对话':['Delete conversation only','会話のみ削除'],
    '清理附件':['Clear attachments','添付を削除'],
    '删除对话及附件':['Delete conversation and attachments','会話と添付を削除'],
    '预计可清理 {size}':['Reclaimable: {size}','解放可能な容量 {size}'],
    '工作区原文件和其他对话仍在使用的附件会保留。':['Original workspace files and attachments used by other conversations will be kept.','ワークスペースの元ファイルと、ほかの会話で使用中の添付は残ります。'],
    '正在删除…':['Deleting…','削除中…'],
    '删除完成。':['Deletion complete.','削除しました。'],
    '删除失败，请刷新后重试。':['Deletion failed. Refresh and try again.','削除できませんでした。更新して再試行してください。'],
    '重试':['Retry','再試行'],
  });
  const filters = {all:'全部', open:'未归档', archived:'已归档', active:'运行中', attachments:'仅剩附件'};
  const modes = {conversation:'仅删除对话', attachments:'清理附件', both:'删除对话及附件'};
  const paths = {
    folder:'M3 7V5.5A1.5 1.5 0 0 1 4.5 4H9l2 2h8.5A1.5 1.5 0 0 1 21 7.5v10a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5V7Z',
    arrow:'m9 5 7 7-7 7', image:'M4 3.5h16v17H4z M4.5 17l5-5 4 4.2 2.5-2.5 4 4 M15.5 7.5h.1',
    info:'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z M12 11v5 M12 7.8v.3',
    file:'M6 3.5h8l4 4v13H6z M14 3.5v4.8h4 M9 12h6 M9 16h4',
    conversation:'M4 4.5h16v12H9l-5 4z M8 9h8 M8 12h5',
    attachments:'m8 13.5 7-7a2.4 2.4 0 0 1 3.4 3.4l-9.2 9.2A4.1 4.1 0 0 1 3.4 13.3l10-10',
    both:'M4 6h16 M9 6V3.5h6V6 M6.5 6l1 14h9l1-14 M10 10v6 M14 10v6',
    cleanup:'m13 11 5.5-7.5a1.6 1.6 0 0 1 2.5 1.9L15 13 M6 11l3-2 8 6c-1.6 3.3-5 5.6-10.5 5.5L3 18c2-1.6 3.3-4 3-7Z M8.5 14.5l-3 4.5 M11 16.5l-2.4 3.7'
  };
  function icon(name) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg','svg');
    const path = document.createElementNS(svg.namespaceURI,'path');
    path.setAttribute('d',paths[name]); svg.setAttribute('viewBox','0 0 24 24');
    svg.setAttribute('aria-hidden','true'); svg.classList.add('session-icon');
    svg.append(path); return svg;
  }
  const controls = `<div class="session-search-row">
    <label class="storage-search"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10" cy="10" r="6"/><path d="m15 15 5 5"/></svg>
    <input type="search" data-session-search aria-label="搜索对话或工作区" placeholder="搜索对话或工作区"></label>
    <button type="button" class="storage-action" data-session-refresh aria-label="重新统计" title="重新统计">
    <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 10a8 8 0 1 0-1.8 7.6M20 4v6h-6"/></svg></button></div>
    <div class="choices session-filters" role="group" aria-label="会话状态">${Object.keys(filters).map(k =>
      '<button type="button" data-session-filter="'+k+'" aria-pressed="'+String(k === 'all')+'">'+filters[k]+'</button>').join('')}</div>
    <p data-session-status class="storage-note" role="status"></p><div data-session-rows></div>`;
  const detailControls = `<div data-session-overview></div><div data-session-choices></div>
    <p data-session-detail-status role="status" class="storage-note"></p><div class="session-section-head">
    <h2>附件明细</h2><span data-session-file-count></span></div><div data-session-files></div>
    <div class="storage-pager" data-session-file-pager hidden><button class="storage-action" data-session-file-prev type="button">上一页</button>
    <span data-session-file-page></span><button class="storage-action" data-session-file-next type="button">下一页</button></div>`;
  function node(tag, css, text) {
    const el = document.createElement(tag);
    if (css) el.className = css;
    if (text != null) el.textContent = text;
    return el;
  }
  function button(css, label, handler) {
    const el = node('button',css,label);
    el.type = 'button'; el.addEventListener('click',handler);
    return el;
  }
  function size(bytes) {
    const units = ['B','KiB','MiB','GiB','TiB'];
    let n = bytes, unit = 0;
    while (n >= 1024 && unit < units.length-1) { n /= 1024; unit++; }
    return n.toLocaleString(i18n.language,{maximumFractionDigits:n >= 100 ? 0 : 1})+' '+units[unit];
  }
  function category(row) { return row.attachmentsOnly ? 'attachments' : row.archived ? 'archived' : 'open'; }
  function title(row) { return row.title && row.title !== row.id ? row.title : t('未命名对话'); }
  function groupName(row) { return row.workspace || t('未分组'); }
  function meta(row) {
    const parts = [t(filters[category(row)])];
    if (row.active) parts.unshift(t(filters.active));
    if (row.updatedAt > 0) parts.push(new Date(row.updatedAt).toLocaleDateString(i18n.language));
    return parts.join(' · ');
  }
  const numberLabel = (key,n) => t(key).replace('{n}',n.toLocaleString(i18n.language));
  const listRoute = 'storage-sessions', detailRoute = 'storage-session-detail';
  const perGroup = 12, filePageSize = 24;
  function mount(pane, detailPane, post, openDetail, goList) {
    const list = pane.querySelector('[data-session-rows]'), status = pane.querySelector('[data-session-status]');
    const search = pane.querySelector('[data-session-search]'), refresh = pane.querySelector('[data-session-refresh]');
    const overview = detailPane.querySelector('[data-session-overview]'), files = detailPane.querySelector('[data-session-files]');
    const fileStatus = detailPane.querySelector('[data-session-detail-status]'), choices = detailPane.querySelector('[data-session-choices]');
    const pager = detailPane.querySelector('[data-session-file-pager]');
    const prev = pager.querySelector('[data-session-file-prev]'), next = pager.querySelector('[data-session-file-next]');
    const expanded = new Map(), counts = new Map();
    let route = null, selected = null, rows = [], partial = false, filter = 'all', notice = '';
    let report = null, offset = 0, detailError = false, listError = false, generation = 0;
    let request = null, listNeeded = true, filesNeeded = false, afterDelete = null;
    let cleanupExpanded = false;
    let displayedReport = null, displayedLanguage = null;
    function issue(kind, id = null, mode = null, fileId = null) {
      const token = ++generation;
      request = {kind,token,id,mode,offset,fileId};
      const msg = {type:kind === 'delete' ? 'sessionStorageDelete' : kind === 'open' ? 'sessionStorageOpen' : 'sessionStorageQuery',token};
      if (id) msg.id = id;
      if (kind === 'files') msg.offset = offset;
      if (fileId) msg.fileId = fileId;
      if (mode) msg.mode = mode;
      // The shell publishes the new page authorization before requesting its data.
      setTimeout(() => { if (request?.token === token) post(msg); },0);
    }
    function sync() {
      if (!route || request) return;
      if ((route === listRoute || afterDelete) && listNeeded) { listNeeded = false; issue('list'); }
      else if (route === detailRoute && selected && filesNeeded) { filesNeeded = false; issue('files', selected); }
    }
    function drawRow(row) {
      const item = button('session-entry',null,() => openDetail(row.id));
      item.dataset.sessionManage = row.id;
      const label = node('span','session-entry-label');
      label.append(node('strong','',title(row)),node('small','',meta(row)));
      const count = (row.imageCount || 0)+(row.fileCount || 0);
      if (count) {
        const parts = [];
        if (row.imageCount) parts.push(numberLabel('图片 {n}',row.imageCount));
        if (row.fileCount) parts.push(numberLabel('文件 {n}',row.fileCount));
        if (Number.isFinite(row.attachmentBytes)) parts.push(size(row.attachmentBytes));
        label.append(node('small','session-attachment-summary',parts.join(' · ')));
      }
      item.append(label,icon('arrow'));
      return item;
    }
    function drawGroup(key, items, defaultExpanded) {
      const section = node('section','session-workspace');
      const isOpen = expanded.get(key) ?? defaultExpanded;
      const head = button('session-workspace-head',null,() => {
        expanded.set(key,!isOpen); paintList();
        Array.from(list.querySelectorAll('[data-session-group]')).find(el => el.dataset.sessionGroup === key)?.focus({preventScroll:true});
      });
      head.dataset.sessionGroup = key;
      head.setAttribute('aria-expanded',String(isOpen));
      head.append(icon('folder'),node('span','session-workspace-name',groupName(items[0])),
        node('span','session-workspace-count',items.length.toLocaleString(i18n.language)),icon('arrow'));
      section.append(head);
      if (isOpen) {
        const limit = counts.get(key) ?? perGroup;
        section.append(...items.slice(0,limit).map(drawRow));
        if (items.length > limit) {
          section.append(button('session-more',numberLabel('再显示 {n} 条对话',Math.min(perGroup,items.length-limit)),() => {
            counts.set(key,limit+perGroup); paintList();
          }));
        }
      }
      return section;
    }
    function paintList() {
      const busy = request?.kind === 'list';
      refresh.disabled = Boolean(request);
      refresh.setAttribute('aria-busy',String(busy));
      status.textContent = [notice, listError && '无法读取会话。', busy && '正在统计…', partial && '部分数据未能读取。']
        .filter(Boolean).map(t).join(' ');
      for (const b of pane.querySelectorAll('[data-session-filter]')) b.setAttribute('aria-pressed',String(filter === b.dataset.sessionFilter));
      const needle = search.value.trim().toLocaleLowerCase();
      const groups = new Map();
      for (const row of rows) {
        if (filter !== 'all' && category(row) !== filter && !(filter === 'active' && row.active)) continue;
        if (needle && ![title(row),row.id,groupName(row)].some(x => x.toLocaleLowerCase().includes(needle))) continue;
        const key = row.workspaceKey || row.workspace || '';
        if (!groups.has(key)) groups.set(key,[]);
        groups.get(key).push(row);
      }
      list.replaceChildren(...Array.from(groups,([key,items],i) => drawGroup(key,items,i === 0 || Boolean(needle) || filter !== 'all')));
      if (!groups.size && !busy && !listError) list.append(node('p','storage-note',t('未找到匹配的对话。')));
    }
    function metric(name,value) {
      const stat = node('div','session-metric');
      stat.append(node('dt','',t(name)),node('dd','',value));
      return stat;
    }
    function fileRow(file) {
      const item = node('div','session-file');
      item.dataset.sessionFile = file.kind;
      const isImage = file.kind === 'image' || /^image\//.test(file.mediaType);
      const label = node('span','session-file-label');
      const mime = isImage && /^image\/[\w.+-]+$/.test(file.mediaType) ? file.mediaType.slice(6).toUpperCase() : '';
      const metadata = [size(file.bytes)];
      if (file.width > 0 && file.height > 0) metadata.push(file.width+' × '+file.height);
      if (file.shared) metadata.push(t('其他会话也在使用'));
      const isHash = /^[a-f0-9]{64}$/i.test(file.name);
      const name = isHash ? (mime || t(isImage ? '图片' : '文件'))+' · '+file.name.slice(0,10)+'…' : file.name;
      label.append(node('span','session-file-name',name));
      if (mime && !isHash) metadata.unshift(mime);
      label.append(node('small','',metadata.join(' · ')));
      const badge = node('span','session-file-icon'); badge.append(icon(isImage ? 'image' : 'file'));
      if (typeof file.thumbnail === 'string' && /^data:image\/(?:png|webp|jpeg);base64,[a-zA-Z0-9+/]+={0,2}$/.test(file.thumbnail)) {
        const pic = node('img','session-file-thumbnail');
        pic.alt = ''; pic.loading = 'lazy'; pic.decoding = 'async';
        pic.addEventListener('error',() => pic.remove(),{once:true});
        pic.src = file.thumbnail; badge.append(pic);
      }
      const canOpen = typeof file.fileId === 'string' && /^[a-f0-9]{64}$/.test(file.fileId);
      const entry = canOpen ? button('session-file-open',null,() => {
        if (request || route !== detailRoute || !report?.files.includes(file)) return;
        notice = ''; issue('open',selected,null,file.fileId); paintDetail();
      }) : node('div','session-file-open');
      if (canOpen) {
        entry.dataset.sessionOpen = file.fileId;
        entry.setAttribute('aria-label',t('查看附件')+' · '+name);
      }
      entry.append(badge,label);
      if (canOpen && !isHash) entry.append(icon('arrow'));
      item.append(entry);
      if (isHash) {
        const hash = node('code','session-file-hash',file.name);
        hash.hidden = true;
        const toggle = button('session-hash-toggle',null,() => {
          hash.hidden = !hash.hidden;
          toggle.setAttribute('aria-expanded',String(!hash.hidden));
        });
        toggle.setAttribute('aria-label',t('存储文件名'));
        toggle.setAttribute('aria-expanded','false'); toggle.append(icon('info'));
        item.append(toggle,hash);
      }
      return item;
    }
    function deleteRow(row,mode) {
      if (request || route !== detailRoute || selected !== row.id || !report || report.partial || row.active || !row.canDelete) return;
      notice = ''; issue('delete',row.id,mode); paintDetail();
    }
    function drawActions(row) {
      choices.replaceChildren();
      if (!row || !report || report.partial) return;
      if (row.active || !row.canDelete) {
        choices.append(node('p','session-footnote',t(row.active ? '会话运行中，结束后可清理。' : '暂不可管理')));
        return;
      }
      const hasFiles = Boolean(row.imageCount || row.fileCount);
      const actions = row.attachmentsOnly ? ['attachments'] : hasFiles ? ['conversation','attachments','both'] : ['conversation'];
      const toggle = button('session-cleanup-toggle',null,() => {
        cleanupExpanded = !cleanupExpanded;
        toggle.setAttribute('aria-expanded',String(cleanupExpanded));
        panel.hidden = !cleanupExpanded;
      });
      toggle.dataset.sessionCleanup = '';
      toggle.setAttribute('aria-expanded',String(cleanupExpanded));
      toggle.setAttribute('aria-controls','sugar-session-cleanup-panel');
      toggle.append(icon('cleanup'),node('span','session-cleanup-label',t('释放空间')));
      if (Number.isFinite(row.reclaimableBytes) && row.reclaimableBytes > 0) {
        toggle.append(node('small','',t('预计可清理 {size}').replace('{size}',size(row.reclaimableBytes))));
      }
      toggle.append(icon('arrow'));
      const panel = node('div','session-cleanup-panel');
      panel.id = 'sugar-session-cleanup-panel';
      panel.hidden = !cleanupExpanded;
      const actionList = node('div','session-action-list');
      for (const mode of actions) {
        const el = button('session-action',null,() => deleteRow(row,mode));
        el.dataset.sessionMode = mode; el.disabled = Boolean(request);
        el.append(icon(mode),node('span','',t(modes[mode])));
        actionList.append(el);
      }
      panel.append(actionList);
      if (hasFiles) panel.append(node('p','session-footnote',t('工作区原文件和其他对话仍在使用的附件会保留。')));
      choices.append(toggle,panel);
    }
    function paintDetail() {
      const row = report?.row || rows.find(r => r.id === selected);
      overview.replaceChildren();
      if (row) {
        const group = node('div','session-detail-group'); group.append(icon('folder'),node('span','',groupName(row)));
        const stats = node('dl','session-metrics');
        if (!row.attachmentsOnly && Number.isFinite(row.transcriptBytes)) stats.append(metric('对话记录',size(row.transcriptBytes)));
        if (Number.isFinite(row.attachmentBytes)) stats.append(metric('附件占用',size(row.attachmentBytes)));
        overview.append(group,node('h2','session-detail-title',title(row)),node('p','session-detail-meta',meta(row)),stats);
      }
      const count = report?.totalFiles;
      detailPane.querySelector('[data-session-file-count]').textContent = count == null ? '' : String(count);
      if (displayedReport !== report || displayedLanguage !== i18n.language || detailError) {
        displayedReport = report; displayedLanguage = i18n.language;
        files.replaceChildren(...(report?.files || []).map(fileRow));
        if (report && !count && !report.partial) files.append(node('p','session-empty',t('这条会话没有保存的附件。')));
      }
      files.inert = Boolean(request) && request.kind !== 'open';
      if (detailError) files.append(button('storage-action',t('重试'),() => {
        detailError = false; filesNeeded = true; sync(); paintDetail();
      }));
      pager.hidden = !report || count <= filePageSize;
      if (!pager.hidden) {
        pager.querySelector('[data-session-file-page]').textContent = (1+Math.floor(report.offset/filePageSize))+' / '+Math.ceil(count/filePageSize);
        prev.disabled = Boolean(request) || !report.offset;
        next.disabled = Boolean(request) || report.offset+report.files.length >= count;
      }
      fileStatus.textContent = [notice, detailError && '无法读取附件。',request?.kind === 'delete' ? '正在删除…' :
        (request?.kind === 'files' || request?.kind === 'list') && '读取附件中…', report?.partial && '部分数据未能读取。'].filter(Boolean).map(t).join(' ');
      drawActions(row);
    }
    function paint() { if (route === listRoute) paintList(); else if (route === detailRoute) paintDetail(); }
    function changeFilePage(n) {
      if (request || !report) return;
      offset = n; filesNeeded = true; detailError = false; sync(); paintDetail();
      files.scrollIntoView({block:'start',behavior:'instant'});
    }
    prev.addEventListener('click',() => changeFilePage(Math.max(0,offset-filePageSize)));
    next.addEventListener('click',() => changeFilePage(offset+report.files.length));
    refresh.addEventListener('click',() => { listNeeded = true; notice = ''; listError = false; sync(); paint(); });
    function filterChanged() { expanded.clear(); counts.clear(); paintList(); }
    search.addEventListener('input',filterChanged);
    for (const b of pane.querySelectorAll('[data-session-filter]')) b.addEventListener('click',() => {
      filter = b.dataset.sessionFilter; filterChanged();
    });
    return {
      show(nextRoute, reconnect = false, id = null) {
        const nextView = nextRoute === listRoute || nextRoute === detailRoute ? nextRoute : null;
        const nextId = nextView === detailRoute ? id : null;
        if (!reconnect && nextView === route && nextId === selected) return;
        if (!route || !nextView || reconnect) {
          ++generation; request = null; listNeeded = true; afterDelete = null;
        }
        if (request?.kind === 'open' && selected !== nextId) request = null;
        route = nextView; selected = nextId; report = null; offset = 0; cleanupExpanded = false;
        files.replaceChildren(); displayedReport = null;
        detailError = false; filesNeeded = Boolean(selected); notice = '';
        if (!route) {
          rows = []; partial = false; listError = false; filter = 'all'; search.value = ''; expanded.clear(); counts.clear();
          return;
        }
        sync(); paint();
      },
      receive(msg) {
        if (!route || !request || msg.token !== request.token) return;
        const req = request;
        if (msg.type !== (req.kind === 'delete' ? 'sessionStorageResult' : req.kind === 'open' ? 'sessionStorageOpened' : 'sessionStorage')) return;
        request = null;
        if (req.kind === 'list') {
          listError = !Array.isArray(msg.rows);
          partial = listError || msg.partial === true;
          if (!listError) rows = msg.rows.slice().sort((a,b) => (b.updatedAt || 0)-(a.updatedAt || 0));
          if (afterDelete && !listError) {
            const id = afterDelete; afterDelete = null;
            if (route === detailRoute && selected === id && !rows.some(row => row.id === id)) {
              goList(id); return;
            }
          }
        } else if (req.kind === 'files' && route === detailRoute && selected === req.id && offset === req.offset) {
          detailError = msg.success === false || msg.id !== selected || !Array.isArray(msg.files);
          if (!detailError) {
            const row = msg.row || rows.find(item => item.id === selected);
            report = {row, files:msg.files,offset:msg.offset ?? req.offset,totalFiles:msg.totalFiles ?? msg.files.length,partial:msg.partial === true};
            if (row) {
              const idx = rows.findIndex(item => item.id === selected);
              if (idx >= 0) rows[idx] = row;
            }
            offset = report.offset;
          } else report = null;
        } else if (req.kind === 'open' && route === detailRoute && selected === req.id && req.offset === offset) {
          if (msg.success !== true || msg.id !== selected || msg.fileId !== req.fileId) notice = '无法打开附件。';
        } else if (req.kind === 'delete' && !msg.cancelled) {
          notice = msg.success ? '删除完成。' : '删除失败，请刷新后重试。';
          if (msg.success && req.mode !== 'attachments') window.SugarNavigation?.sessionRemoved?.(req.id);
          // Re-read the list even on failure: the transcript may already have been removed.
          afterDelete = req.id; listNeeded = true; filesNeeded = Boolean(selected); report = null; offset = 0;
        }
        sync(); paint();
      },
      paint,
    };
  }
  window.SugarSessionStorage = {controls,detailControls,mount};
})();
