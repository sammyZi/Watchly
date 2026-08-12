'use client';
/** Search: poster-first results, recent queries, genre chips. */
import { Suspense, useEffect, useRef, useState } from 'react';
import { useSearchParams, useRouter } from 'next/navigation';
import { GENRES, kindOfGenre, loadCatalog, searchTitles, type Title } from '../../lib/meta';
import { loadSearches, rememberSearch } from '../../lib/searches';
import { Poster } from '../../components/Blocks';

function SearchScreenInner() {
  const router = useRouter();
  const params = useSearchParams();
  const query = params.get('q') ?? '';
  const genre = params.get('genre') ?? null;

  const [recent, setRecent] = useState<string[]>([]);
  const [results, setResults] = useState<Title[] | null>(null);
  const [input, setInput] = useState(query);
  const loaded = useRef<string | null>(null);

  useEffect(() => {
    loadSearches().then(setRecent);
  }, []);

  useEffect(() => {
    const q = query.trim();
    const key = `${q}:${genre ?? ''}`;
    if (loaded.current === key) return;
    loaded.current = key;
    setResults(null);

    // Films and series are separate catalogues, and a search only ever asked
    // the one the `kind` param happened to hold — so every series came back as
    // "nothing found". Both are asked, and the answers interleave so looking
    // for a show does not mean scrolling past thirty films first.
    const pending: Promise<Title[]> = q
      ? Promise.all([searchTitles(q, 'movie'), searchTitles(q, 'tv')]).then(
          ([films, series]) => {
            const mixed: Title[] = [];
            for (let i = 0; i < Math.max(films.length, series.length); i++) {
              if (films[i]) mixed.push(films[i]);
              if (series[i]) mixed.push(series[i]);
            }
            return mixed;
          }
        )
      : // No query but a genre chosen: that is browsing, which the chips used
        // to do nothing at all for — they set the URL and left a blank page.
        genre
        ? loadCatalog(kindOfGenre(genre), genre)
        : Promise.resolve([] as Title[]);

    pending.then((titles) => {
      if (loaded.current === key) setResults(titles);
    });
  }, [query, genre]);

  useEffect(() => {
    if (input !== query) {
      const t = setTimeout(() => {
        if (!input.trim()) {
          router.replace('/search');
          return;
        }
        router.replace(`/search?q=${encodeURIComponent(input.trim())}`);
      }, 350);
      return () => clearTimeout(t);
    }
  }, [input, query, router]);

  const pickGenre = (g: string | null) => {
    const next = new URLSearchParams();
    if (g) next.set('genre', g);
    if (query) next.set('q', query);
    router.replace(next.toString() ? `/search?${next}` : '/search');
  };

  const submit = (q: string) => {
    if (!q.trim()) return;
    rememberSearch(q.trim()).then(setRecent);
    router.replace(`/search?q=${encodeURIComponent(q.trim())}`);
  };

  return (
    <main className="page">
      <div className="page-head">
        <h1 className="page-title">Search</h1>
      </div>
      <input
        className="input"
        autoFocus
        placeholder="Films, series…"
        value={input}
        onChange={(e) => setInput(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && submit(input)}
      />
      <div className="chips" style={{ marginTop: 12 }}>
        <button className={`chip ${genre === null ? 'active' : ''}`} onClick={() => pickGenre(null)}>
          All
        </button>
        {GENRES.map((g) => (
          <button key={g} className={`chip ${genre === g ? 'active' : ''}`} onClick={() => pickGenre(g)}>
            {g}
          </button>
        ))}
      </div>

      {!query && !genre && (
        <>
          {recent.length > 0 && (
            <>
              <h2 style={{ fontSize: 13, letterSpacing: 0.8, textTransform: 'uppercase', color: 'var(--muted)', margin: '18px 2px 8px' }}>
                Recent
              </h2>
              <div className="chips">
                {recent.map((q) => (
                  <button key={q} className="chip" onClick={() => submit(q)}>
                    {q}
                  </button>
                ))}
              </div>
            </>
          )}
        </>
      )}

      {(query || genre) && !results && <div className="spinner" />}
      {query && results?.length === 0 && <p className="center">Nothing found for “{query}”.</p>}
      {results && results.length > 0 && (
        <h2 style={{ fontSize: 13, letterSpacing: 0.8, textTransform: 'uppercase', color: 'var(--muted)', margin: '18px 2px 8px' }}>
          {query ? `${results.length} result${results.length === 1 ? '' : 's'}` : genre}
        </h2>
      )}
      {results?.length ? (
        <div className="poster-grid" style={{ marginTop: 6 }}>
          {results.map((t) => (
            <Poster key={t.imdb} title={t} />
          ))}
        </div>
      ) : null}
    </main>
  );
}

export default function SearchScreen() {
  return (
    <Suspense fallback={<main className="page"><div className="spinner" /></main>}>
      <SearchScreenInner />
    </Suspense>
  );
}