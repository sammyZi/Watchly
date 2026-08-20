import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { BackHandler, Text, View } from 'react-native';
import * as SplashScreen from 'expo-splash-screen';
import {
  NotoSans_400Regular,
  NotoSans_500Medium,
  NotoSans_600SemiBold,
  NotoSans_700Bold,
  useFonts,
} from '@expo-google-fonts/noto-sans';
import { Focusable } from './components/Focusable';
import { Icon, type IconName } from './components/Icon';
import { HomeScreen } from './screens/HomeScreen';
import { SearchScreen } from './screens/SearchScreen';
import { DetailScreen } from './screens/DetailScreen';
import { PlayerScreen } from './screens/PlayerScreen';
import { SettingsScreen } from './screens/SettingsScreen';
import { LibraryScreen } from './screens/LibraryScreen';
import { resumeRatio, useTorrent } from './hooks/useTorrent';
import {
  clearHistory,
  drop,
  loadHistory,
  patch,
  put,
  setDuration,
  setFileSubs,
  setPosition,
  type Item,
  type SavedSub,
} from './lib/history';
import { loadSaved, toggleSaved, type List, type Saved } from './lib/saved';
import { loadTitle, type Episode, type Title } from './lib/meta';
import type { TorrentMeta } from '../modules/torrent-stream';
import { episodeTag, type Hit } from './lib/search';
import { pickFile } from './lib/pick';
import { DEFAULTS, loadSettings, saveSettings, type Settings } from './lib/settings';
import {
  NAV_OPEN_W,
  NAV_W,
  SAFE,
  ThemeProvider,
  fadeRight,
  font,
  radius,
  space,
  type,
  useColors,
  useThemedStyles,
} from './theme';

SplashScreen.preventAutoHideAsync();

type Tab = 'home' | 'search' | 'library' | 'settings';

const TABS: { key: Tab; label: string; icon: IconName }[] = [
  { key: 'home', label: 'Home', icon: 'home' },
  { key: 'search', label: 'Search', icon: 'search' },
  { key: 'library', label: 'Library', icon: 'clock' },
  { key: 'settings', label: 'Settings', icon: 'sliders' },
];

/**
 * The navigation rail.
 *
 * Floats over the page rather than taking a column out of it, so a backdrop can
 * run the full width of the television behind it — and collapses to icons the
 * moment focus leaves, because a fifth of a 960dp canvas is far too much to
 * spend on three words.
 *
 * It lives down the left because the D-pad reaches it with one press from
 * anywhere on a page of rails; a bottom bar would be four presses down through
 * the posters.
 */
function Nav({ tab, onChange }: { tab: Tab; onChange: (tab: Tab) => void }) {
  const colors = useColors();
  // Counted, not a boolean: blur of the item being left fires before focus of
  // the one being entered, and a boolean makes the rail flicker shut between
  // every pair of items.
  const held = useRef(0);
  const [open, setOpen] = useState(false);

  const styles = useThemedStyles((c) => ({
    rail: {
      position: 'absolute' as const,
      top: 0,
      left: 0,
      bottom: 0,
      paddingTop: SAFE.y,
      paddingLeft: space.md,
      gap: space.xs,
      zIndex: 2,
    },
    // Drawn wider than the rail itself, and wider again once it opens: the
    // labels sit over a backdrop, and a fade that stops at the rail's own edge
    // leaves "Search" reading straight across a hero title.
    veil: {
      position: 'absolute' as const,
      top: 0,
      bottom: 0,
      left: 0,
      experimental_backgroundImage: fadeRight(c.bg),
    },
    mark: {
      ...type.title,
      fontFamily: font.bold,
      color: c.accent,
      marginBottom: space.xl,
      paddingLeft: space.sm,
    },
    item: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      paddingHorizontal: space.sm,
      height: 44,
      borderRadius: radius.md,
    },
    markGap: { height: type.title.lineHeight + space.xl },
    label: { ...type.label, color: c.faint },
  }));

  return (
    <View style={[styles.rail, { width: open ? NAV_OPEN_W : NAV_W }]}>
      <View
        style={[styles.veil, { width: open ? NAV_OPEN_W * 2.4 : NAV_W * 1.6 }]}
        pointerEvents="none"
      />
      {open ? (
        <Text style={styles.mark} numberOfLines={1}>
          Watchly
        </Text>
      ) : (
        // Closed, the icons are the whole rail — a single letter floating above
        // them reads as a rendering fault rather than a wordmark.
        <View style={styles.markGap} />
      )}
      {TABS.map((t) => {
        const on = t.key === tab;
        return (
          <Focusable
            key={t.key}
            onPress={() => onChange(t.key)}
            onFocus={() => {
              held.current += 1;
              setOpen(true);
            }}
            onBlur={() => {
              held.current -= 1;
              // Settles on the next frame, by which time the sibling being
              // entered has already counted itself in.
              setTimeout(() => {
                if (held.current <= 0) setOpen(false);
              }, 0);
            }}
            scale={1}
            style={styles.item}
            focusedStyle={{ backgroundColor: colors.focus }}
          >
            {(focused) => {
              const tint = focused ? colors.bg : on ? colors.accent : colors.faint;
              return (
                <>
                  <Icon name={t.icon} size={20} color={tint} />
                  {open && (
                    <Text
                      style={[
                        styles.label,
                        { color: tint },
                        on && { fontFamily: font.semibold },
                      ]}
                      numberOfLines={1}
                    >
                      {t.label}
                    </Text>
                  )}
                </>
              );
            }}
          </Focusable>
        );
      })}
    </View>
  );
}

export default function App() {
  const [settings, setSettings] = useState<Settings>(DEFAULTS);
  const [ready, setReady] = useState(false);
  const [fontsLoaded] = useFonts({
    NotoSans_400Regular,
    NotoSans_500Medium,
    NotoSans_600SemiBold,
    NotoSans_700Bold,
  });

  useEffect(() => {
    loadSettings()
      .then(setSettings)
      .finally(() => setReady(true));
  }, []);

  const change = useCallback((next: Settings) => {
    setSettings(next);
    saveSettings(next);
  }, []);

  if (!fontsLoaded || !ready) return null;

  // Always dark. A television is watched in a dark room and there is no system
  // light/dark setting on most of them worth following.
  return (
    <ThemeProvider mode="dark" onChangeMode={() => {}}>
      <Shell settings={settings} onChangeSettings={change} />
    </ThemeProvider>
  );
}

function Shell({
  settings,
  onChangeSettings,
}: {
  settings: Settings;
  onChangeSettings: (next: Settings) => void;
}) {
  const colors = useColors();
  const [tab, setTab] = useState<Tab>('home');
  const [history, setHistory] = useState<Item[]>([]);
  const [saved, setSaved] = useState<Saved[]>([]);
  const [detail, setDetail] = useState<Title | null>(null);

  const torrent = useTorrent(settings);
  const { phase, item, meta, url, fileName, subtitleFiles, playingIndex, progress, inspect, play, playKnown, reset } =
    torrent;

  useEffect(() => {
    loadHistory().then(setHistory);
    loadSaved().then(setSaved);
    SplashScreen.hideAsync().catch(() => {});
  }, []);

  // BACK unwinds one layer at a time. The player handles its own — it is a full
  // screen with its own two-step exit.
  useEffect(() => {
    if (phase === 'playing') return;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (detail) {
        setDetail(null);
        return true;
      }
      if (tab !== 'home') {
        setTab('home');
        return true;
      }
      return false;
    });
    return () => sub.remove();
  }, [detail, tab, phase]);

  const openTitle = useCallback((title: Title) => setDetail(title), []);

  const saveTitle = useCallback(
    (title: Title, list: List) => toggleSaved(title, list).then(setSaved),
    []
  );

  /**
   * Record what is inside the torrent.
   *
   * Continue watching needs this: resuming skips the metadata fetch entirely
   * and hands the stored list straight to the engine, so an entry without one
   * has no file to resume.
   */
  const rememberFiles = useCallback(
    async (uri: string, found: TorrentMeta, index: number) => {
      const items = await patch(uri, {
        torrentName: found.name,
        lastFileIndex: index,
        files: found.files.map(({ index: i, name, path, size, isVideo, isSubtitle }) => ({
          index: i,
          name,
          path,
          size,
          isVideo,
          isSubtitle,
        })),
      });
      setHistory(items);
    },
    []
  );

  /**
   * A chosen release becomes a stream.
   *
   * `tag` names the episode inside a season pack; `pickFile` uses it to choose
   * which file of the torrent to fetch, and without one takes the largest
   * video — which for a film is the film.
   */
  const stream = useCallback(
    async (hit: Hit, title: Title, tag?: string, episode?: Episode) => {
      const next: Item = {
        uri: hit.magnet,
        title: hit.name,
        at: Date.now(),
        imdb: title.imdb,
        kind: title.kind,
        poster: title.poster ?? undefined,
        titleName: episode
          ? `${title.title} · ${episodeTag(episode.season, episode.episode)}`
          : title.title,
      };
      const items = await put(next);
      setHistory(items);
      const fresh = items.find((h) => h.uri === next.uri) ?? next;

      const found = await inspect(fresh);
      if (!found) return;
      const index = pickFile(found.files, tag);
      if (index == null) return;

      await rememberFiles(next.uri, found, index);
      play(index, resumeRatio(fresh, index));
    },
    [inspect, play, rememberFiles]
  );

  /**
   * Continue watching, from the home page or the library.
   *
   * The fast path hands the stored file list straight to the engine. Entries
   * written before that list was recorded have none — and silently doing
   * nothing is the worst thing a card can do — so those fall back to resolving
   * the torrent the long way, and record the list on the way through.
   */
  const resume = useCallback(
    async (target: Item) => {
      const known = target.lastFileIndex;
      if (known != null && target.files?.length) {
        playKnown(target, known);
        return;
      }

      const found = await inspect(target);
      if (!found) return;
      const index = known ?? pickFile(found.files);
      if (index == null) return;
      await rememberFiles(target.uri, found, index);
      play(index, resumeRatio(target, index));
    },
    [inspect, play, playKnown, rememberFiles]
  );

  const exit = useCallback(() => {
    reset();
    loadHistory().then(setHistory);
  }, [reset]);

  const removeItem = useCallback((target: Item) => {
    drop(target.uri).then(setHistory);
  }, []);

  const clearAll = useCallback(() => {
    clearHistory().then(setHistory);
  }, []);

  const saveSubs = useCallback(
    (subs: SavedSub[]) => {
      if (item) setFileSubs(item, playingIndex, subs);
    },
    [item, playingIndex]
  );

  const savePosition = useCallback(
    (seconds: number) => {
      if (!item) return;
      setPosition(item, playingIndex, seconds);
    },
    [item, playingIndex]
  );

  const saveDuration = useCallback(
    (seconds: number) => {
      if (!item) return;
      setDuration(item, playingIndex, seconds);
    },
    [item, playingIndex]
  );

  const startAt = useMemo(() => {
    if (!item) return 0;
    return item.positions?.[playingIndex] ?? 0;
  }, [item, playingIndex]);

  if (phase === 'playing') {
    return (
      <PlayerScreen
        url={url}
        name={fileName ?? item?.titleName ?? item?.title ?? ''}
        searchName={item?.titleName}
        imdb={item?.imdb}
        kind={item?.kind}
        startAt={startAt}
        subtitleFiles={subtitleFiles}
        savedSubs={item?.subtitles?.[playingIndex]}
        onSaveSubs={saveSubs}
        progress={progress}
        onPosition={savePosition}
        onDuration={saveDuration}
        onEnded={exit}
        onExit={exit}
      />
    );
  }

  return (
    <View style={{ flex: 1, backgroundColor: colors.bg }}>
      <View style={{ flex: 1 }}>
        {detail ? (
          <DetailScreen
            title={detail}
            saved={saved}
            onSave={saveTitle}
            onStream={stream}
          />
        ) : tab === 'home' ? (
          <HomeScreen
            history={history}
            saved={saved}
            genres={settings.genres}
            onPickTitle={openTitle}
            onResume={resume}
          />
        ) : tab === 'search' ? (
          <SearchScreen onPickTitle={openTitle} />
        ) : tab === 'library' ? (
          <LibraryScreen
            history={history}
            onResume={resume}
            onRemove={removeItem}
            onClear={clearAll}
          />
        ) : (
          <SettingsScreen settings={settings} onChange={onChangeSettings} />
        )}
      </View>
      {/* Last, so it paints over the backdrop rather than beside it. */}
      <Nav
        tab={tab}
        onChange={(next) => {
          setDetail(null);
          setTab(next);
        }}
      />
    </View>
  );
}
