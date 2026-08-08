import { useCallback, useEffect, useState } from 'react';
import { BackHandler, Linking, StyleSheet, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import * as SplashScreen from 'expo-splash-screen';
import {
  NotoSans_400Regular,
  NotoSans_500Medium,
  NotoSans_600SemiBold,
  NotoSans_700Bold,
  useFonts,
} from '@expo-google-fonts/noto-sans';
import TorrentStream from '../modules/torrent-stream';
import { ConfirmModal, type Confirm } from './components/ConfirmModal';
import { TabBar, type TabKey } from './components/TabBar';
import { resumeRatio, useTorrent } from './hooks/useTorrent';
import {
  clearHistory,
  drop,
  loadHistory,
  patch,
  put,
  setDuration,
  setPoster,
  setPosition,
  type Item,
} from './lib/history';
import { DEFAULTS, loadSettings, saveSettings, type Settings } from './lib/settings';
import { isTorrentSource } from './lib/magnet';
import { titleOf } from './lib/title';
import { FilesScreen } from './screens/FilesScreen';
import { HistoryScreen } from './screens/HistoryScreen';
import { PlayerScreen } from './screens/PlayerScreen';
import { SettingsScreen } from './screens/SettingsScreen';
import { StreamScreen } from './screens/StreamScreen';
import { colors } from './theme';

SplashScreen.preventAutoHideAsync();

export default function App() {
  // Provider has to sit above every screen — each one returns from a different
  // branch below, so wrapping here is the only place it covers all of them.
  return (
    <SafeAreaProvider>
      <Shell />
    </SafeAreaProvider>
  );
}

function Shell() {
  const [tab, setTab] = useState<TabKey>('stream');
  const [history, setHistory] = useState<Item[]>([]);
  const [settings, setSettings] = useState<Settings>(DEFAULTS);
  const [confirm, setConfirm] = useState<Confirm | null>(null);

  const torrent = useTorrent(settings);
  const {
    phase,
    item,
    meta,
    url,
    fileName,
    subtitleFiles,
    playingIndex,
    progress,
    fail,
    inspect,
    play,
    playKnown,
    reset,
  } = torrent;

  const [fontsLoaded] = useFonts({
    NotoSans_400Regular,
    NotoSans_500Medium,
    NotoSans_600SemiBold,
    NotoSans_700Bold,
  });

  useEffect(() => {
    loadHistory().then(setHistory);
    loadSettings().then(setSettings);
  }, []);

  useEffect(() => {
    if (fontsLoaded) SplashScreen.hideAsync();
  }, [fontsLoaded]);

  // Remember the whole torrent, not just the file that got opened.
  useEffect(() => {
    if (!item || !meta) return;
    patch(item.uri, {
      torrentName: meta.name,
      files: meta.files.map(({ index, name, size, isVideo, isSubtitle }) => ({
        index,
        name,
        size,
        isVideo,
        isSubtitle,
      })),
    }).then(setHistory);
  }, [item, meta]);

  useEffect(() => {
    if (item && fileName && item.title !== fileName) {
      patch(item.uri, { title: fileName }).then(setHistory);
    }
  }, [item, fileName]);

  /** Resolve the torrent, then either play `fileIndex` or let the user choose. */
  const open = useCallback(
    async (next: Item, fileIndex?: number) => {
      const items = await put({ ...next, at: Date.now() });
      setHistory(items);
      // Merged copy — `next` can arrive without the resume points already stored
      // under this uri, and both the player and the engine seek by them.
      const fresh = items.find((h) => h.uri === next.uri) ?? next;

      // Resume with a known file — skip inspect; native side uses cached meta/peers.
      if (fileIndex != null && fresh.files?.length) {
        playKnown(fresh, fileIndex);
        return;
      }

      const found = await inspect(fresh);
      if (!found) return;

      if (fileIndex != null) {
        play(fileIndex, resumeRatio(fresh, fileIndex));
        return;
      }
      const videos = found.files.filter((f) => f.isVideo);
      if (videos.length === 1) play(videos[0].index, resumeRatio(fresh, videos[0].index));
      else if (videos.length === 0 && found.files.length === 1) play(0, resumeRatio(fresh, 0));
    },
    [inspect, play, playKnown]
  );

  const openUri = useCallback(
    async (uri: string) => {
      // A shared file arrives as a content:// grant that expires — take our own
      // copy before doing anything else, and store *that* path, so the history
      // entry is still openable tomorrow.
      let local = uri;
      try {
        local = await TorrentStream.importSource(uri);
      } catch (e: any) {
        fail(String(e?.message ?? e));
        return;
      }
      open({ uri: local, title: titleOf(uri), at: Date.now() });
    },
    [open, fail]
  );

  // Opened from another app: a magnet link, or a .torrent shared in.
  useEffect(() => {
    const handle = (incoming: string | null) => {
      if (incoming && isTorrentSource(incoming)) openUri(incoming);
    };
    Linking.getInitialURL().then(handle);
    const sub = Linking.addEventListener('url', ({ url }) => handle(url));
    return () => sub.remove();
  }, [openUri]);

  const changeSettings = useCallback((next: Settings) => {
    setSettings(next);
    saveSettings(next);
  }, []);

  const askRemove = (target: Item) =>
    setConfirm({
      title: 'Remove from history?',
      message: target.torrentName ?? target.title,
      icon: 'trash-2',
      confirmLabel: 'Remove',
      destructive: true,
      onConfirm: () => drop(target.uri).then(setHistory),
    });

  const askClear = () =>
    setConfirm({
      title: 'Clear watch history?',
      message: `${history.length} item(s) and their resume points will be deleted.`,
      icon: 'trash-2',
      confirmLabel: 'Clear all',
      destructive: true,
      onConfirm: () => clearHistory().then(setHistory),
    });

  // Back key unwinds the streaming flow; the player handles its own.
  useEffect(() => {
    if (phase === 'idle' || phase === 'playing') return;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      reset();
      return true;
    });
    return () => sub.remove();
  }, [phase, reset]);

  if (!fontsLoaded) return null;

  if (phase === 'playing' && item) {
    // Freshest copy, so subtitle and resume edits reach the player.
    const stored = history.find((h) => h.uri === item.uri) ?? item;

    return (
      <PlayerScreen
        // fileName is what the engine actually opened; before it reports back,
        // the stored file list is the next best thing.
        title={
          fileName ??
          stored.files?.find((f) => f.index === playingIndex)?.name ??
          stored.torrentName ??
          stored.title
        }
        resumeAt={stored.positions?.[playingIndex]}
        url={url}
        subtitleFiles={subtitleFiles}
        openSubtitlesKey={settings.openSubtitlesKey}
        progress={progress}
        onSavePosition={(seconds) =>
          setPosition(stored, playingIndex, seconds).then(setHistory)
        }
        onDuration={(seconds) =>
          setDuration(stored, playingIndex, seconds).then(setHistory)
        }
        onPoster={(uri) => setPoster(stored, playingIndex, uri).then(setHistory)}
        onExit={reset}
      />
    );
  }

  if (phase === 'choosing' && meta) {
    return (
      <FilesScreen
        meta={meta}
        onPlay={(file) => play(file.index, item ? resumeRatio(item, file.index) : 0)}
        onBack={reset}
      />
    );
  }

  return (
    <View style={styles.app}>
      <View style={styles.body}>
        {tab === 'stream' && (
          <StreamScreen
            recent={history}
            error={torrent.error}
            busy={phase === 'inspecting'}
            onOpenUri={openUri}
            onPlayFile={(target, index) => open(target, index)}
            onSeeAll={() => setTab('history')}
          />
        )}
        {tab === 'history' && (
          <HistoryScreen
            history={history}
            onOpenItem={open}
            onPlayFile={(target, index) => open(target, index)}
            onRemove={askRemove}
            onClear={askClear}
          />
        )}
        {tab === 'settings' && (
          <SettingsScreen
            settings={settings}
            onChange={changeSettings}
            onClearHistory={askClear}
            historyCount={history.length}
          />
        )}
      </View>

      <TabBar active={tab} onChange={setTab} />
      <ConfirmModal request={confirm} onClose={() => setConfirm(null)} />
    </View>
  );
}

const styles = StyleSheet.create({
  app: { flex: 1, backgroundColor: colors.bg },
  body: { flex: 1 },
});
