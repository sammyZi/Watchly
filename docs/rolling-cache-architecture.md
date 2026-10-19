# Streaming a torrent with a bounded cache — how the apps do it

Watchly plays a video while its torrent downloads, starts from any point, seeks
anywhere, and keeps the film's footprint on disk under a cap the viewer picks.
This describes what the code does today:

- **Phone and TV** share one engine in `tv/core`: `TorrentStream.kt` (libtorrent
  through libtorrent4j) and `LocalFileServer.kt`, played by Media3 (`ExoView.kt`)
  or libVLC (`VlcView.kt`).
- **Windows** has its own engine on MonoTorrent (`windows/Watchly/Engine/`),
  covered in section 9.

The numbers below are the constants in the code; each was measured on a real
swarm, and the comments beside them say what went wrong before.

---

## 1. The shape

```
 Player (Media3, or libVLC)                        ExoView.kt / VlcView.kt
    │  HTTP range requests to 127.0.0.1:<port>/<token>
    ▼
 LocalFileServer                                   LocalFileServer.kt
    │  blocks a read until its piece is on disk; reports each reader's byte offset
    ▼
 TorrentStream — one libtorrent session            TorrentStream.kt
    │  a poll loop (every 200 ms before the first frame, 500 ms after):
    │  readers → piece zones + deadlines, watchdogs, the rolling cache
    ▼
 Piece data on disk                                <external files>/torrents/
```

The player never gets a file path. A partly downloaded file read directly hands
back zeros where pieces are missing, and the demuxer sees corruption. Over HTTP a
read for a missing piece simply waits, and every range request tells the engine
where the player is reading.

---

## 2. Starting a stream

`play(uri, fileIndex, PlayOptions)`:

1. **Metadata.** A `.torrent` seen before is read from `torrent-cache/meta/`;
   a magnet is resolved through the swarm. A torrent `inspect()` already added to
   list its files is adopted rather than re-added, so its peers carry over.
2. **Files.** Only the chosen video and its sidecar subtitles (same folder, same
   stem) are wanted; every other file and piece is `IGNORE`. Sequential mode is
   switched off — piece deadlines do the ordering, and sequential mode hands
   piece 0 to the slowest peer.
3. **Buffers, sized by bitrate** when the file's size and running time are known
   (from history, or the title's runtime):

   | | rule |
   |---|---|
   | prepare (pre-roll) | max(the Buffer setting, one piece, bitrate × 3 s up to 24 MB), at most 2 pieces |
   | readahead | bitrate × 60 s, between 128 MB and 768 MB |
   | rush window | max(32 MB, bitrate × 15 s), in pieces |

4. **Pre-roll.** Only what the first frame needs, each its own one-piece zone:
   the file's first piece (the container header) and the prepare window at the
   resume point — not at byte 0, because buffering a head the viewer skips is dead
   time. The first ~2 MB of the tail (Matroska Cues, an MP4 `moov` at the end) is
   asked for at a distant deadline.
5. **Ready.** Once the head piece and the prepare window are contiguous on disk,
   the HTTP server starts and the player gets its URL.

---

## 3. Following the player

Every HTTP connection is a **reader** with a byte offset, updated on each pass of
its copy loop. Each tick turns readers into **targets**:

- The **newest** connection leads: after a seek it is where the picture waits.
- A reader in the **tail** is the player reading the container index, not
  playback; it never leads.
- A reader that closed **lingers 10 s** — players hop between two spots in one file.
- `setPosition(ratio)` plants a **seek aim** before the player reconnects, so a
  resume an hour in does not fetch the head first. It is dropped once a new
  connection reads past the header (16 MB), or after 30 s.

### Piece priorities (`applyZone`)

| range | priority |
|---|---|
| each target … + readahead | DEFAULT |
| 32 MB behind each target (a short rewind lands on data already paid for) | LOW |
| half a zone of spare past each target (a "finished" torrent drops its seeds) | LOW |
| the tail (first 2 MB during pre-roll, all of it after) | DEFAULT |
| everything else in the file | IGNORE — or LOW in whole-file mode |

Zones are re-aimed with hysteresis (a rewind inside the band already covered is
not a new zone). If libtorrent rewrites the priorities under us — a fresh add's
file-priority update can — the next tick notices an IGNORE piece wanted again and
puts the zone back.

### Deadlines

From each target, `reach` pieces are rushed: as many as the swarm finishes in
16 s at the current rate, between 2 and the rush window. Each gets `TOP_PRIORITY`
and a deadline of `rank × 3000 + distance × 300` ms (pre-roll pieces 300 ms apart),
set once — deadlines are absolute. Deadlines no longer wanted are reset.

### Watchdogs

| problem | response |
|---|---|
| the piece the player waits on is stuck for 20 s (4 s in pre-roll, once all its blocks are out with peers) | re-arm that piece alone: `TOP_PRIORITY`, deadline 0, so its blocks go to faster peers too. Pausing the torrent instead dropped the swarm (71 peers → 18) |
| waiting on a piece for over 5 s | narrow fetching to the rushed pieces until it lands; a thin swarm spread over the readahead starves the one the player needs |
| no data at all before the first frame | re-announce every 10 s; give up after 90 s |
| a file error on the torrent | clear it and carry on |
| free space under 512 MB | one last roll (see section 4), then stop and clear |

---

## 4. The rolling cache

**Settings → Storage for one film**: 2, 4, 8 or 16 GB (4 by default), clamped to
the free space less 1.5 GB. The readahead never exceeds half the cap.

When the bytes on disk reach the cap — or free space drops under 1.5 GB — and the
viewer is not in whole-file mode, at most every 30 s:

```
1. note the earliest reader's offset (or the last one, if all are closed)
2. copy the metadata, save the peers, clear the deadlines
3. remove the torrent; wait for libtorrent to let go of it
4. delete its files
5. add it again: same metadata, saved peers, same wanted files
6. aim the new torrent at the noted offset; bump the server's epoch
```

The player's connection survives: its next read blocks until the pieces under the
playhead arrive again, and the server's epoch makes every reader reopen the file
(an open handle would keep reading the deleted inode). The cost is one rebuffer —
a pause of a few seconds — per cap's worth of film.

**Whole-file mode** (the player's "Download ahead: whole file") wants every piece
of the file at LOW outside the zones and stops the rolling, unless the disk runs
low. For a thin swarm, or to watch on to the end offline.

### Why not evict piece by piece

libtorrent has no API to forget a piece. Its picker only clears a have-bit on a
resume-data re-add, a forced recheck or a failed hash; dropping bytes in custom
storage leaves it believing it still has them, so a backward seek would read
garbage instead of downloading again. Continuous eviction would need holes punched
in the file (`fallocate(FALLOC_FL_PUNCH_HOLE)`, NDK only — Java cannot) plus a
resume-data re-add with a trimmed `have_pieces`, which libtorrent4j does not
expose. Restarting the torrent at the playhead gets the same bounded disk with
none of that, at the price of the rebuffer — the trade marked with a `ponytail:`
note in `rollCache`.

---

## 5. The HTTP server

`LocalFileServer` serves the one file being watched:

- **Loopback** `127.0.0.1` (not `::1`, which libVLC does not dial) on a random
  port. The phone also listens on the network so a Cast receiver can fetch the
  stream. Either way the path carries a random token.
- **Ranges**: `200`, `206 Partial Content` and `416`, so the player can read the
  tail for its index and seek by reopening at an offset.
- **Blocking reads**: a read for a missing piece waits in 40 ms steps. A read is
  never allowed to run past the last available byte — an unaligned read across a
  piece boundary would hand back zeros from the sparse file.
- **Superseded readers**: a connection waiting on a piece when a newer one has
  opened is going nowhere (the player seeked away) and lets go; a hung-up client
  is noticed every 500 ms.
- **`starved()`**: true while any reader is blocked on a missing piece. libVLC is
  stuck inside that read and ignores a seek until it returns, so the player
  reloads at the target instead of seeking (see section 6).

---

## 6. Seeking and resuming

- A **seek** makes the player open a new connection at the new offset; it becomes
  the newest reader and the zone moves with it. A jump over 30 s also calls
  `setPosition`, so the swarm is aimed there before the player reconnects.
- If the player is **starved** when the viewer seeks, it reopens the stream at the
  target rather than seeking, and a seek that has gone nowhere after 2.5 s is
  retried the same way.
- **Resume points** are saved as a time and as the reader's byte position, every
  5 s and on close. The byte position is what pre-roll aims at: a variable-bitrate
  file is not as far through its bytes as through its running time (a resume at
  39% of the time was at 32% of the bytes).

---

## 7. The players

- **Media3** by default, with the Jellyfin FFmpeg extension for the audio formats
  phones and TVs do not decode themselves (AC3, E-AC3, DTS). It buffers 15–30 s
  ahead. A file Media3 cannot play reports `onUnsupported`, and the screen falls
  back to libVLC at the same position.
- **libVLC** on request, or as that fallback: the widest format support and full
  ASS styling. Its network cache is 3 s, or 6 s above 4 MB/s.

---

## 8. What is on disk

| where | what | lifetime |
|---|---|---|
| `<external files>/torrents/` | piece data of the stream | wiped at launch, on stop, and by a roll |
| `files/torrent-cache/meta/` | `.torrent` files seen before | kept |
| `files/torrent-cache/peers/` | peers per torrent, so a restart finds them at once | kept |
| `files/torrent-cache/session.dat` | DHT routing table | kept |
| `files/torrent-cache/subs/` | downloaded subtitles | until their history entry goes |

Piece data lives in app-specific external storage, not the cache directory:
Android sweeps cache directories when space runs low, which would truncate the
film mid-playback.

---

## 9. Windows

The Windows app streams with MonoTorrent and libVLC (`windows/Watchly/Engine/`):

- **`StreamSession`** serves the file over a loopback HTTP server with ranges, as
  on Android; libVLC reads it as a network stream.
- **`FastStreamingRequester`** is MonoTorrent's streaming picker with deeper
  request queues: upstream keeps 2–4 blocks in flight per peer, which pulled
  ~500 KB/s from a swarm that gave qBittorrent 3.8 MB/s.
- **`WindowedRequester`** wraps it so only a window is fetched — 5 s behind the
  playhead to 5 minutes ahead, moved on the player's tick — and nothing else of
  the film. "Whole file" lifts the window; so does a download of the same torrent.
- There is no cap to roll: the stream's files are deleted when the player closes.

---

## 10. Constants

| constant | value | in |
|---|---|---|
| `PREPARE_SECONDS` / `PREPARE_MAX` | 3 s / 24 MB | `TorrentStream.kt` |
| `READAHEAD_BYTES` / `READAHEAD_SECONDS` / `READAHEAD_MAX` | 128 MB / 60 s / 768 MB | |
| `WINDOW_BYTES` / `WINDOW_SECONDS` | 32 MB / 15 s | |
| `REWIND_BYTES` | 32 MB | |
| `REACH_SECONDS` | 16 s | |
| `STUCK_PIECE_MS` / `PREROLL_STUCK_MS` / `STARVE_MS` | 20 s / 4 s / 5 s | |
| `STALL_NO_DATA_MS` / `SHAKE_INTERVAL_MS` | 90 s / 10 s | |
| `READER_LINGER_MS` / `SEEK_AIM_MS` / `REOPEN_HEAD_BYTES` | 10 s / 30 s / 16 MB | |
| `FREE_SPACE_MIN` | 512 MB (rolls under 3×) | |
| tail fetched | file size / 200, between 2 and 16 MB | `tailBytesFor` |
| `CACHE_CAPS` | 2, 4, 8, 16 GB; 4 by default | `Store.kt` |
| `AIM_SECONDS` / `SEEK_CHECK_MS` | 30 s / 2.5 s | the players' `Player.kt` |
| `WindowSeconds` / `BehindSeconds` | 5 min / 5 s | `windows/…/TorrentEngine.cs` |
