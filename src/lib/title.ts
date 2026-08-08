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

const safeDecode = (s: string) => {
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
};
