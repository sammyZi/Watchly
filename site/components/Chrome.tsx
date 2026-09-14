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

/** Feather glyph paths — the same set the Android TabBar uses. */
const TABS = [
  { href: '/', label: 'Home', icon: 'M3 9.5 12 3l9 6.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1Z' },
  { href: '/search', label: 'Search', icon: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14ZM20 20l-3.5-3.5' },
  { href: '/library', label: 'Library', icon: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 7v5l3 2' },
  { href: '/downloads', label: 'Files', icon: 'M12 3.5v11M7.5 10.5 12 15l4.5-4.5M4.5 19.5h15' },
  {
    href: '/settings',
    label: 'Settings',
    icon: 'M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6',
  },
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
  if (pathname.startsWith('/watch') || pathname === '/login') return null;
  const isActive = (href: string) => (href === '/' ? pathname === '/' : pathname.startsWith(href));
  return (
    <>
      {/* Phones: the floating capsule from the Android app, in thumb reach.
          CSS shows one bar or the other by width. */}
      <nav className="tabbar" aria-label="Main">
        {TABS.map((t) => (
          <Link key={t.href} href={t.href} className={isActive(t.href) ? 'active' : ''}>
            <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden>
              <path
                d={t.icon}
                fill="none"
                stroke="currentColor"
                strokeWidth="1.9"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
            {t.label}
          </Link>
        ))}
      </nav>
      <DesktopNav isActive={isActive} />
    </>
  );
}

function DesktopNav({ isActive }: { isActive: (href: string) => boolean }) {
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
              className={isActive(t.href) ? 'active' : ''}
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