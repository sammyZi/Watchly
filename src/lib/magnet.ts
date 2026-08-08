/**
 * Bare magnet links (`xt` only) have to discover peers through DHT alone, which
 * is what makes "fetching metadata" crawl. Attaching well-known open trackers
 * gives the session somewhere to ask immediately.
 */
export const TRACKERS = [
  'udp://tracker.opentrackr.org:1337/announce',
  'udp://open.stealth.si:80/announce',
  'udp://tracker.torrent.eu.org:451/announce',
  'udp://exodus.desync.com:6969/announce',
  'udp://tracker.openbittorrent.com:6969/announce',
  'udp://explodie.org:6969/announce',
  'wss://tracker.openwebtorrent.com',
];

export function withTrackers(uri: string, trackers: string[] = TRACKERS): string {
  if (!uri.toLowerCase().startsWith('magnet:')) return uri;

  const have = new Set(
    [...uri.matchAll(/[?&]tr=([^&]+)/g)].map((m) => decodeURIComponent(m[1]))
  );
  const missing = trackers.filter((t) => !have.has(t));
  if (!missing.length) return uri;

  return uri + missing.map((t) => `&tr=${encodeURIComponent(t)}`).join('');
}

/**
 * Does this URL look like something we can stream? Guards the deep-link
 * handler, which also sees the dev-client's own exp+torrentviewer:// urls.
 */
export function isTorrentSource(url: string): boolean {
  const lower = url.toLowerCase();
  if (lower.startsWith('magnet:')) return true;
  if (lower.startsWith('content://')) return true;
  return (
    /^(file|https?):/.test(lower) && lower.split('?')[0].endsWith('.torrent')
  );
}
