import { NextRequest, NextResponse } from 'next/server';
import { find, touch } from '../../../../../../lib/engine';
import { inputUrl, needsVideoEncode, probe } from '../../../../../../lib/media';

/** Audio tracks, built-in text subtitles, duration and whether the video needs
 *  re-encoding for a browser. */
export async function GET(request: NextRequest, ctx: RouteContext<'/api/torrent/[hash]/[index]/probe'>) {
  const { hash, index } = await ctx.params;
  if (!find(hash)?.files?.[Number(index)]) return NextResponse.json({ error: 'not found' }, { status: 404 });
  touch(hash);
  try {
    const p = await probe(inputUrl(request.nextUrl, hash, index));
    return NextResponse.json({ ...p, needsVideoEncode: needsVideoEncode(p) });
  } catch (e) {
    return NextResponse.json({ error: String((e as Error)?.message ?? e) }, { status: 502 });
  }
}
