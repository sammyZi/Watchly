/**
 * The library as one JSON file: history and resume points, saved lists,
 * settings, recent searches, episode links, intros.
 *
 * Downloads are left out — they name files on this phone's disk, which a
 * restore on another phone would point at nothing.
 */
import AsyncStorage from '@react-native-async-storage/async-storage';

const KEYS = [
  'torrent-history',
  'saved',
  'settings',
  'searches',
  'episodes',
  'intros',
  'aired-counts',
];

export async function exportLibrary(): Promise<string> {
  const pairs = await AsyncStorage.multiGet(KEYS);
  const data = Object.fromEntries(pairs.filter(([, v]) => v != null));
  return JSON.stringify({ app: 'watchly', version: 1, at: Date.now(), data });
}

/**
 * Replace what is stored with the file's copy, key by key. Only known keys, and
 * only values that are themselves JSON — a picked file is untrusted input, and
 * one bad key must not leave the app reading garbage on its next launch.
 * Returns how many keys were restored.
 */
export async function importLibrary(text: string): Promise<number> {
  const pairs = parseBackup(text);
  await AsyncStorage.multiSet(pairs);
  return pairs.length;
}

export function parseBackup(text: string): [string, string][] {
  let file: any;
  try {
    file = JSON.parse(text);
  } catch {
    throw new Error('That file is not a Watchly backup');
  }
  if (file?.app !== 'watchly' || typeof file.data !== 'object' || !file.data) {
    throw new Error('That file is not a Watchly backup');
  }
  const pairs = KEYS.filter((k) => typeof file.data[k] === 'string').flatMap(
    (k): [string, string][] => {
      try {
        JSON.parse(file.data[k]);
        return [[k, file.data[k]]];
      } catch {
        return [];
      }
    }
  );
  if (!pairs.length) throw new Error('That backup is empty');
  return pairs;
}
