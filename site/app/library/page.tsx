'use client';
/** Library: favourites, watch later, and what you were in the middle of. */
import { Suspense, useEffect, useState } from 'react';
import Link from 'next/link';
import { loadSaved, type Saved } from '../../lib/saved';
import { loadHistory } from '../../lib/history';
import { asTitle } from '../../lib/saved';
import { WATCHED } from '../../lib/watched';
import { Poster, titleHref } from '../../components/Blocks';

type Tab = 'favorites' | 'later' | 'continue';

function LibraryInner() {
  const [tab, setTab] = useState<Tab>('favorites');
  const [saved, setSaved] = useState<Saved[]>([]);
  const [continueWatching, setContinueWatching] = useState<ReturnType<typeof asTitle>[]>([]);

  useEffect(() => {
    loadSaved().then(setSaved);
    (async () => {
      const items = await loadHistory();
      const rows: ReturnType<typeof asTitle>[] = [];
      for (const item of items) {
        if (!item.imdb || !item.kind) continue;
        if (rows.some((r) => r.imdb === item.imdb)) continue;
        // The file last played, not whichever position happens to be first.
        const i = item.lastFileIndex;
        const pos = i != null ? item.positions?.[i] : undefined;
        const dur = i != null ? item.durations?.[i] : undefined;
        if (!pos || (item.kind === 'movie' && (!dur || pos / dur >= WATCHED / 100))) continue;
        rows.push(
          asTitle({
            imdb: item.imdb,
            kind: item.kind,
            title: item.titleName ?? item.title,
            poster: item.poster ?? null,
            year: null,
            rating: null,
            list: 'later',
            at: item.at,
          })
        );
      }
      setContinueWatching(rows);
    })();
  }, []);

  const lists: Record<Tab, Saved[]> = {
    favorites: saved.filter((s) => s.list === 'favorite'),
    later: saved.filter((s) => s.list === 'later'),
    continue: [],
  };

  const content =
    tab === 'continue' ? continueWatching : lists[tab].map((s) => asTitle(s));

  return (
    <main className="page">
      <div className="page-head">
        <h1 className="page-title">Library</h1>
      </div>
      <div className="segment" style={{ display: 'flex', width: '100%', marginBottom: 16 }}>
        {(
          [
            ['favorites', 'Favourites'],
            ['later', 'Watch later'],
            ['continue', 'Continue'],
          ] as const
        ).map(([id, label]) => (
          <button key={id} className={tab === id ? 'active' : ''} onClick={() => setTab(id)} style={{ flex: 1 }}>
            {label}
          </button>
        ))}
      </div>

      {content.length === 0 ? (
        <p className="center">
          {tab === 'continue'
            ? 'Nothing in progress — start something from Home.'
            : 'Nothing here yet. Tap ♥ or 🔖 on a title page.'}
        </p>
      ) : (
        <div className="poster-grid">
          {content.map((t) => (
            <Poster key={t.imdb} title={t} href={tab === 'continue' ? titleHref(t) : undefined} />
          ))}
        </div>
      )}

      {tab === 'continue' && content.length > 0 && (
        <Link href="/downloads" className="shelf-more" style={{ display: 'block', marginTop: 16 }}>
          Torrent-level history and saved files →
        </Link>
      )}
    </main>
  );
}

export default function LibraryScreen() {
  return (
    <Suspense fallback={<main className="page"><div className="spinner" /></main>}>
      <LibraryInner />
    </Suspense>
  );
}