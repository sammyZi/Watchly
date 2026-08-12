/**
 * Subtitles from SubDL.
 *
 * Picked over OpenSubtitles because the free tier has no download quota and the
 * search takes a plain film name — no hashing, no login. The catch is that it
 * only ever serves zips, which is why the download side lives in the native
 * module: see `TorrentStream.saveSubtitle`.
 */
import { net } from './net.ts';

const API = 'https://api.subdl.com/api/v1/subtitles';
/** Where the `url` on a result is relative to. */
const FILES = 'https://dl.subdl.com';

export type Sub = {
  /** The release the subtitle was timed against — the thing worth matching. */
  release: string;
  name: string;
  language: string;
  /** Ready to hand to `saveSubtitle`. */
  url: string;
  season: number | null;
  episode: number | null;
};

const num = (v: unknown): number | null =>
  v == null || v === '' || !Number.isFinite(Number(v)) ? null : Number(v);

/** SubDL's rows, which vary by how the subtitle was uploaded. */
export function toSubs(rows: Record<string, any>[]): Sub[] {
  return rows
    .filter((r) => r?.url)
    .map((r) => ({
      release: String(r.release_name ?? r.name ?? ''),
      name: String(r.name ?? r.release_name ?? ''),
      language: String(r.language ?? r.lang ?? '').toUpperCase(),
      url: String(r.url).startsWith('http') ? String(r.url) : `${FILES}${r.url}`,
      // `Number(null)` is 0, which is why these are guarded rather than
      // coerced — a film would otherwise come back claiming to be S0E0.
      season: num(r.season),
      episode: num(r.episode),
    }));
}

/**
 * Search by film or show name. `episode` narrows a series to one entry —
 * without it SubDL answers with every subtitle for the whole show.
 */
export async function searchSubtitles(
  query: string,
  apiKey: string,
  opts?: { language?: string; season?: number; episode?: number }
): Promise<Sub[]> {
  const params = new URLSearchParams({
    api_key: apiKey,
    film_name: query,
    languages: opts?.language ?? 'EN',
    subs_per_page: '30',
  });
  if (opts?.season != null) params.set('season_number', String(opts.season));
  if (opts?.episode != null) params.set('episode_number', String(opts.episode));

  const res = await net(`${API}?${params}`);
  if (!res.ok) throw new Error(`SubDL search returned ${res.status}`);
  const body = await res.json();
  // A rejected key comes back as 200 with `status: false` and an `error`.
  if (body?.status === false) throw new Error(String(body.error ?? 'SubDL refused the request'));
  return toSubs(body?.subtitles ?? []);
}
