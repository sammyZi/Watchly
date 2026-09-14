'use client';
/**
 * The player. Opens a magnet on the server-side engine (HTTP range requests
 * from /api/torrent), picks the file the release intended, and streams it with
 * resume, subtitles, stats and up-next — and when the browser cannot decode a
 * release, or another audio track is picked, switches to the server's ffmpeg
 * conversion (/play), which streams as it converts.
 *
 * Layout copies the Android player (src/screens/PlayerScreen.tsx): everything
 * floats over the picture, transport in the centre, scrubber and a labelled
 * action row at the bottom, track sheet top right.
 */
import { Suspense, useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { getTorrent, hashOf, liveStats, releaseTorrent, type Engine } from '../../lib/torrent';
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
  SoundIcon,
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
import { downloadSubtitle, findSubtitles, parseVtt, subtitleBlob, type Cue, type Sub } from '../../lib/subs';
import { saveUrlToDisk, supportsSaveAPI } from '../../lib/save';
import type { Probe } from '../../lib/probe';
import { searchableName, titleOf } from '../../lib/title';
import { clock, bytes, speed } from '../../lib/format';

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
/** Built-in subtitles are extracted this many seconds at a time… */
const SUB_WINDOW = 300;
/** …and the next window is fetched when playback is this close to the end. */
const SUB_READAHEAD = 90;

/** A subtitle the sheet can offer: an in-torrent file, a track built into the
 *  video (extracted by the server), or a SubDL download. */
type SubOption = { label: string; url: string; online: boolean; meta?: string };

/** What /probe answers. */
type Media = Probe & { needsVideoEncode: boolean };

/** Audio a browser decodes. AC-3, E-AC-3, DTS and TrueHD are not — and Chrome
 *  plays such a file silently rather than failing, so it has to be caught
 *  from the probe. */
const BROWSER_AUDIO = /^(aac|mp3|opus|vorbis|flac|pcm_\w+)$/;

/**
 * Server conversion in use. The converted stream starts at zero wherever it
 * was started, so `offset` is where in the film that zero is.
 */
type Convert = { audio: number; encode: boolean; offset: number };

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
  /** Starts silent; the Sound button or `m` unmutes. */
  const [muted, setMuted] = useState(true);
  const [time, setTime] = useState(0);
  const [duration, setDurationState] = useState(0);
  const [buffered, setBuffered] = useState(0);
  const [showUi, setShowUi] = useState(true);
  const [rate, setRate] = useState(1);
  const [scaleIdx, setScaleIdx] = useState(0);
  const [locked, setLocked] = useState(false);
  const [stats, setStats] = useState({ progress: 0, dl: 0, ul: 0, peers: 0 });
  const [subs, setSubs] = useState<SubOption[]>([]);
  /** The subtitle on screen, as text — its blob URL is rebuilt per `convert`
   *  offset so cues stay in sync with a converted stream. */
  const [activeSub, setActiveSub] = useState<{ text: string; label: string } | null>(null);
  const [picking, setPicking] = useState(false);
  /** The subtitle being downloaded, for its spinner. */
  const [subBusy, setSubBusy] = useState<string | null>(null);
  const [search, setSearch] = useState<{
    open: boolean;
    query: string;
    busy: boolean;
    results: SubOption[];
    note: string | null;
  }>({ open: false, query: '', busy: false, results: [], note: null });
  const [media, setMedia] = useState<Media | null>(null);
  const [convert, setConvert] = useState<Convert | null>(null);
  const convertRef = useRef<Convert | null>(null);
  convertRef.current = convert;
  /** A calm message (not an error), e.g. "Converting on the server". */
  const [notice, setNotice] = useState<string | null>(null);
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

  /**
   * Films are mixed far quieter than YouTube, and 5.1 folded to laptop
   * speakers loses more. A compressor (its defaults level speech against
   * explosions) then 2x gain, so full volume is actually loud. Built on the
   * first play — Web Audio needs a user gesture, and an element can only be
   * wired once.
   */
  const audioRef = useRef<AudioContext | null>(null);
  const boostAudio = useCallback(() => {
    const v = videoRef.current;
    if (!v) return;
    if (!audioRef.current) {
      const ctx = new AudioContext();
      const gain = ctx.createGain();
      gain.gain.value = 2;
      ctx.createMediaElementSource(v).connect(ctx.createDynamicsCompressor()).connect(gain).connect(ctx.destination);
      audioRef.current = ctx;
    }
    void audioRef.current.resume();
  }, []);
  useEffect(() => () => void audioRef.current?.close(), []);

  /** Where in the film playback is, in either mode. */
  const clockNow = useCallback(
    () => (convertRef.current?.offset ?? 0) + (videoRef.current?.currentTime ?? 0),
    []
  );

  /** Switch to (or restart) the server conversion at `at` seconds. */
  const startConvert = useCallback(
    (opts: { audio: number; encode: boolean }, at: number) => {
      const v = videoRef.current;
      const f = engine && fileIndex != null ? engine.findFile(fileIndex) : null;
      if (!v || !f) return;
      const start = Math.max(0, Math.floor(at));
      const q = new URLSearchParams({ t: String(start), audio: String(opts.audio) });
      if (opts.encode) q.set('video', 'encode');
      const next = { ...opts, offset: start };
      convertRef.current = next;
      setConvert(next);
      setTime(start);
      setBuffering(true);
      v.src = `${f.streamURL}/play?${q}`;
      void v.play().catch(() => {});
    },
    [engine, fileIndex]
  );

  /** Seek in either mode: a converted stream has no ranges, so it restarts. */
  /**
   * Where a seek is headed until playback gets there. Over a torrent the
   * element keeps reporting the old position while it waits for data, which
   * snapped the slider back until the picture moved.
   */
  const seekTarget = useRef<{ to: number; at: number } | null>(null);

  const seekTo = useCallback(
    (seconds: number) => {
      const v = videoRef.current;
      if (!v) return;
      const end = media?.duration || v.duration || 0;
      const to = Math.max(0, end ? Math.min(end - 1, seconds) : seconds);
      seekTarget.current = { to, at: Date.now() };
      setTime(to);
      const c = convertRef.current;
      if (c) startConvert(c, to);
      else v.currentTime = to;
    },
    [media, startConvert]
  );

  // From the pending target, so quick repeated skips add up instead of each
  // starting from the position the video has not left yet.
  const skip = useCallback(
    (by: number) => seekTo((seekTarget.current?.to ?? clockNow()) + by),
    [seekTo, clockNow]
  );

  /** Fullscreen, and on a phone turn to landscape with it — the lock is only
   *  allowed while fullscreen, and not at all on iOS, hence the catches. */
  const toggleFullscreen = useCallback(async () => {
    const orientation = screen.orientation as ScreenOrientation & {
      lock?: (o: string) => Promise<void>;
    };
    try {
      if (document.fullscreenElement) {
        orientation.unlock?.();
        await document.exitFullscreen();
      } else {
        await document.documentElement.requestFullscreen({ navigationUI: 'hide' });
        if (matchMedia('(pointer: coarse)').matches) await orientation.lock?.('landscape');
      }
    } catch {
      // No fullscreen or orientation API here (iOS Safari): nothing to do.
    }
  }, []);

  /**
   * Touch on the picture, like the Android player: one tap toggles the
   * controls, a double tap on either half seeks ten seconds, and further taps
   * on that side keep adding. The single tap waits a beat so a double tap
   * does not flash the controls on and off. Mouse clicks toggle at once.
   */
  const lastTap = useRef<{ at: number; side: 'left' | 'right' } | null>(null);
  const tapTimer = useRef<number | undefined>(undefined);
  const [nudge, setNudge] = useState<{ side: 'left' | 'right'; total: number; at: number } | null>(null);
  useEffect(() => {
    if (!nudge) return;
    const t = setTimeout(() => setNudge(null), 700);
    return () => clearTimeout(t);
  }, [nudge]);

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
    const src = v.src;
    v.addEventListener(
      'loadedmetadata',
      () => {
        // Only for the source it was meant for — the probe may have switched
        // to a converted stream, which starts at the position and cannot seek.
        if (v.src !== src) return;
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
        // Phones (and Firefox/Safari): hand it to the browser's own download
        // manager. The live read keeps the server copy until it finishes.
        const a = document.createElement('a');
        a.href = `${f.streamURL}?download=1`;
        a.download = f.name;
        document.body.appendChild(a);
        a.click();
        a.remove();
        await putHistory({
          uri: `saved:${Date.now()}:${f.name}`,
          title: f.name,
          at: Date.now(),
          files: [{ index: 0, name: f.name, size: 0, isVideo: true, isSubtitle: false }] as StoredFile[],
        });
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
    // A converted stream reports no real duration and a clock from its own
    // start — the probe and the offset give the film's.
    const d = media?.duration || v.duration;
    if (Number.isFinite(d) && d > 0) void setDuration(item, fileIndex, d);
    void setPosition(item, fileIndex, clockNow());
  }, [historyItem, fileIndex, media, clockNow]);

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
      seekTarget.current = null;
      convertRef.current = null;
      setConvert(null);
      setMedia(null);
      const v = video();
      if (v) {
        v.src = url;
        resumeAt(v, historyItem?.positions?.[index]);
        void v.play().catch(() => {});
      }
      setActiveSub(null);
      setBuiltIn(null);
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
      // Built-in tracks load in windows as the film plays — see the effect below.
      if (s.url.includes('/subtitle?')) {
        const track = Number(new URL(s.url, location.origin).searchParams.get('track')) || 0;
        setActiveSub(null);
        setBuiltIn({ track, label: s.label, url: s.url });
        if (historyItem && fileIndex != null)
          void setFileSubs(historyItem, fileIndex, [{ path: s.url, label: s.label }]);
        return;
      }
      setSubBusy(s.url);
      try {
        let text: string;
        if (s.online) text = await downloadSubtitle(s.url, { season, episode });
        else {
          const res = await fetch(s.url);
          if (!res.ok) throw new Error((await res.json().catch(() => null))?.error ?? `answered ${res.status}`);
          text = await res.text();
        }
        setBuiltIn(null);
        setActiveSub({ text, label: s.label });
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
   * The Subtitles sheet lists the subtitle files that came with the torrent —
   * online results live in Find subs. The one chosen last time for this file
   * (from either place) is put back on.
   */
  useEffect(() => {
    if (!engine || fileIndex == null) return;
    setSubs(
      engine.files
        .filter((x) => x.isSubtitle)
        .map((f) => ({ label: f.name.split('/').pop() ?? f.name, url: f.streamURL, online: false }))
    );
    const saved = historyItem?.subtitles?.[fileIndex]?.[0];
    if (saved?.path) {
      void applySub({ label: saved.label, url: saved.path, online: saved.path.startsWith('https://') });
    }
    // Once per file; historyItem changes on every checkpoint.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [engine, fileIndex]);

  /**
   * What is inside the file — audio tracks, built-in subtitles, codecs — from
   * the server's ffmpeg. Audio the browser cannot decode (AC-3, DTS…) plays
   * silently in Chrome instead of failing, so that gets a hint to turn on
   * Universal.
   */
  useEffect(() => {
    if (!engine || fileIndex == null) return;
    const f = engine.findFile(fileIndex);
    if (!f?.isVideo) return;
    let live = true;
    fetch(`${f.streamURL}/probe`)
      .then((r) => (r.ok ? (r.json() as Promise<Media>) : null))
      .then((m) => {
        if (!live || !m) return;
        setMedia(m);
        if (m.duration) setDurationState(m.duration);
        const first = m.audio[0];
        // Universal stays off by default; only point at it.
        if (first && !BROWSER_AUDIO.test(first.codec) && !convertRef.current) {
          log(`audio is ${first.codec}: may be silent without Universal`);
          setNotice(`This release's audio (${first.codec.toUpperCase()}) may be silent — turn on Universal`);
        }
      })
      .catch(() => {});
    return () => {
      live = false;
    };
    // Once per file.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [engine, fileIndex]);

  /** Built-in text subtitles join the sheet once the probe has found them. */
  useEffect(() => {
    if (!engine || fileIndex == null || !media?.subtitles.length) return;
    const f = engine.findFile(fileIndex);
    if (!f) return;
    const builtIn = media.subtitles.map((t) => ({
      label: t.title || (t.language ? t.language.toUpperCase() : `Track ${t.index + 1}`),
      url: `${f.streamURL}/subtitle?track=${t.index}`,
      online: false,
      meta: ['Built in', t.language?.toUpperCase(), t.codec].filter(Boolean).join(' · '),
    }));
    setSubs((all) => [...all.filter((s) => !s.url.includes('/subtitle?')), ...builtIn]);
  }, [engine, fileIndex, media]);

  /** Pick an audio track: the first plays directly, any other through the
   *  server — a browser only ever plays the first. */
  const pickAudio = useCallback(
    (index: number) => {
      const current = convertRef.current?.audio ?? 0;
      if (index === current) return;
      startConvert({ audio: index, encode: convertRef.current?.encode ?? !!media?.needsVideoEncode }, clockNow());
    },
    [startConvert, clockNow, media]
  );

  /**
   * The subtitle as a blob URL, cue times shifted by the converted stream's
   * offset so they line up with its clock. Revoked when replaced — each one
   * holds the whole subtitle in memory.
   */
  const [subUrl, setSubUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!activeSub) {
      setSubUrl(null);
      return;
    }
    const url = subtitleBlob(activeSub.text, convert?.offset ?? 0);
    setSubUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [activeSub, convert?.offset]);

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
      if (t.kind === 'subtitles' && t !== builtInTrack.current) t.mode = subUrl ? 'showing' : 'hidden';
    }
  }, [subUrl]);

  /**
   * Built-in subtitles, a window at a time.
   *
   * Reading a whole track means reading the whole file (its lines are spread
   * through it), which on a torrent looked like the subtitles were stuck. So
   * the server extracts WINDOW seconds around where playback is, the cues go
   * straight onto one live text track, and the next window is fetched before
   * playback reaches the end of this one. A seek outside what is loaded
   * fetches the window there. Cue times are the film's; they are shifted by
   * the converted stream's offset when there is one.
   */
  const [builtIn, setBuiltIn] = useState<{ track: number; label: string; url: string } | null>(null);
  const builtInTrack = useRef<TextTrack | null>(null);
  const builtInCues = useRef(new Map<string, Cue>());
  const builtInLoaded = useRef<{ from: number; to: number }[]>([]);
  const builtInLoading = useRef<AbortController | null>(null);
  const [builtInWaiting, setBuiltInWaiting] = useState(false);

  /** Redraw every known cue onto the track, relative to the current stream. */
  const drawBuiltIn = useCallback(() => {
    const t = builtInTrack.current;
    if (!t) return;
    for (const cue of Array.from(t.cues ?? [])) t.removeCue(cue);
    const offset = convertRef.current?.offset ?? 0;
    for (const c of builtInCues.current.values()) {
      if (c.end - offset <= 0) continue;
      t.addCue(new VTTCue(Math.max(0, c.start - offset), c.end - offset, c.text));
    }
  }, []);

  // Choosing (or dropping) a built-in track resets what is loaded.
  useEffect(() => {
    builtInLoading.current?.abort();
    builtInLoading.current = null;
    builtInCues.current.clear();
    builtInLoaded.current = [];
    const v = video();
    if (!builtInTrack.current && v && builtIn) {
      builtInTrack.current = v.addTextTrack('subtitles', 'Built in');
    }
    if (builtInTrack.current) {
      drawBuiltIn();
      builtInTrack.current.mode = builtIn ? 'showing' : 'disabled';
    }
    setBuiltInWaiting(!!builtIn);
  }, [builtIn, drawBuiltIn]);

  // The converted stream's clock moved: same cues, new offset.
  useEffect(() => {
    if (builtIn) drawBuiltIn();
  }, [convert?.offset, builtIn, drawBuiltIn]);

  // Keep a window loaded ahead of playback.
  useEffect(() => {
    if (!builtIn || builtInLoading.current) return;
    const at = time;
    const here = builtInLoaded.current.find((r) => r.from <= at && at < r.to);
    let from: number;
    if (!here) from = Math.max(0, Math.floor(at) - 5);
    else if (here.to - at < SUB_READAHEAD && here.to < (media?.duration || Infinity)) {
      if (builtInLoaded.current.some((r) => r.from <= here.to && here.to < r.to)) return;
      from = here.to;
    } else return;

    const ctrl = new AbortController();
    builtInLoading.current = ctrl;
    const q = new URL(builtIn.url, location.origin);
    q.searchParams.set('from', String(from));
    q.searchParams.set('seconds', String(SUB_WINDOW));
    fetch(q, { signal: ctrl.signal })
      .then(async (res) => {
        if (!res.ok) throw new Error((await res.json().catch(() => null))?.error ?? `answered ${res.status}`);
        return res.text();
      })
      .then((vtt) => {
        for (const c of parseVtt(vtt)) builtInCues.current.set(`${c.start}|${c.text}`, c);
        builtInLoaded.current.push({ from, to: from + SUB_WINDOW });
        drawBuiltIn();
        setBuiltInWaiting(false);
      })
      .catch((e) => {
        if (ctrl.signal.aborted) return;
        setBuiltInWaiting(false);
        setError(`Subtitle failed: ${String((e as Error)?.message ?? e)}`);
      })
      .finally(() => {
        if (builtInLoading.current === ctrl) builtInLoading.current = null;
      });
    // `time` ticks several times a second; the checks above make most ticks free.
  }, [builtIn, time, media, drawBuiltIn]);

  /** Swarm stats, once a second — the poll also tells the server we are still
   *  watching, so the stream data is not swept as idle. */
  useEffect(() => {
    if (!engine) return;
    const t = setInterval(async () => {
      const s = await liveStats(engine.infoHash);
      if (s) setStats({ progress: s.progress, dl: s.downloadSpeed, ul: s.uploadSpeed, peers: s.numPeers });
    }, 1000);
    return () => clearInterval(t);
  }, [engine]);

  /**
   * Stream data is not kept: leaving the player releases it, and the server
   * deletes it shortly after. Saving a copy is what Save is for. `pagehide`
   * covers closing the tab, where React never unmounts.
   */
  useEffect(() => {
    if (!engine) return;
    const leave = () => releaseTorrent(engine.infoHash);
    window.addEventListener('pagehide', leave);
    return () => {
      window.removeEventListener('pagehide', leave);
      leave();
    };
  }, [engine]);

  /**
   * Back after the data was swept (a phone asleep for ten minutes): open the
   * torrent again and pick up where the picture stopped.
   */
  const reopening = useRef(false);
  const recover = useCallback(async () => {
    const v = video();
    if (!v || !engine || fileIndex == null || reopening.current) return false;
    if (await liveStats(engine.infoHash)) return false;
    reopening.current = true;
    const at = clockNow();
    setBuffering(true);
    try {
      const again = await getTorrent(engine.uri);
      const url = again.findFile(fileIndex)?.streamURL;
      if (url) {
        const c = convertRef.current;
        if (c) startConvert(c, at);
        else {
          v.src = url;
          resumeAt(v, at);
          void v.play().catch(() => {});
        }
      }
      return true;
    } catch {
      return false;
    } finally {
      reopening.current = false;
    }
  }, [engine, fileIndex, resumeAt, clockNow, startConvert]);

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

  /**
   * Universal: play through the server's ffmpeg — audio to AAC, and video
   * re-encoded when the probe says a browser cannot decode it. Pressed again,
   * back to the direct stream.
   */
  const toggleUniversal = useCallback(() => {
    setError(null);
    const at = clockNow();
    if (convertRef.current) {
      const v = video();
      const f = engine && fileIndex != null ? engine.findFile(fileIndex) : null;
      if (!v || !f) return;
      convertRef.current = null;
      setConvert(null);
      setNotice(null);
      v.src = f.streamURL;
      resumeAt(v, at);
      void v.play().catch(() => {});
      return;
    }
    startConvert({ audio: 0, encode: !!media?.needsVideoEncode }, at);
  }, [clockNow, engine, fileIndex, media, resumeAt, startConvert]);

  /** Calm notices fade on their own. */
  useEffect(() => {
    if (!notice) return;
    const t = setTimeout(() => setNotice(null), 6000);
    return () => clearTimeout(t);
  }, [notice]);

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
        muted={muted}
        onVolumeChange={(e) => setMuted(e.currentTarget.muted)}
        onPlay={() => {
          boostAudio();
          setPlaying(true);
        }}
        onPause={() => {
          setPlaying(false);
          setShowUi(true);
        }}
        onWaiting={() => setBuffering(true)}
        onPlaying={() => setBuffering(false)}
        onCanPlay={() => setBuffering(false)}
        onTimeUpdate={(e) => {
          const t = (convertRef.current?.offset ?? 0) + e.currentTarget.currentTime;
          const target = seekTarget.current;
          if (target) {
            // Still the old position: hold the slider on the target. Given up
            // after a while so a seek that never lands cannot freeze the clock.
            if (Math.abs(t - target.to) > 3 && Date.now() - target.at < 15_000) return;
            seekTarget.current = null;
          }
          setTime(t);
        }}
        onDurationChange={(e) => {
          // A converted stream has no length of its own; the probe's stands.
          const d = e.currentTarget.duration;
          if (!convertRef.current && Number.isFinite(d) && !media?.duration) {
            setDurationState(d);
            if (historyItem && fileIndex != null) void setDuration(historyItem, fileIndex, d);
          }
        }}
        onProgress={(e) => {
          const v = e.currentTarget;
          for (let i = 0; i < v.buffered.length; i++) {
            if (v.buffered.start(i) <= v.currentTime && v.currentTime <= v.buffered.end(i)) {
              setBuffered((convert?.offset ?? 0) + v.buffered.end(i));
            }
          }
        }}
        onEnded={() => {
          if (next && !dismissedNext) next.go();
        }}
        onError={async (e) => {
          const v = e.currentTarget;
          if (!v.error) return;
          log('video error', v.error.code, v.error.message, convertRef.current ? '(converted)' : '(direct)');
          const message = v.error.message;
          if (await recover()) return;
          const at = clockNow() || (fileIndex != null ? (historyItem?.positions?.[fileIndex] ?? 0) : 0);
          const c = convertRef.current;
          // The browser cannot decode this release: convert on the server —
          // first copying the video, then, if even that fails, re-encoding it.
          if (!c) {
            setNotice('The browser cannot play this format — converting on the server');
            startConvert({ audio: 0, encode: !!media?.needsVideoEncode }, at);
            return;
          }
          if (!c.encode) {
            setNotice('Re-encoding the video for this browser');
            startConvert({ ...c, encode: true }, at);
            return;
          }
          setBuffering(false);
          setError(`This release could not be played (${message || 'unsupported format'}).`);
        }}
      >
        {/* Inside the element, or the browser never attaches it. */}
        {subUrl && <track key={subUrl} kind="subtitles" src={subUrl} default />}
      </video>

      {/* Tap target for the picture — see lastTap above. */}
      <div
        className={`pl-tap${showUi && !locked ? ' pl-dim' : ''}`}
        onClick={(e) => {
          const toggle = () => {
            if (picking || search.open) {
              setPicking(false);
              setSearch((s) => ({ ...s, open: false }));
            } else if (showUi) setShowUi(false);
            else bumpUi();
          };
          if (!matchMedia('(pointer: coarse)').matches) return toggle();

          const rect = e.currentTarget.getBoundingClientRect();
          const side = e.clientX - rect.left < rect.width / 2 ? 'left' : 'right';
          const now = Date.now();
          const prev = lastTap.current;
          lastTap.current = { at: now, side };
          const chaining = nudge?.side === side && now - nudge.at < 700;
          if (!locked && (chaining || (prev && prev.side === side && now - prev.at < 300))) {
            window.clearTimeout(tapTimer.current);
            skip(side === 'left' ? -SKIP_SECONDS : SKIP_SECONDS);
            setNudge({ side, total: (chaining ? nudge.total : 0) + SKIP_SECONDS, at: now });
            return;
          }
          tapTimer.current = window.setTimeout(toggle, 260);
        }}
        onDoubleClick={() => {
          if (!matchMedia('(pointer: coarse)').matches) void toggleFullscreen();
        }}
      />

      {nudge && (
        <div className={`pl-ripple pl-ripple-${nudge.side}`}>
          {nudge.side === 'left' ? <SkipIcon back /> : <SkipIcon />}
          <span>{nudge.total} seconds</span>
        </div>
      )}

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
                  if (duration) seekTo(ratio * duration);
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
                on={!!activeSub || !!builtIn}
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
                <Action label="Universal" on={!!convert} onClick={() => { toggleUniversal(); bumpUi(); }}>
                  <ZapIcon />
                </Action>
              )}
              <Action label={muted ? 'Unmute' : 'Mute'} on={muted} onClick={() => { setMuted(!muted); bumpUi(); }}>
                <SoundIcon muted={muted} />
              </Action>
              <Action label="Fullscreen" onClick={toggleFullscreen}>
                <FullscreenIcon />
              </Action>
            </div>
          </div>
        </div>
      )}

      {picking && (
        <div className="pl-sheet">
          {media && media.audio.length > 1 && (
            <>
              <div className="pl-sheet-title">Audio</div>
              {media.audio.map((t) => (
                <TrackOption
                  key={t.index}
                  label={t.title || (t.language ? t.language.toUpperCase() : `Track ${t.index + 1}`)}
                  note={[t.title && t.language ? t.language.toUpperCase() : null, t.codec.toUpperCase(), t.channels]
                    .filter(Boolean)
                    .join(' · ')}
                  active={(convert?.audio ?? 0) === t.index}
                  onClick={() => {
                    pickAudio(t.index);
                    setPicking(false);
                  }}
                />
              ))}
              <div className="pl-sheet-rule" />
            </>
          )}
          <div className="pl-sheet-title">Subtitles</div>
          <TrackOption
            label="Off"
            active={!activeSub && !builtIn}
            onClick={() => {
              setActiveSub(null);
              setBuiltIn(null);
              if (historyItem && fileIndex != null) void setFileSubs(historyItem, fileIndex, []);
              setPicking(false);
            }}
          />
          {subs.map((s) => (
            <TrackOption
              key={s.url}
              label={s.label}
              note={builtIn?.url === s.url && builtInWaiting ? 'Loading…' : s.meta}
              busy={subBusy === s.url || (builtIn?.url === s.url && builtInWaiting)}
              active={builtIn ? builtIn.url === s.url : activeSub?.label === s.label}
              onClick={() => { void applySub(s); setPicking(false); }}
            />
          ))}
          {/* A subtitle picked in Find subs is on — show it, so there is
              something to see as selected and a way to tell what is playing. */}
          {activeSub && !subs.some((s) => s.label === activeSub.label) && (
            <TrackOption label={activeSub.label} note="From Find subs" active onClick={() => setPicking(false)} />
          )}
          {subs.length === 0 && (
            <div className="pl-nosubs">No subtitles in this torrent. Use Find subs to search online.</div>
          )}
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
          <button className="pl-pill-x" aria-label="Dismiss" onClick={() => setError(null)}>
            ×
          </button>
        </div>
      )}

      {!error && notice && <div className="pl-pill pl-failure">{notice}</div>}

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
