// node scripts/check-workspace-picker.mjs <unpacked-opt-dsh>
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {mkdir, mkdtemp, readFile, rm, rmdir, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {runInNewContext} from 'node:vm';

if (!process.argv[2]) throw new Error('Pass the unpacked DSH installation containing node_modules');
const installation = resolve(process.argv[2]);
const modules = join(installation, 'node_modules/@deepseek-ai');
const scratch = await mkdtemp(join(tmpdir(), 'sugar-picker-'));
const workspace = join(scratch, 'workspace');
const home = join(scratch, 'home/sugar/aaaa');
let ctx;
try {
  for (const directory of [join(workspace, 'aaa'), join(workspace, 'default-workspace'), join(home, 'home-only')]) {
    await mkdir(directory, {recursive:true});
  }
  // Put only the two Android-specific absolute paths under the host's test directories.
  const source = await readFile(new URL('../app/src/main/assets/guest/workspace-directory-picker.mjs', import.meta.url), 'utf8');
  const plugin = join(scratch, 'picker.mjs');
  await writeFile(plugin, source
    .replace("'/opt/dsh/package.json'", () => JSON.stringify(join(installation, 'package.json')))
    .replace("'/workspace'", () => JSON.stringify(workspace)));
  const {Context} = await import(pathToFileURL(join(modules, 'cordis/lib/index.js')));
  const {default:Picker} = await import(pathToFileURL(plugin));
  ctx = new Context();
  await ctx.plugin(Picker, {maxEntries:1000}).await();
  const api = ctx.directoryPicker.capability();
  assert.equal(api.kind, 'browse');
  assert.equal(api, ctx.directoryPicker.capability());

  // Capture the two packaged UI contributions and forward their listing requests directly to Cordis.
  let ui;
  const bundlePath = 'dsh-client-ui-directory-picker-browse/lib/client.js';
  let client = await readFile(join(modules, bundlePath), 'utf8');
  const hooks = JSON.parse(await readFile(new URL('../app/src/main/assets/guest/project-hooks.json', import.meta.url)));
  const recipe = hooks[bundlePath];
  assert.equal(createHash('sha256').update(client).digest('hex'), recipe.hash);
  for (const [before, after] of recipe.edits) {
    assert.equal(client.split(before).length, 2, 'DSH picker adapter source has changed');
    client = client.replace(before, () => after);
  }
  runInNewContext(client, {
    window:{__ModuleLoader__:{load:({factory}) => { ui = factory(() => ({createElement:(type, props) => ({type, props})})); }}}
  });
  const slots = new Map();
  ui.apply({
    effect(){}, locale:{bind:() => String}, uiWorkspace:{listDirectory:api.list, createDirectory:api.createDirectory},
    slots:{
      inject(name, callback) {
        const result = callback();
        if (result?.next) Array.from(result);
      },
      register({name, inject}, flow) { slots.set(name, {...inject(), flow}); }
    }
  });
  const hero = slots.get('conversation.hero.workspace.directoryFlow');
  const sidebar = slots.get('sidebar.workspaces.directoryFlow');
  for (const [round, entry] of [hero, sidebar, hero, sidebar, hero, sidebar].entries()) {
    if (round === 2) await mkdir(join(workspace, 'bbb'));
    if (round === 4) await rmdir(join(workspace, 'bbb'));
    assert.equal(entry.flow({open:false}), null, 'closing must unmount the previous browser');
    assert.equal(entry.flow({open:true}).props.open, true);
    const list = await entry.listDirectory();
    assert.equal(list.path, workspace);
    const expected = round === 2 || round === 3 ? ['aaa', 'bbb', 'default-workspace'] : ['aaa', 'default-workspace'];
    assert.deepEqual(list.entries.map(row => row.name), expected);
    const explicit = await entry.listDirectory(home);
    assert.equal(explicit.path, home);
    assert.deepEqual(explicit.entries.map(row => row.name), ['home-only']);
  }
  for (const parent of [join(scratch, 'home'), join(scratch, 'home/sugar')]) {
    const created = await hero.createDirectory(parent, 'new project');
    assert.equal(created, join(parent, 'new project'));
    const listing = await sidebar.listDirectory(created);
    assert.equal(listing.path, created);
    assert.equal(listing.crumbs.at(-2).path, parent);
    assert.deepEqual(listing.entries, []);
  }
  await assert.rejects(hero.listDirectory(undefined, AbortSignal.abort()), {name:'AbortError'});
  console.log('PASS: six alternating picker openings, refreshed folder names, explicit paths and aborted listings');
} finally {
  try { await ctx?.fiber.dispose(); } finally { await rm(scratch, {recursive:true, force:true}); }
}
