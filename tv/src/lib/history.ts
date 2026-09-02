import { store } from './store';
import TorrentStream from '../../modules/torrent-stream';

export type StoredFile = {
  index: number;
  name: string;
  size: number;
  isVideo: boolean;
  isSubtitle: boolean;
  path?: string;
};

export type SavedSub = { path: string; label: string };

export type Item = {
  uri: string; // magnet / url / local .torrent path — the identity
  title: string;
  at: number; // last opened, ms

  /** Contents of the torrent, filled in once metadata resolves. */
  torrentName?: string;
  files?: StoredFile[];
  lastFileIndex?: number;

  /** Resume point and duration per file index, both in seconds. */
  positions?: Record<string, number>;
  durations?: Record<string, number>;

  /** SubDL downloads kept for next play, keyed by file index. */
  subtitles?: Record<string, SavedSub[]>;

  /**
   * What this torrent turned out to be, once resolved against the metadata
   * service. Stored so the library shows real artwork on the next launch
   * instead of looking it up again.
   */
  imdb?: string;
  kind?: 'movie' | 'tv';
  poster?: string;
  /** The real title, so the library reads "The Matrix" rather than
   *  "The.Matrix.1999.1080p.BluRay.x264-GRP". */
  titleName?: string;
};

const KEY = 'torrent-history';
const MAX = 50;

export async function loadHistory(): Promise<Item[]> {
  try {
    return JSON.parse((await store.getItem(KEY)) ?? '[]');
  } catch {
    return [];
  }
}

/**
 * Every mutation runs one at a time.
 *
 * They are all read-modify-write against a single key, and they fire from
 * everywhere at once: the player checkpointing a position, the poster backfill
 * finishing a lookup, metadata landing. Interleaved, the later write is built
 * on a list read before the earlier one landed — which is how "clear history"
 * came back undone. The clear writes an empty list, an already-in-flight patch
 * writes back the copy it read a moment earlier, and the rows are on disk again
 * for the next launch.
 */
let queue: Promise<unknown> = Promise.resolve();

function serial<T>(job: () => Promise<T>): Promise<T> {
  const next = queue.then(job, job);
  // The chain must survive a failed job, or one rejection strands every write
  // after it.
  queue = next.catch(() => {});
  return next;
}

async function write(items: Item[]) {
  const capped = items.slice(0, MAX);
  await store.setItem(KEY, JSON.stringify(capped));
  return capped;
}

/** Prefer a real poster URL over a missing or leftover local still. */
export const remoteArt = (...urls: (string | null | undefined)[]) =>
  urls.find((u) => !!u && /^https?:\/\//i.test(u)) ?? undefined;

/** Artwork to show for a library row — this item, a saved title, or a sibling. */
export function posterOf(
  item: Item,
  extras?: { saved?: { imdb: string; poster: string | null }[]; kin?: Item[] }
) {
  return remoteArt(
    item.poster,
    extras?.saved?.find((s) => s.imdb === item.imdb)?.poster,
    extras?.kin?.find((h) => h.uri !== item.uri && h.imdb && h.imdb === item.imdb)
      ?.poster
  );
}

/** Insert or move-to-top, merging over whatever was already stored. */
export function put(item: Item) {
  return serial(async () => {
    const rest = await loadHistory();
    const prev = rest.find((x) => x.uri === item.uri);
    // A different episode of the same show still has the artwork this row needs.
    const kin = rest.find(
      (x) =>
        x.uri !== item.uri &&
        !!remoteArt(x.poster) &&
        ((item.imdb && x.imdb === item.imdb) ||
          (prev?.imdb && x.imdb === prev.imdb) ||
          (item.titleName && x.titleName === item.titleName))
    );
    const merged: Item = {
      ...prev,
      ...item,
      poster: remoteArt(item.poster, prev?.poster, kin?.poster),
      imdb: item.imdb || prev?.imdb || kin?.imdb,
      kind: item.kind || prev?.kind || kin?.kind,
      titleName: item.titleName || prev?.titleName || kin?.titleName,
    };
    return write([merged, ...rest.filter((x) => x.uri !== item.uri)]);
  });
}

export function patch(uri: string, fields: Partial<Item>) {
  return serial(async () => {
    const items = await loadHistory();
    return write(items.map((x) => (x.uri === uri ? { ...x, ...fields } : x)));
  });
}

/** Resume point for one file, without clobbering the others. */
export const setPosition = (item: Item, index: number, seconds: number) =>
  patch(item.uri, {
    lastFileIndex: index,
    positions: { ...item.positions, [index]: seconds },
  });

export const setDuration = (item: Item, index: number, seconds: number) =>
  patch(item.uri, { durations: { ...item.durations, [index]: seconds } });

export const setFileSubs = (item: Item, index: number, subs: SavedSub[]) =>
  patch(item.uri, {
    subtitles: { ...item.subtitles, [index]: subs },
  });

const subPaths = (item: Item) =>
  Object.values(item.subtitles ?? {}).flatMap((xs) => xs.map((s) => s.path));

const wipeSubs = (paths: string[]) =>
  paths.length
    ? TorrentStream.deleteSubtitles(paths).catch(() => {})
    : Promise.resolve();

export const drop = (uri: string) =>
  serial(async () => {
    const items = await loadHistory();
    const gone = items.find((x) => x.uri === uri);
    if (gone) await wipeSubs(subPaths(gone));
    return write(items.filter((x) => x.uri !== uri));
  });

export const clearHistory = () =>
  serial(async () => {
    const items = await loadHistory();
    await wipeSubs(items.flatMap(subPaths));
    return write([]);
  });
