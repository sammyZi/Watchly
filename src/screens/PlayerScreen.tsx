import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Animated,
  AppState,
  BackHandler,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  useWindowDimensions,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import * as ScreenOrientation from 'expo-screen-orientation';
// Feather has no closed-caption glyph, and "type" reads as a bare T. The badge
// is the one subtitle symbol everyone recognises, so borrow it from Material.
import MaterialIcons from '@expo/vector-icons/MaterialIcons';
import {
  CastButton,
  useMediaStatus,
  useRemoteMediaClient,
} from 'react-native-google-cast';
import { VlcPlayer, type VlcPlayerRef, type VlcTrack } from '../../modules/vlc-player';
import TorrentStream, { type Progress } from '../../modules/torrent-stream';
import { Icon, type IconName } from '../components/Icon';
import { Marquee } from '../components/Marquee';
import { clock, speed } from '../lib/format';
import { searchSubtitles, type Sub } from '../lib/subdl';
import { parseEpisode } from '../lib/search';
import { searchableName } from '../lib/title';
import { recordDownload } from './DownloadsScreen';
import { font, radius, space, type } from '../theme';

/** Ships with the app — see .env. Nothing to type in, and nothing to store:
 *  it is the same key for every install. */
const SUBDL_KEY = process.env.EXPO_PUBLIC_SUBDL_KEY;

const SKIP_SECONDS = 10;
/** How long before the end the "up next" card appears. Long enough to cancel
 *  before the credits, short enough not to sit over the last scene. */
const UP_NEXT_SECONDS = 30;
const RATES = [0.75, 1, 1.25, 1.5, 2];

/**
 * libVLC's fit modes, in the order the button cycles them.
 *
 * Stretch distorts the picture to the screen; crop keeps its shape and cuts
 * the overhang. Both are here because they are the two different things people
 * mean by "fill", and stretch is the one reached for first.
 */
const SCALES = [
  { type: 'SURFACE_BEST_FIT', label: 'Best fit' },
  { type: 'SURFACE_FILL', label: 'Stretch' },
  { type: 'SURFACE_FIT_SCREEN', label: 'Crop' },
  { type: 'SURFACE_16_9', label: '16:9' },
  { type: 'SURFACE_4_3', label: '4:3' },
  { type: 'SURFACE_ORIGINAL', label: 'Original' },
] as const;

/**
 * Player chrome over a libVLC surface. The controls are ours because no engine
 * ships an overlay that knows about swarm stats or piece-level buffering.
 */
export function PlayerScreen({
  title,
  resumeAt,
  url,
  lanUrl,
  subtitleFiles,
  savedSubs,
  onSaveSubs,
  fileIndex,
  next,
  onNext,
  progress,
  onSavePosition,
  onDuration,
  onExit,
}: {
  /** What is playing — the file inside the torrent, not the torrent. */
  title?: string;
  resumeAt?: number;
  /** null until the first piece lands — the player mounts before that. */
  url: string | null;
  /** The stream on a network address. A Cast receiver fetches the video itself,
   *  so loopback is no use to it — no LAN address, no casting. */
  lanUrl?: string | null;
  subtitleFiles?: string[];
  /** SubDL picks kept from a previous session for this file. */
  savedSubs?: { path: string; label: string }[];
  onSaveSubs?: (subs: { path: string; label: string }[]) => void;
  fileIndex: number;
  /** The episode after this one — from this torrent or the next one. */
  next?: { name: string } | null;
  onNext?: () => void;
  progress: Progress | null;
  onSavePosition: (seconds: number) => void;
  onDuration: (seconds: number) => void;
  onExit: () => void;
}) {
  const [failure, setFailure] = useState<{ text: string; fatal: boolean } | null>(null);
  const [paused, setPaused] = useState(false);
  const [showUi, setShowUi] = useState(true);
  const [duration, setDuration] = useState(0);
  const [current, setCurrent] = useState(resumeAt ?? 0);
  const [rate, setRate] = useState(1);
  const [buffering, setBuffering] = useState(false);
  /** Every subtitle track libVLC found inside the media. */
  const [tracks, setTracks] = useState<VlcTrack[]>([]);
  /** Audio tracks, for the dubs and commentaries a release often carries. */
  const [audioTracks, setAudioTracks] = useState<VlcTrack[]>([]);
  const [audioTrack, setAudioTrack] = useState<number | null>(null);
  /** Index of the chosen track, null for off. */
  const [subTrack, setSubTrack] = useState<number | null>(null);
  const [picking, setPicking] = useState(false);
  /** Tracks that failed to select. libVLC renders essentially every subtitle
   *  format, so this should stay empty — it is a safety net, not a workaround. */
  const [badTracks, setBadTracks] = useState<number[]>([]);
  /** The side that was double-tapped and how far the run of taps has moved, so
   *  tapping four times reads "40 seconds" rather than flashing 10 four times. */
  const [nudge, setNudge] = useState<{ side: 'left' | 'right'; total: number } | null>(
    null
  );
  /** Swarm stats, subscribed to only while they are on screen. */
  const [stats, setStats] = useState<Progress | null>(null);
  const [scaleIdx, setScaleIdx] = useState(0);
  /** Controls locked out, so a hand on the screen cannot pause or seek. */
  const [locked, setLocked] = useState(false);
  /** Transient readout for the fit mode, which has no control of its own. */
  const [hud, setHud] = useState<{ icon: IconName; label: string } | null>(null);
  /** SubDL search state. */
  const [searching, setSearching] = useState(false);
  const [subResults, setSubResults] = useState<Sub[]>([]);
  const [subLoading, setSubLoading] = useState(false);
  const [subQuery, setSubQuery] = useState('');
  const [externalSubs, setExternalSubs] = useState<
    { path: string; label: string }[]
  >(savedSubs ?? []);
  const externalSubsRef = useRef(externalSubs);
  externalSubsRef.current = externalSubs;

  // Same player instance can move to the next episode — reload that file's picks.
  useEffect(() => {
    setExternalSubs(savedSubs ?? []);
    // Only on file change; including savedSubs would reset mid-session on every history write.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fileIndex]);
  /** After a SubDL pick, select the matching track once libVLC lists it. */
  const pendingSub = useRef<string | null>(null);
  /** "Cancel" on the up-next card — only for the episode playing now. */
  const [dismissedNext, setDismissedNext] = useState(false);

  const position = useRef(resumeAt ?? 0);
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const tapTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const warnTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  /** When a subtitle track was last chosen, so onError can blame the right thing. */
  const pickedAt = useRef(0);
  const pickedTrack = useRef<number | null>(null);
  /** When a seek was last asked for. Progress reported inside this window is
   *  still the old position and would drag the thumb backwards. */
  const seekAt = useRef(0);
  /** Consecutive recoveries from a dropped stream, so a genuinely dead source
   *  cannot put the player in a reload loop. Reset once playback gets going. */
  const reloads = useRef(0);
  /** Whether the app has been away since playback last ran. */
  const left = useRef(false);
  const nudgeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const nudgeFade = useRef(new Animated.Value(0)).current;
  const videoRef = useRef<VlcPlayerRef>(null);
  const hudTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  /** The back handler is subscribed once and would hold the first render's
   *  value otherwise — same reason as `bump`. */
  const lockRef = useRef(false);
  const { width } = useWindowDimensions();
  const insets = useSafeAreaInsets();

  /**
   * Casting.
   *
   * The receiver pulls the video off the phone over the network, so this needs
   * `lanUrl`; loopback is unreachable from a TV.
   *
   * The visible control has to be the library's own `CastButton` — see the note
   * where it is rendered. Everything else here is plain modules.
   */
  const castClient = useRemoteMediaClient();
  const castStatus = useMediaStatus();
  const casting = !!castClient;
  /** The media has been handed over; without this the load effect would fire
   *  again on every status tick. */
  const castLoaded = useRef<string | null>(null);

  // The parent re-renders while the torrent downloads, so these arrive as fresh
  // closures constantly. Read through refs: an effect that depends on them would
  // otherwise resubscribe the back/AppState listeners several times a second.
  const save = useRef(onSavePosition);
  const leave = useRef(onExit);
  save.current = onSavePosition;
  leave.current = onExit;
  /** Same trick for bumpUi: it changes with `picking`, and the AppState effect
   *  must not resubscribe every time that flips. Assigned below its definition. */
  const bump = useRef<() => void>(() => {});

  // Captured once: the native side reads it when the media loads, and letting
  // it track `position` would restart playback on every checkpoint.
  const startAt = useRef(resumeAt ?? 0).current;

  const exit = useCallback(() => {
    const at = position.current;
    console.log(`[player] exiting at ${Math.round(at)}s`);
    save.current(at);
    leave.current();
  }, []);

  /**
   * Up next.
   *
   * The card comes up over the last stretch of the episode and starts the one
   * after it when the file ends — the same bargain every streaming app makes:
   * carry on unless told otherwise. Cancelling holds until the next file, so
   * the credits can be watched in peace.
   */
  const remaining = duration > 0 ? duration - current : Infinity;
  const upNext = !!next && !!onNext && !dismissedNext && remaining <= UP_NEXT_SECONDS;
  const countdown = Math.max(0, Math.ceil(remaining));

  const playNext = useCallback(() => {
    if (!onNext) return;
    // Recorded before the switch, or the episode just finished loses its place.
    save.current(position.current);
    setDismissedNext(true);
    onNext();
  }, [onNext]);

  // A different file is playing: the card is owed a fresh chance.
  useEffect(() => setDismissedNext(false), [fileIndex]);

  // Hand the stream to the receiver as soon as a device is connected, and
  // silence the local player — two copies playing at once is the one thing
  // nobody wants.
  useEffect(() => {
    if (!castClient || !lanUrl || castLoaded.current === lanUrl) return;
    console.log('[cast] connected — handing over', lanUrl, 'at', position.current);
    castLoaded.current = lanUrl;
    setPaused(true);
    videoRef.current?.pause().catch(() => {});
    castClient
      .loadMedia({
        mediaInfo: { contentUrl: lanUrl, metadata: { type: 'movie', title: title ?? '' } },
        startTime: position.current,
      })
      .then(() => console.log('[cast] receiver accepted the stream'))
      .catch((e: any) => {
        console.warn('[cast] loadMedia failed', e?.message ?? e);
        castLoaded.current = null;
        setFailure({ text: `Cast failed: ${e?.message ?? e}`, fatal: false });
      });
  }, [castClient, lanUrl, title]);

  // Session ended: pick up locally where the TV got to.
  useEffect(() => {
    if (castClient) return;
    if (castLoaded.current === null) return;
    console.log('[cast] session ended — resuming locally');
    castLoaded.current = null;
    setPaused(false);
  }, [castClient]);

  // While casting, the receiver is the source of truth for the clock.
  useEffect(() => {
    if (!casting || castStatus?.streamPosition == null) return;
    position.current = castStatus.streamPosition;
    setCurrent(castStatus.streamPosition);
  }, [casting, castStatus?.streamPosition]);

  /** Transient banner — says what happened without ending playback. */
  const warn = useCallback((message: string) => {
    setFailure({ text: message, fatal: false });
    if (warnTimer.current) clearTimeout(warnTimer.current);
    warnTimer.current = setTimeout(() => setFailure(null), 3000);
  }, []);

  const handOff = useCallback(async () => {
    if (!url) return;
    setPaused(true);
    try {
      // Silence this player before the other one starts. Awaited rather than
      // left to the `paused` prop: that is a round trip through React, and it
      // does not reliably land before Android switches activities — which is
      // exactly when you hear both at once.
      await videoRef.current?.pause();
      await TorrentStream.openExternally(url, position.current);
    } catch (e: any) {
      warn(String(e?.message ?? e));
    }
  }, [url, warn]);

  const saveCopy = useCallback(async () => {
    if (fileIndex < 0) return;
    const name = title ?? 'video.mp4';
    try {
      const path = await TorrentStream.copyTo(fileIndex, name);
      recordDownload(name, 0, path);
      warn(`Saved to Downloads/${name}`);
    } catch (e: any) {
      warn(String(e?.message ?? e));
    }
  }, [fileIndex, title, warn]);

  const searchSubs = useCallback(async () => {
    const q = subQuery.trim() || title;
    const key = SUBDL_KEY;
    if (!q) return;
    if (!key) { warn('This build has no SubDL key'); setSearching(false); return; }
    setSubLoading(true);
    setSubResults([]);
    try {
      // SubDL matches on a title, so the release name has to come off first —
      // "Show.S01E02.1080p.WEB-DL.mkv" as a `film_name` matches nothing. The
      // episode markers it carries are the useful part, and they go in the
      // query separately.
      const at = parseEpisode(q);
      const results = await searchSubtitles(searchableName(q) || q, key, { ...at });
      setSubResults(results.slice(0, 20));
    } catch (e: any) {
      const msg = String(e?.message ?? e);
      if (msg.includes('403') || msg.includes('401')) {
        warn('Invalid API key — check Settings');
      } else {
        warn(msg);
      }
      setSearching(false);
    } finally {
      setSubLoading(false);
    }
  }, [subQuery, title, warn]);

  const applySubtitle = useCallback(async (result: Sub) => {
    try {
      const label = srtDisplayName(result);
      // Native: SubDL serves zips, and unpacking one in JS would need a
      // dependency the app does not have.
      const dest = await TorrentStream.saveSubtitle(
        result.url,
        label.replace(/\.srt$/i, '')
      );
      pendingSub.current = label;
      setExternalSubs((prev) => {
        const next = prev.some((s) => s.path === dest)
          ? prev
          : [...prev, { path: dest, label }];
        onSaveSubs?.(next);
        return next;
      });
      setSearching(false);
      setPicking(true);
      // Picker is the feedback — don't leave a toast that can outlive its timer
      // when bumpUi recreates (picking flips).
      setFailure(null);
      if (warnTimer.current) clearTimeout(warnTimer.current);
      bump.current();
    } catch (e: any) {
      warn(String(e?.message ?? e));
      setSearching(false);
    }
  }, [warn, onSaveSubs]);

  // No setCurrent here: every caller either seeks (which sets it) or is only
  // reachable while the overlay is already up and the clock already live. The
  // one path that needs it — revealing a hidden overlay — does it itself.
  const bumpUi = useCallback(() => {
    setShowUi(true);
    if (hideTimer.current) clearTimeout(hideTimer.current);
    // The picker is a deliberate stop — do not yank it away mid-choice.
    hideTimer.current = setTimeout(() => setShowUi(false), picking ? 12000 : 4000);
  }, [picking]);
  bump.current = bumpUi;

  /** Absolute seek. `duration` is 0 until onLoad, so guard every caller. */
  const seekTo = useCallback(
    (seconds: number) => {
      if (!duration) return;
      const next = Math.max(0, Math.min(duration, seconds));
      seekAt.current = Date.now();
      // Whoever is holding the stream is the one that has to move.
      if (castClient) castClient.seek({ position: next }).catch(() => {});
      else videoRef.current?.seekTo(next);
      position.current = next;
      setCurrent(next);
    },
    [duration, castClient]
  );

  /**
   * Dragging the bar, before letting go. Moves the clock and nothing else —
   * `position.current` stays on real playback, so abandoning a drag does not
   * leave a bogus resume point behind.
   */
  const scrubTo = useCallback(
    (ratio: number) => {
      if (!duration) return;
      seekAt.current = Date.now();
      setCurrent(ratio * duration);
      bumpUi(); // a slow drag must not time the controls out mid-gesture
    },
    [duration, bumpUi]
  );

  const skip = useCallback(
    /** `quiet` is the double-tap: YouTube does not raise the controls for it,
     *  and neither do we — the ripple is the whole acknowledgement. */
    (delta: number, quiet = false) => {
      if (!duration) return;
      seekTo(position.current + delta);
      if (!quiet) bumpUi();

      const side = delta < 0 ? 'left' : 'right';
      setNudge((prev) =>
        prev && prev.side === side
          ? { side, total: prev.total + delta }
          : { side, total: delta }
      );
      // Hold at full strength while the taps keep coming, then fade.
      nudgeFade.setValue(1);
      Animated.timing(nudgeFade, {
        toValue: 0,
        duration: 350,
        delay: 300,
        useNativeDriver: true,
      }).start();
      if (nudgeTimer.current) clearTimeout(nudgeTimer.current);
      nudgeTimer.current = setTimeout(() => setNudge(null), 700);
    },
    [duration, seekTo, bumpUi, nudgeFade]
  );

  /**
   * Tap toggles the overlay, double-tap skips — so the single-tap action has to
   * wait out the double-tap window before it commits, or every skip would also
   * flip the overlay.
   */
  const tapPicture = useCallback(
    (x: number) => {
      // Locked: the only thing a touch does is show the way out.
      if (locked) {
        bumpUi();
        return;
      }
      if (tapTimer.current) {
        clearTimeout(tapTimer.current);
        tapTimer.current = null;
        skip(x < width / 2 ? -SKIP_SECONDS : SKIP_SECONDS, true);
        return;
      }
      tapTimer.current = setTimeout(() => {
        tapTimer.current = null;
        setPicking(false);
        setShowUi((visible) => {
          if (visible) return false;
          setCurrent(position.current);
          if (hideTimer.current) clearTimeout(hideTimer.current);
          hideTimer.current = setTimeout(() => setShowUi(false), 4000);
          return true;
        });
      }, 220);
    },
    [skip, width, locked, bumpUi]
  );


  const showHud = useCallback(
    (next: { icon: IconName; label: string }) => {
      setHud(next);
      if (hudTimer.current) clearTimeout(hudTimer.current);
      hudTimer.current = setTimeout(() => setHud(null), 900);
    },
    []
  );

  const lock = useCallback(
    (next: boolean) => {
      setLocked(next);
      lockRef.current = next;
      // Locking hides the controls at once — leaving them up for four seconds
      // is four seconds in which the thing you locked against can still happen.
      if (next) setShowUi(false);
      else bumpUi();
      showHud({ icon: next ? 'lock' : 'unlock', label: next ? 'Locked' : 'Unlocked' });
    },
    [bumpUi, showHud]
  );

  const cycleScale = useCallback(() => {
    const next = (scaleIdx + 1) % SCALES.length;
    setScaleIdx(next);
    showHud({ icon: 'maximize', label: SCALES[next].label });
    bumpUi();
  }, [scaleIdx, showHud, bumpUi]);

  // Video is a landscape medium; go there on open and hand the phone back on exit.
  useEffect(() => {
    ScreenOrientation.lockAsync(ScreenOrientation.OrientationLock.LANDSCAPE);
    return () => {
      ScreenOrientation.lockAsync(ScreenOrientation.OrientationLock.PORTRAIT_UP);
    };
  }, []);

  useEffect(() => {
    bumpUi();
    return () => {
      // Do not clear warnTimer here — bumpUi changes when the subtitle picker
      // opens, and wiping the timer left "Subtitle ready" on screen forever.
      [hideTimer, tapTimer, nudgeTimer].forEach((t) => {
        if (t.current) clearTimeout(t.current);
      });
    };
  }, [bumpUi]);

  useEffect(() => {
    return () => {
      if (warnTimer.current) clearTimeout(warnTimer.current);
    };
  }, []);

  /**
   * Straight off the native event rather than down through props: the parent
   * stops re-rendering once playback starts, on purpose. Subscribing only while
   * something displays the stats keeps a hidden overlay free of re-renders.
   */
  useEffect(() => {
    if (!showUi && !buffering) return;
    const sub = TorrentStream.addListener('progress', setStats);
    return () => sub.remove();
  }, [showUi, buffering]);

  useEffect(() => {
    const back = BackHandler.addEventListener('hardwareBackPress', () => {
      // Locked means locked: leaving by the back key would defeat the point of
      // guarding against a stray touch in the first place.
      if (lockRef.current) {
        bump.current();
        return true;
      }
      exit();
      return true;
    });
    // Losing focus can mean the process is about to die — checkpoint the resume point.
    const app = AppState.addEventListener('change', (s) => {
      if (s === 'active') {
        // Coming back from another app, the video surface has been destroyed and
        // recreated. A paused player draws nothing into the new one — it has no
        // frame to redraw — so the screen is black with no hint that anything is
        // there. Reopen the stream where we left it and put the controls up, so
        // there is a title, a scrubber and a play button rather than a void.
        if (left.current) {
          left.current = false;
          videoRef.current?.reload(position.current);
          bump.current();
        }
        return;
      }
      left.current = true;
      save.current(position.current);
      // libVLC keeps decoding in the background on its own. react-native-video
      // had playInBackground={false} doing this; without it, leaving the app
      // leaves the audio running.
      //
      // Stopped imperatively first, for the reason handOff gives: the `paused`
      // prop is a round trip through React and does not reliably land before
      // Android suspends us, so the sound carries on into the home screen.
      videoRef.current?.pause().catch(() => {});
      setPaused(true);
    });
    return () => {
      back.remove();
      app.remove();
    };
  }, [exit]);

  return (
    <View style={styles.screen}>
      <StatusBar hidden />

      {!url ? (
        <View style={styles.preparing}>
          {!!title && (
            <Text numberOfLines={2} style={styles.preparingTitle}>
              {title}
            </Text>
          )}
          <ActivityIndicator color="#fff" />
          <Text style={styles.preparingText}>
            {progress ? stageLabel(progress.state) : 'Starting'}
            {progress?.state === 'DOWNLOADING' ? ` ${progress.buffer}%` : ''}
          </Text>
          {!!progress && (progress.seeds > 0 || progress.peers > 0) && (
            <Text style={styles.preparingMeta}>
              {speed(progress.speed)} · {progress.seeds} seeds · {progress.peers} peers
            </Text>
          )}
        </View>
      ) : (
        <View style={StyleSheet.absoluteFill}>
          <VlcPlayer
            ref={videoRef}
          style={StyleSheet.absoluteFill}
            source={url}
            startPosition={startAt}
            paused={paused}
            rate={rate}
            subtitleTrack={subTrack}
            audioTrack={audioTrack}
            subtitleFiles={[
              ...(subtitleFiles ?? []),
              ...externalSubs.map((s) => s.path),
            ]}
            scaleType={SCALES[scaleIdx].type}
            onTracks={({ nativeEvent }) => {
              const externals = externalSubsRef.current;
              const next = nativeEvent.subtitles.map((t, i) => ({
                ...t,
                name: friendlySubName(t, i, externals),
              }));
              setTracks(next);
              setAudioTracks(nativeEvent.audio);
              const want = pendingSub.current;
              if (want) {
                const hit =
                  next.find((t) => t.name === want) ??
                  next.find((t) =>
                    t.name
                      .toLowerCase()
                      .includes(want.replace(/\.srt$/i, '').toLowerCase())
                  ) ??
                  next[next.length - 1];
                if (hit) {
                  pendingSub.current = null;
                  pickedAt.current = Date.now();
                  pickedTrack.current = hit.id;
                  setSubTrack(hit.id);
                }
              }
            }}
            onProgress={({ nativeEvent: { currentTime } }) => {
              // Between asking for a seek and it landing, this still reports
              // where playback was — over a torrent that can be several seconds.
              // Taking it would snap the thumb back, hold it there, then jump.
              // Bounded by time as well as by onSeek, so a seek that never
              // reports completion cannot freeze the clock.
              if (Date.now() - seekAt.current < 1200) return;
            position.current = currentTime;
              // Only the clock and scrubber read this. Re-rendering twice a
              // second behind a hidden overlay is what makes the first tap on
              // the picture feel dead.
              //
              // The exception is the run-up to the end: the up-next card has to
              // appear on its own, and with the overlay down nothing was moving
              // the clock it watches — so it never appeared at all.
              const ending =
                !!next && duration > 0 && duration - currentTime <= UP_NEXT_SECONDS;
              if (showUi || ending) setCurrent(currentTime);

              // Playing steadily again — allow the drop recovery to arm afresh.
              if (currentTime > 5) reloads.current = 0;

            }}
            onLoaded={({ nativeEvent: { duration: d } }) => {
              // A seek is only honoured once the media is open, so the window
              // that suppresses stale progress can close here too.
              seekAt.current = 0;
              setDuration(d);
              onDuration(d);
            }}
            onBuffering={({ nativeEvent: { isBuffering } }) => setBuffering(isBuffering)}
            onEnded={() => {
              // "End of stream" is what the player reports when the HTTP body
              // finishes — which over a torrent can mean the connection dropped
              // twenty minutes into a three-hour film. Only treat it as the end
              // if we are actually near the end.
              const at = position.current;
              // A real end with another episode behind it: carry on rather
              // than dropping back to the library.
              if (duration > 0 && at >= duration - 10 && next && onNext && !dismissedNext) {
                playNext();
                return;
              }
              if (duration > 0 && at < duration - 10) {
                if (reloads.current > 2) {
                  setFailure({ text: 'The stream keeps dropping', fatal: true });
                  return;
                }
                reloads.current += 1;
                warn('Stream dropped — picking it back up');
                videoRef.current?.reload(at);
                return;
              }
              exit();
            }}
            onFailed={({ nativeEvent }) => {
              console.warn('[player] error', nativeEvent.message);
              // A failed subtitle track is not a failed file: selecting one
              // makes the player load it, and a failure there arrives here
              // looking exactly like an unplayable video.
              if (Date.now() - pickedAt.current < 8000) {
                const failed = pickedTrack.current;
                if (failed != null) {
                  setBadTracks((bad) => (bad.includes(failed) ? bad : [...bad, failed]));
                }
                setSubTrack(null);
                warn('That subtitle format is not supported');
                return;
              }
              // Already playing: an error mid-stream means the swarm has not
              // caught up, not that the file is broken.
              if (duration > 0) {
                warn('Playback stalled — waiting for data');
                return;
              }
              setFailure({ text: 'This file could not be played', fatal: true });
            }}
          />

          {/* Tap target for the picture. Always mounted — it sits above the
              video because Android Video eats touches aimed at a parent, and
              swapping it in and out loses the tap that caused the swap.
              The controls are *siblings* on top of it, not children: a child
              that wants the touch responder (the scrubber) cannot win it from
              an ancestor Pressable, which is why dragging the bar did nothing. */}
          <Pressable
            style={[StyleSheet.absoluteFill, showUi && !locked && styles.dim]}
            onPress={(e) => tapPicture(e.nativeEvent.locationX)}
          />

          {/* Locked: one button, and nothing else responds. A tap on the picture
              brings it back for the usual four seconds. */}
          {showUi && locked && (
            <View
              style={[styles.lockDock, { paddingLeft: insets.left }]}
              pointerEvents="box-none"
            >
              <Pressable
                onPress={() => lock(false)}
                hitSlop={16}
                style={styles.lockBtn}
                accessibilityLabel="Unlock controls"
              >
                <Icon name="lock" size={20} color="#fff" />
              </Pressable>
            </View>
          )}

          {showUi && !locked && (
            <View
              // Landscape puts the notch and the gesture bar on the *sides*, so
              // the controls have to inset horizontally, not vertically.
              style={[
                styles.overlay,
                { paddingLeft: insets.left, paddingRight: insets.right },
              ]}
              pointerEvents="box-none"
            >
                <View style={styles.top}>
                  <Pressable onPress={exit} hitSlop={12} style={styles.iconBtn}>
                    <Icon name="arrow-left" size={20} color="#fff" />
                  </Pressable>

                  {!!title && (
                    <Text numberOfLines={1} style={styles.title}>
                      {title}
                    </Text>
                  )}

                  <View style={styles.topRight}>
                    {/* Fixed-width, tabular figures — the numbers change every
                        second and must not shove the buttons around. */}
                    {fileIndex >= 0 && (
                      <View style={styles.stats}>
                        <Icon name="users" size={12} color="#ffffff90" />
                        <Text style={styles.statsText}>
                          {(stats?.seeds ?? 0).toString().padStart(2, ' ')}/
                          {(stats?.peers ?? 0).toString().padStart(2, ' ')}
                        </Text>
                        <Icon name="download" size={12} color="#ffffff90" />
                        <Text style={[styles.statsText, styles.statsSpeed]}>
                          {speed(stats?.speed ?? 0)}
                        </Text>
                      </View>
                    )}

                    {/*
                      The library's own button, not one of ours.

                      On Android `showCastDialog()` is implemented as
                      `RNGoogleCastButtonManager.getCurrent().performClick()` —
                      it does nothing at all unless this native view is mounted,
                      which is why a hand-rolled button could only ever resolve
                      false. Wrapped in the stats pill so it still matches the
                      row above it.
                    */}
                    {!!lanUrl && (
                      <View style={[styles.stats, casting && styles.castOn]}>
                        <CastButton
                          style={styles.castButton}
                          tintColor={casting ? '#7aa2ff' : '#fff'}
                        />
                      </View>
                    )}
                  </View>

                </View>

                <View style={styles.center} pointerEvents="box-none">
                  <Pressable
                    onPress={() => skip(-SKIP_SECONDS)}
                    hitSlop={12}
                    style={styles.skipBtn}
                  >
                    <Icon name="rotate-ccw" size={22} color="#fff" />
                    <Text style={styles.skipText}>{SKIP_SECONDS}</Text>
                  </Pressable>

                  {buffering ? (
                    <View style={styles.playBtn}>
                      <ActivityIndicator color="#fff" />
                    </View>
                  ) : (
                    <Pressable
                      onPress={() => {
                        if (castClient) {
                          (paused ? castClient.play() : castClient.pause()).catch(() => {});
                        }
                        setPaused((p) => !p);
                        bumpUi();
                      }}
                      hitSlop={12}
                      style={styles.playBtn}
                    >
                      <Icon name={paused ? 'play' : 'pause'} size={28} color="#fff" />
                    </Pressable>
                  )}

                  <Pressable
                    onPress={() => skip(SKIP_SECONDS)}
                    hitSlop={12}
                    style={styles.skipBtn}
                  >
                    <Icon name="rotate-cw" size={22} color="#fff" />
                    <Text style={styles.skipText}>{SKIP_SECONDS}</Text>
                  </Pressable>
                </View>

                <View style={styles.bottomWrap}>
                  <View style={styles.bottom}>
                    <Text style={styles.time}>{clock(current)}</Text>
                    <SeekBar
                      value={duration ? current / duration : 0}
                      onScrub={scrubTo}
                      onSeek={(ratio) => {
                        seekTo(ratio * duration);
                        bumpUi();
                      }}
                    />
                    <Text style={styles.time}>{clock(duration)}</Text>
                  </View>

                  {/* Everything that is not playback itself. It was a stack of
                      six buttons crowding the title; down here each one gets a
                      label and the top bar is just context again. */}
                  <View style={styles.actions}>
                    <Action icon="lock" label="Lock" onPress={() => lock(true)} />
                    {/* The card at the end of an episode is easy to miss, and
                        impossible to reach on purpose. This is the same thing,
                        available whenever the controls are up. */}
                    {!!next && !!onNext && (
                      <Action icon="skip-forward" label="Next" onPress={playNext} />
                    )}
                    <Action
                      icon="maximize"
                      label={SCALES[scaleIdx].label}
                      on={scaleIdx !== 0}
                      onPress={cycleScale}
                    />
                    <Action
                      material={subTrack === null ? 'subtitles-off' : 'subtitles'}
                      label="Subtitles"
                      on={subTrack !== null}
                      onPress={() => {
                        setPicking((p) => !p);
                        bumpUi();
                      }}
                    />
                    <Action
                      icon="search"
                      label="Find subs"
                      on={searching}
                      onPress={() => {
                        setSearching((s) => !s);
                        if (!searching) setSubQuery(title ?? '');
                        bumpUi();
                      }}
                    />
                    <Action
                      text={`${rate}x`}
                      label="Speed"
                      on={rate !== 1}
                      onPress={() => {
                        setRate((r) => RATES[(RATES.indexOf(r) + 1) % RATES.length]);
                        bumpUi();
                      }}
                    />
                    {fileIndex >= 0 && (
                      <Action icon="download" label="Save" onPress={saveCopy} />
                    )}
                    <Action
                      icon="external-link"
                      label="Open in"
                      onPress={handOff}
                    />
                  </View>
                </View>
            </View>
          )}

          {picking && (
            <View style={styles.sheet}>
              {/* Audio to the left of subtitles rather than under them: the
                  screen is landscape, so the room is sideways, and a stacked
                  second section pushed the list past the bottom edge. */}
              {audioTracks.length > 1 && (
                <View style={styles.sheetCol}>
                  <Text style={styles.sheetTitle}>Audio</Text>
                  {audioTracks.map((t) => (
                    <TrackOption
                      key={`audio-${t.id}`}
                      label={t.name}
                      active={audioTrack === t.id}
                      onPress={() => {
                        setAudioTrack(t.id);
                        setPicking(false);
                        bumpUi();
                      }}
                    />
                  ))}
                </View>
              )}

              <View
                style={[
                  styles.sheetCol,
                  audioTracks.length > 1 && styles.sheetColSecond,
                ]}
              >
              <Text style={styles.sheetTitle}>Subtitles</Text>
              <TrackOption
                label="Off"
                active={subTrack === null}
                onPress={() => {
                  setSubTrack(null);
                  setPicking(false);
                  bumpUi();
                }}
              />
              {tracks.length === 0 &&
                (subtitleFiles?.length ?? 0) === 0 &&
                externalSubs.length === 0 && (
                  <Text style={styles.noSubs}>
                    No subtitles found.{' '}
                    {SUBDL_KEY
                      ? 'Use the search icon to find subtitles online.'
                      : 'No SubDL key is built into this app, so online search is off.'}
                  </Text>
                )}
              {tracks.map((t) => (
                <TrackOption
                  key={t.id}
                  label={t.name}
                  active={subTrack === t.id}
                  unsupported={badTracks.includes(t.id)}
                  onPress={() => {
                    pickedAt.current = Date.now();
                    pickedTrack.current = t.id;
                    setSubTrack(t.id);
                    setPicking(false);
                    bumpUi();
                  }}
                />
              ))}

              </View>
            </View>
          )}

          {searching && (
            <View style={styles.sheet}>
              <Text style={styles.sheetTitle}>Find subtitles</Text>
              <View style={styles.searchRow}>
                <TextInput
                  style={styles.searchInput}
                  value={subQuery}
                  onChangeText={setSubQuery}
                  placeholder="Search by title..."
                  placeholderTextColor="#ffffff60"
                  onSubmitEditing={searchSubs}
                  returnKeyType="search"
                />
                <Pressable onPress={searchSubs} style={styles.searchBtn}>
                  {subLoading ? (
                    <ActivityIndicator color="#fff" size="small" />
                  ) : (
                    <Icon name="search" size={16} color="#fff" />
                  )}
                </Pressable>
              </View>
              {subResults.length > 0 ? (
                <ScrollView style={styles.resultList}>
                  {subResults.map((r) => {
                    const name = srtDisplayName(r);
                    return (
                      <Pressable
                        key={r.url}
                        onPress={() => applySubtitle(r)}
                        style={styles.subResult}
                      >
                        <View style={styles.subResultBody}>
                          <Text style={styles.subResultTitle}>{name}</Text>
                          <Text style={styles.subResultMeta}>
                            {[r.language || 'EN', 'SRT',
                              r.season != null ? `S${r.season}` : null,
                              r.episode != null ? `E${r.episode}` : null,
                            ]
                              .filter(Boolean)
                              .join(' · ')}
                          </Text>
                        </View>
                        <Icon name="download" size={14} color="#ffffff80" />
                      </Pressable>
                    );
                  })}
                </ScrollView>
              ) : subLoading ? (
                <ActivityIndicator
                  color="#fff"
                  style={{ marginVertical: 20 }}
                />
              ) : subQuery ? (
                <Text style={styles.noResults}>No subtitles found</Text>
              ) : (
                <Text style={styles.noResults}>
                  Enter a search term and press Search
                </Text>
              )}
            </View>
          )}

          {hud !== null && (
            <View pointerEvents="none" style={styles.hud}>
              <Icon name={hud.icon} size={16} color="#fff" />
              <Text style={styles.hudLabel}>{hud.label}</Text>
            </View>
          )}

          {nudge !== null && (
            <Animated.View
              pointerEvents="none"
              style={[
                styles.ripple,
                nudge.side === 'left' ? styles.rippleLeft : styles.rippleRight,
                { opacity: nudgeFade },
              ]}
            >
              <View style={styles.rippleArrows}>
                <Icon
                  name={nudge.side === 'left' ? 'chevron-left' : 'chevron-right'}
                  size={20}
                  color="#fff"
                />
                <View style={styles.rippleArrowSecond}>
                  <Icon
                    name={nudge.side === 'left' ? 'chevron-left' : 'chevron-right'}
                    size={20}
                    color="#fff"
                  />
                </View>
              </View>
              <Text style={styles.rippleText}>
                {Math.abs(nudge.total)} seconds
              </Text>
            </Animated.View>
          )}

          {/* A seek lands in a region the swarm has not sent yet — say so, with
              the numbers that show it is actually moving. The overlay has its
              own spinner, so this one is for when the controls are hidden. */}
          {buffering && !showUi && (
            <View pointerEvents="none" style={styles.waiting}>
              <ActivityIndicator color="#fff" />
              <Text style={styles.waitingText}>Buffering</Text>
              {!!stats && (
                <Text style={styles.waitingMeta}>
                  {speed(stats.speed)} · {stats.seeds} seeds · {stats.peers} peers
                </Text>
              )}
            </View>
          )}
        </View>
      )}

      {/* Outside the overlay: the card is the one thing that has to be there
          whether or not the controls are up, same as every streaming app. */}
      {upNext && (
        <View style={[styles.upNext, { bottom: insets.bottom + 90 }]}>
          <View style={styles.upNextText}>
            <Text style={styles.upNextLabel}>Up next in {countdown}s</Text>
            <Text style={styles.upNextName} numberOfLines={1}>
              {next!.name}
            </Text>
          </View>
          <Pressable
            onPress={() => {
              setDismissedNext(true);
              bumpUi();
            }}
            hitSlop={8}
            style={styles.upNextCancel}
          >
            <Text style={styles.upNextCancelText}>Cancel</Text>
          </Pressable>
          <Pressable onPress={playNext} style={styles.upNextPlay}>
            <Icon name="play" size={14} color="#0b0e14" />
            <Text style={styles.upNextPlayText}>Play</Text>
          </Pressable>
        </View>
      )}

      {!!failure && (
        <View style={[styles.failure, !failure.fatal && styles.notice]}>
          <Text style={styles.failureText}>{failure.text}</Text>
        </View>
      )}
    </View>
  );
}

/** One control in the bottom row: glyph over a label, tinted when active. */
function Action({
  icon,
  material,
  text,
  label,
  on,
  onPress,
}: {
  icon?: IconName;
  /** Feather has no closed-caption glyph — see the MaterialIcons note above. */
  material?: 'subtitles' | 'subtitles-off';
  /** For the speed control, which is a number rather than a symbol. */
  text?: string;
  label: string;
  on?: boolean;
  onPress: () => void;
}) {
  const tint = on ? '#7aa2ff' : '#fff';
  return (
    <Pressable
      onPress={onPress}
      hitSlop={8}
      accessibilityLabel={label}
      style={({ pressed }) => [styles.action, pressed && styles.actionPressed]}
    >
      <View style={styles.actionGlyph}>
        {!!icon && <Icon name={icon} size={18} color={tint} />}
        {!!material && <MaterialIcons name={material} size={19} color={tint} />}
        {!!text && <Text style={[styles.actionText, { color: tint }]}>{text}</Text>}
      </View>
      <Text numberOfLines={1} style={[styles.actionLabel, on && styles.actionLabelOn]}>
        {label}
      </Text>
    </Pressable>
  );
}

function TrackOption({
  label,
  active,
  unsupported,
  sublabel,
  onPress,
}: {
  label: string;
  active: boolean;
  unsupported?: boolean;
  sublabel?: string;
  onPress: () => void;
}) {
  return (
    <Pressable
      onPress={onPress}
      disabled={unsupported}
      style={[styles.option, active && styles.optionOn, unsupported && styles.optionOff]}
    >
      <View style={{ flex: 1, minWidth: 0 }}>
        <Marquee style={styles.optionText}>{label}</Marquee>
        {!!sublabel && <Text style={styles.optionNote}>{sublabel}</Text>}
      </View>
      {unsupported ? (
        <Text style={styles.optionNote}>unsupported</Text>
      ) : active ? (
        <Icon name="check" size={16} color="#fff" />
      ) : null}
    </Pressable>
  );
}

/** SubDL links are .zip; after save they are .srt — show that to the user. */
function srtDisplayName(r: Sub): string {
  const raw = (r.release || r.name || 'subtitle').trim() || 'subtitle';
  const base = raw.replace(/\.zip$/i, '').replace(/\.srt$/i, '');
  return `${base}.srt`;
}

function friendlySubName(
  t: VlcTrack,
  index: number,
  externals: { path: string; label: string }[]
): string {
  const raw = (t.name || '').trim().replace(/\.zip$/i, '.srt');
  const useless =
    !raw ||
    raw === '...' ||
    raw === '—' ||
    /^Track\s*\d*$/i.test(raw) ||
    /^Subtitle\s*\d+$/i.test(raw);

  if (!useless) {
    const byFile = externals.find((e) => {
      const base = e.path.replace(/^file:\/\//, '').split(/[/\\]/).pop() || '';
      const stem = raw.replace(/\.srt$/i, '');
      return !!base && (raw.includes(base) || base.includes(stem));
    });
    return byFile?.label ?? raw;
  }

  // External slaves are usually appended after embedded tracks.
  const embedded = Math.max(0, index + 1 - externals.length);
  const ei = index - embedded;
  if (ei >= 0 && ei < externals.length) return externals[ei].label;
  return externals[externals.length - 1]?.label || `Subtitle ${index + 1}`;
}

/**
 * Thin scrubber — tap anywhere on the track, or drag the head, to seek.
 *
 * Uses the responder props directly rather than a Pressable: it has to follow
 * the finger, and the capture variants make sure no ancestor takes the gesture
 * away mid-drag.
 */
function SeekBar({
  value,
  onScrub,
  onSeek,
}: {
  value: number;
  onScrub: (ratio: number) => void;
  onSeek: (ratio: number) => void;
}) {
  const width = useRef(0);
  const last = useRef(0);
  // Local, so the bar tracks the finger without waiting on a parent render.
  const [dragging, setDragging] = useState<number | null>(null);
  const pct = Math.max(0, Math.min(1, dragging ?? value));

  const track = (x: number) => {
    last.current = width.current ? Math.max(0, Math.min(1, x / width.current)) : 0;
    setDragging(last.current);
    onScrub(last.current);
  };

  return (
    <View
      style={styles.scrub}
      onLayout={(e) => {
        width.current = e.nativeEvent.layout.width;
      }}
      onStartShouldSetResponderCapture={() => true}
      onMoveShouldSetResponderCapture={() => true}
      onResponderGrant={(e) => track(e.nativeEvent.locationX)}
      onResponderMove={(e) => track(e.nativeEvent.locationX)}
      onResponderRelease={() => {
        // Not the release event's own locationX: lifting a finger that has
        // drifted off the bar reports a position the user never saw.
        onSeek(last.current);
        setDragging(null);
      }}
      onResponderTerminate={() => setDragging(null)}
    >
      {/* Padded hit area: a 3px bar is impossible to grab on a phone. */}
      <View style={styles.track}>
        <View style={[styles.fill, { width: `${pct * 100}%` }]} />
      </View>
      <View style={[styles.knob, { left: `${pct * 100}%` }]} />
    </View>
  );
}

const stageLabel = (state: string) =>
  state === 'DOWNLOADING_METADATA'
    ? 'Reading torrent'
    : state === 'CHECKING_FILES' || state === 'CHECKING_RESUME_DATA'
      ? 'Checking files'
      : state === 'DOWNLOADING'
        ? 'Buffering'
        : 'Finding peers';

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: '#000' },
  preparing: {
    ...StyleSheet.absoluteFill,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 12,
  },
  preparingTitle: {
    ...type.title,
    color: '#fff',
    textAlign: 'center',
    marginBottom: space.sm,
    maxWidth: '80%',
  },
  preparingText: { ...type.meta, color: '#ffffffb0' },
  preparingMeta: { ...type.meta, color: '#ffffff70', fontSize: 11 },
  dim: { backgroundColor: '#0006' },
  overlay: { ...StyleSheet.absoluteFill, justifyContent: 'space-between' },
  sheet: {
    position: 'absolute',
    top: 68,
    right: 16,
    flexDirection: 'row',
    alignItems: 'flex-start',
    padding: 6,
    borderRadius: 12,
    backgroundColor: '#000d',
  },
  sheetTitle: {
    ...type.overline,
    color: '#ffffff70',
    paddingHorizontal: 10,
    paddingTop: 6,
    paddingBottom: 4,
  },
  /** One list. Capped so a release with long track names cannot push the
   *  other column off the screen — the names scroll instead. */
  sheetCol: { minWidth: 170, maxWidth: 260, gap: 2 },
  /** The rule between the two, on whichever is on the right. */
  sheetColSecond: {
    marginLeft: 6,
    paddingLeft: 6,
    borderLeftWidth: 1,
    borderLeftColor: '#ffffff1f',
  },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
    paddingHorizontal: 10,
    paddingVertical: 9,
    borderRadius: 8,
  },
  optionOn: { backgroundColor: '#3b6fe0' },
  optionOff: { opacity: 0.45 },
  optionText: { ...type.meta, color: '#fff', flex: 1 },
  optionNote: { ...type.meta, color: '#ffffff90', fontSize: 10 },
  noSubs: {
    ...type.meta,
    color: '#ffffff70',
    paddingHorizontal: 10,
    paddingVertical: 8,
  },
  top: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingTop: 16,
    paddingHorizontal: 16,
  },
  // flexShrink, not flex: the stats pill and the buttons keep their size and a
  // long release name gives way instead of pushing them off the edge.
  title: {
    ...type.label,
    color: '#fff',
    flexShrink: 1,
    marginRight: space.sm,
  },
  // Left edge, vertically centred: away from the transport it replaces, and out
  // of the way of the picture.
  lockDock: {
    ...StyleSheet.absoluteFill,
    alignItems: 'flex-start',
    justifyContent: 'center',
    paddingHorizontal: 20,
  },
  lockBtn: {
    width: 46,
    height: 46,
    borderRadius: radius.pill,
    backgroundColor: '#000a',
    alignItems: 'center',
    justifyContent: 'center',
  },
  bottomWrap: { paddingBottom: 8 },
  actions: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'space-around',
    paddingHorizontal: 12,
    paddingTop: 2,
  },
  action: { alignItems: 'center', gap: 3, minWidth: 62, paddingVertical: 4 },
  actionPressed: { opacity: 0.55 },
  // Fixed height so the "1.5x" label and the icons sit on the same baseline.
  actionGlyph: { height: 20, alignItems: 'center', justifyContent: 'center' },
  actionText: {
    ...type.meta,
    fontSize: 15,
    lineHeight: 19,
    fontFamily: font.medium,
    fontVariant: ['tabular-nums'],
  },
  actionLabel: { ...type.meta, color: '#ffffffa0', fontSize: 10, lineHeight: 13 },
  actionLabelOn: { color: '#7aa2ff' },
  /** Stats pill and the cast button, stacked at the right of the top bar. */
  topRight: { alignItems: 'flex-end', gap: 8 },
  castOn: { backgroundColor: '#7aa2ff26' },
  castOnText: { color: '#7aa2ff' },
  /** Fills the pill, so the whole thing is the tap target. */
  castButton: { width: 24, height: 24 },
  stats: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 12,
    height: 32,
    borderRadius: 16,
    backgroundColor: '#0008',
  },
  statsText: {
    ...type.meta,
    color: '#ffffffd0',
    fontSize: 11,
    fontFamily: font.medium,
    fontVariant: ['tabular-nums'],
  },
  statsSpeed: { minWidth: 62 },
  waiting: {
    ...StyleSheet.absoluteFill,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
  },
  waitingText: { ...type.meta, color: '#ffffffd0' },
  waitingMeta: { ...type.meta, color: '#ffffff80', fontSize: 11 },
  // Centred on the picture, not between the bars. As a flex child of a
  // space-between column it drifted upwards the moment the bottom grew a second
  // row — the transport belongs in the middle of the screen either way.
  center: {
    ...StyleSheet.absoluteFill,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 28,
  },
  bottom: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 16,
    paddingBottom: 4,
  },
  iconBtn: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: '#0008',
    alignItems: 'center',
    justifyContent: 'center',
  },
  skipBtn: {
    width: 52,
    height: 52,
    borderRadius: 26,
    backgroundColor: '#0008',
    alignItems: 'center',
    justifyContent: 'center',
  },
  // Sits inside the rotate arrow, so it reads as "back 10".
  skipText: {
    ...type.meta,
    position: 'absolute',
    color: '#fff',
    fontSize: 9,
    fontFamily: font.medium,
  },
  playBtn: {
    width: 64,
    height: 64,
    borderRadius: 32,
    backgroundColor: '#000a',
    alignItems: 'center',
    justifyContent: 'center',
  },
  time: {
    ...type.meta,
    color: '#fff',
    fontFamily: font.medium,
    fontVariant: ['tabular-nums'],
    minWidth: 48,
  },
  scrub: { flex: 1, height: 40, justifyContent: 'center' },
  track: {
    height: 3,
    borderRadius: 2,
    backgroundColor: '#ffffff40',
    overflow: 'hidden',
  },
  fill: { height: 3, borderRadius: 2, backgroundColor: '#3b6fe0' },
  knob: {
    position: 'absolute',
    width: 12,
    height: 12,
    borderRadius: 6,
    marginLeft: -6,
    backgroundColor: '#3b6fe0',
  },
  // Bottom right, clear of the controls: the same corner every streaming app
  // puts it in, and the one place a thumb is not already resting.
  upNext: {
    position: 'absolute',
    right: 16,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    maxWidth: 420,
    paddingLeft: 14,
    paddingRight: 8,
    paddingVertical: 8,
    borderRadius: 12,
    backgroundColor: '#0b0e14e6',
  },
  upNextText: { flexShrink: 1, gap: 2 },
  upNextLabel: { ...type.meta, fontSize: 11, color: '#ffffffa0' },
  upNextName: { ...type.label, color: '#fff', fontFamily: font.medium },
  upNextCancel: { paddingHorizontal: 8, paddingVertical: 8 },
  upNextCancelText: { ...type.meta, color: '#ffffffa0', fontFamily: font.medium },
  upNextPlay: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 999,
    backgroundColor: '#fff',
  },
  upNextPlayText: { ...type.label, color: '#0b0e14', fontFamily: font.semibold },
  // Under the title rather than mid-screen: at 38% it landed straight on the
  // play button, which is the one thing that must never be covered.
  hud: {
    position: 'absolute',
    alignSelf: 'center',
    top: 76,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    backgroundColor: '#000c',
    paddingHorizontal: 16,
    paddingVertical: 9,
    borderRadius: radius.pill,
  },
  hudLabel: {
    ...type.meta,
    color: '#fff',
    fontSize: 14,
    fontFamily: font.medium,
    fontVariant: ['tabular-nums'],
  },
  // Half the picture, bulging inwards from the edge that was tapped — the outer
  // edge stays flush with the screen, exactly the shape YouTube uses.
  ripple: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    width: '50%',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 2,
    // Darkens the picture rather than washing it out — a white veil disappears
    // over a bright scene, the white arrows on top of it do not.
    backgroundColor: '#00000073',
  },
  rippleLeft: {
    left: 0,
    borderTopRightRadius: 1000,
    borderBottomRightRadius: 1000,
  },
  rippleRight: {
    right: 0,
    borderTopLeftRadius: 1000,
    borderBottomLeftRadius: 1000,
  },
  rippleArrows: { flexDirection: 'row' },
  // Overlapped rather than spaced: two chevrons read as one arrowhead.
  rippleArrowSecond: { marginLeft: -10 },
  rippleText: {
    ...type.meta,
    color: '#fff',
    fontSize: 13,
    fontFamily: font.medium,
    fontVariant: ['tabular-nums'],
  },
  failure: {
    position: 'absolute',
    top: 24,
    alignSelf: 'center',
    backgroundColor: '#d0483c',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 999,
  },
  notice: { backgroundColor: '#000c' },
  failureText: { ...type.meta, color: '#fff' },

  // OpenSubtitles search sheet
  searchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 4,
    marginTop: 4,
  },
  searchInput: {
    flex: 1,
    ...type.meta,
    color: '#fff',
    backgroundColor: '#ffffff14',
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 8,
  },
  searchBtn: {
    width: 36,
    height: 36,
    borderRadius: 8,
    backgroundColor: '#3b6fe0',
    alignItems: 'center',
    justifyContent: 'center',
  },
  resultList: { maxHeight: 280, marginTop: 4 },
  subResult: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 10,
    paddingVertical: 9,
  },
  subResultBody: { flex: 1 },
  subResultTitle: { ...type.meta, color: '#fff' },
  subResultMeta: { ...type.meta, color: '#ffffff60', fontSize: 10, marginTop: 1 },
  noResults: {
    ...type.meta,
    color: '#ffffff60',
    textAlign: 'center',
    paddingVertical: 20,
  },
});
