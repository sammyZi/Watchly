import { useCallback, useEffect, useRef, useState } from 'react';
import TorrentStream, {
  type Progress,
  type TorrentMeta,
} from '../../modules/torrent-stream';
import type { Item } from '../lib/history';
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
  const [fileName, setFileName] = useState<string | null>(null);
  const [progress, setProgress] = useState<Progress | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [playingIndex, setPlayingIndex] = useState(0);

  // Latest source, so play() uses the same (tracker-augmented) uri as inspect().
  const source = useRef<string | null>(null);
  const lastState = useRef<string | null>(null);
  // Progress ticks once a second. Nothing renders it once playback starts, but
  // the state write still re-renders the whole app — including the player —
  // which is enough to make the video controls feel unresponsive.
  const streaming = useRef(false);

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
        if (!streaming.current) setProgress(p);
      }),
      TorrentStream.addListener('ready', ({ url, name }) => {
        console.log('[torrent] ready', name);
        streaming.current = true;
        setUrl(url);
        setFileName(name);
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
    setFileName(null);
    setProgress(null);
    source.current = null;
    lastState.current = null;
    streaming.current = false;
  }, []);

  /** Stage 1 — resolve the file list. Returns the metadata so callers can auto-pick. */
  const inspect = useCallback(
    async (next: Item): Promise<TorrentMeta | null> => {
      const uri = settings.addTrackers ? withTrackers(next.uri) : next.uri;
      source.current = uri;

      setError(null);
      setProgress(null);
      setUrl(null);
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
    (fileIndex: number, startRatio = 0) => {
      const uri = source.current;
      if (!uri) return;

      setProgress(null);
      setUrl(null);
      streaming.current = false;
      setPlayingIndex(fileIndex);
      setPhase('playing');

      TorrentStream.play(uri, fileIndex, {
        prepareMb: settings.prepareMb,
        removeFilesAfterStop: settings.removeFilesAfterStop,
        startRatio,
      }).catch((e: any) => {
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
    (next: Item, fileIndex: number) => {
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
          })),
        });
      }
      play(fileIndex, resumeRatio(next, fileIndex));
    },
    [play, settings.addTrackers]
  );

  return {
    phase,
    item,
    meta,
    url,
    fileName,
    playingIndex,
    progress,
    error,
    fail,
    inspect,
    play,
    playKnown,
    reset,
  };
}
