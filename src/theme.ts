// Cards separate from the page by tone alone — no borders, no shadows. The page
// and the chrome are white; a card is the only thing that carries a tint, and
// anything sitting *on* a card goes back to white.
export const colors = {
  /** Header, tab bar, and controls that sit on a card. */
  control: '#ffffff',
  bg: '#ffffff',
  surface: '#eef1f7',
  surfaceAlt: '#e1e7f1',
  // Kept for the few real dividers (inside cards, the progress track).
  line: '#dbe2ed',
  text: '#232833',
  muted: '#616a7a',
  faint: '#98a0ae',
  accent: '#3b6fe0',
  accentSoft: '#edf2fe',
  onAccent: '#ffffff',
  danger: '#d0483c',
  dangerSoft: '#fceeed',
  scrim: '#0009',
};

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
