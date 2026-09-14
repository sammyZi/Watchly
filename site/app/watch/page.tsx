'use client';
/**
 * The player. Opens a magnet on the server-side engine (HTTP range requests
 * from /api/torrent), picks the file the release intended, and streams it with
 * resume, subtitles, stats and up-next — and when the browser cannot decode a
 * release, hands it to the ffmpeg.wasm transcode path.
 *
 * Layout copies the Android player (src/screens/PlayerScreen.tsx): everything
 * floats over the picture, transport in the centre, scrubber and a labelled
 * action row at the bottom, track sheet top right.
 */
import { Suspense, useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { getTorrent, hashOf, liveStats, type Engine } from '../../lib/torrent';
import { pickFile, nextEpisode } from '../../lib/pick';
import {
  BackIcon,
  CcIcon,
  CheckIcon,
  DownloadIcon,
  FullscreenIcon,
  LockIcon,
  NextIcon,
  PauseIcon,
  PlayIcon,
  ScaleIcon,
  SearchIcon,
  SkipIcon,
  UsersIcon,
  ZapIcon,
} from '../../components/Blocks';
import {
  loadHistory,
  put as putHistory,
  setPosition,
  setDuration,
  setFileSubs,
  type Item,
  type StoredFile,
} from '../../lib/history';
import { linkEpisode } from '../../lib/watched';
import { loadSettings } from '../../lib/settings';
import { withTrackers } from '../../lib/magnet';
import { searchEpisode, type Hit } from '../../lib/search';
import { downloadSubtitle, findSubtitles, subtitleBlob, type Sub } from '../../lib/subs';
import { saveUrlToDisk, supportsSaveAPI, pickLocalFile } from '../../lib/save';
import { getFFmpeg, transcode, type Job, type TranscodeHandle } from '../../lib/ffmpeg';
import { searchableName, titleOf } from '../../lib/title';
import { clock, bytes, speed, left } from '../../lib/format';

/** Same prefix style as the engine, so one console reads as one session. */
const log = (...parts: unknown[]) => console.log('[player]', ...parts);

const SKIP_SECONDS = 10;
/** How long before the end the "up next" card appears. */
const UP_NEXT_SECONDS = 30;
const RATES = [0.75, 1, 1.25, 1.5, 2];
const SCALES = [
  { fit: 'contain', label: 'Best fit' },
  { fit: 'fill', label: 'Stretch' },
  { fit: 'cover', label: 'Crop' },
  { fit: 'none', label: 'Original' },
] as const;
/** Controls hide after this long without a pointer move while playing. */
const HIDE_MS = 4000;

/** A subtitle the sheet can offer: an in-torrent file, or a SubDL download. */
type SubOption = { label: string; url: string; online: boolean; meta?: string };

/** SubDL links are .zip; the server hands back the .srt inside. */
const onlineOption = (s: Sub): SubOption => ({
  label: `${(s.release || s.name || 'subtitle').replace(/\.(zip|srt)$/i, '')}.srt`,
  url: s.url,
  online: true,
  meta: [s.language || 'EN', 'SubDL', s.season != null ? `S${s.season}` : null, s.episode != null ? `E${s.episode}` : null]
    .filter(Boolean)
    .join(' · '),
});

function PlayerInner() {
  const params = useSearchParams();
  const router = useRouter();
  const uri = params.get('uri') ?? '';
  const tag = params.get('tag') ?? undefined;
  const imdb = params.get('imdb') ?? undefined;
  const kind = (params.get('kind') as 'movie' | 'tv') || 'movie';
  const titleName = params.get('titleName') ?? undefined;
  const poster = params.get('poster') ?? undefined;
  const epId = params.get('epId') ?? undefined;
  const season = params.get('season') ? Number(params.get('season')) : undefined;
  const episode = params.get('episode') ? Number(params.get('episode')) : undefined;
  const epName = params.get('epName') ?? undefined;
  const dlOnOpen = params.get('dl') === '1';

  const videoRef = useRef<HTMLVideoElement>(null);
  const [engine, setEngine] = useState<Engine | null>(null);
  const [stage, setStage] = useState<'meta' | 'ready' | 'error'>('meta');
  const [error, setError] = useState<string | null>(null);
  const [fileIndex, setFileIndex] = useState<number | null>(null);
  const [historyItem, setHistoryItem] = useState<Item | null>(null);
  const [playing, setPlaying] = useState(false);
  const [buffering, setBuffering] = useState(false);
  const [time, setTime] = useState(0);
  const [duration, setDurationState] = useState(0);
  const [buffered, setBuffered] = useState(0);
  const [showUi, setShowUi] = useState(true);
  const [rate, setRate] = useState(1);
  const [scaleIdx, setScaleIdx] = useState(0);
  const [locked, setLocked] = useState(false);
  const [stats, setStats] = useState({ progress: 0, dl: 0, ul: 0, peers: 0 });
  const [subs, setSubs] = useState<SubOption[]>([]);
  const [activeSub, setActiveSub] = useState<{ url: string; label: string } | null>(null);
  const [picking, setPicking] = useState(false);
  /** Why the subtitle list is empty (no server key, SubDL down, nothing found). */
  const [subNote, setSubNote] = useState<string | null>(null);
  /** The subtitle being downloaded, for its spinner. */
  const [subBusy, setSubBusy] = useState<string | null>(null);
  const [search, setSearch] = useState<{
    open: boolean;
    query: string;
    busy: boolean;
    results: SubOption[];
    note: string | null;
  }>({ open: false, query: '', busy: false, results: [], note: null });
  const [universal, setUniversal] = useState<'off' | 'prep' | 'running'>('off');
  const [transcodeState, setTranscodeState] = useState<TranscodeHandle | null>(null);
  const [saveState, setSaveState] = useState<{ percent: number; speed: number } | null>(null);
  const [nextUp, setNextUp] = useState<{ index: number; name: string } | null>(null);
  const [nextReleases, setNextReleases] = useState<Hit[] | null>(null);
  const [dismissedNext, setDismissedNext] = useState(false);
  /** Bumped by every interaction, so the hide timer restarts. */
  const [poke, setPoke] = useState(0);

  const video = () => videoRef.current;
  const bumpUi = useCallback(() => {
    setShowUi(true);
    setPoke((n) => n + 1);
  }, []);

  const exit = useCallback(() => {
    if (window.history.length > 1) router.back();
    else router.push('/');
  }, [router]);

  const togglePlay = useCallback(() => {
    const v = videoRef.current;
    if (!v) return;
    if (v.paused) void v.play();
    else v.pause();
  }, []);

  const skip = useCallback((by: number) => {
    const v = videoRef.current;
    if (!v) return;
    v.currentTime = Math.max(0, Math.min(v.duration || 0, v.currentTime + by));
  }, []);

  const toggleFullscreen = useCallback(() => {
    void (document.fullscreenElement
      ? document.exitFullscreen()
      : document.documentElement.requestFullscreen());
  }, []);

  /**
   * Keyboard control: space/k to pause, arrows to scrub and set volume, f for
   * fullscreen, m to mute. Ignored while typing and while the controls are locked.
   */
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null;
      if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) {
        return;
      }
      const v = videoRef.current;
      if (!v || locked) return;
      switch (e.key) {
        case ' ':
        case 'k':
          e.preventDefault();
          togglePlay();
          break;
        case 'ArrowLeft':
          e.preventDefault();
          skip(-SKIP_SECONDS);
          break;
        case 'ArrowRight':
          e.preventDefault();
          skip(SKIP_SECONDS);
          break;
        case 'ArrowUp':
          e.preventDefault();
          v.volume = Math.min(1, v.volume + 0.1);
          break;
        case 'ArrowDown':
          e.preventDefault();
          v.volume = Math.max(0, v.volume - 0.1);
          break;
        case 'm':
          v.muted = !v.muted;
          break;
        case 'f':
          toggleFullscreen();
          break;
        case 'Escape':
          setPicking(false);
          setSearch((s) => ({ ...s, open: false }));
          break;
        default:
          return;
      }
      bumpUi();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [locked, togglePlay, skip, toggleFullscreen, bumpUi]);

  /** Seek to the saved position once the new source knows its length. */
  const resumeAt = useCallback((v: HTMLVideoElement, pos: number | undefined) => {
    if (!pos) return;
    v.addEventListener(
      'loadedmetadata',
      () => {
        if (pos > 0 && pos < (v.duration || 1) - 5) v.currentTime = pos;
      },
      { once: true }
    );
  }, []);

  /** Attach the picked file to the element once both exist. */
  useEffect(() => {
    const v = videoRef.current;
    if (!engine || fileIndex == null || !v) return;
    const f = engine.findFile(fileIndex);
    if (!f?.streamURL || v.getAttribute('src')) return;
    v.src = f.streamURL;
    if (!params.get('fresh')) resumeAt(v, historyItem?.positions?.[fileIndex]);
    void v.play().catch(() => {});
  }, [engine, fileIndex, historyItem, params, stage, resumeAt]);

  /** Open the swarm, pick the file, load resume + subtitles. */
  useEffect(() => {
    if (!uri) {
      setError('Nothing to play — no torrent given.');
      setStage('error');
      return;
    }
    let live = true;
    let magnet = uri;
    setStage('meta');
    // The trackers setting has to be read before the add, not after — the
    // open used to race it and always injected trackers.
    loadSettings()
      .then((s) => {
        magnet = s.addTrackers ? withTrackers(uri) : uri;
        return getTorrent(magnet);
      })
      .then(async (e) => {
        if (!live) return;
        log(`opened ${e.torrentName} — ${e.files.length} file(s)`);
        setEngine(e);
        const files = e.files;
        // `file` comes from Continue watching: the exact file that was playing.
        const asked = params.get('file');
        const picked =
          asked != null && files.some((f) => f.index === Number(asked))
            ? Number(asked)
            : pickFile(
                files.map((f) => ({ index: f.index, name: f.name, size: f.size, isVideo: f.isVideo })),
                tag
              );
        // By hash as well: the same torrent reached with a different tracker
        // list must still find its resume point.
        const hash = hashOf(magnet);
        const item = (await loadHistory()).find(
          (x) => x.uri === magnet || (!!hash && hashOf(x.uri) === hash)
        ) ?? {
          uri: magnet,
          title: e.torrentName || titleOf(uri),
          at: Date.now(),
          torrentName: e.torrentName,
        };
        const files2: StoredFile[] = files.map((f) => ({
          index: f.index,
          name: f.name,
          size: f.size,
          isVideo: f.isVideo,
          isSubtitle: f.isSubtitle,
        }));
        // What the title page and Home match on — without these, resume from a
        // title page and Continue-watching artwork never worked.
        const known: Partial<Item> = {
          ...(imdb && { imdb }),
          ...(params.get('kind') && { kind }),
          ...(titleName && { titleName }),
          ...(poster && { poster }),
        };
        const stored: Item = { ...item, ...known, files: files2, torrentName: e.torrentName, at: Date.now() };
        await putHistory(stored);
        if (epId) await linkEpisode(epId, item.uri);
        setHistoryItem(stored);
        if (picked != null) {
          log('playing file', picked, e.files[picked]?.name);
          setFileIndex(picked);
        } else {
          log('no playable file found in', e.torrentName);
        }
        if (dlOnOpen) {
          const f = files[picked ?? 0];
          if (f?.streamURL) doSave(f);
        }
        setStage('ready');
      })
      .catch((err) => {
        if (!live) return;
        log('open failed:', err?.message ?? err);
        setError(String(err?.message ?? err));
        setStage('error');
      });
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [uri]);

  const doSave = useCallback(
    async (f: { streamURL: string | null; name: string; index: number }) => {
      if (!f.streamURL) return;
      if (!supportsSaveAPI()) {
        setError('Your browser cannot write files to disk — use a Chromium browser.');
        return;
      }
      setSaveState({ percent: 0, speed: 0 });
      try {
        const { path, size } = await saveUrlToDisk(f.streamURL, f.name, (p) =>
          setSaveState({ percent: p.percent, speed: p.speed })
        );
        setSaveState({ percent: 1, speed: 0 });
        await putHistory({
          uri: `saved:${Date.now()}:${f.name}`,
          title: f.name,
          at: Date.now(),
          files: [{ index: 0, name: path, size, isVideo: true, isSubtitle: false }] as StoredFile[],
        });
      } catch (e) {
        setError(
          e instanceof Error && e.name === 'AbortError'
            ? 'Save cancelled.'
            : `Save failed: ${String(e)}`
        );
      }
      setSaveState(null);
    },
    []
  );

  /** Resume/duration checkpointing. */
  const flush = useCallback(() => {
    const v = video();
    const item = historyItem;
    if (!v || !item || fileIndex == null) return;
    if (Number.isFinite(v.duration) && v.duration > 0)
      void setDuration(item, fileIndex, v.duration);
    void setPosition(item, fileIndex, v.currentTime);
  }, [historyItem, fileIndex]);

  useEffect(() => {
    const t = setInterval(flush, 5000);
    const onLeave = () => flush();
    window.addEventListener('beforeunload', onLeave);
    document.addEventListener('visibilitychange', onLeave);
    return () => {
      clearInterval(t);
      window.removeEventListener('beforeunload', onLeave);
      document.removeEventListener('visibilitychange', onLeave);
      flush();
    };
  }, [flush]);

  /** Point the video at the chosen file. */
  const switchFile = useCallback(
    (index: number) => {
      if (!engine) return;
      const url = engine.findFile(index)?.streamURL;
      if (!url) return;
      setFileIndex(index);
      const v = video();
      if (v) {
        v.src = url;
        resumeAt(v, historyItem?.positions?.[index]);
        void v.play().catch(() => {});
      }
      setActiveSub(null);
      setSubs([]);
      setError(null);
      setDismissedNext(false);
    },
    [engine, historyItem, resumeAt]
  );

  /** What SubDL is asked for: exact IMDb id when known, else a cleaned name. */
  const subQueryFor = useCallback(
    (query?: string) => ({
      query: query ?? titleName ?? searchableName(engine?.torrentName ?? ''),
      // A typed query means the id was the wrong title — search by name.
      imdb: query ? undefined : imdb,
      kind,
      season,
      episode,
    }),
    [engine, titleName, imdb, kind, season, episode]
  );

  const applySub = useCallback(
    async (s: SubOption) => {
      if (/\.(ass|ssa)$/i.test(s.label)) {
        // <track> only speaks WebVTT, and ASS styling does not survive the trip.
        setError('ASS/SSA subtitles are not supported in the browser — pick an SRT.');
        return;
      }
      setSubBusy(s.url);
      try {
        const text = s.online
          ? await downloadSubtitle(s.url, { season, episode })
          : await (await fetch(s.url)).text();
        setActiveSub({ url: subtitleBlob(text), label: s.label });
        if (historyItem && fileIndex != null)
          void setFileSubs(historyItem, fileIndex, [{ path: s.url, label: s.label }]);
      } catch (e) {
        setError(`Subtitle failed: ${String((e as Error)?.message ?? e)}`);
      }
      setSubBusy(null);
    },
    [historyItem, fileIndex, season, episode]
  );

  /**
   * Subtitle candidates: in-torrent files, then SubDL. The one chosen last time
   * for this file is put back on — it used to be saved and never restored.
   */
  useEffect(() => {
    if (!engine || fileIndex == null) return;
    let live = true;
    const local: SubOption[] = engine.files
      .filter((x) => x.isSubtitle)
      .map((f) => ({ label: f.name.split('/').pop() ?? f.name, url: f.streamURL, online: false }));
    setSubs(local);
    setSubNote(null);

    const saved = historyItem?.subtitles?.[fileIndex]?.[0];
    if (saved?.path) {
      void applySub({ label: saved.label, url: saved.path, online: saved.path.startsWith('https://') });
    }

    findSubtitles(subQueryFor())
      .then((found) => {
        if (!live) return;
        setSubs([...local, ...found.map(onlineOption)]);
        if (!found.length && !local.length) setSubNote('No subtitles found. Try Find subs with another name.');
      })
      .catch((e) => {
        if (live) setSubNote(String((e as Error)?.message ?? e));
      });
    return () => {
      live = false;
    };
    // Once per file; historyItem changes on every checkpoint.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [engine, fileIndex]);

  /** Blob URLs hold the whole subtitle in memory until revoked. */
  useEffect(() => {
    const url = activeSub?.url;
    return () => {
      if (url) URL.revokeObjectURL(url);
    };
  }, [activeSub]);

  /** Find subs sheet: search by any name. */
  const runSubSearch = useCallback(
    async (query: string) => {
      setSearch((s) => ({ ...s, busy: true, note: null }));
      try {
        const found = await findSubtitles(subQueryFor(query.trim() || undefined));
        setSearch((s) => ({
          ...s,
          busy: false,
          results: found.map(onlineOption),
          note: found.length ? null : 'No subtitles found',
        }));
      } catch (e) {
        setSearch((s) => ({ ...s, busy: false, results: [], note: String((e as Error)?.message ?? e) }));
      }
    },
    [subQueryFor]
  );

  /** Show the chosen track; hide the rest — tracks stick to the element. */
  useEffect(() => {
    const v = video();
    if (!v) return;
    for (const t of Array.from(v.textTracks)) {
      if (t.kind === 'subtitles') t.mode = activeSub ? 'showing' : 'hidden';
    }
  }, [activeSub]);

  /** Swarm stats, once a second. */
  useEffect(() => {
    if (!engine) return;
    const t = setInterval(async () => {
      const s = await liveStats(engine.infoHash);
      if (s) setStats({ progress: s.progress, dl: s.downloadSpeed, ul: s.uploadSpeed, peers: s.numPeers });
    }, 1000);
    return () => clearInterval(t);
  }, [engine]);

  /** Peer count while the metadata is still being chased. */
  const [waiting, setWaiting] = useState<{ peers: number; seconds: number } | null>(null);
  useEffect(() => {
    const hash = hashOf(uri);
    if (stage !== 'meta' || !hash) return;
    const started = Date.now();
    const tick = setInterval(async () => {
      const s = await liveStats(hash);
      setWaiting({ peers: s?.numPeers ?? 0, seconds: Math.round((Date.now() - started) / 1000) });
    }, 1000);
    return () => clearInterval(tick);
  }, [stage, uri]);

  /** Controls auto-hide while playing, unless a sheet is open. */
  useEffect(() => {
    if (!playing || picking || search.open || !showUi) return;
    const t = setTimeout(() => setShowUi(false), HIDE_MS);
    return () => clearTimeout(t);
  }, [playing, picking, search.open, showUi, poke]);

  /** Up next: inside the torrent first, then a release search for the next ep. */
  useEffect(() => {
    if (!engine || fileIndex == null) return;
    const next = nextEpisode(
      engine.files.map((f) => ({ index: f.index, name: f.name, size: f.size, isVideo: f.isVideo })),
      fileIndex
    );
    setNextReleases(null);
    if (next) {
      setNextUp({ index: next.index, name: next.name.split('/').pop() ?? next.name });
      return;
    }
    setNextUp(null);
    if (!imdb || season == null || episode == null) return;
    let live = true;
    searchEpisode(titleName ?? imdb, season, episode + 1, undefined, imdb).then((hits) => {
      if (live) setNextReleases(hits);
    });
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [engine, fileIndex, imdb, season, episode]);

  const goNextRelease = (h: Hit) => {
    const q = new URLSearchParams({ uri: h.magnet, name: h.name, kind });
    if (titleName) q.set('titleName', titleName);
    if (poster) q.set('poster', poster);
    if (imdb) {
      q.set('imdb', imdb);
      q.set('epId', `${imdb}:${season}:${episode! + 1}`);
    }
    if (season != null) q.set('season', String(season));
    if (episode != null) q.set('episode', String(episode + 1));
    q.set('epName', `Episode ${episode! + 1}`);
    window.location.href = `/watch?${q}`;
  };

  // ponytail: best-seeded release for the next episode, no chooser; add one if picks go wrong.
  const next = nextUp
    ? { name: nextUp.name, go: () => switchFile(nextUp.index) }
    : nextReleases?.length && episode != null
      ? { name: `Episode ${episode + 1}`, go: () => goNextRelease(nextReleases[0]) }
      : null;

  /** Universal mode: transcode this file with ffmpeg.wasm. */
  const runUniversal = useCallback(
    async (job: Job) => {
      if (!engine || fileIndex == null) return;
      const f = engine.findFile(fileIndex);
      if (!f) return;
      setUniversal('prep');
      setTranscodeState(null);
      setError(null);
      try {
        await getFFmpeg();
        const file =
          f.size <= 1.5 * 1024 ** 3 ? (await engine.blobOf(f)).file : await pickLocalFile();
        if (!file) {
          setUniversal('off');
          return;
        }
        if (file.size > 2.5 * 1024 ** 3) {
          setError('Files over 2.5 GB are too big for in-browser ffmpeg — pick a smaller release.');
          setUniversal('off');
          return;
        }
        setUniversal('running');
        const url = await transcode(file, file.name, job, setTranscodeState);
        const v = video();
        if (v) {
          v.src = url;
          resumeAt(v, historyItem?.positions?.[fileIndex]);
          void v.play().catch(() => {});
        }
        setUniversal('off');
      } catch (e) {
        setUniversal('off');
        setError(`Universal encode failed: ${String((e as Error)?.message ?? e)}`);
      }
    },
    [engine, fileIndex, historyItem, resumeAt]
  );

  const file = engine && fileIndex != null ? engine.findFile(fileIndex) : null;
  const title =
    epName ?? titleName ?? file?.name.split('/').pop() ?? engine?.torrentName ?? params.get('name') ?? titleOf(uri);

  if (stage !== 'ready' || !engine) {
    return (
      <div className="pl-screen">
        <button className="pl-round pl-exit" aria-label="Back" onClick={exit}>
          <BackIcon />
        </button>
        <div className="pl-preparing">
          {!!title && <div className="pl-prep-title">{title}</div>}
          {stage === 'error' ? (
            <div className="pl-pill pl-pill-bad">{error ?? 'Missing torrent.'}</div>
          ) : (
            <>
              <div className="pl-spinner" />
              <div className="pl-prep-text">
                {waiting && waiting.peers > 0 ? 'Reading torrent' : 'Finding peers'}
              </div>
              {waiting && (
                <div className="pl-prep-meta">
                  {waiting.peers} peer{waiting.peers === 1 ? '' : 's'} · {waiting.seconds}s
                </div>
              )}
            </>
          )}
        </div>
      </div>
    );
  }

  const upNextVisible =
    !!next && !dismissedNext && duration > 0 && duration - time <= UP_NEXT_SECONDS;
  const cannotDecode = !!error && error.startsWith('The browser cannot play');

  return (
    <div
      className={`pl-screen${showUi ? '' : ' pl-idle'}`}
      onMouseMove={bumpUi}
    >
      <video
        ref={videoRef}
        className="pl-video"
        style={{ objectFit: SCALES[scaleIdx].fit }}
        playsInline
        onPlay={() => setPlaying(true)}
        onPause={() => {
          setPlaying(false);
          setShowUi(true);
        }}
        onWaiting={() => setBuffering(true)}
        onPlaying={() => setBuffering(false)}
        onCanPlay={() => setBuffering(false)}
        onTimeUpdate={(e) => setTime(e.currentTarget.currentTime)}
        onDurationChange={(e) => {
          const d = e.currentTarget.duration;
          if (Number.isFinite(d)) {
            setDurationState(d);
            if (historyItem && fileIndex != null) void setDuration(historyItem, fileIndex, d);
          }
        }}
        onProgress={(e) => {
          const v = e.currentTarget;
          for (let i = 0; i < v.buffered.length; i++) {
            if (v.buffered.start(i) <= v.currentTime && v.currentTime <= v.buffered.end(i)) {
              setBuffered(v.buffered.end(i));
            }
          }
        }}
        onEnded={() => {
          if (next && !dismissedNext) next.go();
        }}
        onError={(e) => {
          const v = e.currentTarget;
          if (!v.error || universal !== 'off') return;
          log('video error', v.error.code, v.error.message);
          setBuffering(false);
          setError(`The browser cannot play this release (${v.error.message || 'unsupported format'}).`);
        }}
      >
        {/* Inside the element, or the browser never attaches it. */}
        {activeSub && <track key={activeSub.url} kind="subtitles" src={activeSub.url} default />}
      </video>

      {/* Tap target for the picture: toggles the controls, like the phone. */}
      <div
        className={`pl-tap${showUi && !locked ? ' pl-dim' : ''}`}
        onClick={() => {
          if (picking || search.open) {
            setPicking(false);
            setSearch((s) => ({ ...s, open: false }));
          } else if (showUi) setShowUi(false);
          else bumpUi();
        }}
        onDoubleClick={toggleFullscreen}
      />

      {showUi && locked && (
        <div className="pl-lockdock">
          <button className="pl-round pl-lock" aria-label="Unlock controls" onClick={() => { setLocked(false); bumpUi(); }}>
            <LockIcon on />
          </button>
        </div>
      )}

      {showUi && !locked && (
        <div className="pl-overlay">
          <div className="pl-top">
            <button className="pl-round" aria-label="Back" onClick={exit}>
              <BackIcon />
            </button>
            <div className="pl-title">{title}</div>
            <div className="pl-stats" title={`${Math.round(stats.progress * 100)}% downloaded`}>
              <UsersIcon />
              <span>{stats.peers}</span>
              <DownloadIcon size={12} />
              <span className="pl-speed">{speed(stats.dl)}</span>
            </div>
          </div>

          <div className="pl-center">
            <button className="pl-skip" aria-label={`Back ${SKIP_SECONDS} seconds`} onClick={() => { skip(-SKIP_SECONDS); bumpUi(); }}>
              <SkipIcon back />
            </button>
            {buffering ? (
              <div className="pl-play">
                <div className="pl-spinner" />
              </div>
            ) : (
              <button className="pl-play" aria-label={playing ? 'Pause' : 'Play'} onClick={() => { togglePlay(); bumpUi(); }}>
                {playing ? <PauseIcon size={28} /> : <PlayIcon size={28} />}
              </button>
            )}
            <button className="pl-skip" aria-label={`Forward ${SKIP_SECONDS} seconds`} onClick={() => { skip(SKIP_SECONDS); bumpUi(); }}>
              <SkipIcon />
            </button>
          </div>

          <div className="pl-bottom">
            <div className="pl-seekrow">
              <span className="pl-time">{clock(time)}</span>
              <SeekBar
                value={duration ? time / duration : 0}
                buffered={duration ? buffered / duration : 0}
                onSeek={(ratio) => {
                  const v = video();
                  if (v && duration) v.currentTime = ratio * duration;
                  bumpUi();
                }}
              />
              <span className="pl-time pl-time-end">{clock(duration)}</span>
            </div>

            <div className="pl-actions">
              <Action label="Lock" onClick={() => { setLocked(true); setPicking(false); bumpUi(); }}>
                <LockIcon on />
              </Action>
              {next && (
                <Action label="Next" onClick={next.go}>
                  <NextIcon />
                </Action>
              )}
              <Action
                label={SCALES[scaleIdx].label}
                on={scaleIdx !== 0}
                onClick={() => { setScaleIdx((i) => (i + 1) % SCALES.length); bumpUi(); }}
              >
                <ScaleIcon />
              </Action>
              <Action
                label="Subtitles"
                on={!!activeSub}
                onClick={() => { setSearch((s) => ({ ...s, open: false })); setPicking((p) => !p); bumpUi(); }}
              >
                <CcIcon />
              </Action>
              <Action
                label="Find subs"
                on={search.open}
                onClick={() => {
                  setPicking(false);
                  const open = !search.open;
                  const query = search.query || titleName || searchableName(engine.torrentName);
                  setSearch((s) => ({ ...s, open, query }));
                  if (open && !search.results.length) void runSubSearch('');
                  bumpUi();
                }}
              >
                <SearchIcon />
              </Action>
              <Action
                label="Speed"
                on={rate !== 1}
                onClick={() => {
                  const r = RATES[(RATES.indexOf(rate) + 1) % RATES.length];
                  const v = video();
                  if (v) v.playbackRate = r;
                  setRate(r);
                  bumpUi();
                }}
              >
                <span className="pl-action-text">{rate}x</span>
              </Action>
              {file && (
                <Action
                  label="Save"
                  onClick={() => doSave({ streamURL: file.streamURL, name: file.name.split('/').pop() ?? file.name, index: file.index })}
                >
                  <DownloadIcon />
                </Action>
              )}
              {file && (
                <Action label="Universal" on={universal !== 'off'} onClick={() => runUniversal('fast')}>
                  <ZapIcon />
                </Action>
              )}
              <Action label="Fullscreen" onClick={toggleFullscreen}>
                <FullscreenIcon />
              </Action>
            </div>
          </div>
        </div>
      )}

      {picking && (
        <div className="pl-sheet">
          <div className="pl-sheet-title">Subtitles</div>
          <TrackOption
            label="Off"
            active={!activeSub}
            onClick={() => {
              setActiveSub(null);
              if (historyItem && fileIndex != null) void setFileSubs(historyItem, fileIndex, []);
              setPicking(false);
            }}
          />
          {subs.map((s) => (
            <TrackOption
              key={s.url}
              label={s.label}
              note={s.meta}
              busy={subBusy === s.url}
              active={activeSub?.label === s.label}
              onClick={() => { void applySub(s); setPicking(false); }}
            />
          ))}
          {subs.length === 0 && <div className="pl-nosubs">{subNote ?? 'Looking for subtitles…'}</div>}
        </div>
      )}

      {search.open && (
        <div className="pl-sheet pl-search">
          <div className="pl-sheet-title">Find subtitles</div>
          <form
            className="pl-search-row"
            onSubmit={(e) => {
              e.preventDefault();
              void runSubSearch(search.query);
            }}
          >
            <input
              autoFocus
              value={search.query}
              placeholder="Search by title…"
              onChange={(e) => setSearch((s) => ({ ...s, query: e.target.value }))}
            />
            <button type="submit" className="pl-search-btn" aria-label="Search">
              {search.busy ? <div className="pl-spinner pl-spinner-sm" /> : <SearchIcon />}
            </button>
          </form>
          {search.results.map((s) => (
            <TrackOption
              key={s.url}
              label={s.label}
              note={s.meta}
              busy={subBusy === s.url}
              active={activeSub?.label === s.label}
              onClick={async () => {
                await applySub(s);
                setSubs((all) => (all.some((x) => x.url === s.url) ? all : [...all, s]));
                setSearch((x) => ({ ...x, open: false }));
              }}
            />
          ))}
          {!search.busy && search.note && <div className="pl-nosubs">{search.note}</div>}
        </div>
      )}

      {fileIndex == null && (
        <div className="pl-sheet pl-sheet-center">
          <div className="pl-sheet-title">Choose a file</div>
          {engine.files.filter((f) => f.isVideo).map((f) => (
            <TrackOption
              key={f.index}
              label={f.name.split('/').pop() ?? f.name}
              note={bytes(f.size)}
              active={false}
              onClick={() => switchFile(f.index)}
            />
          ))}
        </div>
      )}

      {/* When the controls are hidden the overlay's spinner is too. */}
      {buffering && !showUi && (
        <div className="pl-waiting">
          <div className="pl-spinner" />
          <div className="pl-prep-text">Buffering</div>
          <div className="pl-prep-meta">
            {speed(stats.dl)} · {stats.peers} peers
          </div>
        </div>
      )}

      {upNextVisible && (
        <div className="pl-upnext">
          <div className="pl-upnext-text">
            <div className="pl-upnext-label">Up next in {Math.max(0, Math.ceil(duration - time))}s</div>
            <div className="pl-upnext-name">{next!.name}</div>
          </div>
          <button className="pl-upnext-cancel" onClick={() => setDismissedNext(true)}>
            Cancel
          </button>
          <button className="pl-upnext-play" onClick={next!.go}>
            <PlayIcon size={14} />
            Play
          </button>
        </div>
      )}

      {!!error && (
        <div className="pl-pill pl-pill-bad pl-failure">
          <span>{error}</span>
          {cannotDecode && (
            <button className="pl-pill-btn" onClick={() => runUniversal('fast')}>
              Try Universal
            </button>
          )}
          <button className="pl-pill-x" aria-label="Dismiss" onClick={() => setError(null)}>
            ×
          </button>
        </div>
      )}

      {saveState && (
        <div className="pl-modal">
          <div className="pl-modal-card">
            <div className="pl-modal-title">Saving to disk</div>
            <div className="pl-bar">
              <div style={{ width: `${saveState.percent * 100}%` }} />
            </div>
            <div className="pl-prep-meta">
              {Math.round(saveState.percent * 100)}% · {speed(saveState.speed)}
            </div>
          </div>
        </div>
      )}

      {universal !== 'off' && (
        <div className="pl-modal">
          <div className="pl-modal-card">
            <div className="pl-modal-title">
              {universal === 'prep' ? 'Preparing Universal mode' : 'Converting for the browser'}
            </div>
            {transcodeState ? (
              <>
                <div className="pl-bar">
                  <div style={{ width: `${transcodeState.progress * 100}%` }} />
                </div>
                <div className="pl-prep-meta">
                  {Math.round(transcodeState.progress * 100)}% · {clock(transcodeState.time)} processed
                  {duration > 0 && ` · ${left(Math.max(0, (duration - transcodeState.time) * 3))}`}
                </div>
              </>
            ) : (
              <div className="pl-spinner" style={{ margin: '14px auto' }} />
            )}
            <div className="pl-modal-note">
              Video is kept as-is and audio converted to AAC. Bitmap subtitles (PGS/VOBSUB) can’t be converted.
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/** One control in the bottom row: glyph over a label, tinted when active. */
function Action({
  label,
  on,
  onClick,
  children,
}: {
  label: string;
  on?: boolean;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <button className={`pl-action${on ? ' on' : ''}`} aria-label={label} onClick={onClick}>
      <span className="pl-action-glyph">{children}</span>
      <span className="pl-action-label">{label}</span>
    </button>
  );
}

function TrackOption({
  label,
  note,
  active,
  busy,
  onClick,
}: {
  label: string;
  note?: string;
  active: boolean;
  busy?: boolean;
  onClick: () => void;
}) {
  return (
    <button className={`pl-option${active ? ' on' : ''}`} onClick={onClick} title={label}>
      <span className="pl-option-body">
        <span className="pl-option-text">{label}</span>
        {note && <span className="pl-option-note">{note}</span>}
      </span>
      {busy ? <span className="pl-spinner pl-spinner-sm" /> : active && <CheckIcon />}
    </button>
  );
}

/** Thin scrubber — click anywhere on the track, or drag the head, to seek. */
function SeekBar({
  value,
  buffered,
  onSeek,
}: {
  value: number;
  buffered: number;
  onSeek: (ratio: number) => void;
}) {
  const [drag, setDrag] = useState<number | null>(null);
  const ratio = (e: React.PointerEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
  };
  const pct = Math.max(0, Math.min(1, drag ?? value)) * 100;
  return (
    <div
      className={`pl-scrub${drag != null ? ' dragging' : ''}`}
      onPointerDown={(e) => {
        e.currentTarget.setPointerCapture(e.pointerId);
        setDrag(ratio(e));
      }}
      onPointerMove={(e) => {
        if (drag != null) setDrag(ratio(e));
      }}
      onPointerUp={(e) => {
        if (drag != null) onSeek(ratio(e));
        setDrag(null);
      }}
      onPointerCancel={() => setDrag(null)}
    >
      <div className="pl-track">
        <div className="pl-buffered" style={{ width: `${Math.min(1, buffered) * 100}%` }} />
        <div className="pl-fill" style={{ width: `${pct}%` }} />
      </div>
      <div className="pl-knob" style={{ left: `${pct}%` }} />
    </div>
  );
}

export default function PlayerScreen() {
  return (
    <Suspense fallback={<div className="pl-screen" />}>
      <PlayerInner />
    </Suspense>
  );
}
