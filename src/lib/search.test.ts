// node --experimental-strip-types src/lib/search.test.ts
import assert from 'node:assert';
import { health, kindOf, quality, rank, toHits, type Hit } from './search.ts';

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
assert.equal(numeric.id, '42');
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

console.log('ok');
