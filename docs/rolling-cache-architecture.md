# Bounded rolling cache for torrent streaming — architecture

Target: stream a video from a torrent with disk use capped at a configurable
budget (300 MB default), evicting consumed pieces and re-downloading them if the
viewer seeks back into an evicted region.

Everything below is written against **libtorrent 2.0.x**, because that is what
this repo already ships (`org.libtorrent4j:libtorrent4j:2.1.0-38`, which tracks
`RC_2_0`). Where 1.2.x differs materially it is called out, because the storage
API is the one place the two versions are not interchangeable.

---

## 0. Feasibility first: can libtorrent evict a piece?

**No — not per piece, not through any public API, in either 1.2 or 2.0.** This
is the constraint the whole design has to bend around, so it comes first.

### What libtorrent actually exposes

Piece availability lives in the `piece_picker`, in memory. `torrent_handle::have_piece(piece_index_t)`
reads it. Only three things ever clear a have-bit:

1. adding a torrent with resume data whose `have_pieces` bitfield lacks it,
2. `torrent_handle::force_recheck()` — whole torrent, stops and rehashes,
3. a failed hash check on a piece being written.

There is no `unhave_piece`, no `discard_piece`, no storage callback that reports
"this piece went away". Storage is a sink, not a source of truth: libtorrent
never asks it what exists during normal operation.

### Why custom storage does not solve it

- **libtorrent 1.2**: `storage_interface` + `add_torrent_params::storage`
  (a `storage_constructor_type`) let you own `readv`/`writev`.
- **libtorrent 2.0**: `storage_interface` was **removed**. It is replaced by
  `disk_interface` — you implement the whole async disk subsystem and install it
  with `session_params::disk_io_constructor` (the built-ins are
  `mmap_disk_io_constructor`, `posix_disk_io_constructor`, `default_disk_io_constructor`).
  Bigger surface, job-queue semantics, and it is per-session, not per-torrent.

Either way, dropping a piece's bytes inside your storage does **not** tell the
picker. On a backward seek libtorrent still believes it has the data, so it never
schedules a re-download; your read returns an error, which surfaces as
`file_error_alert` and pauses the torrent. Worse than useless.

There is one saving grace: libtorrent only *reads* piece data to (a) serve
peers, (b) satisfy an explicit `read_piece()`, (c) recheck. A leeching,
non-seeding torrent that never calls `read_piece` on an evicted index will never
notice the hole. So the bytes can go; only the bitfield has to be corrected.

### The supported way to make libtorrent forget

`add_torrent_params::have_pieces` is a documented, honoured field. So:

```
save_resume_data(save_info_dict)
  → save_resume_data_alert          // gives you a populated add_torrent_params
  → edit atp.have_pieces            // clear the bits you are evicting
  → remove_torrent(h)               // NO delete_files flag
  → free the bytes on disk
  → add_torrent(atp)                // picker now agrees with the disk
```

This is a **batched** operation, not per piece. Do it when the budget is
exceeded, not every time a piece scrolls out of the window — a re-add costs a
peer reconnect and a few hundred ms.

> **Verify on your build**: whether the re-add triggers a full recheck depends on
> how 2.0 validates resume data against file sizes/mtimes. Because you are
> re-adding params libtorrent itself just produced, it should accept them — but
> punching holes changes mtime, so stat the file *after* freeing bytes and make
> sure the params reflect the current state. Test with a 3 GB file and watch for
> `torrent_checked_alert`; if you see a full recheck, that is the bug to fix
> before anything else.

### Freeing the bytes

Two options, both real:

| | mechanism | frees disk | needs |
|---|---|---|---|
| **A. Hole punching** | `fallocate(fd, FALLOC_FL_PUNCH_HOLE \| FALLOC_FL_KEEP_SIZE, off, len)` | yes, on ext4/f2fs | a few lines of C++/NDK; **not** on FAT/exFAT SD cards |
| **B. Piece-per-file store** | custom `disk_interface`, one file per piece, `unlink()` to evict | yes, any filesystem | a full `disk_interface` implementation |

Android's Java layer cannot punch holes — `android.system.Os` exposes
`posix_fallocate` but not `fallocate` with `PUNCH_HOLE`. From C++/NDK it is a
direct syscall, which is why option A is nearly free *once you are in the NDK
anyway*.

### Verdict

**Recommended: A + batched re-add.** Keep libtorrent's default disk I/O
(`mmap_disk_io` on 2.0), punch holes yourself, correct the picker with a
remove/re-add carrying a trimmed `have_pieces`. No custom `disk_interface`, no
invented APIs, bounded disk, redownload-on-demand works because the picker
genuinely believes the pieces are gone.

Fall back to B only if you must support exFAT SD cards, where hole punching is
unavailable.

---

## 1. Architecture

```
┌───────────────────────────────────────────────┐
│ React Native (TS)                             │
│   TorrentPlayer TurboModule + <TorrentVideo/> │
└───────────────┬───────────────────────────────┘
                │ JSI / TurboModule
┌───────────────▼───────────────────────────────┐
│ Kotlin                                        │
│   TorrentPlayerModule   — API surface, events │
│   CacheManager          — LRU, readers, budget│
│   TorrentDataSource     — Media3 DataSource   │
│   ExoPlayer (Media3)                          │
└───────────────┬───────────────────────────────┘
                │ JNI
┌───────────────▼───────────────────────────────┐
│ C++                                           │
│   TorrentEngine  — session, alerts, scheduler │
│   PieceStore     — hole punching, byte reads  │
│   libtorrent 2.0 (mmap_disk_io)               │
└───────────────────────────────────────────────┘
```

Note the DataSource sits *above* JNI and reads through the C++ layer; ExoPlayer
never sees a file path.

---

## 2. Module layout

```
android/
  torrentplayer/
    src/main/java/.../
      TorrentPlayerModule.kt      RN surface, event emitter
      TorrentEngine.kt            JNI facade, one instance per session
      CacheManager.kt             piece table, LRU, reader refcounts
      TorrentDataSource.kt        Media3 DataSource
      PlaybackController.kt       maps playback position → piece window
    src/main/cpp/
      CMakeLists.txt
      engine.cpp                  session, add/remove, alert loop
      scheduler.cpp               priorities + deadlines
      piece_store.cpp             read bytes, punch holes
      jni_bridge.cpp              JNIEnv marshalling
    src/main/cpp/libtorrent/      submodule, built with the NDK
```

---

## 3. Piece ↔ byte mapping

Given the chosen file index `f` inside the torrent:

```
file_offset  = file_storage::file_offset(f)      // torrent-global byte offset
piece_length = torrent_info::piece_length()

// media byte b (0-based within the file):
torrent_offset = file_offset + b
piece_index    = torrent_offset / piece_length
offset_in_piece= torrent_offset % piece_length
```

Do not hand-roll it — `torrent_info::map_file(file_index_t, std::int64_t offset, int size)`
returns a `peer_request{piece, start, length}` and handles the edge cases
(last piece short, file spanning a piece boundary). The inverse,
`map_block(piece, offset, size)`, gives you back the file ranges a piece covers,
which is what you need when a piece completes and you want to know which
outstanding reads it unblocks.

**Piece boundaries do not align to file boundaries.** The first and last piece of
a file usually contain bytes of the neighbouring files. Consequence: those two
pieces must never be evicted by "distance behind playback" logic alone — evicting
the first piece of the file destroys the container header for everyone.

**Choosing the video file**: largest file whose extension is in a video set, with
`sample`/`trailer` path components excluded. Everything else gets
`file_priority(f, dont_download)`.

---

## 4. Cache manager

One entry per piece of the selected file:

```kotlin
data class PieceState(
  val index: Int,
  val size: Int,             // last piece is short
  val fileOffset: Long,      // byte offset within the media file
  var cached: Boolean,
  var downloading: Boolean,
  var lastAccessMs: Long,
  var readers: Int,          // > 0 pins the piece
)
```

`readers` is the pin count, incremented by the DataSource around every read and
decremented in a `finally`. A piece with `readers > 0` is never a candidate,
full stop — that is requirement 5, and it is enforced in one place.

Eviction candidacy, all of which must hold:

```
readers == 0
&& cached
&& index !in protectedRange        // header piece, tail pieces, current window
&& piecePosMs < playbackMs - KEEP_BEHIND
&& index != firstPieceOfFile && index != lastPieceOfFile
```

Ordering among candidates: furthest behind playback first, LRU as the tiebreak.
Pure LRU is wrong here — a piece touched once during a seek-preview is more
likely to be wanted again than one the viewer scrolled past an hour ago.

```
CACHE_LIMIT  = 300 MB      // configurable via setCacheLimit()
KEEP_BEHIND  = 60 s        // never evict inside this
KEEP_AHEAD   = 120 s       // never evict, obviously; also the prefetch target
EVICT_BATCH  = 64 MB       // evict down to LIMIT - BATCH, so re-adds are rare
```

Evicting to `LIMIT - BATCH` rather than to `LIMIT` is what keeps the expensive
part (the torrent re-add) down to roughly one event per `BATCH` of playback.

---

## 5. Piece scheduling

Run on every playback-position update (≈1 Hz) and on every seek.

```
p        = piece index at current playback position
ahead(t) = pieces covering t seconds of media at the file's average bitrate
           bitrate = fileSize / durationMs   (duration from ExoPlayer)

CRITICAL  p .. p+ahead(5s)      priority 7, set_piece_deadline(i, (i-p)*200ms)
HIGH      .. p+ahead(45s)       priority 6, deadline (i-p)*400ms
MEDIUM    .. p+ahead(KEEP_AHEAD) priority 4, no deadline
LOW       p-ahead(KEEP_BEHIND) .. p   priority 1   (re-fetch after a small rewind)
IGNORE    everything else            priority 0
```

Bitrate-derived windows rather than fixed piece counts: piece size varies by two
orders of magnitude between torrents, and "60 seconds" is the unit the cache
budget is actually expressed in.

APIs: `torrent_handle::prioritize_pieces(std::vector<download_priority_t>)` for
the bulk pass (one call, not N), `set_piece_deadline(piece_index_t, int, deadline_flags_t)`
for the critical window, `clear_piece_deadlines()` before re-aiming after a seek.
Deadlines are absolute once set, so set them once per window rather than
refreshing every tick.

Keep the wanted set generously sized (a few hundred pieces at LOW/MEDIUM). Too
small and every peer holds the same few blocks, which puts libtorrent in
permanent end-game: a swarm full of seeds delivering duplicates and almost no
unique bytes.

---

## 6. Eviction algorithm

```
onTick():
  if cachedBytes <= CACHE_LIMIT: return
  target = CACHE_LIMIT - EVICT_BATCH
  victims = candidates()                    // see §4
      .sortedBy { playbackDistance(it) desc, lastAccess asc }
      .takeWhile { cachedBytes - freed > target }
  if victims.isEmpty(): return              // everything is pinned; try next tick

  engine.evict(victims)                     // JNI, one call
```

C++ side, in order — the order is the safety property:

```cpp
1. h.save_resume_data(torrent_handle::save_info_dict);
2. wait for save_resume_data_alert           // gives you add_torrent_params
3. atp = alert->params;
   for (auto i : victims) atp.have_pieces[i] = false;
4. ses.remove_torrent(h);                    // no delete_files
5. for each victim: punch_hole(fd, off, len) // now nothing can be reading
6. fstat each file, reconcile atp file metadata
7. h = ses.add_torrent(atp);
8. re-apply the priority/deadline window     // add_torrent resets it
```

Step 4 before step 5 is the whole point: with the torrent removed, neither
libtorrent's disk threads nor a peer request can touch the range being freed.
The DataSource is blocked separately (see §8).

---

## 7. Media3 DataSource

```kotlin
class TorrentDataSource(
  private val engine: TorrentEngine,
  private val cache: CacheManager,
) : BaseDataSource(true) {

  private var position = 0L
  private var remaining = 0L

  override fun open(spec: DataSpec): Long {
    position  = spec.position
    remaining = if (spec.length == C.LENGTH_UNSET.toLong())
                  fileSize - spec.position else spec.length
    engine.setReadPosition(readerId, position)   // steers the scheduler
    return remaining
  }

  override fun read(buf: ByteArray, off: Int, len: Int): Int {
    if (remaining == 0L) return C.RESULT_END_OF_INPUT
    val want  = minOf(len.toLong(), remaining).toInt()
    val piece = cache.pieceAt(position)

    cache.pin(piece)                             // eviction can no longer take it
    try {
      if (!cache.isCached(piece)) {
        engine.prioritizeNow(piece)              // deadline 0, TOP priority
        if (!cache.awaitPiece(piece, TIMEOUT_MS)) throw HttpDataSourceException(...)
      }
      val n = engine.readBytes(position, buf, off, want)   // JNI, into the array
      position  += n
      remaining -= n
      bytesTransferred(n)
      return n
    } finally {
      cache.unpin(piece)
    }
  }

  override fun close() { engine.releaseReader(readerId) }
}
```

Points that matter:

- **`open()` is the seek signal.** ExoPlayer seeks by closing and re-opening the
  source at a new position; there is no `seek()` on `DataSource`.
- **Pin around the read**, release in `finally`, including on cancellation.
- **`awaitPiece` must be interruptible** — ExoPlayer cancels loads on seek, and a
  read blocked forever on a piece nobody is fetching any more is a deadlock.
  Wait on a condition with a timeout, and have the cancel path signal it.
- Register it with a `DataSource.Factory` on a `ProgressiveMediaSource`. Set the
  content length from `open()` so ExoPlayer can build a seek map; without it,
  seeking degrades to unseekable.

---

## 8. Thread safety

Four thread groups touch this state:

| thread | touches |
|---|---|
| libtorrent alert thread | piece completion, stats |
| ExoPlayer loader thread | DataSource `read`/`open`/`close` |
| Kotlin scheduler tick | priorities, eviction decisions |
| RN JS bridge thread | API calls |

Rules:

1. **One lock over the piece table**, held only for field updates — never across
   a JNI call or a blocking wait.
2. **The eviction path takes the table lock, snapshots victims, releases**, then
   does the slow work. Between snapshot and execution, re-check pin counts;
   anything newly pinned drops out of the batch.
3. **libtorrent calls go through a single-threaded executor.** libtorrent's own
   API is thread-safe, but the save/remove/punch/add sequence must be atomic with
   respect to other engine calls, so serialise them.
4. **Piece-available signalling** is a `ConditionVariable` per waiting reader, not
   a global notify-all — a 30-reader notify storm on every piece is measurable.
5. **JNI**: attach the alert thread once with `AttachCurrentThread`, cache
   `jmethodID`s at load time, never hold a `JNIEnv*` across threads. Copy piece
   bytes into the caller's array with `SetByteArrayRegion` rather than pinning
   with `GetByteArrayElements`.

---

## 9. Data flows

**Start** — resolve metadata (`add_torrent` with `flag_upload_mode` off) → pick
the video file → `file_priority(others, dont_download)` → schedule the head
(container header) and the tail (Matroska Cues / non-faststart MP4 `moov` live at
the end; without them ExoPlayer cannot build a seek map) → wait for the prepare
buffer → hand ExoPlayer a `MediaItem` backed by the DataSource factory.

**Buffering** — ExoPlayer reads ahead as far as its `LoadControl` allows; the
DataSource blocks on missing pieces and the scheduler pulls the window forward as
`position` advances. Emit `onBufferUpdate` from the cache's contiguous-ahead
count, not from libtorrent's overall progress, which is meaningless here.

**Normal playback** — scheduler tick re-aims the window ≈1 Hz; cache grows;
nothing is evicted until the budget is crossed.

**Eviction** — §6. Playback is unaffected: everything the player is reading is
pinned or inside `KEEP_BEHIND`, and the re-add takes a few hundred ms during
which no read can arrive at libtorrent (the DataSource is serving from pieces
already cached, which is exactly why `EVICT_BATCH` and `KEEP_AHEAD` exist).

**Seek forward within cache** — ExoPlayer closes and re-opens at the new offset;
pieces are present; the scheduler re-aims. No network work.

**Seek forward beyond cache** — re-open blocks on a missing piece;
`clear_piece_deadlines()` then a fresh window around the target; the old window
drops to IGNORE so the swarm stops fetching what nobody will watch; playback
resumes when the critical window is contiguous.

**Seek backward into evicted region** — the picker no longer has those bits
(that is what the re-add bought), so it schedules them like any missing piece.
Same path as "seek forward beyond cache". This is the case that does not work
without the re-add, and is the reason the whole design is shaped this way.

**Stop** — `close()` the DataSource, release readers, `remove_torrent` with
`delete_files`, drop the cache directory.

---

## 10. Errors and recovery

| failure | handling |
|---|---|
| piece wait timeout | surface `onTorrentError`, keep the torrent alive, let the player retry the read — swarms recover |
| `file_error_alert` | disk full or a hole punched under a live read; stop, wipe, report |
| no peers / stalled | report peer count and rate; do not tear down, DHT can take minutes |
| hash failure | libtorrent re-requests automatically; count it, warn above a threshold |
| re-add rechecks | log `torrent_checked_alert` timing; if it happens, the resume params are wrong (§0) |
| out of disk mid-play | check `usableSpace` on the tick, stop cleanly with a message |
| process death | resume data is stale by design; wipe the cache on next launch |

---

## 11. React Native API

```ts
type TorrentPlayer = {
  startTorrent(uri: string, opts?: { cacheLimitBytes?: number }): Promise<Session>;
  pauseTorrent(): Promise<void>;
  resumeTorrent(): Promise<void>;
  stopTorrent(): Promise<void>;
  seek(positionMs: number): Promise<void>;
  getPlaybackState(): Promise<PlaybackState>;
  getBufferInfo(): Promise<{ aheadMs: number; behindMs: number; cachedBytes: number }>;
  getTorrentStats(): Promise<{ peers: number; seeds: number; downloadRate: number }>;
  setCacheLimit(bytes: number): Promise<void>;
};

// events: onTorrentProgress, onBufferUpdate, onPlaybackReady, onDownloadSpeed,
//         onPeerCountChanged, onCacheUpdated, onTorrentError
```

Emit at most 1 Hz and coalesce — an event per piece completion at 3 MB/s is ~1
bridge crossing per 10 ms and will dominate your frame budget.

---

## 12. Prototype order

Build in this sequence; each step is independently verifiable:

1. NDK builds libtorrent; a session downloads a torrent to a file. No player.
2. `map_file` + file selection + priorities. Log the piece window; confirm it
   tracks a fake advancing "playback position".
3. `TorrentDataSource` over the *complete* file, no eviction. ExoPlayer plays and
   seeks. This proves the DataSource independently of the cache.
4. `CacheManager` with pins and accounting, still no eviction. Confirm the byte
   totals and that pins are balanced under seeking.
5. Hole punching alone: verify `du` drops while `ls -l` stays the same, and that
   playback is unaffected for pieces the picker still claims.
6. Add the save/remove/punch/add cycle. **Test the backward seek into an evicted
   region first** — it is the only test that proves the design.
7. Only then: LRU tuning, batch sizing, adaptive windows.

Step 6 is where this either works or doesn't, so get to it before optimising
anything.

---

## 13. Two notes specific to this repo

**ExoPlayer vs libVLC.** This project moved off Media3 deliberately (`0578d27`):
Media3 defers AC3/E-AC3/DTS to the platform MediaCodec, which most Android
devices do not ship, so those releases play as picture with silence — and it
cannot render PGS/VOBSUB bitmap subtitles. libVLC carries its own decoders. If
you adopt Media3 for the DataSource integration you get that regression back.
The alternative is a libVLC `IMedia` callback source, which is the same idea with
a different consumer interface; the cache and engine design below is unchanged.

**libtorrent4j cannot do this.** It exposes no `disk_interface`, no `fallocate`,
and its `add_torrent_params` bindings would need `have_pieces` to be writable.
The rolling cache requires dropping to C++/NDK, which is the single largest cost
in this plan — the current Kotlin-over-libtorrent4j stack cannot be extended into
it incrementally.
