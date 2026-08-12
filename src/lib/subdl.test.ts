// node --experimental-strip-types src/lib/subdl.test.ts
import assert from 'node:assert';
import { toSubs } from './subdl.ts';

// The shape SubDL actually answers with: a relative url and a season/episode
// only on series rows.
assert.deepEqual(
  toSubs([
    {
      release_name: 'Dune.Part.Two.2024.1080p.WEB-DL',
      name: 'Dune: Part Two',
      language: 'en',
      url: '/subtitle/3061234-1234567.zip',
      season: null,
      episode: null,
    },
  ]),
  [
    {
      release: 'Dune.Part.Two.2024.1080p.WEB-DL',
      name: 'Dune: Part Two',
      language: 'EN',
      url: 'https://dl.subdl.com/subtitle/3061234-1234567.zip',
      season: null,
      episode: null,
    },
  ]
);

// Episode rows keep their numbers; an absolute url is left alone.
assert.deepEqual(toSubs([{ url: 'https://x/y.zip', season: 1, episode: 2 }])[0], {
  release: '',
  name: '',
  language: '',
  url: 'https://x/y.zip',
  season: 1,
  episode: 2,
});

// A row with nothing to download is not a result.
assert.equal(toSubs([{ release_name: 'x' }]).length, 0);

console.log('ok');
