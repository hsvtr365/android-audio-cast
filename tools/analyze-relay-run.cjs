const fs = require('node:fs');
const assert = require('node:assert/strict');
const source = fs.readFileSync(process.argv[2] || 'test-results/device-relay-log.txt', 'utf8');
const decoder = fs.readFileSync(process.argv[3] || 'test-results/device-live-30min-decode.log', 'utf8');
const segments = new Map();
for (const match of source.matchAll(/^\d\d-\d\d (\d\d):(\d\d):([\d.]+).*HLS sequence=(\d+) buffer=([\d.]+) retained=(\d+)/gm)) {
  const [, hour, minute, second, sequence, buffer, retained] = match;
  segments.set(Number(sequence), { sequence: Number(sequence), clock: Number(hour)*3600+Number(minute)*60+Number(second), buffer: Number(buffer), retained: Number(retained) });
}
const ordered = [...segments.values()].sort((a,b) => a.sequence - b.sequence);
assert(ordered.length > 1, 'Not enough source measurements');
const first = ordered[0], last = ordered.at(-1);
const requested = [...decoder.matchAll(/seg-(\d+)\.aac' for reading/g)].map(match => Number(match[1]));
const errors = [...decoder.matchAll(/Invalid data|Error submitting|Server returned|Non-monotonous|Connection timed out|HTTP error/gi)].map(match => match[0]);
const stats = [...decoder.matchAll(/time=(\d+):(\d+):([\d.]+).*elapsed=(\d+):(\d+):([\d.]+)/g)].at(-1);
const seconds = values => Number(values[0])*3600+Number(values[1])*60+Number(values[2]);
const result = {
  sourceSegmentsObserved: ordered.length,
  firstSequence: first.sequence,
  lastSequence: last.sequence,
  sourceWallSeconds: +(last.clock-first.clock).toFixed(3),
  sourceMediaSeconds: +((last.sequence-first.sequence)*188*1024/48000).toFixed(3),
  maxPlaylistSeconds: Math.max(...ordered.map(x => x.buffer)),
  maxRetainedSegments: Math.max(...ordered.map(x => x.retained)),
  decoderRequestedSegments: requested.length,
  decoderMaxSequenceJump: Math.max(0, ...requested.slice(1).map((value,index) => value-requested[index])),
  decodedSeconds: stats ? seconds(stats.slice(1,4)) : null,
  decoderElapsedSeconds: stats ? seconds(stats.slice(4,7)) : null,
  decoderErrors: errors,
};
result.sourceClockDifferenceSeconds = +(result.sourceWallSeconds-result.sourceMediaSeconds).toFixed(3);
fs.writeFileSync('test-results/relay-run-summary.json', JSON.stringify(result, null, 2));
console.log(JSON.stringify(result));
assert(result.maxPlaylistSeconds <= 28.2 && result.maxRetainedSegments <= 16, 'Default buffer exceeded its bound');
assert.equal(errors.length, 0, 'Decoder reported errors');
assert(result.decoderMaxSequenceJump <= 1, 'Decoder skipped a segment');
