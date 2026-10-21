using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using LibVLCSharp.Shared;
using LibVLCSharp.WPF;
using Watchly.Controls;
using Watchly.Core;
using Watchly.Engine;
using MediaPlayer = LibVLCSharp.Shared.MediaPlayer;

namespace Watchly.Views;

/// <summary>
/// Starting libVLC loads every plugin DLL (300+) unless plugins.dat caches
/// them, and the package ships none — seconds on a cold disk, which froze the
/// window on first play. App warms it on a worker at launch; the first run
/// also writes the cache (if the folder is writable) so later ones skip the scan.
/// Its own class, not a PlayerView static: touching a PlayerView static from the
/// worker ran PlayerView's type initializer there, and the brushes it made then
/// belonged to that thread ("Cannot use a DependencyObject that belongs to a
/// different thread than its parent Freezable").
/// </summary>
static class VlcLibrary
{
    public static readonly Lazy<LibVLC> Instance = new(() =>
    {
        var clock = System.Diagnostics.Stopwatch.StartNew();
        LibVLCSharp.Shared.Core.Initialize();
        var plugins = Path.Combine(AppContext.BaseDirectory, "libvlc", "win-x64", "plugins");
        // No metadata lookups on the internet and no Lua scripts (which do such lookups): what is
        // played stays on this machine. The Lua plugin isn't shipped either — see Watchly.csproj.
        var args = new List<string> { "--no-video-title-show", "--no-osd", "--quiet", "--no-metadata-network-access", "--no-lua" };
        if (!File.Exists(Path.Combine(plugins, "plugins.dat"))) args.Add("--reset-plugins-cache");
        var made = new LibVLC(args.ToArray());
        Log.Player($"libVLC ready in {clock.ElapsedMilliseconds} ms");
        return made;
    });

    public static void Warm() => _ = Task.Run(() => Instance.Value);
}

/// <summary>
/// src/screens/PlayerScreen.tsx on libVLC — the same engine the phone uses, so
/// every codec, container, audio track and subtitle format plays. The controls
/// are ours, drawn over the video, because no engine ships an overlay that knows
/// about swarm stats or piece-level buffering.
/// </summary>
public sealed class PlayerView : UserControl, IDisposable
{
    const int SkipSeconds = 10;
    const double ControlsScale = 1.25;
    const int UpNextSeconds = 30;
    static readonly double[] Rates = { 0.75, 1, 1.25, 1.5, 2 };
    static readonly (string Label, string? Aspect, bool Crop, float Scale)[] Scales =
    {
        ("Best fit", null, false, 0),
        ("Stretch", "fill", false, 0),
        ("Crop", "fill", true, 0),
        ("16:9", "16:9", false, 0),
        ("4:3", "4:3", false, 0),
        ("Original", null, false, 1),
    };

    public static void Warm() => VlcLibrary.Warm();

    public event Action? Exited;

    readonly PlaybackRequest request;
    readonly MainWindow shell = MainWindow.Shell;
    readonly VideoView video = new();
    readonly MediaPlayer player;
    readonly LibVLC vlc = VlcLibrary.Instance.Value;
    readonly Grid overlay = new() { Background = Brushes.Black };
    readonly DispatcherTimer tick = new() { Interval = TimeSpan.FromMilliseconds(300) };
    readonly DispatcherTimer hideTimer = new() { Interval = TimeSpan.FromSeconds(3.5) };
    readonly DispatcherTimer clickTimer = new() { Interval = TimeSpan.FromMilliseconds(230) };
    readonly CancellationTokenSource life = new();

    StreamSession? session;
    Media? media;
    bool disposed;
    Core.Title? titleMeta;
    /// <summary>The title's logo for the pause card; null (no logo) falls back to its name.</summary>
    BitmapSource? logo;
    /// <summary>When the pause card came up, so a re-render mid-fade carries the fade on.</summary>
    DateTime? pauseCardAt;
    bool started;
    bool showUi = true;
    bool locked;
    bool buffering;
    bool stalled;
    double bufferPct;
    long duration;
    long current;
    long lastMovedTime = -1;
    DateTime lastMovedAt = DateTime.UtcNow;
    DateTime seekAt = DateTime.MinValue;
    int reloads;
    /// <summary>Where the last recovery restarted, in ms — five seconds past it counts as going again.</summary>
    long reloadFrom;
    int scaleIdx;
    double rate = 1;
    /// <summary>Audio &amp; Subtitles: the two lists side by side, as Netflix shows them.</summary>
    bool picking;
    bool searchingSubs;
    /// <summary>Everything that is not in the bottom row — the phone's More sheet.</summary>
    bool more;
    bool SheetOpen => picking || searchingSubs || more;
    void CloseSheets() => picking = searchingSubs = more = false;
    bool dismissedNext;
    /// <summary>IntroDB's window for this episode, once it answers.</summary>
    Intro? intro;
    /// <summary>Once per intro: seeking back into it to watch it is not undone.</summary>
    bool autoSkipped;
    bool fullscreen;
    bool pip;
    string? failure;
    bool failureFatal;
    string? hud;
    DateTime hudUntil;
    DateTime lastSave = DateTime.UtcNow;
    readonly List<SavedSub> externalSubs;
    readonly HashSet<string> addedSlaves = new(StringComparer.OrdinalIgnoreCase);
    readonly Dictionary<int, string> slaveTrackLabels = new();
    /// <summary>The track each added file became, by path.</summary>
    readonly Dictionary<string, int> slaveTracks = new(StringComparer.OrdinalIgnoreCase);
    List<Sub> subResults = new();
    bool subLoading;
    string subQuery = "";
    Progress? stats;
    Point lastMousePos;
    DateTime lastLog = DateTime.MinValue;

    bool IsPlayingSafe
    {
        get
        {
            if (disposed) return false;
            try
            {
                var p = player;
                return p.NativeReference != IntPtr.Zero && p.IsPlaying;
            }
            catch
            {
                return false;
            }
        }
    }

    VLCState SafeState
    {
        get
        {
            if (disposed) return VLCState.Stopped;
            try
            {
                var p = player;
                return p.NativeReference != IntPtr.Zero ? p.State : VLCState.Stopped;
            }
            catch
            {
                return VLCState.Stopped;
            }
        }
    }

    // Chrome, rebuilt when state changes rather than bound.
    readonly Grid chrome = new();
    readonly TextBlock clockText = new() { Foreground = Brushes.White, FontSize = 12, FontWeight = FontWeights.Medium, MinWidth = 52, VerticalAlignment = VerticalAlignment.Center };
    readonly TextBlock lengthText = new() { Foreground = Brushes.White, FontSize = 12, FontWeight = FontWeights.Medium, MinWidth = 52, TextAlignment = TextAlignment.Right, VerticalAlignment = VerticalAlignment.Center };
    readonly SeekBar seek = new();
    SeekPreview? preview;
    /// <summary>The hovered time, in ms, when it has a picture to show; -1 for the time alone.</summary>
    long peekAt = -1;

    public PlayerView(PlaybackRequest request)
    {
        this.request = request;
        externalSubs = request.SavedSubs.Where(s => File.Exists(s.Path)).ToList();
        if (request.LocalPath is not null)
        {
            try
            {
                var dir = Path.GetDirectoryName(request.LocalPath);
                if (dir is not null && Directory.Exists(dir))
                {
                    var files = Directory.GetFiles(dir, "*.srt")
                        .Concat(Directory.GetFiles(dir, "*.vtt"))
                        .Concat(Directory.GetFiles(dir, "*.sub"));
                    foreach (var f in files)
                    {
                        if (!externalSubs.Any(s => s.Path.Equals(f, StringComparison.OrdinalIgnoreCase)))
                            externalSubs.Add(new SavedSub(f, Path.GetFileName(f)));
                    }
                }
            }
            catch { }
        }
        Background = Brushes.Black;
        Focusable = true;

        player = NewPlayer();
        video.MediaPlayer = player;
        infoPanel = new(BuildInfoPanel);
        // Picture mode and speed carry over from the last video (volume already does).
        scaleIdx = Math.Max(0, Array.FindIndex(Scales, s => s.Label == shell.Settings.PlayerScale));
        if (Rates.Contains(shell.Settings.PlaybackRate)) rate = shell.Settings.PlaybackRate;
        video.Content = overlay;
        Content = video;

        // Sized for a phone; on a desktop screen watched from further away everything reads
        // small. One scale keeps text, icons and buttons in proportion.
        chrome.LayoutTransform = new ScaleTransform(ControlsScale, ControlsScale);
        overlay.Children.Add(chrome);
        overlay.MouseMove += OnOverlayMouseMove;
        overlay.MouseLeave += OnOverlayMouseLeave;
        overlay.MouseLeftButtonDown += OnPictureDown;
        overlay.MouseWheel += OnOverlayMouseWheel;
        PreviewKeyDown += (_, e) => HandleKey(e);
        System.Windows.Interop.ComponentDispatcher.ThreadFilterMessage += OnThreadMessage;

        seek.Scrub += ratio => { seekAt = DateTime.UtcNow; current = (long)(ratio * duration); UpdateClock(); Bump(); };
        seek.Commit += ratio => SeekTo((long)(ratio * duration));
        seek.Hover += PeekAt;

        // Stretch and Crop follow the window's shape, so a resize re-applies them (once it settles).
        scaleAfterResize.Tick += (_, _) => { scaleAfterResize.Stop(); ApplyScale(); };
        SizeChanged += (_, _) => { scaleAfterResize.Stop(); scaleAfterResize.Start(); };

        tick.Tick += (_, _) => OnTick();
        hideTimer.Tick += (_, _) =>
        {
            if (disposed) return;
            if (SheetOpen)
            {
                return;
            }
            // Paused hides too: the paused screen is the pause card, not the controls.
            if (IsPlayingSafe || SafeState is VLCState.Playing or VLCState.Paused)
            {
                hideTimer.Stop();
                showUi = false;
                Render();
            }
            else
            {
                hideTimer.Interval = TimeSpan.FromSeconds(2);
            }
        };
        clickTimer.Tick += (_, _) => { clickTimer.Stop(); ToggleUi(); };

        Loaded += (_, _) => { Focus(); Keyboard.Focus(this); };
        tick.Start();
        Render();
        _ = Start();
        _ = LoadTitleMeta();
        _ = LoadIntro();
    }

    /// <summary>This episode's intro, from IntroDB. A film has no recurring titles.</summary>
    async Task LoadIntro()
    {
        if (request.Imdb is not { Length: > 0 } imdb || Search.ParseEpisode(request.Title) is not { } at) return;
        intro = await IntroDb.Find(imdb, at.Season, at.Episode);
        if (!disposed && intro is not null) Render();
    }

    /// <summary>The pause card's rating, facts and plot — Cinemeta's, cached, so usually already here.</summary>
    async Task LoadTitleMeta()
    {
        if (request.Imdb is not { Length: > 0 } imdb) return;
        var kind = request.Kind ?? (Search.ParseEpisode(request.Title) is not null ? "tv" : "movie");
        try
        {
            titleMeta = await Meta.LoadTitle(imdb, kind);
            logo = await ImageUrl.Fetch($"https://images.metahub.space/logo/medium/{imdb}/img");
        }
        catch { }
        if (!disposed && PauseCard) Render();
    }

    /// <summary>The libVLC player, its events wired.</summary>
    MediaPlayer NewPlayer()
    {
        var p = new MediaPlayer(vlc) { EnableHardwareDecoding = true, EnableKeyInput = false, EnableMouseInput = false };
        void On(Action action) => Ui(action);

        p.Playing += (_, _) => On(() =>
        {
            buffering = false;
            // The audio output exists now, and it picks up whatever mute Windows remembered
            // for this app (a pause in an older build muted it); only the viewer's M mutes.
            // Likewise the saved volume: set before playback there is no output to take it.
            player.Mute = userMuted;
            player.Volume = shell.Settings.Volume;
            OnPlaying();
            Bump();
            Render();
        });
        p.Paused += (_, _) => On(Render);
        p.Buffering += (_, e) => On(() =>
        {
            bufferPct = e.Cache;
            var now = e.Cache < 100;
            if (now != buffering) { buffering = now; Render(); }
        });
        p.LengthChanged += (_, e) => On(() =>
        {
            if (e.Length <= 0) return;
            duration = e.Length;
            if (request.HistoryUri is not null) shell.SaveDuration(request.HistoryUri, request.FileIndex, duration / 1000.0);
            UpdateClock();
        });
        p.TimeChanged += (_, e) => On(() =>
        {
            // Between asking for a seek and it landing this still reports the old spot.
            if ((DateTime.UtcNow - seekAt).TotalMilliseconds < 1200) return;
            current = e.Time;
            if (current > reloadFrom + 5000) reloads = 0;
            UpdateClock();
        });
        p.EndReached += (_, _) => On(OnEnded);
        p.EncounteredError += (_, _) => On(() =>
        {
            if (duration > 0) Warn("Playback stalled — waiting for data");
            else Fail("This file could not be played", true);
        });
        // The picture mode goes in once the video output exists, not while libVLC is creating it.
        p.Vout += (_, e) => On(() =>
        {
            hasVout = e.Count > 0;
            if (hasVout) ApplyScale();
        });
        p.ESAdded += (_, _) => On(() => tracksVersion++);
        p.ESDeleted += (_, _) => On(() => tracksVersion++);
        p.ESSelected += (_, _) => On(() => tracksVersion++);
        return p;
    }

    void OnOverlayMouseMove(object sender, MouseEventArgs e)
    {
        if (disposed) return;
        var p = e.GetPosition(overlay);
        if ((p - lastMousePos).LengthSquared > 16)
        {
            lastMousePos = p;
            Bump();
        }
    }

    void OnOverlayMouseLeave(object sender, MouseEventArgs e)
    {
        if (disposed) return;
        if (!SheetOpen && IsPlayingSafe)
        {
            hideTimer.Stop();
            hideTimer.Interval = TimeSpan.FromSeconds(1);
            hideTimer.Start();
        }
    }

    void OnOverlayMouseWheel(object sender, MouseWheelEventArgs e)
    {
        if (disposed) return;
        StepVolume(e.Delta > 0 ? 5 : -5);
        e.Handled = true;
    }

    void OnThreadMessage(ref System.Windows.Interop.MSG msg, ref bool handled)
    {
        if (handled || disposed) return;
        const int WM_KEYDOWN = 0x0100;
        const int WM_SYSKEYDOWN = 0x0104;
        if (msg.message != WM_KEYDOWN && msg.message != WM_SYSKEYDOWN) return;

        if (Keyboard.FocusedElement is TextBox) return;

        var key = KeyInterop.KeyFromVirtualKey((int)msg.wParam);
        if (key == Key.None) return;

        var window = Window.GetWindow(this) ?? Application.Current?.MainWindow;
        var source = (window is not null ? PresentationSource.FromVisual(window) : null)
                  ?? System.Windows.Interop.HwndSource.FromHwnd(msg.hwnd);
        if (source is null) return;
        var args = new KeyEventArgs(Keyboard.PrimaryDevice, source, 0, key)
        {
            RoutedEvent = Keyboard.KeyDownEvent
        };
        HandleKey(args);
        if (args.Handled)
        {
            handled = true;
        }
    }

    /// <summary>libVLC events arrive on its own threads; one queued just before closing must not touch a disposed player.</summary>
    void Ui(Action action) => Dispatcher.BeginInvoke(() => { if (!disposed) action(); });

    // ── Source ──────────────────────────────────────────────────────────────

    async Task Start()
    {
        string url;
        try
        {
            if (request.LocalPath is not null)
            {
                url = new Uri(request.LocalPath).AbsoluteUri;
            }
            else
            {
                session = await StreamSession.Start(request.Torrent!, request.FileIndex, shell.Settings.RemoveFilesAfterStop, request.HistoryUri, life.Token);
                if (disposed) { await session.DisposeAsync(); return; }
                session.WholeFile = shell.Settings.StreamWholeFile;
                url = session.Url;
            }
        }
        catch (OperationCanceledException)
        {
            return;
        }
        catch (Exception e)
        {
            Fail($"Could not start: {e.Message}", true);
            return;
        }
        Load(url, request.ResumeAt);
    }

    void Load(string url, double startSeconds)
    {
        var old = media;
        media = new Media(vlc, new Uri(url),
            ":network-caching=600",
            ":file-caching=300",
            ":live-caching=300",
            ":clock-jitter=0",
            $":start-time={startSeconds.ToString(System.Globalization.CultureInfo.InvariantCulture)}");
        started = true;
        buffering = true;
        current = (long)(startSeconds * 1000);
        if (old is not null)
        {
            // A reload opens a new video window: black loading screen again until it plays,
            // and the new media needs its external subtitles attached afresh.
            firstPlay = true;
            addedSlaves.Clear();
            slaveTrackLabels.Clear();
            slaveTracks.Clear();
        }
        player.Play(media);
        player.SetRate((float)rate);
        old?.Dispose();
        Render();
    }

    bool firstPlay = true;

    void OnPlaying()
    {
        if (!firstPlay) return;
        firstPlay = false;
        foreach (var sub in externalSubs) AddSlave(sub.Path, false);
    }

    void AddSlave(string path, bool select)
    {
        if (!addedSlaves.Add(path))
        {
            // Picked again: switch to the track it already is, rather than load it twice.
            if (select && slaveTracks.TryGetValue(path, out var id) && player.Spu != id) player.SetSpu(id);
            return;
        }
        var label = externalSubs.FirstOrDefault(s => s.Path.Equals(path, StringComparison.OrdinalIgnoreCase))?.Label
                 ?? Path.GetFileName(path);
        try
        {
            var before = player.SpuDescription.Select(t => t.Id).ToHashSet();
            // libVLC selects the file itself when asked to; selecting again is a track switch.
            player.AddSlave(MediaSlaveType.Subtitle, new Uri(path).AbsoluteUri, select);
            _ = LabelNewTrack(path, label, select, before);
        }
        catch (Exception ex)
        {
            Log.Warn("player", $"AddSlave failed for '{path}': {ex.Message}");
        }
    }

    /// <summary>
    /// Name the one track the file added, once libVLC lists it. Only a track that was not there
    /// before counts: this used to name, and select, every subtitle not yet named — a film's own
    /// tracks too — and each select is a track switch that makes libVLC resync the stream, so
    /// adding one subtitle lagged and smeared the picture.
    /// </summary>
    async Task LabelNewTrack(string path, string label, bool select, HashSet<int> before)
    {
        for (var i = 0; i < 20 && !disposed; i++)
        {
            await Task.Delay(150);
            if (disposed) return;
            var added = player.SpuDescription.Select(t => t.Id).FirstOrDefault(id => id >= 0 && !before.Contains(id) && !slaveTrackLabels.ContainsKey(id), -1);
            if (added < 0) continue;
            slaveTrackLabels[added] = label;
            slaveTracks[path] = added;
            if (select)
            {
                if (player.Spu != added) player.SetSpu(added);
                ShowHud($"Subtitles: {label}");
            }
            else if (picking) Render();
            return;
        }
    }

    void OnTick()
    {
        if (disposed) return;
        if (session is not null)
        {
            stats = session.Progress;
            _ = session.KeepBuffer(current / 1000.0, duration / 1000.0);
            seek.Downloaded = session.Downloaded();
        }
        if (showInfo) UpdateInfo();

        // The stall libVLC never reports: the socket blocks on a piece that has
        // not landed, the picture freezes, and no event fires. A clock that stops
        // is the only signal there is.
        if (IsPlayingSafe)
        {
            if (current != lastMovedTime) { lastMovedTime = current; lastMovedAt = DateTime.UtcNow; }
            var nowStalled = (DateTime.UtcNow - lastMovedAt).TotalMilliseconds > 1200;
            if (nowStalled != stalled) { stalled = nowStalled; Render(); }
        }
        else if (stalled)
        {
            stalled = false;
            Render();
        }

        if (intro is { } skipping && !autoSkipped && shell.Settings.AutoSkipIntro && duration > 0 && IsPlayingSafe && skipping.Covers(current / 1000.0))
        {
            autoSkipped = true;
            SeekTo((long)(skipping.End * 1000));
            ShowHud("Skipped intro");
        }

        if (session is not null)
        {
            foreach (var path in session.ReadySubtitles())
            {
                if (!externalSubs.Any(s => s.Path.Equals(path, StringComparison.OrdinalIgnoreCase)))
                    externalSubs.Add(new SavedSub(path, Path.GetFileName(path)));
                AddSlave(path, false);
            }
        }

        if ((DateTime.UtcNow - lastLog).TotalSeconds >= 3)
        {
            lastLog = DateTime.UtcNow;
            if (stats is not null)
            {
                Log.Player($"{Format.Speed(stats.Speed)} · seeds={stats.Seeds} · peers={stats.Peers} · {stats.State} · {Format.Clock(current / 1000.0)}/{Format.Clock(duration / 1000.0)} · buf={bufferPct:0}%");
            }
        }

        if ((DateTime.UtcNow - lastSave).TotalSeconds >= 5 && current > 0)
        {
            lastSave = DateTime.UtcNow;
            SavePosition();
        }

        if (hud is not null && DateTime.UtcNow > hudUntil) hud = null;
        Refresh();
    }

    string lastSignature = "";

    /// <summary>
    /// Everything that changes the shape of the controls. Rebuilding them on
    /// every tick would swallow clicks — a press and its release landing on two
    /// different buttons — so a tick only rebuilds when this moves, and
    /// otherwise just updates the numbers in place.
    /// </summary>
    string Signature() => string.Join('|',
        started, Preparing, showUi, locked, buffering || stalled, started && IsPlayingSafe, PauseCard,
        picking, more, searchingSubs, hud, UpNextVisible, SkipIntroVisible,
        failure, failureFatal, scaleIdx, rate, fullscreen, pip, showInfo,
        userMuted, tracksVersion,
        stats != null, subLoading, subResults.Count);

    /// <summary>
    /// Bumped by libVLC's own track events (added, removed, selected), so a track change still
    /// redraws the controls. Asking libVLC for the current tracks on every tick instead crashed
    /// natively while a remux was still setting up its many tracks, like the volume read did.
    /// </summary>
    int tracksVersion;

    /// <summary>
    /// Until libVLC has actually started playing, the video window under the controls is
    /// unpainted (white), so the black loading screen stays up. Keying this on the clock
    /// ("current &lt;= 0") broke on resume: the clock starts at the resume point, the
    /// see-through controls went up at once, and the white showed through as grey.
    /// </summary>
    bool Preparing => !started || firstPlay;

    void Refresh()
    {
        if (Signature() != lastSignature) Render();
        else UpdateLive();
    }

    void UpdateLive()
    {
        if (disposed) return;
        UpdateClock();
        if (stats is { } s)
        {
            liveSwarm.Text = $"{s.Seeds}/{s.Peers}";
            liveSpeed.Text = Format.Speed(s.Speed);
            livePct.Text = $"· {s.Percent:0}%";
            liveMeta.Text = $"{Format.Speed(s.Speed)} · {s.Seeds} seeds · {s.Peers} peers";
        }
        liveStage.Text = request.LocalPath is not null ? "Opening" : !started ? StageLabel(stats) : $"Buffering {Math.Round(bufferPct)}%";
        liveUpNext.Text = $"Up next in {Math.Max(0, (int)Math.Ceiling((duration - current) / 1000.0))}s";
        liveVolume.Text = shell.Settings.Volume.ToString();
        volumeFill.Height = VolumeTrack * Math.Clamp(shell.Settings.Volume, 0, 150) / 150.0;
        var ends = DateTime.Now.AddMilliseconds(Math.Max(0, duration - current) / rate);
        liveNow.Text = DateTime.Now.ToString("t");
        liveEnds.Text = duration > 0 ? $"Ends at {ends:t}" : "";
        livePauseEnds.Text = $"Ends at {ends:t} if you resume now";
        liveDot.Fill = request.LocalPath is not null || (stats?.Peers ?? 0) > 0 ? Green : White(0x55);
        ring.Percent = bufferPct;
    }

    static TextBlock Live(double size, byte alpha, FontWeight? weight = null) => new()
    {
        FontSize = size,
        Foreground = new SolidColorBrush(Color.FromArgb(alpha, 255, 255, 255)),
        FontWeight = weight ?? FontWeights.Normal,
        VerticalAlignment = VerticalAlignment.Center,
    };

    readonly TextBlock liveSwarm = Live(11, 0xd0, FontWeights.Medium);
    readonly TextBlock liveSpeed = Live(11, 0xd0, FontWeights.Medium);
    readonly TextBlock livePct = Live(11, 0x90);
    readonly TextBlock liveMeta = Live(11, 0x80);
    readonly TextBlock liveStage = Live(12, 0xb0);
    readonly TextBlock liveUpNext = Live(11, 0xa0);
    readonly TextBlock liveVolume = Live(12, 0xff, FontWeights.SemiBold);
    readonly TextBlock liveNow = Live(14, 0xff, FontWeights.Bold);
    readonly TextBlock liveEnds = Live(11, 0xa0);
    readonly TextBlock livePauseEnds = Live(11, 0x80);
    readonly System.Windows.Shapes.Ellipse liveDot = new() { Width = 8, Height = 8, VerticalAlignment = VerticalAlignment.Center };
    readonly Ring ring = new();
    /// <summary>The volume meter's fill, out of 150 (libVLC's ceiling).</summary>
    readonly Border volumeFill = new() { CornerRadius = new CornerRadius(3), VerticalAlignment = VerticalAlignment.Bottom, Background = Brushes.White, IsHitTestVisible = false };
    const double VolumeTrack = 72;

    void SavePosition()
    {
        if (request.HistoryUri is null || current <= 0) return;
        shell.SavePosition(request.HistoryUri, request.FileIndex, current / 1000.0);
    }

    /// <summary>Paused with the controls away: what you are watching and how much is left.</summary>
    bool PauseCard => started && !Preparing && !showUi && !locked && !pip && !buffering && !stalled && SafeState == VLCState.Paused;

    bool SkipIntroVisible => intro is { } i && started && duration > 0 && !Preparing && !locked && !SheetOpen && !UpNextVisible && i.Covers(current / 1000.0);

    bool UpNextVisible =>request.Next is not null && !dismissedNext && duration > 0 && duration - current <= UpNextSeconds * 1000;

    void OnEnded()
    {
        // "End of stream" over a torrent can mean the connection dropped twenty
        // minutes into a three-hour film. Only the real end counts as the end.
        var nearEnd = duration > 0 && current >= duration - 10_000;
        if (nearEnd && request.Next is not null && !dismissedNext)
        {
            PlayNext();
            return;
        }
        if (duration > 0 && !nearEnd && media is not null)
        {
            if (reloads > 2)
            {
                Fail("The stream keeps dropping", true);
                return;
            }
            reloads++;
            reloadFrom = current;
            Warn("Stream dropped — picking it back up");
            var at = current / 1000.0;
            var url = media.Mrl;
            Dispatcher.BeginInvoke(() => Load(url, at));
            return;
        }
        RequestExit();
    }

    void PlayNext()
    {
        if (request.Next is not { } next) return;
        SavePosition();
        dismissedNext = true;
        var go = next.Go;
        // Hand the screen back first: if the next episode has to be searched for
        // and nothing turns up, the page underneath is where that is said.
        _ = Close().ContinueWith(_ => Dispatcher.BeginInvoke(() => { Exited?.Invoke(); go(); }));
    }

    // ── Controls ────────────────────────────────────────────────────────────

    void Bump()
    {
        overlay.Cursor = null;
        if (!showUi) { showUi = true; Render(); }
        hideTimer.Stop();
        hideTimer.Interval = TimeSpan.FromSeconds(SheetOpen ? 12 : 3.5);
        hideTimer.Start();
    }

    void ToggleUi()
    {
        if (locked) { Bump(); return; }
        if (SheetOpen) { CloseSheets(); Render(); return; }
        showUi = !showUi;
        if (showUi) Bump();
        Render();
    }

    void OnPictureDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource != overlay && e.OriginalSource is not Border { Tag: "dim" }) return;
        if (SheetOpen) { CloseSheets(); Render(); return; }
        if (pip)
        {
            // The mini window has no title bar: the picture is the handle. Double-click goes back.
            if (e.ClickCount == 2) TogglePip();
            else try { Window.GetWindow(this)?.DragMove(); } catch { }
            return;
        }
        if (e.ClickCount == 2)
        {
            clickTimer.Stop();
            if (!locked) ToggleFullscreen();
            return;
        }
        clickTimer.Start();
    }

    public void HandleKey(KeyEventArgs e)
    {
        if (e.Handled) return;
        if (Keyboard.FocusedElement is TextBox)
        {
            if (e.Key == Key.Escape) { searchingSubs = false; Render(); e.Handled = true; }
            return;
        }
        if (locked && e.Key != Key.Escape) { Bump(); e.Handled = true; return; }
        switch (e.Key)
        {
            case Key.Space or Key.K or Key.MediaPlayPause:
                TogglePlay();
                break;
            case Key.Left or Key.J:
                Skip(-SkipSeconds);
                break;
            case Key.Right or Key.L:
                Skip(SkipSeconds);
                break;
            // Volume shows its own little readout; it must not pull up the whole control bar.
            case Key.Up:
                StepVolume(5);
                e.Handled = true;
                return;
            case Key.Down:
                StepVolume(-5);
                e.Handled = true;
                return;
            case Key.M:
                userMuted = !userMuted;
                player.Mute = userMuted;
                // Not player.Mute: libVLC applies it to the audio output a moment later, so
                // reading it back straight away still gives the old state ("Muted" on unmute).
                ShowHud(userMuted ? "Muted" : $"Volume {shell.Settings.Volume}%");
                e.Handled = true;
                return;
            case Key.A:
                CycleAudio();
                break;
            case Key.S:
                CycleSubtitles();
                break;
            case Key.C:
                ToggleSubtitles();
                break;
            case Key.OemOpenBrackets:
                AdjustSpeed(-1);
                break;
            case Key.OemCloseBrackets:
                AdjustSpeed(1);
                break;
            case Key.D0 or Key.NumPad0:
                SeekRatio(0);
                break;
            case Key.D1 or Key.NumPad1:
                SeekRatio(0.1);
                break;
            case Key.D2 or Key.NumPad2:
                SeekRatio(0.2);
                break;
            case Key.D3 or Key.NumPad3:
                SeekRatio(0.3);
                break;
            case Key.D4 or Key.NumPad4:
                SeekRatio(0.4);
                break;
            case Key.D5 or Key.NumPad5:
                SeekRatio(0.5);
                break;
            case Key.D6 or Key.NumPad6:
                SeekRatio(0.6);
                break;
            case Key.D7 or Key.NumPad7:
                SeekRatio(0.7);
                break;
            case Key.D8 or Key.NumPad8:
                SeekRatio(0.8);
                break;
            case Key.D9 or Key.NumPad9:
                SeekRatio(0.9);
                break;
            case Key.Home:
                SeekTo(0);
                ShowHud("Start (0:00)");
                break;
            case Key.End:
                if (duration > 0)
                {
                    SeekTo(Math.Max(0, duration - 10_000));
                    ShowHud($"End ({Format.Clock((duration - 10_000) / 1000.0)})");
                }
                break;
            case Key.H or Key.OemQuestion:
                ShowShortcutsHelp();
                break;
            case Key.F or Key.F11:
                ToggleFullscreen();
                break;
            case Key.P:
                TogglePip();
                break;
            case Key.I:
                ToggleInfo();
                break;
            case Key.N when request.Next is not null:
                PlayNext();
                break;
            case Key.Escape:
                if (locked) { Lock(false); break; }
                if (SheetOpen) { CloseSheets(); Render(); break; }
                if (fullscreen) { ToggleFullscreen(); break; }
                if (pip) { TogglePip(); break; }
                RequestExit();
                break;
            case Key.BrowserBack or Key.Back:
                RequestExit();
                break;
            default:
                return;
        }
        e.Handled = true;
        // Seeking shows its own little readout (like volume); only other keys bring up the controls.
        if (e.Key is not (Key.Left or Key.Right or Key.J or Key.L or Key.Home or Key.End
            or >= Key.D0 and <= Key.D9 or >= Key.NumPad0 and <= Key.NumPad9)) Bump();
    }

    void CycleAudio()
    {
        var tracks = player.AudioTrackDescription.Where(t => t.Id >= 0).ToList();
        if (tracks.Count <= 1)
        {
            ShowHud(tracks.Count == 1 ? $"Audio: {tracks[0].Name}" : "No audio tracks detected");
            return;
        }
        var currentId = player.AudioTrack;
        var idx = tracks.FindIndex(t => t.Id == currentId);
        var next = tracks[(idx + 1) % tracks.Count];
        player.SetAudioTrack(next.Id);
        var label = string.IsNullOrWhiteSpace(next.Name) ? $"Track {next.Id}" : next.Name;
        ShowHud($"Audio: {label}");
        Render();
    }

    void CycleSubtitles()
    {
        var tracks = player.SpuDescription.Where(t => t.Id >= 0).ToList();
        if (tracks.Count == 0)
        {
            ShowHud("No subtitles available");
            return;
        }
        var currentId = player.Spu;
        if (currentId < 0)
        {
            var first = tracks[0];
            player.SetSpu(first.Id);
            ShowHud($"Subtitles: {FriendlySub(first.Name, first.Id)}");
        }
        else
        {
            var idx = tracks.FindIndex(t => t.Id == currentId);
            if (idx >= 0 && idx < tracks.Count - 1)
            {
                var next = tracks[idx + 1];
                player.SetSpu(next.Id);
                ShowHud($"Subtitles: {FriendlySub(next.Name, next.Id)}");
            }
            else
            {
                player.SetSpu(-1);
                ShowHud("Subtitles: Off");
            }
        }
        Render();
    }

    void ToggleSubtitles()
    {
        var tracks = player.SpuDescription.Where(t => t.Id >= 0).ToList();
        if (tracks.Count == 0)
        {
            ShowHud("No subtitles available");
            return;
        }
        if (player.Spu >= 0)
        {
            player.SetSpu(-1);
            ShowHud("Subtitles: Off");
        }
        else
        {
            var first = tracks[0];
            player.SetSpu(first.Id);
            ShowHud($"Subtitles: {FriendlySub(first.Name, first.Id)}");
        }
        Render();
    }

    void AdjustSpeed(int direction)
    {
        var currentIdx = Array.IndexOf(Rates, rate);
        if (currentIdx < 0) currentIdx = 1;
        var nextIdx = Math.Clamp(currentIdx + direction, 0, Rates.Length - 1);
        rate = Rates[nextIdx];
        player.SetRate((float)rate);
        shell.ChangeSettings(shell.Settings with { PlaybackRate = rate });
        ShowHud($"Speed {rate}x");
        Render();
    }

    void SeekRatio(double ratio)
    {
        if (duration <= 0) return;
        var target = (long)(ratio * duration);
        SeekTo(target);
        ShowHud($"{Format.Clock(target / 1000.0)} ({(int)(ratio * 100)}%)");
    }

    void ShowShortcutsHelp()
    {
        ShowHud("F: Fullscreen · P: Mini player · I: Stats · Space: Play/Pause · ←/→: 10s · ↑/↓: Vol · A: Audio · S: Subs · [: Slower · ]: Faster", 3000);
    }

    bool userMuted;

    void TogglePlay()
    {
        if (!started || disposed) return;
        // No muting around pause: libVLC's mute is Windows' mute for this app's audio session,
        // which Windows remembers — pause, close, and every later launch started silent.
        if (IsPlayingSafe) player.SetPause(true);
        else if (SafeState == VLCState.Ended || SafeState == VLCState.Stopped) player.Play();
        else player.SetPause(false);
        Render();
    }

    void SeekTo(long ms)
    {
        if (duration <= 0) return;
        var to = Math.Clamp(ms, 0, duration - 1000);
        seekAt = DateTime.UtcNow;
        current = to;
        // The clock is held still for 1.2 s after a seek (see TimeChanged); don't read that as a stall.
        lastMovedAt = seekAt.AddMilliseconds(1200);
        player.Time = to;
        UpdateClock();
    }

    void Skip(int seconds)
    {
        SeekTo(current + seconds * 1000L);
        ShowHud($"{(seconds < 0 ? "−" : "+")}{Math.Abs(seconds)} seconds");
    }

    void StepVolume(int by) => SetVolume(shell.Settings.Volume + by);

    void SetVolume(int level)
    {
        var next = Math.Clamp(level, 0, 150);
        player.Volume = next;
        player.Mute = false;
        userMuted = false;
        ShowHud($"Volume {next}%");
        shell.ChangeSettings(shell.Settings with { Volume = next });
    }

    void ShowHud(string text, int durationMs = 1200)
    {
        hud = text;
        hudUntil = DateTime.UtcNow.AddMilliseconds(durationMs);
        Render();
    }

    void Warn(string message)
    {
        failure = message;
        failureFatal = false;
        var until = DateTime.UtcNow.AddSeconds(3);
        Render();
        var t = new DispatcherTimer { Interval = TimeSpan.FromSeconds(3) };
        t.Tick += (_, _) => { t.Stop(); if (!failureFatal && DateTime.UtcNow >= until) { failure = null; Render(); } };
        t.Start();
    }

    void Fail(string message, bool fatal)
    {
        failure = message;
        failureFatal = fatal;
        buffering = false;
        Render();
    }

    void Lock(bool on)
    {
        locked = on;
        if (on) showUi = false;
        ShowHud(on ? "Locked" : "Unlocked");
        if (!on) Bump();
    }

    void CycleScale()
    {
        scaleIdx = (scaleIdx + 1) % Scales.Length;
        ApplyScale();
        shell.ChangeSettings(shell.Settings with { PlayerScale = Scales[scaleIdx].Label });
        ShowHud(Scales[scaleIdx].Label);
    }

    // ── Stats panel ─────────────────────────────────────────────────────────

    bool showInfo;
    readonly SpeedGraph graph = new();
    readonly Dictionary<string, TextBlock> infoValues = new();
    DateTime infoAt;
    int infoTracksVersion = -1;
    string videoInfo = "—", audioInfo = "—";

    void ToggleInfo()
    {
        showInfo = !showInfo;
        infoAt = default;
        if (showInfo) UpdateInfo();
        Render();
    }

    static readonly (string Section, string[] Rows)[] InfoLayout =
    {
        ("TORRENT", new[] { "Seeders", "Leechers", "Connections", "Download", "Upload", "File", "Pieces", "Fetching" }),
        ("VIDEO", new[] { "Codec", "Resolution", "Frame rate", "Displayed", "Lost frames" }),
        ("AUDIO", new[] { "Track", "Played buffers", "Lost buffers" }),
        ("STREAM", new[] { "Input bitrate", "Content bitrate", "Average bitrate", "Read", "Corrupted", "Discontinuities" }),
    };

    readonly Lazy<FrameworkElement> infoPanel;

    /// <summary>
    /// Two columns (graph, torrent and audio | video and stream), in a Viewbox that only ever
    /// shrinks: it sits between the top bar and the bottom controls, and in a small window it
    /// scales down to fit there rather than running over the seek bar.
    /// </summary>
    FrameworkElement BuildInfoPanel()
    {
        Grid Column(bool withGraph, params int[] sections)
        {
            var grid = new Grid { Width = 272 };
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            void Row(UIElement left, UIElement? right = null)
            {
                grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                var r = grid.RowDefinitions.Count - 1;
                Grid.SetRow(left, r);
                if (right is null) Grid.SetColumnSpan(left, 2);
                grid.Children.Add(left);
                if (right is null) return;
                Grid.SetRow(right, r);
                Grid.SetColumn(right, 1);
                grid.Children.Add(right);
            }
            if (withGraph)
            {
                var graphHeading = WText("DOWNLOAD VS VIDEO BITRATE · LAST 2 MIN", 10, White(0x80), FontWeights.SemiBold);
                graphHeading.Margin = new Thickness(0, 0, 0, 4);
                Row(graphHeading);
                graph.Margin = new Thickness(0, 0, 0, 2);
                Row(graph);
            }
            foreach (var index in sections)
            {
                var (section, rows) = InfoLayout[index];
                var heading = WText(section, 10, White(0x80), FontWeights.SemiBold);
                heading.Margin = new Thickness(0, grid.RowDefinitions.Count == 0 ? 0 : 10, 0, 4);
                Row(heading);
                foreach (var name in rows)
                {
                    var label = WText(name, 11, White(0x90));
                    label.Margin = new Thickness(0, 1, 16, 1);
                    var value = WText("—", 11, null, FontWeights.Medium);
                    value.Margin = new Thickness(0, 1, 0, 1);
                    value.TextAlignment = TextAlignment.Right;
                    infoValues[name] = value;
                    Row(label, value);
                }
            }
            return grid;
        }

        var right = Column(false, 1, 3);
        right.Margin = new Thickness(24, 0, 0, 0);
        return new Viewbox
        {
            Stretch = Stretch.Uniform,
            StretchDirection = StretchDirection.DownOnly,
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Top,
            // Below the top bar, above the seek bar and actions.
            Margin = new Thickness(16, 64, 16, 150),
            IsHitTestVisible = false,
            Child = new Border
            {
                CornerRadius = new CornerRadius(12),
                Background = Black(0xcc),
                Padding = new Thickness(14, 12, 14, 12),
                Child = new StackPanel
                {
                    Orientation = Orientation.Horizontal,
                    Children = { Column(true, 0, 2), right },
                },
            },
        };
    }

    void SetInfo(string name, string value)
    {
        if (infoValues.TryGetValue(name, out var t)) t.Text = value;
    }

    static string Kbps(double kbps) => kbps >= 1000 ? $"{kbps / 1000:0.0} Mb/s" : $"{kbps:0} kb/s";

    /// <summary>
    /// Once a second while the panel is up. Swarm numbers are MonoTorrent's own; the picture and
    /// sound figures are libVLC's input statistics. Tracks are read only when libVLC reports a
    /// track change, never on the tick (see <see cref="tracksVersion"/>).
    /// </summary>
    void UpdateInfo()
    {
        if (disposed) return;
        _ = infoPanel.Value; // builds the rows SetInfo fills
        if (DateTime.UtcNow - infoAt < TimeSpan.FromSeconds(1)) return;
        infoAt = DateTime.UtcNow;

        if (session is { } s)
        {
            var m = s.Manager;
            var p = s.Progress;
            var file = m.Files[s.FileIndex];
            var (have, total) = s.Pieces();
            SetInfo("Seeders", p.Seeds.ToString());
            SetInfo("Leechers", p.Peers.ToString());
            SetInfo("Connections", m.OpenConnections.ToString());
            SetInfo("Download", Format.Speed(p.Speed));
            SetInfo("Upload", Format.Speed(p.UploadSpeed));
            SetInfo("File", $"{p.Percent:0.0}% of {Format.Bytes(file.Length)}");
            SetInfo("Pieces", $"{have} / {total} · {Format.Bytes(m.Torrent?.PieceLength ?? 0)}");
            SetInfo("Fetching", s.Borrowed ? "Whole file (download)" : s.WholeFile ? "Whole file" : "Next 5 minutes");
        }
        else
        {
            foreach (var name in InfoLayout[0].Rows) SetInfo(name, "—");
            SetInfo("File", "Local file");
        }

        var downMbps = session is { } s3 ? s3.Progress.Speed * 8 / 1e6 : 0;
        var m2 = media;
        if (m2 is null || !started)
        {
            graph.Push(downMbps, 0);
            return;
        }
        if (infoTracksVersion != tracksVersion)
        {
            infoTracksVersion = tracksVersion;
            ReadTracks(m2);
        }
        SetInfo("Codec", videoInfo.Split('|')[0]);
        SetInfo("Resolution", videoInfo.Split('|').ElementAtOrDefault(1) ?? "—");
        SetInfo("Frame rate", videoInfo.Split('|').ElementAtOrDefault(2) ?? "—");
        SetInfo("Track", audioInfo);

        var st = m2.Statistics;
        SetInfo("Displayed", st.DisplayedPictures.ToString("N0"));
        SetInfo("Lost frames", st.LostPictures.ToString("N0"));
        SetInfo("Played buffers", st.PlayedAudioBuffers.ToString("N0"));
        SetInfo("Lost buffers", st.LostAudioBuffers.ToString("N0"));
        // × 8000 = kb/s, the same scaling VLC's own statistics window uses.
        SetInfo("Input bitrate", Kbps(st.InputBitrate * 8000));
        SetInfo("Content bitrate", Kbps(st.DemuxBitrate * 8000));
        var length = session is { } s2 ? s2.Manager.Files[s2.FileIndex].Length
            : request.LocalPath is { } lp && File.Exists(lp) ? new FileInfo(lp).Length : 0;
        SetInfo("Average bitrate", length > 0 && duration > 0 ? Kbps(length * 8.0 / duration) : "—");
        SetInfo("Read", Format.Bytes(st.ReadBytes));
        SetInfo("Corrupted", st.DemuxCorrupted.ToString("N0"));
        SetInfo("Discontinuities", st.DemuxDiscontinuity.ToString("N0"));
        graph.Push(downMbps, st.DemuxBitrate * 8); // × 8000 kb/s ÷ 1000 = Mb/s
    }

    void ReadTracks(Media m)
    {
        try
        {
            // MediaTrack is a struct, and a default one reads as an audio track: go by index.
            var tracks = m.Tracks;
            var vi = Array.FindIndex(tracks, t => t.TrackType == TrackType.Video);
            if (vi >= 0)
            {
                var video = tracks[vi];
                var v = video.Data.Video;
                var fps = v.FrameRateDen > 0 ? $"{(double)v.FrameRateNum / v.FrameRateDen:0.###} fps" : "—";
                videoInfo = $"{Codec(m, TrackType.Video, video.Codec)}|{v.Width} × {v.Height}|{fps}";
            }
            var audioId = player.AudioTrack;
            var ai = Array.FindIndex(tracks, t => t.TrackType == TrackType.Audio && t.Id == audioId);
            if (ai < 0) ai = Array.FindIndex(tracks, t => t.TrackType == TrackType.Audio);
            if (ai >= 0)
            {
                var a = tracks[ai];
                var d = a.Data.Audio;
                var lang = string.IsNullOrEmpty(a.Language) ? "" : $" · {a.Language}";
                audioInfo = $"{Codec(m, TrackType.Audio, a.Codec)} · {d.Channels} ch · {d.Rate / 1000.0:0.#} kHz{lang}";
            }
        }
        catch (Exception e)
        {
            Log.Warn("player", $"track info: {e.Message}");
        }
    }

    static string Codec(Media m, TrackType type, uint fourcc)
    {
        var name = m.CodecDescription(type, fourcc);
        if (!string.IsNullOrWhiteSpace(name)) return name;
        var b = BitConverter.GetBytes(fourcc);
        return System.Text.Encoding.ASCII.GetString(b).Trim();
    }

    /// <summary>What libVLC was last given; it starts with none of it (best fit).</summary>
    (string? Crop, string? Aspect, float Scale) appliedScale = (null, null, 0);
    bool hasVout;
    readonly DispatcherTimer scaleAfterResize = new() { Interval = TimeSpan.FromMilliseconds(200) };

    /// <summary>
    /// Only once the video output exists, and only what changed: libVLC crashed (in the crop
    /// setter, and natively in strlen) when these strings were set while it was still creating
    /// the output — on every start in Stretch mode. Best fit sets nothing at all.
    /// </summary>
    void ApplyScale()
    {
        if (disposed || !hasVout) return;
        var mode = Scales[scaleIdx];
        var ratio = ActualWidth > 0 && ActualHeight > 0 ? $"{(int)ActualWidth}:{(int)ActualHeight}" : null;
        var want = (Crop: mode.Crop ? ratio : null, Aspect: mode.Aspect == "fill" ? (mode.Crop ? null : ratio) : mode.Aspect, mode.Scale);
        if (want.Crop != appliedScale.Crop) player.CropGeometry = want.Crop;
        if (want.Aspect != appliedScale.Aspect) player.AspectRatio = want.Aspect;
        if (want.Scale != appliedScale.Scale) player.Scale = want.Scale;
        appliedScale = want;
    }

    [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential)]
    struct RECT { public int Left, Top, Right, Bottom; }

    [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential)]
    struct MONITORINFO { public int Size; public RECT Monitor, Work; public uint Flags; }

    [System.Runtime.InteropServices.DllImport("user32.dll")] static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint flags);
    [System.Runtime.InteropServices.DllImport("user32.dll")] static extern bool GetMonitorInfo(IntPtr monitor, ref MONITORINFO info);
    [System.Runtime.InteropServices.DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hwnd, out RECT rect);
    [System.Runtime.InteropServices.DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
    const uint SWP_NOZORDER = 0x0004, SWP_NOACTIVATE = 0x0010, SWP_FRAMECHANGED = 0x0020;

    /// <summary>Where the window sat before fullscreen, to put it back exactly.</summary>
    RECT windowedRect;

    /// <summary>
    /// Straight to the monitor's edges and straight back, by window rectangle. Going through
    /// WindowState.Normal (needed to make a borderless maximized WPF window cover the taskbar)
    /// shrank the window for a frame each way, and whatever was behind it flashed through.
    /// </summary>
    void ToggleFullscreen()
    {
        var window = Window.GetWindow(this) ?? Application.Current?.MainWindow;
        if (window is null) return;
        SetFullscreen(window, !fullscreen);
        ShowHud(fullscreen ? "Fullscreen" : "Windowed");
        Dispatcher.BeginInvoke(ApplyScale, DispatcherPriority.Background);
        Render();
    }

    /// <summary>Where the window sat before picture-in-picture, and its minimum size (the mini window is smaller).</summary>
    RECT pipRect;
    Size pipMin;

    void TogglePip()
    {
        var window = Window.GetWindow(this) ?? Application.Current?.MainWindow;
        if (window is null) return;
        if (fullscreen) SetFullscreen(window, false);
        SetPip(window, !pip);
        showUi = false;
        Dispatcher.BeginInvoke(ApplyScale, DispatcherPriority.Background);
        Render();
    }

    /// <summary>A small always-on-top window in the corner of the screen, just the picture; drag it anywhere.</summary>
    void SetPip(Window window, bool on)
    {
        var hwnd = new System.Windows.Interop.WindowInteropHelper(window).Handle;
        pip = on;
        if (on)
        {
            GetWindowRect(hwnd, out pipRect);
            pipMin = new Size(window.MinWidth, window.MinHeight);
            var info = new MONITORINFO { Size = System.Runtime.InteropServices.Marshal.SizeOf<MONITORINFO>() };
            GetMonitorInfo(MonitorFromWindow(hwnd, 2 /* nearest */), ref info);
            window.MinWidth = window.MinHeight = 0;
            window.Topmost = true;
            window.WindowStyle = WindowStyle.None;
            window.ResizeMode = ResizeMode.NoResize;
            // A quarter of the screen's width, in the film's shape, 16 px (scaled) off the corner.
            var w = info.Work;
            var ratio = VideoRatio();
            var width = (w.Right - w.Left) / 4;
            var height = (int)(width / ratio);
            var margin = (int)(16 * VisualTreeHelper.GetDpi(window).DpiScaleX);
            SetWindowPos(hwnd, IntPtr.Zero, w.Right - width - margin, w.Bottom - height - margin, width, height, SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
        }
        else
        {
            window.Topmost = false;
            window.WindowStyle = WindowStyle.SingleBorderWindow;
            window.ResizeMode = ResizeMode.CanResize;
            window.MinWidth = pipMin.Width;
            window.MinHeight = pipMin.Height;
            var r = pipRect;
            if (r.Right > r.Left)
                SetWindowPos(hwnd, IntPtr.Zero, r.Left, r.Top, r.Right - r.Left, r.Bottom - r.Top, SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
        }
    }

    double VideoRatio()
    {
        uint w = 0, h = 0;
        try { player.Size(0, ref w, ref h); } catch { }
        return w > 0 && h > 0 ? Math.Clamp((double)w / h, 1, 2.4) : 16.0 / 9;
    }

    void SetFullscreen(Window window, bool on)
    {
        var hwnd = new System.Windows.Interop.WindowInteropHelper(window).Handle;
        if (on && pip) SetPip(window, false);
        fullscreen = on;
        if (on)
        {
            GetWindowRect(hwnd, out windowedRect);
            var info = new MONITORINFO { Size = System.Runtime.InteropServices.Marshal.SizeOf<MONITORINFO>() };
            GetMonitorInfo(MonitorFromWindow(hwnd, 2 /* nearest */), ref info);
            window.Topmost = true;
            window.WindowStyle = WindowStyle.None;
            window.ResizeMode = ResizeMode.NoResize;
            var m = info.Monitor;
            SetWindowPos(hwnd, IntPtr.Zero, m.Left, m.Top, m.Right - m.Left, m.Bottom - m.Top, SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
        }
        else
        {
            window.Topmost = false;
            window.WindowStyle = WindowStyle.SingleBorderWindow;
            window.ResizeMode = ResizeMode.CanResize;
            var r = windowedRect;
            if (r.Right > r.Left)
                SetWindowPos(hwnd, IntPtr.Zero, r.Left, r.Top, r.Right - r.Left, r.Bottom - r.Top, SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
        }
    }

    /// <summary>
    /// What streaming fetches: only the next five minutes past the playhead (the default,
    /// light on data and disk), or the whole file so the rest plays without waiting.
    /// Remembered for next time. "Save" is still what keeps a copy in Downloads.
    /// </summary>
    void ToggleWholeFile()
    {
        if (session is null) return;
        session.WholeFile = !session.WholeFile;
        shell.ChangeSettings(shell.Settings with { StreamWholeFile = session.WholeFile });
        ShowHud(session.WholeFile ? "Downloading the whole file" : "Downloading only the next 5 minutes", 1800);
    }

    void SaveCopy()
    {
        if (request.Torrent is null || request.HistoryUri is null) return;
        var item = shell.HistoryList.FirstOrDefault(h => h.Uri == request.HistoryUri);
        if (item is null) return;
        shell.Toast("Downloading — see the Downloads tab");
        Downloads.Instance.Start(new DownloadJob
        {
            Uri = item.Uri,
            FileIndices = new() { request.FileIndex },
            Title = item.Title,
            TitleName = item.TitleName,
            Poster = item.Poster,
            Imdb = item.Imdb,
            Kind = item.Kind,
        });
        Warn("Downloading — see the Downloads tab");
    }

    async void SearchSubs(string? typed = null)
    {
        var q = (typed ?? subQuery).Trim();
        if (q.Length == 0) q = request.MetaTitle ?? TitleText.SearchableName(request.Title);
        if (q.Length == 0 && request.Imdb is null) return;
        subLoading = true;
        subResults = new();
        Render();
        try
        {
            var at = Search.ParseEpisode(request.Title);
            var useImdb = request.Imdb is not null && (typed is null || typed.Trim().Length == 0 || typed.Trim() == request.MetaTitle);
            // OpenSubtitles needs no key; SubDL joins in when this build has one.
            var results = await Subtitles.Find(q, useImdb ? request.Imdb : null, at?.Season, at?.Episode,
                at is not null || request.Kind == "tv" ? "tv" : request.Kind == "movie" ? "movie" : null,
                request.Title);
            subResults = results.Take(25).ToList();
        }
        catch (Exception e)
        {
            Warn(e.Message.Contains("401") || e.Message.Contains("403") ? "SubDL rejected the key" : e.Message);
        }
        finally
        {
            subLoading = false;
            Render();
        }
    }

    async void ApplySub(Sub sub)
    {
        try
        {
            var path = await SubDL.Save(sub);
            if (!externalSubs.Any(s => s.Path == path)) externalSubs.Add(new SavedSub(path, sub.DisplayName));
            if (request.HistoryUri is not null) shell.SaveSubs(request.HistoryUri, request.FileIndex, externalSubs.ToList());
            AddSlave(path, true);
            searchingSubs = false;
            picking = true;
            Render();
        }
        catch (Exception e)
        {
            Warn(e.Message);
        }
    }

    // ── Render ──────────────────────────────────────────────────────────────

    static Brush White(byte alpha = 255) => new SolidColorBrush(Color.FromArgb(alpha, 255, 255, 255));
    static Brush Black(byte alpha) => new SolidColorBrush(Color.FromArgb(alpha, 0, 0, 0));
    // Frozen: a shared brush then belongs to no thread.
    // The phone's and the TV's red: the played part, what is on, what is selected.
    static readonly Brush Red = Frozen(Color.FromRgb(0xe5, 0x09, 0x14));
    static readonly Brush RedLight = Frozen(Color.FromRgb(0xff, 0x5a, 0x63));
    static readonly Brush Imdb = Frozen(Color.FromRgb(0xf5, 0xc5, 0x18));
    static readonly Brush Green = Frozen(Color.FromRgb(0x46, 0xd3, 0x69));
    static readonly Brush Sheet = Frozen(Color.FromArgb(0xf2, 0x14, 0x14, 0x14));
    static Brush Frozen(Color color) { var b = new SolidColorBrush(color); b.Freeze(); return b; }

    /// <summary>Dark at the top and bottom where the controls are, the picture left clear in the middle.</summary>
    static readonly Brush Scrim = Gradient(new Point(0, 0), new Point(0, 1), (0xbf, 0), (0x26, 0.3), (0x26, 0.6), (0xd9, 1));
    /// <summary>Under the loading screen's and the pause card's text: dark on the left, the picture showing on the right.</summary>
    static readonly Brush Veil = Gradient(new Point(0, 0), new Point(1, 0), (0xeb, 0), (0x99, 0.7), (0x73, 1));
    static readonly Brush PauseVeil = Gradient(new Point(0, 0), new Point(1, 0), (0xe0, 0), (0x8c, 0.6), (0x4d, 1));

    static Brush Gradient(Point from, Point to, params (byte Alpha, double At)[] stops)
    {
        var b = new LinearGradientBrush { StartPoint = from, EndPoint = to };
        foreach (var (alpha, at) in stops) b.GradientStops.Add(new GradientStop(Color.FromArgb(alpha, 0, 0, 0), at));
        b.Freeze();
        return b;
    }

    /// <summary>The title's own name, the release file name only when nothing better is known.</summary>
    string ShownTitle
    {
        get
        {
            if (!string.IsNullOrWhiteSpace(request.MetaTitle)) return request.MetaTitle;
            var name = TitleText.SearchableName(request.Title);
            return name.Length > 0 ? name : request.Title;
        }
    }

    /// <summary>"S1:E4" when the file names an episode.</summary>
    string? EpisodeLine => Search.ParseEpisode(request.Title) is { } at ? $"S{at.Season}:E{at.Episode}" : null;

    static Icon WIcon(string kind, double size, Brush? brush = null) => new() { Kind = kind, Size = size, Foreground = brush ?? Brushes.White };

    static TextBlock WText(string text, double size, Brush? brush = null, FontWeight? weight = null) => new()
    {
        Text = text,
        FontSize = size,
        Foreground = brush ?? Brushes.White,
        FontWeight = weight ?? FontWeights.Normal,
        TextTrimming = TextTrimming.CharacterEllipsis,
    };

    Button Circle(UIElement content, double size, Action onClick, byte alpha = 0x88, string? tooltip = null)
    {
        var b = Controls.Ui.Button(content, () => { onClick(); Bump(); }, "Bare", tooltip);
        b.Width = b.Height = size;
        b.Tag = size / 2;
        b.Background = Black(alpha);
        b.HorizontalContentAlignment = HorizontalAlignment.Center;
        b.VerticalContentAlignment = VerticalAlignment.Center;
        return b;
    }

    void UpdateClock()
    {
        clockText.Text = Format.Clock(current / 1000.0);
        // Time left, as Netflix shows it — the number people actually want.
        lengthText.Text = duration > 0 ? $"-{Format.Clock(Math.Max(0, duration - current) / 1000.0)}" : "--:--";
        seek.Value = duration > 0 ? (double)current / duration : 0;
        seek.Duration = duration;
    }

    /// <summary>The frame under the hovered spot of the seek bar, as YouTube shows one.</summary>
    void PeekAt(double ratio)
    {
        if (duration <= 0) return;
        // A stream has only the downloaded parts to show; elsewhere the time alone.
        var onDisk = session is null || seek.Downloaded.Any(s => ratio >= s.From && ratio <= s.To);
        // Short of the very end: stepping a frame past it would end the preview's playback.
        peekAt = onDisk ? Math.Min((long)(ratio * duration), duration - 3000) : -1;
        if (onDisk) (preview ??= OpenPreview())?.Show(peekAt);
        ShowPeek();
    }

    /// <summary>
    /// Only a picture from near the hovered spot. The last one stays up until the next lands,
    /// and where that never comes (the keyframe before the spot isn't downloaded) it would
    /// show some other moment as this one.
    /// </summary>
    void ShowPeek() =>
        seek.Preview = preview is { Image: { } image } p && peekAt >= 0 && Math.Abs(p.FrameAt - peekAt) <= duration / 50 ? image : null;

    SeekPreview? OpenPreview()
    {
        var path = session is { } s ? s.Manager.Files[s.FileIndex].FullPath : request.LocalPath;
        if (path is null || !File.Exists(path)) return null;
        try
        {
            return new SeekPreview(vlc, path, _ => ShowPeek());
        }
        catch (Exception e)
        {
            Log.Player($"Seek preview: {e.Message}");
            return null;
        }
    }

    static string StageLabel(Progress? p) => p?.State switch
    {
        "Metadata" => "Reading torrent",
        "Hashing" or "Starting" => "Checking files",
        "Downloading" => "Buffering",
        _ => "Finding peers",
    };

    void Render()
    {
        if (disposed) return;
        lastSignature = Signature();
        chrome.Children.Clear();
        foreach (var live in new FrameworkElement[] { liveSwarm, liveSpeed, livePct, liveMeta, liveStage, liveUpNext, liveVolume, liveNow, liveEnds, livePauseEnds, liveDot, ring, volumeFill }) Detach(live);
        if (infoPanel.IsValueCreated) Detach(infoPanel.Value);
        overlay.Cursor = !showUi && started && player.IsPlaying ? Cursors.None : null;
        Dispatcher.BeginInvoke(UpdateLive, DispatcherPriority.Render);

        if (Preparing)
        {
            // One loading screen, the phone's: the title's own backdrop, its name, and a ring
            // that fills with libVLC's buffer once it has the stream.
            overlay.Background = Brushes.Black;
            if (request.Imdb is { Length: > 0 } imdb && ImageUrl.Load($"https://images.metahub.space/background/medium/{imdb}/img", 1280) is { } backdrop)
            {
                chrome.Children.Add(new Grid
                {
                    ClipToBounds = true,
                    // Past the edges: a blur fades out at its own border.
                    Children = { new Border { Margin = new Thickness(-24), Background = new ImageBrush(backdrop) { Stretch = Stretch.UniformToFill }, Effect = new System.Windows.Media.Effects.BlurEffect { Radius = 14 } } },
                });
            }
            chrome.Children.Add(new Border { Background = Veil });

            var body = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            var name = WText(ShownTitle, 30, null, FontWeights.Bold);
            name.TextWrapping = TextWrapping.Wrap;
            name.MaxHeight = 84;
            body.Children.Add(name);
            if (EpisodeLine is { } episode)
            {
                var line = WText(episode, 14, null, FontWeights.SemiBold);
                line.Margin = new Thickness(0, 6, 0, 0);
                body.Children.Add(line);
            }
            var status = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 18, 0, 0) };
            status.Children.Add(Pad(liveDot, 0, 8));
            status.Children.Add(Pad(liveStage, 0, 0));
            if (stats is not null) status.Children.Add(Pad(liveMeta, 10, 0));
            body.Children.Add(status);

            var row = new DockPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(56, 0, 56, 0), MaxWidth = 900, HorizontalAlignment = HorizontalAlignment.Left };
            // Before libVLC has the stream there is no buffer to count: a spinner, not an empty ring.
            UIElement gauge = started ? Pad(ring, 32, 0) : Pad((FrameworkElement)Spin(), 32, 0);
            DockPanel.SetDock(gauge, Dock.Right);
            row.Children.Add(gauge);
            row.Children.Add(body);
            chrome.Children.Add(row);
            var back = Circle(WIcon("arrow-left", 20), 40, RequestExit, 0x88, "Back");
            back.HorizontalAlignment = HorizontalAlignment.Left;
            back.VerticalAlignment = VerticalAlignment.Top;
            back.Margin = new Thickness(16);
            chrome.Children.Add(back);
            RenderFailure();
            return;
        }

        overlay.Background = new SolidColorBrush(Color.FromArgb(1, 0, 0, 0));
        var waiting = buffering || stalled;

        if (pip)
        {
            // Only what a mini window needs, on hover: close, back to full, play/pause.
            if (waiting && !showUi)
            {
                chrome.Children.Add(new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, IsHitTestVisible = false, Children = { Spin() } });
            }
            if (!showUi) return;
            chrome.Children.Add(new Border { Background = Black(0x60), Tag = "dim" });
            var close = Circle(WIcon("x", 16), 32, RequestExit, 0x88, "Close");
            close.HorizontalAlignment = HorizontalAlignment.Right;
            close.VerticalAlignment = VerticalAlignment.Top;
            close.Margin = new Thickness(8);
            chrome.Children.Add(close);
            var expand = Circle(WIcon("maximize", 16), 32, TogglePip, 0x88, "Back to the full player (P)");
            expand.HorizontalAlignment = HorizontalAlignment.Left;
            expand.VerticalAlignment = VerticalAlignment.Top;
            expand.Margin = new Thickness(8);
            chrome.Children.Add(expand);
            var toggle = Circle(waiting ? Spin() : WIcon(player.IsPlaying ? "pause" : "play", 22), 48, TogglePlay, 0xaa, "Play / pause (Space)");
            toggle.HorizontalAlignment = HorizontalAlignment.Center;
            toggle.VerticalAlignment = VerticalAlignment.Center;
            chrome.Children.Add(toggle);
            return;
        }

        if (showUi && locked)
        {
            var unlock = Circle(WIcon("lock", 20), 46, () => Lock(false), 0xaa, "Unlock controls");
            unlock.HorizontalAlignment = HorizontalAlignment.Left;
            unlock.VerticalAlignment = VerticalAlignment.Center;
            unlock.Margin = new Thickness(20, 0, 0, 0);
            chrome.Children.Add(unlock);
        }

        if (showUi && !locked)
        {
            // Dark at the top and bottom where the controls are, not a flat wash over the picture.
            chrome.Children.Add(new Border { Background = Scrim, Tag = "dim" });

            // Top: back, the title big with the episode under it; on the right the clock, when
            // the film ends, and how fast it is arriving. The swarm went to More.
            var top = new DockPanel { VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(16) };
            var back = Circle(WIcon("arrow-left", 20), 40, RequestExit, 0x55, "Back (Esc)");
            DockPanel.SetDock(back, Dock.Left);
            top.Children.Add(back);
            var right = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            liveNow.HorizontalAlignment = HorizontalAlignment.Right;
            liveEnds.HorizontalAlignment = HorizontalAlignment.Right;
            right.Children.Add(new StackPanel { VerticalAlignment = VerticalAlignment.Center, Children = { liveNow, liveEnds } });
            if (session is not null && stats is not null)
            {
                // Fixed width: it changes every second and must not shove the clock around.
                liveSpeed.MinWidth = 58;
                right.Children.Add(new Border
                {
                    CornerRadius = new CornerRadius(14),
                    Background = Black(0x77),
                    Height = 28,
                    Padding = new Thickness(10, 0, 10, 0),
                    Margin = new Thickness(14, 0, 0, 0),
                    Child = new StackPanel { Orientation = Orientation.Horizontal, Children = { WIcon("download", 12, White(0xb0)), Pad(liveSpeed, 5, 0) } },
                });
            }
            DockPanel.SetDock(right, Dock.Right);
            top.Children.Add(right);
            var heading = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 12, 0) };
            heading.Children.Add(WText(ShownTitle, 18, null, FontWeights.Bold));
            if (EpisodeLine is { } episode) heading.Children.Add(WText(episode, 12, White(0xb0)));
            top.Children.Add(heading);
            chrome.Children.Add(top);

            // Centre: transport.
            var center = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            center.Children.Add(Circle(SkipGlyph("rotate-ccw"), 56, () => Skip(-SkipSeconds), 0x88, "Back 10 seconds (←)"));
            UIElement playGlyph = waiting ? Spin() : WIcon(player.IsPlaying ? "pause" : "play", 28);
            var play = Circle(playGlyph, 68, TogglePlay, 0xaa, "Play / pause (Space)");
            play.Margin = new Thickness(28, 0, 28, 0);
            center.Children.Add(play);
            center.Children.Add(Circle(SkipGlyph("rotate-cw"), 56, () => Skip(SkipSeconds), 0x88, "Forward 10 seconds (→)"));
            chrome.Children.Add(center);

            // Left edge: volume, the phone's brightness pill — a step at a time, or click the meter.
            var meter = new Border
            {
                Width = 6,
                Height = VolumeTrack,
                CornerRadius = new CornerRadius(3),
                Background = White(0x33),
                Margin = new Thickness(0, 4, 0, 4),
                HorizontalAlignment = HorizontalAlignment.Center,
                Cursor = Cursors.Hand,
                ToolTip = "Volume",
                Child = volumeFill,
            };
            meter.MouseLeftButtonDown += (_, e) =>
            {
                SetVolume((int)Math.Round((1 - e.GetPosition(meter).Y / VolumeTrack) * 150));
                Bump();
                e.Handled = true;
            };
            var speaker = WIcon(userMuted ? "volume-x" : "volume", 14);
            speaker.HorizontalAlignment = HorizontalAlignment.Center;
            var rail = new Border
            {
                Width = 48,
                CornerRadius = new CornerRadius(24),
                Background = new SolidColorBrush(Color.FromArgb(0xb3, 0x0b, 0x0e, 0x14)),
                BorderBrush = White(0x2e),
                BorderThickness = new Thickness(1),
                Padding = new Thickness(0, 4, 0, 4),
                HorizontalAlignment = HorizontalAlignment.Left,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(20, 0, 0, 0),
                Child = new StackPanel
                {
                    Children =
                    {
                        RailButton("plus", () => StepVolume(5), "Louder (↑)"),
                        meter,
                        new StackPanel { Margin = new Thickness(0, 8, 0, 2), Children = { speaker, Centered(liveVolume) } },
                        RailButton("minus", () => StepVolume(-5), "Quieter (↓)"),
                    },
                },
            };
            chrome.Children.Add(rail);

            // Bottom: the time so far, the red bar and the time left; under it the short Netflix row.
            var bottom = new StackPanel { VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(16, 0, 16, 10) };
            var seekRow = new DockPanel();
            DockPanel.SetDock(clockText, Dock.Left);
            DockPanel.SetDock(lengthText, Dock.Right);
            Detach(clockText);
            Detach(lengthText);
            Detach(seek);
            seekRow.Children.Add(clockText);
            seekRow.Children.Add(lengthText);
            seek.Margin = new Thickness(10, 0, 10, 0);
            seekRow.Children.Add(seek);
            bottom.Children.Add(seekRow);
            UpdateClock();

            // What you reach for while watching, and nothing else; the rest waits in More.
            var actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 4, 0, 0) };
            actions.Children.Add(Action("fast-forward", $"Speed ({rate}x)", rate != 1, () =>
            {
                rate = Rates[(Array.IndexOf(Rates, rate) + 1) % Rates.Length];
                player.SetRate((float)rate);
                shell.ChangeSettings(shell.Settings with { PlaybackRate = rate });
                Render();
            }));
            actions.Children.Add(Action("lock", "Lock", false, () => Lock(true)));
            actions.Children.Add(Action("subtitles", "Audio & Subtitles", player.Spu >= 0 || picking, () =>
            {
                var open = !picking;
                CloseSheets();
                picking = open;
                Render();
            }));
            if (request.Next is not null) actions.Children.Add(Action("skip-forward", "Next Episode", false, PlayNext));
            actions.Children.Add(Action("more-horizontal", "More", more, () =>
            {
                var open = !more;
                CloseSheets();
                more = open;
                Render();
            }));
            actions.Children.Add(Action(fullscreen ? "minimize" : "maximize", fullscreen ? "Exit full screen" : "Full screen", false, ToggleFullscreen));
            bottom.Children.Add(actions);
            chrome.Children.Add(bottom);
        }

        // Stays up with the controls hidden, like VLC's statistics window; clicks go through it.
        if (showInfo) chrome.Children.Add(infoPanel.Value);

        if (picking) chrome.Children.Add(TrackSheet());
        if (more) chrome.Children.Add(MoreSheet());
        if (searchingSubs) chrome.Children.Add(SearchSheet());
        if (PauseCard)
        {
            pauseCardAt ??= DateTime.UtcNow;
            chrome.Children.Add(PauseCardView());
        }
        else pauseCardAt = null;

        if (waiting && !showUi)
        {
            var box = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, IsHitTestVisible = false };
            box.Children.Add(Spin());
            var label = WText("Buffering", 12, White(0xd0));
            label.HorizontalAlignment = HorizontalAlignment.Center;
            label.Margin = new Thickness(0, 8, 0, 0);
            box.Children.Add(label);
            if (stats is not null)
            {
                liveMeta.HorizontalAlignment = HorizontalAlignment.Center;
                liveMeta.Margin = new Thickness(0);
                box.Children.Add(liveMeta);
            }
            chrome.Children.Add(box);
        }

        if (hud is not null)
        {
            chrome.Children.Add(new Border
            {
                CornerRadius = new CornerRadius(18),
                Background = Black(0xcc),
                Padding = new Thickness(16, 9, 16, 9),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(0, 76, 0, 0),
                IsHitTestVisible = false,
                Child = WText(hud, 14, null, FontWeights.Medium),
            });
        }

        if (SkipIntroVisible) chrome.Children.Add(SkipIntroButton());
        if (UpNextVisible) chrome.Children.Add(UpNextCard());
        RenderFailure();
    }

    void RenderFailure()
    {
        if (failure is null) return;
        var banner = new Border
        {
            CornerRadius = new CornerRadius(17),
            Background = failureFatal ? new SolidColorBrush(Color.FromRgb(0xd0, 0x48, 0x3c)) : Black(0xcc),
            Padding = new Thickness(16, 8, 16, 8),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(0, 24, 0, 0),
            Child = WText(failure, 12),
        };
        chrome.Children.Add(banner);
    }

    static FrameworkElement Pad(FrameworkElement e, double left, double right)
    {
        Detach(e);
        e.Margin = new Thickness(left, 0, right, 0);
        e.VerticalAlignment = VerticalAlignment.Center;
        return e;
    }

    static FrameworkElement Centered(FrameworkElement e)
    {
        Detach(e);
        e.HorizontalAlignment = HorizontalAlignment.Center;
        return e;
    }

    static void Detach(FrameworkElement? e)
    {
        if (e is null) return;
        if (e.Parent is Panel p) p.Children.Remove(e);
        else if (e.Parent is Decorator d) d.Child = null;
        else if (e.Parent is ContentControl cc) cc.Content = null;
    }

    static UIElement Spin()
    {
        var s = Controls.Ui.Spinner(24);
        ((Control)s).Foreground = Brushes.White;
        return s;
    }

    static UIElement SkipGlyph(string kind) => new Grid
    {
        Children =
        {
            WIcon(kind, 24),
            new TextBlock { Text = SkipSeconds.ToString(), Foreground = Brushes.White, FontSize = 8, FontWeight = FontWeights.SemiBold, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 1, 0, 0) },
        },
    };

    Button RailButton(string icon, Action onClick, string tooltip)
    {
        var b = Controls.Ui.Button(WIcon(icon, 18), () => { onClick(); Bump(); }, "Bare", tooltip);
        b.Width = 44;
        b.Height = 44;
        b.HorizontalContentAlignment = HorizontalAlignment.Center;
        b.VerticalContentAlignment = VerticalAlignment.Center;
        return b;
    }

    /// <summary>One button of the bottom row: icon beside its label, as Netflix lays it out.</summary>
    Button Action(string icon, string label, bool on, Action onClick)
    {
        var tint = on ? RedLight : Brushes.White;
        var glyph = WIcon(icon, 18, tint);
        glyph.VerticalAlignment = VerticalAlignment.Center;
        var labelText = WText(label, 13, tint, FontWeights.Medium);
        labelText.VerticalAlignment = VerticalAlignment.Center;
        labelText.Margin = new Thickness(8, 0, 0, 0);
        var b = Controls.Ui.Button(new StackPanel { Orientation = Orientation.Horizontal, Children = { glyph, labelText } }, () => { onClick(); Bump(); }, "Bare", label);
        b.Padding = new Thickness(10, 8, 10, 8);
        b.Margin = new Thickness(4, 0, 4, 0);
        b.Tag = 8;
        return b;
    }

    /// <summary>One line of the More sheet: glyph, name, and the current setting on the right.</summary>
    Button MoreRow(string icon, string label, string? value, bool on, Action onClick)
    {
        var dock = new DockPanel();
        var glyph = WIcon(icon, 18, on ? RedLight : Brushes.White);
        glyph.Margin = new Thickness(0, 0, 14, 0);
        glyph.VerticalAlignment = VerticalAlignment.Center;
        DockPanel.SetDock(glyph, Dock.Left);
        dock.Children.Add(glyph);
        if (value is not null)
        {
            var shown = WText(value, 12, on ? RedLight : White(0x90));
            shown.VerticalAlignment = VerticalAlignment.Center;
            shown.Margin = new Thickness(12, 0, 0, 0);
            DockPanel.SetDock(shown, Dock.Right);
            dock.Children.Add(shown);
        }
        var name = WText(label, 13);
        name.VerticalAlignment = VerticalAlignment.Center;
        dock.Children.Add(name);
        var b = Controls.Ui.Button(dock, () => { onClick(); Bump(); }, "Bare", label);
        b.Padding = new Thickness(16, 11, 16, 11);
        b.HorizontalContentAlignment = HorizontalAlignment.Stretch;
        b.Tag = 8;
        return b;
    }

    /// <summary>Above the bottom row, at the right — where the phone's sheets and Netflix's panels sit.</summary>
    static Border SheetBox(UIElement content) => new()
    {
        CornerRadius = new CornerRadius(14),
        Background = Sheet,
        Padding = new Thickness(6),
        HorizontalAlignment = HorizontalAlignment.Right,
        VerticalAlignment = VerticalAlignment.Bottom,
        Margin = new Thickness(0, 68, 16, 96),
        Child = content,
    };

    FrameworkElement MoreSheet()
    {
        var list = new StackPanel { Width = 300 };
        // The swarm, which used to crowd the top bar.
        if (session is not null && stats is not null)
        {
            liveMeta.HorizontalAlignment = HorizontalAlignment.Left;
            liveMeta.Margin = new Thickness(16, 8, 16, 6);
            list.Children.Add(liveMeta);
        }
        list.Children.Add(MoreRow("maximize", "Picture", Scales[scaleIdx].Label, scaleIdx != 0, CycleScale));
        list.Children.Add(MoreRow("search", "Find subtitles", null, false, () =>
        {
            CloseSheets();
            searchingSubs = true;
            subQuery = request.MetaTitle ?? TitleText.SearchableName(request.Title);
            subResults = new();
            SearchSubs(subQuery);
            Render();
        }));
        if (session is not null && !session.Borrowed)
            list.Children.Add(MoreRow(session.WholeFile ? "hard-drive" : "clock", "Download", session.WholeFile ? "Whole file" : "Next 5 min", session.WholeFile, ToggleWholeFile));
        if (session is not null) list.Children.Add(MoreRow("download", "Save a copy", null, false, SaveCopy));
        list.Children.Add(MoreRow("pip", "Mini player", null, false, () => { more = false; TogglePip(); }));
        list.Children.Add(MoreRow("info", "Stats for nerds", showInfo ? "On" : "Off", showInfo, ToggleInfo));
        list.Children.Add(MoreRow("type", "Keyboard shortcuts", "H", false, ShowShortcutsHelp));
        return SheetBox(new ScrollViewer { MaxHeight = Math.Max(160, ActualHeight / ControlsScale - 200), VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = list });
    }

    /// <summary>
    /// Paused with the controls away: what you are watching, where you are in it and how much
    /// is left — the reason to glance at a paused screen. Clicks go through to the picture.
    /// </summary>
    FrameworkElement PauseCardView()
    {
        var body = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(64, 0, 64, 0), MaxWidth = 640 };
        body.Children.Add(WText("You're watching", 14, White(0xa0)));
        if (logo is not null)
        {
            // The title's own logo, as the TV draws it; its name only when there is none.
            body.Children.Add(new Image { Source = logo, MaxHeight = 90, MaxWidth = 460, Stretch = Stretch.Uniform, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 10, 0, 0) });
        }
        else
        {
            var name = WText(ShownTitle, 34, null, FontWeights.Bold);
            name.TextWrapping = TextWrapping.Wrap;
            name.Margin = new Thickness(0, 6, 0, 0);
            body.Children.Add(name);
        }
        var at = Search.ParseEpisode(request.Title);
        var ep = at is { } a ? titleMeta?.Episodes.FirstOrDefault(e => e.Season == a.Season && e.Number == a.Episode) : null;
        if (EpisodeLine is { } episode)
        {
            var line = WText(ep is { Name.Length: > 0 } ? $"{episode}  “{ep.Name}”" : episode, 15, null, FontWeights.SemiBold);
            line.Margin = new Thickness(0, 6, 0, 0);
            body.Children.Add(line);
        }
        if (titleMeta is { } t)
        {
            // The TV's facts row: the IMDb badge, then year • runtime • genres.
            var facts = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 10, 0, 0) };
            if (t.Rating is { Length: > 0 } rating)
            {
                facts.Children.Add(new Border
                {
                    CornerRadius = new CornerRadius(3),
                    Background = Imdb,
                    Padding = new Thickness(5, 0, 5, 1),
                    VerticalAlignment = VerticalAlignment.Center,
                    Child = WText("IMDb", 11, Brushes.Black, FontWeights.Black),
                });
                facts.Children.Add(Pad(WText(rating, 13, null, FontWeights.SemiBold), 6, 14));
            }
            var rest = new[] { t.Year is { Length: > 9 } y ? y[..9] : t.Year, t.Runtime }.Concat(t.Genres.Take(3)).Where(s => !string.IsNullOrEmpty(s));
            facts.Children.Add(WText(string.Join("  •  ", rest), 13, White(0xd8)));
            body.Children.Add(facts);
        }
        if ((ep?.Overview ?? titleMeta?.Plot) is { Length: > 0 } plot)
        {
            var text = WText(plot, 14, White(0xcc));
            text.TextWrapping = TextWrapping.Wrap;
            text.MaxWidth = 560;
            text.MaxHeight = 14 * 1.4 * 4;
            text.HorizontalAlignment = HorizontalAlignment.Left;
            text.Margin = new Thickness(0, 10, 0, 0);
            body.Children.Add(text);
        }
        if (duration > 0)
        {
            const double width = 360;
            var ratio = Math.Clamp((double)current / duration, 0, 1);
            body.Children.Add(new Grid
            {
                Width = width,
                Height = 4,
                HorizontalAlignment = HorizontalAlignment.Left,
                Margin = new Thickness(0, 22, 0, 0),
                Children =
                {
                    new Border { CornerRadius = new CornerRadius(2), Background = White(0x40) },
                    new Border { CornerRadius = new CornerRadius(2), Background = Red, Width = width * ratio, HorizontalAlignment = HorizontalAlignment.Left },
                },
            });
            var times = new DockPanel { Width = width, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 8, 0, 0) };
            var left = WText(TimeLeft(duration - current), 13, White(0xa0));
            DockPanel.SetDock(left, Dock.Right);
            times.Children.Add(left);
            times.Children.Add(WText($"{Format.Clock(current / 1000.0)} of {Format.Clock(duration / 1000.0)}", 13, null, FontWeights.SemiBold));
            body.Children.Add(times);
            livePauseEnds.HorizontalAlignment = HorizontalAlignment.Left;
            livePauseEnds.Margin = new Thickness(0, 2, 0, 0);
            body.Children.Add(livePauseEnds);
        }
        var paused = WText("PAUSED", 12, White(0x90), FontWeights.SemiBold);
        paused.Margin = new Thickness(0, 22, 0, 0);
        body.Children.Add(paused);
        var card = new Grid { Background = PauseVeil, IsHitTestVisible = false, Children = { body } };
        // Fades up over 700ms, as the TV's does; Render() rebuilds the card, so pick up where the fade is.
        var shown = (DateTime.UtcNow - (pauseCardAt ?? DateTime.UtcNow)).TotalMilliseconds;
        if (shown < 700) card.BeginAnimation(OpacityProperty, new DoubleAnimation(shown / 700, 1, TimeSpan.FromMilliseconds(700 - shown)));
        return card;
    }

    /// <summary>Coarse "how much is left" — "1h 07m left", "23m left".</summary>
    static string TimeLeft(long ms)
    {
        var m = Math.Max(0, (int)Math.Round(ms / 60000.0));
        return m >= 60 ? $"{m / 60}h {m % 60:00}m left" : $"{m}m left";
    }

    FrameworkElement Option(string label, bool active, Action onClick, string? note = null)
    {
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Children = { WText(label, 12) } };
        if (note is not null) text.Children.Add(WText(note, 10, White(0x90)));
        var dock = new DockPanel();
        if (active)
        {
            var check = WIcon("check", 16);
            DockPanel.SetDock(check, Dock.Right);
            dock.Children.Add(check);
        }
        dock.Children.Add(text);
        var b = Controls.Ui.Button(dock, () => { onClick(); Bump(); }, "Bare", label);
        b.Padding = new Thickness(10, 9, 10, 9);
        b.Tag = 8;
        b.Background = active ? Red : Brushes.Transparent;
        return b;
    }

    /// <summary>Audio on the left, subtitles on the right, in one panel — Netflix's Audio &amp; Subtitles.</summary>
    FrameworkElement TrackSheet() => SheetBox(new StackPanel
    {
        Orientation = Orientation.Horizontal,
        Children =
        {
            new ScrollViewer { MaxHeight = Math.Max(160, ActualHeight / ControlsScale - 200), VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = AudioList() },
            new Border { Width = 1, Background = White(0x1a), Margin = new Thickness(6, 8, 6, 8) },
            new ScrollViewer { MaxHeight = Math.Max(160, ActualHeight / ControlsScale - 200), VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = SubtitleList() },
        },
    });

    StackPanel AudioList()
    {
        var panel = new StackPanel { Width = 240 };
        panel.Children.Add(SheetTitle("AUDIO"));
        var audio = player.AudioTrackDescription.Where(t => t.Id >= 0).ToList();
        if (audio.Count == 0)
        {
            var none = WText("No audio tracks detected", 12, White(0x70));
            none.Margin = new Thickness(10, 8, 10, 8);
            panel.Children.Add(none);
        }
        else
        {
            foreach (var t in audio)
            {
                var id = t.Id;
                var label = string.IsNullOrWhiteSpace(t.Name) ? $"Audio Track {id}" : t.Name;
                panel.Children.Add(Option(label, player.AudioTrack == id, () =>
                {
                    // Stays open: the subtitles beside it are often the next thing changed.
                    player.SetAudioTrack(id);
                    ShowHud($"Audio: {label}");
                    Render();
                }));
            }
        }
        return panel;
    }

    StackPanel SubtitleList()
    {
        var subs = new StackPanel { Width = 280 };
        subs.Children.Add(SheetTitle("SUBTITLES"));
        subs.Children.Add(Option("Off", player.Spu < 0, () => { player.SetSpu(-1); picking = false; ShowHud("Subtitles: Off"); Render(); }));
        var tracks = player.SpuDescription.Where(t => t.Id >= 0).ToList();
        foreach (var t in tracks)
        {
            var id = t.Id;
            var label = FriendlySub(t.Name, id);
            subs.Children.Add(Option(label, player.Spu == id, () => { player.SetSpu(id); picking = false; ShowHud($"Subtitles: {label}"); Render(); }));
        }
        if (tracks.Count == 0)
        {
            var none = WText("No subtitles found. Use More → Find subtitles to search online.", 12, White(0x70));
            none.TextWrapping = TextWrapping.Wrap;
            none.Margin = new Thickness(10, 8, 10, 8);
            subs.Children.Add(none);
        }
        return subs;
    }

    string FriendlySub(string? raw, int id)
    {
        if (slaveTrackLabels.TryGetValue(id, out var custom) && !string.IsNullOrWhiteSpace(custom))
            return custom;
        var name = (raw ?? "").Trim();
        var external = externalSubs.FirstOrDefault(s => name.Contains(Path.GetFileNameWithoutExtension(s.Path), StringComparison.OrdinalIgnoreCase));
        if (external is not null) return external.Label;
        return name.Length == 0 || (name.StartsWith("Track", StringComparison.OrdinalIgnoreCase) && name.Length < 9) ? $"Subtitle {id}" : name;
    }

    static TextBlock SheetTitle(string text) => new()
    {
        Text = text,
        FontSize = 11,
        FontWeight = FontWeights.Medium,
        Foreground = White(0x70),
        Margin = new Thickness(10, 6, 10, 4),
    };

    TextBox? subBox;

    FrameworkElement SearchSheet()
    {
        var panel = new StackPanel { Width = 420 };
        panel.Children.Add(SheetTitle("FIND SUBTITLES"));

        Detach(subBox);
        subBox ??= new TextBox
        {
            Style = (Style)FindResource("Field"),
            Foreground = Brushes.White,
            CaretBrush = Brushes.White,
            Tag = "Search by title…",
            FontSize = 13,
        };
        subBox.Text = subQuery;
        subBox.KeyDown -= OnSubKey;
        subBox.KeyDown += OnSubKey;
        var field = new Border { Background = White(0x14), CornerRadius = new CornerRadius(8), Padding = new Thickness(10, 0, 10, 0), Height = 36, Child = subBox };
        var go = Controls.Ui.Button(subLoading ? Spin() : WIcon("search", 16), () => { subQuery = subBox.Text; SearchSubs(subBox.Text); }, "Bare", "Search");
        go.Width = go.Height = 36;
        go.Tag = 8;
        go.Background = Red;
        go.HorizontalContentAlignment = HorizontalAlignment.Center;
        go.VerticalContentAlignment = VerticalAlignment.Center;
        go.Margin = new Thickness(8, 0, 0, 0);
        var row = new DockPanel { Margin = new Thickness(4, 4, 4, 6) };
        DockPanel.SetDock(go, Dock.Right);
        row.Children.Add(go);
        row.Children.Add(field);
        panel.Children.Add(row);

        if (subResults.Count > 0)
        {
            var list = new StackPanel();
            foreach (var r in subResults)
            {
                var captured = r;
                var meta = string.Join(" · ", new[] { r.Language.Length > 0 ? r.Language : "EN", "SRT", r.Season is null ? null : $"S{r.Season}", r.Episode is null ? null : $"E{r.Episode}" }.Where(s => s is not null));
                var dock = new DockPanel();
                var dl = WIcon("download", 14, White(0x80));
                DockPanel.SetDock(dl, Dock.Right);
                dock.Children.Add(dl);
                dock.Children.Add(new StackPanel { Children = { WText(r.DisplayName, 12), WText(meta, 10, White(0x60)) } });
                var b = Controls.Ui.Button(dock, () => ApplySub(captured), "Bare", r.DisplayName);
                b.Padding = new Thickness(10, 9, 10, 9);
                b.Tag = 8;
                list.Children.Add(b);
            }
            panel.Children.Add(new ScrollViewer { MaxHeight = Math.Max(120, ActualHeight - 260), VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = list });
        }
        else
        {
            UIElement empty = subLoading ? Centered((FrameworkElement)Spin()) : Centered(WText(subQuery.Length > 0 || request.Imdb is not null ? "No subtitles found" : "Enter a search term and press Search", 12, White(0x60)));
            ((FrameworkElement)empty).Margin = new Thickness(0, 20, 0, 20);
            panel.Children.Add(empty);
        }

        return new Border
        {
            CornerRadius = new CornerRadius(12),
            Background = Black(0xdd),
            Padding = new Thickness(6),
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(0, 68, 16, 0),
            Child = panel,
        };
    }

    void OnSubKey(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter || subBox is null) return;
        subQuery = subBox.Text;
        SearchSubs(subBox.Text);
        e.Handled = true;
    }

    /// <summary>Skip intro, bottom-right, where Up next sits later.</summary>
    FrameworkElement SkipIntroButton()
    {
        var ink = new SolidColorBrush(Color.FromRgb(0x0b, 0x0e, 0x14));
        var skip = Controls.Ui.Button(new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Children = { WIcon("chevrons-right", 16, ink), Pad(WText("Skip intro", 14, ink, FontWeights.Bold), 8, 0) },
        }, SkipIntro, "Bare");
        skip.Background = Brushes.White;
        skip.Tag = 8;
        skip.Padding = new Thickness(18, 10, 18, 10);
        skip.HorizontalAlignment = HorizontalAlignment.Right;
        skip.VerticalAlignment = VerticalAlignment.Bottom;
        skip.Margin = new Thickness(0, 0, 16, showUi ? 110 : 24);
        return skip;
    }

    void SkipIntro()
    {
        if (intro is not { } i) return;
        SeekTo((long)(i.End * 1000));
        Render();
    }

    FrameworkElement UpNextCard()
    {
        var next = request.Next!.Value;
        var cancel = Controls.Ui.Button(WText("Cancel", 12, White(0xa0), FontWeights.Medium), () => { dismissedNext = true; Render(); }, "Bare");
        cancel.Padding = new Thickness(8);
        var play = Controls.Ui.Button(new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Children = { WIcon("play", 14, new SolidColorBrush(Color.FromRgb(0x0b, 0x0e, 0x14))), Pad(WText("Play", 13, new SolidColorBrush(Color.FromRgb(0x0b, 0x0e, 0x14)), FontWeights.SemiBold), 6, 0) },
        }, PlayNext, "Bare");
        play.Background = Brushes.White;
        play.Tag = 999;
        play.Padding = new Thickness(14, 8, 14, 8);
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center, MaxWidth = 280, Children = { liveUpNext, WText(next.Name, 13, null, FontWeights.Medium) } };
        return new Border
        {
            CornerRadius = new CornerRadius(12),
            Background = new SolidColorBrush(Color.FromArgb(0xe6, 0x0b, 0x0e, 0x14)),
            Padding = new Thickness(14, 8, 8, 8),
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Bottom,
            Margin = new Thickness(0, 0, 16, showUi ? 110 : 24),
            Child = new StackPanel { Orientation = Orientation.Horizontal, Children = { text, Pad(cancel, 12, 4), play } },
        };
    }

    // ── Leaving ─────────────────────────────────────────────────────────────

    /// <summary>
    /// Back to the page underneath at once. Stopping libVLC and letting go of the
    /// torrent (tracker goodbyes, flushing the disk cache, deleting the stream
    /// files) takes seconds; that used to happen before the screen came back.
    /// </summary>
    public void RequestExit()
    {
        if (disposed) return;
        _ = Close();
        Exited?.Invoke();
    }

    Task? closing;

    /// <summary>Finishes when libVLC and the stream are fully shut down — the host keeps the video window until then.</summary>
    public Task Closed => closing ?? Task.CompletedTask;

    /// <summary>Checkpoint, stop the picture and the stream, give the window back.</summary>
    Task Close() => closing ??= CloseCore();

    async Task CloseCore()
    {
        overlay.Background = Brushes.Black;
        Background = Brushes.Black;
        SavePosition();
        if (fullscreen) ToggleFullscreen();
        Dispose();
        var p = player;
        var m = media;
        var s = session;
        // All of it off the UI thread, stream last: libVLC reads from it until it stops.
        var stopped = Task.Run(() =>
        {
            try { p.Stop(); } catch { }
            try { m?.Dispose(); p.Dispose(); } catch { }
        });
        await stopped;
        // libVLC has let go of the video window: close it and the controls window for good.
        video.Dispose();
        if (s is not null) await Task.Run(async () => await s.DisposeAsync());
    }

    public Task Shutdown() => Close();

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        try { System.Windows.Interop.ComponentDispatcher.ThreadFilterMessage -= OnThreadMessage; } catch { }
        try
        {
            var window = Window.GetWindow(this) ?? Application.Current?.MainWindow;
            if (window != null && fullscreen) SetFullscreen(window, false);
            if (window != null && pip) SetPip(window, false);
        }
        catch { }
        overlay.MouseMove -= OnOverlayMouseMove;
        overlay.MouseLeave -= OnOverlayMouseLeave;
        overlay.MouseLeftButtonDown -= OnPictureDown;
        overlay.MouseWheel -= OnOverlayMouseWheel;
        overlay.Background = Brushes.Black;
        Background = Brushes.Black;
        life.Cancel();
        tick.Stop();
        hideTimer.Stop();
        clickTimer.Stop();
        preview?.Dispose();
        // The controls live in LibVLCSharp's own see-through window over the video, which
        // only hides when the VideoView unloads — and that waits for libVLC and the torrent
        // to finish closing, seconds after Back or closing the app. Hide it now.
        if (Window.GetWindow(overlay) is { } controls && controls != Window.GetWindow(this)) controls.Hide();
        video.MediaPlayer = null;
    }
}

/// <summary>The thin scrubber: click anywhere on the track, or drag the head.</summary>
/// <summary>
/// Two lines over the last two minutes, one sample a second, in Mb/s: download speed (red, filled)
/// and the video's own bitrate (white). Red above white = the buffer is growing.
/// </summary>
public sealed class SpeedGraph : FrameworkElement
{
    const int Samples = 120;
    readonly List<(double Down, double Rate)> points = new();
    static readonly Brush Red = Frozen(new SolidColorBrush(Color.FromRgb(0xe5, 0x09, 0x14)));
    static readonly Brush Fill = Frozen(new SolidColorBrush(Color.FromArgb(0x40, 0xe5, 0x09, 0x14)));
    static readonly Pen RedPen = Frozen(new Pen(Red, 1.5));
    static readonly Pen WhitePen = Frozen(new Pen(new SolidColorBrush(Color.FromArgb(0xd0, 255, 255, 255)), 1.2));
    static readonly Pen GridPen = Frozen(new Pen(new SolidColorBrush(Color.FromArgb(0x26, 255, 255, 255)), 1));
    static readonly Typeface Face = new("Segoe UI");

    static T Frozen<T>(T f) where T : Freezable { f.Freeze(); return f; }

    public SpeedGraph()
    {
        Height = 92;
        IsHitTestVisible = false;
    }

    public void Push(double downMbps, double rateMbps)
    {
        points.Add((Math.Max(0, downMbps), Math.Max(0, rateMbps)));
        if (points.Count > Samples) points.RemoveAt(0);
        InvalidateVisual();
    }

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight - 22; // bottom strip for the legend
        if (w <= 0 || h <= 0) return;
        var dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;
        var top = Math.Max(1, points.Count == 0 ? 1 : points.Max(p => Math.Max(p.Down, p.Rate)) * 1.15);

        dc.DrawLine(GridPen, new Point(0, 0.5), new Point(w, 0.5));
        dc.DrawLine(GridPen, new Point(0, h / 2), new Point(w, h / 2));
        dc.DrawLine(GridPen, new Point(0, h - 0.5), new Point(w, h - 0.5));
        dc.DrawText(new FormattedText($"{top:0.#} Mb/s", System.Globalization.CultureInfo.CurrentCulture, FlowDirection.LeftToRight, Face, 9, Brushes.Gray, dpi), new Point(2, 2));

        if (points.Count >= 2)
        {
            double X(int i) => w - (points.Count - 1 - i) * (w / (Samples - 1));
            double Y(double v) => h - v / top * h;
            Geometry Line(Func<(double Down, double Rate), double> pick, bool closed)
            {
                var g = new StreamGeometry();
                using (var c = g.Open())
                {
                    c.BeginFigure(new Point(X(0), closed ? h : Y(pick(points[0]))), closed, closed);
                    if (closed) c.LineTo(new Point(X(0), Y(pick(points[0]))), true, true);
                    for (var i = 1; i < points.Count; i++) c.LineTo(new Point(X(i), Y(pick(points[i]))), true, true);
                    if (closed) c.LineTo(new Point(X(points.Count - 1), h), true, true);
                }
                g.Freeze();
                return g;
            }
            dc.DrawGeometry(Fill, null, Line(p => p.Down, true));
            dc.DrawGeometry(null, RedPen, Line(p => p.Down, false));
            dc.DrawGeometry(null, WhitePen, Line(p => p.Rate, false));
        }

        // The legend carries the latest value of each line: the white one sits near the bottom
        // whenever the download is much faster, and is hard to read off the scale there.
        var (down, rate) = points.Count > 0 ? points[^1] : (0, 0);
        var legend = h + 6;
        FormattedText Label(string text) => new(text, System.Globalization.CultureInfo.CurrentCulture, FlowDirection.LeftToRight, Face, 10.5, Brushes.White, dpi);
        dc.DrawRectangle(Red, null, new Rect(0, legend + 5, 10, 3));
        var first = Label($"Download {down:0.0} Mb/s");
        dc.DrawText(first, new Point(14, legend));
        var x = 14 + first.Width + 16;
        dc.DrawRectangle(WhitePen.Brush, null, new Rect(x, legend + 5, 10, 3));
        dc.DrawText(Label($"Video {rate:0.0} Mb/s"), new Point(x + 14, legend));
    }
}

/// <summary>The loading screen's ring: it fills red with the buffer, the percentage inside it.</summary>
public sealed class Ring : FrameworkElement
{
    const double Size = 64, Radius = 26;
    static readonly Pen TrackPen = Frozen(new Pen(new SolidColorBrush(Color.FromArgb(0x22, 255, 255, 255)), 5));
    static readonly Pen ArcPen = Frozen(new Pen(new SolidColorBrush(Color.FromRgb(0xe5, 0x09, 0x14)), 5) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round });
    static readonly Typeface Face = new(new FontFamily("Segoe UI"), FontStyles.Normal, FontWeights.Bold, FontStretches.Normal);

    static T Frozen<T>(T f) where T : Freezable { f.Freeze(); return f; }

    double percent;

    public double Percent
    {
        get => percent;
        set
        {
            var next = Math.Clamp(value, 0, 100);
            if (Math.Abs(next - percent) < 0.5) return;
            percent = next;
            InvalidateVisual();
        }
    }

    public Ring()
    {
        Width = Height = Size;
    }

    protected override void OnRender(DrawingContext dc)
    {
        var c = new Point(Size / 2, Size / 2);
        dc.DrawEllipse(null, TrackPen, c, Radius, Radius);
        var p = percent / 100;
        if (p >= 0.999)
        {
            dc.DrawEllipse(null, ArcPen, c, Radius, Radius);
        }
        else if (p > 0)
        {
            // Clockwise from twelve o'clock.
            var a = p * 2 * Math.PI;
            var arc = new StreamGeometry();
            using (var g = arc.Open())
            {
                g.BeginFigure(new Point(c.X, c.Y - Radius), false, false);
                g.ArcTo(new Point(c.X + Radius * Math.Sin(a), c.Y - Radius * Math.Cos(a)), new Size(Radius, Radius), 0, a > Math.PI, SweepDirection.Clockwise, true, false);
            }
            arc.Freeze();
            dc.DrawGeometry(null, ArcPen, arc);
        }
        var text = new FormattedText($"{Math.Round(percent)}%", System.Globalization.CultureInfo.CurrentCulture, FlowDirection.LeftToRight,
            Face, 13, Brushes.White, VisualTreeHelper.GetDpi(this).PixelsPerDip);
        dc.DrawText(text, new Point(c.X - text.Width / 2, c.Y - text.Height / 2));
    }
}

public sealed class SeekBar : FrameworkElement
{
    public event Action<double>? Scrub;
    public event Action<double>? Commit;
    /// <summary>The spot under the mouse, hovering or dragging.</summary>
    public event Action<double>? Hover;

    double value;
    double? dragging;
    double? hover;

    /// <summary>In ms, for the time shown over the hovered spot.</summary>
    public long Duration { get; set; }

    /// <summary>The frame at the hovered spot, drawn above it; null shows the time alone.</summary>
    public ImageSource? Preview
    {
        get => preview;
        set
        {
            if (preview == value) return;
            preview = value;
            InvalidateVisual();
        }
    }
    ImageSource? preview;

    public double Value
    {
        get => value;
        set
        {
            this.value = value;
            if (dragging is null) InvalidateVisual();
        }
    }

    /// <summary>Spans of the file already on disk, as 0–1 ratios; drawn behind the played part.</summary>
    public IReadOnlyList<(double From, double To)> Downloaded
    {
        get => downloaded;
        set
        {
            downloaded = value;
            InvalidateVisual();
        }
    }
    IReadOnlyList<(double From, double To)> downloaded = Array.Empty<(double, double)>();

    public SeekBar()
    {
        Height = 36;
        Cursor = Cursors.Hand;
    }

    protected override void OnRender(DrawingContext dc)
    {
        var w = ActualWidth;
        var y = ActualHeight / 2;
        var pct = Math.Clamp(dragging ?? value, 0, 1);
        // The phone's and the TV's bar: 4px, the played part red, what is on disk lighter.
        dc.DrawRectangle(Brushes.Transparent, null, new Rect(0, 0, w, ActualHeight));
        dc.DrawRoundedRectangle(new SolidColorBrush(Color.FromArgb(0x40, 255, 255, 255)), null, new Rect(0, y - 2, w, 4), 2, 2);
        var white = new SolidColorBrush(Color.FromArgb(0x70, 255, 255, 255));
        foreach (var (from, to) in downloaded)
            dc.DrawRectangle(white, null, new Rect(w * from, y - 2, Math.Max(1, w * (to - from)), 4));
        var at = dragging ?? (IsMouseOver ? hover : null);
        // Hovering ahead of the playhead lights the stretch up to the mouse, as YouTube does.
        if (dragging is null && at is double ahead && ahead > pct)
            dc.DrawRectangle(new SolidColorBrush(Color.FromArgb(0x60, 255, 255, 255)), null, new Rect(w * pct, y - 2, w * (ahead - pct), 4));
        var red = new SolidColorBrush(Color.FromRgb(0xe5, 0x09, 0x14));
        dc.DrawRoundedRectangle(red, null, new Rect(0, y - 2, w * pct, 4), 2, 2);
        // The knob swells under the mouse, with a soft red halo while held — the TV's knob.
        var held = IsMouseOver || dragging is not null;
        if (held) dc.DrawEllipse(new SolidColorBrush(Color.FromArgb(0x55, 0xe5, 0x09, 0x14)), null, new Point(w * pct, y), 13, 13);
        dc.DrawEllipse(red, null, new Point(w * pct, y), held ? 9 : 7, held ? 9 : 7);
        if (at is double r && Duration > 0) DrawPeek(dc, r, w, y);
    }

    /// <summary>Above the bar, over the video: the frame at that spot and its time under it.</summary>
    void DrawPeek(DrawingContext dc, double r, double w, double y)
    {
        var dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;
        var time = new FormattedText(Format.Clock(r * Duration / 1000.0), System.Globalization.CultureInfo.CurrentCulture,
            FlowDirection.LeftToRight, new Typeface("Segoe UI"), 12, Brushes.White, dpi);
        var shade = new SolidColorBrush(Color.FromArgb(0xc0, 0, 0, 0));
        var x = w * r;
        var pill = new Rect(0, y - 12 - time.Height - 4, time.Width + 12, time.Height + 4);
        pill.X = Math.Clamp(x - pill.Width / 2, 0, Math.Max(0, w - pill.Width));
        if (preview is { Width: > 0 } image)
        {
            const double width = 168;
            var frame = new Rect(0, pill.Top - 6 - width * image.Height / image.Width, width, width * image.Height / image.Width);
            frame.X = Math.Clamp(x - width / 2, 0, Math.Max(0, w - width));
            var round = new RectangleGeometry(frame, 6, 6);
            dc.PushClip(round);
            dc.DrawImage(image, frame);
            dc.Pop();
            dc.DrawGeometry(null, new Pen(Brushes.White, 2), round);
        }
        dc.DrawRoundedRectangle(shade, null, pill, 4, 4);
        dc.DrawText(time, new Point(pill.X + 6, pill.Y + 2));
    }

    // The peek is drawn outside the bar; only the bar itself takes the mouse.
    protected override HitTestResult? HitTestCore(PointHitTestParameters p) =>
        new Rect(RenderSize).Contains(p.HitPoint) ? new PointHitTestResult(this, p.HitPoint) : null;

    double RatioAt(MouseEventArgs e) => ActualWidth > 0 ? Math.Clamp(e.GetPosition(this).X / ActualWidth, 0, 1) : 0;

    protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
    {
        CaptureMouse();
        dragging = RatioAt(e);
        Scrub?.Invoke(dragging.Value);
        Hover?.Invoke(dragging.Value);
        InvalidateVisual();
        e.Handled = true;
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        hover = RatioAt(e);
        Hover?.Invoke(hover.Value);
        if (dragging is null) { InvalidateVisual(); return; }
        dragging = hover;
        Scrub?.Invoke(dragging.Value);
        InvalidateVisual();
    }

    protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
    {
        if (dragging is null) return;
        var at = dragging.Value;
        dragging = null;
        ReleaseMouseCapture();
        Commit?.Invoke(at);
        InvalidateVisual();
        e.Handled = true;
    }

    protected override void OnMouseLeave(MouseEventArgs e)
    {
        hover = null;
        InvalidateVisual();
    }
}

/// <summary>
/// The seek bar's hover frames: a second libVLC player on the same file, silent and paused,
/// decoding into a small bitmap; each hover seeks it (to keyframes, for speed) and plays it
/// to the first new picture. YouTube reads
/// these off a pre-made storyboard; here the file itself is the storyboard. It reads the file
/// off disk — a torrent's HTTP stream has one reader, the playback — shared so the torrent
/// can keep writing it and rename it once complete.
/// </summary>
sealed class SeekPreview : IDisposable
{
    const uint Width = 320;
    readonly FileStream file;
    readonly Media media;
    readonly MediaPlayer player;
    readonly Dispatcher ui = Dispatcher.CurrentDispatcher;
    readonly Action<WriteableBitmap> shown;
    readonly DispatcherTimer pump = new() { Interval = TimeSpan.FromMilliseconds(120) };
    // Held here: libVLC calls them on its own threads, and a collected delegate crashes it.
    readonly MediaPlayer.LibVLCVideoFormatCb formatCb;
    readonly MediaPlayer.LibVLCVideoCleanupCb cleanupCb;
    readonly MediaPlayer.LibVLCVideoLockCb lockCb;
    readonly MediaPlayer.LibVLCVideoDisplayCb displayCb;
    IntPtr buffer;
    int height;
    byte[]? last;
    long wanted = -1, asked = -1;
    /// <summary>Pause on the next new picture: set at the start and by each seek.</summary>
    volatile bool hold = true;
    volatile bool disposed;

    public WriteableBitmap? Image { get; private set; }

    /// <summary>The time, in ms, <see cref="Image"/> was asked for (the latest seek when it came).</summary>
    public long FrameAt { get; private set; }

    public SeekPreview(LibVLC vlc, string path, Action<WriteableBitmap> shown)
    {
        this.shown = shown;
        file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        media = new Media(vlc, new StreamMediaInput(file), ":no-audio", ":no-spu", ":no-sub-autodetect-file", ":input-fast-seek");
        player = new MediaPlayer(media) { EnableHardwareDecoding = false };
        formatCb = Format;
        cleanupCb = Cleanup;
        lockCb = Lock;
        displayCb = Display;
        player.SetVideoFormatCallbacks(formatCb, cleanupCb);
        player.SetVideoCallbacks(lockCb, null, displayCb);
        pump.Tick += (_, _) =>
        {
            if (wanted == asked || disposed) { pump.Stop(); return; }
            asked = wanted;
            // Seek, then play until the new picture lands (Display pauses it again). Paused,
            // a seek plus a frame step drew nothing for some files (4K x265).
            hold = true;
            player.Time = asked;
            player.SetPause(false);
        };
        player.Play();
    }

    /// <summary>Seeks to <paramref name="ms"/>, at most every 120 ms however fast the mouse moves.</summary>
    public void Show(long ms)
    {
        wanted = ms;
        if (!pump.IsEnabled) pump.Start();
    }

    uint Format(ref IntPtr opaque, IntPtr chroma, ref uint width, ref uint height, ref uint pitches, ref uint lines)
    {
        Marshal.Copy("RV32"u8.ToArray(), 0, chroma, 4);
        var h = width > 0 && height > 0 ? Math.Max(2, Width * height / width) : Width * 9 / 16;
        width = Width;
        height = h;
        pitches = Width * 4;
        lines = h;
        this.height = (int)h;
        buffer = Marshal.AllocHGlobal((int)(pitches * lines));
        return 1;
    }

    void Cleanup(ref IntPtr opaque)
    {
        Marshal.FreeHGlobal(buffer);
        buffer = IntPtr.Zero;
    }

    IntPtr Lock(IntPtr opaque, IntPtr planes)
    {
        Marshal.WriteIntPtr(planes, buffer);
        return IntPtr.Zero;
    }

    void Display(IntPtr opaque, IntPtr picture)
    {
        if (disposed) return;
        var h = height;
        var pixels = new byte[Width * 4 * h];
        Marshal.Copy(buffer, pixels, 0, pixels.Length);
        // Paused, libVLC keeps redrawing the same picture; only a new one goes up.
        if (last is not null && pixels.AsSpan().SequenceEqual(last)) return;
        last = pixels;
        if (hold)
        {
            hold = false;
            // Not if a newer seek wants it playing again meanwhile.
            ThreadPool.QueueUserWorkItem(_ => { if (!disposed && !hold) player.SetPause(true); });
        }
        ui.BeginInvoke(() =>
        {
            if (disposed) return;
            if (Image is null || Image.PixelHeight != h) Image = new WriteableBitmap((int)Width, h, 96, 96, PixelFormats.Bgr32, null);
            Image.WritePixels(new Int32Rect(0, 0, (int)Width, h), pixels, (int)Width * 4, 0);
            FrameAt = Math.Max(0, asked);
            shown(Image);
        });
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        pump.Stop();
        // Stop waits for libVLC's threads; off the UI thread, like the main player's close.
        ThreadPool.QueueUserWorkItem(_ =>
        {
            try
            {
                player.Stop();
                player.Dispose();
                media.Dispose();
                file.Dispose();
            }
            catch { }
        });
    }
}
