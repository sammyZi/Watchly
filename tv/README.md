# Watchly TV

The television build. A separate Expo project from the phone app in `../` —
its own `android/`, its own copy of the native modules, its own `package.json`.
Nothing here is imported by the phone app and nothing in the phone app is
imported by this.

```bash
cd tv && npm install
```

```bash
cd tv && npx expo run:android
```

Different `applicationId` (`com.torrentviewer.tv`), so it installs alongside
the phone build on the same device.

## What is different from the phone app

**No downloads.** A television has no file manager to browse them with and no
storage to hold them. `useDownloads`, `downloadJob`, the Downloads tab and the
download notification are all gone from the JS; `removeFilesAfterStop` is
hard-wired on.

**No Google Cast.** The television *is* the receiver. The dependency, its
Play Services framework, the manifest metadata and the discovery callbacks are
all removed — several TV boxes ship without Play Services at all and the old
`onCreate` path would have thrown on them.

**Leanback.** `android.software.leanback` is required and touchscreen is not,
which is what makes the store offer this APK to a television and not to a
phone. The launcher entry carries `LEANBACK_LAUNCHER` and a generated
`@drawable/tv_banner` — without a banner the app is invisible on the TV home
screen. Locked to landscape.

**D-pad everything.** `components/Focusable.tsx` is the one focus primitive;
every pressable thing on every screen goes through it. It draws a transparent
border when unfocused so that focus does not resize the view and reflow the
rail around it. Exactly one element per screen sets `first`, which is where
focus lands when the screen appears.

**Ten-foot scale.** `theme.ts` keeps the phone's palette and roughly doubles
its type and spacing, and adds `SAFE` — televisions crop the edges of the
picture by a few percent and every set does it differently.

## The rolling cache

The reason this exists: a 4K film is tens of gigabytes and a TV box has single
digits free, so a stream cannot simply accumulate the way it does on a phone.

libtorrent has no way to forget a piece — priorities decide what to *fetch*,
never what to drop — so `rollCache()` in `TorrentStreamModule.kt` removes the
torrent, deletes its data, and adds it again. have-state resets, the sparse
file is recreated empty, and the readahead zone re-aims at wherever the viewer
is on the next tick.

Playback survives it. The player's HTTP connection stays open because
`LocalFileServer` blocks on `available` — false for every piece until the ones
under the playhead arrive again — then reopens the file (see `epoch`) and
carries on. Seeking backwards still works: it moves a reader, the zone follows,
and those pieces are fetched again rather than read as holes.

The cost is one rebuffer per roll, so the cap wants to be as large as the disk
allows. It is **Settings → Storage for one film**, default 4 GB, passed to
native as `cacheCapMb`. Native also rolls early if free space is genuinely
about to run out, so a cap set too high degrades into more rebuffers rather
than the hard out-of-space stop that kills the stream.

Evicting continuously instead of rolling would need `FALLOC_FL_PUNCH_HOLE`
through JNI, and libtorrent would then seed the zeros it still believes are
pieces — worth doing only if the stall becomes the thing people notice.

## Incoming connections

The engine listens on a fixed port, **6881**, on IPv4 and IPv6, and asks the
router to forward it with UPnP and NAT-PMP — with UPnP on in the router, peers
can connect in with nothing set up. It is not the phone's 6882 or the Windows
app's 51413 because a router forwards a port to one device only, and all three
can be on one network at once.

## Installing a release on a TV

Most Android TVs are 32-bit ARM (`getprop ro.product.cpu.abilist` says), so
build only that and install over network debugging:

```bash
cd tv/android && ./gradlew assembleRelease -PreactNativeArchitectures=armeabi-v7a
```

```bash
adb connect <tv-ip>:5555
```

```bash
adb install -r tv/android/app/build/outputs/apk/release/app-armeabi-v7a-release.apk
```

Release builds are signed with the debug keystore, so this updates in place and
keeps the app's data.

## Checks

```bash
cd tv && npm run tsc && npm test
```
