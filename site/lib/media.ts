/**
 * Server-side ffmpeg (ffmpeg-static): what a browser cannot do with a release.
 *
 *  - probe: audio tracks, built-in subtitles, codecs, duration
 *  - remux: a fragmented MP4 the <video> element can play as it arrives —
 *           a chosen audio track, audio always AAC stereo, video copied when
 *           browsers decode it and re-encoded to H.264 when they do not
 *  - subtitle: one built-in text subtitle track as WebVTT
 *
 * Input is the torrent's own range-request URL, so ffmpeg seeks through the
 * engine exactly like the player does.
 */
import { spawn, type ChildProcessByStdio } from 'node:child_process';
import type { Readable } from 'node:stream';
import ffmpegPath from 'ffmpeg-static';

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

/** ffmpeg's `-i` banner (stderr) -> streams. Pure, so it can be checked. */
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

/**
 * The torrent file's own stream URL, for ffmpeg to read. Loopback, so it
 * never leaves the machine whatever address the viewer used.
 * ponytail: plain http on the server's own port; behind a TLS-terminating
 * proxy set PORT to the port Next listens on.
 */
export function inputUrl(requestUrl: URL, hash: string, index: string): string {
  const port = process.env.PORT || requestUrl.port || '3000';
  return `http://127.0.0.1:${port}/api/torrent/${hash}/${index}`;
}

export const needsVideoEncode =(p: Probe) => !!p.video && !BROWSER_VIDEO.test(p.video.codec);

const bin = () => {
  if (!ffmpegPath) throw new Error('ffmpeg is not available on this server.');
  return ffmpegPath;
};

const probes = new Map<string, Promise<Probe>>();

/** Cached per input — a file's streams never change. */
export function probe(input: string): Promise<Probe> {
  let p = probes.get(input);
  if (!p) {
    p = new Promise<Probe>((resolve, reject) => {
      const proc = spawn(bin(), ['-hide_banner', '-i', input], { stdio: ['ignore', 'ignore', 'pipe'] });
      let err = '';
      proc.stderr.on('data', (c) => (err += c));
      const timer = setTimeout(() => proc.kill('SIGKILL'), 60_000);
      proc.on('error', reject);
      // Exits non-zero by design ("At least one output file must be
      // specified") — the banner is the answer.
      proc.on('close', () => {
        clearTimeout(timer);
        const parsed = parseProbe(err);
        if (parsed.video || parsed.audio.length) resolve(parsed);
        else reject(new Error(/Invalid data|could not find codec/i.test(err) ? 'Not a media file ffmpeg can read.' : 'Could not read this file yet — try again in a moment.'));
      });
    });
    probes.set(input, p);
    p.catch(() => probes.delete(input));
  }
  return p;
}

/**
 * A fragmented MP4 from `start` seconds, streamed as ffmpeg writes it.
 * ponytail: input seeking snaps to the keyframe before `start` when video is
 * copied, so the picture can begin a second or two early.
 */
export function remux(
  input: string,
  opts: { start: number; audio: number; encodeVideo: boolean }
): ChildProcessByStdio<null, Readable, null> {
  const args = ['-hide_banner', '-loglevel', 'error'];
  if (opts.start > 0) args.push('-ss', String(opts.start));
  args.push(
    '-i', input,
    '-map', '0:v:0',
    '-map', `0:a:${opts.audio}?`,
    '-sn', '-dn',
    '-c:a', 'aac', '-ac', '2', '-b:a', '192k'
  );
  if (opts.encodeVideo) {
    // 10-bit and 4K sources: 8-bit 4:2:0 at most 1080p is what decodes
    // everywhere and what a CPU can encode in real time.
    args.push(
      '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '23',
      '-pix_fmt', 'yuv420p', '-vf', "scale=-2:'min(1080,ih)'"
    );
  } else {
    args.push('-c:v', 'copy');
  }
  args.push(
    '-avoid_negative_ts', 'make_zero',
    '-movflags', 'frag_keyframe+empty_moov+default_base_moof',
    '-f', 'mp4', 'pipe:1'
  );
  return spawn(bin(), args, { stdio: ['ignore', 'pipe', 'ignore'] });
}

/**
 * One built-in text subtitle track as WebVTT, for `seconds` from `from` only.
 *
 * Subtitle packets are spread through the whole file, so extracting a track
 * in one go meant downloading the entire torrent before a single line showed
 * — it looked stuck. A window reads just that stretch, which the player is
 * downloading anyway: input `-ss` seeks, `-copyts` keeps the cue times the
 * film's own, and output `-to` (an absolute time, because of -copyts) stops
 * the read. `-t` as an input option does not stop it here — ffmpeg read on to
 * the end of the file, the very download this is meant to avoid.
 */
export function extractSubtitle(
  input: string,
  track: number,
  window: { from: number; seconds: number },
  signal: AbortSignal
): Promise<string> {
  return new Promise((resolve, reject) => {
    const proc = spawn(
      bin(),
      [
        '-hide_banner', '-loglevel', 'error',
        '-ss', String(window.from),
        '-i', input,
        '-copyts', '-map', `0:s:${track}`,
        '-to', String(window.from + window.seconds),
        '-f', 'webvtt', 'pipe:1',
      ],
      { stdio: ['ignore', 'pipe', 'pipe'] }
    );
    let out = '';
    let err = '';
    proc.stdout.on('data', (c) => (out += c));
    proc.stderr.on('data', (c) => (err += c));
    signal.addEventListener('abort', () => proc.kill('SIGKILL'), { once: true });
    proc.on('error', reject);
    // An empty window (no dialogue in those minutes) is still a success.
    proc.on('close', (code) =>
      code === 0 ? resolve(out || 'WEBVTT\n') : reject(new Error(err.trim().split('\n').pop() || 'Subtitle extraction failed.'))
    );
  });
}
