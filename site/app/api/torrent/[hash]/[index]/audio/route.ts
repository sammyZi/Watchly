import { NextRequest } from 'next/server';
import { find, reading } from '../../../../../../lib/engine';
import { aacAudio, inputUrl } from '../../../../../../lib/media';

/**
 * A soundtrack as AAC, as it is encoded.
 *
 * ?t=SECONDS  start here
 * ?audio=N    audio track, as numbered by /probe
 *
 * No range support — a live stream — so the player restarts it to seek.
 */
export async function GET(request: NextRequest, ctx: RouteContext<'/api/torrent/[hash]/[index]/audio'>) {
  const { hash, index } = await ctx.params;
  if (!find(hash)?.files?.[Number(index)]) return new Response('not found', { status: 404 });
  const p = request.nextUrl.searchParams;

  const proc = aacAudio(
    inputUrl(request.nextUrl, hash, index),
    Math.max(0, Number(p.get('t')) || 0),
    Math.max(0, Math.floor(Number(p.get('audio')) || 0))
  );
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
        // Backpressure: a paused player stops reading, so ffmpeg stops too.
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

  return new Response(body, { headers: { 'Content-Type': 'audio/aac', 'Cache-Control': 'no-store' } });
}
