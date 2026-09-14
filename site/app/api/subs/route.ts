import { NextRequest, NextResponse } from 'next/server';
import { strFromU8, unzipSync } from 'fflate';
import { FILES, pickEntry, searchSubtitles } from '../../../lib/subdl';

/**
 * SubDL, with the key kept on the server.
 *
 * Set SUBDL_API_KEY in site/.env.local (free key from subdl.com) and restart
 * the server.
 *
 * GET ?q=&imdb=&kind=&season=&episode=&lang=  search → Sub[]
 * GET ?url=https://dl.subdl.com/…              download → the .srt/.vtt text
 */
export async function GET(request: NextRequest) {
  const p = request.nextUrl.searchParams;
  const key = process.env.SUBDL_API_KEY;
  if (!key) {
    return NextResponse.json(
      { error: 'Online subtitles are off — set SUBDL_API_KEY on the server.' },
      { status: 503 }
    );
  }
  const int = (name: string) => (Number(p.get(name)) > 0 ? Number(p.get(name)) : undefined);

  const url = p.get('url');
  if (url) return download(url, key, int('season'), int('episode'));

  const q = p.get('q')?.trim() || undefined;
  const imdb = p.get('imdb') || undefined;
  if (!q && !imdb) return NextResponse.json({ error: 'Nothing to search for.' }, { status: 400 });
  const kind = p.get('kind');

  try {
    const subs = await searchSubtitles(key, {
      query: q,
      imdb: imdb && /^tt\d+$/.test(imdb) ? imdb : undefined,
      kind: kind === 'movie' || kind === 'tv' ? kind : undefined,
      language: p.get('lang') || undefined,
      season: int('season'),
      episode: int('episode'),
    });
    return NextResponse.json(subs);
  } catch (e) {
    return NextResponse.json({ error: String((e as Error)?.message ?? e) }, { status: 502 });
  }
}

const TEXT_SUB = /\.(srt|vtt)$/i;

async function download(url: string, key: string, season?: number, episode?: number) {
  // Only SubDL's own file host — this must not become an open proxy.
  if (!url.startsWith(`${FILES}/`)) {
    return NextResponse.json({ error: 'Not a SubDL download.' }, { status: 400 });
  }
  try {
    const target = new URL(url);
    target.searchParams.set('api_key', key);
    const res = await fetch(target, { signal: AbortSignal.timeout(15000) });
    if (!res.ok) throw new Error(`SubDL download returned ${res.status}`);
    const bytes = new Uint8Array(await res.arrayBuffer());
    const files = unzipSync(bytes, { filter: (f) => TEXT_SUB.test(f.name) });
    const name = pickEntry(
      Object.entries(files).map(([n, d]) => ({ name: n, size: d.length })),
      season,
      episode
    );
    if (!name) {
      return NextResponse.json({ error: 'That download has no SRT inside.' }, { status: 422 });
    }
    return new NextResponse(strFromU8(files[name]), {
      headers: {
        'Content-Type': 'text/plain; charset=utf-8',
        'X-Subtitle-Name': encodeURIComponent(name),
      },
    });
  } catch (e) {
    return NextResponse.json({ error: String((e as Error)?.message ?? e) }, { status: 502 });
  }
}
