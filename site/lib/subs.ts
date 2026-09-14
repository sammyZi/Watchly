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

/**
 * Every cue moved `seconds` earlier; cues that end before zero are dropped.
 * A converted stream starts its clock at zero wherever in the film it began.
 */
export function shiftVtt(vtt: string, seconds: number): string {
  if (!seconds) return vtt;
  const toSec = (t: string) => {
    const p = t.split(':').map(Number);
    return p.length === 3 ? p[0] * 3600 + p[1] * 60 + p[2] : p[0] * 60 + p[1];
  };
  const fmt = (s: number) => {
    const v = Math.max(0, s);
    const h = Math.floor(v / 3600);
    const m = Math.floor((v % 3600) / 60);
    return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${(v % 60).toFixed(3).padStart(6, '0')}`;
  };
  const TIME = /((?:\d+:)?\d{2}:\d{2}\.\d{3})\s*-->\s*((?:\d+:)?\d{2}:\d{2}\.\d{3})([^\n]*)/;
  return vtt
    .split(/\n\n+/)
    .map((block) => {
      const found = TIME.exec(block);
      if (!found) return block;
      const end = toSec(found[2]) - seconds;
      if (end <= 0) return null;
      return block.replace(TIME, `${fmt(toSec(found[1]) - seconds)} --> ${fmt(end)}${found[3]}`);
    })
    .filter((b): b is string => b !== null)
    .join('\n\n');
}

/** Blob URL for one subtitle text, ready for `<track src>`. `shift` moves
 *  every cue that many seconds earlier. */
export function subtitleBlob(text: string, shift = 0): string {
  return URL.createObjectURL(new Blob([shiftVtt(srtToVtt(text), shift)], { type: 'text/vtt' }));
}
