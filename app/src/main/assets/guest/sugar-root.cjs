#!/usr/bin/node
'use strict';

const fs = require('node:fs/promises');
const {constants} = require('node:fs');
const {runRequest} = require('./adb');

const help = `DSH Sugar · Android Root
  sugar-root --status
  sugar-root -c 'id'
  sugar-root --script ./manage-phone.sh

命令及脚本内容在 Android 的 /system/bin/sh 中执行。--script 读取工作区文件，
将本次读取的完整内容送审并执行。其中的路径属于 Android，/workspace 不会映射。
标准输出、错误与退出码返回工作区，可从 Bash 或 Python subprocess 调用。
Sugar 需先获 Root 管理器授权。普通会话逐次审批实际脚本，完全权限按会话授权执行。
Agent 轮次结束或权限改变会取消其 Root 命令。手动终端按用户输入直接执行。
终端退出或取消会结束该终端正在执行的 Root 命令。
`;

async function main(args) {
  if (!args.length || ['--help','-h','help'].includes(args[0])) {
    process.stdout.write(help);
    return 0;
  }
  if (args.length === 1 && args[0] === '--status') return runRequest({request:{op:'root-status'}});
  if (args.length !== 2 || !['-c','--script'].includes(args[0])) throw Error('运行 sugar-root --help 查看用法。');
  let command = args[1];
  if (args[0] === '--script') {
    const file = await fs.open(args[1], constants.O_RDONLY | constants.O_NONBLOCK);
    try {
      const stat = await file.stat();
      if (!stat.isFile() || stat.size > 16384) throw Error('脚本须为不超过 16 KiB 的 UTF-8 普通文件。');
      const buffer = Buffer.alloc(16385);
      let length = 0;
      while (length < buffer.length) {
        const {bytesRead} = await file.read(buffer, length, buffer.length - length);
        if (!bytesRead) break;
        length += bytesRead;
      }
      command = new TextDecoder('utf-8', {fatal:true}).decode(buffer.subarray(0,length));
    } finally { await file.close(); }
    // Android sh receives the script contents. CRLF and a UTF-8 BOM are common
    // when a workspace script was edited on another platform.
    command = command.replace(/^\uFEFF/, '').replaceAll('\r\n','\n');
  }
  if (!command.trim() || command.includes('\0') || Buffer.byteLength(command) > 16384)
    throw Error('Root 脚本无效或超过 16 KiB。');
  return runRequest({request:{op:'root-exec',command}});
}

main(process.argv.slice(2)).then(code => { process.exitCode = code; }, error => {
  process.exitCode = 1;
  if (error.code !== 'EPIPE') process.stderr.write('sugar-root: ' + error.message + '\n');
});
