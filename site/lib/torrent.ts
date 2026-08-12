'use client';
/**
 * The browser torrent engine — WebTorrent with its service-worker HTTP server.
 *
 * Unlike the native app (libtorrent4j, one stream at a time), the web build
 * keeps a single shared client so an open torrent keeps seeding while the
 * viewer browses, and "Downloads" can list every active swarm.
 *
 * Streaming URLs are served by `/sw.min.js` under `/webtorrent/...`, which
 * answers range requests — the browser's media stack does the seeking and the
 * engine fetches only the pieces the reader asks for.
 */
import WebTorrent from 'webtorrent';

export type TorrentFile = {
  index: number;
  name: string;
  size: number;
  isVideo: boolean;
  isSubtitle: boolean;
  /** Only set once the service-worker server is up. */
  streamURL: string | null;
};

type TorrentLike = {
  infoHash: string;
  name: string;
  length: number;
  progress: number;
  downloaded: number;
  downloadSpeed: number;
  uploadSpeed: number;
  numPeers: number;
  timeRemaining: number;
  done: boolean;
  files: {
    index: number;
    name: string;
    length: number;
    numPieces: number;
    streamURL?: string;
    getBlobURL(cb: (url: string) => void): void;
    createReadStream(opts?: { start?: number; end?: number }): any;
    select(): void;
    deselect(): void;
    selected?: boolean;
    on(event: string, cb: (...args: any[]) => void): void;
    [key: string]: unknown;
  }[];
};

/**
 * Engine state, hung off `globalThis` rather than module scope.
 *
 * Fast Refresh re-evaluates this module while the page keeps running, which
 * reset the maps to empty and left the previous client alive and holding
 * torrents nothing could see any more — so the next open added a hash the
 * client already had and WebTorrent refused it. One registry, whatever the
 * bundler does to the module.
 */
type Registry = {
  client: WebTorrent | null;
  byHash: Map<string, TorrentLike>;
  byUri: Map<string, TorrentLike>;
  /** Adds in flight, keyed the way WebTorrent itself dedupes: by info hash. */
  adding: Map<string, Promise<Engine>>;
};

const registry: Registry = ((globalThis as any).__watchlyTorrent ??= {
  client: null,
  byHash: new Map(),
  byUri: new Map(),
  adding: new Map(),
});

const { byHash, byUri, adding } = registry;

/** Same shape as the native module's logs, so a session reads the same way in
 *  a browser console as it does in Metro. */
const log = (...parts: unknown[]) => console.log('[torrent]', ...parts);

/** The info hash a magnet names, lowercase. Null for a .torrent URL. */
function hashOf(uri: string): string | null {
  const found = /xt=urn:btih:([0-9a-z]{40}|[0-9a-z]{32})/i.exec(uri);
  return found ? found[1].toLowerCase() : null;
}

let serverPromise: Promise<void> | null = null;

function instance(): WebTorrent {
  if (!registry.client) {
    const c = new WebTorrent({ dht: false });
    // A permanent listener, because an EventEmitter with none *throws* on
    // 'error' — which is how a rejected duplicate became an uncaught error
    // that no caller could catch, leaving the open to sit until it timed out.
    c.on('error', (err: unknown) => {
      console.warn('[torrent]', (err as Error)?.message ?? err);
    });
    log('client started');
    registry.client = c;
  }
  return registry.client;
}

/** Register the service worker and start the virtual HTTP server. Idempotent. */
export function startServer(): Promise<void> {
  if (serverPromise) return serverPromise;
  if (typeof navigator === 'undefined') return Promise.resolve();
  serverPromise = (async () => {
    try {
      const controller = await navigator.serviceWorker.register('/sw.min.js', {
        scope: '/',
      });
      if (controller.active || controller.installing || controller.waiting) {
        await navigator.serviceWorker.ready;
      }
      instance().createServer({ controller });
    } catch {
      // No service worker (http, private mode): playback falls back to
      // in-memory blobs, which still work for small files.
      serverPromise = null;
    }
  })();
  return serverPromise;
}

const VIDEO_EXT =
  /\.(mp4|mkv|webm|mov|avi|m4v|ts|m2ts|3gp|flv|ogv|wmv|mpg|mpeg)$/i;

function toFiles(files: TorrentLike['files']): TorrentFile[] {
  return files.map((f, i) => ({
    index: f.index ?? i,
    name: f.name,
    size: f.length,
    isVideo: VIDEO_EXT.test(f.name),
    isSubtitle: SUB_EXT.test(f.name),
    streamURL: f.streamURL ?? null,
  }));
}

import { SUB_EXT } from './subs';

export type Engine = {
  infoHash: string;
  torrentName: string;
  size: number;
  progress: number;
  downloaded: number;
  downloadSpeed: number;
  uploadSpeed: number;
  numPeers: number;
  timeRemaining: number;
  done: boolean;
  files: TorrentFile[];
  /** The uri this torrent was opened from (magnet / .torrent url). */
  uri: string;
  findFile(index: number): TorrentFile | null;
  /** HTTP URL the browser's video element can play (range requests). */
  urlOf(file: TorrentFile): string;
  /** Full file as a blob — the universal-encoder input. */
  blobOf(file: TorrentFile): Promise<{ blob: Blob; file: File }>;
  read(file: TorrentFile, bytes?: number): Promise<Uint8Array>;
  destroy(): void;
};

/** One stream per uri — matches the native app's single-stream rule. */
export function getTorrent(uri: string): Promise<Engine> {
  const existing = byUri.get(uri);
  if (existing) return Promise.resolve(wrap(existing, uri));

  // Matching on the uri string alone was not enough: the same torrent reaches
  // this from a release row and from the /watch URL with its trackers in a
  // different order, so the strings differ while the info hash does not — and
  // WebTorrent answers a second add of a hash it already holds by throwing
  // "Cannot add duplicate torrent". React also mounts effects twice in dev, so
  // two adds for one hash can be in flight at once; both cases are covered by
  // keying on the hash and sharing the pending promise.
  const hash = hashOf(uri);
  if (hash) {
    const known = byHash.get(hash) ?? findInClient(hash);
    if (known) {
      log('cache hit', hash);
      byUri.set(uri, known);
      return Promise.resolve(wrap(known, uri));
    }
    const inFlight = adding.get(hash);
    if (inFlight) return inFlight;
  }

  const openedAt = Date.now();
  startedAt.set(hash ?? uri, openedAt);
  log('adding', hash ?? uri.slice(0, 60));
  const pending = new Promise<Engine>((resolve, reject) => {
    const timer = setTimeout(() => {
      c.removeListener('torrent', onTorrent);
      c.removeListener('error', onError);
      const peers = (hash ? findInClient(hash) : null)?.numPeers ?? 0;
      log(`timed out after 60s with ${peers} peer(s)`);
      reject(
        new Error(
          peers > 0
            ? 'Connected, but no peer sent the file list in time. Try another release.'
            : 'No WebRTC peers for this release. A browser can only reach peers ' +
              'running WebTorrent — desktop clients on TCP are invisible to it. ' +
              'Open this one in the Android app, or pick a release someone is ' +
              'seeding over the web.'
        )
      );
    }, 60000);
    const c = instance();
    const onError = (err: Error) => {
      clearTimeout(timer);
      c.removeListener('torrent', onTorrent);
      // "Cannot add duplicate torrent <hash>" is not a failure: the torrent it
      // names is the one being asked for. WebTorrent decides this inside the
      // async parse, long after `add` returned, which is why it cannot be
      // caught around the call.
      const already = hash ? findInClient(hash) : null;
      if (already) {
        log('already running, reusing', already.infoHash);
        byHash.set(already.infoHash, already);
        byUri.set(uri, already);
        resolve(wrap(already, uri));
        return;
      }
      log('failed:', err?.message ?? err);
      reject(err);
    };
    const onTorrent = (torrent: any) => {
      // 'torrent' is a client event, not a per-add one: with two opens in
      // flight it fires for whichever arrives first. Without this the Downloads
      // page could hand a viewer somebody else's film.
      if (hash && String(torrent?.infoHash).toLowerCase() !== hash) return;
      c.removeListener('torrent', onTorrent);
      byHash.set(torrent.infoHash, torrent as TorrentLike);
      byUri.set(uri, torrent as TorrentLike);
      clearTimeout(timer);
      c.removeListener('error', onError);
      const t = torrent as TorrentLike;
      log(
        `metadata: ${t.name} in ${Date.now() - openedAt}ms — ${t.files.length} file(s), ` +
          `${(t.length / 1024 / 1024).toFixed(0)}MB`
      );
      resolve(wrap(torrent as TorrentLike, uri));
      // Warm a few pieces so the player's first range request is near-instant.
      const biggest = (torrent as TorrentLike).files.reduce(
        (a, b) => (b.length > a.length ? b : a)
      );
      if (biggest && !biggest.selected) {
        try {
          biggest.select();
        } catch {
          // Unknown API — the player's own request will still fill pieces.
        }
      }
    };
    c.on('torrent', onTorrent);
    c.once('error', onError);
    try {
      c.add(uri);
    } catch (e) {
      clearTimeout(timer);
      c.removeListener('torrent', onTorrent);
      c.removeListener('error', onError);
      // Lost a race with another add of the same hash — the torrent it
      // complained about is exactly the one being asked for.
      const already = hash ? findInClient(hash) : null;
      if (already) {
        byHash.set(already.infoHash, already);
        byUri.set(uri, already);
        resolve(wrap(already, uri));
        return;
      }
      reject(e);
    }
  });

  if (hash) {
    adding.set(hash, pending);
    pending.finally(() => adding.delete(hash)).catch(() => {});
  }
  return pending;
}

/** A torrent the client holds that our own maps have not seen — after a hot
 *  reload the module state resets while the client survives. */
function findInClient(hash: string): TorrentLike | null {
  const c = registry.client;
  if (!c) return null;
  const found = c.torrents.find(
    (t: any) => String(t?.infoHash).toLowerCase() === hash
  );
  return (found as TorrentLike) ?? null;
}

function wrap(raw: TorrentLike, uri: string): Engine {
  return {
    uri,
    infoHash: raw.infoHash,
    torrentName: raw.name,
    size: raw.length,
    progress: raw.progress,
    downloaded: raw.downloaded,
    downloadSpeed: raw.downloadSpeed,
    uploadSpeed: raw.uploadSpeed,
    numPeers: raw.numPeers,
    timeRemaining: raw.timeRemaining,
    done: raw.done,
    files: toFiles(raw.files),
    findFile(index) {
      const f = raw.files.find((x) => x.index === index);
      return f
        ? {
            index: f.index,
            name: f.name,
            size: f.length,
            isVideo: VIDEO_EXT.test(f.name),
            isSubtitle: SUB_EXT.test(f.name),
            streamURL: f.streamURL ?? null,
          }
        : null;
    },
    urlOf(file) {
      const f = raw.files[file.index];
      return f?.streamURL ?? '';
    },
    async blobOf(file) {
      const f = raw.files[file.index];
      if (!f) throw new Error('File gone');
      const url = await new Promise<string>((done) => f.getBlobURL(done));
      const blob = await (await fetch(url)).blob();
      return { blob, file: new File([blob], f.name) };
    },
    read(file, bytes) {
      const f = raw.files[file.index];
      if (!f) return Promise.reject(new Error('File gone'));
      const stream = f.createReadStream(bytes ? { end: bytes - 1 } : undefined);
      return new Promise<Uint8Array>((resolve, reject) => {
        const chunks: Uint8Array[] = [];
        stream.on('data', (c: Uint8Array) => chunks.push(c));
        stream.on('end', () => {
          const out = new Uint8Array(chunks.reduce((n, c) => n + c.length, 0));
          let at = 0;
          for (const c of chunks) {
            out.set(c, at);
            at += c.length;
          }
          resolve(out);
        });
        stream.on('error', reject);
      });
    },
    destroy() {
      try {
        raw.files.forEach((f) => f.deselect());
      } catch {
        // Ignore.
      }
    },
  };
}

/** Every open swarm — for the Downloads screen. */
export function activeTorrents(): Engine[] {
  return [...byUri.entries()].map(([uri, t]) => wrap(t, uri));
}

/** Live swarm numbers for one torrent. */
export function liveStats(infoHash: string): {
  progress: number;
  downloadSpeed: number;
  uploadSpeed: number;
  numPeers: number;
} {
  const t = byHash.get(infoHash);
  if (!t) return { progress: 0, downloadSpeed: 0, uploadSpeed: 0, numPeers: 0 };
  // The swarm line, once every ~10s. This is the readout that tells you whether
  // a stall is the swarm or the browser, so it is worth the one line.
  const now = Date.now();
  const at = lastLogged.get(infoHash) ?? 0;
  if (now - at > 10000) {
    lastLogged.set(infoHash, now);
    log(
      `${(t.downloadSpeed / 1024).toFixed(0)}kB/s down, ${t.numPeers} peers, ` +
        `${(t.progress * 100).toFixed(1)}% of ${t.name}`
    );
  }
  return {
    progress: t.progress,
    downloadSpeed: t.downloadSpeed,
    uploadSpeed: t.uploadSpeed,
    numPeers: t.numPeers,
  };
}

const lastLogged = new Map<string, number>();

/**
 * How an add is going before there is any metadata to report.
 *
 * WebTorrent creates the torrent object as soon as `add` is called, so peer
 * counts exist long before the info dict does — which is the difference
 * between "still looking" and "nobody here", and the only useful thing to
 * show while the spinner turns.
 */
export function pendingStats(uri: string): { peers: number; seconds: number } | null {
  const hash = hashOf(uri);
  const started = startedAt.get(hash ?? uri);
  if (!hash || !started) return null;
  const t = findInClient(hash);
  return {
    peers: t?.numPeers ?? 0,
    seconds: Math.round((Date.now() - started) / 1000),
  };
}

const startedAt = new Map<string, number>();

/** Stop a torrent and drop it from the bookkeeping. */
export function dropTorrent(uri: string) {
  const t = byUri.get(uri);
  if (!t) return;
  try {
    instance().remove(t as never);
  } catch {
    // Ignore.
  }
  byUri.delete(uri);
  byHash.delete(t.infoHash);
}

/** True when the browser can reach a service worker (https/localhost). */
export async function serverAvailable(): Promise<boolean> {
  try {
    if (typeof navigator === 'undefined') return false;
    return 'serviceWorker' in navigator;
  } catch {
    return false;
  }
}