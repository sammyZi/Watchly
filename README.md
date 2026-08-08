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
npx tsc --noEmit && node --experimental-strip-types src/lib/format.test.ts && node --experimental-strip-types src/lib/title.test.ts
```

## Layout

```
src/
  App.tsx                     screen switch + history wiring
  hooks/useTorrent.ts         native engine lifecycle (one stream at a time)
  screens/HomeScreen.tsx      input + recent list
  screens/BufferingScreen.tsx peer/buffer progress before playback
  screens/PlayerScreen.tsx    video, subtitles, resume point
  components/                 SourceInput, HistoryRow, ProgressBar, Chip
  lib/history.ts              AsyncStorage-backed recents (50 max)
  lib/title.ts                magnet/URL -> display name
  lib/format.ts               speed, clock, relative time
modules/torrent-stream/       Expo native module -> TorrentStream-Android
```

## Features

- Magnet links, `.torrent` URLs, and local `.torrent` files (document picker)
- Clipboard paste button
- History in AsyncStorage: title, last-opened, resume point, progress bar;
  long-press a row to delete, or clear the lot
- Resume where you left off — position is checkpointed on exit and whenever
  the app leaves the foreground
- Buffering screen with buffer %, download speed, seed count, cancel
- ExoPlayer's native controls: seek, fullscreen, playback speed, audio-track
  and **embedded subtitle** selection (MKV/MP4 tracks appear automatically)
- External `.srt` / `.vtt` can be attached from the player and is remembered
  per torrent
- Live download stats over the video, toggleable
- Hardware back leaves the stream instead of killing the app

## How the streaming works

`modules/torrent-stream` wraps [TorrentStream-Android](https://github.com/TorrentStream/TorrentStream-Android)
(jlibtorrent). It downloads the largest video file in the torrent *sequentially*
into the app cache and emits `ready` with a `file://` path once the head of the
file is buffered; `react-native-video` plays that file while the rest arrives.
Files are deleted when the stream stops.

## Notes

- Debug APK is ~190 MB because jlibtorrent ships for four ABIs. A release build
  with ABI splits is roughly 35 MB each.
- iOS is unsupported — no comparable torrent library exists for it.
- Multi-file torrents play the largest video only; there is no file picker.
