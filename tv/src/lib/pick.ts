import { parseEpisode, seasonsIn } from './search.ts';

/** Just what picking needs — `TorrentFile` from the native module fits this. */
type Filed = { index: number; name: string; size: number; isVideo: boolean; path?: string };

/** Sample, trailer and "extras" folders sit next to the feature in a lot of
 *  releases, and are always small — but a 200MB trailer is still smaller than
 *  the film, so they are named rather than left to the size test. */
const JUNK = /\b(sample|trailer|extras?|featurette|behind[. _-]?the[. _-]?scenes)\b/i;

/**
 * The one file a viewer meant to open.
 *
 * `tag` is an episode marker like "S01E02": a season pack holds thirty videos
 * and only one of them is the episode that was tapped, so a name match beats
 * size there. Everything else — a film, a single-episode torrent — is the
 * biggest video in the torrent, which is the feature in every layout I know of.
 *
 * Returns null when there is no video at all; the caller then has to fall back
 * to letting the user choose.
 */
/**
 * "S01E02" -> a pattern matching how releases actually write it: S01E02, s1e2,
 * S01.E02, 1x02. Built from the numbers rather than by padding the string,
 * because a release that drops the leading zero drops it everywhere.
 */
function episodePattern(tag: string): RegExp | null {
  const found = /^s(\d+)e(\d+)$/i.exec(tag);
  if (!found) return null;
  const season = Number(found[1]);
  const episode = Number(found[2]);
  return new RegExp(`(?:s0*${season}[ ._-]*e0*${episode}|\\b0*${season}x0*${episode})\\b`, 'i');
}

export function pickFile(files: Filed[], tag?: string): number | null {
  const videos = files.filter((f) => f.isVideo && !JUNK.test(f.name));
  if (!videos.length) return null;

  if (tag) {
    const wanted = episodePattern(tag);
    const match = wanted ? videos.filter((f) => wanted.test(f.name)) : [];
    if (match.length) {
      return match.reduce((a, b) => (b.size > a.size ? b : a)).index;
    }
    // A season pack whose names cannot be read: the biggest file is simply the
    // longest episode, not the one that was asked for. Playing that is worse
    // than admitting defeat and letting the user pick.
    if (videos.length > 1) return null;
  }

  return videos.reduce((a, b) => (b.size > a.size ? b : a)).index;
}

/**
 * The episode `step` places from the one playing, inside the same torrent.
 *
 * Only ever the same download: a season pack already holds its neighbours and
 * playing one is instant, whereas a loose episode would mean another search,
 * another swarm and a wait — nothing worth auto-starting on someone.
 *
 * Ordering is by what the names say, not by file order, because packs are
 * routinely laid out alphabetically ("E10" before "E2") and a season boundary
 * then falls in the wrong place.
 */
export function siblingEpisode(
  files: Filed[],
  index: number,
  step: number
): Filed | null {
  const playing = files.find((f) => f.index === index);
  if (!playing || !parseEpisode(playing.name)) return null;

  const ordered = files
    .filter((f) => f.isVideo && !JUNK.test(f.name))
    .map((file) => ({ file, at: parseEpisode(file.name) }))
    .filter((e): e is { file: Filed; at: { season: number; episode: number } } => !!e.at)
    .sort((a, b) => a.at.season - b.at.season || a.at.episode - b.at.episode);

  const at = ordered.findIndex((e) => e.file.index === index);
  if (at < 0) return null;
  const to = at + step;
  return to < 0 ? null : (ordered[to]?.file ?? null);
}

export function nextEpisode(files: Filed[], index: number): Filed | null {
  return siblingEpisode(files, index, 1);
}

/** The episode before this one. Same ordering, same "only a pack" rule. */
export function prevEpisode(files: Filed[], index: number): Filed | null {
  return siblingEpisode(files, index, -1);
}

export function parentDir(path?: string, name?: string) {
  const p = (path || name || '').replace(/\\/g, '/');
  const i = p.lastIndexOf('/');
  return i > 0 ? p.slice(0, i) : '';
}

export function folderName(dir: string) {
  return dir.split('/').filter(Boolean).pop() || dir;
}

function natural(a: string, b: string) {
  return a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' });
}

function fileOrder(
  a: { name: string; path?: string },
  b: { name: string; path?: string }
) {
  const ea = parseEpisode(a.name) ?? parseEpisode(a.path ?? '');
  const eb = parseEpisode(b.name) ?? parseEpisode(b.path ?? '');
  if (ea && eb) {
    const byEp = ea.season - eb.season || ea.episode - eb.episode;
    if (byEp) return byEp;
  }
  return natural(a.name, b.name) || natural(a.path ?? '', b.path ?? '');
}

function commonPrefix(dirs: string[]): string {
  if (dirs.length < 2) return '';
  const parts = dirs[0].split('/');
  let n = parts.length;
  for (const dir of dirs.slice(1)) {
    const p = dir.split('/');
    let i = 0;
    while (i < n && i < p.length && parts[i] === p[i]) i++;
    n = i;
  }
  return n > 0 ? parts.slice(0, n).join('/') : '';
}

function displayFolder(dir: string, prefix: string) {
  if (!dir) return '';
  const rest =
    prefix && (dir === prefix || dir.startsWith(`${prefix}/`))
      ? dir.slice(prefix.length).replace(/^\//, '')
      : dir;
  return rest || folderName(dir);
}

/** Group files by their full parent path. Sort folders and episodes naturally. */
export function groupByFolder<T extends { name: string; path?: string }>(
  files: T[]
): { folder: string; dir: string; files: T[] }[] {
  const groups = new Map<string, T[]>();
  for (const file of files) {
    const dir = parentDir(file.path, file.name);
    const list = groups.get(dir) ?? [];
    list.push(file);
    groups.set(dir, list);
  }
  if (groups.size <= 1) {
    const [dir, list] = [...groups.entries()][0] ?? ['', files];
    return [{ folder: '', dir, files: list.slice().sort(fileOrder) }];
  }
  const prefix = commonPrefix([...groups.keys()].filter(Boolean));
  return [...groups.entries()]
    .sort((a, b) => natural(a[0], b[0]))
    .map(([dir, grouped]) => ({
      dir,
      folder: displayFolder(dir, prefix),
      files: grouped.slice().sort(fileOrder),
    }));
}

/** Video files that belong to one season — or every video, when the pack
 *  does not label seasons (anime episode numbers, a single-season rip). */
export function seasonFiles(files: Filed[], season?: number): number[] {
  const videos = files.filter((f) => f.isVideo && !JUNK.test(f.name));
  if (!videos.length) return [];
  if (season == null) return videos.map((f) => f.index);
  const hit = videos.filter((f) => {
    const at = parseEpisode(f.name) ?? parseEpisode(f.path ?? '');
    if (at?.season === season) return true;
    const hay = `${f.path ?? ''} ${f.name}`;
    return seasonsIn(hay).includes(season);
  });
  return (hit.length ? hit : videos).slice().sort(fileOrder).map((f) => f.index);
}
