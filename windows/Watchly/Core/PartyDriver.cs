namespace Watchly.Core;

/// <summary>What a watch party needs of the player it rides on.</summary>
public interface IPartyPlayer
{
    /// <summary>Seconds, read off the player now.</summary>
    double PartyTime { get; }
    /// <summary>Where the player is or is going (mid-jump, the target).</summary>
    double PartySpot { get; }
    bool PartyPaused { get; }
    bool PartyLoaded { get; }
    /// <summary>Loading, or playing with no new frames for a while.</summary>
    bool PartyStarved { get; }
    bool PartySeeking { get; }
    /// <summary>The speed the film runs at: the leader's is the room's.</summary>
    float PartyRate { get; }
    string? PartyFile { get; }
    PartyMedia? PartyShare();
    void PartyPause(bool on);
    /// <summary>The player's own speed, for steering, leaving the viewer's choice alone.</summary>
    void PartySteer(float rate);
    /// <summary>A guest's shown speed set to the room's.</summary>
    void PartyShowRate(float rate);
    /// <summary>A jump the party decided on, not to be asked about again.</summary>
    void PartyJump(double seconds);
    void PartySay(string text);
    void PartySave();
    /// <summary>Something the party draws changed.</summary>
    void PartyRedraw();
}

/// <summary>A guest's pause (SeekTo null) or jump, waiting on "just me" or "everyone".</summary>
public sealed record PartyChoice(double? SeekTo);

/// <summary>
/// The watch party's side of one player — tv/core PartyDriver.kt, ported: the same rules, so a PC follows
/// a phone's party exactly as a TV does. The leader tells the room on every change and every beat; a guest
/// follows its play and pause on each change, and once a second closes a small gap by speed and a big one
/// by a jump — never while loading, and not again until the last jump has had time to load. Anyone stuck
/// loading holds the film, as long as the leader's Settings allow. A guest can pause or jump "just me" and
/// watch on its own until Catch up. Driven by the player's tick, not by timers of its own.
/// </summary>
public sealed class PartyDriver
{
    readonly IPartyPlayer p;
    readonly Func<AppSettings> settings;

    public PartyState? LeaderState { get; private set; }
    bool? leaderPlaying;
    public PartyAsk? AskShown { get; private set; }
    DateTime askAt;
    public PartyChoice? Choice { get; private set; }
    DateTime choiceAt;
    public bool Detached { get; private set; }
    public bool Stuck { get; private set; }
    DateTime? starvedSince;
    public List<string> WaitingFor { get; } = new();
    readonly HashSet<string> skipped = new();
    public bool Held { get; private set; }
    DateTime heldAt;
    public ChatLine? Peek { get; private set; }
    DateTime peekAt;
    /// <summary>A reaction to float over the picture: the player takes it.</summary>
    public event Action<string, string>? Floats;

    Room? hooked;
    string told = "";
    DateTime toldAt = DateTime.MinValue;
    DateTime steeredAt = DateTime.MinValue;
    DateTime jumpedAt = DateTime.MinValue;
    float applied;
    bool reportedStuck;
    bool? wasLoaded;

    public PartyDriver(IPartyPlayer player, Func<AppSettings> settings)
    {
        p = player;
        this.settings = settings;
    }

    public static Room? Room => Party.Room;
    public bool Guest => Room is { Leader: false };
    public bool Leader => Room is { Leader: true };
    public bool CanPickSpeed => !Guest;
    /// <summary>This PC's name as the room shows it, numbered if someone else has it too.</summary>
    public string Me => Room?.MyName() ?? Party.Me();

    /// <summary>Who the film is held for, as this PC sees it.</summary>
    public List<string> HeldFor => Leader ? (Held ? WaitingFor.ToList() : new()) : Guest ? (LeaderState is { Playing: false } s ? s.Wait : new()) : new();

    /// <summary>Changes the controls' shape: in the player's signature, so it redraws.</summary>
    public string Signature => string.Join(',', Room?.Id, Room?.People, Room?.Members.Count, Room?.Chat.Count, Room?.Connected, AskShown, Choice, Detached, Held,
        string.Join('/', HeldFor), Peek, LeaderState?.Asks);

    // ---------------------------------------------------------------- leader

    public void Tell()
    {
        if (Room is not { Leader: true } r || p.PartyShare() is not { } media) return;
        var pos = !p.PartySeeking && p.PartyLoaded && p.PartyTime > 0 ? p.PartyTime : p.PartySpot;
        var wait = WaitingFor.ToList();
        if (Stuck) wait.Add(r.MyName());
        var s = settings();
        r.Tell(new PartyState(pos, !p.PartyPaused && p.PartyLoaded && !Stuck, media, p.PartyRate, r.Now(), wait, s.PartyAsks != "off", r.MyName()));
        toldAt = DateTime.UtcNow;
    }

    public void GoOnWithout()
    {
        foreach (var w in WaitingFor) skipped.Add(w);
        WaitingFor.Clear();
        Held = false;
        p.PartyPause(false);
        p.PartyRedraw();
    }

    public void Allow(PartyAsk a)
    {
        AskShown = null;
        switch (a.What)
        {
            case "pause": p.PartyPause(true); break;
            // Held for someone loading, play is going on without them — or the banner stays over a playing film.
            case "play": if (Held) GoOnWithout(); else p.PartyPause(false); break;
            case "seek": p.PartyJump(a.Pos); break;
        }
        p.PartyRedraw();
    }

    public void Refuse()
    {
        AskShown = null;
        p.PartyRedraw();
    }

    // ---------------------------------------------------------------- the viewer's hands

    /// <summary>Play/pause pressed. True if the party took it and the player is to do nothing more.</summary>
    public bool OnTogglePause()
    {
        if (Room is not { } r) return false;
        if (r.Leader)
        {
            if (!Held) return false;
            GoOnWithout();
            return true;
        }
        if (Detached) return false;
        if (p.PartyPaused && leaderPlaying == true)
        {
            // Paused while the leader played: back in step at once, nothing to ask.
            p.PartyPause(false);
            r.SayHello();
        }
        else if (!p.PartyPaused) SetChoice(new PartyChoice(null));
        else if (LeaderState?.Asks == false) p.PartySay("Only the leader controls this party");
        else
        {
            r.Ask("play");
            p.PartySay("Asked to play");
        }
        return true;
    }

    /// <summary>A jump the viewer asked for. True if the party took it.</summary>
    public bool OnSeek(double seconds)
    {
        if (!Guest || Detached) return false;
        SetChoice(new PartyChoice(Math.Max(0, seconds)));
        return true;
    }

    void SetChoice(PartyChoice c)
    {
        Choice = c;
        choiceAt = DateTime.UtcNow;
        p.PartyRedraw();
    }

    public void DismissChoice()
    {
        Choice = null;
        p.PartyRedraw();
    }

    public void JustMe()
    {
        if (Choice is not { } c) return;
        Choice = null;
        Detached = true;
        if (c.SeekTo is { } to) p.PartyJump(to);
        else
        {
            p.PartyPause(true);
            p.PartySave();
        }
        p.PartySay("Watching on your own — Catch up any time");
        p.PartyRedraw();
    }

    public void Everyone()
    {
        if (Choice is not { } c) return;
        Choice = null;
        if (c.SeekTo is { } to) Room?.Ask("seek", to); else Room?.Ask("pause");
        p.PartySay(c.SeekTo is { } t ? $"Asked to jump to {Format.Clock(t)} for everyone" : "Asked to pause for everyone");
        p.PartyRedraw();
    }

    public void CatchUp()
    {
        Detached = false;
        var playing = LeaderState?.Playing ?? true;
        leaderPlaying = playing;
        p.PartyPause(!playing);
        Room?.SayHello();
        p.PartySay("Catching up with the party");
        p.PartyRedraw();
    }

    // ---------------------------------------------------------------- the room's word

    void Hook(Room? r)
    {
        if (ReferenceEquals(r, hooked)) return;
        if (hooked is { } old)
        {
            old.State -= OnState;
            old.Asked -= OnAsked;
            old.Hello -= OnHello;
            old.Stuck -= OnStuck;
            old.Reacted -= OnReacted;
            old.Changed -= OnChanged;
        }
        // Out of a party: the viewer's own speed again, not the leader's.
        if (hooked is not null && r is null && LeaderState is not null)
        {
            LeaderState = null;
            Detached = false;
            var own = (float)settings().PlaybackRate;
            p.PartyShowRate(own);
            p.PartySteer(own);
        }
        hooked = r;
        told = "";
        if (r is null) return;
        r.State += OnState;
        r.Asked += OnAsked;
        r.Hello += OnHello;
        r.Stuck += OnStuck;
        r.Reacted += OnReacted;
        r.Changed += OnChanged;
        // What was said before this player opened: and ask for where things are now.
        if (!r.Leader)
        {
            if (r.LastState is { } last) OnState(last);
            r.SayHello();
        }
    }

    int chatSeen;

    void OnChanged()
    {
        if (Room is { } r && r.Chat.Count > chatSeen)
        {
            var last = r.Chat[^1];
            if (!last.Mine && last.From is not null && !PanelOpen && settings().PartyShowChat)
            {
                Peek = last;
                peekAt = DateTime.UtcNow;
            }
            chatSeen = r.Chat.Count;
        }
        p.PartyRedraw();
    }

    /// <summary>The party panel is showing: no chat peeks, and nothing unread.</summary>
    public bool PanelOpen { get; set; }

    void OnReacted(string who, string emoji)
    {
        if (settings().PartyShowReactions) Floats?.Invoke(who, emoji);
    }

    void OnHello(string _) => Tell();

    void OnStuck(string who, bool on)
    {
        if (!on)
        {
            WaitingFor.Remove(who);
            skipped.Remove(who);
        }
        else if (settings().PartyWaitSec > 0 && !skipped.Contains(who) && !WaitingFor.Contains(who)) WaitingFor.Add(who);
        Tell();
        p.PartyRedraw();
    }

    void OnAsked(PartyAsk a)
    {
        switch (settings().PartyAsks)
        {
            case "allow":
                Allow(a);
                p.PartySay($"{a.From}: {(a.What == "seek" ? $"jumped to {Format.Clock(a.Pos)}" : a.What)}");
                break;
            case "off":
                break;
            default:
                AskShown = a;
                askAt = DateTime.UtcNow;
                p.PartyRedraw();
                break;
        }
    }

    void OnState(PartyState s)
    {
        if (s.Media.File != p.PartyFile || !p.PartyLoaded) return;
        var before = LeaderState;
        LeaderState = s;
        var who = s.By.Length > 0 ? s.By : "The leader";
        var sped = Math.Abs(s.Rate - p.PartyRate) > 0.001;
        if (sped) p.PartyShowRate(s.Rate);
        // Who did what — not for a hold for someone loading, nobody's hand.
        if (before is not null && s.Wait.Count == 0 && before.Wait.Count == 0)
        {
            if (sped) p.PartySay($"{who} set the speed to {s.Rate:0.##}×");
            else if (s.Playing != before.Playing) p.PartySay(s.Playing ? $"{who} pressed play" : $"{who} paused");
            else if (Math.Abs(s.Pos - before.PosAt(s.Stamp)) > 3) p.PartySay($"{who} jumped to {Format.Clock(s.Pos)}");
        }
        if (s.Playing != leaderPlaying)
        {
            leaderPlaying = s.Playing;
            if (!Detached) p.PartyPause(!s.Playing);
        }
        p.PartyRedraw();
    }

    // ---------------------------------------------------------------- the tick

    /// <summary>The player's tick, a few times a second.</summary>
    public void Tick()
    {
        var r = Room;
        Hook(r);
        var now = DateTime.UtcNow;
        var loaded = p.PartyLoaded;
        if (loaded && wasLoaded == false && r is { Leader: false }) r.SayHello();
        wasLoaded = loaded;

        // Loading for more than a moment, once the film has started here: the party waits for it.
        var starved = loaded && p.PartyStarved;
        starvedSince = starved ? starvedSince ?? now : null;
        var stuckNow = starvedSince is { } since && (now - since).TotalMilliseconds >= Party.StuckMs;
        if (stuckNow != Stuck)
        {
            Stuck = stuckNow;
            p.PartyRedraw();
        }
        var report = Stuck && !Detached;
        if (r is { Leader: false } && report != reportedStuck)
        {
            reportedStuck = report;
            r.SetStuck(report);
        }

        if (AskShown is not null && (now - askAt).TotalSeconds > 10) Refuse();
        if (Choice is not null && (now - choiceAt).TotalSeconds > 8) DismissChoice();
        if (Peek is not null && (now - peekAt).TotalSeconds > 5)
        {
            Peek = null;
            p.PartyRedraw();
        }
        if (PanelOpen && r is not null) r.Unread = 0;
        if (r is null) return;

        r.SetStatus(!loaded ? "opening" : Detached ? "behind" : Stuck ? "loading" : p.PartyPaused ? "paused" : "watching");

        if (r.Leader)
        {
            // Anyone loading holds the film — Jellyfin's "waiting" — and it plays on when they are through.
            if (loaded && WaitingFor.Count > 0 && !p.PartyPaused && !Held)
            {
                Held = true;
                heldAt = now;
                p.PartyPause(true);
                p.PartyRedraw();
            }
            else if (WaitingFor.Count == 0 && Held)
            {
                Held = false;
                p.PartyPause(false);
                p.PartyRedraw();
            }
            // Never held for ever: a PC that lost its connection mid-wait says nothing more.
            if (Held && (now - heldAt).TotalSeconds > Math.Max(1, settings().PartyWaitSec))
            {
                p.PartySay($"Went on without {string.Join(", ", WaitingFor)}");
                GoOnWithout();
            }
            var shape = $"{p.PartyPaused}|{loaded}|{p.PartyRate}|{Stuck}|{WaitingFor.Count}";
            if (shape != told || (now - toldAt).TotalMilliseconds >= Party.BeatMs)
            {
                told = shape;
                Tell();
            }
            return;
        }

        // Guest: once a second, how far from the leader on the shared clock.
        if (!loaded || (now - steeredAt).TotalMilliseconds < 1000 || LeaderState is not { } s || p.PartySeeking) return;
        steeredAt = now;
        void Steer(float rate)
        {
            if (Math.Abs(rate - applied) > 0.0001) p.PartySteer(rate);
            applied = rate;
        }
        var settled = (now - jumpedAt).TotalMilliseconds > Party.JumpSettleMs;
        void Jump(double to)
        {
            jumpedAt = now;
            Steer(s.Rate);
            p.PartyJump(to);
        }
        var here = p.PartyTime;
        if (Detached) Steer(s.Rate);
        else if (!s.Playing && p.PartyPaused)
        {
            Steer(s.Rate);
            if (Math.Abs(s.Pos - here) > 0.5 && settled) Jump(s.Pos);
        }
        // Out of step by choice, or loading: nothing to steer. Loading long, the party waits for it.
        else if (p.PartyPaused || Stuck || p.PartyStarved || !s.Playing) Steer(s.Rate);
        else
        {
            var drift = s.PosAt(r.Now()) - here;
            if (Math.Abs(drift) > Party.SkipSeconds)
            {
                if (settled) Jump(s.PosAt(r.Now()));
                else Steer(Party.CatchUpRate(drift, s.Rate));
            }
            else Steer(settings().PartySmooth ? Party.CatchUpRate(drift, s.Rate) : s.Rate);
        }
    }

    /// <summary>The player closing: a guest that was holding the party lets it go.</summary>
    public void Dispose()
    {
        if (reportedStuck) Room?.SetStuck(false);
        Hook(null);
    }
}
