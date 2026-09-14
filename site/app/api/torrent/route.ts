import { NextRequest, NextResponse } from 'next/server';
import { describe, find, list, open, release, remove, touch } from '../../../lib/engine';

/**
 * GET ?uri=   add (or reuse) a torrent, answer once the file list is known
 * GET ?hash=  live stats for one torrent — the player polls this, which is
 *             also what keeps its data from being swept as idle
 * GET         every torrent the engine holds
 * DELETE ?hash=            stop it and delete its data now
 * DELETE ?hash=&release=1  the player left: delete after a short grace
 */
export async function GET(request: NextRequest) {
  const q = request.nextUrl.searchParams;
  const uri = q.get('uri');
  const hash = q.get('hash');
  if (uri) {
    try {
      return NextResponse.json(describe(await open(uri)));
    } catch (e) {
      return NextResponse.json({ error: String((e as Error)?.message ?? e) }, { status: 504 });
    }
  }
  if (hash) {
    const t = find(hash);
    if (!t) return NextResponse.json({ error: 'not found' }, { status: 404 });
    touch(hash);
    return NextResponse.json(describe(t));
  }
  return NextResponse.json(list().map(describe));
}

export async function DELETE(request: NextRequest) {
  const p = request.nextUrl.searchParams;
  const hash = p.get('hash');
  if (hash) {
    if (p.get('release')) release(hash);
    else await remove(hash);
  }
  return new NextResponse(null, { status: 204 });
}
