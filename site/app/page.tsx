'use client';
/**
 * Home: hero + popularity shelves, personalised by what you actually watch.
 */
import { useEffect, useMemo, useState } from 'react';
import { loadCatalog, peekCatalog, watchedGenres, type Title } from '../lib/meta';
import { loadHistory } from '../lib/history';
import { loadSettings } from '../lib/settings';
import { loadSaved } from '../lib/saved';
import { Hero, Poster, Shelf, titleHref } from '../components/Blocks';

type ShelfSpec = { genre: string; kind: 'movie' | 'tv' };

export default function HomeScreen() {
  const [shelves, setShelves] = useState<Title[][] | null>(null);
  const [personal, setPersonal] = useState<ShelfSpec[]>([]);
  /** Which of the top recommendations the hero is showing. */
  const [heroAt, setHeroAt] = useState(0);

  const spec = useMemo<ShelfSpec[]>(
    () => [
      ...personal,
      { genre: '', kind: 'movie' },
      { genre: '', kind: 'tv' },
      { genre: 'Anime', kind: 'tv' },
    ],
    [personal]
  );

  useEffect(() => {
    let alive = true;
    (async () => {
      const [history, settings, saved] = await Promise.all([
        loadHistory(),
        loadSettings(),
        loadSaved(),
      ]);
      const seen = history
        .filter((h) => h.imdb && h.kind)
        .map((h) => ({ imdb: h.imdb!, kind: h.kind! }));
      saved.forEach((s) => seen.push({ imdb: s.imdb, kind: s.kind }));
      const fromHistory = await watchedGenres(seen, 3);
      const fromSettings = settings.genres
        .slice(0, 3)
        .map((g) => ({ genre: g, kind: (g === 'Anime' ? 'tv' : 'movie') as 'movie' | 'tv' }));
      const merged = [...fromSettings, ...fromHistory]
        .filter((s, i, all) => all.findIndex((x) => x.genre === s.genre) === i)
        .slice(0, 4);
      if (!alive) return;
      setPersonal(merged);
    })();
    return () => {
      alive = false;
    };
  }, []);

  useEffect(() => {
    let alive = true;
    const firstRender = () => {
      const rows = spec.map((s) => peekCatalog(s.kind, s.genre));
      if (rows.some(Boolean)) {
        const got = rows.map((r, i) => r ?? []);
        if (alive) setShelves(got);
      }
    };
    firstRender();
    Promise.all(
      spec.map((s) => loadCatalog(s.kind, s.genre || undefined).catch(() => [] as Title[]))
    ).then((rows) => {
      if (alive) setShelves(rows);
    });
    return () => {
      alive = false;
    };
    // spec changes identity per render through useMemo deps [personal] — fine.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [JSON.stringify(spec)]);

  if (!shelves) {
    return (
      <main className="page">
        <div className="spinner" />
      </main>
    );
  }

  // The top of the first shelf that actually loaded, paged rather than pinned
  // to one title — five recommendations is more use than one.
  const top = (shelves.find((s) => s.length) ?? []).slice(0, 5);
  const hero = top[heroAt] ?? top[0];

  return (
    <main className="page">
      {hero && (
        <Hero
          title={hero}
          actionHref={titleHref(hero)}
          actionLabel={`Browse ${hero.kind === 'movie' ? 'Film' : 'Series'}`}
          page={{ count: top.length, index: Math.min(heroAt, top.length - 1), onPick: setHeroAt }}
        />
      )}
      {spec.map((s, i) =>
        shelves[i]?.length ? (
          <Shelf
            key={`${s.kind}:${s.genre}`}
            title={s.genre || (s.kind === 'movie' ? 'Popular films' : 'Popular series')}
            href={`/catalog/${encodeURIComponent(s.genre || (s.kind === 'movie' ? 'films' : 'series'))}?kind=${s.kind}`}
          >
            {shelves[i].slice(0, 14).map((t) => (
              <Poster key={t.imdb} title={t} width={168} />
            ))}
          </Shelf>
        ) : null
      )}
    </main>
  );
}