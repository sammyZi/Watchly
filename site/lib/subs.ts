'use client';
/**
 * Subtitle helpers for the web player.
 *
 * SubDL only serves zip files, so the download and unpack happen here in the
 * browser (fflate) — the same job the native module did on Android. In-torrent
 * subtitle files are streamed off the WebTorrent file directly.
 */
import { unzipSync, strFromU8 } from 'fflate';

/** Subtitle extensions the engine should expose in the track picker. */
export const SUB_EXT = /\.(srt|ass|ssa|vtt)$/i;

/** Download and unpack a SubDL zip; returns one text subtitle per entry. */
export async function fetchSubZip(url: string): Promise<Map<string, string>> {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Subtitle download returned ${res.status}`);
  const bytes = new Uint8Array(await res.arrayBuffer());
  const files = new Map<string, string>();
  const entries = unzipSync(bytes);
  for (const [name, data] of Object.entries(entries)) {
    if (!SUB_EXT.test(name)) continue;
    files.set(name, strFromU8(data));
  }
  return files;
}

/** Fetch a text subtitle by URL and keep only its real name. */
export async function fetchSubText(url: string): Promise<string> {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Subtitle download returned ${res.status}`);
  return res.text();
}

/**
 * SRT -> WebVTT, because `<track>` only speaks WebVTT. ASS/SSA is left alone:
 * the browser cannot read it either, so the player offers it as a raw
 * download instead. Rough but honest — text stays text.
 */
export function srtToVtt(srt: string): string {
  return (
    'WEBVTT\n\n' +
    srt
      .replace(/^\uFEFF/, '')
      .replace(/\r\n?/g, '\n')
      .replace(
        /(\d{1,2}):(\d{2}):(\d{2}),(\d{3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2}),(\d{3})/g,
        (_m, ...g: string[]) => {
          const t = (h: string, m: string, s: string, ms: string) =>
            `${h.padStart(2, '0')}:${m}:${s}.${ms}`;
          return `${t(g[0], g[1], g[2], g[3])} --> ${t(g[4], g[5], g[6], g[7])}`;
        }
      )
  );
}

/** Blob URL for one subtitle text, ready for `<track src>`. */
export function subtitleBlob(text: string, kind: 'srt' | 'vtt'): string {
  const vtt = kind === 'srt' ? srtToVtt(text) : text;
  return URL.createObjectURL(new Blob([vtt], { type: 'text/vtt' }));
}