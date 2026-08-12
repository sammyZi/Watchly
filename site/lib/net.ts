'use client';
/**
 * `fetch`, plus the connectivity bookkeeping — same contract as the native
 * app: an aborted or unreachable request marks the app offline, and a backoff
 * probe flips it back when the connection returns.
 *
 * apibay.org does not answer browser CORS preflights, so its requests are
 * rerouted through the local `/api/bay` proxy; everything else (Cinemeta,
 * Torrentio, SubDL) already serves `Access-Control-Allow-Origin: *`.
 */
import { useSyncExternalStore } from 'react';

/** Small, and on a host the app depends on anyway. */
const PROBE = 'https://v3-cinemeta.strem.io/manifest.json';

/** Hosts that need the local proxy — apibay blocks browser CORS. */
export function rewrite(url: string): string {
  if (/^https:\/\/apibay\.org\/q\.php\?/i.test(url)) {
    return `/api/bay?${url.slice(url.indexOf('?') + 1)}`;
  }
  return url;
}

let online = true;
let probing = false;
const listeners = new Set<() => void>();

function set(next: boolean) {
  if (next === online) return;
  online = next;
  for (const notify of listeners) notify();
}

const sleep = (ms: number) => new Promise((done) => setTimeout(done, ms));

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

export async function net(url: string, init?: RequestInit): Promise<Response> {
  try {
    const res = await fetch(rewrite(url), init);
    set(true);
    return res;
  } catch (e) {
    if (!init?.signal?.aborted) {
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