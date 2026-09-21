import { requireNativeModule } from 'expo-modules-core';

export type TorrentFile = {
  index: number;
  path: string; // full path inside the torrent, folders included
  name: string; // basename
  size: number; // bytes
  isVideo: boolean;
  isSubtitle: boolean;
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
  paused?: boolean;
  /** Set on download-mode ticks so JS can tell torrents apart. */
  uri?: string;
  id?: string;
};

export type PlayOptions = {
  /** Most MB of the file head buffered before playback starts. A swarm faster
   *  than the file's bitrate starts after one piece. */
  prepareMb?: number;
  /** Pieces kept hot ahead of the playback head. Omit — the native side sizes
   *  it in bytes, which is the only way that holds across piece sizes. */
  windowPieces?: number;
  removeFilesAfterStop?: boolean;
  /** Where playback begins, 0-1 through the file. Buffering the start of a file
   *  the viewer is about to seek away from is pure dead time. */
  startRatio?: number;
  /**
   * Fetch all of `fileIndex` rather than a window around the read position.
   *
   * Streaming wants a narrow sliding window: only what the viewer is about to
   * watch, everything else deprioritised. A download has no reader to follow,
   * so that window stays pinned at the head — the swarm saturates the link on
   * a few MB and the file never finishes. Set this for downloads.
   *
   * This never widens to the rest of the torrent unless `fileList` names
   * more files (a season download).
   */
  fullFile?: boolean;
  /** Extra file indexes as `"0,1,2"` — a JS array does not survive the Record bridge. */
  fileList?: string;
  /** When >= 0, native fetches every video in this season. */
  season?: number;
  /** Show name for Movies/Watchly/<this>/. */
  saveFolder?: string;
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
  /** Start streaming one file from the torrent. Returns the indexes actually wanted. */
  play(uri: string, fileIndex: number, options: PlayOptions, extraFiles: string): Promise<number[]>;
  /**
   * Open the running stream in another installed player, resuming at
   * `positionSeconds`. The torrent keeps running — the other app reads from the
   * same local server — so do not stop the stream while it is being watched
   * elsewhere.
   */
  openExternally(url: string, positionSeconds: number): Promise<void>;
  /** Copy the currently-downloaded file to the public Downloads folder.
   *  Returns a `file://` URI for the saved file. */
  copyTo(fileIndex: number, fileName: string): Promise<string | string[]>;
  /** Copy finished wanted videos into Downloads without stopping the torrent. */
  saveReady(): Promise<{ index: number; name: string; uri: string; size: number; id?: string }[]>;
  /** `file://` URI for a finished episode still in the torrent cache. */
  fileUri(index: number, id?: string): Promise<string>;
  /** `file://` URI for a finished file in public Downloads/. */
  downloadUri(fileName: string): Promise<string>;
  /** Delete a finished file from public Downloads/. */
  deleteDownload(fileName: string): Promise<boolean>;
  /**
   * Download an external subtitle — a bare .srt or a zip holding one — into
   * app cache and return a `file://` URI the player can load. `name` is only
   * used to name the file on disk.
   */
  saveSubtitle(url: string, name: string): Promise<string>;
  /** Delete SubDL files previously returned by `saveSubtitle`. Ignores others. */
  deleteSubtitles(paths: string[]): Promise<void>;
  /** Pause the active transfer (Download Manager). */
  pause(): Promise<void>;
  /** Resume a paused transfer. */
  resume(): Promise<void>;
  pauseDownload(id: string): Promise<void>;
  resumeDownload(id: string): Promise<void>;
  stopDownload(id: string): Promise<void>;
  skipDownload(id: string, index: number): Promise<void>;
  includeDownload(id: string, index: number): Promise<void>;
  /** Stop fetching one file; the rest of a season download continues. */
  /**
   * Streaming: fetch the whole file (`true`) or only a window around the
   * playhead (`false`, the default). Off is lighter on data, disk and battery;
   * on plays the rest without waiting. Resets when the stream stops.
   */
  setWholeFile(on: boolean): Promise<void>;
  skipFile(index: number): Promise<void>;
  /** Put a skipped file back into a season download. */
  includeFile(index: number): Promise<void>;
  /** Show / hide the ongoing download notification. */
  setNotify(enabled: boolean): Promise<void>;
  /** Move the prefetch window; `ratio` is 0-1 through the file. */
  setPosition(ratio: number): Promise<void>;
  stop(): Promise<void>;
  /** Delete cached stream piece data. No-op while a torrent is active. */
  clearCache(): Promise<boolean>;
  addListener(
    event: 'log' | 'progress' | 'ready' | 'error',
    cb: (e: any) => void
  ): { remove(): void };
};

export default requireNativeModule<TorrentStream>('TorrentStream');
