import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, BackHandler, Text, View } from 'react-native';
import { VlcPlayer, type VlcPlayerRef, type VlcTracks } from '../../modules/vlc-player';
import type { Progress } from '../../modules/torrent-stream';
import { Focusable } from '../components/Focusable';
import { Icon, type IconName } from '../components/Icon';
import { ProgressBar } from '../components/ProgressBar';
import { clock, speed as speedOf } from '../lib/format';
import { font, radius, SAFE, space, type, useColors, useThemedStyles } from '../theme';

/** How long the bar stays up after the last press. */
const HIDE_MS = 5000;
const SKIP = 10;

function Control({
  icon,
  label,
  onPress,
  first,
  big,
}: {
  icon: IconName;
  label: string;
  onPress: () => void;
  first?: boolean;
  big?: boolean;
}) {
  const colors = useColors();
  const size = big ? 84 : 68;
  return (
    <Focusable
      first={first}
      onPress={onPress}
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
          size={big ? 36 : 28}
          color={focused ? colors.bg : colors.text}
        />
      )}
    </Focusable>
  );
}

/**
 * Playback on a remote.
 *
 * There is no gesture to scrub with and no key handler in core React Native to
 * read the D-pad directly, so the transport is a row of focusable buttons: the
 * arrows move between them and OK presses one. That is also what every other
 * television app does, which matters more here than inventing something.
 */
export function PlayerScreen({
  url,
  name,
  startAt,
  subtitleFiles,
  progress,
  onPosition,
  onDuration,
  onEnded,
  onExit,
}: {
  url: string | null;
  name: string;
  /** Resume point in seconds. */
  startAt: number;
  subtitleFiles: string[];
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
      paddingBottom: SAFE.y + space.lg,
      paddingTop: space.xxl,
      backgroundColor: c.scrim,
    },
    name: { ...type.title, fontFamily: font.semibold, color: '#ffffff' },
    times: {
      flexDirection: 'row' as const,
      justifyContent: 'space-between' as const,
      marginTop: space.sm,
    },
    time: { ...type.meta, color: '#cbd2e0' },
    row: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.lg,
      marginTop: space.lg,
    },
  }));

  const player = useRef<VlcPlayerRef>(null);
  const [paused, setPaused] = useState(false);
  const [at, setAt] = useState(startAt);
  const [duration, setDuration] = useState(0);
  const [buffering, setBuffering] = useState(true);
  const [controls, setControls] = useState(true);
  const [tracks, setTracks] = useState<VlcTracks | null>(null);
  const [subtitle, setSubtitle] = useState<number | null>(null);

  // Held in a ref as well: the hide timer reads it without re-arming on every
  // progress tick.
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const show = useCallback(() => {
    setControls(true);
    if (hideTimer.current) clearTimeout(hideTimer.current);
    hideTimer.current = setTimeout(() => setControls(false), HIDE_MS);
  }, []);

  useEffect(() => {
    show();
    return () => {
      if (hideTimer.current) clearTimeout(hideTimer.current);
    };
  }, [show]);

  // BACK closes the controls first and leaves on the second press — walking out
  // of a film by pressing back once is the easiest mistake a remote can make.
  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (controls) {
        setControls(false);
        return true;
      }
      onExit();
      return true;
    });
    return () => sub.remove();
  }, [controls, onExit]);

  const seekBy = useCallback(
    (delta: number) => {
      const next = Math.max(0, Math.min(duration || Infinity, at + delta));
      setAt(next);
      player.current?.seekTo(next);
      show();
    },
    [at, duration, show]
  );

  const togglePaused = useCallback(() => {
    setPaused((p) => !p);
    show();
  }, [show]);

  const cycleSubtitle = useCallback(() => {
    const list = tracks?.subtitles ?? [];
    if (!list.length) return;
    const i = list.findIndex((t) => t.id === subtitle);
    setSubtitle(i + 1 >= list.length ? null : list[i + 1].id);
    show();
  }, [tracks, subtitle, show]);

  const waiting = !url || buffering;

  return (
    <View style={styles.screen}>
      {!!url && (
        <VlcPlayer
          ref={player}
          style={styles.video}
          source={url}
          paused={paused}
          startPosition={startAt}
          subtitleTrack={subtitle}
          subtitleFiles={subtitleFiles.length ? subtitleFiles : null}
          onLoaded={({ nativeEvent }) => {
            setDuration(nativeEvent.duration);
            onDuration(nativeEvent.duration);
          }}
          onProgress={({ nativeEvent }) => {
            setAt(nativeEvent.currentTime);
            onPosition(nativeEvent.currentTime);
          }}
          onBuffering={({ nativeEvent }) => setBuffering(nativeEvent.isBuffering)}
          onTracks={({ nativeEvent }) => setTracks(nativeEvent)}
          onEnded={onEnded}
          onFailed={() => onExit()}
        />
      )}

      {waiting && (
        <View style={styles.centre} pointerEvents="none">
          <ActivityIndicator size="large" color={colors.accent} />
          <Text style={styles.waiting}>
            {url ? 'Buffering' : 'Finding peers'}
          </Text>
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

      {/* The surface itself takes focus when the bar is down, so OK brings it
          back up. Without it the remote has nothing to land on at all. */}
      {!controls && (
        <Focusable
          first
          onPress={show}
          scale={1}
          style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}
        >
          <View style={{ flex: 1 }} />
        </Focusable>
      )}

      {controls && (
        <View style={styles.bar}>
          <Text style={styles.name} numberOfLines={1}>
            {name}
          </Text>
          <ProgressBar
            value={duration ? (at / duration) * 100 : 0}
            height={6}
            style={{ marginTop: space.md }}
          />
          <View style={styles.times}>
            <Text style={styles.time}>{clock(at)}</Text>
            <Text style={styles.time}>
              {progress?.speed ? speedOf(progress.speed) : ''}
            </Text>
            <Text style={styles.time}>{duration ? clock(duration) : '--:--'}</Text>
          </View>
          <View style={styles.row}>
            <Control icon="rotate-ccw" label="Back 10s" onPress={() => seekBy(-SKIP)} />
            <Control
              first
              big
              icon={paused ? 'play' : 'pause'}
              label={paused ? 'Play' : 'Pause'}
              onPress={togglePaused}
            />
            <Control icon="rotate-cw" label="Forward 10s" onPress={() => seekBy(SKIP)} />
            {!!tracks?.subtitles?.length && (
              <Control icon="type" label="Subtitles" onPress={cycleSubtitle} />
            )}
            <Control icon="x" label="Stop" onPress={onExit} />
          </View>
        </View>
      )}
    </View>
  );
}
