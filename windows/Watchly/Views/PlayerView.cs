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

    static LibVLC? vlc;
    static LibVLC Vlc => vlc ??= new LibVLC("--no-video-title-show", "--no-osd", "--quiet");

    public event Action? Exited;

    readonly PlaybackRequest request;
    readonly MainWindow shell = MainWindow.Shell;
    readonly VideoView video = new();
    readonly MediaPlayer player;
    readonly Grid overlay = new() { Background = new SolidColorBrush(Color.FromArgb(1, 0, 0, 0)) };
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
    string? pendingSub;
    List<Sub> subResults = new();
    bool subLoading;
    string subQuery = "";
    Progress? stats;

    // Chrome, rebuilt when state changes rather than bound.
    readonly Grid chrome = new();
    readonly TextBlock clockText = new() { Foreground = Brushes.White, FontSize = 12, FontWeight = FontWeights.Medium, MinWidth = 52, VerticalAlignment = VerticalAlignment.Center };
    readonly TextBlock lengthText = new() { Foreground = Brushes.White, FontSize = 12, FontWeight = FontWeights.Medium, MinWidth = 52, TextAlignment = TextAlignment.Right, VerticalAlignment = VerticalAlignment.Center };
    readonly SeekBar seek = new();

    public PlayerView(PlaybackRequest request)
    {
        this.request = request;
        externalSubs = request.SavedSubs.Where(s => File.Exists(s.Path)).ToList();
        Background = Brushes.Black;
        Focusable = true;

        player = new MediaPlayer(Vlc) { EnableHardwareDecoding = true, EnableKeyInput = false, EnableMouseInput = false };
        player.Volume = shell.Settings.Volume;
        video.MediaPlayer = player;
        video.Content = overlay;
        Content = video;

        overlay.Children.Add(chrome);
        overlay.MouseMove += (_, _) => Bump();
        overlay.MouseLeftButtonDown += OnPictureDown;
        overlay.MouseWheel += (_, e) => { StepVolume(e.Delta > 0 ? 5 : -5); e.Handled = true; };
        overlay.PreviewKeyDown += (_, e) => HandleKey(e);
        seek.Scrub += ratio => { seekAt = DateTime.UtcNow; current = (long)(ratio * duration); UpdateClock(); Bump(); };
        seek.Commit += ratio => SeekTo((long)(ratio * duration));

        player.Playing += (_, _) => Ui(() => { buffering = false; OnPlaying(); Render(); });
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
            hideTimer.Stop();
            if (!picking && !searchingSubs && player.IsPlaying) { showUi = false; Render(); }
        };
        clickTimer.Tick += (_, _) => { clickTimer.Stop(); ToggleUi(); };

        Loaded += (_, _) => { Focus(); Keyboard.Focus(this); };
        tick.Start();
        Render();
        _ = Start();
    }

    static void Ui(Action action) => Application.Current?.Dispatcher.BeginInvoke(action);

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
                session = await StreamSession.Start(request.Torrent!, request.FileIndex, shell.Settings.RemoveFilesAfterStop, life.Token);
                if (disposed) { await session.DisposeAsync(); return; }
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
            ":network-caching=3000",
            ":file-caching=1000",
            $":start-time={startSeconds.ToString(System.Globalization.CultureInfo.InvariantCulture)}");
        started = true;
        buffering = true;
        current = (long)(startSeconds * 1000);
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
        player.AddSlave(MediaSlaveType.Subtitle, new Uri(path).AbsoluteUri, select);
    }

    void OnTracksChanged()
    {
        if (pendingSub is null) return;
        // A SubDL pick lands as a new track; select it once libVLC lists it.
        var tracks = player.SpuDescription.Where(t => t.Id >= 0).ToList();
        if (tracks.Count == 0) return;
        var stem = Path.GetFileNameWithoutExtension(pendingSub);
        var match = tracks.FindIndex(t => t.Name?.Contains(stem, StringComparison.OrdinalIgnoreCase) == true);
        player.SetSpu(tracks[match >= 0 ? match : tracks.Count - 1].Id);
        pendingSub = null;
        if (picking) Render();
    }

    void OnTick()
    {
        if (disposed) return;
        if (session is not null) stats = session.Progress;

        // The stall libVLC never reports: the socket blocks on a piece that has
        // not landed, the picture freezes, and no event fires. A clock that stops
        // is the only signal there is.
        if (player.IsPlaying)
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
            foreach (var path in session.ReadySubtitles()) AddSlave(path, false);

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
        started, Preparing, showUi, locked, buffering || stalled, started && player.IsPlaying, picking, searchingSubs, hud, UpNextVisible,
        failure, failureFatal, scaleIdx, rate, fullscreen, started && player.Mute, started && player.Spu >= 0,
        stats is { } s && (s.Seeds > 0 || s.Peers > 0), subLoading, subResults.Count);

    bool Preparing => !started || (buffering && current <= 0 && !player.IsPlaying);

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
        _ = Close().ContinueWith(_ => Ui(() => { Exited?.Invoke(); go(); }));
    }

    // ── Controls ────────────────────────────────────────────────────────────

    void Bump()
    {
        overlay.Cursor = null;
        if (!showUi) { showUi = true; Render(); }
        hideTimer.Stop();
        hideTimer.Interval = TimeSpan.FromSeconds(picking || searchingSubs ? 12 : 3.5);
        hideTimer.Start();
    }

    void ToggleUi()
    {
        if (locked) { Bump(); return; }
        if (picking || searchingSubs) { picking = searchingSubs = false; Render(); return; }
        showUi = !showUi;
        if (showUi) Bump();
        Render();
    }

    void OnPictureDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource != overlay && e.OriginalSource is not Border { Tag: "dim" }) return;
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
        if (Keyboard.FocusedElement is TextBox) { if (e.Key == Key.Escape) { searchingSubs = false; Render(); e.Handled = true; } return; }
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
            case Key.Up:
                StepVolume(5);
                break;
            case Key.Down:
                StepVolume(-5);
                break;
            case Key.M:
                player.Mute = !player.Mute;
                ShowHud(player.Mute ? "Muted" : $"Volume {player.Volume}%");
                break;
            case Key.F or Key.F11:
                ToggleFullscreen();
                break;
            case Key.N when request.Next is not null:
                PlayNext();
                break;
            case Key.Escape:
                if (locked) { Lock(false); break; }
                if (picking || searchingSubs) { picking = searchingSubs = false; Render(); break; }
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
        Bump();
    }

    void TogglePlay()
    {
        if (!started) return;
        if (player.IsPlaying) player.SetPause(true);
        else if (player.State == VLCState.Ended || player.State == VLCState.Stopped) player.Play();
        else player.SetPause(false);
        Render();
    }

    void SeekTo(long ms)
    {
        if (duration <= 0) return;
        var to = Math.Clamp(ms, 0, duration - 1000);
        seekAt = DateTime.UtcNow;
        current = to;
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
        ShowHud($"Volume {next}%");
        shell.ChangeSettings(shell.Settings with { Volume = next });
    }

    void ShowHud(string text)
    {
        hud = text;
        hudUntil = DateTime.UtcNow.AddMilliseconds(900);
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

    void ToggleFullscreen()
    {
        var window = Window.GetWindow(this);
        if (window is null) return;
        fullscreen = !fullscreen;
        if (fullscreen)
        {
            window.WindowStyle = WindowStyle.None;
            window.ResizeMode = ResizeMode.NoResize;
            if (window.WindowState == WindowState.Maximized) window.WindowState = WindowState.Normal;
            window.WindowState = WindowState.Maximized;
        }
        else
        {
            window.WindowStyle = WindowStyle.SingleBorderWindow;
            window.ResizeMode = ResizeMode.CanResize;
            window.WindowState = WindowState.Normal;
        }
        Dispatcher.BeginInvoke(ApplyScale, DispatcherPriority.Background);
        Render();
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
    static readonly Brush Blue = new SolidColorBrush(Color.FromRgb(0x3b, 0x6f, 0xe0));
    static readonly Brush BlueLight = new SolidColorBrush(Color.FromRgb(0x7a, 0xa2, 0xff));

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
            if (stats is { } s && (s.Seeds > 0 || s.Peers > 0))
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
            actions.Children.Add(Action("subtitles", null, "Subtitles", player.Spu >= 0 || picking, () => { searchingSubs = false; picking = !picking; Render(); }));
            actions.Children.Add(Action("search", null, "Find subs", searchingSubs, () =>
            {
                picking = false;
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
            if (session is not null) actions.Children.Add(Action("download", null, "Save", false, SaveCopy));
            actions.Children.Add(Action(fullscreen ? "minimize" : "maximize", null, fullscreen ? "Exit full" : "Fullscreen", fullscreen, ToggleFullscreen));
            bottom.Children.Add(actions);
            chrome.Children.Add(bottom);
        }

        if (picking) chrome.Children.Add(TrackSheet());
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

    static void Detach(FrameworkElement e)
    {
        if (e.Parent is Panel p) p.Children.Remove(e);
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

    FrameworkElement TrackSheet()
    {
        var columns = new StackPanel { Orientation = Orientation.Horizontal };

        var audio = player.AudioTrackDescription.Where(t => t.Id >= 0).ToList();
        if (audio.Count > 1)
        {
            var list = new StackPanel { Width = 240 };
            list.Children.Add(SheetTitle("AUDIO"));
            foreach (var t in audio)
            {
                var id = t.Id;
                list.Children.Add(Option(string.IsNullOrWhiteSpace(t.Name) ? $"Track {id}" : t.Name, player.AudioTrack == id, () => { player.SetAudioTrack(id); picking = false; Render(); }));
            }
            columns.Children.Add(new ScrollViewer { MaxHeight = Math.Max(160, ActualHeight - 200), VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = list });
            columns.Children.Add(new Border { Width = 1, Background = White(0x1f), Margin = new Thickness(6, 0, 6, 0) });
        }

        var subs = new StackPanel { Width = 280 };
        subs.Children.Add(SheetTitle("SUBTITLES"));
        subs.Children.Add(Option("Off", player.Spu < 0, () => { player.SetSpu(-1); picking = false; Render(); }));
        var tracks = player.SpuDescription.Where(t => t.Id >= 0).ToList();
        foreach (var t in tracks)
        {
            var id = t.Id;
            subs.Children.Add(Option(FriendlySub(t.Name, id), player.Spu == id, () => { player.SetSpu(id); picking = false; Render(); }));
        }
        if (tracks.Count == 0)
        {
            var none = WText(SubDL.Key is not null ? "No subtitles found. Use Find subs to search online." : "No subtitles found, and this build has no SubDL key for online search.", 12, White(0x70));
            none.TextWrapping = TextWrapping.Wrap;
            none.Margin = new Thickness(10, 8, 10, 8);
            subs.Children.Add(none);
        }
        columns.Children.Add(new ScrollViewer { MaxHeight = Math.Max(160, ActualHeight - 200), VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = subs });

        return new Border
        {
            CornerRadius = new CornerRadius(12),
            Background = Black(0xdd),
            Padding = new Thickness(6),
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(0, 68, 16, 0),
            Child = columns,
        };
    }

    string FriendlySub(string? raw, int id)
    {
        var name = (raw ?? "").Trim();
        var external = externalSubs.FirstOrDefault(s => name.Contains(Path.GetFileNameWithoutExtension(s.Path), StringComparison.OrdinalIgnoreCase));
        if (external is not null) return external.Label;
        return name.Length == 0 || name.StartsWith("Track", StringComparison.OrdinalIgnoreCase) && name.Length < 9 ? $"Subtitle {id}" : name;
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
        Detach(subBox);
        field.Child = subBox;
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

    public async void RequestExit()
    {
        await Close();
        Exited?.Invoke();
    }

    /// <summary>Checkpoint, stop the picture and the stream, give the window back.</summary>
    async Task Close()
    {
        if (disposed) return;
        SavePosition();
        if (fullscreen) ToggleFullscreen();
        Dispose();
        var p = player;
        var m = media;
        await Task.Run(() =>
        {
            try { p.Stop(); } catch { }
            try { m?.Dispose(); p.Dispose(); } catch { }
        });
        if (session is not null) await session.DisposeAsync();
    }

    public Task Shutdown() => Close();

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        life.Cancel();
        tick.Stop();
        hideTimer.Stop();
        clickTimer.Stop();
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
