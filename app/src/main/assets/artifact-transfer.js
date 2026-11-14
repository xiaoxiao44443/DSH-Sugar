// Android supplies a port only to the active DSH main document.
(() => {
  'use strict';
  if (window !== window.top || window.__dshSugarExportBlob) return;

  const message = source => window.SugarI18n?.text(source) ?? source;
  let port;
  let serial = 0;
  let queued = Promise.resolve();
  const pending = new Map();
  const rawChunkSize = 128 * 1024;
  const attach = event => {
    if (event.data !== '__SUGAR_ARTIFACT_NONCE__' || event.ports.length !== 1 || port) return;
    const channel = event.ports[0];
    port = channel;
    channel.onmessage = ({ data }) => {
      let reply;
      try { reply = JSON.parse(data); } catch { return; }
      const waiting = pending.get(reply.seq);
      if (!waiting) return;
      clearTimeout(waiting.timer);
      pending.delete(reply.seq);
      if (reply.error) waiting.reject(new Error(reply.error));
      else waiting.resolve();
    };
    window.removeEventListener('message', attach);
  };
  window.addEventListener('message', attach);
  function send(op, args = {}) {
    if (!port) return Promise.reject(new Error(message('手机文件传输尚未就绪')));
    const seq = ++serial;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(seq);
        reject(new Error(message('等待手机写入文件超时')));
      }, 60000);
      pending.set(seq, { resolve, reject, timer });
      try { port.postMessage(JSON.stringify({ op, seq, ...args })); } catch (error) {
        clearTimeout(timer);
        pending.delete(seq);
        reject(error);
      }
    });
  }
  function encode(buffer) {
    const bytes = new Uint8Array(buffer);
    const pieces = [];
    for (let i = 0; i < bytes.length; i += 8192) {
      pieces.push(String.fromCharCode(...bytes.subarray(i, i + 8192)));
    }
    return btoa(pieces.join(''));
  }
  async function write(mode, source, name, mediaType) {
    const blob = typeof source === 'function' ? await source() : source;
    if (!blob || !Number.isSafeInteger(blob.size) || blob.size < 0 || typeof blob.slice !== 'function') {
      throw new Error(message('无法获取产物大小或内容'));
    }
    try {
      await send('begin', { mode, name, mime: mediaType || blob.type || '', size: blob.size });
      for (let offset = 0; offset < blob.size; offset += rawChunkSize) {
        const end = Math.min(offset + rawChunkSize, blob.size);
        const buffer = await blob.slice(offset, end).arrayBuffer();
        if (buffer.byteLength !== end - offset) throw new Error(message('文件在导出时发生变化'));
        await send('chunk', { offset, data: encode(buffer) });
      }
      await send('finish');
    } catch (error) {
      await send('abort').catch(() => {});
      throw error;
    }
  }
  window.__dshSugarExportBlob = (mode, source, filename, mediaType) => {
    if (!navigator.userActivation?.isActive || (mode !== 'download' && mode !== 'open')) {
      return Promise.reject(new Error(message('请从产物菜单打开或下载文件')));
    }
    // Consume the UI action before any asynchronous read. Parallel menu clicks
    // get independent destinations, and only one chunk crosses the port at once.
    const transfer = queued.then(() => write(mode, source, filename, mediaType));
    queued = transfer.catch(() => {});
    return transfer;
  };
})();
