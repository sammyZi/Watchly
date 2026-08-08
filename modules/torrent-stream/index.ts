import { requireNativeModule } from 'expo-modules-core';

export type TorrentFile = {
  index: number;
  path: string; // full path inside the torrent, folders included
  name: string; // basename
  size: number; // bytes
  isVideo: boolean;
};

export type TorrentMeta = {
  name: string;
  files: TorrentFile[];
};

export type Progress = {
  buffer: number; // 0-100 of the head buffer needed to start
  progress: number; // 0-100 of the selected file
  speed: number; // bytes/s
  seeds: number;
  peers: number;
  dhtNodes: number;
  state: string; // libtorrent state, e.g. DOWNLOADING_METADATA
};

export type PlayOptions = {
  /** MB of the file head buffered before playback starts. */
  prepareMb?: number;
  /** Pieces kept hot ahead of the playback head. */
  windowPieces?: number;
  removeFilesAfterStop?: boolean;
  /** Where playback begins, 0-1 through the file. Buffering the start of a file
   *  the viewer is about to seek away from is pure dead time. */
  startRatio?: number;
};

type TorrentStream = {
  /**
   * Copy a `content://` source into app storage and return a plain path that
   * keeps working. Anything else is returned unchanged. Call this the moment a
   * shared file arrives — the grant behind a content:// URI does not last.
   */
  importSource(uri: string): Promise<string>;
  /** Resolve metadata and list the files inside. Slow for cold magnets. */
  inspect(uri: string, timeoutSec: number): Promise<TorrentMeta>;
  /** Start streaming one file from the torrent. */
  play(uri: string, fileIndex: number, options: PlayOptions): Promise<void>;
  /**
   * Open the running stream in another installed player, resuming at
   * `positionSeconds`. The torrent keeps running — the other app reads from the
   * same local server — so do not stop the stream while it is being watched
   * elsewhere.
   */
  openExternally(url: string, positionSeconds: number): Promise<void>;
  /** Move the prefetch window; `ratio` is 0-1 through the file. */
  setPosition(ratio: number): Promise<void>;
  stop(): Promise<void>;
  addListener(
    event: 'log' | 'progress' | 'ready' | 'error',
    cb: (e: any) => void
  ): { remove(): void };
};

export default requireNativeModule<TorrentStream>('TorrentStream');
