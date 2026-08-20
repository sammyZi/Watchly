/** Human-readable label for a magnet link, torrent URL, or local .torrent path. */
export function titleOf(uri: string): string {
  const dn = /[?&]dn=([^&]*)/.exec(uri)?.[1];
  if (dn) return safeDecode(dn.replace(/\+/g, ' '));

  const infohash = /btih:([a-z0-9]+)/i.exec(uri)?.[1];
  if (infohash) return infohash.slice(0, 16);

  const file = uri.split(/[/\\]/).pop()?.split('?')[0];
  return file ? safeDecode(file) : uri;
}

/**
 * Stable hue (0-359) for a name, so a torrent keeps the same poster colour
 * between launches and two rows never look alike by accident.
 */
export function hueOf(name: string): number {
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = (hash * 31 + name.charCodeAt(i)) % 360;
  }
  return hash;
}

/**
 * A release name reduced to something a metadata service will recognise:
 * "The.Matrix.1999.1080p.BluRay.x264-GRP" -> "The Matrix".
 *
 * Everything from the year or the first quality marker onwards is scaffolding,
 * so the cut is made there rather than by blacklisting every tag in existence.
 */
export function searchableName(name: string): string {
  return name
    .replace(/\.[a-z0-9]{2,4}$/i, '')
    .replace(/\[[^\]]*\]/g, ' ')
    .replace(/[._]+/g, ' ')
    .split(
      /\b(?:19\d{2}|20\d{2}|s\d{1,2}e\d{1,2}|season|complete|\d{3,4}p|2160p|4k|bluray|brrip|bdrip|web[ -]?dl|webrip|hdtv|dvdrip|x26[45]|h ?26[45]|hevc|aac|ac3|dts)\b/i
    )[0]
    .replace(/[([{].*$/, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Release year, when the name carries one — the only way to tell two films
 *  with the same title apart. */
export function yearOf(name: string): string | null {
  return /\b(19\d{2}|20\d{2})\b/.exec(name)?.[1] ?? null;
}

/** Does this name describe an episode or a season rather than a film? */
export const isSeries = (name: string) =>
  /\bs\d{1,2}\s?e\d{1,2}\b|\b\d{1,2}x\d{2}\b|\bseason\b|\bcomplete series\b/i.test(name);

const safeDecode = (s: string) => {
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
};
