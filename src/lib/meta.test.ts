// node --experimental-strip-types src/lib/meta.test.ts
import assert from 'node:assert';
import { bestMatch, reviewLinks, toEpisodes, toMeta, toTitles } from './meta.ts';

assert.deepEqual(
  toMeta({
    name: 'The Matrix',
    imdbRating: '8.7',
    genre: ['Action', 'Sci-Fi'],
    poster: 'https://images.metahub.space/poster/small/tt0133093/img',
    description: 'A hacker learns the truth.',
    cast: ['Keanu Reeves'],
    director: ['Lana Wachowski'],
    awards: 'Won 4 Oscars',
    released: '1999-03-31T00:00:00.000Z',
  }),
  {
    title: 'The Matrix',
    year: '1999',
    rating: '8.7',
    genres: ['Action', 'Sci-Fi'],
    poster: 'https://images.metahub.space/poster/small/tt0133093/img',
    plot: 'A hacker learns the truth.',
    cast: ['Keanu Reeves'],
    directors: ['Lana Wachowski'],
    awards: 'Won 4 Oscars',
  }
);

// Cinemeta omits fields rather than nulling them, so every one has to be optional.
assert.deepEqual(toMeta({ name: 'Untagged' }), {
  title: 'Untagged',
  year: null,
  rating: null,
  genres: [],
  poster: null,
  plot: null,
  cast: [],
  directors: [],
  awards: null,
});

// A series carries `year` as a range ("2008–2013"), not a release date.
assert.equal(toMeta({ name: 'x', year: '2008–2013' }).year, '2008–2013');

// A shelf entry needs art and an id; anything short of that cannot be drawn.
const titles = toTitles(
  [
    { name: 'No art', imdb_id: 'tt1' },
    { name: 'No id', poster: 'p' },
    { name: 'Good', id: 'tt2', poster: 'p', background: 'b', runtime: '136 min' },
  ],
  'movie'
);
assert.equal(titles.length, 1);
assert.deepEqual(
  { imdb: titles[0].imdb, kind: titles[0].kind, backdrop: titles[0].backdrop },
  { imdb: 'tt2', kind: 'movie', backdrop: 'b' }
);
assert.equal(titles[0].runtime, '136 min');

// Review links: IMDb is exact, the rest are searches carrying title and year.
const links = reviewLinks({ ...toMeta({ name: 'The Matrix', year: '1999' }), imdb: 'tt0133093', kind: 'movie', backdrop: null, runtime: null });
assert.deepEqual(links.map((l) => l.label), ['IMDb', 'Rotten Tomatoes', 'Metacritic', 'Letterboxd']);
assert.equal(links[0].url, 'https://www.imdb.com/title/tt0133093/reviews');
assert.ok(links[1].url.endsWith('search?search=The%20Matrix%201999'));

// Episodes: ordered by season then number, specials (season 0) kept.
const eps = toEpisodes([
  { id: 'tt:1:2', season: 1, episode: 2, name: 'Two', thumbnail: 't' },
  { id: 'tt:0:1', season: 0, episode: 1, name: 'Special' },
  { id: 'tt:1:1', season: 1, episode: 1, name: 'One', description: 'from description' },
  { name: 'no numbers' },
]);
assert.deepEqual(eps.map((e) => e.id), ['tt:0:1', 'tt:1:1', 'tt:1:2']);
assert.equal(eps[1].overview, 'from description');
assert.equal(eps[0].thumbnail, null);
assert.deepEqual(toEpisodes(undefined), []);


// A search result is thinner than a catalog row: name, poster, releaseInfo.
const thin = toTitles(
  [{ id: 'tt0133093', name: 'The Matrix', poster: 'p', releaseInfo: '1999' }],
  'movie'
);
assert.equal(thin.length, 1);
assert.equal(thin[0].year, '1999');
assert.deepEqual(thin[0].genres, []);
// A series' releaseInfo is a range, and is kept as one.
assert.equal(toMeta({ name: 'x', releaseInfo: '2020–2023' }).year, '2020–2023');

// Matching: the search endpoint answers something for any query, so a
// candidate has to actually be the thing asked for.
const cand = (name: string, kind: 'movie' | 'tv' = 'movie', year: string | null = null) =>
  ({
    ...toMeta({ name, year }),
    imdb: 'tt' + name.length,
    kind,
    backdrop: null,
    runtime: null,
    episodes: [],
  }) as any;

const pool = [cand('House of the Dragon', 'tv'), cand('Ted Lasso', 'tv')];
assert.equal(bestMatch(pool, 'Ted Lasso')?.title, 'Ted Lasso');
// Nothing related: better to return nothing than the most popular result.
assert.equal(bestMatch(pool, 'Breaking Bad'), null);
// A sequel is a weaker match than the exact title.
assert.equal(bestMatch([cand('Dune Part Two'), cand('Dune')], 'Dune')?.title, 'Dune');
// Two films of the same name: the release year decides.
assert.equal(
  bestMatch([cand('Dune', 'movie', '1984'), cand('Dune', 'movie', '2021')], 'Dune', '2021')
    ?.year,
  '2021'
);
// A year matching nothing must not force a wrong pick, only a weaker one.
assert.equal(bestMatch([cand('Dune', 'movie', '1984')], 'Dune', '2021')?.title, 'Dune');
// Punctuation is noise.
assert.equal(
  bestMatch([cand('Spider-Man: No Way Home')], 'Spider Man No Way Home')?.title,
  'Spider-Man: No Way Home'
);

console.log('ok');
