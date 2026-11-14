import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {mkdtemp, mkdir, readFile, readdir, realpath, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';

// Exercise the same Node --eval entry point, lock, revision check, and disk
// publication as Android. Only the guest paths and DSH parser import are substituted.
const helper = await readFile(new URL('../app/src/main/assets/guest/save-profile.mjs',import.meta.url),'utf8');
const root = await realpath(await mkdtemp(join(tmpdir(),'sugar-recovery-profile-')));
const current = join(root,'temporary'), original = join(root,'normal');
const name = 'cordis.patch.yml', hash = data => createHash('sha256').update(data).digest('hex');
try {
  await mkdir(current); await mkdir(original);
  await writeFile(join(original,name),'normal profile');
  // Pass an extracted dsh-app-boot/lib/index.js to validate with the actual bundled parser.
  const validator = process.argv[2] || join(root,'parser.mjs');
  if (!process.argv[2]) await writeFile(validator,`import {readFile} from 'node:fs/promises';
    export async function loadOverlayPatches(_, file) { if ((await readFile(file,'utf8')).startsWith('!invalid')) throw Error(); }`);
  const script = helper.replace("'/opt/dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js'",JSON.stringify(pathToFileURL(validator).href))
    .replace("'/tmp/recovery-home/.dsh/profiles/web'",JSON.stringify(current))
    .replace("'/home/sugar/.dsh/profiles/web'",JSON.stringify(original));
  const save = (revision,input) => spawnSync(process.execPath,['--input-type=module','--eval',script,'--','save-profile',revision,'recovery'],{input}).status;
  const initial = '- id: test\n', updated = '# 编辑\n- id: test\n';
  assert.equal(save('missing',initial),0);
  assert.equal(await readFile(join(current,name),'utf8'),initial);
  assert.equal(save('missing',updated),2);
  assert.equal(save(hash(initial),'!invalid syntax'),3);
  assert.equal(await readFile(join(current,name),'utf8'),initial);
  await writeFile(join(current,'package.json.lock'),'another writer');
  assert.equal(save(hash(initial),updated),4);
  assert.equal(await readFile(join(current,'package.json.lock'),'utf8'),'another writer');
  await rm(join(current,'package.json.lock'));
  assert.equal(save(hash(initial),updated),0);
  assert.equal(await readFile(join(current,name),'utf8'),updated);
  assert.deepEqual(await readdir(current),[name]);
  assert.equal(await readFile(join(original,name),'utf8'),'normal profile');
  console.log('Recovery profile: first save, conflict, invalid document, active writer, update, original isolation and cleanup passed.');
} finally {
  await rm(root,{recursive:true,force:true});
}
