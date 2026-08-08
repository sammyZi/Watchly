import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  AppState,
  BackHandler,
  Pressable,
  StatusBar,
  StyleSheet,
  Text,
  useWindowDimensions,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import * as ScreenOrientation from 'expo-screen-orientation';
// Feather has no closed-caption glyph, and "type" reads as a bare T. The badge
// is the one subtitle symbol everyone recognises, so borrow it from Material.
import MaterialIcons from '@expo/vector-icons/MaterialIcons';
import { VlcPlayer, type VlcPlayerRef, type VlcTrack } from '../../modules/vlc-player';
import TorrentStream, { type Progress } from '../../modules/torrent-stream';
import { Icon } from '../components/Icon';
import { clock, speed } from '../lib/format';
import { font, space, type } from '../theme';

/** How often to refresh the library tile while watching. Cheap now: it copies
 *  the frame already on screen, so it never touches the stream. */
const POSTER_EVERY_MS = 30_000;

const SKIP_SECONDS = 10;
const RATES = [0.75, 1, 1.25, 1.5, 2];

/**
 * Player chrome over a libVLC surface. The controls are ours because no engine
 * ships an overlay that knows about swarm stats or piece-level buffering.
 */
export function PlayerScreen({
  title,
  resumeAt,
  url,
  progress,
  onSavePosition,
  onDuration,
  onPoster,
  onExit,
}: {
  /** What is playing — the file inside the torrent, not the torrent. */
  title?: string;
  resumeAt?: number;
  /** null until the first piece lands — the player mounts before that. */
  url: string | null;
  progress: Progress | null;
  onSavePosition: (seconds: number) => void;
  onDuration: (seconds: number) => void;
  onPoster: (uri: string) => void;
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
  /** Index of the chosen track, null for off. */
  const [subTrack, setSubTrack] = useState<number | null>(null);
  const [picking, setPicking] = useState(false);
  /** Tracks that failed to select. libVLC renders essentially every subtitle
   *  format, so this should stay empty — it is a safety net, not a workaround. */
  const [badTracks, setBadTracks] = useState<number[]>([]);
  /** Brief "+10s" flash so a double-tap in the dark is acknowledged. */
  const [nudge, setNudge] = useState<number | null>(null);
  /** Swarm stats, subscribed to only while they are on screen. */
  const [stats, setStats] = useState<Progress | null>(null);

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
  const posterAt = useRef(0);
  const nudgeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const videoRef = useRef<VlcPlayerRef>(null);
  const { width } = useWindowDimensions();
  const insets = useSafeAreaInsets();

  // The parent re-renders while the torrent downloads, so these arrive as fresh
  // closures constantly. Read through refs: an effect that depends on them would
  // otherwise resubscribe the back/AppState listeners several times a second.
  const save = useRef(onSavePosition);
  const leave = useRef(onExit);
  const poster = useRef(onPoster);
  save.current = onSavePosition;
  leave.current = onExit;
  poster.current = onPoster;

  // Captured once: the native side reads it when the media loads, and letting
  // it track `position` would restart playback on every checkpoint.
  const startAt = useRef(resumeAt ?? 0).current;

  const exit = useCallback(() => {
    const at = position.current;
    console.log(`[player] exiting at ${Math.round(at)}s`);
    save.current(at);
    leave.current();
  }, []);

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

  // No setCurrent here: every caller either seeks (which sets it) or is only
  // reachable while the overlay is already up and the clock already live. The
  // one path that needs it — revealing a hidden overlay — does it itself.
  const bumpUi = useCallback(() => {
    setShowUi(true);
    if (hideTimer.current) clearTimeout(hideTimer.current);
    // The picker is a deliberate stop — do not yank it away mid-choice.
    hideTimer.current = setTimeout(() => setShowUi(false), picking ? 12000 : 4000);
  }, [picking]);

  /** Absolute seek. `duration` is 0 until onLoad, so guard every caller. */
  const seekTo = useCallback(
    (seconds: number) => {
      if (!duration) return;
      const next = Math.max(0, Math.min(duration, seconds));
      seekAt.current = Date.now();
      videoRef.current?.seekTo(next);
      position.current = next;
      setCurrent(next);
    },
    [duration]
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
    (delta: number) => {
      if (!duration) return;
      seekTo(position.current + delta);
      bumpUi();
      setNudge(delta);
      if (nudgeTimer.current) clearTimeout(nudgeTimer.current);
      nudgeTimer.current = setTimeout(() => setNudge(null), 700);
    },
    [duration, seekTo, bumpUi]
  );

  /**
   * Tap toggles the overlay, double-tap skips — so the single-tap action has to
   * wait out the double-tap window before it commits, or every skip would also
   * flip the overlay.
   */
  const tapPicture = useCallback(
    (x: number) => {
      if (tapTimer.current) {
        clearTimeout(tapTimer.current);
        tapTimer.current = null;
        skip(x < width / 2 ? -SKIP_SECONDS : SKIP_SECONDS);
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
    [skip, width]
  );

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
      [hideTimer, tapTimer, nudgeTimer, warnTimer].forEach((t) => {
        if (t.current) clearTimeout(t.current);
      });
    };
  }, [bumpUi]);

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
      exit();
      return true;
    });
    // Losing focus can mean the process is about to die — checkpoint the resume point.
    const app = AppState.addEventListener('change', (s) => {
      if (s === 'active') return;
      save.current(position.current);
      // libVLC keeps decoding in the background on its own. react-native-video
      // had playInBackground={false} doing this; without it, leaving the app
      // leaves the audio running.
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
            onTracks={({ nativeEvent }) => setTracks(nativeEvent.subtitles)}
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
              if (showUi) setCurrent(currentTime);

              // Playing steadily again — allow the drop recovery to arm afresh.
              if (currentTime > 5) reloads.current = 0;

              // Only while actually showing a frame: a copy taken mid-rebuffer
              // is whatever was last drawn, which may be nothing at all.
              if (
                !buffering &&
                currentTime > 5 &&
                Date.now() - posterAt.current > POSTER_EVERY_MS
              ) {
                posterAt.current = Date.now();
                videoRef.current
                  ?.snapshot()
                  .then((uri) => {
                    if (uri) poster.current(uri);
                  })
                  .catch(() => {});
              }
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
            style={[StyleSheet.absoluteFill, showUi && styles.dim]}
            onPress={(e) => tapPicture(e.nativeEvent.locationX)}
          />

          {showUi && (
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

                  {/* Fixed-width, tabular figures — the numbers change every
                      second and must not shove the buttons around. */}
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

                  <View style={styles.topRight}>
                    <Pressable
                      onPress={handOff}
                      hitSlop={12}
                      style={styles.iconBtn}
                      accessibilityLabel="Play in another app"
                    >
                      <Icon name="external-link" size={18} color="#fff" />
                    </Pressable>
                    {tracks.length > 0 && (
                      <Pressable
                        onPress={() => {
                          setPicking((p) => !p);
                          bumpUi();
                        }}
                        hitSlop={12}
                        style={[styles.iconBtn, subTrack !== null && styles.iconBtnOn]}
                      >
                        <MaterialIcons
                          name={subTrack === null ? 'subtitles-off' : 'subtitles'}
                          size={20}
                          color="#fff"
                        />
                      </Pressable>
                    )}
                    <Pressable
                      onPress={() => {
                        setRate((r) => RATES[(RATES.indexOf(r) + 1) % RATES.length]);
                        bumpUi();
                      }}
                      hitSlop={12}
                      style={[styles.iconBtn, styles.rateBtn, rate !== 1 && styles.iconBtnOn]}
                    >
                      <Text style={styles.rateText}>{rate}x</Text>
                    </Pressable>
                  </View>
                </View>

                <View style={styles.center}>
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
            </View>
          )}

          {picking && (
            <View style={styles.sheet}>
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
          )}

          {nudge !== null && (
            <View pointerEvents="none" style={styles.nudge}>
              <Text style={styles.nudgeText}>
                {nudge > 0 ? `+${nudge}s` : `${nudge}s`}
              </Text>
            </View>
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

      {!!failure && (
        <View style={[styles.failure, !failure.fatal && styles.notice]}>
          <Text style={styles.failureText}>{failure.text}</Text>
        </View>
      )}
    </View>
  );
}

function TrackOption({
  label,
  active,
  unsupported,
  onPress,
}: {
  label: string;
  active: boolean;
  unsupported?: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      onPress={onPress}
      disabled={unsupported}
      style={[styles.option, active && styles.optionOn, unsupported && styles.optionOff]}
    >
      <Text numberOfLines={1} style={styles.optionText}>
        {label}
      </Text>
      {unsupported ? (
        <Text style={styles.optionNote}>unsupported</Text>
      ) : (
        active && <Icon name="check" size={14} color="#fff" />
      )}
    </Pressable>
  );
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
    minWidth: 170,
    padding: 6,
    borderRadius: 12,
    backgroundColor: '#000d',
    gap: 2,
  },
  sheetTitle: {
    ...type.overline,
    color: '#ffffff70',
    paddingHorizontal: 10,
    paddingTop: 6,
    paddingBottom: 4,
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
  topRight: { flexDirection: 'row', gap: 10 },
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
  center: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 28 },
  bottom: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 16,
    paddingBottom: 18,
  },
  iconBtn: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: '#0008',
    alignItems: 'center',
    justifyContent: 'center',
  },
  iconBtnOn: { backgroundColor: '#3b6fe0' },
  rateBtn: { width: 48 },
  rateText: {
    ...type.meta,
    color: '#fff',
    fontFamily: font.medium,
    fontVariant: ['tabular-nums'],
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
  nudge: {
    position: 'absolute',
    alignSelf: 'center',
    top: '45%',
    backgroundColor: '#000a',
    paddingHorizontal: 18,
    paddingVertical: 10,
    borderRadius: 999,
  },
  nudgeText: {
    ...type.meta,
    color: '#fff',
    fontSize: 16,
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
});
