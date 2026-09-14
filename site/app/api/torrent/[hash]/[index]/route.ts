import { NextRequest } from 'next/server';
import { find } from '../../../../../lib/engine';

/** Container types a browser's media stack keys off. Chrome's WebM demuxer is
 *  its Matroska demuxer, so .mkv goes out as webm. */
const TYPES: [RegExp, string][] = [
  [/\.(mp4|m4v|mov)$/i, 'video/mp4'],
  [/\.(webm|mkv)$/i, 'video/webm'],
  [/\.ogv$/i, 'video/ogg'],
  [/\.(srt|vtt|ass|ssa|sub)$/i, 'text/plain; charset=utf-8'],
];

/**
 * One file of a torrent over HTTP with range support. The engine prioritises
 * the pieces a read asks for, so seeking in the player fetches from the swarm
 * at the new position instead of waiting for the whole file.
 */
export async function GET(request: NextRequest, ctx: RouteContext<'/api/torrent/[hash]/[index]'>) {
  const { hash, index } = await ctx.params;
  const file = find(hash)?.files?.[Number(index)];
  if (!file) return new Response('not found', { status: 404 });

  const size: number = file.length;
  const type = TYPES.find(([re]) => re.test(file.name))?.[1] ?? 'application/octet-stream';
  const headers: Record<string, string> = {
    'Accept-Ranges': 'bytes',
    'Content-Type': type,
    'Content-Disposition': `inline; filename*=UTF-8''${encodeURIComponent(file.name)}`,
  };

  const range = /bytes=(\d*)-(\d*)/.exec(request.headers.get('range') ?? '');
  let start = 0;
  let end = size - 1;
  if (range) {
    if (range[1]) {
      start = Number(range[1]);
      if (range[2]) end = Math.min(Number(range[2]), size - 1);
    } else if (range[2]) {
      start = Math.max(0, size - Number(range[2]));
    }
    if (start >= size || start > end) {
      return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${size}` } });
    }
    headers['Content-Range'] = `bytes ${start}-${end}/${size}`;
  }
  headers['Content-Length'] = String(end - start + 1);

  if (size === 0) return new Response(null, { status: 200, headers });
  const iterator = file[Symbol.asyncIterator]({ start, end });
  // Seeking aborts the old request; returning the iterator deselects its pieces.
  const body = new ReadableStream<Uint8Array>({
    async pull(controller) {
      try {
        const { value, done } = await iterator.next();
        if (done) controller.close();
        else controller.enqueue(new Uint8Array(value));
      } catch (e) {
        controller.error(e);
      }
    },
    cancel() {
      void iterator.return?.();
    },
  });
  return new Response(body, { status: range ? 206 : 200, headers });
}
