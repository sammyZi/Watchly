<h1 align="center">Watchly</h1>

<p align="center">
  <b>A beautiful media player for Android phones, Android TV and Windows.</b><br>
  Bring your own sources — Stremio-compatible addons, a debrid service, or your own media server — and your Stremio account comes along.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-phone-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android phone">
  <img src="https://img.shields.io/badge/Android_TV-Google_TV-2B8CFF?style=flat-square&logo=android&logoColor=white" alt="Android TV and Google TV">
  <img src="https://img.shields.io/badge/Windows-10_%7C_11-0078D4?style=flat-square" alt="Windows 10 and 11">
  <a href="https://github.com/sammyZi/Watchly/releases/latest"><img src="https://img.shields.io/github/v/release/sammyZi/Watchly?style=flat-square&color=E50914&label=release" alt="Latest release"></a>
  <a href="https://github.com/sammyZi/Watchly/releases"><img src="https://img.shields.io/github/downloads/sammyZi/Watchly/total?style=flat-square&color=A855F7" alt="Downloads"></a>
  <img src="https://hits.sh/github.com/sammyZi/Watchly.svg?style=flat-square&label=views&color=F59E0B" alt="Views">
</p>

<p align="center">
  <a href="https://github.com/sammyZi/Watchly/releases/latest"><img src="https://img.shields.io/badge/Download-latest_release-E50914?style=for-the-badge" alt="Download the latest release" height="40"></a>
  &nbsp;
  <a href="#install"><img src="https://img.shields.io/badge/How_to-install-2B2B2B?style=for-the-badge" alt="How to install" height="40"></a>
</p>

<a name="features"></a>
<h2><img src="https://img.shields.io/badge/Features-E50914?style=for-the-badge" alt="Features" height="34"></h2>

<table>
  <tr>
    <td width="33%" valign="top">🧩 <b>Any Stremio addon</b><br>Catalogues, titles, streams and subtitles from the addons you add. Tap <i>Install</i> on an addon's site and pick Watchly.</td>
    <td width="33%" valign="top">🔄 <b>Your Stremio account</b><br>Sign in with a code — no password in Watchly. Your library, Continue Watching and addons follow you, both ways.</td>
    <td width="33%" valign="top">▶️ <b>Plays what you bring</b><br>Debrid links, your own media server, any link you paste. 4K, HDR and every format, on Media3 or VLC.</td>
  </tr>
  <tr>
    <td valign="top">🎬 <b>Every detail</b><br>Ratings, cast, trailers and awards on every title — and Resume right where you stopped.</td>
    <td valign="top">📺 <b>Binge-ready series</b><br>Seasons and episodes, what you've watched marked, and Skip intro.</td>
    <td valign="top">👤 <b>Made yours</b><br>Profiles with PINs, Kids profiles, your favourite genres up front, light and dark themes, subtitles in your language.</td>
  </tr>
</table>

<a name="install"></a>
<h2><img src="https://img.shields.io/badge/Install-2B8CFF?style=for-the-badge" alt="Install" height="34"></h2>

Every file is on the **[latest release](https://github.com/sammyZi/Watchly/releases/latest)**.

| Device | File |
|---|---|
| 🪟 Windows 10 / 11 (64-bit) | `Watchly-windows-…-x64-setup.exe` |
| 📱 Android phones and tablets from the last several years — Samsung, Pixel, OnePlus, Xiaomi, vivo, Oppo, Motorola… | `Watchly-phone-…-arm64-v8a.apk` |
| 📱 Older or entry-level 32-bit phones and tablets | `Watchly-phone-…-armeabi-v7a.apk` |
| 💻 Android emulators, x86 tablets and Chromebooks | `Watchly-phone-…-x86.apk` |
| 📺 Android TVs and Google TVs — Sony BRAVIA, TCL, Philips, Hisense, Xiaomi — and TV boxes / sticks such as Mi Box and Chromecast with Google TV | `Watchly-tv-…-armeabi-v7a.apk` |
| 📺 64-bit TV boxes such as NVIDIA Shield TV | `Watchly-tv-…-arm64-v8a.apk` (the `armeabi-v7a` one runs there too) |
| 📺 Android TV emulator, x86 TV boxes | `Watchly-tv-…-x86.apk` |
| 📦 Any Android phone, tablet or TV — one file that has every version inside | `Watchly-phone-…-universal.apk` / `Watchly-tv-…-universal.apk` (about 3× larger) |

Almost every phone sold in the last several years takes `arm64-v8a`; on a TV, the `armeabi-v7a` file works everywhere ARM.
The `universal` APKs fit every device at once, at a bigger download.

### 🪟 Windows 10 / 11

1. Download `Watchly-windows-…-x64-setup.exe` and run it.
2. Follow the setup — Watchly lands in the Start menu, and on the desktop if you tick it.
3. Allow the Windows Firewall prompt on first launch.

> [!WARNING]
> The installer isn't code-signed yet, so Windows may say **"Windows protected your PC"**.
> Click **More info → Run anyway**.

> [!NOTE]
> To remove it: **Settings → Apps → Watchly → Uninstall**. It asks before deleting your profiles
> and history — downloaded videos are never deleted.

### 📱 Android phone

Download the phone APK in the phone's browser, open it, and allow your browser to install apps
when Android asks.

### 📺 Android TV / Google TV — with LocalSend

> [!TIP]
> A TV has no browser to download with, so send the APK to it from your phone or PC with
> [LocalSend](https://localsend.org) — free, over your own Wi-Fi, nothing uploaded anywhere.

1. **On the TV:** install [LocalSend from Google Play](https://play.google.com/store/apps/details?id=org.localsend.localsend_app)
   and open it. It waits on the **Receive** tab.
2. **On your phone or PC:** install LocalSend too ([Play Store](https://play.google.com/store/apps/details?id=org.localsend.localsend_app),
   or [localsend.org](https://localsend.org) for Windows / Mac / iPhone), and download the TV APK from the release.
3. Keep both on the **same Wi-Fi**. In LocalSend on the phone or PC: **Send → File**, pick the APK,
   then pick your TV from the nearby devices.
4. **On the TV:** accept the file. When it arrives, open it from LocalSend.
5. The first time, Android asks to let LocalSend install apps: choose **Settings**, switch
   **LocalSend** on (on Google TV: **Settings → Apps → Security & restrictions → Unknown sources**),
   go back, and press **Install**.
6. Open Watchly from the TV's apps row. Later updates install the same way, over the old one.

### ✨ First launch

1. Pick a few favourite genres — the home screen leads with them.
2. **Sign in to Stremio** (or skip). You approve on Stremio's own page; your library, Continue Watching and addons come with you.
3. No Stremio account? Open the **Addons** tab and paste an addon's link, or tap **Install** on an addon's site and choose Watchly.
4. On the TV, **Settings → Addons → Copy addons from phone** brings every addon over with one QR scan.

<a name="the-apps"></a>
<h2><img src="https://img.shields.io/badge/The_apps-3ECF8E?style=for-the-badge" alt="The apps" height="34"></h2>

Three apps, each with its own README:

| App | Folder | Stack |
|---|---|---|
| 📱 Phone (Android) | [`mobile/`](mobile/README.md) | Kotlin + Compose on `tv/core`; Media3 / libVLC |
| 📺 Television (Android TV) | [`tv/`](tv/README.md) | Kotlin + Compose; Media3 / libVLC |
| 🪟 Windows desktop | [`windows/`](windows/README.md) | LibVLCSharp (.NET 8, WPF) |

The phone and the TV share one Gradle module, `tv/core`: the addon client, the
Stremio account sync, the players, metadata, the store and the look.

```bash
cd mobile && ./gradlew compileDebugKotlin
```

```bash
cd tv && ./gradlew compileDebugKotlin testDebugUnitTest
```

[`docs/`](docs/rolling-cache-architecture.md) describes how streams reach the
player, and the streaming cache the apps share.

<a name="built-on"></a>
<h2><img src="https://img.shields.io/badge/Built_on-A855F7?style=for-the-badge" alt="Built on" height="34"></h2>

| | |
|---|---|
| 🧩 Sources | The [Stremio addon protocol](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md): catalogues, titles, streams and subtitles from addons you add yourself |
| 🔄 Sync | Your [Stremio](https://www.stremio.com) account: library, Continue Watching, watched marks and addons |
| 🎞️ Players | [AndroidX Media3](https://github.com/androidx/media) with the [Jellyfin FFmpeg decoder](https://github.com/jellyfin/jellyfin-androidx-media) · [VLC / libVLC](https://www.videolan.org/vlc/libvlc.html) and [LibVLCSharp](https://github.com/videolan/libvlcsharp) |
| 🎨 Look | [Jetpack Compose](https://developer.android.com/jetpack/compose) · [Coil](https://github.com/coil-kt/coil) · [Lucide](https://lucide.dev) icons |
| 🗂️ Titles &amp; art | [Cinemeta](https://v3-cinemeta.strem.io), Stremio's public catalogue · [TVmaze](https://www.tvmaze.com) episode stills |
| ⏭️ Intros | [IntroDB](https://introdb.app) |
| 💬 Subtitles | Subtitle addons you add · [SubDL](https://subdl.com) with your own free key |
| 🔗 Peer streams | For addons that send them: [libtorrent](https://libtorrent.org) via [libtorrent4j](https://github.com/aldenml/libtorrent4j) on Android · [MonoTorrent](https://github.com/alanmcgovern/monotorrent) on Windows |

Licenses for all of these are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

<a name="disclaimer"></a>
<h2><img src="https://img.shields.io/badge/Disclaimer-5B6472?style=for-the-badge" alt="Disclaimer" height="34"></h2>

> [!CAUTION]
> Watchly is only a media player. It does not host, upload, share, index or own any content, and it
> ships with no sources: what it plays comes from addons, links and servers you add yourself. Watchly
> does not run, endorse or control any of them. You are responsible for what you add and play, and for
> following the copyright laws where you live. Please only watch what you have the right to.
>
> Watchly is an independent project. It is not affiliated with, endorsed by or sponsored by Stremio,
> any addon, debrid service or metadata provider, Google, Microsoft or any studio. Names and logos of
> those products are trademarks of their owners and are used here only to describe compatibility.
> Titles, posters, artwork and other metadata shown inside the apps come from the services you use
> and belong to their respective owners.
>
> Copyright holders: Watchly holds nothing to take down. Notices about content belong with the addon
> or server that serves it; for anything about this repository itself, open an issue.
