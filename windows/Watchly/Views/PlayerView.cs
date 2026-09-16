using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
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
        var args = new List<string> { "--no-video-title-show", "--no-osd", "--quiet" };
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

    static LibVLC Vlc => VlcLibrary.Instance.Value;
    public static void Warm() => VlcLibrary.Warm();

    public event Action? Exited;

    readonly PlaybackRequest request;
    readonly MainWindow shell = MainWindow.Shell;
    readonly VideoView video = new();
    readonly MediaPlayer player;
    readonly Grid overlay = new() { Background = Brushes.Black };
    readonly DispatcherTimer tick = new() { Interval = TimeSpan.FromMilliseconds(300) };
    readonly DispatcherTimer hideTimer = new() { Interval = TimeSpan.FromSeconds(3.5) };
    readonly DispatcherTimer clickTimer = new() { Interval = TimeSpan.FromMilliseconds(230) };
    readonly CancellationTokenSource life = new();

    StreamSession? session;
    Media? media;
    bool disposed;
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
    int scaleIdx;
    double rate = 1;
    bool picking;
    bool pickingAudio;
    bool searchingSubs;
    bool dismissedNext;
    bool fullscreen;
    string? failure;
    bool failureFatal;
    string? hud;
    DateTime hudUntil;
    DateTime lastSave = DateTime.UtcNow;
    readonly List<SavedSub> externalSubs;
    readonly HashSet<string> addedSlaves = new(StringComparer.OrdinalIgnoreCase);
    readonly Dictionary<int, string> slaveTrackLabels = new();
    string? pendingSub;
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

        player = new MediaPlayer(Vlc) { EnableHardwareDecoding = true, EnableKeyInput = false, EnableMouseInput = false };
        player.Volume = shell.Settings.Volume;
        video.MediaPlayer = player;
        video.Content = overlay;
        Content = video;

        overlay.Children.Add(chrome);
        overlay.MouseMove += OnOverlayMouseMove;
        overlay.MouseLeave += OnOverlayMouseLeave;
        overlay.MouseLeftButtonDown += OnPictureDown;
        overlay.MouseWheel += OnOverlayMouseWheel;
        PreviewKeyDown += (_, e) => HandleKey(e);
        System.Windows.Interop.ComponentDispatcher.ThreadFilterMessage += OnThreadMessage;

        seek.Scrub += ratio => { seekAt = DateTime.UtcNow; current = (long)(ratio * duration); UpdateClock(); Bump(); };
        seek.Commit += ratio => SeekTo((long)(ratio * duration));

        player.Playing += (_, _) => Ui(() =>
        {
            buffering = false;
            // The audio output exists now, and it picks up whatever mute Windows remembered
            // for this app (a pause in an older build muted it); only the viewer's M mutes.
            // Likewise the saved volume: set before playback there is no output to take it.
            if (player.Mute != userMuted) player.Mute = userMuted;
            if (player.Volume != shell.Settings.Volume) player.Volume = shell.Settings.Volume;
            OnPlaying();
            Bump();
            Render();
        });
        player.Paused += (_, _) => Ui(Render);
        player.Buffering += (_, e) => Ui(() =>
        {
            bufferPct = e.Cache;
            var now = e.Cache < 100;
            if (now != buffering) { buffering = now; Render(); }
        });
        player.LengthChanged += (_, e) => Ui(() =>
        {
            if (e.Length <= 0) return;
            duration = e.Length;
            if (request.HistoryUri is not null) shell.SaveDuration(request.HistoryUri, request.FileIndex, duration / 1000.0);
            UpdateClock();
        });
        player.TimeChanged += (_, e) => Ui(() =>
        {
            // Between asking for a seek and it landing this still reports the old spot.
            if ((DateTime.UtcNow - seekAt).TotalMilliseconds < 1200) return;
            current = e.Time;
            if (current > 5000) reloads = 0;
            UpdateClock();
        });
        player.EndReached += (_, _) => Ui(OnEnded);
        player.EncounteredError += (_, _) => Ui(() =>
        {
            if (duration > 0) Warn("Playback stalled — waiting for data");
            else Fail("This file could not be played", true);
        });
        player.ESAdded += (_, _) => Ui(OnTracksChanged);

        tick.Tick += (_, _) => OnTick();
        hideTimer.Tick += (_, _) =>
        {
            if (disposed) return;
            if (picking || pickingAudio || searchingSubs)
            {
                return;
            }
            if (IsPlayingSafe || SafeState == VLCState.Playing)
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
        if (!picking && !pickingAudio && !searchingSubs && IsPlayingSafe)
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
        media = new Media(Vlc, new Uri(url),
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
        ApplyScale();
    }

    void AddSlave(string path, bool select)
    {
        if (!addedSlaves.Add(path)) return;
        var label = externalSubs.FirstOrDefault(s => s.Path.Equals(path, StringComparison.OrdinalIgnoreCase))?.Label
                 ?? Path.GetFileName(path);
        try
        {
            player.AddSlave(MediaSlaveType.Subtitle, new Uri(path).AbsoluteUri, select);
            MapNewTracks(label, select);
        }
        catch (Exception ex)
        {
            Log.Warn("player", $"AddSlave failed for '{path}': {ex.Message}");
        }
    }

    void MapNewTracks(string label, bool select)
    {
        _ = Task.Run(async () =>
        {
            for (int i = 0; i < 15; i++)
            {
                await Task.Delay(150);
                var found = false;
                Ui(() =>
                {
                    var tracks = player.SpuDescription.Where(t => t.Id >= 0).ToList();
                    foreach (var t in tracks)
                    {
                        if (!slaveTrackLabels.ContainsKey(t.Id))
                        {
                            slaveTrackLabels[t.Id] = label;
                            found = true;
                            if (select || pendingSub is not null)
                            {
                                player.SetSpu(t.Id);
                                ShowHud($"Subtitles: {label}");
                                pendingSub = null;
                            }
                        }
                    }
                    if (found && (picking || pickingAudio)) Render();
                });
                if (found) break;
            }
        });
    }

    void OnTracksChanged()
    {
        if (pendingSub is null) return;
        // A SubDL pick lands as a new track; select it once libVLC lists it.
        var tracks = player.SpuDescription.Where(t => t.Id >= 0).ToList();
        if (tracks.Count == 0) return;
        var stem = Path.GetFileNameWithoutExtension(pendingSub);
        var match = tracks.FindIndex(t => t.Name?.Contains(stem, StringComparison.OrdinalIgnoreCase) == true);
        var chosen = tracks[match >= 0 ? match : tracks.Count - 1];
        player.SetSpu(chosen.Id);
        var label = slaveTrackLabels.TryGetValue(chosen.Id, out var l) ? l : FriendlySub(chosen.Name, chosen.Id);
        ShowHud($"Subtitles: {label}");
        pendingSub = null;
        if (picking) Render();
    }

    void OnTick()
    {
        if (disposed) return;
        if (session is not null)
        {
            stats = session.Progress;
            _ = session.KeepBuffer(current / 1000.0, duration / 1000.0);
        }

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
        started, Preparing, showUi, locked, buffering || stalled, started && IsPlayingSafe,
        picking, pickingAudio, searchingSubs, hud, UpNextVisible,
        failure, failureFatal, scaleIdx, rate, fullscreen,
        started && !disposed && player.NativeReference != IntPtr.Zero && player.Mute,
        started && !disposed && player.NativeReference != IntPtr.Zero && player.Spu >= 0,
        !disposed && player.NativeReference != IntPtr.Zero ? player.AudioTrack : -1,
        !disposed && player.NativeReference != IntPtr.Zero ? player.AudioTrackCount : 0,
        stats != null, subLoading, subResults.Count);

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
        if (started) liveVolume.Text = player.Volume.ToString();
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

    void SavePosition()
    {
        if (request.HistoryUri is null || current <= 0) return;
        shell.SavePosition(request.HistoryUri, request.FileIndex, current / 1000.0);
    }

    bool UpNextVisible => request.Next is not null && !dismissedNext && duration > 0 && duration - current <= UpNextSeconds * 1000;

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
        hideTimer.Interval = TimeSpan.FromSeconds(picking || pickingAudio || searchingSubs ? 12 : 3.5);
        hideTimer.Start();
    }

    void ToggleUi()
    {
        if (locked) { Bump(); return; }
        if (picking || pickingAudio || searchingSubs) { picking = pickingAudio = searchingSubs = false; Render(); return; }
        showUi = !showUi;
        if (showUi) Bump();
        Render();
    }

    void OnPictureDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource != overlay && e.OriginalSource is not Border { Tag: "dim" }) return;
        if (picking || pickingAudio || searchingSubs) { picking = pickingAudio = searchingSubs = false; Render(); return; }
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
                ShowHud(player.Mute ? "Muted" : $"Volume {player.Volume}%");
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
            case Key.N when request.Next is not null:
                PlayNext();
                break;
            case Key.Escape:
                if (locked) { Lock(false); break; }
                if (picking || pickingAudio || searchingSubs) { picking = pickingAudio = searchingSubs = false; Render(); break; }
                if (fullscreen) { ToggleFullscreen(); break; }
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
        ShowHud("F: Fullscreen · Space: Play/Pause · ←/→: 10s · ↑/↓: Vol · A: Audio · S: Subs · [: Slower · ]: Faster", 3000);
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

    void StepVolume(int by)
    {
        var next = Math.Clamp(player.Volume + by, 0, 150);
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
        ShowHud(Scales[scaleIdx].Label);
    }

    void ApplyScale()
    {
        var mode = Scales[scaleIdx];
        var ratio = ActualWidth > 0 && ActualHeight > 0 ? $"{(int)ActualWidth}:{(int)ActualHeight}" : null;
        player.CropGeometry = mode.Crop ? ratio : null;
        player.AspectRatio = mode.Aspect == "fill" ? (mode.Crop ? null : ratio) : mode.Aspect;
        player.Scale = mode.Scale;
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

    void SetFullscreen(Window window, bool on)
    {
        var hwnd = new System.Windows.Interop.WindowInteropHelper(window).Handle;
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
        if (SubDL.Key is null)
        {
            Warn("This build has no SubDL key");
            searchingSubs = false;
            Render();
            return;
        }
        if (q.Length == 0) q = request.MetaTitle ?? TitleText.SearchableName(request.Title);
        if (q.Length == 0 && request.Imdb is null) return;
        subLoading = true;
        subResults = new();
        Render();
        try
        {
            var at = Search.ParseEpisode(request.Title);
            var useImdb = request.Imdb is not null && (typed is null || typed.Trim().Length == 0 || typed.Trim() == request.MetaTitle);
            var results = await SubDL.Search(useImdb ? "" : q, useImdb ? request.Imdb : null, at?.Season, at?.Episode,
                at is not null || request.Kind == "tv" ? "tv" : request.Kind == "movie" ? "movie" : null);
            subResults = results.Take(20).ToList();
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
            pendingSub = path;
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
    static readonly Brush Blue = Frozen(Color.FromRgb(0x3b, 0x6f, 0xe0));
    static readonly Brush BlueLight = Frozen(Color.FromRgb(0x7a, 0xa2, 0xff));
    static Brush Frozen(Color color) { var b = new SolidColorBrush(color); b.Freeze(); return b; }

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
        lengthText.Text = Format.Clock(duration / 1000.0);
        seek.Value = duration > 0 ? (double)current / duration : 0;
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
        foreach (var live in new[] { liveSwarm, liveSpeed, livePct, liveMeta, liveStage, liveUpNext, liveVolume }) Detach(live);
        overlay.Cursor = !showUi && started && player.IsPlaying ? Cursors.None : null;
        Dispatcher.BeginInvoke(UpdateLive, DispatcherPriority.Render);

        if (Preparing)
        {
            overlay.Background = Brushes.Black;
            var preparing = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, MaxWidth = 700 };
            var name = WText(request.Title, 15, null, FontWeights.Medium);
            name.TextWrapping = TextWrapping.Wrap;
            name.TextAlignment = TextAlignment.Center;
            name.Margin = new Thickness(0, 0, 0, 16);
            preparing.Children.Add(name);
            var spin = Controls.Ui.Spinner(26);
            ((Control)spin).Foreground = Brushes.White;
            preparing.Children.Add(spin);
            liveStage.HorizontalAlignment = HorizontalAlignment.Center;
            liveStage.Margin = new Thickness(0, 12, 0, 0);
            preparing.Children.Add(liveStage);
            if (stats is not null)
            {
                liveMeta.HorizontalAlignment = HorizontalAlignment.Center;
                liveMeta.Margin = new Thickness(0, 4, 0, 0);
                preparing.Children.Add(liveMeta);
            }
            chrome.Children.Add(preparing);
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
            chrome.Children.Add(new Border { Background = Black(0x60), Tag = "dim" });

            // Top: back, title, swarm stats.
            var top = new DockPanel { VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(16) };
            var back = Circle(WIcon("arrow-left", 20), 40, RequestExit, 0x88, "Back (Esc)");
            DockPanel.SetDock(back, Dock.Left);
            top.Children.Add(back);
            if (session is not null && stats is not null)
            {
                var pill = new Border
                {
                    CornerRadius = new CornerRadius(16),
                    Background = Black(0x88),
                    Height = 32,
                    Padding = new Thickness(12, 0, 12, 0),
                    Child = new StackPanel
                    {
                        Orientation = Orientation.Horizontal,
                        VerticalAlignment = VerticalAlignment.Center,
                        Children =
                        {
                            WIcon("users", 12, White(0x90)),
                            Pad(liveSwarm, 6, 12),
                            WIcon("download", 12, White(0x90)),
                            Pad(liveSpeed, 6, 0),
                            Pad(livePct, 6, 0),
                        },
                    },
                };
                DockPanel.SetDock(pill, Dock.Right);
                top.Children.Add(pill);
            }
            var title = WText(request.Title, 13, null, FontWeights.Medium);
            title.VerticalAlignment = VerticalAlignment.Center;
            title.Margin = new Thickness(12, 0, 12, 0);
            top.Children.Add(title);
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

            // Left edge: volume, a point at a time — the phone's brightness rail.
            var rail = new Border
            {
                CornerRadius = new CornerRadius(22),
                Background = Black(0x73),
                Padding = new Thickness(0, 4, 0, 4),
                HorizontalAlignment = HorizontalAlignment.Left,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(20, 0, 0, 0),
                Child = new StackPanel
                {
                    Children =
                    {
                        RailButton("plus", () => StepVolume(5), "Louder (↑)"),
                        new StackPanel { Margin = new Thickness(0, 2, 0, 2), Children = { WIcon(player.Mute ? "volume-x" : "volume", 13, White(0xb0)), Centered(liveVolume) } },
                        RailButton("minus", () => StepVolume(-5), "Quieter (↓)"),
                    },
                },
            };
            chrome.Children.Add(rail);

            // Bottom: clock, scrubber, and the labelled action row.
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

            var actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 4, 0, 0) };
            actions.Children.Add(Action("lock", null, "Lock", false, () => Lock(true)));
            if (request.Next is not null) actions.Children.Add(Action("skip-forward", null, "Next", false, PlayNext));
            actions.Children.Add(Action("maximize", null, Scales[scaleIdx].Label, scaleIdx != 0, CycleScale));
            actions.Children.Add(Action("volume", null, "Audio", pickingAudio, () =>
            {
                picking = false;
                searchingSubs = false;
                pickingAudio = !pickingAudio;
                Render();
            }));
            actions.Children.Add(Action("subtitles", null, "Subtitles", player.Spu >= 0 || picking, () =>
            {
                pickingAudio = false;
                searchingSubs = false;
                picking = !picking;
                Render();
            }));
            actions.Children.Add(Action("search", null, "Find subs", searchingSubs, () =>
            {
                picking = false;
                pickingAudio = false;
                searchingSubs = !searchingSubs;
                if (searchingSubs)
                {
                    subQuery = request.MetaTitle ?? TitleText.SearchableName(request.Title);
                    subResults = new();
                    SearchSubs(subQuery);
                }
                Render();
            }));
            actions.Children.Add(Action(null, $"{rate}x", "Speed", rate != 1, () =>
            {
                rate = Rates[(Array.IndexOf(Rates, rate) + 1) % Rates.Length];
                player.SetRate((float)rate);
                Render();
            }));
            if (session is not null && !session.Borrowed)
                actions.Children.Add(Action(session.WholeFile ? "hard-drive" : "clock", null, session.WholeFile ? "Whole file" : "Next 5 min", session.WholeFile, ToggleWholeFile));
            if (session is not null) actions.Children.Add(Action("download", null, "Save", false, SaveCopy));
            actions.Children.Add(Action(fullscreen ? "minimize" : "maximize", null, fullscreen ? "Exit full" : "Fullscreen", fullscreen, ToggleFullscreen));
            bottom.Children.Add(actions);
            chrome.Children.Add(bottom);
        }

        if (picking) chrome.Children.Add(TrackSheet());
        if (pickingAudio) chrome.Children.Add(AudioSheet());
        if (searchingSubs) chrome.Children.Add(SearchSheet());

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

    Button Action(string? icon, string? text, string label, bool on, Action onClick)
    {
        var tint = on ? BlueLight : Brushes.White;
        UIElement glyph = icon is not null
            ? WIcon(icon, 18, tint)
            : new TextBlock { Text = text, FontSize = 15, FontWeight = FontWeights.Medium, Foreground = tint, HorizontalAlignment = HorizontalAlignment.Center };
        var labelText = WText(label, 10, on ? BlueLight : White(0xa0));
        labelText.HorizontalAlignment = HorizontalAlignment.Center;
        labelText.Margin = new Thickness(0, 3, 0, 0);
        var b = Controls.Ui.Button(new StackPanel { Children = { new Grid { Height = 20, Children = { glyph } }, labelText } }, () => { onClick(); Bump(); }, "Bare", label);
        b.MinWidth = 72;
        b.Padding = new Thickness(6, 4, 6, 4);
        b.Tag = 8;
        return b;
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
        b.Background = active ? Blue : Brushes.Transparent;
        return b;
    }

    FrameworkElement AudioSheet()
    {
        var panel = new StackPanel { Width = 260 };
        panel.Children.Add(SheetTitle("AUDIO TRACKS"));
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
                    player.SetAudioTrack(id);
                    pickingAudio = false;
                    ShowHud($"Audio: {label}");
                    Render();
                }));
            }
        }

        return new Border
        {
            CornerRadius = new CornerRadius(12),
            Background = Black(0xdd),
            Padding = new Thickness(6),
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(0, 68, 16, 0),
            Child = new ScrollViewer { MaxHeight = Math.Max(160, ActualHeight - 200), VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = panel },
        };
    }

    FrameworkElement TrackSheet()
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
            var none = WText(SubDL.Key is not null ? "No subtitles found. Use Find subs to search online." : "No subtitles found.", 12, White(0x70));
            none.TextWrapping = TextWrapping.Wrap;
            none.Margin = new Thickness(10, 8, 10, 8);
            subs.Children.Add(none);
        }

        return new Border
        {
            CornerRadius = new CornerRadius(12),
            Background = Black(0xdd),
            Padding = new Thickness(6),
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(0, 68, 16, 0),
            Child = new ScrollViewer { MaxHeight = Math.Max(160, ActualHeight - 200), VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = subs },
        };
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
        go.Background = Blue;
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
        // The controls live in LibVLCSharp's own see-through window over the video, which
        // only hides when the VideoView unloads — and that waits for libVLC and the torrent
        // to finish closing, seconds after Back or closing the app. Hide it now.
        if (Window.GetWindow(overlay) is { } controls && controls != Window.GetWindow(this)) controls.Hide();
        video.MediaPlayer = null;
    }
}

/// <summary>The thin scrubber: click anywhere on the track, or drag the head.</summary>
public sealed class SeekBar : FrameworkElement
{
    public event Action<double>? Scrub;
    public event Action<double>? Commit;

    double value;
    double? dragging;

    public double Value
    {
        get => value;
        set
        {
            this.value = value;
            if (dragging is null) InvalidateVisual();
        }
    }

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
        dc.DrawRectangle(Brushes.Transparent, null, new Rect(0, 0, w, ActualHeight));
        dc.DrawRoundedRectangle(new SolidColorBrush(Color.FromArgb(0x40, 255, 255, 255)), null, new Rect(0, y - 1.5, w, 3), 1.5, 1.5);
        var blue = new SolidColorBrush(Color.FromRgb(0x3b, 0x6f, 0xe0));
        dc.DrawRoundedRectangle(blue, null, new Rect(0, y - 1.5, w * pct, 3), 1.5, 1.5);
        dc.DrawEllipse(blue, null, new Point(w * pct, y), IsMouseOver || dragging is not null ? 7 : 6, IsMouseOver || dragging is not null ? 7 : 6);
    }

    double RatioAt(MouseEventArgs e) => ActualWidth > 0 ? Math.Clamp(e.GetPosition(this).X / ActualWidth, 0, 1) : 0;

    protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
    {
        CaptureMouse();
        dragging = RatioAt(e);
        Scrub?.Invoke(dragging.Value);
        InvalidateVisual();
        e.Handled = true;
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        if (dragging is null) { InvalidateVisual(); return; }
        dragging = RatioAt(e);
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

    protected override void OnMouseLeave(MouseEventArgs e) => InvalidateVisual();
}
