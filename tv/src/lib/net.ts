/**
 * Whether the network is there, and getting back on it when it returns.
 *
 * No new dependency: expo-network and NetInfo both mean a native module — and a
 * new dev build — for a boolean that every request already reports. A `fetch`
 * that throws before it has a status *is* the offline signal; a cheap HEAD on
 * a backoff is what says the connection is back.
 */
import { useSyncExternalStore } from 'react';

/** Small, and on a host the app depends on anyway. */
const PROBE = 'https://v3-cinemeta.strem.io/manifest.json';

let online = true;
let probing = false;
const listeners = new Set<() => void>();

function set(next: boolean) {
  if (next === online) return;
  online = next;
  for (const notify of listeners) notify();
}

const sleep = (ms: number) => new Promise((done) => setTimeout(done, ms));

/** Poll until something answers, then flip back. One loop at a time — every
 *  failing request calls this, and they fail in batches. */
async function probe() {
  if (probing) return;
  probing = true;
  for (let wait = 2000; ; wait = Math.min(wait * 2, 30000)) {
    await sleep(wait);
    try {
      await fetch(PROBE, { method: 'HEAD' });
      break;
    } catch {
      // Still down.
    }
  }
  probing = false;
  set(true);
}

/**
 * `fetch`, plus the connectivity bookkeeping.
 *
 * Only a thrown request counts as offline: a 404 or a 503 means the network is
 * fine and the service is not, and calling that "no internet" would raise a
 * banner that nothing can ever clear.
 */
export async function net(
  url: string,
  init?: RequestInit,
  /** Indexer misses are not "you are offline" — they must not flip the banner. */
  quiet = false
): Promise<Response> {
  try {
    const res = await fetch(url, init);
    set(true);
    return res;
  } catch (e) {
    const aborted =
      !!init?.signal?.aborted ||
      (e instanceof Error && (e.name === 'AbortError' || /abort/i.test(e.message)));
    // An abort, or a single index being down, is not a lost connection.
    if (!aborted && !quiet) {
      set(false);
      probe();
    }
    throw e;
  }
}

export const isOnline = () => online;

const subscribe = (notify: () => void) => {
  listeners.add(notify);
  return () => {
    listeners.delete(notify);
  };
};

/** Re-renders on every change. Put it in the deps of whatever load failed and
 *  that load runs again the moment the connection comes back. */
export function useOnline(): boolean {
  return useSyncExternalStore(subscribe, isOnline);
}
