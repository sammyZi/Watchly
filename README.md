<p align="center">
  <img src="docs/images/banner.jpg" alt="Watchly on Android: Home, a film, a series and search">
</p>

# Watchly

Stream or download movies and series from torrents — on Android phones,
Android TV and Windows.

**[Download the latest release](https://github.com/sammyZi/Watchly/releases/latest)** —
phone and TV APKs and the Windows installer. How to install each is [below](#install).

- **Stream instantly** — press play and watch right away, and resume where you left off
- **Every detail** — ratings, cast, trailers and awards on every title
- **Binge-ready series** — seasons and episodes, with what you've watched marked and Skip intro
- **Download & go** — download an episode or a whole season to watch offline
- **Find anything** — search titles, or torrents by name
- **Made yours** — profiles with PINs, light and dark themes, Media3 or VLC, subtitles in your language

## Install

Every file is on the [latest release](https://github.com/sammyZi/Watchly/releases/latest).

### Windows 10 / 11

1. Download `Watchly-windows-…-x64-setup.exe` and run it.
2. Windows may say **"Windows protected your PC"** — the installer isn't code-signed yet. Click
   **More info → Run anyway**.
3. Follow the setup. Watchly lands in the Start menu (and on the desktop, if you tick it).
   Allow the Windows Firewall prompt on first launch so other peers can connect.

To remove it: **Settings → Apps → Watchly → Uninstall**. It asks before deleting your profiles
and history; downloaded videos are never deleted.

### Android phone

Download the phone APK (`…-phone-…-arm64-v8a.apk` on most phones) in the phone's browser, open
it, and allow your browser to install apps when Android asks.

### Android TV / Google TV — with LocalSend

A TV has no browser to download with, so send the APK to it from your phone or PC with
[LocalSend](https://localsend.org) — free, over your own Wi-Fi, nothing uploaded anywhere.

1. **On the TV:** install [LocalSend from Google Play](https://play.google.com/store/apps/details?id=org.localsend.localsend_app)
   and open it. It waits on the **Receive** tab.
2. **On your phone or PC:** install LocalSend too ([Play Store](https://play.google.com/store/apps/details?id=org.localsend.localsend_app),
   or [localsend.org](https://localsend.org) for Windows / Mac / iPhone) and download the TV APK from the release —
   `…-tv-…-armeabi-v7a.apk` for most TVs and boxes, `…-tv-…-arm64-v8a.apk` for Shield and
   Chromecast with Google TV, or `…-tv-…-universal.apk` if unsure.
3. Keep both on the **same Wi-Fi**. In LocalSend on the phone or PC: **Send → File**, pick the APK,
   then pick your TV from the nearby devices.
4. **On the TV:** accept the file. When it arrives, open it from LocalSend.
5. The first time, Android asks to let LocalSend install apps: choose **Settings**, switch
   **LocalSend** on (on Google TV it's under **Settings → Apps → Security & restrictions → Unknown sources**),
   go back, and press **Install**.
6. Open Watchly from the TV's apps row. Later updates install the same way, over the old one.

Prefer to watch it done? [Send an APK to Android TV / Google TV and install it](https://www.youtube.com/watch?v=f3TDtpFK_-g) (YouTube).

## Disclaimer

Watchly is only a player. It does not host, upload, share or own any videos or
torrent files, and none come with it. It searches public, third-party indexes
and plays what you choose, through the BitTorrent protocol, the way any torrent
client does. You are responsible for what you stream or download, and for
following the copyright laws where you live. Please only watch what you have
the right to.

Watchly is built on open-source projects:

- [libtorrent](https://libtorrent.org) via [libtorrent4j](https://github.com/aldenml/libtorrent4j) — the torrent engine on Android
- [MonoTorrent](https://github.com/alanmcgovern/monotorrent) — the torrent engine on Windows
- [AndroidX Media3](https://github.com/androidx/media) with the [Jellyfin FFmpeg decoder](https://github.com/jellyfin/jellyfin-androidx-media) — the default player
- [VLC / libVLC](https://www.videolan.org/vlc/libvlc.html) and [LibVLCSharp](https://github.com/videolan/libvlcsharp) — the fallback player, and the player on Windows
- [Jetpack Compose](https://developer.android.com/jetpack/compose), [Coil](https://github.com/coil-kt/coil) and [Lucide](https://lucide.dev) icons — the look

Titles, artwork and episode lists come from [Cinemeta](https://v3-cinemeta.strem.io), Stremio's public catalogue,
intro timings from [IntroDB](https://introdb.app) and subtitles from [OpenSubtitles](https://www.opensubtitles.org) (through Stremio's add-on, no key needed) and [SubDL](https://subdl.com).

## Screenshots

### Phone

<table>
  <tr>
    <td><img src="docs/images/screen-1-home.jpg" alt="Home" width="400"></td>
    <td><img src="docs/images/screen-2-title.jpg" alt="A film's page" width="400"></td>
  </tr>
  <tr>
    <td><img src="docs/images/screen-3-series.jpg" alt="A series' page" width="400"></td>
    <td><img src="docs/images/screen-4-episodes.jpg" alt="Episodes and downloads" width="400"></td>
  </tr>
  <tr>
    <td><img src="docs/images/screen-5-search.jpg" alt="Search" width="400"></td>
    <td><img src="docs/images/screen-6-settings.jpg" alt="Settings" width="400"></td>
  </tr>
</table>

### TV

<table>
  <tr>
    <td align="center"><img src="docs/images/tv-0-profiles.jpg" alt="Who's watching" width="800"></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/tv-1-home.jpg" alt="Home" width="800"></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/tv-2-title.jpg" alt="A film's page" width="800"></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/tv-4-sources-4k.jpg" alt="Sources filtered to 4K" width="800"></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/tv-5-search.jpg" alt="Search" width="800"></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/tv-6-series.jpg" alt="A series' page" width="800"></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/tv-7-episodes.jpg" alt="Episodes" width="800"></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/tv-8-settings.jpg" alt="Settings" width="800"></td>
  </tr>
</table>

## The apps

Three apps, each with its own README:

| App | Folder | Stack |
|---|---|---|
| Phone (Android) | [`mobile/`](mobile/README.md) | Kotlin + Compose on `tv/core`; libtorrent4j + Media3 / libVLC |
| Television (Android TV) | [`tv/`](tv/README.md) | Kotlin + Compose; libtorrent4j + Media3 / libVLC |
| Windows desktop | [`windows/`](windows/README.md) | MonoTorrent + LibVLCSharp (.NET 8, WPF) |

The phone and the TV share one Gradle module, `tv/core`: the torrent engine,
the players, indexers, metadata, the store and the look.

```bash
cd mobile && ./gradlew compileDebugKotlin
```

```bash
cd tv && ./gradlew compileDebugKotlin testDebugUnitTest
```

[`docs/`](docs/rolling-cache-architecture.md) has the rolling-cache design the
apps share.
