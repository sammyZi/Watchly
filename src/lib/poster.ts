import * as VideoThumbnails from 'expo-video-thumbnails';

/**
 * Bound on how long a stuck decode can hold the connection open, not a
 * responsiveness limit — nothing waits on this any more. A retriever parked on
 * data the swarm has not sent yet would otherwise sit there indefinitely.
 */
const BUDGET_MS = 10_000;

/**
 * Grab a frame from the running stream.
 *
 * Only possible while the stream is live: the file is served by the local HTTP
 * server for as long as the torrent is running, and the pieces are wiped when
 * it stops. So it happens during playback rather than on the way out — see
 * grabPoster in PlayerScreen for why.
 *
 * The result lives in the cache directory. If Android reclaims it the poster
 * just disappears and the tile falls back to its colour, which is why nothing
 * depends on it existing.
 */
export async function capturePoster(
  url: string | null,
  seconds: number
): Promise<string | null> {
  if (!url) {
    console.log('[poster] skipped: no stream url yet');
    return null;
  }
  if (seconds <= 0) {
    console.log('[poster] skipped: nothing watched yet');
    return null;
  }

  const started = Date.now();
  console.log(`[poster] capturing at ${Math.round(seconds)}s from ${url}`);

  try {
    const shot = await Promise.race([
      VideoThumbnails.getThumbnailAsync(url, {
        time: Math.floor(seconds * 1000),
        quality: 0.5,
      }),
      new Promise<never>((_, reject) =>
        setTimeout(() => reject(new Error(`gave up after ${BUDGET_MS}ms`)), BUDGET_MS)
      ),
    ]);
    console.log(
      `[poster] got ${shot.width}x${shot.height} in ${Date.now() - started}ms -> ${shot.uri}`
    );
    return shot.uri;
  } catch (e: any) {
    console.warn(
      `[poster] failed after ${Date.now() - started}ms:`,
      e?.message ?? String(e)
    );
    return null;
  }
}
