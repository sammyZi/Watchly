import { NextRequest, NextResponse } from 'next/server';
import { find, reading } from '../../../../../../lib/engine';
import { extractSubtitle, inputUrl } from '../../../../../../lib/media';

/** ?track=N — one built-in text subtitle track (numbered by /probe) as WebVTT. */
export async function GET(request: NextRequest, ctx: RouteContext<'/api/torrent/[hash]/[index]/subtitle'>) {
  const { hash, index } = await ctx.params;
  if (!find(hash)?.files?.[Number(index)]) return NextResponse.json({ error: 'not found' }, { status: 404 });
  const track = Math.max(0, Math.floor(Number(request.nextUrl.searchParams.get('track')) || 0));
  const finished = reading(hash);
  try {
    const vtt = await extractSubtitle(inputUrl(request.nextUrl, hash, index), track, request.signal);
    return new NextResponse(vtt, { headers: { 'Content-Type': 'text/vtt; charset=utf-8' } });
  } catch (e) {
    return NextResponse.json({ error: String((e as Error)?.message ?? e) }, { status: 502 });
  } finally {
    finished();
  }
}
