'use client';
/** Sign in with the site password (see lib/auth.ts). */
import { Suspense, useState } from 'react';
import { useSearchParams } from 'next/navigation';

function LoginInner() {
  const params = useSearchParams();
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Only back to a page on this site — never an open redirect.
  const raw = params.get('next') ?? '/';
  const next = raw.startsWith('/') && !raw.startsWith('//') ? raw : '/';

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!password || busy) return;
    setBusy(true);
    setError(null);
    try {
      const res = await fetch('/api/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password }),
      });
      if (res.ok) {
        // A full load, so every server request from here carries the cookie.
        window.location.replace(next);
        return;
      }
      setError((await res.json().catch(() => null))?.error ?? 'Could not sign in.');
      setPassword('');
    } catch {
      setError('Could not reach the server.');
    }
    setBusy(false);
  };

  return (
    <main className="login">
      <form className="login-card" onSubmit={submit}>
        <span className="brand-mark login-mark">W</span>
        <h1 className="login-title">Watchly</h1>
        <p className="login-sub">Enter the password to continue.</p>
        <input
          className="input"
          type="password"
          name="password"
          autoComplete="current-password"
          autoFocus
          placeholder="Password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          aria-invalid={!!error}
          aria-describedby={error ? 'login-error' : undefined}
        />
        {error && (
          <p id="login-error" className="login-error" role="alert">
            {error}
          </p>
        )}
        <button className="btn btn-primary login-btn" type="submit" disabled={busy || !password}>
          {busy ? 'Checking…' : 'Sign in'}
        </button>
      </form>
    </main>
  );
}

export default function LoginPage() {
  return (
    <Suspense fallback={<main className="login" />}>
      <LoginInner />
    </Suspense>
  );
}
