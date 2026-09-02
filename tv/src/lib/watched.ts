import { store } from './store';

const KEY = 'episodes';

/**
 * Which torrent an episode was opened with.
 *
 * The player only ever knows about torrents, so an episode's progress cannot be
 * read back without this link: episode id -> the uri its history entry is
 * filed under. The position itself stays in history, which already tracks it.
 */
export type EpisodeLinks = Record<string, string>;

export async function loadEpisodeLinks(): Promise<EpisodeLinks> {
  try {
    const raw = await store.getItem(KEY);
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

export async function linkEpisode(id: string, uri: string): Promise<EpisodeLinks> {
  const links = { ...(await loadEpisodeLinks()), [id]: uri };
  await store.setItem(KEY, JSON.stringify(links));
  return links;
}

/** Anything past this counts as watched — credits are not worth chasing. */
export const WATCHED = 92;
