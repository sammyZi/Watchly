/**
 * Subtitles from SubDL — server side only (see app/api/subs/route.ts), so the
 * API key stays in the server's environment and never reaches the browser.
 *
 * Picked over OpenSubtitles because the free tier has no download quota and the
 * search takes a plain film name — no hashing, no login. The catch is that it
 * only ever serves zips, which the route unpacks before answering.
 */

const API = 'https://api.subdl.com/api/v1/subtitles';
/** Where the `url` on a result is relative to. */
export const FILES = 'https://dl.subdl.com';

export type Sub = {
  /** The release the subtitle was timed against — the thing worth matching. */
  release: string;
  name: string;
  language: string;
  /** A dl.subdl.com zip, fetched through /api/subs?url=. */
  url: string;
  season: number | null;
  episode: number | null;
};

/** Positive numbers only — SubDL sends 0 for "not a series". */
const num = (v: unknown): number | null =>
  v == null || v === '' || !(Number(v) > 0) ? null : Number(v);

/**
 * The download URL without credentials. SubDL writes `api_key=` into the
 * links it returns, which would hand the key to every browser that searched.
 */
export function publicUrl(url: string): string {
  const u = new URL(url.startsWith('http') ? url : `${FILES}${url}`);
  u.searchParams.delete('api_key');
  return u.toString();
}

/** SubDL's rows, which vary by how the subtitle was uploaded. */
export function toSubs(rows: Record<string, unknown>[]): Sub[] {
  return rows
    .filter((r) => r?.url)
    .map((r) => ({
      release: String(r.release_name ?? r.name ?? ''),
      name: String(r.name ?? r.release_name ?? ''),
      language: String(r.language ?? r.lang ?? '').toUpperCase(),
      url: publicUrl(String(r.url)),
      season: num(r.season),
      episode: num(r.episode),
    }));
}

/**
 * The file in a zip for one episode — season packs hold every episode, and
 * the first entry is rarely the one playing (SubDL even adds a "00 …" note
 * file). Matches S01E02, s1e2, 1x02, then a leading "02 Title.srt"; failing
 * that, the biggest file, which is never the note.
 */
export function pickEntry(
  entries: { name: string; size: number }[],
  season?: number,
  episode?: number
): string | undefined {
  if (episode != null) {
    const base = (n: string) => n.split('/').pop() ?? n;
    const tagged =
      season != null
        ? new RegExp(`s0*${season}[ ._-]*e0*${episode}(?!\\d)|\\b0*${season}x0*${episode}(?!\\d)`, 'i')
        : null;
    const leading = new RegExp(`^(?:e|ep)?0*${episode}(?!\\d)`, 'i');
    const hit =
      (tagged && entries.find((e) => tagged.test(e.name))) ??
      entries.find((e) => leading.test(base(e.name)));
    if (hit) return hit.name;
  }
  return [...entries].sort((a, b) => b.size - a.size)[0]?.name;
}

export type SubQuery = {
  query?: string;
  imdb?: string;
  kind?: 'movie' | 'tv';
  language?: string;
  season?: number;
  episode?: number;
};

/**
 * Search by IMDb id when there is one (exact), else by film or show name.
 * `episode` narrows a series to one entry — without it SubDL answers with
 * every subtitle for the whole show.
 */
export async function searchSubtitles(apiKey: string, q: SubQuery): Promise<Sub[]> {
  const params = new URLSearchParams({
    api_key: apiKey,
    languages: q.language ?? 'EN',
    subs_per_page: '30',
  });
  if (q.imdb) params.set('imdb_id', q.imdb);
  else if (q.query) params.set('film_name', q.query);
  if (q.kind) params.set('type', q.kind);
  if (q.season != null) params.set('season_number', String(q.season));
  if (q.episode != null) params.set('episode_number', String(q.episode));

  const res = await fetch(`${API}?${params}`, { signal: AbortSignal.timeout(12000) });
  const body = await res.json().catch(() => null);
  // A rejected key comes back as 200 with `status: false` and an `error`.
  if (!res.ok || body?.status === false) {
    // "Can't find" is an empty answer, not a failure.
    if (/not found|can'?t find/i.test(String(body?.error ?? ''))) return [];
    throw new Error(String(body?.error ?? `SubDL search returned ${res.status}`));
  }
  return toSubs(body?.subtitles ?? []);
}
