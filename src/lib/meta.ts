/**
 * Title metadata for an IMDb id, from Stremio's public Cinemeta service.
 *
 * Chosen over TMDB/OMDb because it needs no API key: the index gives us a `tt`
 * id and nothing else, and asking the user to register for one just to see a
 * rating would be worse than showing no rating at all.
 */
import AsyncStorage from '@react-native-async-storage/async-storage';
import { net } from './net.ts';
import { isSeries, searchableName, yearOf } from './title.ts';

const BASE = 'https://v3-cinemeta.strem.io/meta';
/** Where a popularity shelf redirects to — asked for directly to save every
 *  rail a round trip. It only serves the precomputed lists: a `search=` handed
 *  to this host is ignored, and it answers with the same top 50 either way. */
const CATALOG = 'https://cinemeta-catalogs.strem.io/top/catalog';
/** Search has to go through the front door, which actually reads the query. */
const SEARCH = 'https://v3-cinemeta.strem.io/catalog';
/**
 * Anime, which Cinemeta has no genre for — its list stops at "Animation", and
 * that is half western cartoons.
 *
 * Stremio's Kitsu addon speaks the same catalog format, and most of its rows
 * carry a real IMDb id; `toTitles` drops the handful that do not, so a title
 * from here behaves like any other — the detail page still fills itself from
 * Cinemeta by id, and releases are still found by name.
 */
const ANIME = 'https://anime-kitsu.strem.fun/catalog/series/kitsu-anime-trending.json';

/** Not one of Cinemeta's genres — asking for it switches catalogs entirely.
 *  Everything that browses by genre goes through `loadCatalog`, so naming it
 *  here is all it takes for the shelf, the grid and the chip to work. */
export const ANIME_GENRE = 'Anime';

/**
 * The genres worth offering, in Cinemeta's own spelling — anything not in its
 * vocabulary comes back as an empty shelf.
 *
 * Trimmed to the ones both the film and series catalogues carry, so a chosen
 * genre always has something behind it whichever kind it is asked for. Anime
 * leads because it is the one entry that is not a Cinemeta genre at all.
 */
export const GENRES = [
  ANIME_GENRE,
  'Action',
  'Adventure',
  'Animation',
  'Comedy',
  'Crime',
  'Documentary',
  'Drama',
  'Family',
  'Fantasy',
  'History',
  'Horror',
  'Mystery',
  'Romance',
  'Sci-Fi',
  'Sport',
  'Thriller',
  'War',
  'Western',
];

/** Anime only exists as a series catalogue; everything else defaults to film
 *  unless the viewer's own history says otherwise. */
export const kindOfGenre = (genre: string): 'movie' | 'tv' =>
  genre === ANIME_GENRE ? 'tv' : 'movie';

export type Episode = {
  /** Cinemeta's own "tt10986410:1:2" — unique, and stable enough to key a
   *  watch record on. */
  id: string;
  season: number;
  episode: number;
  name: string;
  overview: string | null;
  thumbnail: string | null;
  released: string | null;
};

/** A browsable title. Same payload as Meta, plus the art a shelf needs. */
export type Title = Meta & {
  imdb: string;
  kind: 'movie' | 'tv';
  backdrop: string | null;
  runtime: string | null;
  /** Series only, and only from /meta — a catalog row carries no episodes. */
  episodes: Episode[];
};

export type Meta = {
  title: string;
  year: string | null;
  rating: string | null;
  genres: string[];
  poster: string | null;
  plot: string | null;
  cast: string[];
  directors: string[];
  /** Cinemeta's own prose — "Won 4 Oscars. 42 wins & 52 nominations total". */
  awards: string | null;
};

/** One request per key for the life of the process — ids repeat constantly
 *  across results, and the answer does not change while the app is open. */
const cache = new Map<string, Promise<any>>();

/** A day. Popularity charts move slowly and a rating never moves fast. */
const TTL = 24 * 60 * 60 * 1000;

/**
 * Bumped whenever `Title` gains a field.
 *
 * A stored entry is a snapshot of the shape that wrote it, so an older one
 * comes back missing whatever was added since — and the screens then read
 * `undefined.map`. The version is part of the key, so old entries are simply
 * never found again.
 */
const STORE = 'meta:3:';

/**
 * Memory cache over a disk cache over the network.
 *
 * The disk layer is what stops the home page rebuilding itself from scratch on
 * every launch: shelves paint from storage first, and a stale copy is still
 * served if the fetch then fails, so the page works on a dead connection.
 */
async function remember<T>(key: string, fetcher: () => Promise<T>): Promise<T> {
  let stale: T | undefined;
  try {
    const raw = await AsyncStorage.getItem(`${STORE}${key}`);
    if (raw) {
      const { at, value } = JSON.parse(raw);
      if (Date.now() - at < TTL) return value as T;
      stale = value as T;
    }
  } catch {
    // Unreadable entry: treat as absent.
  }

  try {
    const value = await fetcher();
    AsyncStorage.setItem(`${STORE}${key}`, JSON.stringify({ at: Date.now(), value })).catch(
      () => {}
    );
    return value;
  } catch (e) {
    if (stale !== undefined) return stale;
    throw e;
  }
}

/**
 * One row per IMDb id, first wins.
 *
 * Applied on the way *out* of the disk cache as well as on the way in. A list
 * stored by an older build carries whatever duplicates that build allowed, and
 * it is served for a day — bumping the cache version would drop the stale
 * entries but leave the next such bug live for 24h all over again. Cleaning at
 * the point of use is the one place that cannot go stale.
 */
export function uniqueById(titles: Title[]): Title[] {
  const seen = new Set<string>();
  return titles.filter((t) => {
    if (!t?.imdb || seen.has(t.imdb)) return false;
    seen.add(t.imdb);
    return true;
  });
}

/** Drop every cached shelf and title. Storage is bounded but not small. */
export async function clearMetaCache(): Promise<void> {
  cache.clear();
  const keys = await AsyncStorage.getAllKeys();
  // Every version, not just the current one — this is the "clear it" path.
  await AsyncStorage.multiRemove(keys.filter((k) => k.startsWith('meta:')));
}

export function loadTitle(imdb: string, kind: 'movie' | 'tv'): Promise<Title | null> {
  const key = `title:${kind}:${imdb}`;
  const hit = cache.get(key) as Promise<Title | null> | undefined;
  if (hit) return hit;

  const pending = remember(key, () => fetchTitle(imdb, kind)).catch(() => {
    // A miss must not be cached as permanent — Cinemeta 404s for anything it
    // does not carry, but a dropped connection deserves another go.
    cache.delete(key);
    return null;
  });
  cache.set(key, pending);
  return pending;
}

async function fetchTitle(imdb: string, kind: 'movie' | 'tv'): Promise<Title | null> {
  const res = await net(`${BASE}/${kind === 'tv' ? 'series' : 'movie'}/${imdb}.json`);
  if (!res.ok) return null;
  const { meta } = await res.json();
  return meta ? (toTitles([{ ...meta, imdb_id: imdb }], kind, true)[0] ?? null) : null;
}

/**
 * One shelf of the home page: the most popular titles of `kind`, optionally
 * narrowed to a genre. Genre is Cinemeta's own vocabulary — "Action", "Comedy".
 */
/** Shelves that have already resolved, readable without awaiting anything —
 *  a remount then paints from this on its very first render instead of
 *  flashing a spinner while a settled promise resolves a tick later. */
const settled = new Map<string, Title[]>();

export const peekCatalog = (kind: 'movie' | 'tv', genre?: string): Title[] | null =>
  settled.get(`shelf:${kind}:${genre ?? ''}`) ?? null;

export function loadCatalog(kind: 'movie' | 'tv', genre?: string): Promise<Title[]> {
  const key = `shelf:${kind}:${genre ?? ''}`;
  const hit = cache.get(key) as Promise<Title[]> | undefined;
  if (hit) return hit;

  const type = kind === 'tv' ? 'series' : 'movie';
  const url =
    genre === ANIME_GENRE
      ? ANIME
      : `${CATALOG}/${type}/top${genre ? `/genre=${encodeURIComponent(genre)}` : ''}.json`;
  const pending = remember(key, async () => {
    const res = await net(url);
    if (!res.ok) throw new Error(`Catalog returned ${res.status}`);
    return toTitles((await res.json())?.metas ?? [], kind);
  })
    .then((all) => {
      const titles = uniqueById(all);
      settled.set(key, titles);
      return titles;
    })
    .catch(() => {
      cache.delete(key);
      return [] as Title[];
    });
  cache.set(key, pending);
  return pending;
}

/** How a shelf can be ordered. `popular` is the order the catalogue came in. */
export type Sort = 'popular' | 'rating' | 'year' | 'title';

export const SORTS: { key: Sort; label: string }[] = [
  { key: 'popular', label: 'Popular' },
  { key: 'rating', label: 'Top rated' },
  { key: 'year', label: 'Newest' },
  { key: 'title', label: 'A–Z' },
];

/**
 * Order a shelf. Titles missing the field being sorted on go last rather than
 * counting as zero — an unrated title is unknown, not bad — and keep the
 * catalogue's own order among themselves.
 */
export function sortTitles<T extends { title: string; rating: string | null; year: string | null }>(
  titles: T[],
  sort: Sort
): T[] {
  if (sort === 'popular') return titles;
  const number = (value: string | null) => {
    const n = parseFloat(value ?? '');
    return Number.isFinite(n) ? n : null;
  };
  const by = (t: T) => (sort === 'rating' ? number(t.rating) : number(t.year));
  return [...titles].sort((a, b) => {
    if (sort === 'title') return a.title.localeCompare(b.title);
    const x = by(a);
    const y = by(b);
    if (x === null || y === null) return x === y ? 0 : x === null ? 1 : -1;
    return y - x;
  });
}

/**
 * Title search — the poster-first front door. Not persisted: queries are
 * one-off, and filling storage with them would crowd out the shelves.
 */
export function searchTitles(query: string, kind: 'movie' | 'tv'): Promise<Title[]> {
  const key = `find:${kind}:${query.toLowerCase()}`;
  const hit = cache.get(key) as Promise<Title[]> | undefined;
  if (hit) return hit;

  const type = kind === 'tv' ? 'series' : 'movie';
  const pending = net(`${SEARCH}/${type}/top/search=${encodeURIComponent(query)}.json`)
    .then((r) => (r.ok ? r.json() : { metas: [] }))
    .then((body) => toTitles(body?.metas ?? [], kind))
    .catch(() => {
      cache.delete(key);
      return [] as Title[];
    });
  cache.set(key, pending);
  return pending;
}

/**
 * What a set of watched titles is mostly made of, most-watched first.
 *
 * Every genre that turns up counts, so one film is enough to shape the page —
 * an earlier version wanted two before it would say anything, which on a normal
 * library meant it said nothing at all and the home page stayed on the stock
 * shelves. Pass the titles in the order they matter: ties keep that order, so
 * putting what is being watched now at the front is what makes it win.
 *
 * Each genre carries the kind it was mostly watched as, so a shelf built from
 * it lands on the right catalog.
 */
export function topGenres(
  titles: Title[],
  limit = 3
): { genre: string; kind: 'movie' | 'tv' }[] {
  const tally = new Map<string, { seen: number; films: number }>();
  for (const title of titles) {
    for (const genre of title.genres ?? []) {
      const at = tally.get(genre) ?? { seen: 0, films: 0 };
      at.seen++;
      if (title.kind === 'movie') at.films++;
      tally.set(genre, at);
    }
  }
  return [...tally]
    .sort((a, b) => b[1].seen - a[1].seen)
    .slice(0, limit)
    .map(([genre, at]) => ({ genre, kind: at.films * 2 >= at.seen ? 'movie' : 'tv' }));
}

/** The same, from ids. Every lookup is a cache hit for anything already opened. */
export async function watchedGenres(
  seen: { imdb: string; kind: 'movie' | 'tv' }[],
  limit = 3
): Promise<{ genre: string; kind: 'movie' | 'tv' }[]> {
  const titles = await Promise.all(seen.map((s) => loadTitle(s.imdb, s.kind)));
  return topGenres(titles.filter((t): t is Title => !!t), limit);
}

const norm = (s: string) =>
  s
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();

/**
 * The candidate that is actually the thing asked for, or nothing.
 *
 * Cinemeta's search ranks by popularity and answers *something* for any query,
 * so taking the first result lands on a confidently wrong title — the reason
 * the library used to open somebody else's film. Every candidate has to earn
 * its place:
 *
 *  - the same name wins outright;
 *  - a name that merely starts with the query is a sequel or a spin-off, good
 *    enough only when nothing better turned up;
 *  - a name missing any word of the query is not a match at all.
 *
 * The year, when the release name carries one, settles the rest — there are two
 * films called Dune. It is only applied to films: a series' `year` is when it
 * first aired, which rarely matches the season being watched.
 */
export function bestMatch(
  titles: Title[],
  query: string,
  year?: string | null
): Title | null {
  const want = norm(query);
  if (!want) return null;
  const words = want.split(' ');

  let best: { title: Title; score: number } | null = null;
  for (const title of titles) {
    const got = norm(title.title);
    let score = 0;
    if (got === want) score = 100;
    else if (got.startsWith(want)) score = 60;
    else if (words.every((w) => got.split(' ').includes(w))) score = 45;
    else continue;

    if (year && title.kind === 'movie' && title.year) {
      score += title.year.slice(0, 4) === year ? 25 : -30;
    }
    if (!best || score > best.score) best = { title, score };
  }

  // Below the weakest positive rule the "match" is a guess, and a guess opens
  // the wrong page with no sign that anything went wrong.
  return best && best.score >= 45 ? best.title : null;
}

/**
 * What a torrent actually is, from its name alone.
 *
 * History only ever stored release names, so this is the bridge from a stored
 * torrent back to its title page. Returns null rather than guessing.
 */
export async function resolveTitle(releaseName: string): Promise<Title | null> {
  const query = searchableName(releaseName);
  if (query.length < 2) return null;
  const year = yearOf(releaseName);
  const kind = isSeries(releaseName) ? 'tv' : 'movie';
  const [likely, other] = await Promise.all([
    searchTitles(query, kind),
    searchTitles(query, kind === 'tv' ? 'movie' : 'tv'),
  ]);
  return bestMatch(likely, query, year) ?? bestMatch(other, query, year);
}

/**
 * Catalog rows without art are unusable on a poster shelf, so they are dropped.
 *
 * `withEpisodes` is off by default, and that is the whole point: a catalog row
 * carries a `videos` array holding the twenty most recent episodes and nothing
 * else. Copying that onto a Title made a series look like it began at season
 * six — the page had "some" episodes, so it never asked for the real ones.
 * Only `/meta` returns the full list, so only `/meta` is allowed to fill it.
 */
export function toTitles(
  metas: Record<string, any>[],
  kind: 'movie' | 'tv',
  withEpisodes = false
): Title[] {
  // One row per id. The anime catalogue lists every season separately —
  // "Attack on Titan", "Attack on Titan Season 2", "…: The Final Season" — and
  // all five carry the same IMDb id, which is a duplicate React key on the
  // shelf and five tiles opening the same page. The first is kept because that
  // is the base entry, and the detail page loads every season from the id
  // anyway.
  const seen = new Set<string>();
  return metas
    .filter((m) => m.poster && /^tt\d+$/.test(String(m.imdb_id ?? m.id ?? '')))
    .filter((m) => {
      const id = String(m.imdb_id ?? m.id);
      if (seen.has(id)) return false;
      seen.add(id);
      return true;
    })
    .map((m) => ({
      ...toMeta(m),
      imdb: String(m.imdb_id ?? m.id),
      kind,
      episodes: withEpisodes ? toEpisodes(m.videos) : [],
      backdrop: m.background ? String(m.background) : null,
      runtime: m.runtime ? String(m.runtime) : null,
    }));
}

/**
 * Cinemeta omits fields rather than nulling them, and the same title comes back
 * at three different levels of detail: a search result carries little more than
 * a name and a poster, a catalog row has genres and a rating, `/meta` has
 * everything. Each field falls back through the shapes it can appear in.
 */
export function toMeta(m: Record<string, any>): Meta {
  return {
    title: String(m.name ?? ''),
    // `releaseInfo` is a search result's only date, and for a series it is a
    // range ("2020–2023") — the leading year is the useful part either way.
    year:
      (m.year ? String(m.year) : null) ??
      (m.releaseInfo ? String(m.releaseInfo) : null) ??
      (String(m.released ?? '').slice(0, 4) || null),
    rating: m.imdbRating ? String(m.imdbRating) : null,
    genres: Array.isArray(m.genre ?? m.genres)
      ? (m.genre ?? m.genres).slice(0, 3).map(String)
      : [],
    poster: m.poster ? String(m.poster) : null,
    plot: m.description ? String(m.description) : null,
    // Kept whole — the detail page lists them, the search card takes the first
    // few itself.
    cast: Array.isArray(m.cast) ? m.cast.map(String) : [],
    directors: Array.isArray(m.director) ? m.director.map(String) : [],
    awards: m.awards ? String(m.awards) : null,
  };
}

/**
 * Cinemeta's `videos`, in broadcast order.
 *
 * Season 0 is where it files specials and promos; they keep their own group
 * rather than being dropped, because some shows put real episodes there.
 */
export function toEpisodes(videos: unknown): Episode[] {
  if (!Array.isArray(videos)) return [];
  return videos
    .filter((v) => v && Number.isFinite(Number(v.season)) && Number.isFinite(Number(v.episode)))
    .map((v) => ({
      id: String(v.id ?? `${v.season}:${v.episode}`),
      season: Number(v.season),
      episode: Number(v.episode),
      name: String(v.name ?? `Episode ${v.episode}`),
      overview: v.overview ? String(v.overview) : (v.description ? String(v.description) : null),
      thumbnail: v.thumbnail ? String(v.thumbnail) : null,
      released: v.released ? String(v.released) : null,
    }))
    .sort((a, b) => a.season - b.season || a.episode - b.episode);
}

/** Where to read what other people thought. IMDb is a direct link because the
 *  id is exact; the rest only have a search, and a wrong guess at a slug is
 *  worse than a search page. */
export function reviewLinks(title: Title): { label: string; url: string }[] {
  const q = encodeURIComponent(`${title.title} ${title.year ?? ''}`.trim());
  return [
    { label: 'IMDb', url: `https://www.imdb.com/title/${title.imdb}/reviews` },
    { label: 'Rotten Tomatoes', url: `https://www.rottentomatoes.com/search?search=${q}` },
    { label: 'Metacritic', url: `https://www.metacritic.com/search/${q}/` },
    { label: 'Letterboxd', url: `https://letterboxd.com/search/${q}/` },
  ];
}
