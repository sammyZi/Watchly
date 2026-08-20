import {
  createContext,
  createElement,
  useCallback,
  useContext,
  useMemo,
  useRef,
  type ReactNode,
} from 'react';
import {
  StyleSheet,
  useColorScheme,
  type ImageStyle,
  type TextStyle,
  type ViewStyle,
} from 'react-native';

export type ThemeMode = 'system' | 'light' | 'dark';
export type ColorScheme = 'light' | 'dark';

export type Colors = {
  control: string;
  bg: string;
  surface: string;
  surfaceAlt: string;
  line: string;
  text: string;
  muted: string;
  faint: string;
  accent: string;
  accentSoft: string;
  onAccent: string;
  danger: string;
  dangerSoft: string;
  good: string;
  goodSoft: string;
  warn: string;
  warnSoft: string;
  scrim: string;
  /** Floating tab capsule fill (with alpha). */
  tabBar: string;
  /** Ring drawn around whatever the D-pad is currently on. */
  focus: string;
};

export const darkColors: Colors = {
  control: '#151922',
  bg: '#000000',
  surface: '#181d28',
  surfaceAlt: '#232936',
  line: '#2d3444',
  text: '#f3f5fa',
  muted: '#9aa4b8',
  faint: '#66718a',
  accent: '#d42f37',
  accentSoft: '#2a1216',
  onAccent: '#ffffff',
  danger: '#ff6b5e',
  dangerSoft: '#2c1512',
  good: '#46d17f',
  goodSoft: '#10281b',
  warn: '#ffb340',
  warnSoft: '#2b2011',
  scrim: '#000000cc',
  tabBar: '#181d28d9',
  focus: '#ffffff',
};

export const lightColors: Colors = {
  control: '#ffffff',
  bg: '#ffffff',
  surface: '#f2f3f7',
  surfaceAlt: '#e6e8ef',
  line: '#d5d9e2',
  text: '#12141a',
  muted: '#5c6578',
  faint: '#8b93a7',
  accent: '#d42f37',
  accentSoft: '#fde8ea',
  onAccent: '#ffffff',
  danger: '#d94040',
  dangerSoft: '#fdecea',
  good: '#1a9f55',
  goodSoft: '#e6f7ed',
  warn: '#c47d00',
  warnSoft: '#fff4e0',
  scrim: '#00000099',
  tabBar: '#fde8eaeb',
  focus: '#12141a',
};

/** @deprecated Prefer useColors() — static snapshot for rare non-React helpers. */
export let colors: Colors = darkColors;

export const hues = ['#d42f37', '#7c5cff', '#00b8d4', '#f5a623', '#38c172'];

export const TAB_INSET = 132;

export const font = {
  regular: 'NotoSans_400Regular',
  medium: 'NotoSans_500Medium',
  semibold: 'NotoSans_600SemiBold',
  bold: 'NotoSans_700Bold',
};

export const space = { xs: 4, sm: 8, md: 14, lg: 20, xl: 28, xxl: 40 };

export const radius = { sm: 8, md: 12, lg: 18, pill: 999 };

/**
 * Overscan. Televisions crop the edges of the picture by a few percent and
 * every set does it differently, so nothing readable goes nearer the edge
 * than this.
 */
export const SAFE = { x: 28, y: 20 };

/**
 * The collapsed navigation rail, and the gutter every screen leaves for it.
 *
 * The rail floats over the page rather than taking a column out of it, so that
 * a backdrop can run the full width of the television behind it.
 */
export const NAV_W = 68;
export const NAV_OPEN_W = 210;
export const GUTTER = NAV_W + SAFE.x;

/**
 * Sized for the canvas a television actually reports, which is much smaller
 * than the panel: 3840x2160 at density 640 is 960x540dp, and 1920x1080 at
 * density 320 is the same. Doubling phone type for "ten feet" overflows that
 * canvas — three posters to a row instead of six. These are close to Leanback's
 * own figures, nudged up for viewing distance.
 */
export const type = {
  display: { fontFamily: font.bold, fontSize: 26, letterSpacing: -0.4 },
  title: { fontFamily: font.medium, fontSize: 18, lineHeight: 25 },
  body: { fontFamily: font.regular, fontSize: 15, lineHeight: 22 },
  label: { fontFamily: font.medium, fontSize: 15 },
  meta: { fontFamily: font.regular, fontSize: 13, lineHeight: 18 },
  overline: {
    fontFamily: font.medium,
    fontSize: 11,
    letterSpacing: 1,
    textTransform: 'uppercase' as const,
  },
  numeric: { fontFamily: font.bold, fontSize: 40, letterSpacing: -1 },
  /** The one thing bigger than everything: a hero title over artwork. */
  hero: { fontFamily: font.bold, fontSize: 38, letterSpacing: -1, lineHeight: 44 },
};

export const shadow = {
  shadowColor: '#0b1020',
  shadowOpacity: 0.18,
  shadowRadius: 24,
  shadowOffset: { width: 0, height: 8 },
  elevation: 12,
};

function hexToRgb(hex: string): string {
  const h = hex.replace('#', '').slice(0, 6);
  const n = parseInt(h, 16);
  return `${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}`;
}

export function fadeToBg(bg: string, from = 0.35) {
  const rgb = hexToRgb(bg);
  return `linear-gradient(to bottom, rgba(${rgb},0) 0%, rgba(${rgb},${from}) 45%, rgba(${rgb},0.92) 82%, rgb(${rgb}) 100%)`;
}

/** Solid at the left, clear by halfway — keeps hero prose off the artwork. */
export function fadeRight(bg: string) {
  const rgb = hexToRgb(bg);
  return (
    `linear-gradient(to right, rgb(${rgb}) 0%, rgba(${rgb},0.86) 28%, ` +
    `rgba(${rgb},0.35) 62%, rgba(${rgb},0) 100%)`
  );
}

export function fadeUp(bg: string) {
  const rgb = hexToRgb(bg);
  return `linear-gradient(to top, rgb(${rgb}) 20%, rgba(${rgb},0))`;
}

type ThemeCtx = {
  mode: ThemeMode;
  scheme: ColorScheme;
  colors: Colors;
  setMode: (mode: ThemeMode) => void;
};

const ThemeContext = createContext<ThemeCtx>({
  mode: 'system',
  scheme: 'dark',
  colors: darkColors,
  setMode: () => {},
});

export function ThemeProvider({
  mode,
  onChangeMode,
  children,
}: {
  mode: ThemeMode;
  onChangeMode: (mode: ThemeMode) => void;
  children: ReactNode;
}) {
  const system = useColorScheme();
  const scheme: ColorScheme =
    mode === 'system' ? (system === 'light' ? 'light' : 'dark') : mode;
  const palette = scheme === 'light' ? lightColors : darkColors;

  // Keep setMode stable so context consumers don't all re-render on parent updates.
  const onChangeRef = useRef(onChangeMode);
  onChangeRef.current = onChangeMode;
  const setMode = useCallback((next: ThemeMode) => {
    onChangeRef.current(next);
  }, []);

  const value = useMemo<ThemeCtx>(
    () => ({
      mode,
      scheme,
      colors: palette,
      setMode,
    }),
    [mode, scheme, palette, setMode]
  );

  // Keep the legacy export in sync for any stray non-hook reads during render.
  colors = palette;

  return createElement(ThemeContext.Provider, { value }, children);
}

export function useTheme() {
  return useContext(ThemeContext);
}

export function useColors() {
  return useContext(ThemeContext).colors;
}

type NamedStyles = {
  [key: string]: ViewStyle | TextStyle | ImageStyle;
};

/**
 * Cache StyleSheets per scheme. Build the active one on first use (and the
 * other on first toggle) — creating both for every screen at launch OOM'd.
 */
export function useThemedStyles<T extends NamedStyles>(factory: (c: Colors) => T): T {
  const { scheme } = useTheme();
  const factoryRef = useRef(factory);
  factoryRef.current = factory;
  const cache = useRef<{ light?: T; dark?: T }>({});

  if (scheme === 'light') {
    if (!cache.current.light) {
      cache.current.light = StyleSheet.create(factoryRef.current(lightColors)) as T;
    }
    return cache.current.light;
  }

  if (!cache.current.dark) {
    cache.current.dark = StyleSheet.create(factoryRef.current(darkColors)) as T;
  }
  return cache.current.dark;
}
