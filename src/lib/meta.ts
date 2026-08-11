/**
 * Title metadata for an IMDb id, from Stremio's public Cinemeta service.
 *
 * Chosen over TMDB/OMDb because it needs no API key: the index gives us a `tt`
 * id and nothing else, and asking the user to register for one just to see a
 * rating would be worse than showing no rating at all.
 */
import AsyncStorage from '@react-native-async-storage/async-storage';

const BASE = 'https://v3-cinemeta.strem.io/meta';
/** Where a popularity shelf redirects to — asked for directly to save every
 *  rail a round trip. It only serves the precomputed lists: a `search=` handed
 *  to this host is ignored, and it answers with the same top 50 either way. */
const CATALOG = 'https://cinemeta-catalogs.strem.io/top/catalog';
/** Search has to go through the front door, which actually reads the query. */
const SEARCH = 'https://v3-cinemeta.strem.io/catalog';

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
const STORE = 'meta:2:';

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
  const res = await fetch(`${BASE}/${kind === 'tv' ? 'series' : 'movie'}/${imdb}.json`);
  if (!res.ok) return null;
  const { meta } = await res.json();
  return meta ? (toTitles([{ ...meta, imdb_id: imdb }], kind)[0] ?? null) : null;
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
  const url = `${CATALOG}/${type}/top${genre ? `/genre=${encodeURIComponent(genre)}` : ''}.json`;
  const pending = remember(key, async () => {
    const res = await fetch(url);
    if (!res.ok) throw new Error(`Catalog returned ${res.status}`);
    return toTitles((await res.json())?.metas ?? [], kind);
  })
    .then((titles) => {
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

/**
 * Title search — the poster-first front door. Not persisted: queries are
 * one-off, and filling storage with them would crowd out the shelves.
 */
export function searchTitles(query: string, kind: 'movie' | 'tv'): Promise<Title[]> {
  const key = `find:${kind}:${query.toLowerCase()}`;
  const hit = cache.get(key) as Promise<Title[]> | undefined;
  if (hit) return hit;

  const type = kind === 'tv' ? 'series' : 'movie';
  const pending = fetch(`${SEARCH}/${type}/top/search=${encodeURIComponent(query)}.json`)
    .then((r) => (r.ok ? r.json() : { metas: [] }))
    .then((body) => toTitles(body?.metas ?? [], kind))
    .catch(() => {
      cache.delete(key);
      return [] as Title[];
    });
  cache.set(key, pending);
  return pending;
}

/** Catalog rows without art are unusable on a poster shelf, so they are dropped. */
export function toTitles(metas: Record<string, any>[], kind: 'movie' | 'tv'): Title[] {
  return metas
    .filter((m) => m.poster && /^tt\d+$/.test(String(m.imdb_id ?? m.id ?? '')))
    .map((m) => ({
      ...toMeta(m),
      imdb: String(m.imdb_id ?? m.id),
      kind,
      episodes: toEpisodes(m.videos),
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
