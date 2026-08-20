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
import { resumeRatio, useTorrent } from './hooks/useTorrent';
import {
  loadHistory,
  put,
  setDuration,
  setPosition,
  type Item,
} from './lib/history';
import { loadSaved, toggleSaved, type List, type Saved } from './lib/saved';
import { loadTitle, type Episode, type Title } from './lib/meta';
import { episodeTag, type Hit } from './lib/search';
import { pickFile } from './lib/pick';
import { DEFAULTS, loadSettings, saveSettings, type Settings } from './lib/settings';
import { ThemeProvider, font, radius, space, type, useColors, useThemedStyles } from './theme';

SplashScreen.preventAutoHideAsync();

type Tab = 'home' | 'search' | 'settings';

const TABS: { key: Tab; label: string; icon: IconName }[] = [
  { key: 'home', label: 'Home', icon: 'home' },
  { key: 'search', label: 'Search', icon: 'search' },
  { key: 'settings', label: 'Settings', icon: 'sliders' },
];

const RAIL_W = 210;

/**
 * The left rail.
 *
 * A television reads left to right from a fixed seat, so navigation lives down
 * the side where the D-pad can reach it with one press from anywhere on the
 * page — a bottom bar would be four presses down through a rail of posters.
 */
function Nav({ tab, onChange }: { tab: Tab; onChange: (tab: Tab) => void }) {
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    rail: {
      width: RAIL_W,
      paddingVertical: space.xxl,
      paddingLeft: space.lg,
      backgroundColor: c.bg,
      gap: space.sm,
    },
    mark: { ...type.display, color: c.accent, marginBottom: space.xl, paddingLeft: space.md },
    item: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      paddingHorizontal: space.md,
      height: 60,
      borderRadius: radius.md,
    },
    label: { ...type.label, color: c.faint },
  }));

  return (
    <View style={styles.rail}>
      <Text style={styles.mark}>Watchly</Text>
      {TABS.map((t) => {
        const on = t.key === tab;
        return (
          <Focusable
            key={t.key}
            onPress={() => onChange(t.key)}
            scale={1.02}
            style={styles.item}
            focusedStyle={{ backgroundColor: colors.focus }}
          >
            {(focused) => {
              const tint = focused ? colors.bg : on ? colors.accent : colors.faint;
              return (
                <>
                  <Icon name={t.icon} size={24} color={tint} />
                  <Text
                    style={[
                      styles.label,
                      { color: tint },
                      on && { fontFamily: font.semibold },
                    ]}
                  >
                    {t.label}
                  </Text>
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
      play(index, resumeRatio(fresh, index));
    },
    [inspect, play]
  );

  /** Continue watching: the file list is already known, so skip inspect. */
  const resume = useCallback(
    (target: Item) => {
      const index = target.lastFileIndex;
      if (index == null || !target.files?.length) return;
      playKnown(target, index);
    },
    [playKnown]
  );

  const exit = useCallback(() => {
    reset();
    loadHistory().then(setHistory);
  }, [reset]);

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
        startAt={startAt}
        subtitleFiles={subtitleFiles}
        progress={progress}
        onPosition={savePosition}
        onDuration={saveDuration}
        onEnded={exit}
        onExit={exit}
      />
    );
  }

  return (
    <View style={{ flex: 1, flexDirection: 'row', backgroundColor: colors.bg }}>
      <Nav tab={tab} onChange={(next) => { setDetail(null); setTab(next); }} />
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
        ) : (
          <SettingsScreen settings={settings} onChange={onChangeSettings} />
        )}
      </View>
    </View>
  );
}
