# Watchly — phone

Movies and series for Android phones, streamed from torrents while they
download. Native Kotlin + Jetpack Compose, with the TV app's look: the dark Ink
palette, hero art under scrims, title logos, the facts row, the player's
loading screen and its "You're watching" pause card.

Browse a catalogue, open a title, pick a release, watch it. Magnet links and
`.torrent` files shared in from other apps land in the same player.

The repo holds two more apps, each with its own README:

| App | Where | Engine |
|---|---|---|
| Phone (Android) | `mobile/app/` + [`tv/core`](../tv/README.md) | Kotlin + Compose; libtorrent4j + Media3 / libVLC |
| Television (Android TV) | [`tv/`](../tv/README.md) | Kotlin + Compose; libtorrent4j + Media3 / libVLC |
| Windows desktop | [`windows/`](../windows/README.md) | MonoTorrent + LibVLCSharp (.NET 8, WPF) |

The phone and the TV build against one shared module, `tv/core` (package
`com.torrentviewer.core`): the torrent engine, the Media3 and libVLC players,
the indexers, Cinemeta metadata, the store and the theme. `mobile/settings.gradle.kts`
includes it from `../tv/core`, so a fix there reaches both apps.

## Build

```bash
cd mobile && ./gradlew compileDebugKotlin
```

```bash
cd mobile && ./gradlew installRelease
```

Put a free [subdl.com](https://subdl.com) key in `mobile/.env`
(`SUBDL_KEY=…`; the Expo name `EXPO_PUBLIC_SUBDL_KEY` works too) for online
subtitle search; it goes into `BuildConfig` and never into git. Everything else
needs no key.

- **Release** is `com.torrentviewer.app`, signed with the Expo build's debug
  keystore, so it installs over the Expo app in place. On first launch the old
  AsyncStorage database is imported once: history and resume points, My List
  and likes, searches, intro marks, settings, and a download in flight.
- **Debug** is `com.torrentviewer.app.next`, **Watchly Next**: it installs
  beside the release with a library of its own.
- One APK per ABI (libtorrent and libVLC are large): arm64-v8a, armeabi-v7a, x86.
- Incoming peers on **6882**, as before — the TV takes 6881 and Windows 51413,
  since a router forwards a port to one device.

## Layout

`mobile/app/src/main/java/com/torrentviewer/mobile/`:

| File | What |
| --- | --- |
| `MainActivity.kt` | Engine and session (`Services`), picture-in-picture hooks (`Pip`). |
| `App.kt` | Shell: tabs along the bottom upright, the TV's rail when turned; pages stacked over them; profiles; "Opening…"; the file chooser. |
| `Ui.kt` | Touch primitives in the TV's look: press-to-dip, buttons, chips, bottom sheets, poster and landscape cards. |
| `Home.kt` | Swiped billboard, Continue Watching, New Episodes, My List, shelves from what you watch, See all. |
| `Pages.kt` | Shelf grid with sorts, search (titles or releases), library, settings, profiles. |
| `Detail.kt` | Title page: logo over the backdrop, Play, My List, like, trailer, reviews, download; seasons and episodes; sources. |
| `Player.kt` | The touch player. |
| `MediaCard.kt` | The playback card in the shade and on the lock screen, and a headset's buttons. |
| `Downloads.kt`, `DownloadsPage.kt` | The download engine, its foreground service, and the Downloads tab. |
| `Cast.kt` | Google Cast, and handing a stream to another app. |

## Features

**Browsing.** Popularity shelves and a swiped hero from Cinemeta; shelves
built from what you watch; anime from Kitsu; See all with Popular, Top rated,
Newest and A–Z; title search with recent queries, or a search of release names;
My List and likes; a New Episodes shelf for followed series; trailers open in
YouTube; reviews link to IMDb, Rotten Tomatoes, Metacritic and Letterboxd.
Profiles, with PINs — asked for only when the phone cannot tell who is watching.

**The player.** The TV's loading screen (backdrop, logo, a ring that fills) and
its "You're watching" card when paused; Up Next over the last 30 seconds, with
the next file in the pack or the show's next episode found online. Double-tap
the sides to skip (taps add up), hold for 2×, brightness from the slider on the
left of the controls or a swipe down the left edge (volume stays with the
phone's own keys), lock the screen. Audio and subtitle tracks
side by side, OpenSubtitles and SubDL search, subtitle delay; speed and picture fit, remembered
between videos; stats for nerds; skip intro (timings from IntroDB, per episode); sleep timer
(15/30/60 minutes or the end of the episode); picture-in-picture from the Mini
player button or Home; "Open in…" any other video app at the same position;
a playback card in the notification shade and on the lock screen (play/pause,
the scrubber, next episode) that headset buttons reach too.
Media3 first, with libVLC taking over by itself for a file Media3 cannot play.

**Google Cast.** The Cast button in the player hands the stream to a Chromecast
or Cast TV: the engine serves it to the network as well as to itself, the
default media receiver plays it, and the phone becomes the remote. Ending the
session picks the film up on the phone where the TV left it.

**Downloads.** A Download button on films, on episodes, on whole seasons and on
every release. A libtorrent session of its own, a foreground service with a
progress notification, Wi-Fi only (a waiting download can be let through on
mobile data), and the SD card's app folder. Finished videos go to
Movies/Watchly and play from the Downloads tab, resuming like any stream.

**Settings.** Storage for one film, buffer, player, trackers, home genres,
preferred audio and subtitle languages, subtitle size, colour and box, downloads
and storage, backup and restore (one JSON file, the Expo build's format).
