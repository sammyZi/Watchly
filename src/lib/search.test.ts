// node --experimental-strip-types src/lib/search.test.ts
import assert from 'node:assert';
import { aliasOf, episodeTag, merge, parseEpisode, seasonsIn, health, kindOf, quality, rank, scopeOf, toHits, toTorrentioHits, type Hit } from './search.ts';

const row = (over: Record<string, string | number> = {}) => ({
  id: '1',
  name: 'A Movie 1080p',
  info_hash: 'C39FE3EEFBDB62DA9C27EB6398FF4A7D2E26E7AB',
  seeders: '5',
  leechers: '1',
  size: '1024',
  category: '207',
  ...over,
});

// Placeholder "no results" row and non-video categories are dropped.
assert.deepEqual(
  toHits([
    row({ id: '0', info_hash: '0'.repeat(40) }),
    row({ id: '2', category: '101' }),
  ]),
  []
);

// The top-100 feed hands back numbers where search hands back strings.
const [numeric] = toHits([row({ id: 42, seeders: 7, leechers: 2, size: 9, category: 207 })]);
// Keyed by info hash, not the index's own row id — that is what lets a hit
// from another index merge with this one.
assert.equal(numeric.id, 'c39fe3eefbdb62da9c27eb6398ff4a7d2e26e7ab');
assert.equal(numeric.seeders, 7);
assert.ok(
  numeric.magnet.startsWith('magnet:?xt=urn:btih:c39fe3eefbdb62da9c27eb6398ff4a7d2e26e7ab&dn=')
);
assert.ok(numeric.magnet.includes('&tr='));

assert.equal(quality('Some.Film.2160p.WEB'), '4K');
assert.equal(quality('Some.Film.720p'), '720p');
assert.equal(quality('Some.Film.DVDRip'), null);

assert.equal(kindOf(207), 'movie');
assert.equal(kindOf(208), 'tv');
assert.equal(kindOf(203), 'other');

const [tagged] = toHits([row({ category: 205, imdb: 'tt0133093' })]);
assert.equal(tagged.kind, 'tv');
assert.equal(tagged.imdb, 'tt0133093');
// An empty imdb field is the common case and must not become the string "".
assert.equal(toHits([row({ imdb: '' })])[0].imdb, null);

assert.equal(health(500), 'fast');
assert.equal(health(20), 'good');
assert.equal(health(1), 'weak');
assert.equal(health(0), 'dead');

const hit = (over: Partial<Hit>): Hit => ({
  id: 'x',
  name: 'n',
  magnet: 'm',
  seeders: 0,
  leechers: 0,
  size: 0,
  quality: null,
  scope: 'episode',
  kind: 'movie',
  imdb: null,
  ...over,
});

// Seeders outweigh leechers: 10 seeders beats 20 leechers.
assert.deepEqual(
  rank([hit({ id: 'leech', leechers: 20 }), hit({ id: 'seed', seeders: 10 })]).map((h) => h.id),
  ['seed', 'leech']
);

// A full name match wins over a bigger swarm that only matches half the query.
assert.deepEqual(
  rank(
    [
      hit({ id: 'partial', name: 'The Matrix Pack', seeders: 100 }),
      hit({ id: 'exact', name: 'The Matrix Reloaded', seeders: 80 }),
    ],
    'matrix reloaded'
  ).map((h) => h.id),
  ['exact', 'partial']
);

assert.equal(episodeTag(1, 2), 'S01E02');
assert.equal(episodeTag(10, 11), 'S10E11');

// What a release contains, from its name alone.
assert.equal(scopeOf('Ted Lasso S01E02 1080p WEB-DL'), 'episode');
assert.equal(scopeOf('Ted.Lasso.S01.COMPLETE.720p.WEBRip'), 'season');
assert.equal(scopeOf('Ted Lasso (2020) Season 1 S01 1080p'), 'season');
assert.equal(scopeOf('Ted Lasso Seasons 1-3 COMPLETE 1080p'), 'complete');
assert.equal(scopeOf('Ted Lasso Complete Series 1080p'), 'complete');
assert.equal(scopeOf('Some.Film.2024.1080p.BluRay'), 'episode');
// An episode marker wins over anything else in the name.
assert.equal(scopeOf('Show Complete Series S02E05'), 'episode');

// File name -> which episode it is, for progress read back out of the library.
assert.deepEqual(parseEpisode('Ted.Lasso.S01E02.1080p.mkv'), { season: 1, episode: 2 });
assert.deepEqual(parseEpisode('Show S1 E2.mkv'), { season: 1, episode: 2 });
assert.deepEqual(parseEpisode('Show 1x02.mkv'), { season: 1, episode: 2 });
assert.deepEqual(parseEpisode('Show.S10E11.mkv'), { season: 10, episode: 11 });
assert.equal(parseEpisode('Some.Film.2024.1080p.mkv'), null);


// Which seasons a name claims. This is what keeps a 700-seeder pack for the
// wrong season out of an episode's release list.
assert.deepEqual(seasonsIn('Stranger.Things.S04.COMPLETE.1080p'), [4]);
assert.deepEqual(seasonsIn('Stranger Things - Season 5 Complete'), [5]);
assert.deepEqual(seasonsIn('Stranger.Things.Season.5.Complete.2025.720p'), [5]);
assert.deepEqual(seasonsIn('Stranger Things S04E01 Chapter One'), [4]);
assert.deepEqual(seasonsIn('Show S01-S03 COMPLETE'), [1, 2, 3]);
assert.deepEqual(seasonsIn('Show Seasons 1-3 COMPLETE'), [1, 2, 3]);
assert.deepEqual(seasonsIn('Stranger Things Season 4 S04 Complete'), [4]);
// A name claiming nothing is kept — that is what a complete-series pack and a
// film both look like.
assert.deepEqual(seasonsIn('Show Complete Series 1080p'), []);
assert.deepEqual(seasonsIn('The.Matrix.1999.1080p.BluRay.x264'), []);
// Codecs and channel counts are not seasons.
assert.deepEqual(seasonsIn('Show.S04.DDP5.1.Atmos.x265.10bit'), [4]);
// A separator is required for the spelled-out form only, so this is not S8.
assert.deepEqual(seasonsIn("Ocean's 8 2018 1080p"), []);

// Torrentio packs the numbers into a display string; the first line is the
// release name and the rest is the stats row.
const stream = (over: Record<string, unknown> = {}) => ({
  name: 'Torrentio\n4k HDR',
  title: 'The.Matrix.1999.2160p.BluRay.x265\n\u{1F464} 84 \u{1F4BE} 51.3 GB \u2699\uFE0F 1337x',
  infoHash: 'aa0fd381c944a72cabb3e962d5f727c8694d7dd6',
  ...over,
});

const [one] = toTorrentioHits([stream()], 'movie');
assert.equal(one.name, 'The.Matrix.1999.2160p.BluRay.x265');
assert.equal(one.seeders, 84);
assert.equal(one.size, Math.round(51.3 * 1024 ** 3));
assert.equal(one.quality, '4K');
assert.equal(one.id, 'aa0fd381c944a72cabb3e962d5f727c8694d7dd6');
assert.ok(one.magnet.startsWith('magnet:?xt=urn:btih:aa0fd381'));

// No stats at all is still a usable release, just an unranked one.
assert.equal(toTorrentioHits([stream({ title: 'Some.Release.720p' })], 'movie')[0].seeders, 0);
// Anything without a real info hash cannot be played.
assert.equal(toTorrentioHits([stream({ infoHash: 'nope' })], 'movie').length, 0);

// Merging two indexes: the same hash is one row, and the better-informed copy
// wins rather than whichever arrived first.
const swarm = (id: string, seeders: number, size = 0) =>
  ({ id, seeders, size, name: 'x' }) as Hit;
const merged = merge([[swarm('a', 5, 100), swarm('b', 1)], [swarm('a', 900)]]);
assert.equal(merged.length, 2);
assert.equal(merged.find((h) => h.id === 'a')?.seeders, 900);
// The size the other index knew is not thrown away with it.
assert.equal(merged.find((h) => h.id === 'a')?.size, 100);

// The renamed-title case: an index knows "Dune 2021", never "Dune: Part One".
assert.equal(aliasOf('Dune: Part One'), 'Dune');
assert.equal(aliasOf('Star Wars - A New Hope'), 'Star Wars');
// A title that is already its own shortest form has no second name to try.
assert.equal(aliasOf('The Matrix'), null);
// Hyphens inside a word are part of it.
assert.equal(aliasOf('Spider-Man'), null);
assert.equal(aliasOf('Spider-Man: No Way Home'), 'Spider-Man');

console.log('ok');
