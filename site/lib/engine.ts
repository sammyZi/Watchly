/**
 * The torrent engine, server side.
 *
 * A browser can only reach WebRTC peers, which almost no release has — so the
 * swarm runs here in Node (TCP, uTP, DHT, udp trackers: the same reach as the
 * Android app's libtorrent) and the page plays the file over plain HTTP range
 * requests from /api/torrent/[hash]/[index].
 *
 * One client, hung off globalThis so dev-server reloads do not orphan swarms.
 */
import WebTorrent from 'webtorrent';

const log = (...parts: unknown[]) => console.log('[engine]', ...parts);

function client(): WebTorrent {
  const g = globalThis as { __watchlyEngine?: WebTorrent };
  if (!g.__watchlyEngine) {
    const c = new WebTorrent();
    // An EventEmitter with no 'error' listener throws — which would take the
    // whole server down over one bad magnet.
    c.on('error', (err: unknown) => log('client error:', (err as Error)?.message ?? err));
    g.__watchlyEngine = c;
    log('client started');
  }
  return g.__watchlyEngine;
}

const VIDEO_EXT = /\.(mp4|mkv|webm|mov|avi|m4v|ts|m2ts|3gp|flv|ogv|wmv|mpg|mpeg)$/i;
const SUB_EXT = /\.(srt|vtt|ass|ssa)$/i;

export function describe(t: any) {
  return {
    infoHash: t.infoHash as string,
    ready: !!t.ready,
    torrentName: (t.name as string) ?? '',
    size: (t.length as number) ?? 0,
    progress: t.progress ?? 0,
    downloaded: t.downloaded ?? 0,
    downloadSpeed: t.downloadSpeed ?? 0,
    uploadSpeed: t.uploadSpeed ?? 0,
    numPeers: t.numPeers ?? 0,
    timeRemaining: Number.isFinite(t.timeRemaining) ? t.timeRemaining : 0,
    done: !!t.done,
    files: ((t.files ?? []) as any[]).map((f, index) => ({
      index,
      // path.join'd, so backslashes on Windows; the pages split on '/'.
      name: (f.path as string).replace(/\\/g, '/'),
      size: f.length as number,
      isVideo: VIDEO_EXT.test(f.name),
      isSubtitle: SUB_EXT.test(f.name),
    })),
  };
}

export type Described = ReturnType<typeof describe>;

export function find(hash: string): any | null {
  const h = hash.toLowerCase();
  return client().torrents.find((t: any) => t.infoHash === h) ?? null;
}

export function list(): any[] {
  return client().torrents;
}

/** The info hash a magnet names, lowercase. Null for a .torrent URL. */
export function hashOf(uri: string): string | null {
  const found = /xt=urn:btih:([0-9a-z]{40}|[0-9a-z]{32})/i.exec(uri);
  return found ? found[1].toLowerCase() : null;
}

/**
 * Torrents by the hash their magnet named, set the moment `add` returns.
 * A new torrent's own infoHash is only filled in after an async parse, so
 * without this two opens of one magnet (React mounts effects twice in dev)
 * both add it and WebTorrent destroys the second.
 */
const byMagnet: Map<string, any> = ((globalThis as any).__watchlyByMagnet ??= new Map());

/** Add (or reuse) a torrent and resolve once its file list is known. */
export async function open(uri: string, timeoutMs = 90000): Promise<any> {
  const hash = hashOf(uri);
  let t = hash ? (byMagnet.get(hash) ?? find(hash)) : null;
  if (!t) {
    t = client().add(uri);
    log('adding', hash ?? uri.slice(0, 80));
    if (hash) {
      byMagnet.set(hash, t);
      t.once('close', () => byMagnet.delete(hash));
    }
  }
  if (t.ready) return t;
  await new Promise<void>((resolve, reject) => {
    const done = () => {
      clearTimeout(timer);
      t.removeListener('ready', onReady);
      t.removeListener('error', onError);
    };
    const onReady = () => {
      done();
      resolve();
    };
    const onError = (err: Error) => {
      done();
      reject(err);
    };
    t.once('ready', onReady);
    t.once('error', onError);
    const timer = setTimeout(() => {
      done();
      reject(
        new Error(
          t.numPeers > 0
            ? 'Connected, but no peer sent the file list in time. Try another release.'
            : 'No peers answered for this release. Try another one with more seeders.'
        )
      );
    }, timeoutMs);
  });
  log(`metadata: ${t.name} — ${t.files.length} file(s)`);
  return t;
}

export function remove(hash: string): Promise<void> {
  const t = find(hash);
  if (!t) return Promise.resolve();
  return new Promise((resolve) => client().remove(t, { destroyStore: true }, () => resolve()));
}
