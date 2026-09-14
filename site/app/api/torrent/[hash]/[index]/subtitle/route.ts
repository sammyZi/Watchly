import { NextRequest, NextResponse } from 'next/server';
import { find, reading } from '../../../../../../lib/engine';
import { extractSubtitle, inputUrl } from '../../../../../../lib/media';

/**
 * ?track=N&from=S&seconds=D — one built-in text subtitle track (numbered by
 * /probe) as WebVTT, for that stretch of the film only. The player asks for
 * the window it is about to play; see extractSubtitle for why not the lot.
 */
export async function GET(request: NextRequest, ctx: RouteContext<'/api/torrent/[hash]/[index]/subtitle'>) {
  const { hash, index } = await ctx.params;
  if (!find(hash)?.files?.[Number(index)]) return NextResponse.json({ error: 'not found' }, { status: 404 });
  const p = request.nextUrl.searchParams;
  const num = (name: string, fallback: number) =>
    Number.isFinite(Number(p.get(name))) && p.get(name) !== null ? Number(p.get(name)) : fallback;
  const track = Math.max(0, Math.floor(num('track', 0)));
  const from = Math.max(0, num('from', 0));
  const seconds = Math.min(1800, Math.max(10, num('seconds', 300)));

  const finished = reading(hash);
  try {
    const vtt = await extractSubtitle(inputUrl(request.nextUrl, hash, index), track, { from, seconds }, request.signal);
    return new NextResponse(vtt, { headers: { 'Content-Type': 'text/vtt; charset=utf-8' } });
  } catch (e) {
    return NextResponse.json({ error: String((e as Error)?.message ?? e) }, { status: 502 });
  } finally {
    finished();
  }
}
