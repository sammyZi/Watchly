# Third-party notices

Watchly's own source code is under the [MIT License](LICENSE). The apps are built with the
open-source components below, each under its own license. The full license texts are at the links.

## Android phone and TV (`mobile/`, `tv/`)

| Component | License |
|---|---|
| [AndroidX Media3](https://github.com/androidx/media) (ExoPlayer) | [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| [Jellyfin Media3 FFmpeg decoder](https://github.com/jellyfin/jellyfin-androidx-media), which includes [FFmpeg](https://ffmpeg.org) | [GPL 3.0](https://www.gnu.org/licenses/gpl-3.0.txt) |
| [LibVLC for Android](https://code.videolan.org/videolan/vlc-android) | [LGPL 2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.en.html) |
| [libtorrent4j](https://github.com/aldenml/libtorrent4j), which includes [libtorrent](https://libtorrent.org) | [MIT](https://opensource.org/license/mit) · libtorrent: [BSD 3-Clause](https://github.com/arvidn/libtorrent/blob/RC_2_0/LICENSE) |
| [Jetpack Compose](https://developer.android.com/jetpack/compose), AndroidX, [Kotlin](https://kotlinlang.org) | [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| [Coil](https://github.com/coil-kt/coil) | [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| [ZXing](https://github.com/zxing/zxing) | [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| [Lucide icons](https://lucide.dev) via [composables icons-lucide](https://github.com/composablehorizons/compose-icons) | [ISC](https://lucide.dev/license) · wrapper: [MIT](https://opensource.org/license/mit) |
| [JSON-java](https://github.com/stleary/JSON-java) (`org.json`) | [Public domain](https://github.com/stleary/JSON-java/blob/master/LICENSE) |
| Google Play services Cast framework | [Android Software Development Kit License](https://developer.android.com/studio/terms) |
| [Google Sans Flex](https://github.com/googlefonts/googlesans-flex) font | [SIL Open Font License 1.1](tv/core/src/main/assets/licenses/GoogleSansFlex-OFL.txt) |

> [!IMPORTANT]
> The Jellyfin FFmpeg decoder is licensed under the GPL 3.0. The Android APKs include it, so the
> APKs as a whole are distributed under the terms of the GPL 3.0. Their complete source code is this
> repository.

## Windows (`windows/`)

| Component | License |
|---|---|
| [LibVLCSharp](https://github.com/videolan/libvlcsharp) | [LGPL 2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.en.html) |
| [VLC / libVLC](https://www.videolan.org/vlc/libvlc.html) (`VideoLAN.LibVLC.Windows`) | [LGPL 2.1 or later](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.en.html); some bundled VLC modules are [GPL 2.0 or later](https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html) |
| [MonoTorrent](https://github.com/alanmcgovern/monotorrent) | [MIT](https://github.com/alanmcgovern/monotorrent/blob/master/LICENSE.md) |
| [QRCoder](https://github.com/codebude/QRCoder) | [MIT](https://github.com/codebude/QRCoder/blob/master/LICENSE.txt) |
| [CommunityToolkit.Mvvm](https://github.com/CommunityToolkit/dotnet) | [MIT](https://github.com/CommunityToolkit/dotnet/blob/main/License.md) |
| [.NET](https://github.com/dotnet/runtime) | [MIT](https://github.com/dotnet/runtime/blob/main/LICENSE.TXT) |
| [Noto Sans](https://github.com/notofonts/latin-greek-cyrillic) font | [SIL Open Font License 1.1](windows/Watchly/Assets/Fonts/OFL.txt) |

## Online services

Titles, artwork and other information shown in the apps come from these services at run time. None
of their content is part of this repository, and it belongs to its respective owners.

| Service | Used for |
|---|---|
| [Cinemeta](https://v3-cinemeta.strem.io) (Stremio) | Film and series catalogues, details and artwork |
| [TVmaze](https://www.tvmaze.com) | Episode stills · API licensed [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) |
| [Wikipedia](https://www.wikipedia.org) | Cast photos (freely licensed images only) |
| [IntroDB](https://introdb.app) | Intro timings for Skip intro |
| [SubDL](https://subdl.com) | Subtitles, with your own key |
