# Watchly

Movies and series for Android, streamed from torrents while they download (Expo).

Browse a catalogue, open a title, pick a release, watch it. Magnet links and
`.torrent` files shared in from other apps still work and land in the same
player.

This README is the phone app (`mobile/`). The repo holds two more, each with its own README:

| App | Where | Engine |
|---|---|---|
| Phone (Android) | `mobile/src/`, `mobile/android/`, `mobile/modules/` | libtorrent4j + libVLC |
| Television (Android TV) | [`tv/`](../tv/README.md) | native Kotlin + Compose; libtorrent4j + libVLC |
| Windows desktop | [`windows/`](../windows/README.md) | MonoTorrent + LibVLCSharp (.NET 8, WPF) |

## Kotlin rewrite (in progress)

`mobile/app/` is the phone app again in native Kotlin + Compose, with the TV
app's look, built against the TV's shared module (`../tv/core`: engine,
players, indexers, metadata, store, theme). It lives beside the Expo project
until it reaches parity, then replaces it.

```bash
cd mobile && ./gradlew compileDebugKotlin
```

Debug builds install beside the Expo app as **Watchly Next**
(`com.torrentviewer.app.next`, so a fresh library); release builds take the
Expo app's id and key, install over it and import its AsyncStorage once.
Incoming peers on 6882, as before. Done so far: tabs (bottom bar upright, rail
turned), Home hero and shelves, See all, search, title page (episodes, sources,
My List, likes, trailer, reviews), library, settings, profiles, and a first
player (Loading and "You're watching" screens, double-tap skip, resume).

## Run (Expo)

From `mobile/`:

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
  screens/SettingsScreen.tsx   buffer size, trackers, cache, theme, languages,
                               subtitle style, Wi-Fi only, SD card, backup
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
  lib/lang.ts                  preferred audio/subtitle language matching
  lib/intros.ts                per-show intro window for Skip intro
  lib/newEpisodes.ts           followed series with episodes out since last look
  lib/backup.ts                library to and from one JSON file
modules/torrent-stream/
  index.ts                     JS surface
  android/…/TorrentStreamModule.kt   libtorrent4j session + piece scheduler
  android/…/LocalFileServer.kt       localhost HTTP server over the partial file
modules/vlc-player/
  index.ts                     JS surface
  android/…/VlcPlayerView.kt         libVLC video surface, picture-in-picture,
                                     playback stats
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
- New episodes: a shelf of watched or saved series with episodes out since
  their page was last opened. Checked when Home builds, off the day-long
  metadata cache, so an episode shows up within a day of airing
- Trailers play in the app, over the title page, in YouTube's embedded player;
  turn the phone for full screen. *YouTube* in its top bar opens the app
  instead, for the odd upload that disallows embedding

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
- Picture-in-picture: *Mini player* in the bottom row, and on Android 12+
  pressing Home while a video plays does the same. The controls hide in the
  window; closing it pauses and saves the position like leaving the app
- Stats panel (ⓘ in the top bar), like VLC's statistics window: two minutes of
  download speed against the video's own bitrate — blue above white means the
  buffer is growing — then the swarm, the video and audio codecs, resolution,
  frame rate, lost frames and audio buffers, and libVLC's bitrates. It stays up
  with the controls hidden and only reads libVLC while it is open
- Subtitle delay: −/+ in the subtitle picker, 0.1 s a tap, held for 1 s; tap
  the value to reset it
- Preferred subtitle and audio languages (Settings) are picked automatically
  when the file has a matching track, and online subtitle search uses the
  subtitle language. Picking a track yourself wins
- Subtitle size, colour (white or yellow) and background box, in Settings and
  in the player's subtitle panel. libVLC only reads them when it starts, so a
  change mid-film restarts the player in place — a blink, same position
- Swipe up or down on the left edge for brightness
- Sleep timer: 15, 30 or 60 minutes, or the end of the episode
- Skip intro: *Mark intro* at the start and again at the end of a show's
  opening; every episode of that show then offers *Skip intro*
- The chosen audio track survives coming back to the app. Returning reloads the
  stream into a fresh player, which used to start on the file's default dub
  while the picker still showed the viewer's choice
- Subtitles from inside the torrent, or searched online via SubDL. SubDL only
  serves zips, so the download, unpack and write happen natively: only `.srt`
  entries are extracted, into app-private cache, under a name the app chooses,
  with size and entry caps
- Google Cast, and handing the running stream to VLC, MX Player or anything
  else installed
- Download mode: keep a file rather than stream it, with a progress
  notification, then play it back from Downloads
- Wi-Fi only downloads, enforced natively so it holds in the background: on
  mobile data a download waits ("Waiting for Wi-Fi" in the notification and the
  Downloads tab) and carries on when Wi-Fi returns. *Use mobile data* on a
  waiting download lets that one continue anyway
- Save to SD card: torrents and finished downloads go to the card's app folder
  instead of the phone. Only switchable while nothing is streaming or
  downloading, and files there are removed if the app is uninstalled

**Elsewhere**

- Offline is a strip, not a wall: shelves, library and finished downloads still
  work from cache, and every failed load re-runs by itself when the connection
  comes back
- Settings: pre-roll buffer size, whether to inject public trackers, whether to
  wipe piece data when a stream stops, and the theme
- Backup and restore: history, resume points, saved lists, settings, searches
  and intro marks as one JSON file written to Downloads; restore picks the
  file. Finished downloads are not included — they are files on this phone

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

## Incoming connections

A peer can only connect if one side accepts connections, and most home users
sit behind a router. Without an open port you reach only the peers that have
one, which is a small part of most swarms.

The engine listens on one fixed port, **6882**, on IPv4 and IPv6, and asks the
router to forward it with UPnP and NAT-PMP. With UPnP on in the router nothing
needs setting up; without it, forward 6882 (TCP and UDP) to the phone by hand.
The phone, the TV and the Windows app each use their own port, because a router
forwards a port to one device only and all three can be on one network at once:

| App | Port |
|---|---|
| Windows | 51413 |
| TV | 6881 |
| Phone | 6882 |

On mobile data no port can be opened: carriers share one IPv4 address between
many subscribers. IPv6 still gets through where the carrier provides it.

## Notes

- Release builds split by ABI — install `app-arm64-v8a-release.apk`. libtorrent4j
  and libVLC both ship large native libraries per architecture, so a universal
  APK would make every device carry code it cannot run.
- iOS is unsupported — no comparable torrent library exists for it.
- Picture-in-picture needs `android:supportsPictureInPicture="true"` on
  `MainActivity`, which is set by hand in `android/app/src/main/AndroidManifest.xml`.
  `expo prebuild --clean` regenerates that file and drops it.
- Nothing prunes the metadata cache. It is a few hundred KB per distinct torrent
  ever opened, so hundreds of them come to tens of MB.
- A shared `content://` URI is copied into app storage on arrival. The grant
  behind one is tied to the delivering intent and does not survive a relaunch,
  so it cannot be read twice or resumed from history later.
- Up next only crosses torrents for a series opened from a title page, since
  that is where the episode order comes from.
