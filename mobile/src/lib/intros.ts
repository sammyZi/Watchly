/**
 * Where a show's opening titles sit, marked once by the viewer.
 *
 * ponytail: one window per show, in seconds from the start. Shows whose cold
 * open varies in length land a little early or late; per-episode detection
 * (audio fingerprinting, as Plex does) is the upgrade if that matters.
 */
import AsyncStorage from '@react-native-async-storage/async-storage';

export type Intro = { start: number; end: number };

const KEY = 'intros';

async function all(): Promise<Record<string, Intro>> {
  try {
    const raw = JSON.parse((await AsyncStorage.getItem(KEY)) ?? '{}');
    return raw && typeof raw === 'object' ? raw : {};
  } catch {
    return {};
  }
}

export async function loadIntro(show: string): Promise<Intro | null> {
  return (await all())[show] ?? null;
}

export async function saveIntro(show: string, intro: Intro) {
  const intros = await all();
  intros[show] = intro;
  await AsyncStorage.setItem(KEY, JSON.stringify(intros));
}

/** Inside the intro, with a moment's lead so the button is up as it starts. */
export const inIntro = (intro: Intro | null, at: number) =>
  !!intro && at >= intro.start - 2 && at < intro.end - 1;
