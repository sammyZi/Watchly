import { getItem, setItem, removeItem } from './storage';

export type Settings = {
  /** MB buffered before playback starts. */
  prepareMb: number;
  /** Attach public trackers to bare magnet links so metadata resolves faster. */
  addTrackers: boolean;
  /** Delete the downloaded pieces when the stream stops. */
  removeFilesAfterStop: boolean;
  /** Genres to lead the home page with, in Cinemeta's spelling. Empty means
   *  the page is built from watch history alone. */
  genres: string[];
  /** App appearance. */
  themeMode: 'system' | 'light' | 'dark';
};

export const DEFAULTS: Settings = {
  // One piece is enough to start; ExoPlayer's own buffer covers the rest, and
  // the native side blocks rather than serving holes. Every extra MB here is
  // dead time on the spinner.
  prepareMb: 1,
  addTrackers: true,
  removeFilesAfterStop: true,
  genres: [],
  themeMode: 'system',
};

const KEY = 'settings';

export async function loadSettings(): Promise<Settings> {
  try {
    const raw = { ...DEFAULTS, ...JSON.parse((await getItem(KEY)) ?? '{}') };
    if (raw.themeMode !== 'system' && raw.themeMode !== 'light' && raw.themeMode !== 'dark') {
      raw.themeMode = DEFAULTS.themeMode;
    }
    // Stored by an older build, or hand-edited: the home page maps over this.
    if (!Array.isArray(raw.genres)) raw.genres = [];
    return raw;
  } catch {
    return DEFAULTS;
  }
}

export async function saveSettings(next: Settings) {
  await setItem(KEY, JSON.stringify(next));
  return next;
}
