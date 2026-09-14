// node --experimental-strip-types site/lib/subs.test.ts
import assert from 'node:assert';
import { parseVtt, shiftVtt, srtToVtt } from './subs.ts';

const vtt = srtToVtt('1\n00:00:05,000 --> 00:00:07,500\nGone\n\n2\n00:02:01,250 --> 00:02:03,000\nHello\n');
assert.ok(vtt.startsWith('WEBVTT'));
// 120s in: the first cue has already ended and goes; the second starts at 1.25s.
const shifted = shiftVtt(vtt, 120);
assert.ok(!shifted.includes('Gone'));
assert.ok(shifted.includes('00:00:01.250 --> 00:00:03.000\nHello'), shifted);
assert.equal(shiftVtt(vtt, 0), vtt);
// Hours-long offsets and hour-form timestamps.
assert.ok(shiftVtt('WEBVTT\n\n01:30:10.000 --> 01:30:12.000\nLate', 3600).includes('00:30:10.000 --> 00:30:12.000'));

// What ffmpeg's webvtt muxer writes for a window (minutes form, two-line cue).
assert.deepEqual(
  parseVtt('WEBVTT\n\n05:00.000 --> 05:03.000\nLine 61\n\n01:05:05.500 --> 01:05:08.000\nTwo\nlines\n'),
  [
    { start: 300, end: 303, text: 'Line 61' },
    { start: 3905.5, end: 3908, text: 'Two\nlines' },
  ]
);
assert.deepEqual(parseVtt('WEBVTT\n'), []);

console.log('subs ok');
