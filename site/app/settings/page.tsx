'use client';
/** Settings: theme, home shelves, trackers, cache control. */
import { useEffect, useState } from 'react';
import { GENRES } from '../../lib/meta';
import { loadSettings, saveSettings, type Settings } from '../../lib/settings';
import { clearMetaCache } from '../../lib/meta';
import { clearHistory } from '../../lib/history';
import { clearSearches } from '../../lib/searches';
import { useThemeChanger } from '../../components/Chrome';

export default function SettingsScreen() {
  const { mode, change } = useThemeChanger();
  const [settings, setSettings] = useState<Settings | null>(null);
  const [msg, setMsg] = useState('');

  useEffect(() => {
    loadSettings().then(setSettings);
    // The SubDL key used to live here in the browser; it is server env now.
    localStorage.removeItem('subdlKey');
  }, []);

  if (!settings) return <main className="page"><div className="spinner" /></main>;

  const update = (next: Settings) => {
    setSettings(next);
    saveSettings(next);
  };

  const toggleGenre = (g: string) => {
    const genres = settings.genres.includes(g)
      ? settings.genres.filter((x) => x !== g)
      : [...settings.genres, g];
    update({ ...settings, genres });
  };

  return (
    <main className="page">
      <div className="page-head">
        <h1 className="page-title">Settings</h1>
      </div>

      <h2 className="shelf-title" style={{ margin: '6px 0 4px' }}>Appearance</h2>
      <div className="field">
        <div>
          <div className="field-label">Theme</div>
          <div className="field-sub">System follows your device</div>
        </div>
        <div className="segment">
          {(['system', 'light', 'dark'] as const).map((m) => (
            <button key={m} className={mode === m ? 'active' : ''} onClick={() => change(m)}>
              {m[0].toUpperCase() + m.slice(1)}
            </button>
          ))}
        </div>
      </div>

      <h2 className="shelf-title" style={{ margin: '18px 0 4px' }}>Home</h2>
      <div className="field">
        <div>
          <div className="field-label">Lead shelves with</div>
          <div className="field-sub">Pick genres to lead the home page; otherwise it follows your history</div>
        </div>
      </div>
      <div className="chips" style={{ marginTop: 6 }}>
        {GENRES.map((g) => (
          <button key={g} className={`chip ${settings.genres.includes(g) ? 'active' : ''}`} onClick={() => toggleGenre(g)}>
            {g}
          </button>
        ))}
      </div>

      <h2 className="shelf-title" style={{ margin: '18px 0 4px' }}>Streaming</h2>
      <div className="field">
        <div>
          <div className="field-label">Inject public trackers</div>
          <div className="field-sub">Attach well-known trackers to bare magnets so metadata resolves faster</div>
        </div>
        <button
          className={`switch ${settings.addTrackers ? 'on' : ''}`}
          onClick={() => update({ ...settings, addTrackers: !settings.addTrackers })}
          aria-label="Inject trackers"
        />
      </div>

      <h2 className="shelf-title" style={{ margin: '18px 0 4px' }}>Account</h2>
      <div className="field">
        <div>
          <div className="field-label">Sign out</div>
          <div className="field-sub">This device will need the site password again</div>
        </div>
        <button
          className="btn btn-soft"
          onClick={async () => {
            await fetch('/api/login', { method: 'DELETE' }).catch(() => {});
            window.location.replace('/login');
          }}
        >
          Sign out
        </button>
      </div>

      <h2 className="shelf-title" style={{ margin: '18px 0 4px' }}>Storage</h2>
      <div className="field" style={{ border: 'none' }}>
        <div>
          <div className="field-label">Clear caches</div>
          <div className="field-sub">Metadata cache, history, recent searches</div>
        </div>
      </div>
      <div className="actions">
        <button
          className="btn btn-soft"
          onClick={async () => {
            await clearMetaCache();
            await Promise.all([clearHistory(), clearSearches()]);
            setMsg('Cleared.');
            setTimeout(() => setMsg(''), 3000);
          }}
        >
          Clear all
        </button>
        <button className="btn btn-soft" onClick={() => clearMetaCache().then(() => setMsg('Metadata cache cleared.'))}>
          Metadata only
        </button>
      </div>
      {msg && <p className="good-text" style={{ fontSize: 12, marginTop: 8 }}>{msg}</p>}
    </main>
  );
}