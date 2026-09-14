'use client';
/**
 * Home — same sections, same order as the Android HomeScreen:
 * hero, Continue watching, Watch later, Favourites, genres chosen in Settings,
 * recommendations from watch history, then the fixed charts.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ANIME_GENRE,
  GENRES,
  kindOfGenre,
  loadCatalog,
  peekCatalog,
  resolveTitle,
  watchedGenres,
  type Title,
} from '../lib/meta';
import { loadHistory, patch, type Item } from '../lib/history';
import { loadSettings } from '../lib/settings';
import { asTitle, inList, loadSaved, type Saved } from '../lib/saved';
import { parseEpisode } from '../lib/search';
import { clock } from '../lib/format';
import { Hero, Poster, Shelf, titleHref } from '../components/Blocks';

type ShelfSpec = { title: string; kind: 'movie' | 'tv'; genre?: string };

const SHELVES: ShelfSpec[] = [
  { title: 'Trending films', kind: 'movie' },
  { title: 'Popular series', kind: 'tv' },
  { title: 'Anime', kind: 'tv', genre: ANIME_GENRE },
];

/** Mirrors `hues` in src/theme.ts. */
const HUES = ['#d42f37', '#7c5cff', '#00b8d4', '#f5a623', '#38c172'];

const shelfKey = (s: ShelfSpec) => `${s.kind}:${s.genre ?? ''}`;

const catalogHref = (s: ShelfSpec) =>
  `/catalog/${encodeURIComponent(s.genre || (s.kind === 'movie' ? 'films' : 'series'))}?kind=${s.kind}`;

/** Still watching — has a resume point short of the end. */
function resumeRatio(item: Item): number {
  const i = item.lastFileIndex;
  if (i == null) return 0;
  const position = item.positions?.[i];
  const duration = item.durations?.[i];
  return position && duration ? Math.min(0.99, position / duration) : 0;
}

function resumeStamp(item: Item): string | null {
  const i = item.lastFileIndex;
  if (i == null) return null;
  const file = item.files?.find((f) => f.index === i);
  const at = parseEpisode(file?.name ?? item.title);
  const ep = at ? (at.season === 0 ? `Sp ${at.episode}` : `S${at.season} E${at.episode}`) : null;
  const position = item.positions?.[i];
  return [ep, position ? clock(position) : null].filter(Boolean).join('  ·  ') || null;
}

/** History as a shelf card. */
function fromHistory(item: Item, saved: Saved[]): Title {
  const book = saved.find((s) => s.imdb && s.imdb === item.imdb);
  return asTitle({
    imdb: item.imdb ?? item.uri,
    kind: item.kind ?? book?.kind ?? 'movie',
    title: item.titleName ?? book?.title ?? item.title,
    poster: item.poster ?? book?.poster ?? null,
    year: null,
    rating: null,
    list: 'later',
    at: item.at,
  });
}

/** Straight back into the player at the saved file and position. */
function resumeHref(item: Item): string {
  const q = new URLSearchParams({ uri: item.uri, file: String(item.lastFileIndex) });
  if (item.titleName) q.set('titleName', item.titleName);
  if (item.imdb) q.set('imdb', item.imdb);
  if (item.kind) q.set('kind', item.kind);
  if (item.poster) q.set('poster', item.poster);
  return `/watch?${q}`;
}

export default function HomeScreen() {
  const [recent, setRecent] = useState<Item[]>([]);
  const [saved, setSaved] = useState<Saved[]>([]);
  const [genres, setGenres] = useState<string[]>([]);
  const [picks, setPicks] = useState<ShelfSpec[]>([]);
  const [loaded, setLoaded] = useState<Record<string, Title[]>>(() =>
    Object.fromEntries(
      SHELVES.map((s) => [shelfKey(s), peekCatalog(s.kind, s.genre)]).filter(([, t]) => t)
    )
  );
  const [heroAt, setHeroAt] = useState(0);

  useEffect(() => {
    Promise.all([loadHistory(), loadSaved(), loadSettings()]).then(([h, s, settings]) => {
      setRecent(h);
      setSaved(s);
      setGenres(settings.genres);
    });
  }, []);

  /**
   * Poster backfill. Torrents opened from a magnet, or before titles were
   * recorded, are stored under their release name only — look each one up
   * once and write the real title and artwork back, as the Android app does.
   */
  const [backfilled, setBackfilled] = useState(false);
  useEffect(() => {
    if (backfilled || !recent.length) return;
    setBackfilled(true);
    const missing = recent.filter((h) => !h.poster && !h.uri.startsWith('saved:')).slice(0, 12);
    (async () => {
      for (const item of missing) {
        const found = await resolveTitle(item.torrentName ?? item.title).catch(() => null);
        if (!found) continue;
        const fields: Partial<Item> = {
          imdb: item.imdb ?? found.imdb,
          kind: item.kind ?? found.kind,
          titleName: item.titleName ?? found.title,
          ...(found.poster && { poster: found.poster }),
        };
        await patch(item.uri, fields);
        setRecent((all) => all.map((h) => (h.uri === item.uri ? { ...h, ...fields } : h)));
      }
    })();
  }, [recent, backfilled]);

  /** Shelves built from what has actually been watched — in-progress first. */
  useEffect(() => {
    const identified = (items: Item[]) =>
      items
        .filter((h): h is Item & { imdb: string } => !!h.imdb)
        .map((h) => ({ imdb: h.imdb, kind: h.kind ?? ('movie' as const) }));
    const continuing = recent.filter((h) => resumeRatio(h) > 0 && resumeRatio(h) < 0.9);
    const rest = recent.filter((h) => !continuing.includes(h));
    const seen = [
      ...identified(continuing),
      ...identified(rest),
      ...saved.map((s) => ({ imdb: s.imdb, kind: s.kind })),
    ].slice(0, 30);
    if (!seen.length) return;
    let live = true;
    watchedGenres(seen, 4).then((found) => {
      if (!live) return;
      setPicks(
        found.map(({ genre, kind }, i) => ({
          title: i === 0 ? 'Recommended for you' : `Because you like ${genre}`,
          kind,
          genre,
        }))
      );
    });
    return () => {
      live = false;
    };
  }, [recent, saved]);

  /** Genres picked in Settings are a statement; they lead. */
  const chosen = useMemo<ShelfSpec[]>(
    () =>
      genres.map((genre) => ({
        title: genre === ANIME_GENRE ? 'Anime' : genre,
        kind: kindOfGenre(genre),
        genre,
      })),
    [genres]
  );

  // Chosen, then recommendations, then the charts, then every other genre —
  // no catalogue twice.
  const shelves = useMemo(() => {
    const taken = new Set<string>();
    const fresh = (list: ShelfSpec[]) =>
      list.filter((s) => !taken.has(shelfKey(s)) && taken.add(shelfKey(s)));
    const everyGenre = GENRES.map((genre) => ({
      title: genre,
      kind: kindOfGenre(genre),
      genre,
    }));
    return [...fresh(chosen), ...fresh(picks), ...fresh(SHELVES), ...fresh(everyGenre)];
  }, [chosen, picks]);

  /**
   * Shelves load as they come near the screen — with every genre on the page
   * that is twenty-odd catalogues, too many to fetch up front on a phone.
   */
  const [near, setNear] = useState<Set<string>>(() => new Set());
  const observer = useRef<IntersectionObserver | null>(null);
  const watchShelf = (el: HTMLElement | null) => {
    if (!el || typeof IntersectionObserver === 'undefined') return;
    observer.current ??= new IntersectionObserver(
      (entries) => {
        const keys = entries.filter((e) => e.isIntersecting).map((e) => (e.target as HTMLElement).dataset.shelf!);
        if (keys.length) setNear((s) => new Set([...s, ...keys]));
      },
      { rootMargin: '800px 0px' }
    );
    observer.current.observe(el);
  };
  useEffect(() => () => observer.current?.disconnect(), []);

  const requested = useRef(new Set<string>());
  useEffect(() => {
    for (const shelf of shelves.filter((s, i) => i < 4 || near.has(shelfKey(s)))) {
      const key = shelfKey(shelf);
      if (requested.current.has(key)) continue;
      requested.current.add(key);
      // An empty or failed catalogue is recorded too, so its shelf hides
      // instead of spinning forever.
      loadCatalog(shelf.kind, shelf.genre)
        .catch(() => [] as Title[])
        .then((titles) => setLoaded((all) => ({ ...all, [key]: titles })));
    }
  }, [shelves, near]);

  const watchedIds = useMemo(
    () => new Set(recent.map((h) => h.imdb).filter(Boolean) as string[]),
    [recent]
  );
  const heroShelf = chosen[0] ?? picks[0] ?? SHELVES[0];
  const heroPersonal = !!loaded[shelfKey(heroShelf)];
  const featured = (loaded[shelfKey(heroShelf)] ?? loaded[shelfKey(SHELVES[0])] ?? [])
    .filter((t) => !watchedIds.has(t.imdb))
    .slice(0, 5);
  const eyebrow = heroPersonal
    ? chosen[0]
      ? `in ${chosen[0].genre}`
      : picks[0]
        ? 'for you'
        : 'today'
    : 'today';

  /** Hero advances every 10s; picking a dot restarts the clock. */
  useEffect(() => {
    if (featured.length < 2) return;
    const t = setTimeout(() => setHeroAt((i) => (i + 1) % featured.length), 10_000);
    return () => clearTimeout(t);
  }, [featured.length, heroAt]);

  const resumable = recent.filter((h) => resumeRatio(h) > 0 && resumeRatio(h) < 0.9);
  const later = inList(saved, 'later');
  const favorites = inList(saved, 'favorite');
  const hero = featured[Math.min(heroAt, featured.length - 1)];

  return (
    <main className="page">
      {hero ? (
        <Hero
          title={hero}
          eyebrow={`#${Math.min(heroAt, featured.length - 1) + 1} ${eyebrow}`}
          actionHref={titleHref(hero)}
          actionLabel="Watch"
          page={{ count: featured.length, index: Math.min(heroAt, featured.length - 1), onPick: setHeroAt }}
        />
      ) : (
        <div className="spinner" style={{ margin: '120px auto' }} />
      )}

      {resumable.length > 0 && (
        <Shelf title="Continue watching" hue={HUES[0]} href="/library">
          {resumable.map((item) => (
            <Poster
              key={item.uri}
              title={fromHistory(item, saved)}
              href={resumeHref(item)}
              progress={resumeRatio(item)}
              stamp={resumeStamp(item)}
            />
          ))}
        </Shelf>
      )}

      {later.length > 0 && (
        <Shelf title="Watch later" hue={HUES[1]} href="/library">
          {later.map((s) => (
            <Poster key={s.imdb} title={asTitle(s)} />
          ))}
        </Shelf>
      )}

      {favorites.length > 0 && (
        <Shelf title="Favourites" hue={HUES[2]} href="/library">
          {favorites.map((s) => (
            <Poster key={s.imdb} title={asTitle(s)} />
          ))}
        </Shelf>
      )}

      {shelves.map((shelf, i) => {
        const raw = loaded[shelfKey(shelf)];
        // Genre shelves are recommendations: drop anything already watched.
        const data = raw && shelf.genre ? raw.filter((t) => !watchedIds.has(t.imdb)) : raw;
        if (data && !data.length) return null;
        return (
          <Shelf
            key={shelfKey(shelf)}
            title={shelf.title}
            hue={HUES[(i + 3) % HUES.length]}
            href={data?.length ? catalogHref(shelf) : undefined}
          >
            {data ? (
              data.slice(0, 20).map((t) => <Poster key={t.imdb} title={t} />)
            ) : (
              <div className="shelf-loading" data-shelf={shelfKey(shelf)} ref={watchShelf}>
                <div className="spinner" />
              </div>
            )}
          </Shelf>
        );
      })}
    </main>
  );
}
