import AsyncStorage from '@react-native-async-storage/async-storage';
import TorrentStream from '../../modules/torrent-stream';

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
    return JSON.parse((await AsyncStorage.getItem(KEY)) ?? '[]');
  } catch {
    return [];
  }
}

async function write(items: Item[]) {
  const capped = items.slice(0, MAX);
  await AsyncStorage.setItem(KEY, JSON.stringify(capped));
  return capped;
}

/** Insert or move-to-top, merging over whatever was already stored. */
export async function put(item: Item) {
  const rest = await loadHistory();
  const prev = rest.find((x) => x.uri === item.uri);
  return write([{ ...prev, ...item }, ...rest.filter((x) => x.uri !== item.uri)]);
}

export async function patch(uri: string, fields: Partial<Item>) {
  const items = await loadHistory();
  return write(items.map((x) => (x.uri === uri ? { ...x, ...fields } : x)));
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

export const drop = async (uri: string) => {
  const items = await loadHistory();
  const gone = items.find((x) => x.uri === uri);
  if (gone) await wipeSubs(subPaths(gone));
  return write(items.filter((x) => x.uri !== uri));
};

export const clearHistory = async () => {
  const items = await loadHistory();
  await wipeSubs(items.flatMap(subPaths));
  return write([]);
};
