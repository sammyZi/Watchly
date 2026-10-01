/**
 * Languages the viewer can prefer, and finding them among a file's tracks.
 *
 * `code` is SubDL's. `match` is how libVLC names a track — "English",
 * "Track 2 - [English]", "eng" — so the English name and the ISO 639-2 codes.
 * No two-letter codes: "it" and "no" are words, and would match a track
 * called "Director's commentary - it is…".
 */
export const LANGS: { code: string; name: string; match: RegExp }[] = [
  { code: 'EN', name: 'English', match: /\b(english|eng)\b/i },
  { code: 'ES', name: 'Spanish', match: /\b(spanish|español|espanol|spa|castellano|latino)\b/i },
  { code: 'FR', name: 'French', match: /\b(french|français|francais|fre|fra)\b/i },
  { code: 'DE', name: 'German', match: /\b(german|deutsch|ger|deu)\b/i },
  { code: 'IT', name: 'Italian', match: /\b(italian|italiano|ita)\b/i },
  { code: 'PT', name: 'Portuguese', match: /\b(portuguese|português|portugues|por)\b/i },
  { code: 'BR_PT', name: 'Portuguese (Brazil)', match: /\b(brazil\w*|pt-br|pob)\b/i },
  { code: 'RU', name: 'Russian', match: /\b(russian|rus)\b/i },
  { code: 'AR', name: 'Arabic', match: /\b(arabic|ara)\b/i },
  { code: 'HI', name: 'Hindi', match: /\b(hindi|hin)\b/i },
  { code: 'TR', name: 'Turkish', match: /\b(turkish|tur)\b/i },
  { code: 'NL', name: 'Dutch', match: /\b(dutch|nederlands|dut|nld)\b/i },
  { code: 'PL', name: 'Polish', match: /\b(polish|polski|pol)\b/i },
  { code: 'JA', name: 'Japanese', match: /\b(japanese|jpn)\b/i },
  { code: 'KO', name: 'Korean', match: /\b(korean|kor)\b/i },
  { code: 'ZH', name: 'Chinese', match: /\b(chinese|mandarin|chi|zho)\b/i },
  { code: 'ID', name: 'Indonesian', match: /\b(indonesian|ind)\b/i },
  { code: 'VI', name: 'Vietnamese', match: /\b(vietnamese|vie)\b/i },
  { code: 'TH', name: 'Thai', match: /\b(thai|tha)\b/i },
  { code: 'FA', name: 'Persian', match: /\b(persian|farsi|fas)\b/i },
  { code: 'HE', name: 'Hebrew', match: /\b(hebrew|heb)\b/i },
  { code: 'EL', name: 'Greek', match: /\b(greek|gre|ell)\b/i },
  { code: 'SV', name: 'Swedish', match: /\b(swedish|swe)\b/i },
  { code: 'RO', name: 'Romanian', match: /\b(romanian|rum|ron)\b/i },
  { code: 'TA', name: 'Tamil', match: /\b(tamil|tam)\b/i },
  { code: 'TE', name: 'Telugu', match: /\b(telugu|tel)\b/i },
  { code: 'ML', name: 'Malayalam', match: /\b(malayalam|mal)\b/i },
  { code: 'BN', name: 'Bengali', match: /\b(bengali|bangla|ben)\b/i },
];

export const langName = (code: string) => LANGS.find((l) => l.code === code)?.name ?? code;

/**
 * The track in `code`'s language, preferring a full one.
 *
 * "Forced" subtitles only cover the foreign-language lines, and a commentary is
 * not the film — either is a match, but only when nothing else is.
 */
export function trackForLang<T extends { name: string }>(tracks: T[], code: string): T | null {
  const lang = LANGS.find((l) => l.code === code);
  if (!lang) return null;
  const hits = tracks.filter((t) => lang.match.test(t.name));
  return hits.find((t) => !/\b(forced|commentary|signs)\b/i.test(t.name)) ?? hits[0] ?? null;
}
