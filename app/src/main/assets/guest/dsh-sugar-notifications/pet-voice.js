const voiceBundle = '@deepseek-ai/dsh-experimental-voice-input-bundle';

// Use the same recognizer and language that the user selected in DSH.
function speechSelection(ctx) {
  const service = ctx.get('speechToText');
  const snapshot = service?.snapshot();
  const selected = snapshot?.providers.find(item => item.id === snapshot.selection.providerId);
  return { service, snapshot, selected };
}

function recognizerState(provider) {
  if (provider?.location !== 'host-local') return 'unavailable';
  switch (provider.preparation?.phase) {
    case 'ready':
    case 'standby':
      return 'ready';
    case 'unprepared':
    case 'cancelled':
    case 'failed':
      return 'model-needed';
    case 'checking':
    case 'downloading':
    case 'loading':
    case 'waking':
    case 'cancelling':
      return 'preparing';
    default:
      return 'unavailable';
  }
}

async function voiceStatus(ctx) {
  try {
    const { selected } = speechSelection(ctx);
    if (selected) return recognizerState(selected);
    const bundles = await ctx.get('pluginManager')?.listBundles(false);
    if (!Array.isArray(bundles)) return 'unavailable';
    const bundle = bundles.find(item => item.name === voiceBundle);
    if (!bundle) return 'not-installed';
    if (bundle.error) return 'unavailable';
    if (bundle.availability === 'missing' && bundle.installed === false && bundle.enabled === false) return 'not-installed';
    // The bundled package has availability "installation" even when the profile's installed flag is false.
    if (['installation', 'profile'].includes(bundle.availability) && bundle.enabled === false) return 'disabled';
  } catch {
    // The host can reload a service while the pet is open.
  }
  return 'unavailable';
}

export async function petVoiceRequest(ctx, method, body) {
  if (method === 'voiceStatus') return { state: await voiceStatus(ctx) };
  const { service, snapshot, selected } = speechSelection(ctx);
  const available = recognizerState(selected) === 'ready';
  if (method === 'voiceAvailable') return { available };
  if (method !== 'voiceTranscribe' || !available) throw new Error('speech-unavailable');
  const encoded = body.audioBase64;
  if (typeof encoded !== 'string' || encoded.length > Math.ceil(640044 / 3) * 4)
    throw new Error('invalid-audio');
  const audio = Buffer.from(encoded, 'base64');
  // Sugar records a canonical mono WAV, PCM16 little-endian at 16 kHz.
  if (audio.length <= 44 || audio.length > 640044 || (audio.length - 44) % 2 !== 0 ||
      audio.toString('ascii', 0, 4) !== 'RIFF' || audio.toString('ascii', 8, 16) !== 'WAVEfmt ' ||
      audio.readUInt32LE(4) !== audio.length - 8 || audio.readUInt32LE(16) !== 16 ||
      audio.readUInt16LE(20) !== 1 || audio.readUInt16LE(22) !== 1 ||
      audio.readUInt32LE(24) !== 16000 || audio.readUInt16LE(34) !== 16 ||
      audio.toString('ascii', 36, 40) !== 'data' || audio.readUInt32LE(40) !== audio.length - 44)
    throw new Error('invalid-audio');
  const spec = service.resolve({audio, providerId: selected.id, language: snapshot.selection.language});
  const {text} = await service.transcribe(spec, AbortSignal.timeout(40000));
  return { text };
}
