# Watchly for Windows

The phone app (`src/`) as a native Windows desktop app — same screens, same
catalogue and release search, plus a BitTorrent-style download manager.

| Part | Library |
|---|---|
| App / UI | .NET 8 + WPF |
| Player | [LibVLCSharp](https://code.videolan.org/videolan/LibVLCSharp) (VideoLAN's official libVLC binding) — every format VLC plays: HEVC, 10-bit, AV1, EAC3, AC-3, DTS, TrueHD, MKV, AVI; all audio tracks and subtitle formats, GPU decoding |
| Torrents | [MonoTorrent](https://github.com/alanmcgovern/monotorrent) — streaming (serves the file over a loopback HTTP server with range support, fetching around wherever the player reads) and downloading, one engine for both |
| Search, titles, subtitles | Plain HTTP: Cinemeta, apibay / torrents-csv / YTS / EZTV / Torrentio, SubDL — ported from `src/lib` |

## Build and run

Needs the [.NET 8 SDK](https://dotnet.microsoft.com/download/dotnet/8.0).

```bash
dotnet run --project windows/Watchly
```

A distributable folder (runs on any Windows 10/11 x64 PC, no .NET install needed):

```bash
dotnet publish windows/Watchly -c Release -r win-x64 --self-contained -o windows/dist
```

`windows/dist` is a single `Watchly.exe` (~270 MB): the .NET runtime, WPF and
libVLC with its codecs are all inside. VLC can only load codecs from disk, so the
first launch of each build unpacks everything (~250 MB, a few seconds) to
`%TEMP%\.net\Watchly\` and runs from there; later launches start straight away.
Each build unpacks to a folder of its own, so the first launch after every
publish is the slow one — unpacking, Defender scanning the new files, and libVLC
building its plugin cache. At startup the app deletes earlier builds' folders
(≈240 MB each), skipping any whose build is still running.
Publish doesn't clear old files, so delete `windows/dist` before re-publishing,
and close the app first — a running `Watchly.exe` can't be overwritten.
Wrap it with Inno Setup or similar for a single installer.

The SubDL key is read from `mobile/.env` (`SUBDL_KEY` or `EXPO_PUBLIC_SUBDL_KEY`) at build
time, like the phone app; without it online subtitle search is off.

### Self-check

```bash
windows/Watchly/bin/Debug/net8.0-windows/win-x64/Watchly.exe --smoke
```

Runs the phone's `src/lib/*.test.ts` cases against the C# ports, then builds
and lays out every screen offscreen. Exit code 0 = pass; details in
`%TEMP%\watchly-smoke.log`. No window opens and no torrent is touched.

## How it maps to the phone app

| Phone (`src/`) | Windows (`windows/Watchly/`) |
|---|---|
| `App.tsx` (shell, tabs, open → pick file → play) | `MainWindow.xaml.cs` |
| `theme.ts` | `Theme/Dark.xaml`, `Theme/Light.xaml`, `Theme/Styles.xaml` |
| `components/Icon.tsx` (Feather) | `Controls/Icon.cs` |
| `screens/HomeScreen.tsx` | `Views/HomeView.cs` |
| `screens/SearchScreen.tsx` | `Views/SearchView.cs` |
| `screens/DetailScreen.tsx` | `Views/DetailView.cs` |
| `screens/CatalogScreen.tsx` | `Views/CatalogView.cs` |
| `screens/HistoryScreen.tsx` + `HistoryRow.tsx` | `Views/LibraryView.cs` |
| `screens/DownloadsScreen.tsx` + `hooks/useDownloads.ts` | `Views/DownloadsView.xaml` + `Engine/Downloads.cs` |
| `screens/FilesScreen.tsx` | `Views/FilesView.cs` |
| `screens/PlayerScreen.tsx` + `modules/vlc-player` | `Views/PlayerView.cs` |
| `screens/SettingsScreen.tsx` | `Views/SettingsView.cs` |
| `hooks/useTorrent.ts` + `modules/torrent-stream` | `Engine/TorrentEngine.cs` |
| `lib/search.ts`, `meta.ts`, `pick.ts`, `title.ts`, `magnet.ts`, `format.ts`, `net.ts` | `Core/Search.cs`, `Meta.cs`, `Pick.cs`, `TitleText.cs`, `Format.cs`, `Net.cs` |
| `lib/history.ts`, `saved.ts`, `settings.ts`, `watched.ts`, `searches.ts`, `downloadJob.ts` | `Core/Library.cs`, `Engine/Downloads.cs` |
| AsyncStorage | `Core/Store.cs` — JSON files in `%APPDATA%\Watchly` |

## Downloads

- Start from a title page (Play's download button, *Download season*), a search
  result, the file list, a Library row, the player's *Save*, or paste a magnet /
  pick a `.torrent` on the Downloads tab (downloads every file in it).
- Jobs run side by side, survive restarts (fast resume), pause/resume, and each
  file in a pack can be skipped or put back while it runs.
- Files land in `Downloads\Watchly\<title>` (changeable in Settings);
  unfinished files carry a `.!mt` suffix until complete.
- Settings: download/upload speed limits, keep seeding when done.

## Player

- Streams fetch only the next five minutes past the playhead (*Next 5 min*), or
  switch to *Whole file* to fetch everything; the choice is remembered. The
  seek bar shows what is already on disk as a white strip, gaps included.
- *Mini player* (P): a borderless, always-on-top window in a corner of the
  screen, shaped like the video. Drag the picture to move it; hover for
  play/pause, back to the full player, and close.
- Stats panel (ⓘ next to the swarm pill, or I): a graph of download speed
  against the video's own bitrate, then the swarm (seeders, leechers,
  connections, up/down, pieces), codecs, resolution, frame rate, lost frames and
  audio buffers, and libVLC's bitrates. It stays up with the controls hidden and
  shrinks to fit between the top bar and the seek bar.
- Picture modes (best fit, stretch, crop, 16:9, 4:3, original) are applied once
  libVLC's video output exists and only when they change, and Stretch/Crop are
  re-applied after a resize. Setting them while the output was still being
  created crashed libVLC natively on some files.

## Incoming connections

Peers can only connect to you through an open port. The engine listens on a
fixed port, **51413** (peers over TCP on IPv4 and IPv6, DHT over UDP on IPv4), and asks the router to
forward it over UPnP/NAT-PMP. With UPnP on in the router nothing needs setting
up; otherwise forward 51413 (TCP/UDP) to this PC by hand. If another program
already holds 51413 the app falls back to a random port and says so in the log.
The phone (6882) and TV (6881) builds use ports of their own, so all three can
take incoming peers on one network.

## Desktop differences from the phone

- Keyboard: Space/K play-pause, ←/→ skip 10 s, ↑/↓ volume, M mute, F / double-click
  fullscreen, P mini player, I stats, N next episode, Esc back. Mouse back button
  and Esc close pages.
- The brightness rail is a volume rail; the wheel over the video changes volume.
- Shelves page with arrow buttons on hover (Shift+wheel scrolls them).
- Drop a `.torrent` file or a magnet link anywhere on the window, or pass one on
  the command line (`Watchly.exe "magnet:?..."`), for the stream / download picker.
- Casting and "Open in" another app are phone-only.
- The first run shows a Windows Firewall prompt: allow it, or peers cannot
  connect in and torrents are slower.
- Cast photos on title pages come from Wikipedia (Cinemeta has names only), used
  only when the page describes the person as a performer; otherwise initials.
