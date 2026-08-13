import { useCallback, useEffect, useRef, useState } from 'react';
import TorrentStream, {
  type Progress,
  type TorrentMeta,
} from '../../modules/torrent-stream';
import type { Item } from '../lib/history';
import { saveDownloadJob } from '../lib/downloadJob';
import { withTrackers } from '../lib/magnet';
import type { Settings } from '../lib/settings';

/**
 * idle      nothing running
 * inspecting resolving metadata (blocking native call, no progress to show)
 * choosing  waiting for the user to pick a file
 * playing   player is mounted; `url` is null until the first piece lands
 */
export type Phase = 'idle' | 'inspecting' | 'choosing' | 'playing';

/** How far into a file the stored resume point is, 0-1. Both sides of the
 *  stream need this: the player seeks there, the engine buffers there. */
export function resumeRatio(item: Item, fileIndex: number): number {
  const position = item.positions?.[fileIndex];
  const duration = item.durations?.[fileIndex];
  if (!position || !duration) return 0;
  return Math.min(0.99, Math.max(0, position / duration));
}

/**
 * Owns the native engine. Two stages: `inspect` resolves the torrent's file
 * list, then `play` streams one of those files.
 *
 * There is deliberately no buffering phase — the native side serves the file
 * over a socket that blocks on data it does not have yet, so the player can be
 * mounted straight away and show its own spinner.
 */
export function useTorrent(settings: Settings) {
  const [phase, setPhase] = useState<Phase>('idle');
  const [item, setItem] = useState<Item | null>(null);
  const [meta, setMeta] = useState<TorrentMeta | null>(null);
  const [url, setUrl] = useState<string | null>(null);
  /** The same stream on a network-reachable address, for casting. */
  const [lanUrl, setLanUrl] = useState<string | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);
  const [subtitleFiles, setSubtitleFiles] = useState<string[]>([]);
  const [progress, setProgress] = useState<Progress | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [playingIndex, setPlayingIndex] = useState(0);
  /** File indexes this play() asked for — a season download is many, not one. */
  const [wantedIndices, setWantedIndices] = useState<number[]>([]);

  // Latest source, so play() uses the same (tracker-augmented) uri as inspect().
  const source = useRef<string | null>(null);
  const lastState = useRef<string | null>(null);
  const itemRef = useRef<Item | null>(null);
  itemRef.current = item;
  // Progress ticks once a second. Once the player is up that re-render makes
  // controls feel laggy — mute it for streaming. Download mode needs every tick.
  const streaming = useRef(false);
  const keepProgress = useRef(false);

  useEffect(() => {
    const subs = [
      TorrentStream.addListener('log', ({ message }) =>
        console.log('[torrent]', message)
      ),
      TorrentStream.addListener('progress', (p: Progress) => {
        // One line per tick drowns the log and costs real time in dev; only
        // speak up when the torrent actually changes state.
        if (p.state !== lastState.current) {
          lastState.current = p.state;
          console.log(`[torrent] ${p.state} seeds=${p.seeds} dht=${p.dhtNodes}`);
        }
        if (!streaming.current || keepProgress.current) setProgress(p);
      }),
      TorrentStream.addListener('ready', ({ url, lanUrl: lan, name, subtitleFiles: subs }) => {
        console.log('[torrent] ready', name, 'lan:', lan ?? 'none');
        streaming.current = true;
        setUrl(url);
        setLanUrl(lan ?? null);
        setFileName(name);
        setSubtitleFiles(subs ?? []);
      }),
      TorrentStream.addListener('error', ({ message }) => {
        console.warn('[torrent] error', message);
        setError(message);
        setPhase('idle');
      }),
    ];
    return () => subs.forEach((s) => s.remove());
  }, []);

  /** Surface a failure that happened before the engine was involved. */
  const fail = useCallback((message: string) => {
    setError(message);
    setPhase('idle');
  }, []);

  const reset = useCallback(() => {
    TorrentStream.stop();
    setPhase('idle');
    setItem(null);
    setMeta(null);
    setUrl(null);
    setLanUrl(null);
    setFileName(null);
    setSubtitleFiles([]);
    setWantedIndices([]);
    setProgress(null);
    source.current = null;
    lastState.current = null;
    streaming.current = false;
    keepProgress.current = false;
  }, []);

  /** Stage 1 — resolve the file list. Returns the metadata so callers can auto-pick. */
  const inspect = useCallback(
    async (next: Item): Promise<TorrentMeta | null> => {
      const uri = settings.addTrackers ? withTrackers(next.uri) : next.uri;
      source.current = uri;

      setError(null);
      setProgress(null);
      setUrl(null);
      setLanUrl(null);
      setMeta(null);
      streaming.current = false;
      setItem(next);
      setPhase('inspecting');

      try {
        const found = await TorrentStream.inspect(uri, 60);
        setMeta(found);
        setPhase('choosing');
        return found;
      } catch (e: any) {
        console.warn('[torrent] inspect failed', e?.message ?? e);
        setError(String(e?.message ?? e));
        setPhase('idle');
        setItem(null);
        return null;
      }
    },
    [settings.addTrackers]
  );

  /**
   * Stage 2 — mount the player now, stream the file into it as it arrives.
   *
   * `startRatio` must match the player's own resume seek, or the engine buffers
   * the head of the file and the player immediately jumps somewhere else.
   */
  const play = useCallback(
    (
      fileIndex: number,
      startRatio = 0,
      opts?: {
        keepProgress?: boolean;
        fullFile?: boolean;
        fileIndices?: number[];
        season?: number;
      }
    ) => {
      const uri = source.current;
      if (!uri) return;

      setProgress(null);
      setUrl(null);
      setLanUrl(null);
      setSubtitleFiles([]);
      streaming.current = false;
      keepProgress.current = !!opts?.keepProgress;
      setPlayingIndex(fileIndex);
      setWantedIndices(
        opts?.fileIndices?.length ? opts.fileIndices : [fileIndex]
      );
      setPhase('playing');
      if (opts?.fullFile) {
        const it = itemRef.current;
        saveDownloadJob({
          uri,
          fileIndex,
          fileIndices: opts.fileIndices?.length ? opts.fileIndices : [fileIndex],
          season: opts.season,
          title: it?.title,
          titleName: it?.titleName,
          poster: it?.poster,
          imdb: it?.imdb,
          kind: it?.kind,
        }).catch(() => {});
      }
      console.log('[torrent] play', {
        index: fileIndex,
        season: opts?.season ?? -1,
        files: opts?.fileIndices ?? [],
      });

      TorrentStream.play(
        uri,
        fileIndex,
        {
          prepareMb: settings.prepareMb,
          removeFilesAfterStop: opts?.fullFile ? false : settings.removeFilesAfterStop,
          startRatio,
          fullFile: !!opts?.fullFile,
          fileList: (opts?.fileIndices ?? []).join(','),
          season: opts?.season ?? -1,
        },
        (opts?.fileIndices ?? []).join(',')
      ).catch((e: any) => {
        console.warn('[torrent] play failed', e?.message ?? e);
        setError(String(e?.message ?? e));
        setPhase('idle');
      });
    },
    [settings.prepareMb, settings.removeFilesAfterStop]
  );

  /**
   * Resume a torrent we already know the file list for — skips native inspect.
   * Metadata/peers are still pulled from the on-disk cache inside play().
   */
  const playKnown = useCallback(
    (
      next: Item,
      fileIndex: number,
      opts?: {
        keepProgress?: boolean;
        fullFile?: boolean;
        fileIndices?: number[];
        season?: number;
      }
    ) => {
      const uri = settings.addTrackers ? withTrackers(next.uri) : next.uri;
      source.current = uri;
      setError(null);
      setItem(next);
      if (next.torrentName && next.files) {
        setMeta({
          name: next.torrentName,
          files: next.files.map((f) => ({
            index: f.index,
            path: f.name,
            name: f.name,
            size: f.size,
            isVideo: f.isVideo,
            isSubtitle: f.isSubtitle ?? false,
          })),
        });
      }
      play(
        fileIndex,
        opts?.keepProgress ? 0 : resumeRatio(next, fileIndex),
        opts
      );
    },
    [play, settings.addTrackers]
  );

  const skipFile = useCallback((index: number) => {
    setWantedIndices((prev) => {
      const next = prev.filter((i) => i !== index);
      const uri = source.current;
      if (uri && next.length) {
        saveDownloadJob({
          uri,
          fileIndex: next[0],
          fileIndices: next,
        }).catch(() => {});
      }
      return next;
    });
    TorrentStream.skipFile(index).catch((e: any) =>
      console.warn('[torrent] skip failed', e?.message ?? e)
    );
  }, []);

  return {
    phase,
    item,
    meta,
    url,
    lanUrl,
    fileName,
    subtitleFiles,
    playingIndex,
    wantedIndices,
    progress,
    error,
    fail,
    inspect,
    play,
    playKnown,
    skipFile,
    reset,
  };
}
