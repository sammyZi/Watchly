# Watchly

Movies and series streamed from torrents while they download. Three apps, each
with its own README:

| App | Folder | Stack |
|---|---|---|
| Phone (Android) | [`mobile/`](mobile/README.md) | Kotlin + Compose on `tv/core`; libtorrent4j + Media3 / libVLC |
| Television (Android TV) | [`tv/`](tv/README.md) | Kotlin + Compose; libtorrent4j + Media3 / libVLC |
| Windows desktop | [`windows/`](windows/README.md) | MonoTorrent + LibVLCSharp (.NET 8, WPF) |

The phone and the TV share one Gradle module, `tv/core`: the torrent engine,
the players, indexers, metadata, the store and the look. The phone's old
React Native / Expo build is still in `mobile/` while it is retired.

```bash
cd mobile && ./gradlew compileDebugKotlin
```

```bash
cd tv && ./gradlew compileDebugKotlin testDebugUnitTest
```

[`docs/`](docs/rolling-cache-architecture.md) has the rolling-cache design the
apps share.
