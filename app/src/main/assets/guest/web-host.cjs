'use strict';

// A plugin can reinvoke the CLI with process.execArgv. Load the one-shot
// web host hooks here so only this process consumes its launch credentials.
const mode = process.argv[2];
if ((mode !== 'main' && mode !== 'recovery') || process.argv[3] !== 'web') {
  throw new Error('Expected a Sugar web host launch');
}
const entry = require('node:path').resolve(__dirname, '../dsh/node_modules/@deepseek-ai/dsh/lib/bin.js');
process.argv.splice(1, 2, entry);
// Android replaces this file on a system time-zone change. The guest may lack
// inotify, so use a non-persistent stat watcher only in the resident host.
const fs = require('node:fs');
const zoneFile = require('node:path').join(__dirname, 'device-timezone');
function refreshTimeZone() {
  try {
    const zone = fs.readFileSync(zoneFile, 'utf8').trim();
    if (zone && zone !== process.env.TZ) {
      const intlZone = zone.replace(/^GMT([+-])(\d{2})(\d{2})$/, '$1$2:$3');
      new Intl.DateTimeFormat('en', { timeZone: intlZone });
      process.env.TZ = zone;
    }
  } catch { /* Keep the launch zone if a newer value cannot be read. */ }
}
fs.watchFile(zoneFile, { persistent: false, interval: 5000 }, refreshTimeZone);
refreshTimeZone();
require('./browser-handoff.cjs');
if (mode === 'main') require('./project-routing.cjs');
require('node:module').runMain();
