import AsyncStorage from '@react-native-async-storage/async-storage';

export type Settings = {
  /** MB buffered before playback starts. */
  prepareMb: number;
  /** Attach public trackers to bare magnet links so metadata resolves faster. */
  addTrackers: boolean;
  /** Delete the downloaded pieces when the stream stops. */
  removeFilesAfterStop: boolean;
};

export const DEFAULTS: Settings = {
  // One piece is enough to start; ExoPlayer's own buffer covers the rest, and
  // the native side blocks rather than serving holes. Every extra MB here is
  // dead time on the spinner.
  prepareMb: 1,
  addTrackers: true,
  removeFilesAfterStop: true,
};

const KEY = 'settings';

export async function loadSettings(): Promise<Settings> {
  try {
    return { ...DEFAULTS, ...JSON.parse((await AsyncStorage.getItem(KEY)) ?? '{}') };
  } catch {
    return DEFAULTS;
  }
}

export async function saveSettings(next: Settings) {
  await AsyncStorage.setItem(KEY, JSON.stringify(next));
  return next;
}
