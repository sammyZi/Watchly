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
Publish doesn't clear old files, so delete `windows/dist` before re-publishing.
Wrap it with Inno Setup or similar for a single installer.

The SubDL key is read from the repo's `.env` (`EXPO_PUBLIC_SUBDL_KEY`) at build
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

## Desktop differences from the phone

- Keyboard: Space/K play-pause, ←/→ skip 10 s, ↑/↓ volume, M mute, F / double-click
  fullscreen, N next episode, Esc back. Mouse back button and Esc close pages.
- The brightness rail is a volume rail; the wheel over the video changes volume.
- Shelves page with arrow buttons on hover (Shift+wheel scrolls them).
- Drop a `.torrent` file or a magnet link anywhere on the window, or pass one on
  the command line (`Watchly.exe "magnet:?..."`), for the stream / download picker.
- Casting and "Open in" another app are phone-only.
- The first run shows a Windows Firewall prompt: allow it, or peers cannot
  connect in and torrents are slower.
