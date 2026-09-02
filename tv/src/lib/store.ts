import AsyncStorage from '@react-native-async-storage/async-storage';

/**
 * Per-profile storage.
 *
 * Everything a viewer accumulates — history, watchlist, watched episodes,
 * recent searches — goes through here instead of touching AsyncStorage
 * directly, so one line decides whose shelf it lands on.
 *
 * The first profile deliberately keeps the original unprefixed keys. Anyone
 * upgrading already has a library under them, and a namespace change would
 * look exactly like losing it.
 */
let profile = '';

/**
 * Guest writes to memory and never to disk.
 *
 * Not "writes nowhere": resume points and the file list still have to work
 * while the guest is watching, and a stream that cannot record where it got to
 * is a worse guest than one that forgets afterwards. Closing the app is what
 * clears it — the map dies with the process.
 */
let guest = false;
const memory = new Map<string, string>();

export function useProfile(id: string, isGuest: boolean) {
  profile = id;
  guest = isGuest;
  if (isGuest) memory.clear();
}

/** Namespaced key. Empty profile id means the original, shared keys. */
const at = (key: string) => (profile ? `${key}@${profile}` : key);

export const store = {
  async getItem(key: string): Promise<string | null> {
    const k = at(key);
    if (guest) return memory.get(k) ?? null;
    return AsyncStorage.getItem(k);
  },

  async setItem(key: string, value: string): Promise<void> {
    const k = at(key);
    if (guest) {
      memory.set(k, value);
      return;
    }
    await AsyncStorage.setItem(k, value);
  },

  async removeItem(key: string): Promise<void> {
    const k = at(key);
    if (guest) {
      memory.delete(k);
      return;
    }
    await AsyncStorage.removeItem(k);
  },
};

/** Everything this profile owns, for when it is deleted. */
export async function wipeProfile(id: string) {
  const keys = await AsyncStorage.getAllKeys();
  const mine = keys.filter((k) => k.endsWith(`@${id}`));
  if (mine.length) await AsyncStorage.multiRemove(mine);
}
