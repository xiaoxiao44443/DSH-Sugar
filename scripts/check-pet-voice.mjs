import assert from 'node:assert/strict';
import { petVoiceRequest } from '../app/src/main/assets/guest/dsh-sugar-notifications/pet-voice.js';

let location = 'host-local';
let phase = 'standby';
let calls = 0;
const service = {
  snapshot: () => ({selection: {providerId: 'current', language: 'auto'}, providers: [
    {id: 'current', location, preparation: {phase}},
  ]}),
  resolve: request => request,
  transcribe: async ({audio, providerId, language}, signal) => {
    assert.equal(audio.length, 364);
    assert.equal(providerId, 'current');
    assert.equal(language, 'auto');
    assert.ok(signal instanceof AbortSignal);
    calls++;
    return {text: '继续'};
  },
};
const ctx = { get: key => key === 'speechToText' ? service : undefined };
const wave = Buffer.alloc(364);
wave.write('RIFF'); wave.writeUInt32LE(356, 4); wave.write('WAVEfmt ', 8);
wave.writeUInt32LE(16, 16); wave.writeUInt16LE(1, 20); wave.writeUInt16LE(1, 22);
wave.writeUInt32LE(16000, 24); wave.writeUInt32LE(32000, 28);
wave.writeUInt16LE(2, 32); wave.writeUInt16LE(16, 34); wave.write('data', 36); wave.writeUInt32LE(320, 40);
const body = {audioBase64: wave.toString('base64')};
assert.deepEqual(await petVoiceRequest({get: () => undefined}, 'voiceAvailable', {}), {available:false});
assert.deepEqual(await petVoiceRequest(ctx, 'voiceAvailable', {}), {available:true});
assert.deepEqual(await petVoiceRequest(ctx, 'voiceTranscribe', body), {text:'继续'});
await assert.rejects(petVoiceRequest(ctx, 'voiceTranscribe', {audioBase64:'1234'}), /invalid-audio/);
phase = 'unprepared';
assert.deepEqual(await petVoiceRequest(ctx, 'voiceAvailable', {}), {available:false});
phase = 'ready';
location = 'cloud';
assert.deepEqual(await petVoiceRequest(ctx, 'voiceAvailable', {}), {available:false});
await assert.rejects(petVoiceRequest(ctx, 'voiceTranscribe', body), /speech-unavailable/);
assert.equal(calls, 1);

const state = async context => (await petVoiceRequest(context, 'voiceStatus', {})).state;
assert.equal(await state(ctx), 'unavailable', 'a cloud selection cannot record desktop pet replies');
location = 'host-local';
for (const [expected, phases] of Object.entries({
  ready: ['ready', 'standby'],
  preparing: ['checking', 'downloading', 'loading', 'waking', 'cancelling'],
  'model-needed': ['unprepared', 'failed', 'cancelled'],
  unavailable: ['future-phase', undefined],
})) {
  for (phase of phases) {
    assert.equal(await state(ctx), expected);
    assert.equal((await petVoiceRequest(ctx, 'voiceAvailable', {})).available, expected === 'ready');
  }
}

const name = '@deepseek-ai/dsh-experimental-voice-input-bundle';
const inventory = (bundles, speechToText) => ({get: key => ({
  speechToText,
  pluginManager: {async listBundles(includeHiddenRowMetadata) {
    assert.equal(includeHiddenRowMetadata, false);
    return bundles;
  }},
})[key]});
assert.equal(await state(inventory([{name: 'unrelated-voice-bundle'}])), 'not-installed');
assert.equal(await state(inventory([{name, availability: 'missing', enabled: false, installed: false}])), 'not-installed');
for (const [availability, installed] of [['installation', false], ['profile', true]]) {
  assert.equal(await state(inventory([{name, availability, installed, enabled: false}])), 'disabled');
}
const enabled = [{name, availability: 'installation', installed: false, enabled: true}];
const noProvider = {snapshot: () => ({selection:{providerId:'missing'}, providers:[]})};
assert.equal(await state(inventory(enabled, noProvider)), 'unavailable');
assert.equal(await state(inventory(enabled)), 'unavailable');
assert.equal(await state(inventory([{name, availability: 'missing', enabled: true, installed: true}])), 'unavailable');
assert.equal(await state(inventory([{name, availability: 'installation', enabled: false, error:{code:'incompatible-version'}}])), 'unavailable');
assert.equal(await state({get: () => undefined}), 'unavailable');
assert.equal(await state(inventory(undefined)), 'unavailable');
assert.equal(await state({get: key => key === 'pluginManager' ? {listBundles: async () => { throw Error('reloading'); }} : undefined}), 'unavailable');
assert.equal(await state(inventory([], {snapshot() { throw Error('reloading'); }})), 'unavailable');
assert.equal(calls, 1, 'status probes never transcribe');
console.log('Pet voice verified: plugin inventory, local model states, WAV validation and no cloud fallback.');
