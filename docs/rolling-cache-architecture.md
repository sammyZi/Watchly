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

## 0. Which streams reach the engine

Watchly ships no sources: every stream comes from an addon the viewer added
(`Addons.kt`, the Stremio addon protocol), or from a link pasted into Search.
`Watch.kt` routes each one by what it is:

| stream | goes to |
|---|---|
| `infoHash` (a magnet), or a `.torrent` link | **TorBox** first if the viewer has a key and TorBox has it cached; otherwise this engine |
| `url` — a debrid service's link, or the viewer's own media server | straight to the player (`Watch.playLink`): no torrent, no local server, nothing on disk |
| `ytId` / `externalUrl` | handed to another app; Watchly does not play it |

A **TorBox** link plays over HTTPS from TorBox's servers. If the picture has not
moved after `TORBOX_STALL_MS` (45 s) — a video the device cannot decode leaves
VLC silent rather than failing — or TorBox drops mid-film, `leaveTorBox` hands the
same file to this engine at the same spot. TorBox numbers a pack's files its own
way, so the swarm's index is found by size and name first (`swarmIndex`).

A **direct link** may carry the headers its server wants (an addon's
`behaviorHints.proxyHeaders`); see section 7. Everything below is about the
torrent case only.

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
 Piece data on disk                                <app files>/torrents/
```

The player never gets a file path. A partly downloaded file read directly hands
back zeros where pieces are missing, and the demuxer sees corruption. Over HTTP a
read for a missing piece simply waits, and every range request tells the engine
where the player is reading.

### The session

`ensureSession` tunes libtorrent for watching rather than for finishing a download:

| setting | value | why |
|---|---|---|
| connections | 200, or 80 under 3 GB of RAM | peers cost native memory; a 2 GB TV with 200 of them and a 4K decoder gets killed |
| `peer_connect_timeout` | 3 s | a dial in flight counts against the limit, and most tracker peers are dead |
| `enable_outgoing_utp` | off | uTP backs off whenever the line is busy, and costs a phone more CPU |
| `whole_pieces_threshold` | 0 | no peer books a whole piece, so every piece is shared among those that have it |
| `request_timeout` | 4 s | an unanswered block is asked for again; at 20 s a start sat on 255 of 256 blocks |
| `strict_end_game_mode` | off | an idle peer may also ask for the last blocks another is sitting on |
| `request_queue_time` | 1 s | an urgent block joins the back of each peer's queue, so a short queue reaches it sooner |
| `max_out_request_queue` | 1500 | the default of 500 is too shallow for distant fast seeds |
| `close_redundant_connections` | off | a full read-ahead looks "finished", and libtorrent would drop its seeds |

---

## 2. Starting a stream

`play(uri, fileIndex, PlayOptions)`:

1. **Metadata.** A `.torrent` seen before is read from `torrent-cache/meta/`;
   a magnet is resolved through the swarm, with the trackers the addon listed in
   its `sources` added to it. A torrent `inspect()` already added to list its
   files is adopted rather than re-added, so its peers carry over.
2. **Which file.** In a pack, the episode asked for is picked by its name
   (`pickFile`); when the names cannot say, the addon's own `fileIdx` decides,
   and failing that the viewer is asked.
3. **Files.** Only the chosen video and its sidecar subtitles (same folder, same
   stem) are wanted; every other file and piece is `IGNORE`. Sequential mode is
   switched off — piece deadlines do the ordering, and sequential mode hands
   piece 0 to the slowest peer.
4. **Buffers, sized by bitrate** when the file's size and running time are known
   (from history, or the title's runtime):

   | | rule |
   |---|---|
   | prepare (pre-roll) | the Buffer setting, at most 2 pieces |
   | readahead | bitrate × 60 s, between 128 MB and 768 MB, and at most half the cap |
   | rush window | max(32 MB, bitrate × 15 s), in pieces |

5. **Pre-roll.** Only what the first frame needs, each its own one-piece zone, in
   the order the player will ask (`prerollTargets`): the file's first piece (the
   container header); on a resume its last piece, since a seek can't happen until
   the player has read the index at the very end (left for later, that one piece
   cost 10–12 s); then the prepare window at the resume point. The first ~2 MB of
   the tail (Matroska Cues, an MP4 `moov` at the end) is asked for at a distant
   deadline.
6. **Ready** (`readyToOpen`). The player has work of its own before the first
   picture, so it opens as soon as it can start on that. A new start waits for the
   head piece and the prepare window; a resume waits for the head piece alone, and
   a seek aim at the resume point keeps that spot coming while the player reads the
   header and index. If the player gets there first, the server blocks it.

---

## 3. Following the player

Every HTTP connection is a **reader** with a byte offset, updated on each pass of
its copy loop. Each tick turns readers into **targets**:

- A reader in the **tail** is the player reading the container index, not
  playback. While that piece is missing it comes first: the player can't seek, or
  show anything, without it. Once the piece is here it no longer counts.
- Otherwise the **newest** connection leads: after a seek it is where the picture
  waits.
- A reader that closed **lingers 10 s** — players hop between two spots in one file.
  Those spots lose their share as soon as the player has waited 2 s on its piece.
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
set once — deadlines are absolute. Tail pieces start at 10 s. Deadlines no longer
wanted are reset.

### When the player waits on a piece

A piece nearly always hangs on its last blocks, each out with one slow peer. A peer
only doubles up on a block another holds once it has nothing else to ask for, so
the responses go from gentle to blunt:

| after | condition | response |
|---|---|---|
| 1.5 s, then every 1.5 s | every block is done or out with a peer (5 s if blocks are still unasked) | **re-ask**: re-arm that piece alone with `TOP_PRIORITY` and deadline 0, so its blocks go to faster peers too |
| 1.5 s | 8 blocks or fewer to go | **focus**: that piece is the only one wanted, so idle peers double up on its blocks. 2 MB took 25 s after a seek with read-ahead on offer, and 1–2 s as the only piece wanted. The read-ahead comes back on the next tick |
| 2 s | blocks still unasked | **narrow**: fetch only the rushed pieces (at least 2) for the next 10 s, as a thin swarm spread over the read-ahead starves the one the player needs. Not while every block is out: narrowing can't speed those up, and an 86 GB remux fell from 14 MB/s to 2 MB/s each time |
| 5 s, then at most every 5 s | 8 blocks or fewer to go | **drop stalled peers** (`stalledPeers`): up to 4, slowest first, of those holding requests and sending under a quarter of the swarm's typical peer (and under 32 KB/s), never below 40 connected. Disconnecting is the only way to get a block back; an IP filter does it, for 2 minutes |

Each of these was tuned on a real swarm:

- Pausing the torrent instead of re-asking dropped the swarm (71 peers → 18).
- Dropping peers sooner than 5 s made seeks slower (91 peers → 38), and a small
  swarm is never thinned, as the holder of a rare piece may be all there is.
- "Slow" is measured against the swarm, because holding requests means a peer has
  unchoked us. With a fixed 32 KB/s, every peer of a 470 kB/s swarm counted, and
  dropping 20 of the ones sending the film left 13 peers at 155 kB/s.
- Focus stops at 8 blocks, and narrowing holds for 10 s, because a film the swarm
  could barely keep up with otherwise switched the read-ahead off and on for every
  piece.

### Other watchdogs

| problem | response |
|---|---|
| no data at all before the first frame | re-announce every 10 s; give up after 90 s |
| a file error on the torrent | clear it and carry on |
| free space under 512 MB | one last roll (see section 4), then stop and clear |

---

## 4. The rolling cache

**Settings → Storage for one film**: Auto (the default), 2, 4, 8 or 16 GB. Auto is
the free space less 1.5 GB; a fixed size is clamped to that too. The readahead
never exceeds half the cap.

When the bytes on disk reach the cap — or free space drops under 1.5 GB — and the
viewer is not in whole-file mode, `rollCache` frees what has been watched, in place,
at most every 30 s. Peers take a good 15 s to come back, so a roll for the cap waits
until half the read-ahead is on disk to play from; a roll for free space goes
regardless.

```
1. note the earliest reader's offset (or the last one, if all are closed)
2. copy the metadata, save the peers, clear the deadlines, note the pieces held
3. remove the torrent; wait for libtorrent to let go of it
4. punch out everything but what is kept (rollCuts)
5. add it again: same metadata, saved peers, the pieces still held (addKeeping)
6. reopen the server's readers
```

`rollCuts` keeps four stretches and frees the rest: the first 8 MB (a player that
reopens reads the header there), 32 MB behind the earliest reader, the read-ahead
past the furthest one, and the tail. What lies ahead goes too: after a seek back,
a stretch left behind further on would keep the cap full and the roll coming round.

Holes are punched by `libholes` (`tv/core/src/main/cpp/holes.c`), which leaves the
file's size unchanged. The torrent comes back owning the kept pieces through
`have_pieces`, without hashing the file again, which on a 50 GB film would take
minutes. While the torrent is away, `rollHave` answers the server's "is this piece
here?", and pieces leave it before their holes appear, so a reader never gets
zeros. Playback carries on from the read-ahead while the peers reconnect.

Where a hole can't be punched, the roll falls back to deleting the file and starting
over at the playhead: one rebuffer per cap's worth of film.

**Whole-file mode** (the player's "Download ahead: whole file") wants every piece
of the file at LOW outside the zones and stops the rolling, unless the disk runs
low. Not during pre-roll, nor while the player waits on a piece: with LOW pieces
queued no peer was idle to take over a slow peer's last blocks.

### Why remove and re-add

libtorrent has no API to forget a piece. Its picker only clears a have-bit on a
re-add, a forced recheck or a failed hash. Left alone, the torrent would believe it
still had the punched pieces, serve zeros to peers, and hand zeros to a backward
seek. Re-adding with a trimmed `have_pieces` is also what tells peers those pieces
are gone.

The piece data lives in app-internal storage for this reason: external storage on a
phone is FUSE, which refuses to punch holes (`EOPNOTSUPP`).

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
  39% of the time was at 32% of the bytes). It is saved less what the player has
  buffered and a keyframe's worth (`playheadRatio`), since the player reads well
  ahead of the picture.
- With no byte position, as for a spot carried over from Stremio, `resumeRatio`
  works it out from the time and the running time, falling back on the title's own
  runtime: at 0 an 86 GB resume aimed at the start for its first 35 s.

---

## 7. The players

- **Media3** by default, with the Jellyfin FFmpeg extension for the audio formats
  phones and TVs do not decode themselves (AC3, E-AC3, DTS). It buffers 15–30 s
  ahead. A file Media3 cannot play reports `onUnsupported`, and the screen falls
  back to libVLC at the same position.
- **libVLC** on request, or as that fallback: the widest format support and full
  ASS styling. Its network cache is 3 s, or 6 s above 4 MB/s.

The same two players take the other streams of section 0 unchanged: a TorBox,
debrid or own-server link is opened as a URL rather than through
`LocalFileServer`. A link's headers (`Ready.headers`, set before `open`) go with
every request Media3 makes; libVLC can send only the `User-Agent` and `Referer`
of them. Headers are not kept in History, so a link that needs them may not
resume from Continue Watching, and it is not offered to a Cast receiver, which
would not send them.

---

## 8. What is on disk

| where | what | lifetime |
|---|---|---|
| `files/torrents/` | piece data of the stream | wiped at launch and on stop; a roll frees what was watched |
| `files/torrent-cache/meta/` | `.torrent` files seen before | kept |
| `files/torrent-cache/peers/` | peers per torrent, so a restart finds them at once | kept |
| `files/torrent-cache/session.dat` | DHT routing table | kept |
| `files/torrent-cache/subs/` | downloaded subtitles | until their history entry goes |

Piece data is not in the cache directory: Android sweeps those when space runs low,
which would truncate the film mid-playback. Nor in external storage, where holes
can't be punched (section 4). It is the same partition, so the same free space.

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
  the film. On a resume the file's last piece comes in alongside, as on Android.
  "Whole file" lifts the window, 64 pieces at a time; so does a download of the
  same torrent.
- There is no cap to roll: the stream's files are deleted when the player closes.
- Streams come from the viewer's addons here too (`Core/Addons.cs`). A `url`
  stream plays straight in libVLC (`PlayLink` in `MainWindow.xaml.cs`), never
  through the engine; streams that need `proxyHeaders`, and `ytId` /
  `externalUrl` ones, are skipped on Windows.

---

## 10. Constants

| constant | value | in |
|---|---|---|
| `READAHEAD_BYTES` / `READAHEAD_SECONDS` / `READAHEAD_MAX` | 128 MB / 60 s / 768 MB | `TorrentStream.kt` |
| `WINDOW_BYTES` / `WINDOW_SECONDS` | 32 MB / 15 s | |
| `REWIND_BYTES` | 32 MB | |
| `REACH_SECONDS` | 16 s | |
| `LAST_BLOCKS` / `LAST_BLOCKS_MS` | 8 blocks / 1.5 s | |
| `STUCK_PIECE_MS` / `STARVE_MS` / `NARROW_HOLD_MS` | 5 s / 2 s / 10 s | |
| `STALLED_AFTER_MS` / `DROP_MS` | 5 s / 2 min | |
| `STALL_NO_DATA_MS` / `SHAKE_INTERVAL_MS` | 90 s / 10 s | |
| `STALLED_BYTES_PER_SECOND` / `STALLED_SHARE` | 32 KB/s / a quarter of the typical peer | `Store.kt` |
| `MAX_DROPPED_AT_ONCE` / `MIN_PEERS_KEPT` | 4 / 40 | |
| `ROLL_HEAD_BYTES` | 8 MB | `Holes.kt` |
| `READER_LINGER_MS` / `SEEK_AIM_MS` / `REOPEN_HEAD_BYTES` | 10 s / 30 s / 16 MB | |
| `FREE_SPACE_MIN` | 512 MB (rolls under 3×) | |
| tail fetched | file size / 200, between 2 and 16 MB | `tailBytesFor` |
| `CACHE_CAPS` | Auto (free space less 1.5 GB), 2, 4, 8, 16 GB; Auto by default | `Store.kt` |
| `TORBOX_STALL_MS` | 45 s without the picture moving: TorBox gives way to the swarm | `Watch.kt` |
| `AIM_SECONDS` / `SEEK_CHECK_MS` | 30 s / 2.5 s | the players' `Player.kt` |
| `WindowSeconds` / `BehindSeconds` | 5 min / 5 s | `windows/…/TorrentEngine.cs` |
