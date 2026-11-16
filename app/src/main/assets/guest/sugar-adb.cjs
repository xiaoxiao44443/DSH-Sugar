#!/usr/bin/node
'use strict';

const net = require('node:net');
const fs = require('node:fs/promises');
const { constants } = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { once } = require('node:events');

const help = `DSH Sugar ADB · 本机无线调试
  adb devices
  adb get-state
  adb shell [-T] [-n] COMMAND [ARG...]
  adb exec-out COMMAND [ARG...]
  adb ui-dump [--compressed|--verbose] > ui.xml
  adb push LOCAL /absolute/device/file
  adb pull /absolute/device/file [LOCAL]

使用前在 Sugar 设置中连接本机。Agent 控制手机前须读取 android-adb 技能。
shell 和 exec-out 支持一次性命令、退出码及二进制输出重定向。
此版本不转发远端标准输入或提供交互终端。push 和 pull 支持单个普通文件。
`;

function devicePath(value) {
  if (!value?.startsWith('/') || /[\0\r\n]/.test(value) || !path.posix.basename(value) ||
      value.endsWith('/') || ['.', '..'].includes(path.posix.basename(value))) {
    throw Error('请指定完整的设备文件绝对路径。');
  }
  return value;
}

function uiDump(option) {
  if (option !== undefined && option !== '--compressed' && option !== '--verbose') {
    throw Error('用法：adb ui-dump [--compressed|--verbose] > ui.xml');
  }
  // Android's dumper opens a named file and prints a status line on stdout.
  // Use a shell-private file and put only the hierarchy on the workspace stream.
  const command = [
    'umask 077',
    'ui_file=$(mktemp /data/local/tmp/sugar-ui-XXXXXXXX) || exit 1',
    "trap 'rm -f \"$ui_file\"' 0",
    "trap 'exit 1' HUP INT TERM",
    ['uiautomator dump', option, '"$ui_file" >/dev/null || exit 1'].filter(Boolean).join(' '),
    '[ -s "$ui_file" ] || { echo "uiautomator returned no UI hierarchy" >&2; exit 1; }',
    'cat "$ui_file"',
  ].join('\n');
  return {request:{op:'exec-out', command}};
}

function parse(args) {
  if (args[0] === '-s') {
    if (args[1] !== 'sugar-local') throw Error('此工作区仅支持设备 sugar-local。');
    args = args.slice(2);
  }
  const [op, ...rest] = args;
  if (['help', '-h', '--help'].includes(op)) return {help:true};
  if (['devices', 'get-state'].includes(op) && !rest.length) return {request:{op}};
  if (op === 'ui-dump' && rest.length <= 1) return uiDump(rest[0]);
  if (op === 'shell' || op === 'exec-out') {
    if (op === 'shell') {
      while (['-T', '-n'].includes(rest[0])) rest.shift();
      if (rest[0] === '--') rest.shift();
      else if (rest[0]?.startsWith('-')) throw Error('shell 支持 -T、-n 和非交互式命令。');
    }
    if (!rest.length || !rest[0].trim()) throw Error('请指定远端命令。交互式 adb shell 暂不支持。');
    if (op === 'shell') {
      const dump = /^(?:\/system\/bin\/)?uiautomator[ \t]+dump(?:[ \t]+(--compressed|--verbose))?$/.exec(rest.join(' ').trim());
      if (dump) return uiDump(dump[1]);
    } else if (['uiautomator','/system/bin/uiautomator'].includes(rest[0]) && rest[1] === 'dump' &&
               (rest.length === 2 || (rest.length === 3 && ['--verbose','--compressed'].includes(rest[2])))) {
      return uiDump(rest[2]);
    }
    // AOSP joins shell arguments verbatim. exec-out escapes arguments after the command.
    const quote = s => "'" + s.replaceAll("'", "'\\''") + "'";
    const command = op === 'shell' ? rest.join(' ') : [rest[0], ...rest.slice(1).map(quote)].join(' ');
    return {request:{op, command}};
  }
  if (op === 'push' && rest.length === 2) return {request:{op, path:devicePath(rest[1])}, source:rest[0]};
  if (op === 'pull' && (rest.length === 1 || rest.length === 2)) {
    const remote = devicePath(rest[0]);
    return {request:{op, path:remote}, destination:rest[1] ?? path.posix.basename(remote)};
  }
  throw Error('不支持此命令或参数。运行 adb help 查看用法。');
}

function write(stream, bytes) {
  return new Promise((resolve, reject) => stream.write(bytes, err => err ? reject(err) : resolve()));
}

async function writeFilePart(file, chunk) {
  let offset = 0;
  while (offset < chunk.length) {
    const {bytesWritten} = await file.write(chunk, offset, chunk.length - offset);
    if (!bytesWritten) throw Error('无法继续写入本地文件。');
    offset += bytesWritten;
  }
}

async function upload(socket, file, size) {
  const buffer = Buffer.allocUnsafe(64 * 1024);
  let left = size;
  while (left > 0) {
    const {bytesRead} = await file.read(buffer, 0, Math.min(buffer.length, left));
    if (!bytesRead) throw Error('源文件在传输过程中变短。');
    await write(socket, buffer.subarray(0, bytesRead));
    left -= bytesRead;
  }
}

// Consume payloads as socket chunks arrive. Image/file size does not determine memory use.
async function readReply(socket, output) {
  const header = Buffer.alloc(5);
  const status = Buffer.alloc(4);
  let head = 0, type, left = 0;
  for await (const chunk of socket) {
    let offset = 0;
    while (offset < chunk.length) {
      if (head < header.length) {
        const n = Math.min(header.length - head, chunk.length - offset);
        chunk.copy(header, head, offset, offset + n);
        offset += n;
        head += n;
        if (head < header.length) continue;
        type = header[0];
        left = header.readUInt32BE(1);
        if (![1, 2, 3].includes(type) || (type === 3 && left !== 4)) throw Error('ADB 响应帧无效。');
      }
      const n = Math.min(left, chunk.length - offset);
      if (n) {
        const bytes = chunk.subarray(offset, offset + n);
        if (type === 3) bytes.copy(status, status.length - left);
        else if (type === 1 && output) await writeFilePart(output, bytes);
        else await write(type === 1 ? process.stdout : process.stderr, bytes);
        offset += n;
        left -= n;
      }
      if (!left) {
        if (type === 3) return status.readInt32BE();
        head = 0;
      }
    }
  }
  throw Error('ADB 连接在收到命令退出状态之前已关闭。');
}

async function destinationPath(name, remote) {
  try { if ((await fs.stat(name)).isDirectory()) return path.join(name, path.posix.basename(remote)); }
  catch (err) { if (err.code !== 'ENOENT') throw err; }
  return name;
}

async function runRequest({request, source, destination, help:showHelp}) {
  if (showHelp) { await write(process.stdout, help); return 0; }
  const token = process.env.SUGAR_ADB_TOKEN, port = Number(process.env.SUGAR_ADB_PORT);
  if (!token || !Number.isInteger(port) || port < 1 || port > 65535) {
    throw Error('当前进程没有 Sugar 设备通道。请从当前会话的工作区命令或终端调用，参阅 android-adb 或 android-root 技能。');
  }
  let input, output, temp, target, interrupted;
  const socket = new net.Socket();
  const root = request.op === 'root-exec' || request.op === 'root-status';
  const stop = () => { interrupted = Error((root ? 'Root' : 'ADB') + ' 操作已取消。'); socket.destroy(interrupted); };
  const signals = ['SIGINT', 'SIGTERM', 'SIGHUP'];
  signals.forEach(signal => process.once(signal, stop));
  // Sucrune may queue an external signal while Node's guest epoll loop is
  // idle. Bound that wait only for an active Root request, without keeping
  // the process alive. Socket closure then cancels the Android-side command.
  const signalWake = root ? setInterval(() => {}, 250) : null;
  signalWake?.unref();
  // Streams also emit 'error' after a failed write callback (e.g. a closed local pipe).
  const onError = err => socket.destroy(err);
  process.stdout.on('error', onError);
  process.stderr.on('error', onError);
  socket.on('error', () => {});
  try {
    if (source !== undefined) {
      input = await fs.open(source, constants.O_RDONLY | constants.O_NONBLOCK);
      const stat = await input.stat();
      if (!stat.isFile() || !Number.isSafeInteger(stat.size)) throw Error('push 需要可读取的普通文件。');
      request.size = stat.size;
    }
    if (destination !== undefined) {
      target = await destinationPath(destination, request.path);
      const pending = path.join(path.dirname(target), '.sugar-adb-' + randomUUID() + '.tmp');
      output = await fs.open(pending, 'wx', 0o600);
      temp = pending;
    }
    const line = Buffer.from(JSON.stringify({token, ...request}) + '\n');
    if (line.length > 65536) throw Error('ADB 请求头过长。');
    if (interrupted) throw interrupted;
    const connected = once(socket, 'connect');
    socket.connect(port, '127.0.0.1');
    await connected;
    const sent = (async () => {
      await write(socket, line);
      if (input) await upload(socket, input, request.size);
      // Keep the writing half open until the terminal response; peer EOF means cancellation.
    })().catch(err => { socket.destroy(err); return err; });
    let code, sendError;
    try { code = await readReply(socket, output); }
    finally { socket.destroy(); sendError = await sent; }
    if (interrupted) throw interrupted;
    if (code === 0 && sendError) throw sendError;
    if (code === 0 && output) {
      await output.close();
      output = null;
      if (interrupted) throw interrupted;
      await fs.rename(temp, target);
      temp = null;
    }
    return code < 0 || code > 255 ? 1 : code;
  } finally {
    if (signalWake) clearInterval(signalWake);
    socket.destroy();
    try { await input?.close(); }
    finally {
      try { await output?.close(); }
      finally {
        if (temp) await fs.unlink(temp).catch(err => { if (err.code !== 'ENOENT') throw err; });
        signals.forEach(signal => process.off(signal, stop));
        process.stdout.off('error', onError);
        process.stderr.off('error', onError);
      }
    }
  }
}

module.exports = {runRequest};
if (require.main === module) Promise.resolve().then(() => runRequest(parse(process.argv.slice(2)))).then(code => { process.exitCode = code; }, err => {
  process.exitCode = 1;
  if (err.code !== 'EPIPE') process.stderr.write('adb: ' + err.message + '\n');
});
