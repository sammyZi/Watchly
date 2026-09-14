/**
 * Server-side ffmpeg (ffmpeg-static): what a browser cannot do with a release.
 *
 *  - probe: audio tracks, built-in subtitles, codecs, duration
 *  - aacAudio: the soundtrack as AAC, for audio browsers cannot decode
 *  - subtitle: one built-in text subtitle track as WebVTT
 *
 * Input is the torrent's own range-request URL, so ffmpeg seeks through the
 * engine exactly like the player does.
 */
import { spawn, type ChildProcessByStdio } from 'node:child_process';
import type { Readable } from 'node:stream';
import ffmpegPath from 'ffmpeg-static';
import { ffmpegAuthArgs } from './auth';
import { parseProbe, type Probe } from './probe';

export { needsVideoEncode, type Probe, type Track } from './probe';

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
      const proc = spawn(bin(), ['-hide_banner', ...ffmpegAuthArgs(), '-i', input], {
        stdio: ['ignore', 'ignore', 'pipe'],
      });
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
 * Audio track `track` from `start` seconds as AAC stereo (ADTS), streamed as
 * ffmpeg writes it — for audio a browser cannot decode (AC-3, E-AC-3, DTS),
 * and for any track but the first, which is the only one a browser plays.
 * Only audio is encoded, so it is cheap; the video keeps playing straight
 * from the torrent with its own seeking.
 */
export function aacAudio(input: string, start: number, track: number): ChildProcessByStdio<null, Readable, null> {
  const args = ['-hide_banner', '-loglevel', 'error', ...ffmpegAuthArgs()];
  if (start > 0) args.push('-ss', String(start));
  args.push('-i', input, '-map', `0:a:${track}`, '-vn', '-sn', '-dn', '-c:a', 'aac', '-ac', '2', '-b:a', '160k', '-f', 'adts', 'pipe:1');
  // stderr (errors only) to the server log, or a failure is just silence.
  return spawn(bin(), args, { stdio: ['ignore', 'pipe', 'inherit'] });
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
        ...ffmpegAuthArgs(),
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
