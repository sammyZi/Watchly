/**
 * Trackers attached to bare magnets, which otherwise have only the DHT to ask.
 *
 * Only the `wss://` ones do anything here. A browser cannot open a UDP socket
 * or a raw TCP connection, so it reaches peers over WebRTC alone — the udp
 * entries below are carried purely so the magnet stays useful when it is
 * handed to a desktop client or the Android app. Every WebSocket tracker that
 * is publicly up is listed, because the odds of finding a browser peer are
 * exactly the odds of one of these knowing about it.
 */
export const TRACKERS = [
  'wss://tracker.openwebtorrent.com',
  'wss://tracker.webtorrent.dev',
  'wss://tracker.files.fm:7073/announce',
  'udp://tracker.opentrackr.org:1337/announce',
  'udp://open.stealth.si:80/announce',
  'udp://tracker.torrent.eu.org:451/announce',
  'udp://exodus.desync.com:6969/announce',
  'udp://tracker.openbittorrent.com:6969/announce',
  'udp://explodie.org:6969/announce',
];

/** The ones a browser can actually announce to. */
export const WEB_TRACKERS = TRACKERS.filter((t) => t.startsWith('wss://'));

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
