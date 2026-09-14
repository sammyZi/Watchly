import { NextRequest, NextResponse } from 'next/server';
import {
  AUTH_COOKIE,
  AUTH_MAX_AGE,
  clearFailures,
  lockedOut,
  passwordMatches,
  recordFailure,
  sessionToken,
  sitePassword,
} from '../../../lib/auth';

const who = (request: NextRequest) =>
  request.headers.get('x-forwarded-for')?.split(',')[0].trim() || request.headers.get('x-real-ip') || 'local';

/** POST { password } — signs this browser in. */
export async function POST(request: NextRequest) {
  const pw = sitePassword();
  if (!pw) return NextResponse.json({ ok: true });
  const address = who(request);
  // Per address, plus a site-wide cap: the forwarded-for header is whatever
  // the client says, so the per-address count alone could be dodged.
  if (lockedOut(address) || lockedOut('*')) {
    return NextResponse.json({ error: 'Too many wrong attempts. Try again in 15 minutes.' }, { status: 429 });
  }

  const body = await request.json().catch(() => null);
  const password = typeof body?.password === 'string' ? body.password : '';
  if (!passwordMatches(password)) {
    recordFailure(address);
    recordFailure('*');
    // Slows guessing even before the lockout.
    await new Promise((r) => setTimeout(r, 800));
    return NextResponse.json({ error: 'Wrong password.' }, { status: 401 });
  }

  clearFailures(address);
  const res = NextResponse.json({ ok: true });
  res.cookies.set(AUTH_COOKIE, sessionToken(pw), {
    httpOnly: true,
    sameSite: 'lax',
    secure: request.nextUrl.protocol === 'https:',
    path: '/',
    maxAge: AUTH_MAX_AGE,
  });
  return res;
}

/** DELETE — signs this browser out. */
export async function DELETE() {
  const res = NextResponse.json({ ok: true });
  res.cookies.set(AUTH_COOKIE, '', { httpOnly: true, sameSite: 'lax', path: '/', maxAge: 0 });
  return res;
}
