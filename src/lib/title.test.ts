// node --experimental-strip-types src/lib/title.test.ts
import assert from 'node:assert';
import { hueOf, titleOf } from './title.ts';

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
