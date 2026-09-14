'use client';
/**
 * Subtitle helpers for the web player.
 *
 * Online search and zip unpacking run on the server (/api/subs) so the SubDL
 * key stays there. In-torrent subtitle files stream off the engine directly.
 */
import type { Sub, SubQuery } from './subdl';

export type { Sub };

/** Subtitle extensions the engine should expose in the track picker. */
export const SUB_EXT = /\.(srt|ass|ssa|vtt)$/i;

async function answer(res: Response) {
  if (res.ok) return res;
  const body = await res.json().catch(() => null);
  throw new Error(body?.error ?? `Subtitles answered ${res.status}`);
}

/** Search SubDL through the server. */
export async function findSubtitles(q: SubQuery): Promise<Sub[]> {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries({
    q: q.query,
    imdb: q.imdb,
    kind: q.kind,
    lang: q.language,
    season: q.season,
    episode: q.episode,
  })) {
    if (v != null && v !== '') p.set(k, String(v));
  }
  return (await answer(await fetch(`/api/subs?${p}`))).json();
}

/** One SubDL result's subtitle text, unzipped by the server. The episode picks
 *  the right file out of a season pack. */
export async function downloadSubtitle(
  url: string,
  at?: { season?: number; episode?: number }
): Promise<string> {
  const p = new URLSearchParams({ url });
  if (at?.season != null) p.set('season', String(at.season));
  if (at?.episode != null) p.set('episode', String(at.episode));
  return (await answer(await fetch(`/api/subs?${p}`))).text();
}

/**
 * SRT -> WebVTT, because `<track>` only speaks WebVTT. Text that is already
 * WebVTT passes through.
 */
export function srtToVtt(srt: string): string {
  const text = srt.replace(/^﻿/, '').replace(/\r\n?/g, '\n');
  if (text.startsWith('WEBVTT')) return text;
  return (
    'WEBVTT\n\n' +
    text.replace(
      /(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})/g,
      (_m, ...g: string[]) => {
        const t = (h: string, m: string, s: string, ms: string) =>
          `${h.padStart(2, '0')}:${m}:${s}.${ms}`;
        return `${t(g[0], g[1], g[2], g[3])} --> ${t(g[4], g[5], g[6], g[7])}`;
      }
    )
  );
}

/** Blob URL for one subtitle text, ready for `<track src>`. */
export function subtitleBlob(text: string): string {
  return URL.createObjectURL(new Blob([srtToVtt(text)], { type: 'text/vtt' }));
}
