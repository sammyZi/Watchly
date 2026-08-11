// Extension included so `node --experimental-strip-types` can run the test.
import { withTrackers } from './magnet.ts';

const API = 'https://apibay.org/q.php';

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
  if (/\bs\d{1,2}\s?e\d{1,2}\b|\b\d{1,2}x\d{2}\b/i.test(name)) return 'episode';
  if (/\bseasons?\s*\d{1,2}\s*[-–]\s*\d{1,2}\b|\ball seasons\b|\bcomplete series\b/i.test(name))
    return 'complete';
  if (/\bs\d{1,2}\b|\bseason\s*\d{1,2}\b/i.test(name)) return 'season';
  return 'episode';
}

export type Hit = {
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
        id: String(r.id),
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

async function get(url: string, signal?: AbortSignal): Promise<Row[]> {
  const res = await fetch(url, { signal });
  if (!res.ok) throw new Error(`Search returned ${res.status}`);
  return res.json();
}

/** Search public indexes for video torrents matching `query`. */
export async function searchTorrents(
  query: string,
  signal?: AbortSignal
): Promise<Hit[]> {
  const rows = await get(`${API}?q=${encodeURIComponent(query)}&cat=200`, signal);
  return rank(toHits(rows), query);
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
  signal?: AbortSignal
): Promise<Hit[]> {
  const season2 = String(season).padStart(2, '0');
  const queries = [
    `${show} ${episodeTag(season, episode)}`,
    `${show} S${season2}`,
    `${show} complete`,
  ];

  const found = await Promise.all(
    queries.map((q) => searchTorrents(q, signal).catch(() => [] as Hit[]))
  );

  // The same torrent comes back from more than one query.
  const seen = new Map<string, Hit>();
  for (const hit of found.flat()) if (!seen.has(hit.id)) seen.set(hit.id, hit);

  // A pack for a different season is worse than useless — it cannot contain
  // this episode at all, and with a season's worth of seeders behind it, it
  // would outrank every release that can. A name claiming no season is kept:
  // that is what a complete-series pack looks like.
  const usable = [...seen.values()].filter((h) => {
    const claims = seasonsIn(h.name);
    return claims.length === 0 || claims.includes(season);
  });

  return rank(usable, show);
}
