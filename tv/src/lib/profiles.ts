import AsyncStorage from '@react-native-async-storage/async-storage';

/**
 * Who is watching.
 *
 * Deliberately not stored through `store`: the profile list is the one thing
 * that cannot live inside a profile, and the guest must still be able to read
 * it without leaving a trace.
 */
const KEY = 'profiles';
const LAST = 'profile-last';

export type Profile = {
  /** Stable, and used as the storage namespace — never reused after deletion. */
  id: string;
  name: string;
  /** Tile colour. Chosen at creation so a profile looks the same every time. */
  colour: string;
  /**
   * A Material icon name, picked from `AVATARS`.
   *
   * An initial tells two people called Sam apart badly, and across a room a
   * shape reads faster than a letter. Absent on profiles made before this
   * existed — the tile falls back to the initial for those.
   */
  icon?: string;
  /**
   * Four digits, asked for before the profile opens.
   *
   * A lock, not security: it is stored as typed, and four digits fall to a
   * patient child with a remote in a few minutes anyway. It exists to keep a
   * shared television's profiles separate, and it is worth being plain about
   * that rather than implying the contents are protected.
   */
  pin?: string | null;
};

/** The guest is not in the list and is never written down. */
export const GUEST: Profile = { id: 'guest', name: 'Guest', colour: '#5b6472' };

export const isGuest = (p: Profile) => p.id === GUEST.id;

/** Netflix picks from a fixed set; so does this, so two profiles are told
 *  apart by colour as much as by name. */
export const COLOURS = [
  '#e50914',
  '#2b8cff',
  '#f5a623',
  '#3ecf8e',
  '#a855f7',
  '#ff6b6b',
];

/**
 * The faces on offer.
 *
 * Material Community's filled emoticon and creature glyphs rather than the
 * outline UI set — these read as drawn characters at tile size, which is what
 * a profile picture is for. Bundled with the icon font, so they work with the
 * television offline; a hosted avatar service would render as ten empty
 * circles the first time the wifi drops.
 *
 * Ten: enough that a household of five all get something they like, few enough
 * to sit in one row without scrolling.
 */
export const AVATARS = [
  'emoticon-happy',
  'emoticon-cool',
  'emoticon-excited',
  'emoticon-wink',
  'emoticon-tongue',
  'robot',
  'cat',
  'dog',
  'alien',
  'penguin',
];

/**
 * The first profile keeps the original storage keys — see `store`. Everyone
 * added later gets a namespace of their own.
 */
export const FIRST_ID = '';

export async function loadProfiles(): Promise<Profile[]> {
  try {
    const raw = await AsyncStorage.getItem(KEY);
    const list = raw ? JSON.parse(raw) : null;
    if (Array.isArray(list) && list.length) {
      return list.filter((p) => p && typeof p.id === 'string');
    }
  } catch {
    /* fall through to the default */
  }
  return [];
}

async function writeProfiles(list: Profile[]) {
  await AsyncStorage.setItem(KEY, JSON.stringify(list));
}

export async function addProfile(
  name: string,
  icon?: string,
  colour?: string
): Promise<Profile[]> {
  const list = await loadProfiles();
  const profile: Profile = {
    // The very first one takes the shared keys so an existing library survives.
    id: list.length ? `p${Date.now().toString(36)}` : FIRST_ID,
    name: name.trim() || 'Me',
    colour: colour ?? COLOURS[list.length % COLOURS.length],
    icon: icon ?? AVATARS[list.length % AVATARS.length],
  };
  const next = [...list, profile];
  await writeProfiles(next);
  return next;
}

/** Rename, re-face, recolour — whatever was passed. */
export async function editProfile(
  id: string,
  changes: { name?: string; icon?: string; colour?: string }
): Promise<Profile[]> {
  const next = (await loadProfiles()).map((p) =>
    p.id === id
      ? {
          ...p,
          name: changes.name?.trim() || p.name,
          icon: changes.icon ?? p.icon,
          colour: changes.colour ?? p.colour,
        }
      : p
  );
  await writeProfiles(next);
  return next;
}

/** Four digits sets a lock; empty clears it. */
export async function setPin(id: string, pin: string): Promise<Profile[]> {
  const clean = pin.replace(/\D/g, '').slice(0, 4);
  const next = (await loadProfiles()).map((p) =>
    p.id === id ? { ...p, pin: clean.length === 4 ? clean : null } : p
  );
  await writeProfiles(next);
  return next;
}

export async function removeProfile(id: string): Promise<Profile[]> {
  const next = (await loadProfiles()).filter((p) => p.id !== id);
  await writeProfiles(next);
  return next;
}

/** Reopen on whoever was watching last — but never on the guest, whose whole
 *  point is that the next person starts clean. */
export async function loadLastProfile(): Promise<string | null> {
  try {
    return await AsyncStorage.getItem(LAST);
  } catch {
    return null;
  }
}

export async function rememberProfile(id: string) {
  if (id === GUEST.id) {
    await AsyncStorage.removeItem(LAST).catch(() => {});
    return;
  }
  await AsyncStorage.setItem(LAST, id).catch(() => {});
}
