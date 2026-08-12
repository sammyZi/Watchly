'use client';
/**
 * App chrome: theme bootstrap (from the stored setting, applied before paint),
 * offline strip, and the top nav.
 */
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { loadSettings, saveSettings, type Settings } from '../lib/settings';
import { loadSaved, type Saved } from '../lib/saved';
import { useOnline } from '../lib/net';

function apply(mode: Settings['themeMode']) {
  const scheme =
    mode === 'system'
      ? window.matchMedia('(prefers-color-scheme: light)').matches
        ? 'light'
        : 'dark'
      : mode;
  document.documentElement.dataset.theme = scheme;
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(false);

  useEffect(() => {
    loadSettings().then((s) => {
      apply(s.themeMode);
      setReady(true);
    });
  }, []);

  if (!ready) return <div style={{ height: '100vh' }} />;
  return <>{children}</>;
}

export function OfflineStrip() {
  const online = useOnline();
  if (online) return null;
  return <div className="offline">Offline — retrying</div>;
}

const TABS = [
  { href: '/', label: 'Home' },
  { href: '/search', label: 'Search' },
  { href: '/library', label: 'Library' },
  { href: '/downloads', label: 'Files' },
  { href: '/settings', label: 'Settings' },
];

/**
 * Top bar, not a floating pill at the bottom.
 *
 * A thumb-reach tab bar is a phone idiom; on a laptop it is a long way from
 * where the eye and the pointer already are, and it covers the last row of
 * whatever page you are reading. Sticky at the top is where a browser puts
 * navigation, so that is where this goes.
 */
export function Nav() {
  const pathname = usePathname();
  if (pathname.startsWith('/watch')) return null;
  return (
    <header className="topnav">
      <div className="topnav-inner">
        <Link href="/" className="brand">
          <span className="brand-mark">W</span>
          Watchly
        </Link>
        <nav className="topnav-links">
          {TABS.map((t) => (
            <Link
              key={t.href}
              href={t.href}
              className={pathname === t.href ? 'active' : ''}
            >
              {t.label}
            </Link>
          ))}
        </nav>
      </div>
    </header>
  );
}

/** Favourites/watch-later flip, straight from the saved store. */
export function useSavedList(): [Saved[], (next: Saved[]) => void] {
  const [saved, setSaved] = useState<Saved[]>([]);
  useEffect(() => {
    loadSaved().then(setSaved);
  }, []);
  return [saved, setSaved];
}

/** Theme toggle shared by Settings and any page that needs it. */
export function useThemeChanger() {
  const [mode, setMode] = useState<Settings['themeMode']>('system');
  useEffect(() => {
    loadSettings().then((s) => setMode(s.themeMode));
  }, []);
  const change = useCallback((next: Settings['themeMode']) => {
    setMode(next);
    apply(next);
    saveSettings({
      ...(JSON.parse(localStorage.getItem('settings') ?? '{}') as Partial<Settings>),
      themeMode: next,
    } as Settings);
  }, []);
  return { mode, change };
}