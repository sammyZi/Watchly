'use client';
/**
 * The player. Opens a magnet with the WebTorrent engine (service-worker HTTP
 * server + range requests), picks the file the release intended, and streams
 * it with resume, subtitles, stats and up-next — and when the browser cannot
 * decode a release, hands it to the ffmpeg.wasm transcode path.
 */
import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import {
  getTorrent,
  liveStats,
  pendingStats,
  startServer,
  type Engine,
} from '../../lib/torrent';
import { pickFile, nextEpisode } from '../../lib/pick';
import {
  BackIcon,
  CcIcon,
  DownloadIcon,
  FullscreenIcon,
  LockIcon,
  NextIcon,
  PauseIcon,
  PlayIcon,
  SkipIcon,
} from '../../components/Blocks';

/** Same prefix style as the engine, so one console reads as one session. */
const log = (...parts: unknown[]) => console.log('[player]', ...parts);
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
import { searchEpisode, episodeTag, type Hit } from '../../lib/search';
import { searchSubtitles, type Sub } from '../../lib/subdl';
import { fetchSubZip, subtitleBlob, SUB_EXT } from '../../lib/subs';
import { saveUrlToDisk, supportsSaveAPI, pickLocalFile } from '../../lib/save';
import {
  getFFmpeg,
  transcode,
  type Job,
  type TranscodeHandle,
} from '../../lib/ffmpeg';
import { titleOf } from '../../lib/title';
import { clock, bytes, speed, left } from '../../lib/format';

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
  const [canPlay, setCanPlay] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [time, setTime] = useState(0);
  const [duration, setDurationState] = useState(0);
  const [buffered, setBuffered] = useState(0);
  const [showOverlay, setShowOverlay] = useState(true);
  const [speedRate, setSpeedRate] = useState(1);
  const [fit, setFit] = useState<'contain' | 'cover'>('contain');
  const [locked, setLocked] = useState(false);
  const [startOver, setStartOver] = useState(false);
  const [stats, setStats] = useState({ progress: 0, dl: 0, ul: 0, peers: 0 });
  const [subs, setSubs] = useState<{ label: string; url: string | null; kind: string }[]>([]);
  const [activeSub, setActiveSub] = useState<string | null>(null);
  const [subMenu, setSubMenu] = useState(false);
  const [universal, setUniversal] = useState<null | 'off' | 'prep' | 'running'>('off');
  const [transcodeState, setTranscodeState] = useState<TranscodeHandle | null>(null);
  const [transcodedUrl, setTranscodedUrl] = useState<string | null>(null);
  const [saveState, setSaveState] = useState<{ percent: number; speed: number } | null>(null);
  const [nextUp, setNextUp] = useState<{ index: number; name: string } | null>(null);
  const [nextReleases, setNextReleases] = useState<Hit[] | null>(null);
  const [showNextReleases, setShowNextReleases] = useState(false);
  const [usingTrackers, setUsingTrackers] = useState(true);

  const video = () => videoRef.current;

  /**
   * Keyboard control.
   *
   * The one thing a player on a laptop is expected to have and this had none:
   * space to pause, arrows to scrub, f for fullscreen, m to mute. Ignored while
   * typing in a field, and while the controls are locked.
   */
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null;
      if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) {
        return;
      }
      const v = videoRef.current;
      if (!v || locked) return;
      const seek = (by: number) => {
        v.currentTime = Math.max(0, Math.min(v.duration || 0, v.currentTime + by));
      };
      switch (e.key) {
        case ' ':
        case 'k':
          e.preventDefault();
          if (v.paused) void v.play();
          else v.pause();
          break;
        case 'ArrowLeft':
          e.preventDefault();
          seek(-10);
          break;
        case 'ArrowRight':
          e.preventDefault();
          seek(10);
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
          void (document.fullscreenElement
            ? document.exitFullscreen()
            : document.documentElement.requestFullscreen());
          break;
        default:
          return;
      }
      setShowOverlay(true);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [locked]);

  /** Attach the picked file to the element once both exist. */
  useEffect(() => {
    if (!engine || fileIndex == null || !videoRef.current) return;
    const f = engine.findFile(fileIndex);
    if (!f?.streamURL || videoRef.current.src) return;
    videoRef.current.src = f.streamURL;
    setCanPlay(true);
    const pos = historyItem?.positions?.[fileIndex];
    if (pos && !startOver && !params.get('fresh')) {
      videoRef.current.addEventListener(
        'loadedmetadata',
        () => {
          const v = video();
          if (v && pos > 0 && pos < (v.duration || 1) - 5) v.currentTime = pos;
        },
        { once: true }
      );
    }
  }, [engine, fileIndex, historyItem, startOver, params]);

  /** Trackers setting feeds the magnet on open. */
  useEffect(() => {
    loadSettings().then((s) => setUsingTrackers(s.addTrackers));
  }, []);

  /** Open the swarm, pick the file, load resume + subtitles. */
  useEffect(() => {
    if (!uri) {
      setError('Nothing to play — no torrent given.');
      setStage('error');
      return;
    }
    let live = true;
    const magnet = usingTrackers ? withTrackers(uri) : uri;
    setStage('meta');
    startServer().then(() => {
      getTorrent(magnet)
        .then(async (e) => {
          if (!live) return;
          log(`opened ${e.torrentName} — ${e.files.length} file(s)`);
          setEngine(e);
          const files = e.files;
          const picked = pickFile(
            files.map((f) => ({ index: f.index, name: f.name, size: f.size, isVideo: f.isVideo })),
            tag
          );
          const item = (await loadHistory()).find((x) => x.uri === magnet) ?? {
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
          await putHistory({ ...item, files: files2, torrentName: e.torrentName });
          if (epId) await linkEpisode(epId, magnet);
          setHistoryItem({ ...item, files: files2 });
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
        setError('Your browser cannot write files to disk — use a Chromium browser or Universal mode.');
        return;
      }
      setSaveState({ percent: 0, speed: 0 });
      try {
        const { path, size } = await saveUrlToDisk(f.streamURL, f.name, (p) =>
          setSaveState({ percent: p.percent, speed: p.speed })
        );
        setSaveState({ percent: 1, speed: 0 });
        const entry = {
          uri: `saved:${Date.now()}:${f.name}`,
          title: f.name,
          at: Date.now(),
          files: [{ index: 0, name: path, size, isVideo: true, isSubtitle: false }] as StoredFile[],
        };
        await putHistory(entry);
        alert(`Saved ${path} (${bytes(size)}) to your Downloads.`);
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
    if (!v || !item || !fileIndex) return;
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
      const f = engine.findFile(index);
      const url = f?.streamURL;
      if (!url) return;
      setFileIndex(index);
      const v = video();
      if (v) {
        const pos = historyItem?.positions?.[index];
        v.src = url;
        if (pos && !startOver) {
          v.addEventListener(
            'loadedmetadata',
            () => {
              const el = video();
              if (el && pos > 0 && pos < (el.duration || 1) - 5) el.currentTime = pos;
            },
            { once: true }
          );
        }
        setCanPlay(true);
      }
      setActiveSub(null);
      setSubs([]);
    },
    [engine, historyItem, startOver]
  );

  /** Load subtitle candidates: in-torrent first, then SubDL when keyed. */
  useEffect(() => {
    if (!engine || fileIndex == null) return;
    let live = true;
    (async () => {
      const list: { label: string; url: string | null; kind: string }[] = [];
      for (const f of engine.files.filter((x) => x.isSubtitle)) {
        const url = f.streamURL;
        const label = f.name.split('/').pop() ?? f.name;
        list.push({ label, url, kind: /\.ass$/i.test(f.name) ? 'ass' : 'srt' });
      }
      const key = localStorage.getItem('subdlKey');
      if (key) {
        try {
          const found = await searchSubtitles(
            titleName ?? engine.torrentName,
            key,
            season != null && episode != null ? { season, episode } : undefined
          );
          for (const s of found.filter((x) => x.language.startsWith('EN'))) {
            list.push({ label: `SubDL: ${s.release.slice(0, 50)}`, url: s.url, kind: 'zip' });
          }
        } catch {
          // No key / refused: in-torrent subs still work.
        }
      }
      if (live) setSubs(list);
    })();
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [engine, fileIndex]);

  const applySub = useCallback(
    async (s: { label: string; url: string | null; kind: string }, index: number) => {
      const v = video();
      if (!v || !s.url) {
        setActiveSub(null);
        return;
      }
      try {
        let text: string;
        if (s.kind === 'zip') {
          const files = await fetchSubZip(s.url);
          const srt = [...files.entries()].find(([name]) => /\.srt$/i.test(name)) ?? [...files.entries()][0];
          text = srt[1];
        } else if (s.kind === 'ass') {
          const res = await fetch(s.url);
          text = await res.text();
          const blob = new Blob([text], { type: 'text/plain' });
          const a = document.createElement('a');
          a.href = URL.createObjectURL(blob);
          a.download = s.label.replace(/[^\w.-]+/g, '_') + '.ass';
          a.click();
          setActiveSub(null);
          return;
        } else {
          const res = await fetch(s.url);
          text = await res.text();
        }
        const url = subtitleBlob(text, 'srt');
        setActiveSub(url);
        if (historyItem && typeof index === 'number')
          setFileSubs(historyItem, index, [{ path: s.label.split(':')[0], label: s.label }]);
      } catch (e) {
        setError(`Subtitle failed: ${String((e as Error)?.message ?? e)}`);
      }
    },
    [historyItem]
  );

  /** Pause the previous source's subtitles — vtt tracks stick to the element. */
  useEffect(() => {
    const v = video();
    if (!v || !fileIndex) return;
    const track = [...(v as HTMLVideoElement & { textTracks?: TextTrackList }).textTracks ?? []] as TextTrack[];
    track.forEach((t) => {
      const mode = t.kind === 'subtitles' ? (activeSub ? 'showing' : 'hidden') : t.mode;
      t.mode = mode;
    });
  }, [activeSub, fileIndex]);

  /** Swarm stats, a beat behind the event loop. */
  useEffect(() => {
    const t = setInterval(() => {
      if (!engine) return;
      const s = liveStats(engine.infoHash);
      setStats({ progress: s.progress, dl: s.downloadSpeed, ul: s.uploadSpeed, peers: s.numPeers });
    }, 1000);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [engine]);

  /** Peer count while the metadata is still being chased. */
  const [waiting, setWaiting] = useState<{ peers: number; seconds: number } | null>(null);
  useEffect(() => {
    if (stage !== 'meta' || !uri) return;
    const magnet = usingTrackers ? withTrackers(uri) : uri;
    const tick = setInterval(() => setWaiting(pendingStats(magnet)), 1000);
    return () => clearInterval(tick);
  }, [stage, uri, usingTrackers]);

  /** Overlay auto-hide on play. */
  useEffect(() => {
    if (!playing || locked) return;
    const t = setTimeout(() => setShowOverlay(false), 3200);
    return () => clearTimeout(t);
  }, [playing, locked, showOverlay]);

  /** Up next: inside the torrent first, then a release search for the next ep. */
  useEffect(() => {
    if (!engine || fileIndex == null) return;
    const next = nextEpisode(
      engine.files.map((f) => ({ index: f.index, name: f.name, size: f.size, isVideo: f.isVideo })),
      fileIndex
    );
    if (next) {
      setNextUp({ index: next.index, name: next.name });
      setNextReleases(null);
      return;
    }
    if (!imdb || season == null || episode == null) {
      setNextUp(null);
      return;
    }
    setNextUp(null);
    setNextReleases(null);
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
    const q = new URLSearchParams({
      uri: h.magnet,
      name: h.name,
      kind,
    });
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

  /** Universal mode: transcode this file with ffmpeg.wasm. */
  const runUniversal = useCallback(
    async (job: Job) => {
      if (!engine || fileIndex == null) return;
      const f = engine.findFile(fileIndex);
      if (!f) return;
      setUniversal('prep');
      setTranscodeState(null);
      try {
        await getFFmpeg();
        let file: File | null = null;
        if (f.size <= 1.5 * 1024 ** 3) {
          const { blob } = await engine.blobOf(f);
          file = new File([blob], f.name.split('/').pop() ?? 'input.mkv');
        } else {
          const picked = await pickLocalFile();
          file = picked;
        }
        if (!file) {
          setUniversal('off');
          return;
        }
        if (file.size > 2.5 * 1024 ** 3) {
          setError('Files over 2.5 GB are too big for in-browser ffmpeg — save to disk and pick a smaller release.');
          setUniversal('off');
          return;
        }
        setUniversal('running');
        const url = await transcode(file, file.name, job, setTranscodeState);
        setTranscodedUrl(url);
        const v = video();
        if (v) {
          const pos = historyItem?.positions?.[fileIndex];
          v.src = url;
          if (pos && !startOver) {
            v.addEventListener(
              'loadedmetadata',
              () => {
                const el = video();
                if (el && pos && pos < (el.duration || 1) - 5) el.currentTime = pos;
              },
              { once: true }
            );
          }
        }
        setUniversal('off');
      } catch (e) {
        setUniversal('off');
        setError(`Universal encode failed: ${String((e as Error)?.message ?? e)}`);
      }
    },
    [engine, fileIndex, historyItem, startOver]
  );

  if (stage === 'error' || !uri) {
    return (
      <main className="page">
        <div className="page-head">
          <Link href="/downloads" className="back-btn" aria-label="Back"><BackIcon /></Link>
          <h1 className="page-title">Player</h1>
        </div>
        <p className="center danger-text">{error ?? 'Missing torrent.'}</p>
      </main>
    );
  }

  if (stage === 'meta' || !engine) {
    return (
      <main className="page" style={{ paddingTop: 40 }}>
        <div className="spinner" />
        <p className="center">Finding peers…</p>
        {/* Peers and seconds, because "0 peers after 40s" is the difference
            between waiting and waiting for nothing. */}
        <p className="center muted" style={{ fontSize: 13, marginTop: 6 }}>
          {waiting
            ? `${waiting.peers} peer${waiting.peers === 1 ? '' : 's'} · ${waiting.seconds}s`
            : 'Starting…'}
        </p>
        <p className="center faint" style={{ fontSize: 12, marginTop: 14, maxWidth: 420, marginInline: 'auto', lineHeight: 1.6 }}>
          In a browser this only reaches peers running WebTorrent. Releases with
          no web seeders will not start here — the Android app has no such limit.
        </p>
      </main>
    );
  }

  const file = fileIndex != null ? engine.findFile(fileIndex) : null;

  return (
    <div style={{ minHeight: '100vh', display: 'flex', flexDirection: 'column', background: '#000' }}>
      <div
        style={{
          position: 'relative',
          background: '#000',
          flex: 1,
          minHeight: 0,
          maxHeight: 'calc(100vh - 8px)',
          overflow: 'hidden',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
        className={showOverlay ? undefined : 'player-idle'}
        onMouseMove={() => setShowOverlay(true)}
        onMouseLeave={() => playing && setShowOverlay(false)}
        onDoubleClick={() => {
          const v = video();
          if (v) {
            void (document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen());
          }
        }}
      >
        <video
          ref={videoRef}
          style={{ width: '100%', height: '100%', objectFit: fit }}
          playsInline
          autoPlay={false}
          onClick={() => {
            const v = video();
            if (!v) return;
            if (v.paused) void v.play();
            else v.pause();
          }}
          onPlay={() => setPlaying(true)}
          onPause={() => setPlaying(false)}
          onTimeUpdate={(e) => setTime(e.currentTarget.currentTime)}
          onDurationChange={(e) => {
            const d = e.currentTarget.duration;
            if (Number.isFinite(d)) {
              setDurationState(d);
              if (historyItem) void setDuration(historyItem, fileIndex!, d);
            }
          }}
          onProgress={(e) => {
            const v = e.currentTarget;
            if (v.buffered.length) {
              setBuffered(v.buffered.end(v.buffered.length - 1));
              if (file && canPlay) {
                const progress = Math.min(1, v.currentTime / Math.max(1, v.duration));
                setStats((s) => ({ ...s, progress: Math.max(s.progress, progress) }));
              }
            }
          }}
          onError={(e) => {
            const v = e.currentTarget;
            if (v.error) log('video error', v.error.code, v.error.message);
            if (v.error && universal === 'off') {
              setError(`The browser cannot play this release (${v.error.message ?? 'error'}). Try Universal mode — it decodes anything.`);
            }
          }}
        />
        {activeSub && (
          <track kind="subtitles" src={activeSub} default />
        )}

        {nextUp && playing && duration - time < 30 && (
          <div style={{ position: 'absolute', right: 18, bottom: 28, zIndex: 5, background: 'var(--surface)', borderRadius: 12, padding: 14, maxWidth: 260, boxShadow: '0 8px 30px rgba(0,0,0,.5)' }}>
            <div style={{ fontSize: 11, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: 0.6 }}>Up next</div>
            <div style={{ fontWeight: 600, fontSize: 13, margin: '4px 0 8px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{nextUp.name}</div>
            <button className="btn btn-primary" style={{ padding: '8px 14px', fontSize: 12 }} onClick={() => switchFile(nextUp.index)}>Play</button>
          </div>
        )}

        {showOverlay && (
          <div className="player-ui" style={{ position: 'absolute', inset: 0, zIndex: 4, display: 'flex', flexDirection: 'column', justifyContent: 'space-between', background: 'linear-gradient(to top, rgba(0,0,0,.72), transparent 45%, rgba(0,0,0,.45))', pointerEvents: 'none' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: 14, pointerEvents: 'auto' }}>
              <button className="icon-btn" aria-label="Back" onClick={() => router.back()}>
                <BackIcon />
              </button>
              <div style={{ minWidth: 0 }}>
                <div style={{ fontWeight: 600, fontSize: 17, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {epName ?? file?.name.split('/').pop() ?? engine.torrentName}
                </div>
                {epName && <div style={{ fontSize: 13, color: 'rgba(255,255,255,.6)' }}>{engine.torrentName}</div>}
              </div>
              <div style={{ marginLeft: 'auto', display: 'flex', gap: 6 }}>
                <button className={`icon-btn ${activeSub ? 'on' : ''}`} title="Subtitles" onClick={() => setSubMenu(!subMenu)}>
                  <CcIcon />
                </button>
                <button className="icon-btn" title={`Fit: ${fit}`} onClick={() => setFit(fit === 'contain' ? 'cover' : 'contain')}>
                  <FullscreenIcon on={fit === 'cover'} />
                </button>
                <button className="icon-btn" title="Speed" onClick={() => {
                  const v = video();
                  if (!v) return;
                  const rates = [0.5, 0.75, 1, 1.25, 1.5, 2];
                  const i = rates.indexOf(v.playbackRate);
                  v.playbackRate = rates[(i + 1) % rates.length];
                  setSpeedRate(v.playbackRate);
                }}>{speedRate}×</button>
                <button className={`icon-btn ${locked ? 'on' : ''}`} title={locked ? 'Unlock' : 'Lock'} onClick={() => setLocked(!locked)}>
                  <LockIcon on={locked} />
                </button>
              </div>
            </div>

            <div style={{ padding: '0 14px 16px', pointerEvents: 'auto' }}>
              {subMenu && subs.length > 0 && (
                <div style={{ background: 'var(--surface)', borderRadius: 12, padding: 8, marginBottom: 10, maxHeight: 220, overflowY: 'auto' }}>
                  <button
                    className="row-sub"
                    style={{ display: 'block', width: '100%', textAlign: 'left', padding: '7px 10px', borderRadius: 8, color: activeSub === null ? 'var(--accent)' : 'var(--muted)' }}
                    onClick={() => { setActiveSub(null); setSubMenu(false); }}
                  >
                    Off
                  </button>
                  {subs.map((s, i) => (
                    <button
                      key={s.label + i}
                      style={{ display: 'block', width: '100%', textAlign: 'left', padding: '7px 10px', borderRadius: 8, fontSize: 12, color: 'var(--muted)' }}
                      onClick={() => { void applySub(s, i); setSubMenu(false); }}
                    >
                      {s.label}
                    </button>
                  ))}
                </div>
              )}

              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <span style={{ fontSize: 13, width: 48, color: '#fff', fontVariantNumeric: 'tabular-nums' }}>{clock(time)}</span>
                <div
                  style={{ flex: 1, height: 22, position: 'relative', cursor: 'pointer', display: 'flex', alignItems: 'center' }}
                  onClick={(e) => {
                    const v = video();
                    if (!v) return;
                    const rect = e.currentTarget.getBoundingClientRect();
                    const frac = (e.clientX - rect.left) / rect.width;
                    v.currentTime = frac * (v.duration || 0);
                  }}
                >
                  <div style={{ position: 'absolute', left: 0, right: 0, height: 5, borderRadius: 3, background: 'rgba(255,255,255,.22)' }} />
                  <div
                    style={{
                      position: 'absolute', left: 0, height: 5, borderRadius: 3, background: '#fff',
                      width: `${duration ? (buffered / duration) * 100 : 0}%`, opacity: 0.28,
                    }}
                  />
                  <div
                    style={{
                      position: 'absolute', left: 0, height: 5, borderRadius: 3, background: 'var(--accent)',
                      width: `${duration ? (time / duration) * 100 : 0}%`,
                    }}
                  />
                  {/* The knob: without it there is nothing to aim at, and no
                      sign the bar is draggable at all. */}
                  <div
                    style={{
                      position: 'absolute',
                      left: `${duration ? (time / duration) * 100 : 0}%`,
                      width: 13,
                      height: 13,
                      marginLeft: -6.5,
                      borderRadius: '50%',
                      background: 'var(--accent)',
                      boxShadow: '0 0 0 3px rgba(0,0,0,.35)',
                    }}
                  />
                </div>
                <span style={{ fontSize: 13, width: 48, color: 'rgba(255,255,255,.65)', fontVariantNumeric: 'tabular-nums', textAlign: 'right' }}>{clock(duration)}</span>
              </div>

              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 6 }}>
                <button className="icon-btn" aria-label="Back 10 seconds" onClick={() => { const v = video(); if (v) v.currentTime = Math.max(0, v.currentTime - 10); }}>
                  <SkipIcon back />
                </button>
                <button
                  className="btn btn-primary"
                  style={{ width: 52, height: 52, borderRadius: '50%', padding: 0, justifyContent: 'center' }}
                  onClick={() => {
                    const v = video();
                    if (!v) return;
                    if (v.paused) void v.play();
                    else v.pause();
                  }}
                >
                  {playing ? <PauseIcon /> : <PlayIcon />}
                </button>
                <button className="icon-btn" aria-label="Forward 10 seconds" onClick={() => { const v = video(); if (v) v.currentTime = Math.min(v.duration || 0, v.currentTime + 10); }}>
                  <SkipIcon />
                </button>

                <div style={{ marginLeft: 'auto', display: 'flex', gap: 6, alignItems: 'center' }}>
                  <span style={{ fontSize: 13, color: 'rgba(255,255,255,.72)', fontVariantNumeric: 'tabular-nums' }}>
                    {Math.round(stats.progress * 100)}% · {speed(stats.dl)} · {stats.peers} peers
                  </span>
                  {engine.findFile(fileIndex ?? -1)?.streamURL && (
                    <>
                      <button
                        className="icon-btn"
                        title="Download this file"
                        onClick={() => file && doSave({ streamURL: file.streamURL!, name: file.name.split('/').pop() ?? file.name, index: file.index })}
                      >
                        <DownloadIcon />
                      </button>
                      {!nextUp && nextReleases && nextReleases.length > 0 && (
                        <button className="icon-btn" title="Next episode" onClick={() => setShowNextReleases(!showNextReleases)}>
                          <NextIcon />
                        </button>
                      )}
                      <button className="icon-btn" title="Universal mode — decodes anything" onClick={() => runUniversal('fast')}>
                        ˄˄
                      </button>
                    </>
                  )}
                </div>
              </div>
            </div>
          </div>
        )}

        {saveState && (
          <div style={{ position: 'absolute', inset: 0, zIndex: 6, background: 'rgba(0,0,0,.75)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <div style={{ background: 'var(--surface)', padding: 22, borderRadius: 14, width: 300, textAlign: 'center' }}>
              <div style={{ fontWeight: 600 }}>Saving to disk…</div>
              <div style={{ height: 4, borderRadius: 2, background: 'var(--line)', margin: '12px 0', overflow: 'hidden' }}>
                <div style={{ width: `${saveState.percent * 100}%`, height: '100%', background: 'var(--accent)' }} />
              </div>
              <div style={{ fontSize: 12, color: 'var(--muted)' }}>{Math.round(saveState.percent * 100)}% · {speed(saveState.speed)}</div>
            </div>
          </div>
        )}

        {(universal === 'prep' || universal === 'running') && (
          <div style={{ position: 'absolute', inset: 0, zIndex: 7, background: 'rgba(0,0,0,.78)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <div style={{ background: 'var(--surface)', padding: 24, borderRadius: 14, width: 340, textAlign: 'center' }}>
              <div style={{ fontWeight: 600, marginBottom: 6 }}>
                {universal === 'prep' ? 'Loading ffmpeg.wasm…' : 'Transcoding — decodes any codec'}
              </div>
              {transcodeState && (
                <>
                  <div style={{ height: 4, borderRadius: 2, background: 'var(--line)', margin: '10px 0', overflow: 'hidden' }}>
                    <div style={{ width: `${transcodeState.progress * 100}%`, height: '100%', background: 'var(--accent)' }} />
                  </div>
                  <div style={{ fontSize: 12, color: 'var(--muted)', fontVariantNumeric: 'tabular-nums' }}>
                    {Math.round(transcodeState.progress * 100)}% · {clock(transcodeState.time)} processed
                    {duration > 0 && ` · ${left(Math.max(0, (duration - transcodeState.time) * 3))}`}
                  </div>
                </>
              )}
              <div style={{ fontSize: 11, color: 'var(--faint)', marginTop: 10 }}>
                Fast: video as-is, audio → AAC. Full: everything re-encoded. Bitmap subs (PGS/VOBSUB) can’t be converted.
              </div>
            </div>
          </div>
        )}

        {showNextReleases && nextReleases && (
          <div style={{ position: 'absolute', right: 18, bottom: 60, zIndex: 5, background: 'var(--surface)', borderRadius: 12, padding: 10, width: 320, boxShadow: '0 8px 30px rgba(0,0,0,.5)' }}>
            <div style={{ fontSize: 11, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: 0.6, padding: '0 4px 6px' }}>
              Next episode releases
            </div>
            {nextReleases.slice(0, 4).map((h) => (
              <button key={h.id} style={{ display: 'block', width: '100%', textAlign: 'left', padding: '7px 8px', borderRadius: 8, fontSize: 12, color: 'var(--muted)' }} onClick={() => goNextRelease(h)}>
                {h.name.slice(0, 70)} · {h.seeders} seeds
              </button>
            ))}
          </div>
        )}
      </div>

      {fileIndex == null && (
        <div style={{ padding: 18 }}>
          <h2 style={{ fontSize: 15, fontWeight: 600, marginBottom: 10 }}>Choose a file</h2>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {engine.files.filter((f) => f.isVideo).map((f) => (
              <button
                key={f.index}
                style={{ display: 'flex', justifyContent: 'space-between', padding: 12, borderRadius: 12, background: 'var(--surface)', fontSize: 13 }}
                onClick={() => switchFile(f.index)}
              >
                <span>{f.name.split('/').pop()}</span>
                <span className="faint">{bytes(f.size)}</span>
              </button>
            ))}
          </div>
        </div>
      )}

      {(error || !canPlay) && (
        <div style={{ padding: '10px 18px' }}>
          {error && <p className="danger-text" style={{ fontSize: 13, marginBottom: 8 }}>{error}</p>}
          {!canPlay && (
            <p className="faint" style={{ fontSize: 13 }}>
              Ready to stream — press play. Swarm stats below.
            </p>
          )}
        </div>
      )}

      {engine && file?.index !== undefined && (
        <div style={{ padding: '0 18px 24px', display: 'flex', gap: 14, flexWrap: 'wrap', alignItems: 'center' }}>
          <button
            className="btn btn-primary"
            style={{ padding: '10px 16px', fontSize: 13 }}
            onClick={() => runUniversal('fast')}
            disabled={universal !== 'off'}
          >
            ˄˄ Universal (fast)
          </button>
          <button
            className="btn btn-soft"
            style={{ padding: '10px 16px', fontSize: 13 }}
            onClick={() => runUniversal('full')}
            disabled={universal !== 'off'}
          >
            ˄˄ Universal (full)
          </button>
          <button className="btn btn-soft" style={{ padding: '10px 16px', fontSize: 13 }} onClick={() => file && switchFile(file.index)}>
            ↺ Restart
          </button>
          <span className="faint" style={{ fontSize: 12 }}>
            {bytes(engine.size)} · {nextReleases ? 'next episode available' : ''}
          </span>
        </div>
      )}
    </div>
  );
}

export default function PlayerScreen() {
  return (
    <Suspense fallback={<div style={{ height: '100vh' }} />}>
      <PlayerInner />
    </Suspense>
  );
}