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
import { rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const log = (...parts: unknown[]) => console.log('[engine]', ...parts);

/**
 * Stream data is scratch, not a library: it lives here only while someone is
 * watching. Keeping a copy is what Save is for — that writes to the viewer's
 * own disk. Its own folder, so clearing it can never touch anything else.
 */
const STORE = join(tmpdir(), 'watchly-stream');
/** After the player leaves: long enough for a refresh to reclaim it. The
 *  download itself stops at once — see `release`. */
const RELEASE_GRACE_MS = 20_000;
/**
 * Nobody reading or polling (tab closed without a release, phone asleep):
 * stopped and deleted. The open player polls every second, so only a page
 * that is really gone goes quiet this long — and a player that comes back
 * reopens the torrent and resumes (see `recover` in the watch page).
 */
const IDLE_MS = 20_000;
/** How often idle torrents are looked for. */
const SWEEP_MS = 5_000;

type Use = { lastUse: number; readers: number; releasedAt: number | null };

type EngineState = { client: WebTorrent; use: Map<string, Use> };

function state(): EngineState {
  const g = globalThis as { __watchlyEngineV2?: EngineState };
  if (!g.__watchlyEngineV2) {
    // A fresh process is streaming nothing, so whatever an earlier run left
    // behind (a crash, a stop mid-film) is garbage.
    rmSync(STORE, { recursive: true, force: true });
    const c = new WebTorrent();
    // An EventEmitter with no 'error' listener throws — which would take the
    // whole server down over one bad magnet.
    c.on('error', (err: unknown) => log('client error:', (err as Error)?.message ?? err));
    const use = new Map<string, Use>();
    g.__watchlyEngineV2 = { client: c, use };
    setInterval(() => sweep(c, use), SWEEP_MS).unref();
    log('client started, store', STORE);
  }
  return g.__watchlyEngineV2;
}

const client = () => state().client;

function useOf(hash: string): Use {
  const { use } = state();
  let u = use.get(hash);
  if (!u) use.set(hash, (u = { lastUse: Date.now(), readers: 0, releasedAt: null }));
  return u;
}

/** Someone still wants this torrent — cancels a pending release and picks
 *  the download back up if the release had paused it. */
export function touch(hash: string) {
  const u = useOf(hash.toLowerCase());
  u.lastUse = Date.now();
  if (u.releasedAt != null) {
    u.releasedAt = null;
    const t = find(hash);
    if (t && t.paused && !t.done && !t.destroyed) {
      t.resume();
      if (t.ready) t.select(0, t.pieces.length - 1, false);
      log(`reclaimed, resuming: ${t.name}`);
    }
  }
}

/** An HTTP read of a file started. Call the returned function when it ends. */
export function reading(hash: string): () => void {
  const h = hash.toLowerCase();
  touch(h);
  useOf(h).readers++;
  let ended = false;
  return () => {
    if (ended) return;
    ended = true;
    const u = useOf(h);
    u.readers = Math.max(0, u.readers - 1);
    u.lastUse = Date.now();
  };
}

/**
 * The player left. Downloading stops now and the data is deleted after the
 * grace period unless the player comes back for it.
 *
 * Stopped by dropping the whole-torrent selection (no piece is wanted, so
 * nothing is requested) and pausing (no new peers) — not by closing the
 * connections, which left a reclaimed torrent with no one to download from.
 * Readers' own selections go away as their requests end.
 */
export function release(hash: string) {
  const h = hash.toLowerCase();
  const u = useOf(h);
  u.releasedAt = Date.now();
  const t = find(h);
  if (t && !t.destroyed && !t.done) {
    t.pause();
    if (t.ready) t.deselect(0, t.pieces.length - 1);
    log(`released, download stopped: ${t.name ?? h}`);
  }
  setTimeout(() => {
    const now = useOf(h);
    if (now.releasedAt != null && now.readers === 0 && Date.now() - now.releasedAt >= RELEASE_GRACE_MS) {
      log(`released, deleting stream data: ${t?.name ?? h}`);
      void remove(h);
    }
  }, RELEASE_GRACE_MS + 50);
}

function sweep(c: WebTorrent, use: Map<string, Use>) {
  const now = Date.now();
  for (const t of [...c.torrents]) {
    if (!t.infoHash) continue;
    const u = use.get(t.infoHash);
    if (!u) {
      useOf(t.infoHash);
      continue;
    }
    if (u.readers > 0) continue;
    const released = u.releasedAt != null && now - u.releasedAt > RELEASE_GRACE_MS;
    const idle = now - u.lastUse > IDLE_MS;
    if (released || idle) {
      log(`${released ? 'released' : 'idle'}, deleting stream data: ${t.name ?? t.infoHash}`);
      void remove(t.infoHash);
    }
  }
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
  // Also a sweep: torrents added before this rule (or by a previous build of
  // this module after a dev reload) have no 'done' listener.
  for (const t of client().torrents) if (t.done && !t.paused) stopSeeding(t);
  return client().torrents;
}

/**
 * A finished download stops uploading. The data stays on disk and keeps
 * playing — WebTorrent reads a done file straight from its store, no peers
 * involved. `pause` refuses new peers in both directions; the wires already
 * open have to be closed by hand.
 */
function stopSeeding(t: any) {
  if (t.destroyed) return;
  t.pause();
  for (const wire of [...t.wires]) wire.destroy();
  log(`done, stopped seeding: ${t.name}`);
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
  if (hash) touch(hash);
  if (!t) {
    t = client().add(uri, { path: STORE });
    t.on('done', () => stopSeeding(t));
    log('adding', hash ?? uri.slice(0, 80));
    if (hash) {
      byMagnet.set(hash, t);
      t.once('close', () => byMagnet.delete(hash));
    }
  }
  if (t.done && !t.paused) stopSeeding(t);
  if (t.ready) {
    touch(t.infoHash);
    return t;
  }
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
  touch(t.infoHash);
  return t;
}

/** Stop a torrent and delete its data from the store. */
export function remove(hash: string): Promise<void> {
  const t = find(hash);
  state().use.delete(hash.toLowerCase());
  if (!t) return Promise.resolve();
  log(`removing, data deleted: ${t.name ?? t.infoHash}`);
  // destroyStore deletes the files but leaves their folders behind.
  const folders = new Set<string>(
    (t.files ?? []).map((f: any) => String(f.path).split(/[\\/]/)[0]).filter(Boolean)
  );
  return new Promise((resolve) =>
    client().remove(t, { destroyStore: true }, () => {
      const inUse = new Set(
        client().torrents.flatMap((o: any) => (o.files ?? []).map((f: any) => String(f.path).split(/[\\/]/)[0]))
      );
      for (const dir of folders) {
        // Only top-level names that never escape the store.
        if (!inUse.has(dir) && dir !== '..' && !dir.includes(':')) {
          rmSync(join(STORE, dir), { recursive: true, force: true });
        }
      }
      resolve();
    })
  );
}
