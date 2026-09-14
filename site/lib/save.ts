'use client';
/**
 * Save a streamed file to disk with the File System Access API.
 *
 * The torrent is already reachable over the service-worker HTTP server
 * (`streamURL`, which serves range requests), so "download" is one streaming
 * GET into a writable file handle — the browser's disk, not its RAM.
 */
export function supportsSaveAPI(): boolean {
  return typeof window !== 'undefined' && 'showSaveFilePicker' in window;
}

export type SaveHandle = {
  bytes: number;
  total: number;
  percent: number;
  speed: number;
  done: boolean;
};

/**
 * Stream `url` into a user-chosen file. `allowedTypes` maps file extensions
 * to MIME types so the picker suggests the right name.
 */
export async function saveUrlToDisk(
  url: string,
  fileName: string,
  onProgress: (p: SaveHandle) => void
): Promise<{ path: string; size: number }> {
  const res = await fetch(url);
  if (!res.ok || !res.body) throw new Error(`Download returned ${res.status}`);
  const total = Number(res.headers.get('content-length') ?? 0) || 0;

  const handle = await (window as any).showSaveFilePicker({
    suggestedName: fileName,
  });
  const writable = await handle.createWritable();

  const reader = res.body.getReader();
  let bytes = 0;
  let last = performance.now();
  let speed = 0;
  const done = () => {
    const now = performance.now();
    speed = ((bytes - (bytes - 64 * 1024)) / Math.max(1, now - last)) * 1000;
    last = now;
    onProgress({
      bytes,
      total,
      percent: total ? bytes / total : 0,
      speed,
      done: bytes >= total,
    });
  };

  try {
    for (;;) {
      const { done: end, value } = await reader.read();
      if (end) break;
      await writable.write(value);
      bytes += value.length;
      done();
    }
  } catch (e) {
    await writable.abort();
    throw e;
  }
  await writable.close();
  onProgress({ bytes, total, percent: total ? bytes / total : 1, speed, done: true });
  return { path: fileName, size: bytes };
}