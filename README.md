# Torrent Viewer

Paste a magnet link or open a `.torrent`, watch the video while it downloads.
Android only.

## Run

```bash
npx expo run:android
```

Not Expo Go — the app contains a native module, so it needs a dev build.
A debug APK sits at `android/app/build/outputs/apk/debug/app-debug.apk`.

Checks:

```bash
npx tsc --noEmit && node --experimental-strip-types --test src/lib/*.test.ts
```

## Layout

```
src/
  App.tsx                      tab switch, history wiring, deep links
  hooks/useTorrent.ts          native engine lifecycle (one stream at a time)
  screens/StreamScreen.tsx     source input + continue watching
  screens/FilesScreen.tsx      pick a file inside a multi-file torrent
  screens/PlayerScreen.tsx     video and its overlay controls
  screens/HistoryScreen.tsx    library, with search
  screens/SettingsScreen.tsx   buffer size, trackers, cache
  components/                  TabBar, Screen, SourceInput, ResumeCard,
                               HistoryRow, Thumb, Button, ProgressBar, …
  lib/history.ts               AsyncStorage recents (50 max)
  lib/settings.ts              user preferences
  lib/poster.ts                frame grab for library tiles
  lib/title.ts                 magnet/URL -> display name, poster hue
  lib/format.ts                speed, clock, time remaining, byte sizes
modules/torrent-stream/
  index.ts                     JS surface
  android/…/TorrentStreamModule.kt   libtorrent4j session + piece scheduler
  android/…/LocalFileServer.kt       localhost HTTP server over the partial file
```

## Features

- Magnet links, `.torrent` URLs, local `.torrent` files, and files shared in
  from other apps
- Multi-file torrents: pick any file, grouped by folder and sorted naturally
  so episode 2 lands between 1 and 10
- Library with search across torrent names *and* the files inside them
- Resume per file — position checkpointed on exit and when the app backgrounds,
  and the engine buffers *at* the resume point rather than the start of the file
- Custom player overlay: scrub bar, ±10s buttons, double-tap sides to skip,
  playback speed, embedded subtitle track picker, live swarm stats
- Library tiles show a frame sampled from what you were watching, falling back
  to a colour derived from the torrent name
- Settings: pre-roll buffer size, whether to inject public trackers, and
  whether to wipe piece data when a stream stops

## How the streaming works

`modules/torrent-stream` is an Expo native module wrapping
[libtorrent4j](https://github.com/aldenml/libtorrent4j).

The chosen file is served to `react-native-video` over an HTTP server on
localhost rather than as a `file://` path. That matters: the file on disk is
sparse, so the parts that have not arrived read back as zeros instead of
blocking, and the player sees corrupt data. Serving it over HTTP lets the
reader block until the piece lands, and lets the player use range requests.

Piece scheduling is deadline-driven, not sequential:

- Every live HTTP reader gets its own window of pieces, and every piece in a
  window carries a staggered `set_piece_deadline`. Pieces outside the windows
  are set to `IGNORE`, with a small `LOW` band ahead of each so the wanted set
  can never be fully satisfied — a torrent that finishes what it wants drops
  its seeds.
- `SEQUENTIAL_DOWNLOAD` is deliberately **off**. libtorrent's own
  [streaming docs](https://libtorrent.org/streaming.html) advise against it:
  it hands pieces out in index order regardless of peer speed, so piece 0 can
  land on the slowest peer in the swarm. Worse, in sequential mode the picker
  treats every non-zero priority as equally eligible, so demoting a piece to
  `LOW` does nothing — only `IGNORE` removes it from the running.
- Before playback starts, two regions are fetched: the container header at
  byte 0, and the resume point. Players read the header before they can seek
  anywhere, so buffering only the resume point stalls the first frame.
- The tail is prefetched too, scaled to ~0.5% of the file. MP4 `moov` and
  Matroska `Cues` live at the end of the file and are read before the first
  frame; for a multi-GB feature that index runs to double-digit MB.

Metadata, discovered peers, and the DHT routing table are cached in
`filesDir/torrent-cache/` so a second open starts warm. Piece data goes to the
cache directory and is wiped when the stream stops.

## Notes

- Debug APK is ~190 MB because libtorrent4j ships for four ABIs. A release
  build with ABI splits is roughly 35 MB each.
- iOS is unsupported — no comparable torrent library exists for it.
- `Item.subtitle` exists in the history model but nothing sets it; the subtitle
  picker only lists tracks embedded in the video file.
- A shared `content://` URI is copied into app storage on arrival. The grant
  behind one is tied to the delivering intent and does not survive a relaunch,
  so it cannot be read twice or resumed from history later.
