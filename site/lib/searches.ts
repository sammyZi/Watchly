/**
 * What has been searched for, newest first.
 *
 * Only submitted queries are kept — storing what the debounce fires on would
 * fill the list with "t", "te", "ted" before the first real result landed.
 */
import { getItem, setItem, removeItem } from './storage';

const KEY = 'searches';
/** A row of chips, not an archive. */
const MAX = 8;

export async function loadSearches(): Promise<string[]> {
  try {
    const raw = await getItem(KEY);
    const all = raw ? JSON.parse(raw) : [];
    return Array.isArray(all) ? all.filter((q) => typeof q === 'string').slice(0, MAX) : [];
  } catch {
    return [];
  }
}

/** Move `query` to the front, however it was capitalised last time. */
export async function rememberSearch(query: string): Promise<string[]> {
  const q = query.trim();
  if (!q) return loadSearches();
  const rest = (await loadSearches()).filter((old) => old.toLowerCase() !== q.toLowerCase());
  const next = [q, ...rest].slice(0, MAX);
  await setItem(KEY, JSON.stringify(next));
  return next;
}

export async function clearSearches(): Promise<string[]> {
  await removeItem(KEY);
  return [];
}
