// Extension included so `node --experimental-strip-types` can run the test.
import { withTrackers } from './magnet.ts';

const API = 'https://apibay.org/q.php';

export type Kind = 'movie' | 'tv' | 'other';

export type Hit = {
  id: string;
  name: string;
  magnet: string;
  seeders: number;
  leechers: number;
  size: number;
  quality: string | null;
  kind: Kind;
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
