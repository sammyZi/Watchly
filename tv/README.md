# Watchly TV

The television build: a native Kotlin app (Jetpack Compose, libtorrent4j,
libVLC). Separate from the phone app in `../` — nothing is shared at build time.

```bash
cd tv && ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

`applicationId` is `com.torrentviewer.tv`, signed with the same debug keystore
as the old Expo build, so it installs over it in place. On first launch the old
build's AsyncStorage database (`RKStorage`) is imported once, so profiles,
history, My List, recent searches and settings carry over.

Put a SubDL key in `tv/.env` (see `.env.example`) for "Find subtitles online";
it is read into `BuildConfig.SUBDL_KEY` at build time and never committed.

## Layout

| File | What |
| --- | --- |
| `TorrentStream.kt` | The streaming engine: one libtorrent session, piece deadlines aimed at each reader, the rolling cache. Ported from the Expo build's `TorrentStreamModule` minus downloads/notifications/casting. |
| `LocalFileServer.kt` | Loopback HTTP server that blocks on pieces that have not landed, so libVLC never reads holes. |
| `VlcView.kt` | libVLC surface: AC3/DTS decoders, PGS subtitles, audio focus, stats. |
| `Watch.kt` | One stream at a time: resolve, pick the file, play, next/previous episode. |
| `Search.kt` / `Meta.kt` | Indexers (apibay, torrents-csv, Knaben, Nyaa, YTS, EZTV, Torrentio), Cinemeta metadata, SubDL. |
| `Store.kt` | SharedPreferences with the Expo build's keys and JSON; per-profile namespaces; guest in memory only. |
| `Player.kt` | The player. |
| `App.kt`, `Home.kt`, `Detail.kt`, `Pages.kt`, `Theme.kt` | Shell, nav rail, screens, focus primitives. |

## The player

Netflix-style, driven entirely by the remote:

- **Bar down:** OK plays/pauses, ‹ › scrub (the step grows the longer the key is
  held: 10 s → 2 min) and commit 0.7 s after the last press, ▲ raises the bar on
  the scrubber, ▼ on the buttons. Media keys work everywhere.
- **Bar up:** title and episode top-left; clock, "Ends at", speed and peers
  top-right; a red seek bar with time left, a scrub bubble showing the target
  and the jump; round controls whose names rise above them on focus.
- **Audio & subtitles** side by side, with "Find subtitles online" (SubDL);
  speed, picture fit and "stats for nerds" (codecs, bitrate, dropped frames).
- **Loading** over the title's own backdrop and logo with a ring that fills;
  a stall spinner mid-film; Netflix's "You're watching" card when paused; an
  Up Next card with the next episode's still and a filling Play button.
- BACK closes a panel, then the bar, then the stats — and only then leaves.

## The rolling cache

A 4K film is tens of gigabytes and a TV has single digits free. libtorrent cannot
forget a piece, so `rollCache()` removes the torrent, deletes its data and adds
it again at the playhead; the player's connection survives because the server
blocks until those pieces return. One rebuffer per roll — **Settings → Storage
for one film** (default 4 GB) sets the cap.

## Incoming connections

Fixed port **6881** (IPv4 + IPv6), forwarded by UPnP/NAT-PMP — not the phone's
6882 or the Windows app's 51413, since a router forwards a port to one device.

## Installing on a TV

Most Android TVs are 32-bit ARM. Build that split and install over network debugging:

```bash
cd tv && ./gradlew assembleRelease
```

```bash
adb install -r tv/app/build/outputs/apk/release/app-armeabi-v7a-release.apk
```

The emulator (`Television_4K_API_36`) is x86: install `app-x86-release.apk`.
