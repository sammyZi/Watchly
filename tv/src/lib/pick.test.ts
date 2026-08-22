// node --experimental-strip-types src/lib/pick.test.ts
import assert from 'node:assert';
import { nextEpisode, pickFile, prevEpisode, seasonFiles } from './pick.ts';

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
// A tag that matches nothing inside a pack: refuse rather than play the wrong
// episode. One video is unambiguous, so that still plays.
assert.equal(pickFile(pack, 'S09E09'), null);
assert.equal(pickFile([f(0, 'episode.mkv', 500)], 'S09E09'), 0);

// Nothing playable at all — the caller has to ask.
assert.equal(pickFile([f(0, 'readme.txt', 10, false)]), null);

// Up next inside a season pack, in broadcast order rather than file order.
const season = [
  f(0, 'Show.S01E10.mkv', 100),
  f(1, 'Show.S01E02.mkv', 100),
  f(2, 'Show.S01E01.mkv', 100),
  f(3, 'Show.S02E01.mkv', 100),
  f(4, 'sample.mkv', 5),
];
assert.equal(nextEpisode(season, 2)?.name, 'Show.S01E02.mkv');
// Across a season boundary, and off the end of the pack.
assert.equal(nextEpisode(season, 0)?.name, 'Show.S02E01.mkv');
assert.equal(nextEpisode(season, 3), null);
// A film has nothing to follow it.
assert.equal(nextEpisode([f(0, 'Dune.2021.1080p.mkv', 100)], 0), null);

// Backwards down the same ordering — and off the front, which must not wrap
// round to the last episode of the pack.
assert.equal(prevEpisode(season, 1)?.name, 'Show.S01E01.mkv');
assert.equal(prevEpisode(season, 3)?.name, 'Show.S01E10.mkv');
assert.equal(prevEpisode(season, 2), null);
assert.equal(prevEpisode([f(0, 'Dune.2021.1080p.mkv', 100)], 0), null);

assert.deepEqual(seasonFiles(pack, 1), [0, 1, 2]);
assert.deepEqual(seasonFiles(season, 1), [2, 1, 0]);
assert.deepEqual(seasonFiles(season, 2), [3]);

console.log('ok');
