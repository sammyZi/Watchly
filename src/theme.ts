// Cinema dark: the page is near-black, cards lift off it by tone alone — no
// borders, no shadows — and the artwork supplies the colour. Every screen reads
// these names, so the whole app changes tone from this one block.
export const colors = {
  /** Header, tab bar, and controls that sit on a card. */
  control: '#151922',
  bg: '#0b0e14',
  surface: '#181d28',
  surfaceAlt: '#232936',
  // Kept for the few real dividers (inside cards, the progress track).
  line: '#2d3444',
  text: '#f3f5fa',
  muted: '#9aa4b8',
  faint: '#66718a',
  // Pulled back from #e50914: same red, a touch deeper and less saturated, so
  // it stops vibrating against the near-black page without going dull.
  accent: '#d42f37',
  accentSoft: '#2a1216',
  onAccent: '#ffffff',
  danger: '#ff6b5e',
  dangerSoft: '#2c1512',
  // Swarm health only — green/amber read as "will this play", which no other
  // colour in here says.
  good: '#46d17f',
  goodSoft: '#10281b',
  warn: '#ffb340',
  warnSoft: '#2b2011',
  scrim: '#000000cc',
};

/**
 * Rail tints. Artwork carries most of the colour, but a shelf with nothing but
 * posters in it reads flat — each rail heading gets its own accent so the page
 * has some life before a single image loads.
 */
export const hues = ['#d42f37', '#7c5cff', '#00b8d4', '#f5a623', '#38c172'];

/** `colors.bg` as rgb, for gradient stops that need a transparent end. */
const BG = '11, 14, 20';

/** Artwork -> page, with no visible seam. Stacked flat washes left a hard edge
 *  across the middle of every backdrop; this is one real gradient instead. */
export const fadeToBg = (from = 0.35) =>
  `linear-gradient(to bottom, rgba(${BG},0) 0%, rgba(${BG},${from}) 45%, rgba(${BG},0.92) 82%, rgb(${BG}) 100%)`;

/** Page -> nothing, under the floating tab bar. */
export const fadeUp = `linear-gradient(to top, rgb(${BG}) 20%, rgba(${BG},0))`;

/** The tab bar floats over the content, so every scrolling screen has to end
 *  this far above its own bottom edge. */
export const TAB_INSET = 96;

export const font = {
  regular: 'NotoSans_400Regular',
  medium: 'NotoSans_500Medium',
  semibold: 'NotoSans_600SemiBold',
  bold: 'NotoSans_700Bold',
};

/** 4pt rhythm — every margin in the app is one of these. */
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

/** Only the modal is genuinely floating; everything else separates by tone. */
export const shadow = {
  shadowColor: '#0b1020',
  shadowOpacity: 0.18,
  shadowRadius: 24,
  shadowOffset: { width: 0, height: 8 },
  elevation: 12,
};
