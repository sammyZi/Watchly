// node --experimental-strip-types src/lib/title.test.ts
import assert from 'node:assert';
import { hueOf, isSeries, searchableName, titleOf, yearOf } from './title.ts';

assert.equal(
  titleOf('magnet:?xt=urn:btih:ABC123&dn=Big+Buck+Bunny%202008'),
  'Big Buck Bunny 2008'
);
assert.equal(titleOf('magnet:?xt=urn:btih:abcdef0123456789aabb'), 'abcdef0123456789');
assert.equal(titleOf('file:///data/cache/My%20Movie.torrent'), 'My Movie.torrent');
assert.equal(titleOf('https://x.org/dl/thing.torrent?key=1'), 'thing.torrent');
assert.equal(titleOf('magnet:?xt=urn:btih:ABC&dn=100%'), '100%'); // bad escape, not a crash

// Poster colour must be stable per name and spread across the wheel.
assert.equal(hueOf('Spider-Man'), hueOf('Spider-Man'));
assert.notEqual(hueOf('Spider-Man'), hueOf('Spider-Man 2'));
assert.ok(hueOf('') >= 0 && hueOf('') < 360);
for (const name of ['a', 'zzzz', 'The.Matrix.1999.1080p']) {
  const h = hueOf(name);
  assert.ok(h >= 0 && h < 360, `${name} -> ${h}`);
}

console.log('ok');

// Release name -> something a metadata service will match.
assert.equal(searchableName('The.Matrix.1999.1080p.BluRay.x264-GRP'), 'The Matrix');
assert.equal(searchableName('Ted Lasso S01E02 1080p WEB-DL'), 'Ted Lasso');
assert.equal(searchableName('Some Show Season 2 Complete'), 'Some Show');
assert.equal(searchableName('Dune Part Two (2024) 2160p.mkv'), 'Dune Part Two');
assert.equal(searchableName('Arrival'), 'Arrival');

assert.equal(yearOf('The.Matrix.1999.1080p'), '1999');
assert.equal(yearOf('Ted Lasso S01E02'), null);

assert.equal(isSeries('Ted Lasso S01E02 1080p'), true);
assert.equal(isSeries('Show Season 2 Complete'), true);
assert.equal(isSeries('The.Matrix.1999.1080p'), false);
