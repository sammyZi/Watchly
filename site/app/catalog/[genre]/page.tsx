'use client';
/** A whole shelf as a grid. */
import { Suspense, useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { useParams, useSearchParams } from 'next/navigation';
import { ANIME_GENRE, loadCatalog, type Title } from '../../../lib/meta';
import { useOnline } from '../../../lib/net';
import { BackIcon, Poster } from '../../../components/Blocks';

function CatalogInner() {
  const { genre } = useParams<{ genre: string }>();
  const params = useSearchParams();
  const decoded = decodeURIComponent(genre ?? '');
  const isFilmList = decoded === 'films' || decoded === 'series';
  const kind = decoded === ANIME_GENRE
    ? 'tv'
    : isFilmList
      ? decoded === 'series' ? 'tv' : 'movie'
      : ((params.get('kind') as 'movie' | 'tv') || 'movie');
  const genreName = isFilmList ? '' : decoded === ANIME_GENRE ? ANIME_GENRE : decoded;
  const online = useOnline();

  const [titles, setTitles] = useState<Title[] | null>(null);
  const loaded = useRef<string | null>(null);

  useEffect(() => {
    const key = `${kind}:${genreName}`;
    if (loaded.current === key) return;
    loaded.current = key;
    setTitles(null);
    loadCatalog(kind, genreName || undefined).then(setTitles);
  }, [kind, genreName, online]);

  const name =
    genreName === ANIME_GENRE
      ? ANIME_GENRE
      : isFilmList
        ? decoded === 'series'
          ? 'Popular series'
          : 'Popular films'
        : genreName || (kind === 'movie' ? 'Films' : 'Series');

  return (
    <main className="page">
      <div className="page-head">
        <Link href="/" className="back-btn" aria-label="Back">
          <BackIcon />
        </Link>
        <h1 className="page-title">{name}</h1>
      </div>
      {!titles && <div className="spinner" />}
      {titles && titles.length === 0 && <p className="center">Nothing here — try another genre.</p>}
      {titles && titles.length > 0 && (
        <div className="poster-grid">
          {titles.map((t) => (
            <Poster key={t.imdb} title={t} />
          ))}
        </div>
      )}
    </main>
  );
}

export default function CatalogScreen() {
  return (
    <Suspense fallback={<main className="page"><div className="spinner" /></main>}>
      <CatalogInner />
    </Suspense>
  );
}