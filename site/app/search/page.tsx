'use client';
/**
 * Search: poster-first results, recent queries, and filter tags that combine —
 * pick Films + Series, or Action + Comedy, and the grid shows titles that carry
 * every selected genre. Tags live in the URL (?kind=movie,tv&genre=Action,Comedy)
 * so back/forward and shared links keep them.
 */
import { Suspense, useEffect, useState } from 'react';
import { useSearchParams, useRouter } from 'next/navigation';
import { ANIME_GENRE, GENRES, loadCatalog, searchTitles, type Title } from '../../lib/meta';
import { clearSearches, loadSearches, rememberSearch } from '../../lib/searches';
import { Poster } from '../../components/Blocks';

type Kind = 'movie' | 'tv';

const list = (v: string | null) => (v ? v.split(',').filter(Boolean) : []);

/** Films and series interleaved, so a show is not buried under thirty films. */
function interleave(a: Title[], b: Title[]): Title[] {
  const out: Title[] = [];
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    if (a[i]) out.push(a[i]);
    if (b[i]) out.push(b[i]);
  }
  return out;
}

/**
 * Titles carrying every selected genre. Each genre is its own catalogue, so
 * fetch them all, merge by id, and keep the rows tagged with the whole set.
 * Anime is a separate catalogue rather than a genre — coming from it is the tag.
 */
async function browse(genres: string[], kinds: Kind[]): Promise<Title[]> {
  const jobs: Promise<{ tag: string | null; titles: Title[] }>[] = [];
  for (const g of genres) {
    if (g === ANIME_GENRE) {
      jobs.push(loadCatalog('tv', ANIME_GENRE).then((titles) => ({ tag: g, titles })));
    } else {
      for (const k of kinds) jobs.push(loadCatalog(k, g).then((titles) => ({ tag: null, titles })));
    }
  }
  const byId = new Map<string, { title: Title; tags: Set<string> }>();
  for (const { tag, titles } of await Promise.all(jobs.map((j) => j.catch(() => ({ tag: null, titles: [] }))))) {
    for (const t of titles) {
      const row = byId.get(t.imdb) ?? { title: t, tags: new Set(t.genres ?? []) };
      if (tag) row.tags.add(tag);
      (t.genres ?? []).forEach((g) => row.tags.add(g));
      byId.set(t.imdb, row);
    }
  }
  return [...byId.values()]
    .filter((r) => genres.every((g) => r.tags.has(g)))
    .map((r) => r.title);
}

function SearchScreenInner() {
  const router = useRouter();
  const params = useSearchParams();
  const query = params.get('q') ?? '';
  const kinds = list(params.get('kind')) as Kind[];
  const genres = list(params.get('genre'));
  const key = `${query}|${kinds.join()}|${genres.join()}`;

  const [recent, setRecent] = useState<string[]>([]);
  const [results, setResults] = useState<{ key: string; titles: Title[] } | null>(null);
  const [input, setInput] = useState(query);

  useEffect(() => {
    loadSearches().then(setRecent);
  }, []);

  useEffect(() => {
    let live = true;
    const q = query.trim();
    const askKinds: Kind[] = kinds.length ? kinds : ['movie', 'tv'];
    const pending: Promise<Title[]> = q
      ? Promise.all(askKinds.map((k) => searchTitles(q, k).catch(() => [] as Title[]))).then((r) =>
          interleave(r[0] ?? [], r[1] ?? [])
        )
      : genres.length
        ? browse(genres, askKinds)
        : Promise.all(askKinds.map((k) => loadCatalog(k).catch(() => [] as Title[]))).then((r) =>
            interleave(r[0] ?? [], r[1] ?? [])
          );
    pending.then((titles) => {
      if (live) setResults({ key, titles: titles.filter((t) => askKinds.includes(t.kind)) });
    });
    return () => {
      live = false;
    };
    // `key` is the whole input; the arrays are rebuilt every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  const go = (next: { q?: string; kinds?: string[]; genres?: string[] }) => {
    const p = new URLSearchParams();
    const q = (next.q ?? query).trim();
    const k = next.kinds ?? kinds;
    const g = next.genres ?? genres;
    if (q) p.set('q', q);
    if (k.length) p.set('kind', k.join(','));
    if (g.length) p.set('genre', g.join(','));
    router.replace(p.size ? `/search?${p}` : '/search', { scroll: false });
  };

  /** Typing updates the URL after a pause, not on every key. */
  useEffect(() => {
    if (input.trim() === query.trim()) return;
    const t = setTimeout(() => go({ q: input }), 350);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [input]);

  const toggle = (arr: string[], v: string) =>
    arr.includes(v) ? arr.filter((x) => x !== v) : [...arr, v];

  const remember = () => {
    if (query.trim()) rememberSearch(query.trim()).then(setRecent);
  };

  const busy = !results || results.key !== key;
  const shown = results?.titles ?? [];
  const filtering = kinds.length + genres.length > 0;

  return (
    <main className="page">
      <div className="page-head">
        <h1 className="page-title">Search</h1>
      </div>

      <div className="search-bar">
        <svg width="17" height="17" viewBox="0 0 24 24" aria-hidden>
          <circle cx="11" cy="11" r="7" fill="none" stroke="currentColor" strokeWidth="2" />
          <path d="m20 20-3.5-3.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
        </svg>
        <input
          autoFocus
          placeholder="Films and series"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && input.trim()) {
              go({ q: input });
              rememberSearch(input.trim()).then(setRecent);
            }
          }}
        />
        {busy && (query || filtering) ? (
          <div className="spinner spinner-sm" />
        ) : (
          input && (
            <button className="search-clear" aria-label="Clear" onClick={() => { setInput(''); go({ q: '' }); }}>
              ×
            </button>
          )
        )}
      </div>

      {!query && recent.length > 0 && (
        <div className="chips chips-recent">
          <span className="chips-label">Recent</span>
          {recent.map((q) => (
            <button key={q} className="chip" onClick={() => { setInput(q); go({ q }); }}>
              {q}
            </button>
          ))}
          <button className="chip chip-ghost" aria-label="Clear recent searches" onClick={() => clearSearches().then(setRecent)}>
            ×
          </button>
        </div>
      )}

      <div className="chips chips-wrap">
        {(
          [
            ['movie', 'Films'],
            ['tv', 'Series'],
          ] as const
        ).map(([k, label]) => (
          <button
            key={k}
            className={`chip ${kinds.includes(k) ? 'active' : ''}`}
            aria-pressed={kinds.includes(k)}
            onClick={() => go({ kinds: toggle(kinds, k) })}
          >
            {kinds.includes(k) && '✓ '}
            {label}
          </button>
        ))}
        {/* Search results carry no genres, so genre tags only apply while
            browsing — the same rule as the Android app. */}
        {!query && (
          <>
            <span className="chips-rule" />
            {GENRES.map((g) => (
              <button
                key={g}
                className={`chip ${genres.includes(g) ? 'active' : ''}`}
                aria-pressed={genres.includes(g)}
                onClick={() => go({ genres: toggle(genres, g) })}
              >
                {genres.includes(g) && '✓ '}
                {g}
              </button>
            ))}
          </>
        )}
        {filtering && (
          <button className="chip chip-ghost" onClick={() => go({ kinds: [], genres: [] })}>
            Clear filters
          </button>
        )}
      </div>

      {!busy && shown.length > 0 && (
        <h2 className="search-heading">
          {query
            ? `${shown.length} result${shown.length === 1 ? '' : 's'}`
            : genres.length
              ? `${shown.length} titles · ${genres.join(' + ')}`
              : 'Popular right now'}
        </h2>
      )}
      {busy && !shown.length && <div className="spinner" style={{ marginTop: 40 }} />}
      {!busy && shown.length === 0 && (
        <p className="center" style={{ marginTop: 40 }}>
          {query
            ? `Nothing found for “${query}”.`
            : 'Nothing carries all of those tags — try removing one.'}
        </p>
      )}
      {shown.length > 0 && (
        <div className="poster-grid" style={{ marginTop: 6, opacity: busy ? 0.5 : 1 }} onClick={remember}>
          {shown.map((t) => (
            <Poster key={t.imdb} title={t} />
          ))}
        </div>
      )}
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
