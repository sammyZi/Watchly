import AsyncStorage from '@react-native-async-storage/async-storage';

export type StoredFile = {
  index: number;
  name: string;
  size: number;
  isVideo: boolean;
  isSubtitle: boolean;
};

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

  subtitle?: string; // attached .srt/.vtt uri

  /** Frame the viewer stopped on, per file index. Cache-directory uris, so
   *  treat every one as possibly gone. */
  posters?: Record<string, string>;
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

export const setPoster = (item: Item, index: number, uri: string) =>
  patch(item.uri, { posters: { ...item.posters, [index]: uri } });

export const setDuration = (item: Item, index: number, seconds: number) =>
  patch(item.uri, { durations: { ...item.durations, [index]: seconds } });

export const drop = async (uri: string) =>
  write((await loadHistory()).filter((x) => x.uri !== uri));

export const clearHistory = () => write([]);
