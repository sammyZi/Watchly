# Watchly

Movies and series for Android, streamed from torrents while they download (Expo).

Browse a catalogue, open a title, pick a release, watch it. Magnet links and
`.torrent` files shared in from other apps still work and land in the same
player. Android only.

## Run

```bash
npx expo run:android
```

Not Expo Go — the app contains two native modules, so it needs a dev build.
Anything under `modules/*/android/` needs a rebuild, not a reload.
A debug APK sits at `android/app/build/outputs/apk/debug/app-debug.apk`.

Copy `.env.example` to `.env` and add a free [subdl.com](https://subdl.com) key
for online subtitle search. Everything else works without a key: metadata comes
from Stremio's public Cinemeta service and the torrent indexes are open.

Checks:

```bash
npx tsc --noEmit && node --experimental-strip-types --test src/lib/*.test.ts
```

## Layout

```
src/
  App.tsx                      tabs, playback wiring, deep links, saved lists
  hooks/useTorrent.ts          native engine lifecycle (one stream at a time)
  screens/HomeScreen.tsx       hero + shelves, recommended from what you watch
  screens/SearchScreen.tsx     title search, recent queries, genre chips
  screens/DetailScreen.tsx     one title: episodes, releases, quality picker
  screens/CatalogScreen.tsx    a whole shelf as a grid
  screens/HistoryScreen.tsx    library, watch later, favourites
  screens/DownloadsScreen.tsx  active transfer and finished files
  screens/FilesScreen.tsx      pick a file inside a multi-file torrent
  screens/PlayerScreen.tsx     video, overlay controls, up next, subtitles
  screens/SettingsScreen.tsx   buffer size, trackers, cache, theme
  components/                  TabBar, Screen, PosterGrid, Marquee, Offline,
                               Thumb, HistoryRow, Button, ProgressBar, …
  lib/meta.ts                  Cinemeta titles, shelves, genre recommendations
  lib/search.ts                torrent indexes, ranking, relevance filtering
  lib/subdl.ts                 subtitle search
  lib/net.ts                   one fetch wrapper: offline state + auto retry
  lib/pick.ts                  which file in a torrent, and what plays next
  lib/saved.ts                 favourites and watch later
  lib/history.ts               AsyncStorage recents (50 max), resume points
  lib/searches.ts              recent search queries
  lib/watched.ts               episode -> torrent links
  lib/title.ts                 release name -> searchable title, poster hue
  lib/magnet.ts                tracker injection, deep-link detection
  lib/format.ts                speed, clock, time remaining, byte sizes
modules/torrent-stream/
  index.ts                     JS surface
  android/…/TorrentStreamModule.kt   libtorrent4j session + piece scheduler
  android/…/LocalFileServer.kt       localhost HTTP server over the partial file
modules/vlc-player/
  index.ts                     JS surface
  android/…/VlcPlayerView.kt         libVLC video surface
```

## Features

**Browsing**

- Popularity shelves and a swipeable hero, from Cinemeta — no API key
- Recommendations built from what you actually watch: genres are tallied from
  continue-watching first, then the rest of the library, then your saved lists,
  and the hero follows the top one
- Anime is its own catalogue (Stremio's Kitsu addon), because Cinemeta has no
  anime genre — its list stops at "Animation", which is half western cartoons
- Title search with recent queries, plus favourites and watch later

**Releases**

- Two indexes per search — apibay, and Torrentio covering 1337x, YTS, EZTV,
  RARBG and ThePirateBay in one request — merged by info hash, so one of them
  being blocked costs half a list rather than an error page
- A title is searched under its own name *and* its short name: metadata
  services rename films as franchises grow ("Dune: Part One"), while every
  index still files it as "Dune 2021"
- Collection packs that do not name the title are dropped. They arrive with a
  catalogue's worth of seeders and cannot be played anyway — the file picker
  takes the largest video, which in a 250-film pack is somebody else's film
- Quality picker (Best / 4K / 1080p / 720p) driving Play, Download and the
  release list together

**Playing**

- Multi-file torrents: pick any file, grouped by folder and sorted naturally
  so episode 2 lands between 1 and 10
- Resume per file — position checkpointed on exit and when the app backgrounds,
  and the engine buffers *at* the resume point rather than the start of the file
- Up next: a card over the last 30 seconds, and a control in the overlay. Uses
  the next file in a season pack, or finds a release for the next episode
- Custom player overlay: scrub bar, ±10s buttons, double-tap sides to skip,
  playback speed, audio and subtitle track pickers, fit modes, lock, live
  swarm stats
- Subtitles from inside the torrent, or searched online via SubDL. SubDL only
  serves zips, so the download, unpack and write happen natively: only `.srt`
  entries are extracted, into app-private cache, under a name the app chooses,
  with size and entry caps
- Google Cast, and handing the running stream to VLC, MX Player or anything
  else installed
- Download mode: keep a file rather than stream it, with a progress
  notification, then play it back from Downloads

**Elsewhere**

- Offline is a strip, not a wall: shelves, library and finished downloads still
  work from cache, and every failed load re-runs by itself when the connection
  comes back
- Settings: pre-roll buffer size, whether to inject public trackers, whether to
  wipe piece data when a stream stops, and the theme

## Why libVLC and not ExoPlayer

`modules/vlc-player` is a small Expo view module around
[libVLC](https://mvnrepository.com/artifact/org.videolan.android/libvlc-all).

react-native-video and Media3 defer to the platform `MediaCodec` for audio, and
Android devices are not required to ship AC3, E-AC3 or DTS decoders — most do
not, for licensing reasons. A release with `AC3 5.1` in the name therefore plays
as picture with silence, and no player prop fixes it: Media3's only Android
extensions are RTSP/SmoothStreaming/DASH/HLS, which are transports, not codecs.
The FFmpeg extension that would fix it has to be compiled from source with the
NDK.

libVLC carries its own decoders, so that class of file simply plays, and it
renders bitmap subtitles (PGS, VOBSUB) that Media3 cannot. The cost is size:
38-50 MB of native code per ABI, which is what the ABI splits in
`android/app/build.gradle` are there to contain.

## How the streaming works

`modules/torrent-stream` is an Expo native module wrapping
[libtorrent4j](https://github.com/aldenml/libtorrent4j).

The chosen file is served to the player over an HTTP server on localhost
rather than as a `file://` path. That matters: the file on disk is
sparse, so the parts that have not arrived read back as zeros instead of
blocking, and the player sees corrupt data. Serving it over HTTP lets the
reader block until the piece lands, and lets the player use range requests.

Piece scheduling is deadline-driven, not sequential:

- Every live HTTP reader gets its own window of pieces, and every piece in a
  window carries a staggered `set_piece_deadline`. Pieces outside the windows
  are set to `IGNORE`, with a small `LOW` band ahead of each so the wanted set
  can never be fully satisfied — a torrent that finishes what it wants drops
  its seeds.
- **Every width is a byte budget, never a piece count.** Piece size varies by
  two orders of magnitude between torrents, so a fixed count is a 3MB window on
  one and a 300MB window on another. The two piece-count floors that used to sit
  under these are gone: they only ever bound on torrents with *large* pieces,
  which is precisely where they hurt. On a season pack with 8MB pieces the
  "32MB" rush window was really 96MB and the "128MB" zone was 384MB — the swarm
  spread itself over all of it, and the one piece the viewer was blocked on
  arrived at a twelfth of the line rate.
- `SEQUENTIAL_DOWNLOAD` is deliberately **off**. libtorrent's own
  [streaming docs](https://libtorrent.org/streaming.html) advise against it:
  it hands pieces out in index order regardless of peer speed, so piece 0 can
  land on the slowest peer in the swarm. Worse, in sequential mode the picker
  treats every non-zero priority as equally eligible, so demoting a piece to
  `LOW` does nothing — only `IGNORE` removes it from the running.
- Before playback starts, two regions are fetched: the container header at
  byte 0, and the resume point. Players read the header before they can seek
  anywhere, so buffering only the resume point stalls the first frame.
- The tail is prefetched too. MP4 `moov` and Matroska `Cues` live at the end of
  the file and are read before the first frame; for a multi-GB feature that
  index runs to double-digit MB. A reader sitting *in* the tail is that index
  being read at open rather than playback, so it is ignored when aiming the
  zone — otherwise the nearly-complete pre-roll is thrown away and rebuilt a
  breath later when the player seeks back to byte zero.

Metadata, discovered peers, and the DHT routing table are cached in
`filesDir/torrent-cache/` so a second open starts warm. Metadata from a magnet
is a view onto memory the torrent owns, so it used to die with the torrent and
cost another 5-10s of DHT chatter on every open; it is now written back out as
a `.torrent` (`write_torrent_file_buf_ex`) and re-read from disk. That cache
survives app close and reboot — it lives in `filesDir`, not `cacheDir`, and the
stream-cache wipe does not touch it. Piece data lives elsewhere and is wiped
when the stream stops.

## Notes

- Release builds split by ABI — install `app-arm64-v8a-release.apk`. libtorrent4j
  and libVLC both ship large native libraries per architecture, so a universal
  APK would make every device carry code it cannot run.
- iOS is unsupported — no comparable torrent library exists for it.
- Nothing prunes the metadata cache. It is a few hundred KB per distinct torrent
  ever opened, so hundreds of them come to tens of MB.
- A shared `content://` URI is copied into app storage on arrival. The grant
  behind one is tied to the delivering intent and does not survive a relaunch,
  so it cannot be read twice or resumed from history later.
- Up next only crosses torrents for a series opened from a title page, since
  that is where the episode order comes from.
