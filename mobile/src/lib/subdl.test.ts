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

// SubDL has entries with no IMDb id (Toy Story 4): the id lookup answers
// "can't find movie or tv", and the film name has to be tried next.
{
  const asked: string[] = [];
  const real = globalThis.fetch;
  globalThis.fetch = (async (url: string) => {
    asked.push(url);
    const body = url.includes('imdb_id=')
      ? { status: false, error: "can't find movie or tv" }
      : { status: true, subtitles: [{ name: 'toy-story-4.zip', url: '/subtitle/1-2.zip', season: 0 }] };
    return new Response(JSON.stringify(body));
  }) as typeof fetch;
  try {
    const { searchSubtitles } = await import('./subdl.ts');
    const subs = await searchSubtitles('Toy Story 4', 'k', { imdb: 'tt1979376', type: 'movie' });
    assert.equal(subs.length, 1);
    assert.equal(asked.length, 2);
    assert.ok(asked[1].includes('film_name=Toy%20Story%204'));
  } finally {
    globalThis.fetch = real;
  }
}
