import AsyncStorage from '@react-native-async-storage/async-storage';

export type Settings = {
  /** Most MB buffered before playback starts; fast swarms need less. */
  prepareMb: number;
  /** Attach public trackers to bare magnet links so metadata resolves faster. */
  addTrackers: boolean;
  /** Delete the downloaded pieces when the stream stops. */
  removeFilesAfterStop: boolean;
  /** Genres to lead the home page with, in Cinemeta's spelling. Empty means
   *  the page is built from watch history alone. */
  genres: string[];
  /** Player default: fetch the whole file while streaming, or only the next few
   *  minutes past the playhead. */
  streamWholeFile: boolean;
  /** The player's picture mode, by label ("Best fit", "Crop"…) — carried from
   *  one video to the next, as the Windows player does. */
  playerScale: string;
  /** Playback speed, likewise remembered. */
  playbackRate: number;
  /** App appearance. */
  themeMode: 'system' | 'light' | 'dark';
  /** SubDL language code ("EN"). Also turns a matching built-in subtitle on by
   *  itself when a video opens; empty leaves subtitles off and searches English. */
  subLang: string;
  /** Audio track to prefer when a release carries several; empty is the file's default. */
  audioLang: string;
  subSize: SubSize;
  subColor: 'white' | 'yellow';
  /** A dark box behind subtitle text. */
  subBackground: boolean;
};

export type SubSize = 'small' | 'normal' | 'large' | 'huge';

/** libVLC's relative font size: the screen height divided by this, so smaller is bigger. */
export const SUB_SIZES: Record<SubSize, number> = { small: 20, normal: 16, large: 12, huge: 9 };
export const SUB_COLORS = { white: 0xffffff, yellow: 0xffff00 } as const;

export const DEFAULTS: Settings = {
  // One piece is enough to start; ExoPlayer's own buffer covers the rest, and
  // the native side blocks rather than serving holes. Every extra MB here is
  // dead time on the spinner.
  prepareMb: 1,
  addTrackers: true,
  removeFilesAfterStop: true,
  genres: [],
  streamWholeFile: false,
  playerScale: 'Best fit',
  playbackRate: 1,
  themeMode: 'system',
  subLang: '',
  audioLang: '',
  subSize: 'normal',
  subColor: 'white',
  subBackground: false,
};

const KEY = 'settings';

export async function loadSettings(): Promise<Settings> {
  try {
    const raw = { ...DEFAULTS, ...JSON.parse((await AsyncStorage.getItem(KEY)) ?? '{}') };
    if (raw.themeMode !== 'system' && raw.themeMode !== 'light' && raw.themeMode !== 'dark') {
      raw.themeMode = DEFAULTS.themeMode;
    }
    // Stored by an older build, or hand-edited: the home page maps over this.
    if (!Array.isArray(raw.genres)) raw.genres = [];
    // Also what a backup restores, so nothing here may be trusted to be a key.
    if (!(raw.subSize in SUB_SIZES)) raw.subSize = DEFAULTS.subSize;
    if (!(raw.subColor in SUB_COLORS)) raw.subColor = DEFAULTS.subColor;
    return raw;
  } catch {
    return DEFAULTS;
  }
}

export async function saveSettings(next: Settings) {
  await AsyncStorage.setItem(KEY, JSON.stringify(next));
  return next;
}

/** Change a few settings, keeping the rest as stored. */
export async function updateSettings(patch: Partial<Settings>) {
  return saveSettings({ ...(await loadSettings()), ...patch });
}
