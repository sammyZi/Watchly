import { NextResponse, type NextRequest } from 'next/server';
import { AUTH_COOKIE, sitePassword, tokenValid } from './lib/auth';

/**
 * The site password gate, in front of every page and API route. Signed out:
 * pages go to /login (and come back after), API calls get 401.
 */
export function proxy(request: NextRequest) {
  if (!sitePassword()) return NextResponse.next();
  const { pathname, search } = request.nextUrl;
  if (pathname === '/login' || pathname === '/api/login') return NextResponse.next();
  if (tokenValid(request.cookies.get(AUTH_COOKIE)?.value)) return NextResponse.next();

  if (pathname.startsWith('/api/')) {
    return NextResponse.json({ error: 'Sign in required.' }, { status: 401 });
  }
  const login = new URL('/login', request.url);
  if (pathname !== '/') login.searchParams.set('next', pathname + search);
  return NextResponse.redirect(login);
}

export const config = {
  // Everything except the build's own static files — the login page needs
  // its scripts and fonts before anyone has signed in.
  matcher: ['/((?!_next/static|_next/image|favicon.ico).*)'],
};
