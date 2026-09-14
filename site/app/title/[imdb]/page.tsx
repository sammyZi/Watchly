'use client';
/**
 * One title: artwork and facts up top, the releases that can actually play it
 * underneath. Same logic as the native DetailScreen — release search by name,
 * quality picker, episode packs, resume.
 */
import {
  Suspense,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import Link from 'next/link';
import { useParams, useRouter, useSearchParams } from 'next/navigation';
import { loadTitle, reviewLinks, type Episode, type Title } from '../../../lib/meta';
import { episodeTag, searchEpisode, searchTorrents, type Hit } from '../../../lib/search';
import { bytes, clock } from '../../../lib/format';
import { hueOf } from '../../../lib/title';
import { loadHistory, type Item } from '../../../lib/history';
import { loadEpisodeLinks, WATCHED } from '../../../lib/watched';
import {
  asTitle,
  loadSaved,
  onList,
  toggleSaved,
  type List,
} from '../../../lib/saved';
import { useOnline } from '../../../lib/net';
import {
  BackIcon,
  BookmarkIcon,
  HeartIcon,
  ReleaseRow,
  Thumb,
  titleHref,
} from '../../../components/Blocks';

const QUALITIES = ['4K', '1080p', '720p', 'CAM'];
const EPISODE_PAGE = 10;

type Progress = Record<string, number>;

function DetailInner() {
  const { imdb } = useParams<{ imdb: string }>();
  const params = useSearchParams();
  const kind = (params.get('kind') as 'movie' | 'tv') || 'movie';
  const router = useRouter();
  const online = useOnline();

  const [title, setTitle] = useState<Title | null>(null);
  const [saved, setSaved] = useState<ReturnType<typeof asTitle> | null>(null);
  const [savedList, setSavedList] = useState<Awaited<ReturnType<typeof loadSaved>>>([]);
  const [sources, setSources] = useState<Hit[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [wanted, setWanted] = useState<string | null>(null);
  const [season, setSeason] = useState<number | null>(null);
  const [picked, setPicked] = useState<Episode | null>(null);
  const [wholeSeason, setWholeSeason] = useState(false);
  const [progress, setProgress] = useState<Progress>({});
  const [resume, setResume] = useState<{ position: number; duration: number; uri: string } | null>(null);
  /** Episode id -> the torrent it was watched from. */
  const [links, setLinks] = useState<Record<string, string>>({});
  const [open, setOpen] = useState(false);

  useEffect(() => {
    let live = true;
    loadSaved().then((s) => live && setSavedList(s));
    return () => {
      live = false;
    };
  }, []);

  useEffect(() => {
    let live = true;
    loadTitle(imdb, kind).then((t) => {
      if (!live || !t) return;
      setTitle(t);
      if (!t.plot) setAncilla(t);
    });
    const seed = savedList.find((s) => s.imdb === imdb);
    if (seed) setAncilla(asTitle(seed));
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [imdb, kind]);

  function setAncilla(t: Title) {
    setTitle((prev) => (prev?.plot ? prev : t));
  }

  // Progress + resume from history: episode id -> %, and film position.
  useEffect(() => {
    let live = true;
    (async () => {
      const [items, epLinks] = await Promise.all([loadHistory(), loadEpisodeLinks()]);
      if (!live) return;
      const mine = items.filter((i) => i.imdb === imdb);
      const map: Progress = {};
      // History is newest first; the first film position found is the latest.
      let film: { position: number; duration: number; uri: string } | null = null;
      for (const item of mine) {
        const epId = Object.entries(epLinks).find(([, uri]) => uri === item.uri)?.[0];
        for (const [idx, pos] of Object.entries(item.positions ?? {})) {
          const dur = item.durations?.[idx];
          if (dur) {
            const pct = Math.min(100, (pos / dur) * 100);
            if (epId) map[epId] = pct;
            if (!epId && item.kind === 'movie' && !film) film = { position: pos, duration: dur, uri: item.uri };
          }
        }
      }
      setProgress(map);
      setLinks(epLinks);
      setResume(film);
    })();
    return () => {
      live = false;
    };
  }, [imdb]);

  const episodes = title?.episodes ?? [];
  const seasons = useMemo(() => [...new Set(episodes.map((e) => e.season))], [episodes]);

  const inSeason = episodes.filter((e) => e.season === season);
  const showsWhole = wholeSeason || (picked ? inSeason.findIndex((e) => e.id === picked.id) >= EPISODE_PAGE : false);
  const listed = showsWhole ? inSeason : inSeason.slice(0, EPISODE_PAGE);
  const tag = picked ? episodeTag(picked.season, picked.episode) : undefined;

  const qualities = QUALITIES.filter((q) => sources?.some((h) => h.quality === q));
  const shown = wanted ? (sources ?? []).filter((h) => h.quality === wanted) : sources;
  const best = shown?.[0] ?? null;

  useEffect(() => {
    if (wanted && sources && !sources.some((h) => h.quality === wanted)) setWanted(null);
  }, [sources, wanted]);

  const seen = picked
    ? (progress[picked.id] ?? 0)
    : resume
      ? Math.min(100, (resume.position / resume.duration) * 100)
      : 0;
  // Resume positions belong to the torrent they were watched from — another
  // release would start from zero.
  const resumeUri = picked ? links[picked.id] : resume?.uri;
  const canResume = seen >= 1 && seen < WATCHED && !!resumeUri;

  // Releases by name — same as the app: episode asks for its packs too.
  const reject = useRef(false);
  useEffect(() => {
    reject.current = false;
    const query = title?.title;
    if (!query) return;
    const ctrl = new AbortController();
    setSources(null);
    setError(null);
    const wanted2 = picked
      ? searchEpisode(query, picked.season, picked.episode, ctrl.signal, title?.imdb)
      : searchTorrents(query, ctrl.signal, {
          imdb: title?.imdb,
          year: title?.kind === 'movie' ? title?.year : null,
        });
    wanted2
      .then((hits) => !reject.current && !ctrl.signal.aborted && setSources(hits))
      .catch((e) => {
        if (ctrl.signal.aborted) return;
        setSources([]);
        setError(String(e?.message ?? e));
      });
    return () => {
      reject.current = true;
      ctrl.abort();
    };
  }, [title?.title, title?.year, title?.kind, title?.imdb, picked, online]);

  const nextIn = (which: number | null) => {
    const s = episodes.filter((e) => e.season === which);
    return s.find((e) => (progress[e.id] ?? 0) < WATCHED) ?? s[0] ?? null;
  };

  const chooseSeason = (which: number) => {
    setSeason(which);
    setWholeSeason(false);
    const next = nextIn(which);
    if (next) setPicked(next);
  };

  useEffect(() => {
    if (title?.kind !== 'tv' || !episodes.length || picked) return;
    const next = episodes.find((e) => e.season > 0 && (progress[e.id] ?? 0) < WATCHED) ?? episodes[0];
    setPicked(next);
    setSeason(next.season);
  }, [title, picked, episodes, progress]);

  const toggle = useCallback(
    async (list: List) => {
      if (!title) return;
      const next = await toggleSaved(title, list);
      setSavedList(next);
    },
    [title]
  );

  const watchHref = (magnet: string, name?: string) => {
    const q = new URLSearchParams({
      uri: magnet,
      name: name ?? title?.title ?? magnet,
      imdb,
      kind: title?.kind ?? kind,
    });
    if (tag) q.set('tag', tag);
    if (picked) q.set('epId', picked.id);
    if (picked) q.set('episode', String(picked.episode));
    if (picked) q.set('season', String(picked.season));
    if (title?.poster) q.set('poster', title.poster);
    if (title?.title) q.set('titleName', title.title);
    if (title?.backdrop) q.set('backdrop', title.backdrop);
    if (picked?.name) q.set('epName', picked.name);
    if (title?.kind) q.set('kind', title.kind);
    return `/watch?${q}`;
  };

  // Loading: the page's own shape, empty — not the raw IMDb id (tt1375666)
  // as a heading, which is what flashed at the top before the title arrived.
  if (!title) {
    return (
      <main className="page">
        <div className="page-head detail-bar">
          <button
            className="back-btn"
            aria-label="Back"
            onClick={() => (window.history.length > 1 ? router.back() : router.push('/'))}
          >
            <BackIcon />
          </button>
        </div>
        <div className="detail-backdrop skeleton" />
        <div className="skeleton skeleton-line" style={{ width: '60%', marginTop: 20 }} />
        <div className="skeleton skeleton-line" style={{ width: '35%', marginTop: 10 }} />
      </main>
    );
  }

  return (
    <main className="page">
      <div className="page-head detail-bar">
        <button
          className="back-btn"
          aria-label="Back"
          onClick={() => (window.history.length > 1 ? router.back() : router.push('/'))}
        >
          <BackIcon />
        </button>
        <div style={{ display: 'flex', gap: 8, marginLeft: 'auto' }}>
          {(['later', 'favorite'] as const).map((list) => {
            const active = onList(savedList, imdb, list);
            return (
              <button
                key={list}
                className={`icon-btn ${active ? 'on' : ''}`}
                onClick={() => toggle(list)}
                title={list === 'later' ? 'Watch later' : 'Favourite'}
              >
                {list === 'later' ? (
                  <BookmarkIcon filled={active} />
                ) : (
                  <HeartIcon filled={active} />
                )}
              </button>
            );
          })}
        </div>
      </div>

      <div className="detail-backdrop">
        {(title.backdrop ?? title.poster) && (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={title.backdrop ?? title.poster!}
            alt=""
            style={{ width: '100%', height: '100%', objectFit: 'cover' }}
          />
        )}
        <div
          style={{
            position: 'absolute',
            inset: 0,
            background: 'linear-gradient(to top, var(--app-bg) 2%, transparent 70%)',
          }}
        />
        {canResume && (
          <Link
            href={watchHref(resumeUri!)}
            className="btn btn-primary"
            style={{ position: 'absolute', right: 16, bottom: 16, zIndex: 2, opacity: 0.9 }}
          >
            ▶ Continue
          </Link>
        )}
      </div>

      {/* Two columns on a laptop: the title and its episodes on the
          left, the release list beside them rather than a screen and a
          half further down. One column below 1000px, unchanged. */}
      <div className="detail-grid">
        <div className="detail-main">
      <div className="detail-head">
        <Thumb name={title.title} size={132} poster={title.poster} radius={14} />
        <div style={{ flex: 1, minWidth: 0, paddingBottom: 4 }}>
          <h1 className="detail-title">{title.title}</h1>
          <div style={{ fontSize: 14, color: 'var(--muted)', marginTop: 6, display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
            {title.rating && <span className="warn-text">★ {title.rating}</span>}
            <span style={{ color: 'var(--accent)' }}>{title.year} · {title.runtime} · {title.kind === 'tv' ? 'Series' : 'Film'}</span>
          </div>
        </div>
      </div>

      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', margin: '12px 0 4px' }}>
        {(title.genres ?? []).map((g) => (
          <span key={g} className="tag">{g}</span>
        ))}
      </div>

      <div className="actions">
        <button
          className="btn btn-primary"
          disabled={!best && !canResume}
          onClick={() => {
            if (canResume) router.push(watchHref(resumeUri!));
            else if (best) router.push(watchHref(best.magnet, best.name));
          }}
        >
          {canResume
            ? `Continue${tag ? ` ${tag}` : ''} · ${Math.round(seen)}%`
            : best
              ? tag
                ? `Play ${tag}`
                : 'Play'
              : sources
                ? 'No releases'
                : 'Finding releases…'}
        </button>
        <button
          className="btn btn-soft"
          disabled={!best}
          onClick={() => best && router.push(`${watchHref(best.magnet, best.name)}&dl=1`)}
        >
          ↓ Save
        </button>
      </div>

      <div className="segment" style={{ display: 'flex', marginBottom: 14 }}>
        {qualities.map((q) => (
          <button
            key={q}
            className={wanted === q ? 'active' : ''}
            onClick={() => setWanted(wanted === q ? null : q)}
            style={{ flex: 1 }}
          >
            {q}
            <span style={{ fontSize: 10, opacity: 0.7, marginLeft: 4 }}>
              {sources?.filter((h) => h.quality === q).length ?? 0}
            </span>
          </button>
        ))}
      </div>

      {title.plot && (
        <p style={{ fontSize: 15, lineHeight: '24px', color: 'var(--muted)', margin: '8px 0', maxWidth: 720 }}>
          {open ? title.plot : `${title.plot.slice(0, 260)}${title.plot.length > 260 ? '…' : ''}`}
          {title.plot.length > 260 && (
            <button className="accent-text" onClick={() => setOpen(!open)} style={{ color: 'var(--accent)', marginLeft: 6 }}>
              {open ? 'Less' : 'More'}
            </button>
          )}
        </p>
      )}

      {title.kind === 'tv' && seasons.length > 0 && (
        <>
          <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', margin: '18px 0 6px' }}>
            <h2 style={{ fontSize: 20, fontWeight: 600 }}>Episodes</h2>
            <span className="faint" style={{ fontSize: 11 }}>{episodes.length} episodes</span>
          </div>
          <div className="chips">
            {seasons.map((s) => (
              <button key={s} className={`chip ${season === s ? 'active' : ''}`} onClick={() => chooseSeason(s)}>
                Season {s}
              </button>
            ))}
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {listed.map((e) => {
              const pct = progress[e.id] ?? 0;
              return (
                <button
                  key={e.id}
                  onClick={() => setPicked(e)}
                  style={{
                    display: 'flex',
                    gap: 12,
                    padding: 8,
                    borderRadius: 12,
                    background: picked?.id === e.id ? 'var(--surface-alt)' : 'var(--surface)',
                    textAlign: 'left',
                    position: 'relative',
                  }}
                >
                  <div style={{ width: 116, height: 66, borderRadius: 8, background: 'var(--surface-alt)', position: 'relative', overflow: 'hidden', flex: 'none' }}>
                    {e.thumbnail && (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img src={e.thumbnail} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                    )}
                    {pct > 0 && (
                      <div style={{ position: 'absolute', left: 6, right: 6, bottom: 6, height: 3, borderRadius: 2, background: 'var(--line)' }}>
                        <div style={{ width: `${pct}%`, height: '100%', background: 'var(--accent)' }} />
                      </div>
                    )}
                  </div>
                  <div style={{ flex: 1, textAlign: 'left' }}>
                    <div style={{ fontWeight: 600, fontSize: 13 }}>{e.name}</div>
                    <div style={{ fontSize: 11, color: 'var(--faint)', margin: '2px 0' }}>
                      S{e.season}E{e.episode}
                      {e.released ? ` · ${e.released.slice(0, 10)}` : ''}
                    </div>
                    {e.overview && (
                      <div style={{ fontSize: 11, color: 'var(--muted)', lineHeight: '15px', display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
                        {e.overview}
                      </div>
                    )}
                    {pct >= WATCHED && <span style={{ position: 'absolute', top: 4, right: 4, width: 18, height: 18, borderRadius: 9, background: 'var(--accent)', color: '#fff', fontSize: 11, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>✓</span>}
                  </div>
                </button>
              );
            })}
            {!showsWhole && inSeason.length > EPISODE_PAGE && (
              <button className="more" onClick={() => setWholeSeason(true)} style={{ padding: 12, borderRadius: 12, background: 'var(--surface)', color: 'var(--accent)', fontWeight: 600, fontSize: 13 }}>
                Show all {inSeason.length} episodes ↓
              </button>
            )}
          </div>
        </>
      )}

        </div>

        <aside className="detail-side">
      <div style={{ marginTop: 20 }}>
        <h2 style={{ fontSize: 20, fontWeight: 600, marginBottom: 12 }}>Releases</h2>
        {!sources && <div className="spinner" style={{ margin: '20px auto' }} />}
        {error && <p className="danger-text" style={{ fontSize: 12 }}>{error}</p>}
        {sources && sources.length === 0 && <p className="center">No releases found yet — try again shortly.</p>}
        {shown?.map((h) => (
          <ReleaseRow
            key={h.id}
            hit={h}
            onClick={() => router.push(watchHref(h.magnet, h.name))}
            right={
              <button className="btn btn-primary" style={{ padding: '8px 14px', fontSize: 12 }} aria-label={`Play ${h.name}`}>
                ▶
              </button>
            }
          />
        ))}
      </div>

      {(title.cast?.length ?? 0) > 0 && (
        <div style={{ marginTop: 22 }}>
          <h2 style={{ fontSize: 20, fontWeight: 600, marginBottom: 12 }}>Cast</h2>
          <div className="shelf-row">
            {title.cast.slice(0, 12).map((name) => (
              <div key={name} style={{ width: 72, textAlign: 'center' }}>
                <div
                  style={{
                    width: 64,
                    height: 64,
                    borderRadius: '50%',
                    margin: '0 auto',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    background: `hsl(${hueOf(name)} 42% 34%)`,
                    color: '#fff',
                    fontWeight: 600,
                    fontSize: 18,
                  }}
                >
                  {name.split(/\s+/).slice(0, 2).map((p) => p[0]?.toUpperCase() ?? '').join('')}
                </div>
                <div style={{ fontSize: 11, color: 'var(--muted)', marginTop: 6, lineHeight: '14px' }}>{name}</div>
              </div>
            ))}
          </div>
        </div>
      )}

      {title.directors?.length > 0 && (
        <p style={{ fontSize: 12, color: 'var(--muted)', marginTop: 14 }}>
          <span style={{ color: 'var(--faint)' }}>Directed by </span>
          {title.directors.join(', ')}
        </p>
      )}
      {title.awards && (
        <div style={{ background: 'var(--warn-soft)', color: 'var(--warn)', fontSize: 12, padding: 12, borderRadius: 12, marginTop: 14 }}>🏆 {title.awards}</div>
      )}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 16 }}>
        {reviewLinks(title).map((l) => (
          <a key={l.label} href={l.url} target="_blank" rel="noreferrer" className="chip" style={{ fontSize: 12, padding: '7px 12px' }}>
            {l.label} ↗
          </a>
        ))}
      </div>
      <p className="faint" style={{ fontSize: 11, marginTop: 18, lineHeight: '16px' }}>
        Peer-to-peer streaming: the Watchly server joins the swarm and streams
        the file to this page with byte-range requests.
        “Save” keeps a copy on your device; releases the browser cannot decode
        are converted on the server as they play.
      </p>
        </aside>
      </div>
    </main>
  );
}

export default function DetailScreen() {
  return (
    <Suspense fallback={<main className="page"><div className="spinner" /></main>}>
      <DetailInner />
    </Suspense>
  );
}