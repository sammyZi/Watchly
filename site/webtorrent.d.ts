/**
 * Minimal declarations for the webtorrent browser build — the package ships
 * no types. Kept loose on purpose: the engine wrapper (lib/torrent.ts) casts
 * the real objects to its own TorrentLike shape anyway.
 */
declare module 'webtorrent' {
  class WebTorrent {
    constructor(opts?: { dht?: boolean });
    on(event: string, cb: (arg?: any) => void): this;
    once(event: string, cb: (arg?: any) => void): this;
    get(torrentId: string): unknown;
    removeListener(event: string, cb: (...args: any[]) => void): this;
    add(torrentId: string, opts?: unknown): unknown;
    createServer(opts: { controller: unknown }): unknown;
    remove(torrent: unknown): void;
    torrents: any[];
  }
  export default WebTorrent;
}