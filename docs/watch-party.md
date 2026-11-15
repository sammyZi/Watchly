# Watch party

Watch together on phones, TVs and Windows PCs anywhere: the same film, in step, with chat and
reactions. There are no accounts and no Watchly server holding anything. Each device streams the
film itself; only small, encrypted messages go between them.

## Using it

1. Start a film and press the player's **party** button. It opens a room and the invite link goes
   to any chat app; the party panel also shows it as a QR code. Only a torrent can be watched
   together, not a local file or a plain link.
2. Whoever opens the link sees the film's page with **Join the party** where Play would be. Nothing
   plays until they choose.
   - **Join the party** streams the leader's own torrent.
   - **Pick my own source** opens the film's page so the guest can choose any release, for example
     a smaller one or one that's faster where they are. That copy stays in step too.
3. The leader's play, pause, jumps and speed reach everyone. A guest who pauses or jumps chooses
   **Just me** (they drift off, and **Catch up** brings them back) or **Everyone** (asked of the
   leader, or done at once if the leader allows it). The party panel has the chat, reactions and
   who's there, with each person's status.
4. Back out of the film, or **Leave**, and a **Rejoin** bar brings you back. A leader's **Reopen**
   reopens the same room, so everyone's link still works.

On the TV, **Settings → Watch party** also joins by scanning a QR code with the phone.

**Settings → Watch party**: on or off, the name the others see (the profile's name by default,
numbered 1, 2… if two people share one), what to do with guests' requests (ask, allow, or off),
how long to wait for someone still loading (default: don't wait), whether chat and reactions
show on the picture, and smooth catching up.

## How it works

```
 phone ──┐                         ┌── TV
         ├── wss://…/room/<id> ────┤
 PC ─────┘   (Cloudflare Worker,   └── phone
              one Durable Object
              per room)
```

- **Relay** (`party/worker.js`). One Durable Object per room passes each message to everyone else
  in it and answers clock pings. It never reads the messages, stores nothing, and holds at most 20
  people and 16 KB per message. It also serves `/j/<id>`, the invite page that hands the link to
  the app (`watchly://party/<id>`).
- **Encryption.** Every message is sealed with AES-GCM using a 16-byte key that exists only in the
  invite link's `#fragment`, which browsers never send to a server. Wire format: hex of
  `iv (12) + ciphertext + tag (16)`, the same on Kotlin and .NET.
- **One clock.** Each device pings the relay for its time and keeps the reply with the shortest
  round trip of the last eight (NTP-style, on a monotonic clock). That way a guest knows how old
  the leader's position is, however slow its own connection.
- **The leader** says where it is on every play, pause, jump and speed change, every 10 seconds,
  and whenever someone says hello. The message includes what is playing: the magnet (with its own
  trackers, cut to fit 4,000 characters), the file name, the title and the IMDb id.
- **A guest follows** (as Jellyfin's SyncPlay and Syncplay do it):
  - same play/pause;
  - within 0.15 s counts as together;
  - a little off, it plays up to 8% faster or slower until it's back (you don't hear it);
  - more than 2 s off, it jumps, then waits 10 s for the jump to load before trying another;
  - never jumps while it is still loading.
- **Different speeds of connection.** A device loading for 2 s says so. The leader can hold the film
  for anyone stuck (Settings: don't wait, 30 s, 1 min or 2 min), and **Go on** stops waiting.
- **People.** Each device says it's here with its status (opening, watching, paused, loading,
  behind) every 30 s, and is dropped after 75 s of silence.
- **Own source.** A guest on a release of their own is followed by the film's IMDb id instead of
  the file name, and the leader's torrent never opens for them. For a series, the id is the show's,
  so the guest picks the same episode themselves.

## Code

| Where | What |
|---|---|
| `party/` | The relay: `worker.js`, `wrangler.toml` |
| `tv/core/…/Party.kt` | Rooms, invites, encryption, the clock, members, chat, Rejoin (phone and TV) |
| `tv/core/…/PartyDriver.kt` | The sync rules above, behind a small `PartyPlayer` interface each player implements |
| `mobile/…/PartyJoin.kt`, `mobile/…/Player.kt` | The phone's join screen, Rejoin bar and party panel |
| `tv/app/…/Player.kt`, `tv/app/…/Pages.kt` | The TV's party panel and Settings page |
| `windows/Watchly/Core/Party.cs`, `PartyDriver.cs` | The same, in C# |
| `windows/Watchly/Views/JoinPartyView.cs`, `PlayerView.Party.cs` | The Windows join screen and party panel |

## Running your own relay

The Workers free plan is enough.

```bash
cd party && npx wrangler deploy
```

Put the URL it prints in `mobile/.env` as `PARTY_URL=https://…workers.dev`. The phone, TV and
Windows builds all read it at build time. Without it, the apps say they were built without the
watch party.
