// Extension included so `node --experimental-strip-types` can run the test.
import { withTrackers } from './magnet.ts';
import { net } from './net.ts';

const API = 'https://apibay.org/q.php';
/** Same magnet/hash results when apibay.org is ISP-blocked (common in IN). */
const CSV = 'https://torrents-csv.com/service/search';

/**
 * The second index, and the reason a release with thousands of seeders is not
 * missing any more.
 *
 * apibay is one site, capped at 100 rows, and blocked outright by a lot of
 * ISPs — when it is, the app used to have nothing at all to show. Torrentio
 * answers for 1337x, YTS, EZTV, RARBG, ThePirateBay and Nyaa in a single
 * request, so it is one more source to add rather than six. It is keyed by
 * IMDb id, which every title page already has.
 */
const TORRENTIO = 'https://torrentio.strem.fun/stream';
/** Fast movie index — usually answers before apibay has even started. */
const YTS = 'https://yts.mx/api/v2/list_movies.json';
/** Fast series index, keyed by IMDb id. */
const EZTV = 'https://eztv.tf/api/get-torrents';
/** YTS / EZTV / Torrentio should answer quickly or we move on. */
const FAST_MS = 2000;

export type Kind = 'movie' | 'tv' | 'other';

/** What a release actually contains — one episode, a season, or the lot. */
export type Scope = 'episode' | 'season' | 'complete';

/** "Ted Lasso" + S1E2 -> the string an indexer will match. */
export const episodeTag = (season: number, episode: number) =>
  `S${String(season).padStart(2, '0')}E${String(episode).padStart(2, '0')}`;

/**
 * The reverse: which episode a file name belongs to, or null.
 *
 * This is how a torrent already in the library maps onto a title page —
 * "Show.S01E02.mkv" is the only record that a stored file is episode two.
 */
export function parseEpisode(name: string): { season: number; episode: number } | null {
  const found =
    /\bs(\d{1,2})[ ._-]*e(\d{1,2})\b/i.exec(name) ?? /\b(\d{1,2})x(\d{1,2})\b/i.exec(name);
  return found ? { season: Number(found[1]), episode: Number(found[2]) } : null;
}

/**
 * Order matters: "S01.COMPLETE" is a season, "Seasons 1-3" is the lot, and
 * anything carrying an episode marker is a single episode whatever else it
 * says. Unmarked names are treated as one episode — for a film that is true,
 * and for a series it is the safe assumption.
 */
/**
 * Which seasons a release name claims to hold. Empty when it names none.
 *
 * Written as plain regex literals on purpose: the same expressions inside a
 * template literal silently become backspace characters, because `\b` is a
 * valid string escape. That is exactly how the wrong-season filter this
 * replaces came to match nothing at all.
 */
export function seasonsIn(name: string): number[] {
  const out = new Set<number>();

  // Ranges first — "Seasons 1-3", "S01-S03". A range makes the single-token
  // pass below redundant, and reading "1" out of "1-3" would be wrong.
  const RANGES = [
    /\bs(\d{1,2})\s*[-–]\s*s?(\d{1,2})\b/gi,
    /\bseasons?[ ._-]*(\d{1,2})\s*[-–]\s*s?(\d{1,2})\b/gi,
  ];
  for (const pattern of RANGES) {
    for (const found of name.matchAll(pattern)) {
      const from = Number(found[1]);
      const to = Number(found[2]);
      for (let i = Math.min(from, to); i <= Math.max(from, to); i++) out.add(i);
    }
  }
  if (out.size) return [...out].sort((a, b) => a - b);

  // Two forms, deliberately kept apart. The bare token allows no separator, so
  // "Ocean's 8" cannot read as season 8; the spelled-out one allows dots and
  // spaces, because "Season.5" is how half of them are written.
  const ONES = [/\bs(\d{1,2})(?=e\d{1,2}\b|\b)/gi, /\bseasons?[ ._-]*(\d{1,2})\b/gi];
  for (const pattern of ONES) {
    for (const found of name.matchAll(pattern)) out.add(Number(found[1]));
  }
  return [...out].sort((a, b) => a - b);
}

export function scopeOf(name: string): Scope {
  // "S01E01-E10" is a season pack, not a single episode.
  if (/\bs\d{1,2}\s?e\d{1,2}\s*[-–]\s*e?\d{1,2}\b/i.test(name)) return 'season';
  if (/\bs\d{1,2}\s?e\d{1,2}\b|\b\d{1,2}x\d{2}\b/i.test(name)) return 'episode';
  if (/\bseasons?\s*\d{1,2}\s*[-–]\s*\d{1,2}\b|\ball seasons\b|\bcomplete series\b/i.test(name))
    return 'complete';
  // "S01-S05" is the whole run — Play must not open that as a "season pack".
  const span = /\bs(\d{1,2})\s*[-–]\s*s?(\d{1,2})\b/i.exec(name);
  if (span && Number(span[1]) !== Number(span[2])) return 'complete';
  if (/\bs\d{1,2}\b|\bseason\s*\d{1,2}\b/i.test(name)) return 'season';
  return 'episode';
}

export type Hit = {
  /** The info hash — the one id every index agrees on, which is what makes
   *  merging two of them a matter of dropping duplicate keys. */
  id: string;
  name: string;
  magnet: string;
  seeders: number;
  leechers: number;
  size: number;
  quality: string | null;
  kind: Kind;
  scope: Scope;
  /** IMDb id when the uploader tagged one — the index carries no genre. */
  imdb: string | null;
  /**
   * True only for Torrentio: its 💾 is the size of the one file it found, even
   * when the torrent behind it is a season pack. Every other index reports the
   * size of the whole torrent already. A caller that needs the pack's total
   * multiplies by episode count for this source and takes `size` as-is for
   * every other — mixing the two up is a 15GB pack rendered as 300GB.
   */
  perFileSize?: boolean;
};

/** The index answers with strings, but its precompiled feeds use numbers. */
type Row = Partial<
  Record<
    'id' | 'name' | 'info_hash' | 'seeders' | 'leechers' | 'size' | 'category' | 'imdb',
    string | number
  >
>;

/** Rows -> hits. Split out from the fetch so it is testable without a network. */
export function toHits(rows: Row[]): Hit[] {
  return rows
    // Empty search answers with one placeholder row carrying a zeroed hash.
    .filter((r) => String(r.id) !== '0' && /^[0-9a-f]{40}$/i.test(String(r.info_hash)))
    // 200–299 is Video; cat=200 already asks for it, this catches the rest.
    .filter((r) => Number(r.category) >= 200 && Number(r.category) < 300)
    .map((r) => {
      const name = String(r.name);
      return {
        id: String(r.info_hash).toLowerCase(),
        name,
        magnet: withTrackers(
          `magnet:?xt=urn:btih:${String(r.info_hash).toLowerCase()}&dn=${encodeURIComponent(name)}`
        ),
        seeders: Number(r.seeders),
        leechers: Number(r.leechers),
        size: Number(r.size),
        quality: quality(name),
        scope: scopeOf(name),
        kind: kindOf(Number(r.category)),
        imdb: /^tt\d+$/.test(String(r.imdb ?? '')) ? String(r.imdb) : null,
      };
    });
}

/** Video subcategories: 201/202/204/207/209 are films, 205/208 are series. */
export function kindOf(category: number): Kind {
  if ([201, 202, 204, 207, 209].includes(category)) return 'movie';
  if ([205, 208].includes(category)) return 'tv';
  return 'other';
}

/** Release quality lifted from the name — the one thing every scene name carries. */
export function quality(name: string): string | null {
  if (/2160p|\buhd\b|\b4k\b/i.test(name)) return '4K';
  if (/1080p/i.test(name)) return '1080p';
  if (/720p/i.test(name)) return '720p';
  if (/\bcam\b|\bhdts\b|\btelesync\b/i.test(name)) return 'CAM';
  return null;
}

/** Seeder count -> how well it will actually stream. Colours live in the screen. */
export function health(seeders: number): 'fast' | 'good' | 'weak' | 'dead' {
  if (seeders >= 100) return 'fast';
  if (seeders >= 20) return 'good';
  if (seeders >= 1) return 'weak';
  return 'dead';
}

/**
 * Order by how well the swarm will actually serve you.
 *
 * Seeders carry the whole file, leechers only prove the torrent is alive and
 * can still trade pieces — hence the discount rather than a plain peer count.
 * A name matching every word typed outranks a fatter swarm that only half
 * matches, which is what stops "the matrix" returning big unrelated packs.
 */
export function rank(hits: Hit[], query = ''): Hit[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  const score = (h: Hit) => {
    const full = words.length > 0 && words.every((w) => h.name.toLowerCase().includes(w));
    return (h.seeders + h.leechers / 4) * (full ? 2 : 1);
  };
  return [...hits].sort((a, b) => score(b) - score(a));
}

/** This season's pack first (fast enough to open). Whole-series dumps last. */
export function rankEpisode(hits: Hit[], query = '', season?: number): Hit[] {
  const ordered = rank(hits, query);
  const claims = (h: Hit) => seasonsIn(h.name);
  const thisPack = (h: Hit) => {
    if (h.seeders < 1 || h.scope !== 'season') return false;
    const c = claims(h);
    return c.length <= 1 && (season == null || !c.length || c.includes(season));
  };
  const one = (h: Hit) => h.scope === 'episode' && h.seeders >= 1;
  return [
    ...ordered.filter(thisPack),
    ...ordered.filter(one),
    ...ordered.filter((h) => !thisPack(h) && !one(h)),
  ];
}

/**
 * Torrentio's streams, which carry their numbers inside a display string:
 *
 *   "The Matrix 1999 2160p BluRay x265\n👤 84 💾 51.3 GB ⚙️ 1337x"
 *
 * The emoji are the field markers, so the two below are load-bearing: a broken
 * byte there silently zeroes every seeder count rather than failing.
 */
/** 👤 */
const SEEDERS = /👤\s*(\d+)/;
/** 💾 */
const SIZE = /💾\s*([\d.]+)\s*(TB|GB|MB|KB)/i;
const UNIT: Record<string, number> = {
  kb: 1024,
  mb: 1024 ** 2,
  gb: 1024 ** 3,
  tb: 1024 ** 4,
};

export function toTorrentioHits(streams: Record<string, any>[], kind: Kind): Hit[] {
  return streams
    .filter((s) => /^[0-9a-f]{40}$/i.test(String(s?.infoHash)))
    .map((s) => {
      const lines = String(s.title ?? '').split('\n');
      // The release name is the first line; a stream with none is unusable,
      // since the name is what picks the file inside the torrent.
      const name = lines[0]?.trim() || String(s.name ?? '').replace('\n', ' ');
      const stats = lines.slice(1).join(' ');
      const size = SIZE.exec(stats);
      return {
        id: String(s.infoHash).toLowerCase(),
        name,
        magnet: withTrackers(
          `magnet:?xt=urn:btih:${String(s.infoHash).toLowerCase()}&dn=${encodeURIComponent(name)}`
        ),
        seeders: Number(SEEDERS.exec(stats)?.[1] ?? 0),
        // Torrentio reports no leecher count at all; ranking already treats
        // them as a quarter of a seeder, so zero is the honest answer.
        leechers: 0,
        size: size ? Math.round(Number(size[1]) * (UNIT[size[2].toLowerCase()] ?? 0)) : 0,
        quality: quality(name),
        scope: scopeOf(name),
        kind,
        imdb: null,
        perFileSize: true,
      };
    })
    .filter((h) => !!h.name);
}

export function toYtsHits(movies: Record<string, any>[]): Hit[] {
  const out: Hit[] = [];
  for (const movie of movies ?? []) {
    const title = String(movie.title_long || movie.title || '');
    const imdb = /^tt\d+$/.test(String(movie.imdb_code ?? ''))
      ? String(movie.imdb_code)
      : null;
    for (const t of movie.torrents ?? []) {
      const hash = String(t.hash ?? '').toLowerCase();
      if (!/^[0-9a-f]{40}$/.test(hash)) continue;
      const name = [title, t.quality, t.type].filter(Boolean).join(' ');
      out.push({
        id: hash,
        name,
        magnet: withTrackers(
          `magnet:?xt=urn:btih:${hash}&dn=${encodeURIComponent(name)}`
        ),
        seeders: Number(t.seeds ?? 0),
        leechers: Number(t.peers ?? 0),
        size: Number(t.size_bytes ?? 0),
        quality: quality(name),
        scope: 'episode',
        kind: 'movie',
        imdb,
      });
    }
  }
  return out;
}

export function toEztvHits(torrents: Record<string, any>[]): Hit[] {
  return (torrents ?? [])
    .filter((t) => /^[0-9a-f]{40}$/i.test(String(t.hash)))
    .map((t) => {
      const name = String(t.title ?? '');
      const hash = String(t.hash).toLowerCase();
      return {
        id: hash,
        name,
        magnet: withTrackers(
          `magnet:?xt=urn:btih:${hash}&dn=${encodeURIComponent(name)}`
        ),
        seeders: Number(t.seeds ?? 0),
        leechers: Number(t.peers ?? 0),
        size: Number(t.size_bytes ?? 0),
        quality: quality(name),
        scope: scopeOf(name),
        kind: 'tv' as Kind,
        imdb: null,
      };
    })
    .filter((h) => !!h.name);
}

export function toCsvHits(rows: Record<string, any>[]): Hit[] {
  return (rows ?? [])
    .filter((r) => /^[0-9a-f]{40}$/i.test(String(r.infohash ?? r.info_hash ?? '')))
    .map((r) => {
      const name = String(r.name ?? '');
      const hash = String(r.infohash ?? r.info_hash).toLowerCase();
      const kind: Kind = parseEpisode(name) ? 'tv' : 'movie';
      return {
        id: hash,
        name,
        magnet: withTrackers(
          `magnet:?xt=urn:btih:${hash}&dn=${encodeURIComponent(name)}`
        ),
        seeders: Number(r.seeders ?? 0),
        leechers: Number(r.leechers ?? 0),
        size: Number(r.size_bytes ?? r.size ?? 0),
        quality: quality(name),
        scope: scopeOf(name),
        kind,
        imdb: null,
      };
    })
    .filter((h) => !!h.name);
}

/** Same torrent from two indexes is one row — keep whichever knew more. */
export function merge(lists: Hit[][]): Hit[] {
  const best = new Map<string, Hit>();
  for (const hit of lists.flat()) {
    const seen = best.get(hit.id);
    if (!seen) {
      best.set(hit.id, hit);
      continue;
    }
    // One index reports a swarm the other cannot see, and one reports a size
    // the other left at zero. Neither number is worth throwing away.
    const bigger = hit.size > seen.size ? hit : seen;
    best.set(hit.id, {
      ...(hit.seeders > seen.seeders ? hit : seen),
      seeders: Math.max(hit.seeders, seen.seeders),
      size: bigger.size,
      // Whichever side's size actually won is what the flag has to describe —
      // spreading it from the seeder-winner let a Torrentio per-file number
      // outvote an apibay whole-pack number on seeders and still get tagged
      // "multiply me", doubling the same torrent's size twice over.
      perFileSize: bigger.perFileSize,
    });
  }
  return [...best.values()];
}

async function fromTorrentio(
  path: string,
  kind: Kind,
  signal?: AbortSignal
): Promise<Hit[]> {
  const res = await net(`${TORRENTIO}/${path}.json`, { signal }, true);
  if (!res.ok) throw new Error(`Torrentio returned ${res.status}`);
  return toTorrentioHits((await res.json())?.streams ?? [], kind);
}

async function get(url: string, signal?: AbortSignal): Promise<Row[]> {
  const res = await net(url, { signal }, true);
  if (!res.ok) throw new Error(`Search returned ${res.status}`);
  return res.json();
}

async function fromCsv(query: string, signal?: AbortSignal): Promise<Hit[]> {
  const res = await net(
    `${CSV}?q=${encodeURIComponent(query)}&size=50`,
    { signal },
    true
  );
  if (!res.ok) throw new Error(`CSV returned ${res.status}`);
  const body = await res.json();
  return toCsvHits(body?.torrents ?? body ?? []);
}

/** apibay first; torrents-csv when the host is blocked. */
async function fromBay(query: string, signal?: AbortSignal): Promise<Hit[]> {
  const hits = await limited(
    get(`${API}?q=${encodeURIComponent(query)}&cat=200`, signal).then(toHits),
    1200,
    [] as Hit[]
  );
  if (hits.length || signal?.aborted) return hits;
  return limited(fromCsv(query, signal), 2000, [] as Hit[]);
}

function limited<T>(job: Promise<T>, ms: number, fallback: T): Promise<T> {
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(fallback), ms);
    job.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      () => {
        clearTimeout(timer);
        resolve(fallback);
      }
    );
  });
}

async function timed<T>(name: string, job: Promise<T>): Promise<T> {
  const t = Date.now();
  try {
    const out = await job;
    const n = Array.isArray(out) ? out.length : '?';
    console.log(`[releases] ${name} ${Date.now() - t}ms n=${n}`);
    return out;
  } catch (e) {
    console.log(`[releases] ${name} ${Date.now() - t}ms FAIL ${String(e)}`);
    throw e;
  }
}

/** First source that actually has rows — the rest can still log when they finish. */
function firstHits(jobs: Promise<Hit[]>[]): Promise<Hit[]> {
  return new Promise((resolve) => {
    let left = jobs.length;
    if (!left) return resolve([]);
    for (const job of jobs) {
      job.then(
        (hits) => (hits.length ? resolve(hits) : --left === 0 && resolve([])),
        () => --left === 0 && resolve([])
      );
    }
  });
}

async function fromYts(query: string, signal?: AbortSignal): Promise<Hit[]> {
  const res = await net(
    `${YTS}?query_term=${encodeURIComponent(query)}&limit=50`,
    { signal },
    true
  );
  if (!res.ok) throw new Error(`YTS returned ${res.status}`);
  return toYtsHits((await res.json())?.data?.movies ?? []);
}

async function fromEztv(imdb: string, signal?: AbortSignal): Promise<Hit[]> {
  const res = await net(
    `${EZTV}?imdb_id=${imdb.replace(/^tt/, '')}&limit=100`,
    { signal },
    true
  );
  if (!res.ok) throw new Error(`EZTV returned ${res.status}`);
  return toEztvHits((await res.json())?.torrents ?? []);
}

/** One EZTV pull per show — every episode would otherwise refetch the same list. */
const eztvMemo = new Map<string, Promise<Hit[]>>();
function eztvFor(imdb: string): Promise<Hit[]> {
  const id = imdb.replace(/^tt/, '');
  let pending = eztvMemo.get(id);
  if (!pending) {
    pending = fromEztv(imdb).catch(() => [] as Hit[]);
    eztvMemo.set(id, pending);
  }
  return pending;
}

const episodeMemo = new Map<string, Promise<Hit[]>>();

/**
 * The other name a title is released under.
 *
 * Metadata services rename films as their franchise grows: the page for
 * tt1160419 says "Dune: Part One", while every index on earth files it as
 * "Dune 2021". Searching the canonical name finds nine obscure rips and misses
 * the release with the entire swarm behind it, because the words "part one"
 * appear in neither its name nor its files. What comes before the subtitle is
 * what the scene actually uses.
 */
export function aliasOf(title: string): string | null {
  const short = title.split(/:| - /)[0].trim();
  return short && short.toLowerCase() !== title.trim().toLowerCase() ? short : null;
}

/**
 * Does this release actually name the thing that was searched for?
 *
 * Both indexes answer with collection packs — "IMDb Top 250 - 2024 Edition",
 * "263 movies hindi dubbed" — because the film really is somewhere inside one.
 * They arrive carrying a whole catalogue's worth of seeders, so they outrank
 * every honest release, and the app cannot even play them: it picks the largest
 * video in a torrent, which in a 250-film pack is somebody else's film.
 *
 * Matched against the short name rather than the full one, or "Dune: Part One"
 * would throw away every release actually called "Dune 2021".
 */
export function namesTitle(release: string, title: string): boolean {
  const words = (s: string) =>
    s.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim().split(' ').filter(Boolean);
  const want = words(title);
  if (!want.length) return true;
  const got = new Set(words(release));
  return want.every((w) => got.has(w));
}

/**
 * Search public indexes for video torrents.
 *
 * `suffix` is appended to every name variant — an episode tag, or "complete".
 * The title is asked for under both its own name and its alias. apibay is
 * tried first — it indexes the widest spread of releases, and its rows carry
 * real seeder counts rather than a scraped display string. YTS and Torrentio
 * only run when it comes up empty, which covers the ISPs that block it.
 * One source being unreachable is the normal case rather than the exception,
 * and half a list beats an error message.
 */
export async function searchTorrents(
  title: string,
  signal?: AbortSignal,
  opts?: { imdb?: string | null; year?: string | null; suffix?: string }
): Promise<Hit[]> {
  const year = opts?.year ?? null;
  const tail = [opts?.suffix, year].filter(Boolean).join(' ');
  const full = [title, tail].filter(Boolean).join(' ');
  const alias = aliasOf(title);

  const bay = (q: string) => fromBay(q, signal);

  const t0 = Date.now();
  console.log(`[releases] torrents start "${full}" imdb=${opts?.imdb ?? '-'}`);

  const bags: Hit[][] = [];
  const [primary, aliased] = await Promise.all([
    timed('apibay', bay(full)),
    alias
      ? timed('apibay-alias', bay([alias, tail].filter(Boolean).join(' ')))
      : Promise.resolve([] as Hit[]),
  ]);
  // The alias is a looser question — "Dune 2021" also matches Dune Prophecy's
  // 2021 rips — so its answers have to carry the year to count. The primary
  // query named the film outright and needs no such proof.
  bags.push(primary, year ? aliased.filter((h) => h.name.includes(year)) : aliased);

  if (!bags.some((h) => h.length) && !opts?.suffix) {
    console.log('[releases] torrents fallback yts/torrentio');
    const fast = await firstHits([
      timed('yts', limited(fromYts(opts?.imdb ?? full, signal), FAST_MS, [] as Hit[])),
      opts?.imdb
        ? timed(
            'torrentio',
            fromTorrentio(`movie/${opts.imdb}`, 'movie', signal).catch(() => [] as Hit[])
          )
        : Promise.resolve([] as Hit[]),
    ]);
    if (fast.length) bags.push(fast);
  }

  const found = rank(merge(bags), full);
  // Everything that does not name the title is a collection pack riding in on
  // a catalogue's worth of seeders. Falling back to the unfiltered list keeps
  // an unusually-named release reachable rather than showing an empty page.
  const named = found.filter((h) => namesTitle(h.name, alias ?? title));
  const out = named.length ? named : found;
  console.log(`[releases] torrents done ${Date.now() - t0}ms n=${out.length}`);
  return out;
}

/**
 * Every release that can play one episode — including the packs.
 *
 * Searching for "Show S01E02" alone finds the loose episode and misses the
 * season pack sitting next to it with thirty times the seeders. A pack streams
 * just as well, because only the one file inside it is ever fetched, so all
 * three shapes are searched and ranked together on swarm health.
 */
export async function searchEpisode(
  show: string,
  season: number,
  episode: number,
  signal?: AbortSignal,
  imdb?: string | null
): Promise<Hit[]> {
  const key = `${imdb ?? show}:${season}:${episode}`;
  const cached = episodeMemo.get(key);
  if (cached) return cached;

  const pending = findEpisode(show, season, episode, signal, imdb).then(
    (hits) => {
      if (!hits.length) episodeMemo.delete(key);
      return hits;
    },
    () => {
      episodeMemo.delete(key);
      return [] as Hit[];
    }
  );
  episodeMemo.set(key, pending);
  return pending;
}

async function findEpisode(
  show: string,
  season: number,
  episode: number,
  signal?: AbortSignal,
  imdb?: string | null
): Promise<Hit[]> {
  const plays = (h: Hit) => {
    const ep = parseEpisode(h.name);
    if (ep) return ep.season === season && ep.episode === episode;
    const claims = seasonsIn(h.name);
    return claims.length === 0 || claims.includes(season);
  };

  const t0 = Date.now();
  const tag = episodeTag(season, episode);
  console.log(`[releases] episode start "${show}" ${tag} imdb=${imdb ?? '-'}`);

  // Two questions, not one. "Prison Break S01E01" only ever matches a loose
  // episode file — the season packs are called "Prison Break Season 1
  // Complete", carry no episode tag at all, and out-seed the loose rips by an
  // order of magnitude. Asking for the show by name is what puts them on the
  // page; `plays` then drops the seasons this episode is not in, and
  // rankEpisode floats the single-season packs to the top.
  const [loose, packs] = await Promise.all([
    searchTorrents(show, signal, { suffix: tag }).catch(() => [] as Hit[]),
    searchTorrents(show, signal).catch(() => [] as Hit[]),
  ]);
  const bayHits = rankEpisode(merge([loose, packs]).filter(plays), show, season);
  if (bayHits.length) {
    console.log(`[releases] episode done ${Date.now() - t0}ms n=${bayHits.length} bay`);
    return bayHits;
  }
  if (signal?.aborted) return [];

  console.log('[releases] episode fallback torrentio/eztv');
  if (imdb) {
    const tio = await timed(
      'torrentio',
      fromTorrentio(`series/${imdb}:${season}:${episode}`, 'tv', signal).catch(
        () => [] as Hit[]
      )
    );
    if (tio.length) {
      const out = rankEpisode(merge([tio]), show, season);
      console.log(`[releases] episode done ${Date.now() - t0}ms n=${out.length} torrentio`);
      return out;
    }
    if (signal?.aborted) return [];
    const eztv = (await timed('eztv', eztvFor(imdb))).filter(plays);
    if (eztv.length) {
      const out = rankEpisode(eztv, show, season);
      console.log(`[releases] episode done ${Date.now() - t0}ms n=${out.length} eztv`);
      return out;
    }
  }

  console.log(`[releases] episode done ${Date.now() - t0}ms n=0`);
  return [];
}
