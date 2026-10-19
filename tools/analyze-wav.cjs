const fs = require('node:fs');
const assert = require('node:assert/strict');

function analyze(buffer, snapshot = false) {
  assert(buffer.length >= 44, 'WAV header missing');
  if (snapshot) {
    // Active recordings reserve the header until stop; repair the local snapshot only.
    buffer.write('RIFF', 0); buffer.writeUInt32LE(buffer.length - 8, 4);
    buffer.write('WAVEfmt ', 8); buffer.writeUInt32LE(16, 16);
    buffer.writeUInt16LE(1, 20); buffer.writeUInt16LE(2, 22);
    buffer.writeUInt32LE(48000, 24); buffer.writeUInt32LE(192000, 28);
    buffer.writeUInt16LE(4, 32); buffer.writeUInt16LE(16, 34);
    buffer.write('data', 36); buffer.writeUInt32LE(buffer.length - 44, 40);
  }
  assert.equal(buffer.toString('ascii', 0, 4), 'RIFF');
  assert.equal(buffer.toString('ascii', 8, 16), 'WAVEfmt ');
  assert.equal(buffer.readUInt16LE(20), 1);
  assert.equal(buffer.readUInt16LE(22), 2);
  assert.equal(buffer.readUInt32LE(24), 48000);
  assert.equal(buffer.readUInt16LE(34), 16);
  assert.equal(buffer.toString('ascii', 36, 40), 'data');
  assert.equal(buffer.readUInt32LE(40), buffer.length - 44);
  assert.equal((buffer.length - 44) % 4, 0);
  const seconds = [];
  let nonzero = 0, peak = 0;
  for (let start = 44; start < buffer.length; start += 192000) {
    let energy = 0, count = 0;
    for (let i = start; i + 1 < Math.min(start + 192000, buffer.length); i += 2) {
      const value = buffer.readInt16LE(i);
      if (value !== 0) nonzero++;
      peak = Math.max(peak, Math.abs(value)); energy += value * value; count++;
    }
    seconds.push(energy ? +(20 * Math.log10(Math.sqrt(energy / count) / 32768)).toFixed(2) : null);
  }
  return { durationSeconds: (buffer.length - 44) / 192000, peak, nonzeroSamples: nonzero, rmsDbfsBySecond: seconds };
}

const fixture = Buffer.alloc(44 + 192000);
assert.equal(analyze(fixture, true).nonzeroSamples, 0);
fixture.writeInt16LE(16384, 44);
assert.equal(analyze(fixture).peak, 16384);
assert.equal(analyze(fixture).nonzeroSamples, 1);
if (process.argv[2]) {
  const file = process.argv[2];
  const buffer = fs.readFileSync(file);
  const snapshot = process.argv.includes('--snapshot');
  const result = analyze(buffer, snapshot);
  if (snapshot) fs.writeFileSync(file, buffer);
  fs.writeFileSync(file + '.json', JSON.stringify(result, null, 2));
  const { rmsDbfsBySecond, ...summary } = result;
  console.log(JSON.stringify({ ...summary, silentSeconds: rmsDbfsBySecond.filter(x => x === null).length, analyzedWindows: rmsDbfsBySecond.length }));
}
