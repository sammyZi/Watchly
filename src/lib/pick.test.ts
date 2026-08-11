// node --experimental-strip-types src/lib/pick.test.ts
import assert from 'node:assert';
import { pickFile } from './pick.ts';

const f = (index: number, name: string, size: number, isVideo = true) => ({
  index,
  name,
  size,
  isVideo,
});

// A film: the feature is the biggest video, and the sample is ignored even
// though it is a video.
assert.equal(
  pickFile([
    f(0, 'Movie.1080p.mkv', 8_000_000_000),
    f(1, 'sample.mkv', 40_000_000),
    f(2, 'Movie.nfo', 900, false),
  ]),
  0
);

// A season pack: the tag decides, not the size.
const pack = [
  f(0, 'Show.S01E01.1080p.mkv', 2_000_000_000),
  f(1, 'Show.S01E02.1080p.mkv', 1_000_000_000),
  f(2, 'Show.S01E03.1080p.mkv', 3_000_000_000),
];
assert.equal(pickFile(pack, 'S01E02'), 1);
// Releases that drop the leading zeros still match.
assert.equal(pickFile([f(0, 'Show.1x02.mkv', 5), f(1, 'Show.S1E2.mkv', 9)], 'S01E02'), 1);
// A tag with no match falls back to the biggest, rather than refusing to play.
assert.equal(pickFile(pack, 'S09E09'), 2);

// Nothing playable at all — the caller has to ask.
assert.equal(pickFile([f(0, 'readme.txt', 10, false)]), null);

console.log('ok');
