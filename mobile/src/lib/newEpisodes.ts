/**
 * Followed series with episodes out since they were last looked at.
 *
 * "Last looked at" is a count of aired episodes per show, taken the first time
 * a show is seen here and again whenever its page is opened. Checked when the
 * home page builds — there is no background job — off `loadTitle`'s day-long
 * cache, so an episode shows up within a day of airing, not the minute it does.
 */
import AsyncStorage from '@react-native-async-storage/async-storage';
import { loadTitle, type Title } from './meta.ts';

const KEY = 'aired-counts';
/** Each show is one /meta request on a cold cache. */
const MAX_SHOWS = 25;

export type Fresh = { title: Title; count: number };

/** Specials (season 0) are not "new episodes", and Cinemeta lists episodes
 *  ahead of their air date. */
export const airedCount = (t: Title, now = Date.now()) =>
  t.episodes.filter((e) => e.season > 0 && !!e.released && Date.parse(e.released) <= now).length;

/** Pure part: which titles have moved past their baseline, and the baselines to keep. */
export function compare(titles: Title[], known: Record<string, number>, now = Date.now()) {
  const fresh: Fresh[] = [];
  const next = { ...known };
  for (const t of titles) {
    if (!t.episodes.length) continue;
    const aired = airedCount(t, now);
    const seen = known[t.imdb];
    if (seen == null) next[t.imdb] = aired;
    else if (aired > seen) fresh.push({ title: t, count: aired - seen });
  }
  return { fresh, next };
}

async function read(): Promise<Record<string, number>> {
  try {
    return JSON.parse((await AsyncStorage.getItem(KEY)) ?? '{}') ?? {};
  } catch {
    return {};
  }
}

export async function newEpisodes(imdbs: string[]): Promise<Fresh[]> {
  const ids = [...new Set(imdbs)].slice(0, MAX_SHOWS);
  if (!ids.length) return [];
  const titles = (await Promise.all(ids.map((id) => loadTitle(id, 'tv')))).filter(
    (t): t is Title => !!t
  );
  const known = await read();
  const { fresh, next } = compare(titles, known);
  if (Object.keys(next).length !== Object.keys(known).length) {
    await AsyncStorage.setItem(KEY, JSON.stringify(next)).catch(() => {});
  }
  return fresh;
}

/** The viewer has seen what is out. Only with the full episode list — a shelf
 *  row has none, and a zero baseline would make every episode "new" later. */
export async function markSeen(t: Title) {
  if (t.kind !== 'tv' || !t.episodes.length) return;
  const known = await read();
  const aired = airedCount(t);
  if (known[t.imdb] === aired) return;
  await AsyncStorage.setItem(KEY, JSON.stringify({ ...known, [t.imdb]: aired })).catch(() => {});
}
