import AsyncStorage from '@react-native-async-storage/async-storage';

export type Settings = {
  /** MB buffered before playback starts. */
  prepareMb: number;
  /** Attach public trackers to bare magnet links so metadata resolves faster. */
  addTrackers: boolean;
  /**
   * MB of the streamed file allowed on disk before the engine rolls it over.
   *
   * The one setting a television needs that a phone does not. A 4K film is tens
   * of gigabytes and the box has single digits free, so the stream is capped and
   * restarted at the playhead when it fills — one rebuffer per roll. Bigger is
   * better right up to the point it does not fit.
   */
  cacheCapMb: number;
  /** Genres to lead the home page with, in Cinemeta's spelling. Empty means
   *  the page is built from watch history alone. */
  genres: string[];
};

export const CACHE_CAPS = [2048, 4096, 8192, 16384];

export const DEFAULTS: Settings = {
  // One piece is enough to start; the player's own buffer covers the rest, and
  // the native side blocks rather than serving holes. Every extra MB here is
  // dead time on the spinner.
  prepareMb: 1,
  addTrackers: true,
  // Fits the 8GB of free space a mid-range TV box actually has, with room for
  // the system to breathe. Roughly half an hour of 4K between rolls.
  cacheCapMb: 4096,
  genres: [],
};

const KEY = 'settings';

export async function loadSettings(): Promise<Settings> {
  try {
    const raw = { ...DEFAULTS, ...JSON.parse((await AsyncStorage.getItem(KEY)) ?? '{}') };
    // Stored by an older build, or hand-edited: the home page maps over this.
    if (!Array.isArray(raw.genres)) raw.genres = [];
    if (typeof raw.cacheCapMb !== 'number' || raw.cacheCapMb <= 0) {
      raw.cacheCapMb = DEFAULTS.cacheCapMb;
    }
    return raw;
  } catch {
    return DEFAULTS;
  }
}

export async function saveSettings(next: Settings) {
  await AsyncStorage.setItem(KEY, JSON.stringify(next));
  return next;
}
