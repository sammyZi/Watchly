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
};

/** @deprecated Prefer useColors() — static snapshot for rare non-React helpers. */
export let colors: Colors = darkColors;

export const hues = ['#d42f37', '#7c5cff', '#00b8d4', '#f5a623', '#38c172'];

export const TAB_INSET = 96;

export const font = {
  regular: 'NotoSans_400Regular',
  medium: 'NotoSans_500Medium',
  semibold: 'NotoSans_600SemiBold',
  bold: 'NotoSans_700Bold',
};

export const space = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 };

export const radius = { sm: 8, md: 12, lg: 18, pill: 999 };

export const type = {
  display: { fontFamily: font.bold, fontSize: 21, letterSpacing: -0.3 },
  title: { fontFamily: font.medium, fontSize: 15, lineHeight: 22 },
  body: { fontFamily: font.regular, fontSize: 14, lineHeight: 21 },
  label: { fontFamily: font.medium, fontSize: 13 },
  meta: { fontFamily: font.regular, fontSize: 12, lineHeight: 17 },
  overline: {
    fontFamily: font.medium,
    fontSize: 11,
    letterSpacing: 0.8,
    textTransform: 'uppercase' as const,
  },
  numeric: { fontFamily: font.bold, fontSize: 40, letterSpacing: -1 },
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
