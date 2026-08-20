import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  BackHandler,
  DeviceEventEmitter,
  Text,
  View,
} from 'react-native';
import {
  VlcPlayer,
  type VlcPlayerRef,
  type VlcScaleType,
  type VlcTracks,
} from '../../modules/vlc-player';
import TorrentStream, { type Progress } from '../../modules/torrent-stream';
import { Focusable } from '../components/Focusable';
import { Icon, type IconName } from '../components/Icon';
import { Sheet, type Option } from '../components/Sheet';
import { searchSubtitles, type Sub } from '../lib/subdl';
import { parseEpisode } from '../lib/search';
import { searchableName } from '../lib/title';
import type { SavedSub } from '../lib/history';
import { clock, speed as speedOf } from '../lib/format';
import { font, radius, SAFE, space, type, useColors, useThemedStyles } from '../theme';

const SUBDL_KEY = process.env.EXPO_PUBLIC_SUBDL_KEY;

/** How long the bar stays up after the last press. */
const HIDE_MS = 6000;
const SKIP = 10;

const RATES = [0.75, 1, 1.25, 1.5, 2];

/** Committed this long after the last press, the way a TV remote expects. */
const SCRUB_COMMIT_MS = 700;

/**
 * How far one press of ‹ or › moves, given how long it has been held.
 *
 * Android reports a repeat count while a key is down, so holding the key walks
 * up these steps — ten seconds for a tap, two minutes once you are clearly
 * looking for a scene rather than skipping an advert.
 */
const stepFor = (repeat: number) =>
  repeat < 3 ? 10 : repeat < 8 ? 30 : repeat < 16 ? 60 : 120;

/** "+1:30" / "−0:20" against where playback actually is. */
function delta(seconds: number) {
  const sign = seconds < 0 ? '−' : '+';
  const n = Math.abs(Math.round(seconds));
  return `${sign}${Math.floor(n / 60)}:${String(n % 60).padStart(2, '0')}`;
}

/**
 * libVLC's fit modes, named and ordered as the phone app names them.
 *
 * Stretch distorts the picture to the screen; crop keeps its shape and cuts
 * the overhang. Both are here because they are the two different things people
 * mean by "fill", and stretch is the one reached for first.
 *
 * The names are libVLC's own enum constants — the native side does
 * `MediaPlayer.ScaleType.valueOf(name)` and falls back to best fit — so these
 * strings have to match exactly.
 */
const SCALES: { value: VlcScaleType; label: string }[] = [
  { value: 'SURFACE_BEST_FIT', label: 'Best fit' },
  { value: 'SURFACE_FILL', label: 'Stretch' },
  { value: 'SURFACE_FIT_SCREEN', label: 'Crop' },
  { value: 'SURFACE_16_9', label: '16:9' },
  { value: 'SURFACE_4_3', label: '4:3' },
  { value: 'SURFACE_ORIGINAL', label: 'Original' },
];

type Panel = 'subtitles' | 'audio' | 'speed' | 'aspect' | 'find' | null;

/** SubDL rows are named by the release they were timed against. */
const subLabel = (s: Sub) => `${s.language} · ${s.release || s.name}`;

function Round({
  icon,
  onPress,
  onFocus,
  first,
  big,
}: {
  icon: IconName;
  onPress: () => void;
  onFocus?: () => void;
  first?: boolean;
  big?: boolean;
}) {
  const colors = useColors();
  const size = big ? 60 : 46;
  return (
    <Focusable
      first={first}
      onPress={onPress}
      onFocus={onFocus}
      scale={1.08}
      style={{
        width: size,
        height: size,
        borderRadius: radius.pill,
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: colors.control,
      }}
      focusedStyle={{ backgroundColor: colors.focus }}
    >
      {(focused) => (
        <Icon
          name={icon}
          size={big ? 26 : 19}
          color={focused ? colors.bg : colors.text}
        />
      )}
    </Focusable>
  );
}

/** A labelled control on the second row — opens a sheet rather than acting. */
function Chip({
  icon,
  label,
  value,
  onPress,
  onFocus,
}: {
  icon: IconName;
  label: string;
  value?: string;
  onPress: () => void;
  onFocus?: () => void;
}) {
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    chip: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.sm,
      height: 36,
      paddingHorizontal: space.md,
      borderRadius: radius.pill,
      backgroundColor: c.control,
    },
    label: { ...type.meta, color: c.muted },
    value: { ...type.meta, fontFamily: font.semibold, color: c.text },
  }));

  return (
    <Focusable invert onPress={onPress} onFocus={onFocus} scale={1.04} style={styles.chip}>
      {(focused) => (
        <>
          <Icon name={icon} size={15} color={focused ? colors.bg : colors.muted} />
          <Text style={[styles.label, focused && { color: colors.bg }]}>{label}</Text>
          {!!value && (
            <Text style={[styles.value, focused && { color: colors.bg }]}>{value}</Text>
          )}
        </>
      )}
    </Focusable>
  );
}

/**
 * Playback on a remote.
 *
 * There is no gesture to scrub with and no key handler in core React Native to
 * read the D-pad directly, so everything is a focusable button: the arrows move
 * between them and OK presses one. Anything that is a *choice* rather than an
 * action — subtitle, audio track, speed, aspect — opens a sheet down the right
 * instead of cycling in place, because cycling gives the viewer no way to see
 * what the options were.
 */
export function PlayerScreen({
  url,
  name,
  searchName,
  imdb,
  kind,
  startAt,
  subtitleFiles,
  savedSubs,
  onSaveSubs,
  progress,
  onPosition,
  onDuration,
  onEnded,
  onExit,
}: {
  url: string | null;
  name: string;
  /** Real title, for looking subtitles up — a release name matches nothing. */
  searchName?: string;
  imdb?: string;
  kind?: 'movie' | 'tv';
  /** Resume point in seconds. */
  startAt: number;
  /** Subtitle files found inside the torrent. */
  subtitleFiles: string[];
  /** Subtitles downloaded on a previous visit to this file. */
  savedSubs?: SavedSub[];
  onSaveSubs?: (subs: SavedSub[]) => void;
  progress: Progress | null;
  onPosition: (seconds: number) => void;
  onDuration: (seconds: number) => void;
  onEnded: () => void;
  onExit: () => void;
}) {
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    screen: { flex: 1, backgroundColor: '#000000' },
    video: { flex: 1 },
    centre: {
      position: 'absolute' as const,
      top: 0,
      left: 0,
      right: 0,
      bottom: 0,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    waiting: { ...type.title, color: '#ffffff', marginTop: space.md },
    waitingMeta: { ...type.meta, color: '#9aa4b8', marginTop: space.xs },
    bar: {
      position: 'absolute' as const,
      left: 0,
      right: 0,
      bottom: 0,
      paddingHorizontal: SAFE.x,
      paddingBottom: SAFE.y,
      paddingTop: space.xxl,
      experimental_backgroundImage: `linear-gradient(to top, rgb(0,0,0) 12%, rgba(0,0,0,0.72) 55%, rgba(0,0,0,0))`,
    },
    titleRow: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
    },
    name: { ...type.title, fontFamily: font.semibold, color: '#ffffff', flex: 1 },
    rate: { ...type.meta, color: '#9aa4b8' },
    // Times sit either side of the slider rather than on a line of their own:
    // one row less, and each number is beside the end of the bar it describes.
    seekRow: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      marginTop: space.sm,
    },
    time: { ...type.meta, color: '#cbd2e0', minWidth: 52 },
    timeEnd: { textAlign: 'right' as const },
    sliderHit: { flex: 1, paddingVertical: space.xs },
    track: {
      height: 5,
      borderRadius: 3,
      backgroundColor: 'rgba(255,255,255,0.22)',
      marginTop: space.sm,
      justifyContent: 'center' as const,
    },
    // How much of the file is actually on disk. On a torrent this is the
    // difference between a seek that plays and a seek that stalls, so it is
    // worth a track of its own.
    have: {
      position: 'absolute' as const,
      left: 0,
      top: 0,
      bottom: 0,
      borderRadius: 3,
      backgroundColor: 'rgba(255,255,255,0.4)',
    },
    played: {
      position: 'absolute' as const,
      left: 0,
      top: 0,
      bottom: 0,
      borderRadius: 3,
      backgroundColor: c.accent,
    },
    knob: {
      position: 'absolute' as const,
      width: 13,
      height: 13,
      borderRadius: radius.pill,
      marginLeft: -6,
      backgroundColor: '#ffffff',
    },
    scrubbing: { backgroundColor: c.accent, transform: [{ scale: 1.25 }] },
    delta: {
      ...type.meta,
      fontFamily: font.semibold,
      color: '#ffffff',
      marginTop: space.xs,
    },
    row: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      marginTop: space.md,
    },
    chips: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      flexWrap: 'wrap' as const,
      gap: space.sm,
      marginTop: space.md,
    },
    toast: {
      position: 'absolute' as const,
      top: SAFE.y,
      right: SAFE.x,
      paddingHorizontal: space.md,
      paddingVertical: space.sm,
      borderRadius: radius.md,
      backgroundColor: c.surface,
    },
    toastText: { ...type.meta, color: c.text },
  }));

  const player = useRef<VlcPlayerRef>(null);
  const [paused, setPaused] = useState(false);
  const [at, setAt] = useState(startAt);
  const [duration, setDuration] = useState(0);
  const [buffering, setBuffering] = useState(true);
  const [controls, setControls] = useState(true);
  const [panel, setPanel] = useState<Panel>(null);
  const [tracks, setTracks] = useState<VlcTracks | null>(null);
  const [subtitle, setSubtitle] = useState<number | null>(null);
  const [audio, setAudio] = useState<number | null>(null);
  const [rate, setRate] = useState(1);
  const [scale, setScale] = useState<VlcScaleType>('SURFACE_BEST_FIT');
  const [note, setNote] = useState<string | null>(null);
  /** Where the ‹ › keys have walked to, not yet committed. Null when idle. */
  const [scrub, setScrub] = useState<number | null>(null);
  /**
   * Which band of the bar the D-pad is in.
   *
   * Android's own TV guidance treats up/down as a "peek": they raise the bar
   * without pausing. Which band you land in decides what left/right then mean —
   * scrubbing on the slider, moving between buttons below it.
   */
  const [row, setRow] = useState<'slider' | 'transport' | 'chips'>('slider');
  /** Where focus should land the next time the bar is raised. */
  const [entry, setEntry] = useState<'slider' | 'transport'>('slider');

  /** Subtitles fetched from SubDL this session, plus any kept from before. */
  const [external, setExternal] = useState<SavedSub[]>(savedSubs ?? []);
  const [found, setFound] = useState<Sub[] | null>(null);
  const [finding, setFinding] = useState(false);
  /** Label of a just-downloaded subtitle, selected as soon as libVLC lists it. */
  const pending = useRef<string | null>(null);

  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const show = useCallback((into: 'slider' | 'transport' = 'transport') => {
    setControls((was) => {
      // Only aim focus when the bar is going up; re-aiming it on every keypress
      // would drag focus back out from under whatever the viewer had reached.
      if (!was) {
        setEntry(into);
        setRow(into);
      }
      return true;
    });
    if (hideTimer.current) clearTimeout(hideTimer.current);
    hideTimer.current = setTimeout(() => setControls(false), HIDE_MS);
  }, []);

  const hide = useCallback(() => {
    if (hideTimer.current) clearTimeout(hideTimer.current);
    setControls(false);
  }, []);

  useEffect(() => {
    show('transport');
    return () => {
      if (hideTimer.current) clearTimeout(hideTimer.current);
    };
  }, [show]);

  // A sheet is a decision in progress; nothing should slide out from under it.
  useEffect(() => {
    if (panel && hideTimer.current) clearTimeout(hideTimer.current);
  }, [panel]);

  const say = useCallback((message: string) => {
    setNote(message);
    setTimeout(() => setNote(null), 3500);
  }, []);

  // BACK closes the sheet, then the controls, and only then leaves. Walking out
  // of a film on one press is the easiest mistake a remote can make.
  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (panel) {
        setPanel(null);
        show();
        return true;
      }
      if (controls) {
        setControls(false);
        return true;
      }
      onExit();
      return true;
    });
    return () => sub.remove();
  }, [panel, controls, onExit, show]);

  const seekBy = useCallback(
    (by: number) => {
      const next = Math.max(0, Math.min(duration || Infinity, at + by));
      setAt(next);
      player.current?.seekTo(next);
      show();
    },
    [at, duration, show]
  );

  // Everything the scrub needs, read from a ref so the key listener can be
  // registered once instead of being torn down on every progress tick.
  const live = useRef({ at, duration, scrub, panel, controls, row });
  live.current = { at, duration, scrub, panel, controls, row };

  const commit = useRef<ReturnType<typeof setTimeout> | null>(null);

  /**
   * Walk the playhead without moving it yet.
   *
   * Seeking on every press would have the torrent re-aim its window a dozen
   * times while someone holds › down, and each of those re-aims throws away the
   * readahead the last one asked for. The position is only committed once the
   * presses stop.
   */
  const nudge = useCallback(
    (seconds: number) => {
      const { at: now, duration: len } = live.current;
      setScrub((prev) => {
        const from = prev ?? now;
        return Math.max(0, Math.min(len > 0 ? len : from + seconds, from + seconds));
      });
      // Deliberately not show(): the button row must stay down while scrubbing.
      // The D-pad is not consumed natively — it cannot be, or focus would stop
      // working everywhere else — so if those buttons were on screen the same
      // press would scrub *and* walk focus along the row.
      if (commit.current) clearTimeout(commit.current);
      commit.current = setTimeout(() => {
        const target = live.current.scrub;
        if (target != null) {
          setAt(target);
          player.current?.seekTo(target);
          setScrub(null);
        }
      }, SCRUB_COMMIT_MS);
    },
    [show]
  );

  // The remote's transport keys, forwarded by MainActivity — core React Native
  // has no TV key API of its own.
  useEffect(() => {
    const sub = DeviceEventEmitter.addListener(
      'tvKey',
      ({ key, repeat }: { key: string; repeat: number }) => {
        // A sheet owns the whole D-pad while it is open.
        if (live.current.panel) return;

        const { controls: up, row: band, scrub: scrubbing } = live.current;

        // Up and down "peek" the bar rather than pausing — Android's TV
        // guidance is explicit that they must not interrupt playback. Which
        // band they land in is what makes left/right mean scrub or navigate.
        if (key === 'up') {
          if (!up) show('slider');
          else if (band === 'slider') hide();
          // From a button row, focus rises to the slider on its own.
          return;
        }
        if (key === 'down') {
          if (!up) show('transport');
          else if (band === 'chips') hide();
          return;
        }

        const arrow = key === 'left' || key === 'right';
        // Left and right seek whenever the bar is down or the slider has focus,
        // and keep seeking mid-scrub so that holding the key works. Anywhere
        // else they belong to focus.
        if (arrow && scrubbing == null && up && band !== 'slider') return;

        const step = stepFor(repeat ?? 0);
        switch (key) {
          case 'left':
          case 'rewind':
            nudge(-step);
            break;
          case 'right':
          case 'forward':
            nudge(step);
            break;
          case 'playPause':
            setPaused((p) => !p);
            show('transport');
            break;
        }
      }
    );
    return () => {
      sub.remove();
      if (commit.current) clearTimeout(commit.current);
    };
  }, [nudge, show]);

  const files = useMemo(
    () => [...subtitleFiles, ...external.map((s) => s.path)],
    [subtitleFiles, external]
  );

  /** Look the film up on SubDL and offer what comes back. */
  const findSubs = useCallback(async () => {
    if (!SUBDL_KEY) {
      say('This build has no SubDL key — set EXPO_PUBLIC_SUBDL_KEY');
      return;
    }
    setFinding(true);
    setFound(null);
    try {
      const at = parseEpisode(name);
      const query = searchName || searchableName(name) || '';
      // The imdb id is the reliable key; a release filename matches nothing.
      const results = await searchSubtitles(imdb ? '' : query, SUBDL_KEY, {
        ...at,
        imdb: imdb || undefined,
        type: at || kind === 'tv' ? 'tv' : kind === 'movie' ? 'movie' : undefined,
      });
      setFound(results.slice(0, 20));
    } catch (e: any) {
      const message = String(e?.message ?? e);
      say(
        message.includes('401') || message.includes('403')
          ? 'SubDL refused the key'
          : message
      );
      setFound([]);
    } finally {
      setFinding(false);
    }
  }, [name, searchName, imdb, kind, say]);

  /** Download one and switch to it the moment libVLC reports it. */
  const applySub = useCallback(
    async (sub: Sub) => {
      setPanel(null);
      show();
      try {
        const label = subLabel(sub);
        // SubDL only ever serves zips, so unpacking happens natively.
        const path = await TorrentStream.saveSubtitle(sub.url, label.replace(/\W+/g, '_'));
        pending.current = label;
        setExternal((prev) => {
          const next = prev.some((s) => s.path === path)
            ? prev
            : [...prev, { path, label }];
          onSaveSubs?.(next);
          return next;
        });
        say(`Added ${label}`);
      } catch (e: any) {
        say(String(e?.message ?? e));
      }
    },
    [onSaveSubs, say, show]
  );

  // A subtitle that has just been downloaded should come on by itself —
  // downloading one and then having to go and select it is a screen too many.
  useEffect(() => {
    if (!pending.current || !tracks?.subtitles?.length) return;
    const wanted = tracks.subtitles[tracks.subtitles.length - 1];
    if (wanted) {
      setSubtitle(wanted.id);
      pending.current = null;
    }
  }, [tracks]);

  const subOptions: Option<number | null>[] = [
    { value: null, label: 'Off' },
    ...(tracks?.subtitles ?? []).map((t) => ({ value: t.id, label: t.name })),
  ];
  const audioOptions: Option<number | null>[] = (tracks?.audio ?? []).map((t) => ({
    value: t.id,
    label: t.name,
  }));

  const subName =
    subtitle == null
      ? 'Off'
      : tracks?.subtitles?.find((t) => t.id === subtitle)?.name ?? 'On';
  const audioName = tracks?.audio?.find((t) => t.id === audio)?.name;
  const scaleName = SCALES.find((s) => s.value === scale)?.label ?? 'Best fit';

  // While the ‹ › keys are walking, the bar shows where they have got to
  // rather than where playback still is.
  const head = scrub ?? at;
  const pct = duration ? Math.max(0, Math.min(100, (head / duration) * 100)) : 0;
  const have = Math.max(0, Math.min(100, progress?.progress ?? 0));

  const waiting = !url || buffering;

  return (
    <View style={styles.screen}>
      {!!url && (
        <VlcPlayer
          ref={player}
          style={styles.video}
          source={url}
          paused={paused}
          rate={rate}
          scaleType={scale}
          startPosition={startAt}
          subtitleTrack={subtitle}
          audioTrack={audio}
          subtitleFiles={files.length ? files : null}
          onLoaded={({ nativeEvent }) => {
            setDuration(nativeEvent.duration);
            onDuration(nativeEvent.duration);

            // Aim the engine at the resume point.
            //
            // The ratio play() was given comes out of history, which only holds
            // one if some earlier session got far enough to record a duration.
            // Without it the engine buffers the head of the file while the
            // player seeks an hour in — so the stream downloads from the start
            // and stalls on data nobody is going to read. The real duration is
            // known now, so put the window where the viewer actually is.
            if (startAt > 0 && nativeEvent.duration > 0) {
              const ratio = Math.min(0.99, startAt / nativeEvent.duration);
              console.log('[player] resuming at', clock(startAt), `(${ratio.toFixed(3)})`);
              TorrentStream.setPosition(ratio).catch(() => {});
            }
          }}
          onProgress={({ nativeEvent }) => {
            setAt(nativeEvent.currentTime);
            onPosition(nativeEvent.currentTime);
          }}
          onBuffering={({ nativeEvent }) => setBuffering(nativeEvent.isBuffering)}
          onTracks={({ nativeEvent }) => {
            setTracks(nativeEvent);
            // libVLC picks an audio track itself; adopt it rather than
            // overriding, or the first frame plays silent.
            setAudio((prev) => prev ?? nativeEvent.audioId ?? nativeEvent.defaultAudio ?? null);
          }}
          onEnded={onEnded}
          onFailed={({ nativeEvent }) => {
            say(nativeEvent.message);
            onExit();
          }}
        />
      )}

      {waiting && (
        <View style={styles.centre} pointerEvents="none">
          <ActivityIndicator size="large" color={colors.accent} />
          <Text style={styles.waiting}>{url ? 'Buffering' : 'Finding peers'}</Text>
          {!!progress && (
            <Text style={styles.waitingMeta}>
              {[
                progress.speed ? speedOf(progress.speed) : null,
                `${progress.peers} peers`,
                progress.seeds ? `${progress.seeds} seeds` : null,
              ]
                .filter(Boolean)
                .join('   ·   ')}
            </Text>
          )}
        </View>
      )}

      {!!note && (
        <View style={styles.toast}>
          <Text style={styles.toastText}>{note}</Text>
        </View>
      )}

      {/* With the bar down the remote has nothing to land on. OK is play/pause
          here, which is what Android's TV guidance asks for — up and down are
          the buttons that merely peek at the bar. It stays mounted through a
          scrub so focus has nowhere to drift to. */}
      {!controls && !panel && (
        <Focusable
          first
          onPress={() => {
            setPaused((p) => !p);
            show('transport');
          }}
          scale={1}
          style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}
        >
          <View style={{ flex: 1 }} />
        </Focusable>
      )}

      {(controls || scrub != null) && (
        <View style={styles.bar}>
          <View style={styles.titleRow}>
            <Text style={styles.name} numberOfLines={1}>
              {name}
            </Text>
            {!!progress?.speed && (
              <Text style={styles.rate}>{speedOf(progress.speed)}</Text>
            )}
          </View>
          {/* The slider is a focus target of its own: landing on it is what
              turns left and right into scrubbing rather than navigation. Its
              focus reads as the knob growing — a ring around a full-width bar
              says nothing. */}
          <View style={styles.seekRow}>
            <Text style={styles.time}>{clock(head)}</Text>
          <Focusable
            first={entry === 'slider'}
            onFocus={() => setRow('slider')}
            onPress={() => {
              setPaused((p) => !p);
              show('slider');
            }}
            scale={1}
            style={styles.sliderHit}
            focusedStyle={{ borderColor: 'transparent' }}
          >
            {(focused) => (
              <View style={styles.track}>
                <View style={[styles.have, { width: `${have}%` }]} />
                <View style={[styles.played, { width: `${pct}%` }]} />
                <View
                  style={[
                    styles.knob,
                    { left: `${pct}%` },
                    (focused || scrub != null) && styles.scrubbing,
                  ]}
                />
              </View>
            )}
          </Focusable>
            <Text style={[styles.time, styles.timeEnd]}>
              {duration ? clock(duration) : '--:--'}
            </Text>
          </View>
          {scrub != null && <Text style={styles.delta}>{delta(scrub - at)}</Text>}

          {controls && scrub == null && (
            <>
          <View style={styles.row}>
            <Round
              icon="rotate-ccw"
              onFocus={() => setRow('transport')}
              onPress={() => seekBy(-SKIP)}
            />
            <Round
              first={entry === 'transport' && !panel}
              big
              icon={paused ? 'play' : 'pause'}
              onFocus={() => setRow('transport')}
              onPress={() => {
                setPaused((p) => !p);
                show('transport');
              }}
            />
            <Round
              icon="rotate-cw"
              onFocus={() => setRow('transport')}
              onPress={() => seekBy(SKIP)}
            />
            <View style={{ flex: 1 }} />
            <Round icon="x" onFocus={() => setRow('transport')} onPress={onExit} />
          </View>

          <View style={styles.chips}>
            <Chip
              icon="type"
              label="Subtitles"
              value={subName}
              onFocus={() => setRow('chips')}
              onPress={() => setPanel('subtitles')}
            />
            <Chip
              icon="volume-2"
              label="Audio"
              value={audioName}
              onFocus={() => setRow('chips')}
              onPress={() => setPanel('audio')}
            />
            <Chip
              icon="fast-forward"
              label="Speed"
              value={`${rate}x`}
              onFocus={() => setRow('chips')}
              onPress={() => setPanel('speed')}
            />
            <Chip
              icon="maximize"
              label="Aspect"
              value={scaleName}
              onFocus={() => setRow('chips')}
              onPress={() => setPanel('aspect')}
            />
            <Chip
              icon="download"
              label="Find subtitles"
              onFocus={() => setRow('chips')}
              onPress={() => {
                setPanel('find');
                findSubs();
              }}
            />
          </View>
            </>
          )}
        </View>
      )}

      {panel === 'subtitles' && (
        <Sheet
          title="Subtitles"
          options={subOptions}
          value={subtitle}
          empty="This file carries no subtitles. Try Find subtitles."
          onPick={(id) => {
            setSubtitle(id);
            setPanel(null);
            show();
          }}
        />
      )}
      {panel === 'audio' && (
        <Sheet
          title="Audio"
          options={audioOptions}
          value={audio}
          empty="No audio tracks reported yet."
          onPick={(id) => {
            setAudio(id);
            setPanel(null);
            show();
          }}
        />
      )}
      {panel === 'speed' && (
        <Sheet
          title="Speed"
          options={RATES.map((r) => ({ value: r, label: `${r}x` }))}
          value={rate}
          onPick={(r) => {
            setRate(r);
            setPanel(null);
            show();
          }}
        />
      )}
      {panel === 'aspect' && (
        <Sheet
          title="Aspect ratio"
          options={SCALES.map((s) => ({ value: s.value, label: s.label }))}
          value={scale}
          onPick={(s) => {
            setScale(s);
            setPanel(null);
            show();
          }}
        />
      )}
      {panel === 'find' && (
        <Sheet
          title="Find subtitles"
          busy={finding}
          options={(found ?? []).map((s, i) => ({
            value: i,
            label: s.language,
            hint: s.release || s.name,
          }))}
          empty="Nothing found for this title."
          onPick={(i) => {
            const hit = found?.[i as number];
            if (hit) applySub(hit);
          }}
        />
      )}
    </View>
  );
}
