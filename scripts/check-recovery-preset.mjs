// Node 24+. Pass the unpacked opt/dsh directory, containing node_modules.
// When testing an ARM64 guest on a different host, NODE_PATH may supply that
// host's matching node-addon-require-builtin native binding.
import assert from 'node:assert/strict';
import {cp, mkdtemp, readFile, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

async function check(installation) {
  const assets = fileURLToPath(new URL('../app/src/main/assets/', import.meta.url));
  const packageUrl = pathToFileURL(path.join(installation, 'node_modules', '@deepseek-ai') + path.sep);
  const moduleUrl = (name, file = 'index.js') => new URL(name + '/lib/' + file, packageUrl).href;
  const previousDirectory = process.cwd();
  const scratch = await mkdtemp(path.join(tmpdir(), 'sugar-check-recovery-'));
  let ctx;
  try {
    process.chdir(scratch);
    process.env.DSH_HOME = path.join(scratch, 'home');
    process.env.DSH_TELEMETRY_DISABLED = '1';
    process.env.NARB_DISABLE_NATIVE_CACHE = '1';

    // Relocate the two Android-only paths, preserving the packaged policy,
    // registry implementation and workspace plugin for the real Cordis loader.
    const plugin = path.join(scratch, 'dsh-sugar-recovery');
    await cp(path.join(assets, 'guest/dsh-sugar-recovery'), plugin, {recursive: true});
    const registry = await readFile(path.join(assets, 'recovery/preset-registry.mjs'), 'utf8');
    await writeFile(path.join(plugin, 'preset-registry.mjs'),
      registry.replace('/opt/dsh/node_modules/@deepseek-ai/', () => packageUrl.href));
    const policy = await readFile(path.join(assets, 'recovery/recovery.patch.yml'), 'utf8');
    const overlay = path.join(scratch, 'diagnostics.patch.yml');
    await writeFile(overlay, policy.replaceAll('file:///opt/sugar/dsh-sugar-recovery/',
      () => pathToFileURL(plugin + path.sep).href));

    const {runProfile} = await import(moduleUrl('dsh', 'profile-boot.js'));
    const {createLaunchEnvironmentSnapshot} = await import(moduleUrl('dsh-launch-environment'));
    ({ctx} = await runProfile({profile: 'web', patchFiles: [overlay],
      environment: createLaunchEnvironmentSnapshot([]), args: ['--no-open', '--host', '127.0.0.1', '--port', '0']}));

    const presets = ctx.agentPresets;
    const id = 'sugar-recovery';
    const inventory = await presets.compositionInventory();
    assert.deepEqual(inventory.map(item => item.id), [id]);
    assert.equal(inventory[0].isDefault, true);
    assert.deepEqual(await presets.resolve(id), {id}, inventory[0].broken);
    const active = inventory[0].rows.filter(row => row.enabled);
    for (const row of active) assert.equal(row.fiberState, 2, row.moduleName + ' failed to activate');
    for (const name of ['persona', 'tool-fs', 'tool-fs-search', 'tool-bash', 'tool-ask-user', 'compaction-basic']) {
      assert.ok(active.some(row => row.entryId === name), 'Missing active recovery plugin: ' + name);
    }
    console.log('Recovery preset active:', active.map(row => row.entryId).join(', '));
  } finally {
    try {
      await ctx?.fiber.dispose();
    } finally {
      process.chdir(previousDirectory);
      await rm(scratch, {recursive: true, force: true, maxRetries: 3});
    }
  }
}

if (process.argv.length !== 3) {
  console.error('Usage: node scripts/check-recovery-preset.mjs <unpacked-opt-dsh>');
  process.exitCode = 2;
} else {
  await check(path.resolve(process.argv[2])).catch(error => {
    console.error(error);
    process.exitCode = 1;
  });
}
