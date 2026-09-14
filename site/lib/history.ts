import { getItem, setItem } from './storage';


export type StoredFile = {
  index: number;
  name: string;
  size: number;
  isVideo: boolean;
  isSubtitle: boolean;
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
    return JSON.parse((await getItem(KEY)) ?? '[]');
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
  await setItem(KEY, JSON.stringify(capped));
  return capped;
}

/** Insert or move-to-top, merging over whatever was already stored. */
export function put(item: Item) {
  return serial(async () => {
    const rest = await loadHistory();
    const prev = rest.find((x) => x.uri === item.uri);
    return write([{ ...prev, ...item }, ...rest.filter((x) => x.uri !== item.uri)]);
  });
}

/**
 * `fields` may be a function of the stored record. The per-file maps below
 * must merge into what is stored, not into the caller's copy: the player holds
 * a snapshot from when the file opened, and merging into that wiped every
 * other file's resume point in a season pack.
 */
export function patch(uri: string, fields: Partial<Item> | ((stored: Item) => Partial<Item>)) {
  return serial(async () => {
    const items = await loadHistory();
    return write(
      items.map((x) =>
        x.uri === uri ? { ...x, ...(typeof fields === 'function' ? fields(x) : fields) } : x
      )
    );
  });
}

/** Resume point for one file, without clobbering the others. */
export const setPosition = (item: Item, index: number, seconds: number) =>
  patch(item.uri, (x) => ({
    lastFileIndex: index,
    positions: { ...x.positions, [index]: seconds },
  }));

export const setDuration = (item: Item, index: number, seconds: number) =>
  patch(item.uri, (x) => ({ durations: { ...x.durations, [index]: seconds } }));

export const setFileSubs = (item: Item, index: number, subs: SavedSub[]) =>
  patch(item.uri, (x) => ({ subtitles: { ...x.subtitles, [index]: subs } }));

/** Web: subtitles live on the page as blob URLs — nothing to delete. */
const wipeSubs = () => Promise.resolve();

export const drop = (uri: string) =>
  serial(async () => {
    const items = await loadHistory();
    const gone = items.find((x) => x.uri === uri);
    if (gone) await wipeSubs();
    return write(items.filter((x) => x.uri !== uri));
  });

export const clearHistory = () =>
  serial(async () => {
    await wipeSubs();
    return write([]);
  });
