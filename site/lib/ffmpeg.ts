'use client';
/**
 * The "handles every encoding" engine for the web build: ffmpeg compiled to
 * WebAssembly (@ffmpeg/core), running in a shared worker.
 *
 * The browser's `<video>` element can play MP4/MKV/WebM with H.264, HEVC,
 * AV1, VP9, AAC, Opus and friends — but not AC3/E-AC3/DTS audio, and not
 * every container or codec a release can ship. That is the same hole the
 * native app fills with libVLC; here it is filled by transcoding with
 * ffmpeg.wasm:
 *
 *  - "Fast":   remux the video stream as-is and only re-encode the audio to
 *              AAC. Much quicker than a full encode, and it fixes the single
 *              most common playback failure.
 *  - "Full":   decode and re-encode everything (H.264 in an MP4), which plays
 *              anywhere — at transcoding speed, so there is an honest ETA.
 *
 * Input is mounted from a File via WORKERFS (disk-backed, no memory copy),
 * which is why universal mode asks for the torrent to be saved to disk first.
 */
import { FFmpeg } from '@ffmpeg/ffmpeg';
import type { FFFSType } from '@ffmpeg/ffmpeg';

export type Job = 'fast' | 'full';

export type TranscodeHandle = {
  progress: number;
  time: number;
  log: string;
};

let ffmpeg: FFmpeg | null = null;
let loading: Promise<FFmpeg> | null = null;

/** Load the WASM core once; every transcode after reuses the worker. */
export function getFFmpeg(): Promise<FFmpeg> {
  if (ffmpeg) return Promise.resolve(ffmpeg);
  if (!loading) {
    loading = (async () => {
      const f = new FFmpeg();
      await f.load({
        coreURL: '/ffmpeg/ffmpeg-core.js',
        wasmURL: '/ffmpeg/ffmpeg-core.wasm',
        classWorkerURL: '/ffmpeg/worker.js',
      });
      ffmpeg = f;
      return f;
    })().catch((e) => {
      loading = null;
      throw e;
    });
  }
  return loading;
}

export const ffmpegLoaded = () => ffmpeg !== null || loading !== null;

/** Filter check — libass decides whether burning subtitles is possible. */
export async function hasSubtitlesFilter(): Promise<boolean> {
  const f = await getFFmpeg();
  const out = new Promise<string>((done) => {
    const listener = ({ type, message }: { type: string; message: string }) => {
      if (type === 'stderr' && /libass|Subtitles/.test(message)) {
        f.off('log', listener);
        done(message);
      }
    };
    f.on('log', listener);
  });
  await f.exec(['-hide_banner', '-filters']);
  const log = await out;
  return /\bass\b|subtitles/.test(log);
}

/**
 * Transcode a local file into an MP4 the browser can always play.
 *
 * `file` is mounted (WORKERFS — reads are random-access from disk, so a
 * multi-gigabyte file costs no RAM up front). The output is written to the
 * worker's memory image and read back at the end; that is the one real limit,
 * so callers cap input size.
 *
 * Returns a Blob URL of the finished MP4.
 */
export async function transcode(
  file: File,
  name: string,
  job: Job,
  onProgress: (p: TranscodeHandle) => void,
  opts?: { burnSubtitles?: File | null }
): Promise<string> {
  const f = await getFFmpeg();
  const input = `/input/${name}`;
  const output = '/out.mp4';

  await f.mount('WORKERFS' as FFFSType, { files: [file] }, '/input');

  const base = ['-i', input];
  if (job === 'fast') {
    base.push('-c:v', 'copy', '-c:a', 'aac', '-b:a', '192k');
  } else {
    base.push('-c:v', 'libx264', '-preset', 'veryfast', '-crf', '23', '-c:a', 'aac', '-b:a', '192k');
  }
  // Bitmap subtitles cannot be converted; text subtitles can be burned in.
  if (opts?.burnSubtitles) {
    base.push(
      '-vf',
      `subtitles=/input/${opts.burnSubtitles.name}:force_style=FontSize=18,format=yuv420p`
    );
  }
  base.push('-movflags', '+faststart', '-y', output);

  const progress = { progress: 0, time: 0, log: '' };
  const listener = ({ type, message }: { type: string; message: string }) => {
    if (type === 'stderr' && /^frame=|^size=|Error/.test(message) && !/vendor|configuration/i.test(message)) {
      progress.log = message;
    }
  };
  f.on('log', listener);
  f.on('progress', ({ progress: p, time }) => {
    progress.progress = p;
    progress.time = time;
    onProgress({ ...progress });
  });

  try {
    await f.exec(base, 0);
  } finally {
    f.off('log', listener);
  }

  const data = await f.readFile(output);
  await f.deleteFile(output);
  try {
    await f.unmount('/input');
  } catch {
    // Idempotent enough.
  }
  onProgress({ progress: 1, time: progress.time, log: 'Done' });
  const blob = new Blob([data as unknown as Uint8Array<ArrayBuffer>], { type: 'video/mp4' });
  return URL.createObjectURL(blob);
}

/** Rough realtime estimate for the full-encode job, to set expectations. */
export function etaFor(job: Job, seconds: number, bytes: number): string | null {
  if (job === 'fast') {
    // Remux + audio encode: roughly 3-6x realtime on typical hardware.
    const mins = Math.ceil(seconds / 240);
    return mins > 0 ? `~${mins} min` : '<1 min';
  }
  // Full x264, veryfast, ~0.3x realtime single-threaded wasm.
  const mins = Math.ceil(seconds / 18);
  return mins > 0 ? `~${mins} min` : '<1 min';
}