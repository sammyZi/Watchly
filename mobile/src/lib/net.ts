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

/**
 * How long any one request gets.
 *
 * React Native's fetch has no timeout — a host that accepts the socket and
 * then says nothing leaves the promise pending for the life of the process.
 * `searchEpisode` memoises its promise, so one hung request left every later
 * press on that episode awaiting a promise that would never settle: the button
 * did nothing, the spinner never stopped, and only restarting the app cleared
 * it. Nothing here is worth waiting longer than this for.
 */
const TIMEOUT_MS = 15000;

/** Poll until something answers, then flip back. One loop at a time — every
 *  failing request calls this, and they fail in batches. */
async function probe() {
  if (probing) return;
  probing = true;
  for (let wait = 2000; ; wait = Math.min(wait * 2, 30000)) {
    await sleep(wait);
    try {
      // Through `net`, for its timeout: a probe left hanging is a loop that
      // never comes round again, and an offline banner nothing can clear.
      // Quiet, so a failure here cannot start a second probe.
      await net(PROBE, { method: 'HEAD' }, true);
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
  // Our own controller rather than the caller's, so the timer can cancel a
  // request nobody else is watching; the caller's signal still gets through.
  const stop = new AbortController();
  const timer = setTimeout(() => stop.abort(), TIMEOUT_MS);
  const caller = init?.signal;
  const relay = () => stop.abort();
  caller?.addEventListener('abort', relay);
  if (caller?.aborted) stop.abort();

  try {
    const res = await fetch(url, { ...init, signal: stop.signal });
    set(true);
    return res;
  } catch (e) {
    const aborted =
      stop.signal.aborted ||
      (e instanceof Error && (e.name === 'AbortError' || /abort/i.test(e.message)));
    // An abort, or a single index being down, is not a lost connection.
    if (!aborted && !quiet) {
      set(false);
      probe();
    }
    throw e;
  } finally {
    clearTimeout(timer);
    caller?.removeEventListener('abort', relay);
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
