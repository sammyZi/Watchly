# Watchly

Movies and series streamed from torrents while they download. Three apps, each
with its own README:

| App | Folder | Stack |
|---|---|---|
| Phone (Android) | [`mobile/`](mobile/README.md) | Expo / React Native; libtorrent4j + libVLC |
| Television (Android TV) | [`tv/`](tv/README.md) | native Kotlin + Compose; libtorrent4j + Media3 / libVLC |
| Windows desktop | [`windows/`](windows/README.md) | MonoTorrent + LibVLCSharp (.NET 8, WPF) |

[`docs/`](docs/rolling-cache-architecture.md) has the rolling-cache design the
apps share.
