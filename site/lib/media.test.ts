// node --experimental-strip-types site/lib/media.test.ts
import assert from 'node:assert';
import { needsVideoEncode, parseProbe } from './media.ts';

// Real banner from Big Buck Bunny (MP4: mp3 + AC-3 audio).
const mp4 = parseProbe(`Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'http://127.0.0.1:3000/api/torrent/x/1':
  Duration: 00:10:34.60, start: 0.000000, bitrate: 3481 kb/s
  Stream #0:0[0x1](und): Video: h264 (High) (avc1 / 0x31637661), yuv420p(progressive), 1920x1080 [SAR 1:1 DAR 16:9], 2998 kb/s, 30 fps, 30 tbr, 30k tbn (default)
    Metadata:
  Stream #0:1[0x2](und): Audio: mp3 (mp4a / 0x6134706D), 48000 Hz, stereo, fltp, 160 kb/s (default)
    Metadata:
  Stream #0:2[0x3](und): Audio: ac3 (ac-3 / 0x332D6361), 48000 Hz, 5.1(side), fltp, 320 kb/s (default)
At least one output file must be specified`);
assert.equal(Math.round(mp4.duration * 10), 6346);
assert.deepEqual(mp4.video, { codec: 'h264' });
assert.deepEqual(
  mp4.audio.map((a) => [a.index, a.codec, a.channels, a.language]),
  [[0, 'mp3', 'stereo', null], [1, 'ac3', '5.1(side)', null]]
);
assert.equal(needsVideoEncode(mp4), false);

// A typical MKV: HEVC, two audio languages with titles, a PGS and an SRT.
const mkv = parseProbe(`Input #0, matroska,webm, from 'x':
  Duration: 01:58:02.12, start: 0.000000, bitrate: 5000 kb/s
  Stream #0:0: Video: hevc (Main 10), yuv420p10le(tv), 1920x1080, SAR 1:1 DAR 16:9, 23.98 fps (default)
  Stream #0:1(eng): Audio: eac3, 48000 Hz, 5.1(side), fltp (default)
    Metadata:
      title           : English DDP 5.1
  Stream #0:2(hin): Audio: aac (LC), 48000 Hz, stereo, fltp
    Metadata:
      title           : Hindi
  Stream #0:3(eng): Subtitle: hdmv_pgs_subtitle (default)
  Stream #0:4(eng): Subtitle: subrip
    Metadata:
      title           : English SDH
  Stream #0:5: Video: mjpeg (Baseline), yuvj420p, 600x882 (attached pic)`);
assert.equal(Math.round(mkv.duration), 7082);
assert.deepEqual(mkv.video, { codec: 'hevc' });
assert.equal(needsVideoEncode(mkv), true);
assert.deepEqual(
  mkv.audio.map((a) => [a.index, a.language, a.title, a.isDefault]),
  [[0, 'eng', 'English DDP 5.1', true], [1, 'hin', 'Hindi', false]]
);
// The SRT is the second subtitle stream (after the PGS), and only it is offered.
assert.deepEqual(mkv.subtitles.map((s) => [s.index, s.codec, s.title]), [[1, 'subrip', 'English SDH']]);

console.log('media ok');
