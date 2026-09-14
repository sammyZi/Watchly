/**
 * Reading ffmpeg's stream banner. Pure — no ffmpeg, no server — so the client
 * can share the types and the parser can be checked on its own.
 */

export type Track = {
  /** Position among streams of its kind — what `-map 0:a:N` / `0:s:N` take. */
  index: number;
  codec: string;
  language: string | null;
  title: string | null;
  channels: string | null;
  isDefault: boolean;
};

export type Probe = {
  duration: number;
  video: { codec: string } | null;
  audio: Track[];
  /** Text subtitles only — bitmap ones (PGS, VobSub) cannot become WebVTT. */
  subtitles: Track[];
};

/** Codecs every current browser decodes straight from an MP4. */
const BROWSER_VIDEO = /^(h264|vp9|av1)$/;
const TEXT_SUBS = /^(subrip|srt|ass|ssa|webvtt|mov_text|text)$/;

/** ffmpeg's `-i` banner (stderr) -> streams. */
export function parseProbe(stderr: string): Probe {
  const out: Probe = { duration: 0, video: null, audio: [], subtitles: [] };
  const d = /Duration: (\d+):(\d+):(\d+(?:\.\d+)?)/.exec(stderr);
  if (d) out.duration = Number(d[1]) * 3600 + Number(d[2]) * 60 + Number(d[3]);

  let last: Track | null = null;
  let subtitleCount = 0;
  for (const line of stderr.split(/\r?\n/)) {
    const s = /^\s*Stream #\d+:\d+(?:\[[^\]]*\])?(?:\(([^)]*)\))?: (Video|Audio|Subtitle): ([^,\s]+)(.*)$/.exec(line);
    if (s) {
      last = null;
      const [, lang, kind, codec, rest] = s;
      const track: Track = {
        index: 0,
        codec,
        language: lang && lang !== 'und' ? lang : null,
        title: null,
        channels: kind === 'Audio' ? (/,\s*(mono|stereo|[\d.]+(?:\([a-z]+\))?|\d+ channels)\s*,/.exec(rest)?.[1] ?? null) : null,
        isDefault: /\(default\)/.test(rest),
      };
      if (kind === 'Video') {
        // Cover art is a "video" stream too; the first real one wins.
        if (!out.video && !/attached pic/.test(rest)) out.video = { codec };
      } else if (kind === 'Audio') {
        track.index = out.audio.length;
        out.audio.push(track);
        last = track;
      } else {
        // Numbered among all subtitle streams, bitmap ones included, because
        // that is what `-map 0:s:N` counts.
        track.index = subtitleCount++;
        if (TEXT_SUBS.test(codec)) {
          out.subtitles.push(track);
          last = track;
        }
      }
      continue;
    }
    const title = /^\s+title\s*:\s*(.+)$/.exec(line);
    if (title && last && !last.title) last.title = title[1].trim();
  }
  return out;
}

export const needsVideoEncode = (p: Probe) => !!p.video && !BROWSER_VIDEO.test(p.video.codec);
