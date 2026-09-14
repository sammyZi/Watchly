/**
 * Site password — server only.
 *
 * One password in SITE_PASSWORD (site/.env.local). Signing in sets an
 * HttpOnly cookie holding an HMAC of the password: nothing to store, nothing
 * to look up, and changing the password signs every device out. proxy.ts
 * checks it on every page and API request.
 */
import { createHash, createHmac, timingSafeEqual } from 'node:crypto';

export const AUTH_COOKIE = 'watchly_auth';
export const AUTH_MAX_AGE = 60 * 60 * 24 * 30;

/** Null when no password is configured — the site is then open, as before. */
export const sitePassword = () => process.env.SITE_PASSWORD || null;

export function sessionToken(password: string): string {
  return createHmac('sha256', password).update('watchly-session-v1').digest('hex');
}

const same = (a: Buffer, b: Buffer) => a.length === b.length && timingSafeEqual(a, b);

/** Constant-time: both sides hashed first so lengths always match. */
export function passwordMatches(input: string): boolean {
  const pw = sitePassword();
  if (!pw) return true;
  const digest = (s: string) => createHash('sha256').update(s).digest();
  return same(digest(input), digest(pw));
}

export function tokenValid(value: string | undefined): boolean {
  const pw = sitePassword();
  if (!pw) return true;
  if (!value) return false;
  return same(Buffer.from(value), Buffer.from(sessionToken(pw)));
}

/**
 * ffmpeg reads the torrent through this site's own API, so it has to carry
 * the cookie too. ponytail: the token is visible in the local process list;
 * fine for a single-machine server.
 */
export function ffmpegAuthArgs(): string[] {
  const pw = sitePassword();
  return pw ? ['-headers', `Cookie: ${AUTH_COOKIE}=${sessionToken(pw)}\r\n`] : [];
}

/**
 * Failed sign-ins: 5 per address per 15 minutes, 30 across the whole site
 * (key '*'), then locked until the window passes.
 */
const failures = new Map<string, { count: number; first: number }>();
const WINDOW_MS = 15 * 60_000;
const MAX_FAILURES = 5;
const MAX_FAILURES_SITE = 30;

export function lockedOut(who: string): boolean {
  const f = failures.get(who);
  if (!f) return false;
  if (Date.now() - f.first > WINDOW_MS) {
    failures.delete(who);
    return false;
  }
  return f.count >= (who === '*' ? MAX_FAILURES_SITE : MAX_FAILURES);
}

export function recordFailure(who: string) {
  const f = failures.get(who);
  if (!f || Date.now() - f.first > WINDOW_MS) failures.set(who, { count: 1, first: Date.now() });
  else f.count++;
}

export const clearFailures = (who: string) => failures.delete(who);
