using System.Diagnostics;
using System.Net.WebSockets;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Watchly.Core;

/// <summary>Enough for a guest to open the same file: a magnet, never a debrid link (it holds the host's key).</summary>
public sealed record PartyMedia(string Uri, string File, string Title, string? Imdb, string? Kind, string? Poster);

/// <summary>
/// The leader's player: <see cref="Pos"/> seconds at <see cref="Stamp"/> (the relay's clock, ms), going at
/// <see cref="Rate"/> or not, who it is held for while they load, whether it takes requests, and who it is.
/// </summary>
public sealed record PartyState(double Pos, bool Playing, PartyMedia Media, float Rate, long Stamp, List<string> Wait, bool Asks, string By)
{
    /// <summary>Where the leader is now, as best this PC can tell from <paramref name="now"/> on the shared clock.</summary>
    public double PosAt(long now) => Playing && Stamp > 0 ? Pos + Math.Max(0, now - Stamp) / 1000.0 * Rate : Pos;
}

/// <summary>A guest asking the leader to pause, play or jump to <see cref="Pos"/>.</summary>
public sealed record PartyAsk(string From, string What, double Pos);

/// <summary>Someone in the room as they last said: Status is opening, watching, paused, loading or behind.</summary>
public sealed record PartyMember(string Id, string Name, bool Leader, string Status, DateTime Seen, bool Me = false);

/// <summary>A party left, to rejoin: Leader if this PC led it, Ended if its leader ended it.</summary>
public sealed record LastParty(string Id, string Key, bool Leader, bool Ended, PartyMedia? Media = null, bool Own = false);

/// <summary>A line in the party's chat; From null is the room's own ("Sam joined").</summary>
public sealed record ChatLine(string? From, string Text, DateTime At, bool Mine = false);

/// <summary>
/// A watch party — the phone's and TV's (tv/core Party.kt), the same messages through the same relay
/// (party/, a Cloudflare Worker), so a PC, a phone and a TV can be in one party. Everyone streams the
/// torrent themselves; only the leader's play, pause and position go round, AES-GCM sealed with a key
/// that exists only in the invite link. No account: the room is a random id, having the link is being invited.
/// </summary>
public static class Party
{
    /// <summary>The Worker's address, baked in from mobile/.env's PARTY_URL; blank turns the feature off.</summary>
    public static string Server { get; } = (typeof(Party).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>()
        .FirstOrDefault(a => a.Key == "PartyUrl")?.Value ?? "").Trim().TrimEnd('/');
    public static bool Enabled => Server.Length > 0;
    /// <summary>What the others see this PC called: Settings' party name, else the profile's.</summary>
    public static Func<string> Me = () => "Someone";

    public static Room? Room { get; private set; }
    /// <summary>A party joined, started or left.</summary>
    public static event Action? Changed;

    public const int BeatMs = 10_000;
    /// <summary>A guest this far from the leader jumps to it; nearer, it changes speed instead.</summary>
    public const double SkipSeconds = 2.0;
    public const int JumpSettleMs = 10_000;
    public const double DeadzoneSeconds = 0.15;
    public const double Nudge = 0.08;
    public const int StuckMs = 2_000;
    /// <summary>A link opened on a room with no one in it, for this long, is a closed party.</summary>
    public const int EmptyMs = 6_000;
    public const int GoneMs = 75_000;
    public const int ChatMax = 300;
    public static readonly string[] Reactions = { "😂", "😮", "😱", "❤️", "👏", "🔥" };

    /// <summary>How fast a guest <paramref name="drift"/> seconds behind (negative: ahead) plays at to close the gap.</summary>
    public static float CatchUpRate(double drift, float rate) =>
        Math.Abs(drift) < DeadzoneSeconds ? rate : (float)(rate * (1 + Math.Clamp(drift / 4, -Nudge, Nudge)));

    public static Room Host(PartyMedia? film = null)
    {
        var r = new Room(Hex(RandomNumberGenerator.GetBytes(16)), RandomNumberGenerator.GetBytes(16), leader: true) { Film = film };
        Enter(r);
        return r;
    }

    /// <summary>From the invite (https://…/j/&lt;id&gt;#&lt;key&gt; or watchly://party/&lt;id&gt;?k=&lt;key&gt;); null if it isn't one.</summary>
    public static Room? Join(string link)
    {
        if (ParseInvite(link) is not { } invite) return null;
        if (Room?.Id == invite.Id) return Room;
        // A leader opening its own invite is back in its own party as its leader, not a guest of it.
        if (Last is { Leader: true } own && own.Id == invite.Id)
        {
            Rejoin();
            return Room;
        }
        var r = new Room(invite.Id, Convert.FromHexString(invite.Key), leader: false);
        Enter(r);
        return r;
    }

    /// <summary>
    /// Out of the party — Back out of the film, Leave, or the leader ending it (<paramref name="ended"/>). Kept in
    /// <see cref="Last"/> to rejoin: a leader opens the same room again (everyone's link still works), a guest goes
    /// straight back in. An invite turned down at its preview isn't kept. The phone's rule.
    /// </summary>
    public static void Leave(bool ended = false)
    {
        if (Room is { } r)
        {
            Log.Info("party", r.Leader ? "ended the party" : ended ? "the leader ended the party" : "left the party");
            if (r.Accepted) Last = new LastParty(r.Id, r.KeyHex, r.Leader, ended, r.LastTold?.Media, r.OwnSource);
        }
        Room?.Close();
        Room = null;
        Changed?.Invoke();
    }

    /// <summary>The party last left, to rejoin; null once rejoined, dismissed, or another one begins.</summary>
    public static LastParty? Last { get; private set; }

    public static void Forget()
    {
        Last = null;
        Changed?.Invoke();
    }

    public static void Rejoin()
    {
        if (Last is not { } l) return;
        var r = new Room(l.Id, Convert.FromHexString(l.Key), l.Leader) { Accepted = true, OwnSource = l.Own, Film = l.Media };
        Enter(r);
        // A leader's film, open again: a room with nothing on was why Reopen seemed to do nothing.
        if (l.Leader && l.Media is { } m) Reopen(m);
    }

    /// <summary>Opens a leader's film for <see cref="Rejoin"/>, set by the window.</summary>
    public static Action<PartyMedia> Reopen = _ => { };

    static void Enter(Room r)
    {
        Room?.Close();
        Room = r;
        Last = null;
        Log.Info("party", $"{(r.Leader ? "started" : "joining")} room {r.Id[..6]}…");
        r.Connect();
        Changed?.Invoke();
    }

    public static (string Id, string Key)? ParseInvite(string link)
    {
        var runs = Regex.Matches(link.ToLowerInvariant(), "[0-9a-f]{32}").Select(m => m.Value).ToList();
        return runs.Count >= 2 ? (runs[0], runs[1]) : null;
    }

    /// <summary>
    /// The playing file as a guest can open it: a magnet as it is, or one made from the info hash of a .torrent
    /// or a debrid link. Null for a local video or a plain link. The magnet keeps its trackers — often the
    /// release's own, where its peers are; without them a guest found a handful through the DHT and never got
    /// going. Only past 4000 characters are the last ones left off, to fit the relay's message.
    /// </summary>
    public static string? ShareableUri(string? uri, string name, string? infoHash, IEnumerable<string>? trackers = null)
    {
        string? magnet = null;
        if (uri is not null && uri.StartsWith("magnet:", StringComparison.OrdinalIgnoreCase)) magnet = uri;
        else if (infoHash is { Length: 40 })
            magnet = $"magnet:?xt=urn:btih:{infoHash.ToLowerInvariant()}&dn={Uri.EscapeDataString(name)}"
                + string.Concat((trackers ?? Enumerable.Empty<string>()).Distinct().Select(t => "&tr=" + Uri.EscapeDataString(t)));
        else if (uri is not null && Regex.Match(uri.Split('?')[0], "(?<![0-9a-fA-F])[0-9a-fA-F]{40}(?![0-9a-fA-F])") is { Success: true } m)
            magnet = $"magnet:?xt=urn:btih:{m.Value.ToLowerInvariant()}&dn={Uri.EscapeDataString(name)}";
        if (magnet is null || magnet.Length <= 4000) return magnet;
        var parts = magnet.Split('&');
        var kept = parts.Where(p => !p.StartsWith("tr=", StringComparison.OrdinalIgnoreCase)).ToList();
        foreach (var t in parts.Where(p => p.StartsWith("tr=", StringComparison.OrdinalIgnoreCase)))
            if (string.Join('&', kept.Append(t)).Length <= 4000) kept.Add(t);
        return string.Join('&', kept);
    }

    internal static string Hex(byte[] b) => Convert.ToHexString(b).ToLowerInvariant();

    internal static string Seal(byte[] key, string text)
    {
        var iv = RandomNumberGenerator.GetBytes(12);
        var plain = Encoding.UTF8.GetBytes(text);
        var cipher = new byte[plain.Length];
        var tag = new byte[16];
        using var gcm = new AesGcm(key, 16);
        gcm.Encrypt(iv, plain, cipher, tag);
        // Java's GCM: the tag after the cipher text, the nonce before both.
        return Hex([.. iv, .. cipher, .. tag]);
    }

    /// <summary>Null for anything not sealed with <paramref name="key"/>: a stranger guessing the room id gets nowhere.</summary>
    internal static string? Unseal(byte[] key, string sealedText)
    {
        try
        {
            var b = Convert.FromHexString(sealedText);
            if (b.Length < 28) return null;
            var plain = new byte[b.Length - 28];
            using var gcm = new AesGcm(key, 16);
            gcm.Decrypt(b.AsSpan(0, 12), b.AsSpan(12, plain.Length), b.AsSpan(12 + plain.Length, 16), plain);
            return Encoding.UTF8.GetString(plain);
        }
        catch
        {
            return null;
        }
    }

    internal static JsonObject EncodeState(PartyState s) => new()
    {
        ["t"] = "state", ["pos"] = s.Pos, ["playing"] = s.Playing, ["rate"] = (double)s.Rate, ["stamp"] = s.Stamp,
        ["wait"] = new JsonArray(s.Wait.Select(w => (JsonNode?)w).ToArray()), ["asks"] = s.Asks, ["by"] = s.By,
        ["uri"] = s.Media.Uri, ["file"] = s.Media.File, ["title"] = s.Media.Title,
        ["imdb"] = s.Media.Imdb, ["kind"] = s.Media.Kind, ["poster"] = s.Media.Poster,
    };

    internal static PartyState? DecodeState(JsonObject j)
    {
        string? Str(string k) => j[k] is JsonValue v && v.TryGetValue<string>(out var s) && s.Length > 0 ? s : null;
        if (Str("uri") is not { } uri || Str("file") is not { } file) return null;
        var wait = j["wait"] is JsonArray a ? a.Select(n => n?.ToString() ?? "").Where(n => n.Length > 0).ToList() : new List<string>();
        var rate = (float)Num(j, "rate", 1);
        return new PartyState(Num(j, "pos", 0), Bool(j, "playing", false), new PartyMedia(uri, file, Str("title") ?? file, Str("imdb"), Str("kind"), Str("poster")),
            rate > 0 ? rate : 1, (long)Num(j, "stamp", 0), wait, Bool(j, "asks", true), Str("by") ?? "");
    }

    internal static double Num(JsonObject j, string k, double fallback) => j[k] is JsonValue v && v.TryGetValue<double>(out var d) ? d : fallback;
    internal static bool Bool(JsonObject j, string k, bool fallback) => j[k] is JsonValue v && v.TryGetValue<bool>(out var b) ? b : fallback;
    internal static string Text(JsonObject j, string k, string fallback = "") => j[k] is JsonValue v && v.TryGetValue<string>(out var s) ? s : fallback;
}

/// <summary>
/// One room on the relay. Events arrive on the thread that made it (the UI's). Reconnects by itself
/// for as long as the party lasts; keeps the relay's clock from the quickest of its pings.
/// </summary>
public sealed class Room
{
    public string Id { get; }
    public bool Leader { get; }
    readonly byte[] key;
    readonly SynchronizationContext? ui = SynchronizationContext.Current;
    readonly string self = Party.Hex(RandomNumberGenerator.GetBytes(6));
    readonly DateTime since = DateTime.UtcNow;
    readonly SemaphoreSlim sending = new(1, 1);
    readonly CancellationTokenSource life = new();
    ClientWebSocket? socket;
    string status = "opening";

    /// <summary>The invite. A leader's carries the film's imdb id and name after the ?, so a chat app's preview shows its art; the key stays after the #, which no server sees.</summary>
    public string Link
    {
        get
        {
            var m = Film ?? LastTold?.Media;
            var query = m?.Imdb is { } imdb && System.Text.RegularExpressions.Regex.IsMatch(imdb, "^tt[0-9]{1,10}$")
                ? $"?i={imdb}&t={Uri.EscapeDataString(m.Title.Length > 80 ? m.Title[..80] : m.Title)}" : "";
            return $"{Party.Server}/j/{Id}{query}#{Party.Hex(key)}";
        }
    }
    /// <summary>Leader: the film the invite is for, from the start — LastTold is only set once the player has said something.</summary>
    public PartyMedia? Film { get; set; }
    internal string KeyHex => Party.Hex(key);
    /// <summary>A guest who has seen what the party plays and said yes (or is rejoining); a leader's own party is accepted.</summary>
    public bool Accepted { get; set; }
    /// <summary>
    /// A guest playing a copy of their own (another release of the same film, picked from its sources) rather than
    /// the leader's torrent: nothing of the leader's opens, and the film is followed by its imdb id. The phone's rule.
    /// </summary>
    public bool OwnSource { get; set; }
    public int People { get; private set; } = 1;
    public bool Connected { get; private set; }
    public Dictionary<string, PartyMember> Members { get; } = new();
    public List<ChatLine> Chat { get; } = new();
    public int Unread { get; set; }
    /// <summary>Guests: the leader's last word, for whoever starts listening late.</summary>
    public PartyState? LastState { get; private set; }
    /// <summary>
    /// Guests: this party is over — its leader ended it, or the link was opened on an empty room (the leader is
    /// long gone, so no one answers). The join screen says so rather than wait for ever. A leader coming back
    /// with a film turns it off again.
    /// </summary>
    public bool Over { get; private set; }

    public event Action<PartyState>? State;
    public event Action<PartyAsk>? Asked;
    public event Action<string>? Hello;
    public event Action<string, bool>? Stuck;
    public event Action? Ended;
    public event Action<string, string>? Reacted;
    /// <summary>Anything the party panel shows changed: people, chat, the connection.</summary>
    public event Action? Changed;

    internal Room(string id, byte[] key, bool leader)
    {
        Id = id;
        this.key = key;
        Leader = leader;
        Accepted = leader;
    }

    // ---------------------------------------------------------------- clock

    static readonly Stopwatch Mono = Stopwatch.StartNew();
    static long MonoMs => Mono.ElapsedMilliseconds;
    /// <summary>The relay's clock less this PC's monotonic one, from the ping with the shortest round trip of the last few.</summary>
    long offset = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - MonoMs;
    readonly List<(long Rtt, long Offset)> samples = new();
    /// <summary>Now on the room's shared clock, ms.</summary>
    public long Now() => MonoMs + Interlocked.Read(ref offset);

    void Clock(long sent, long server, long back)
    {
        if (sent <= 0 || server <= 0 || back < sent) return;
        lock (samples)
        {
            samples.Add((back - sent, server - (sent + back) / 2));
            if (samples.Count > 8) samples.RemoveAt(0);
            Interlocked.Exchange(ref offset, samples.MinBy(s => s.Rtt).Offset);
        }
    }

    // ---------------------------------------------------------------- connection

    internal void Connect() => _ = Task.Run(Run);

    async Task Run()
    {
        while (!life.IsCancellationRequested)
        {
            using var ws = new ClientWebSocket();
            ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(30);
            ws.Options.SetRequestHeader("User-Agent", "Watchly-Windows/2");
            try
            {
                await ws.ConnectAsync(new Uri(Party.Server.Replace("https://", "wss://").Replace("http://", "ws://") + "/room/" + Id), life.Token);
                socket = ws;
                Post(() => { Connected = true; Changed?.Invoke(); });
                if (!Leader)
                {
                    SayHello();
                    // Nobody else in the room, and no word from a leader, after this long: it is closed.
                    // (A leader's own reconnect is a few seconds; it says where it is every 10.)
                    _ = Task.Delay(Party.EmptyMs).ContinueWith(_ => Post(() =>
                    {
                        if (!life.IsCancellationRequested && ReferenceEquals(socket, ws) && People <= 1 && LastState is null) { Over = true; Changed?.Invoke(); }
                    }));
                }
                _ = Pings(ws);
                await Receive(ws);
            }
            catch (Exception e) when (!life.IsCancellationRequested)
            {
                Log.Warn("party", $"relay: {e.Message}");
            }
            catch
            {
                // Closed on purpose.
            }
            socket = null;
            Post(() => { Connected = false; Changed?.Invoke(); });
            // Dropped — a Wi-Fi switch, a sleep: in again a few seconds on.
            try { await Task.Delay(3000, life.Token); } catch { return; }
        }
    }

    /// <summary>Quick pings until the clock settles, then one each half minute — the heartbeat on everyone's list too.</summary>
    async Task Pings(ClientWebSocket ws)
    {
        var n = 0;
        while (ws.State == WebSocketState.Open && !life.IsCancellationRequested)
        {
            await Raw(ws, new JsonObject { ["t"] = "time", ["c"] = MonoMs }.ToJsonString());
            if (n == 0 || n >= 5) Here();
            var cutoff = DateTime.UtcNow.AddMilliseconds(-Party.GoneMs);
            Post(() =>
            {
                foreach (var gone in Members.Values.Where(m => !m.Me && m.Seen < cutoff).ToList()) Gone(gone.Id);
            });
            try { await Task.Delay(++n < 5 ? 1000 : 30_000, life.Token); } catch { return; }
        }
    }

    async Task Receive(ClientWebSocket ws)
    {
        var buffer = new byte[64 * 1024];
        var text = new StringBuilder();
        while (ws.State == WebSocketState.Open)
        {
            var got = await ws.ReceiveAsync(buffer, life.Token);
            if (got.MessageType == WebSocketMessageType.Close) return;
            text.Append(Encoding.UTF8.GetString(buffer, 0, got.Count));
            if (!got.EndOfMessage) continue;
            var message = text.ToString();
            text.Clear();
            try { Handle(message); } catch (Exception e) { Log.Warn("party", $"message: {e.Message}"); }
        }
    }

    async Task Raw(ClientWebSocket ws, string text)
    {
        await sending.WaitAsync();
        try
        {
            if (ws.State == WebSocketState.Open) await ws.SendAsync(Encoding.UTF8.GetBytes(text), WebSocketMessageType.Text, true, CancellationToken.None);
        }
        catch { }
        finally { sending.Release(); }
    }

    void Send(JsonObject json)
    {
        if (socket is { } ws) _ = Raw(ws, Party.Seal(key, json.ToJsonString()));
    }

    internal void Close()
    {
        Send(new JsonObject { ["t"] = "bye", ["id"] = self });
        if (Leader) Send(new JsonObject { ["t"] = "end" });
        // A moment for those two to go out before the socket does.
        _ = Task.Delay(300).ContinueWith(_ =>
        {
            life.Cancel();
            try { socket?.Abort(); } catch { }
        });
    }

    void Post(Action a)
    {
        if (ui is null) a();
        else ui.Post(_ => a(), null);
    }

    // ---------------------------------------------------------------- saying

    /// <summary>Leader: what it last told the room — kept, so Reopen knows the film.</summary>
    public PartyState? LastTold { get; private set; }

    public void Tell(PartyState s)
    {
        LastTold = s;
        Send(Party.EncodeState(s));
    }
    public void Ask(string what, double pos = 0) => Send(new JsonObject { ["t"] = "ask", ["from"] = MyName(), ["what"] = what, ["pos"] = pos });
    public void SayHello() => Send(new JsonObject { ["t"] = "hello", ["from"] = MyName() });
    public void SetStuck(bool on) => Send(new JsonObject { ["t"] = "stuck", ["from"] = MyName(), ["on"] = on });

    /// <summary>This PC's own name as the room shows it — numbered, if another here has the same.</summary>
    public string MyName() => Members.TryGetValue(self, out var m) ? m.Name : Party.Me();

    /// <summary>Each member's name as they gave it; <see cref="Members"/> hold the names as shown.</summary>
    readonly Dictionary<string, string> bases = new();

    /// <summary>
    /// Two people called Sam are "Sam 1" and "Sam 2" — the same numbers on every phone, TV and PC (the
    /// phone's rule: the leader first, then by id), so "waiting for Sam 2" means the same person to everyone.
    /// </summary>
    void Renumber()
    {
        foreach (var group in Members.Values.GroupBy(m => (bases.GetValueOrDefault(m.Id) ?? m.Name).Trim().ToLowerInvariant()).ToList())
        {
            var ordered = group.OrderByDescending(m => m.Leader).ThenBy(m => m.Id, StringComparer.Ordinal).ToList();
            for (var i = 0; i < ordered.Count; i++)
            {
                var m = ordered[i];
                var name = bases.GetValueOrDefault(m.Id) ?? m.Name;
                var shown = ordered.Count == 1 ? name : $"{name} {i + 1}";
                if (m.Name != shown) Members[m.Id] = m with { Name = shown };
            }
        }
    }

    /// <summary>What the player here is doing now; the room hears of a change at once.</summary>
    public void SetStatus(string now)
    {
        if (now == status) return;
        status = now;
        Here();
    }

    public void Say(string text)
    {
        var t = text.Trim();
        if (t.Length == 0) return;
        if (t.Length > Party.ChatMax) t = t[..Party.ChatMax];
        var me = MyName();
        Send(new JsonObject { ["t"] = "chat", ["from"] = me, ["text"] = t });
        Line(new ChatLine(me, t, DateTime.UtcNow, Mine: true));
    }

    public void React(string emoji)
    {
        if (!Party.Reactions.Contains(emoji)) return;
        var me = MyName();
        Send(new JsonObject { ["t"] = "react", ["from"] = me, ["emoji"] = emoji });
        Reacted?.Invoke(me, emoji);
    }

    /// <summary>The name as given, never the number: each one numbers the room for itself, the same way.</summary>
    void Here()
    {
        Send(new JsonObject { ["t"] = "here", ["id"] = self, ["from"] = Party.Me(), ["leader"] = Leader, ["status"] = status });
        Post(() =>
        {
            bases[self] = Party.Me();
            Members[self] = new PartyMember(self, Party.Me(), Leader, status, DateTime.UtcNow, Me: true);
            Renumber();
            Changed?.Invoke();
        });
    }

    void Line(ChatLine l)
    {
        Chat.Add(l);
        if (Chat.Count > 200) Chat.RemoveAt(0);
        if (!l.Mine && l.From is not null) Unread++;
        Changed?.Invoke();
    }

    void Gone(string id)
    {
        if (!Members.Remove(id, out var m)) return;
        bases.Remove(id);
        Line(new ChatLine(null, $"{m.Name} left", DateTime.UtcNow));
        Renumber();
    }

    // ---------------------------------------------------------------- hearing

    void Handle(string text)
    {
        // The relay's own messages, the count and the clock, are the plain ones.
        if (text.StartsWith('{'))
        {
            if (JsonNode.Parse(text) is not JsonObject plain) return;
            switch (Party.Text(plain, "t"))
            {
                case "count":
                    var n = (int)Party.Num(plain, "n", -1);
                    if (n > 0) Post(() => { People = n; Changed?.Invoke(); });
                    break;
                case "time":
                    Clock((long)Party.Num(plain, "c", 0), (long)Party.Num(plain, "s", 0), MonoMs);
                    break;
            }
            return;
        }
        if (Party.Unseal(key, text) is not { } opened || JsonNode.Parse(opened) is not JsonObject j) return;
        var from = Party.Text(j, "from", "Someone");
        switch (Party.Text(j, "t"))
        {
            case "state" when !Leader:
                if (Party.DecodeState(j) is { } s) Post(() => { LastState = s; Over = false; State?.Invoke(s); });
                break;
            case "ask" when Leader:
                var ask = new PartyAsk(from, Party.Text(j, "what"), Party.Num(j, "pos", 0));
                Post(() => Asked?.Invoke(ask));
                break;
            case "hello" when Leader:
                Post(() => Hello?.Invoke(from));
                break;
            case "stuck" when Leader:
                var on = Party.Bool(j, "on", false);
                Post(() => Stuck?.Invoke(from, on));
                break;
            case "end" when !Leader:
                Post(() => { Over = true; Changed?.Invoke(); Ended?.Invoke(); });
                break;
            case "here":
                var id = Party.Text(j, "id");
                if (id.Length == 0 || id == self) return;
                var status = Party.Text(j, "status", "watching");
                var leader = Party.Bool(j, "leader", false);
                Post(() =>
                {
                    var fresh = !Members.ContainsKey(id);
                    bases[id] = from;
                    Members[id] = new PartyMember(id, from, leader, status, DateTime.UtcNow);
                    Renumber();
                    if (fresh)
                    {
                        // Not for those already here when this PC came in: they didn't just join.
                        if ((DateTime.UtcNow - since).TotalSeconds > 5) Line(new ChatLine(null, $"{Members[id].Name} joined", DateTime.UtcNow));
                        // So the newcomer has everyone without waiting for the next heartbeat.
                        Here();
                    }
                    Changed?.Invoke();
                });
                break;
            case "bye":
                var bye = Party.Text(j, "id");
                Post(() => Gone(bye));
                break;
            case "chat":
                var t = Party.Text(j, "text").Trim();
                if (t.Length > Party.ChatMax) t = t[..Party.ChatMax];
                if (t.Length > 0) Post(() => Line(new ChatLine(from, t, DateTime.UtcNow)));
                break;
            case "react":
                var emoji = Party.Text(j, "emoji");
                if (Party.Reactions.Contains(emoji)) Post(() => Reacted?.Invoke(from, emoji));
                break;
        }
    }
}
