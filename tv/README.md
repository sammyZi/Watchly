# Watchly TV

The television build: a native Kotlin app (Jetpack Compose, libtorrent4j,
Media3 with a libVLC fallback). Two modules: `app` is the TV shell and its
D-pad UI; `core` is everything the Kotlin phone app in `../mobile` shares with it
(engine, players, addon client, metadata, store, the look). Like the phone, it
ships with no sources: streams and subtitles come from the addons, debrid
service or media server you add (see the [disclaimer](../README.md#disclaimer)).

```bash
cd tv && ./gradlew compileDebugKotlin testDebugUnitTest
```

`applicationId` is `com.torrentviewer.tv`, signed with the same debug keystore
as the old Expo build, so it installs over it in place. On first launch the old
build's AsyncStorage database (`RKStorage`) is imported once, so profiles,
history, My List, recent searches and settings carry over.

"Find subtitles online" asks your subtitle addons; a SubDL key (`SUBDL_KEY=…`) adds SubDL. A TMDB key
(`TMDB_KEY=…`) adds suggestions, age ratings, cast pages and film collections. Both are read from `tv/.env`,
then `mobile/.env`, into `BuildConfig` at build time and never committed.

## Layout

`core/` (package `com.torrentviewer.core`):

| File | What |
| --- | --- |
| `TorrentStream.kt` | The streaming engine: one libtorrent session, piece deadlines aimed at each reader, the rolling cache. See [the architecture doc](../docs/rolling-cache-architecture.md). |
| `Holes.kt`, `cpp/holes.c` | Where a roll cuts, and the native call that frees those stretches of the file in place. |
| `LocalFileServer.kt` | Loopback HTTP server that blocks on pieces that have not landed, so libVLC never reads holes. |
| `VlcView.kt` | libVLC surface: AC3/DTS decoders, PGS subtitles, audio focus, stats. |
| `Watch.kt` | One stream at a time: resolve, pick the file, play, next/previous episode. |
| `Addons.kt` / `Search.kt` / `Meta.kt` | The addons you add ([Stremio addon protocol](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md)): their catalogues, streams and subtitles; Cinemeta metadata; SubDL. |
| `ForYou.kt`, `WatchOrders.kt`, `TmdbTitle.kt` | Suggestions, the watch order list, and the TMDB client: ratings, cast, collections. |
| `Store.kt` | SharedPreferences with the Expo build's keys and JSON; per-profile namespaces; guest in memory only. |
| `ExoView.kt` | Media3 surface with the FFmpeg audio decoders; `VlcView` takes over when it cannot play a file. |
| `Theme.kt` | Ink palette, type, artwork, title logos, facts row, progress line, hero scrims, avatars. |

`app/` (package `com.torrentviewer.tv`):

| File | What |
| --- | --- |
| `Player.kt` | The player. |
| `App.kt`, `Home.kt`, `Detail.kt`, `Pages.kt` | Shell, nav rail, screens. Pages over the tabs stack, and Back takes one off. |
| `ForYou.kt`, `WatchOrder.kt`, `TmdbUi.kt` | The Top on IMDb page, watch orders, cast, actor pages and More like this. |
| `Focus.kt` | Focus rings, TV buttons and chips, D-pad scrolling, the TV-safe gutters. |

## The player

Built for the big screen, driven entirely by the remote:

- **Bar down:** OK plays/pauses, ‹ › scrub (the step grows the longer the key is
  held: 10 s → 2 min) and commit 0.7 s after the last press, ▲ raises the bar on
  the scrubber, ▼ on the buttons. Media keys work everywhere.
- **Bar up:** title and episode top-left; clock, "Ends at", speed and peers
  top-right; a red seek bar with time left, a scrub bubble showing the target
  and the jump; round controls whose names rise above them on focus.
- **Audio & subtitles** side by side, with "Find subtitles online" (your subtitle addons, and SubDL with a key);
  speed, picture fit and "stats for nerds" (codecs, bitrate, dropped frames).
- **Loading** over the title's own backdrop and logo with a ring that fills;
  a stall spinner mid-film; a "You're watching" card when paused; an
  Up Next card with the next episode's still and a filling Play button.
- BACK closes a panel, then the bar, then the stats — and only then leaves.

## TorBox

With a [torbox.app](https://torbox.app) API key in **Settings → TorBox**, a
stream TorBox already has cached shows **Instant** in the sources list and plays
from TorBox's servers over HTTPS instead of from peers; the loading screen says
"Checking TorBox", then "from TorBox". Anything not cached — or any TorBox error —
falls back to the swarm. `tv/core`'s `TorBox.kt` does the talking and `Watch`
asks it first, so the phone gets the same.

## The rolling cache

A 4K film is tens of gigabytes and a TV has single digits free. libtorrent cannot
forget a piece, so `rollCache()` removes the torrent, punches out what has been
watched, and adds it again holding what is left, read-ahead included, so playback
carries on while peers reconnect. Where a hole can't be punched it deletes the file
and starts over at the playhead, one rebuffer per roll. **Settings → Storage for one
film** sets the cap (Auto by default: the free space less 1.5 GB).

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
