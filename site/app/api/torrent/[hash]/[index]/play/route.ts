import { NextRequest } from 'next/server';
import { find, reading } from '../../../../../../lib/engine';
import { inputUrl, remux } from '../../../../../../lib/media';

/**
 * The file converted for the browser, as it is produced.
 *
 * ?t=SECONDS    start here (seeking restarts the conversion)
 * ?audio=N      audio track, as numbered by /probe
 * ?video=encode re-encode video to H.264 (codecs a browser cannot decode)
 *
 * No range support — it is a live stream — so the player owns the clock.
 */
export async function GET(request: NextRequest, ctx: RouteContext<'/api/torrent/[hash]/[index]/play'>) {
  const { hash, index } = await ctx.params;
  if (!find(hash)?.files?.[Number(index)]) return new Response('not found', { status: 404 });
  const p = request.nextUrl.searchParams;

  const proc = remux(inputUrl(request.nextUrl, hash, index), {
    start: Math.max(0, Number(p.get('t')) || 0),
    audio: Math.max(0, Math.floor(Number(p.get('audio')) || 0)),
    encodeVideo: p.get('video') === 'encode',
  });
  // Holds the torrent like any reader; the process dies with the request.
  const finished = reading(hash);
  const stop = () => {
    finished();
    proc.kill('SIGKILL');
  };
  request.signal.addEventListener('abort', stop, { once: true });

  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      proc.stdout.on('data', (chunk: Buffer) => {
        controller.enqueue(new Uint8Array(chunk));
        // Backpressure: a paused player stops reading, so ffmpeg must stop
        // too, or the whole film piles up in server memory.
        if ((controller.desiredSize ?? 1) <= 0) proc.stdout.pause();
      });
      proc.stdout.on('end', () => {
        finished();
        controller.close();
      });
      proc.on('error', (e) => {
        finished();
        controller.error(e);
      });
    },
    pull() {
      proc.stdout.resume();
    },
    cancel: stop,
  });

  return new Response(body, {
    headers: { 'Content-Type': 'video/mp4', 'Cache-Control': 'no-store' },
  });
}
