/**
 * The two lists a title can be put on: liked, and meant for later.
 *
 * Deliberately not the full `Title` — an episode list runs to hundreds of
 * entries and would be stale by the time it was read back anyway. A row holds
 * what a poster needs; the title page refetches the rest by id, off its own
 * disk cache.
 */
import AsyncStorage from '@react-native-async-storage/async-storage';
import type { Title } from './meta.ts';

export type List = 'favorite' | 'later';

export type Saved = {
  imdb: string;
  kind: 'movie' | 'tv';
  title: string;
  poster: string | null;
  year: string | null;
  rating: string | null;
  list: List;
  /** Newest first when read back — a list is a stack, not an archive. */
  at: number;
};

const KEY = 'saved';

export async function loadSaved(): Promise<Saved[]> {
  try {
    const raw = await AsyncStorage.getItem(KEY);
    const all: Saved[] = raw ? JSON.parse(raw) : [];
    return Array.isArray(all) ? all.sort((a, b) => b.at - a.at) : [];
  } catch {
    return [];
  }
}

export const onList = (saved: Saved[], imdb: string, list: List) =>
  saved.some((s) => s.imdb === imdb && s.list === list);

export const inList = (saved: Saved[], list: List) => saved.filter((s) => s.list === list);

/** Add if absent, remove if present. Returns the whole list, as history does. */
export async function toggleSaved(title: Title, list: List): Promise<Saved[]> {
  const all = await loadSaved();
  const without = all.filter((s) => !(s.imdb === title.imdb && s.list === list));
  const next =
    without.length === all.length
      ? [
          {
            imdb: title.imdb,
            kind: title.kind,
            title: title.title,
            poster: title.poster,
            year: title.year,
            rating: title.rating,
            list,
            at: Date.now(),
          },
          ...without,
        ]
      : without;
  await AsyncStorage.setItem(KEY, JSON.stringify(next));
  return next;
}

/** A stored row as the seed a title page opens on. */
export const asTitle = (s: Saved): Title => ({
  imdb: s.imdb,
  kind: s.kind,
  title: s.title,
  poster: s.poster,
  year: s.year,
  rating: s.rating,
  genres: [],
  plot: null,
  cast: [],
  directors: [],
  awards: null,
  trailer: null,
  backdrop: null,
  runtime: null,
  episodes: [],
});
