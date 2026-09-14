'use client';
/**
 * The page's view of the torrent engine, which runs on the server (see
 * lib/engine.ts). A browser can only reach WebRTC peers, so a browser-side
 * engine never started most releases; the server has the same TCP/UDP/DHT
 * reach as the Android app and hands each file back over HTTP range requests.
 */
import type { Described } from './engine';

export type TorrentFile = Described['files'][number] & {
  /** HTTP URL the video element can play (range requests). */
  streamURL: string;
};

export type Engine = Omit<Described, 'files'> & {
  files: TorrentFile[];
  /** The uri this torrent was opened from (magnet / .torrent url). */
  uri: string;
  findFile(index: number): TorrentFile | null;
};

const log = (...parts: unknown[]) => console.log('[torrent]', ...parts);

function wrap(d: Described, uri: string): Engine {
  const files = d.files.map((f) => ({
    ...f,
    streamURL: `/api/torrent/${d.infoHash}/${f.index}`,
  }));
  return {
    ...d,
    uri,
    files,
    findFile: (index) => files.find((f) => f.index === index) ?? null,
  };
}

async function json<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body?.error ?? `Engine answered ${res.status}`);
  return body as T;
}

/** Open a torrent on the engine; resolves once its file list is known. */
export async function getTorrent(uri: string): Promise<Engine> {
  const started = Date.now();
  const d = await json<Described>(await fetch(`/api/torrent?uri=${encodeURIComponent(uri)}`));
  log(`metadata: ${d.torrentName} in ${Date.now() - started}ms — ${d.files.length} file(s)`);
  return wrap(d, uri);
}

/** Live swarm numbers for one torrent; null when the engine does not hold it. */
export async function liveStats(infoHash: string): Promise<Described | null> {
  const res = await fetch(`/api/torrent?hash=${infoHash}`).catch(() => null);
  return res?.ok ? res.json() : null;
}

/** The info hash a magnet names, lowercase. Null for a .torrent URL. */
export function hashOf(uri: string): string | null {
  const found = /xt=urn:btih:([0-9a-z]{40}|[0-9a-z]{32})/i.exec(uri);
  return found ? found[1].toLowerCase() : null;
}

/** Every torrent the engine holds — for the Downloads screen. */
export async function activeTorrents(): Promise<Engine[]> {
  const res = await fetch('/api/torrent').catch(() => null);
  if (!res?.ok) return [];
  return ((await res.json()) as Described[]).map((d) => wrap(d, `magnet:?xt=urn:btih:${d.infoHash}`));
}

/**
 * The player is done with this torrent: the server deletes its data after a
 * short grace. `keepalive`, so it still goes out while the page unloads.
 */
export function releaseTorrent(infoHash: string) {
  void fetch(`/api/torrent?hash=${infoHash}&release=1`, { method: 'DELETE', keepalive: true }).catch(
    () => {}
  );
}

/** Stop a torrent and delete its data. */
export async function dropTorrent(infoHash: string): Promise<void> {
  await fetch(`/api/torrent?hash=${infoHash}`, { method: 'DELETE' }).catch(() => {});
}
