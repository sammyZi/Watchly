import { NextRequest, NextResponse } from 'next/server';

/**
 * apibay.org does not answer browser CORS preflights, so search requests are
 * rerouted through here (see lib/net.ts `rewrite`). Pass-through, no caching,
 * plus a short upstream timeout so a blocked ISP costs milliseconds.
 */
export async function GET(request: NextRequest) {
  const q = request.nextUrl.searchParams;
  const params = new URLSearchParams();
  const qv = q.get('q');
  if (qv) params.set('q', qv);
  const cat = q.get('cat');
  if (cat) params.set('cat', cat);

  try {
    const res = await fetch(`https://apibay.org/q.php?${params}`, {
      signal: AbortSignal.timeout(12000),
      headers: { 'User-Agent': 'Watchly/1.0' },
    });
    return new NextResponse(res.body, {
      status: res.status,
      headers: { 'content-type': 'application/json' },
    });
  } catch {
    return NextResponse.json({ error: 'apibay unreachable' }, { status: 502 });
  }
}