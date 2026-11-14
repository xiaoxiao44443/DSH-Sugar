import { constants } from 'node:fs'
import * as fs from 'node:fs/promises'
import { createHash, randomUUID } from 'node:crypto'
import { loadOverlayPatches } from '/opt/dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js'

const recovery = process.argv[3] === 'recovery'
const dir = recovery ? '/tmp/recovery-home/.dsh/profiles/web' : '/home/sugar/.dsh/profiles/web'
const path = dir + '/cordis.patch.yml'
const limit = 1024 * 1024
const digest = data => createHash('sha256').update(data).digest('hex')

// DSH creates this same lock with wx. Its normal stale-PID recovery assumes a
// shared PID namespace; separate Sucrune executions can only signal their own
// children. An opaque owner makes DSH wait. Android can recover a killed owner
// using the real PID from the host namespace before the next boot or save.
async function withProfileLock(commit) {
  const lock = dir + '/package.json.lock'
  const staged = dir + '/.sugar-lock-' + randomUUID()
  const owner = 'dsh-sugar-editor:' + process.pid + '\n'
  await fs.writeFile(staged, owner, { flag: 'wx', mode: 0o600 })
  try {
    try {
      await fs.link(staged, lock)
    } catch (error) {
      if (error.code === 'EEXIST') return 4
      throw error
    }
    try {
      return await commit()
    } finally {
      await fs.unlink(lock)
    }
  } finally {
    await fs.unlink(staged)
  }
}

async function revision() {
  if (await fs.realpath(dir) !== dir) throw new Error()
  let handle
  try {
    handle = await fs.open(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK)
  } catch (error) {
    if (recovery && error.code === 'ENOENT') return 'missing'
    throw error
  }
  try {
    const stat = await handle.stat()
    if (!stat.isFile() || stat.size > limit || (recovery && stat.nlink !== 1)) throw new Error()
    return digest(await handle.readFile())
  } finally {
    await handle.close()
  }
}

async function save() {
  const expected = process.argv[2]
  if ((!/^[0-9a-f]{64}$/.test(expected) && !(recovery && expected === 'missing')) || await fs.realpath(dir) !== dir) return 1
  const chunks = []
  let size = 0
  for await (const chunk of process.stdin) {
    size += chunk.length
    if (size > limit) return 1
    chunks.push(chunk)
  }
  const bytes = Buffer.concat(chunks)
  return await withProfileLock(async () => {
    if (await revision() !== expected) return 2
    const tmp = dir + '/.sugar-profile-' + randomUUID() + '.yml'
    const handle = await fs.open(tmp, 'wx', 0o600)
    try {
      try {
        await handle.writeFile(bytes)
      } finally {
        await handle.close()
      }
      try {
        await loadOverlayPatches('dsh', tmp)
      } catch {
        return 3
      }
      if (await revision() !== expected) return 2
      await fs.rename(tmp, path)
      return 0
    } finally {
      await fs.rm(tmp, { force: true })
    }
  })
}

// Output can be retained by the execution manager. Never emit YAML or parser exceptions.
try { process.exitCode = await save() } catch { process.exitCode = 1 }
