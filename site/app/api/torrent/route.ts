import { NextRequest, NextResponse } from 'next/server';
import { describe, find, list, open, remove } from '../../../lib/engine';

/**
 * GET ?uri=   add (or reuse) a torrent, answer once the file list is known
 * GET ?hash=  live stats for one torrent, metadata or not
 * GET         every torrent the engine holds
 * DELETE ?hash=  stop it and delete its data
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
    return t ? NextResponse.json(describe(t)) : NextResponse.json({ error: 'not found' }, { status: 404 });
  }
  return NextResponse.json(list().map(describe));
}

export async function DELETE(request: NextRequest) {
  const hash = request.nextUrl.searchParams.get('hash');
  if (hash) await remove(hash);
  return new NextResponse(null, { status: 204 });
}
