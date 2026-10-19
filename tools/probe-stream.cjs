// Independent wire-format checks against a running phone; no sound is played.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { setTimeout: delay } = require('node:timers/promises');

async function get(url, type) {
  const response = await fetch(url, { signal: AbortSignal.timeout(10000) });
  assert.equal(response.status, 200, `${url}: HTTP ${response.status}`);
  assert(response.headers.get('content-type')?.includes(type));
  const bytes = Buffer.from(await response.arrayBuffer());
  assert(bytes.length < 2 * 1024 * 1024, 'Unexpectedly large response');
  return bytes;
}

function inspect(bytes) {
  assert.equal(bytes.toString('ascii', 0, 3), 'ID3');
  assert.equal(bytes[3], 4);
  const size = bytes.subarray(6, 10).reduce((n, value) => (n << 7) | value, 0);
  const end = 10 + size;
  assert.equal(bytes.toString('ascii', 10, 14), 'PRIV');
  assert.equal(bytes.toString('ascii', 20, end - 9), 'com.apple.streaming.transportStreamTimestamp');
  const ticks = bytes.readBigUInt64BE(end - 8);
  assert(ticks < 2n ** 33n);
  let offset = end, frames = 0;
  while (offset < bytes.length) {
    assert(offset + 7 <= bytes.length);
    assert.equal(bytes[offset], 255);
    assert.equal(bytes[offset + 1] & 0xf6, 0xf0); // sync, layer zero
    assert.equal((bytes[offset + 2] >> 6) & 3, 1); // AAC-LC
    assert.equal((bytes[offset + 2] >> 2) & 15, 3); // 48 kHz
    assert.equal(((bytes[offset + 2] & 1) << 2) | (bytes[offset + 3] >> 6), 2);
    assert.equal(bytes[offset + 6] & 3, 0); // one raw data block
    const length = ((bytes[offset + 3] & 3) << 11) | (bytes[offset + 4] << 3) | (bytes[offset + 5] >> 5);
    assert(length > 7 && offset + length <= bytes.length);
    offset += length; frames++;
  }
  assert.equal(offset, bytes.length);
  return { frames, ticks: Number(ticks), duration: frames * 1024 / 48000, bytes: bytes.length };
}

async function main() {
  const url = process.argv[2]; assert(url, 'Usage: node tools/probe-stream.cjs <stream-url> [seconds=60] [output.json]');
  const seconds = Number(process.argv[3] || 60); assert(Number.isFinite(seconds) && seconds >= 1 && seconds <= 3600);
  const output = process.argv[4] || 'test-results/live-probe.json';
  const samples = []; const started = performance.now();
  let previous = -1;
  try {
    do {
      const playlist = (await get(url, 'mpegurl')).toString('utf8');
      assert(playlist.startsWith('#EXTM3U\n'));
      assert(!playlist.includes('#EXT-X-ENDLIST'));
      const sequence = Number(playlist.match(/#EXT-X-MEDIA-SEQUENCE:(\d+)/)?.[1]);
      assert(Number.isInteger(sequence) && sequence >= previous); previous = sequence;
      const segments = [...playlist.matchAll(/#EXTINF:([\d.]+),\n([^\n]+)/g)];
      assert(segments.length >= 3 && segments.length <= 11);
      const [_, duration, path] = segments.at(-1);
      const data = inspect(await get(new URL(path, url), 'audio/aac'));
      assert(Math.abs(data.duration - Number(duration)) < 0.00001);
      assert(data.duration >= 3 && data.duration <= 5);
      samples.push({ at: new Date().toISOString(), sequence, visibleSegments: segments.length, ...data });
      fs.writeFileSync(output, JSON.stringify({ elapsedSeconds: (performance.now() - started) / 1000, samples }, null, 2));
      const remaining = seconds * 1000 - (performance.now() - started);
      if (remaining > 0) await delay(Math.min(10000, remaining));
    } while (performance.now() - started < seconds * 1000);
    assert(samples.at(-1).sequence > samples[0].sequence || seconds < 5, 'Playlist stopped advancing');
    console.log(JSON.stringify({ passed: true, elapsedSeconds: (performance.now()-started)/1000, samples: samples.length, firstSequence: samples[0].sequence, lastSequence: samples.at(-1).sequence }));
  } catch (error) {
    fs.writeFileSync(output, JSON.stringify({ error: error.message, samples }, null, 2));
    throw error;
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
