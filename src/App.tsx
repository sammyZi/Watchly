import { useCallback, useEffect, useRef, useState } from 'react';
import {
  AppState,
  BackHandler,
  Linking,
  PermissionsAndroid,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
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
import { Icon } from './components/Icon';
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
import { DownloadsScreen, recordDownload } from './screens/DownloadsScreen';
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

  const [torrentMode, setTorrentMode] = useState<'stream' | 'download' | null>(null);
  /** Playing a finished file from Completed — no torrent session involved. */
  const [localPlay, setLocalPlay] = useState<{ url: string; title: string } | null>(
    null
  );

  const [fontsLoaded] = useFonts({
    NotoSans_400Regular,
    NotoSans_500Medium,
    NotoSans_600SemiBold,
    NotoSans_700Bold,
  });

  useEffect(() => {
    loadHistory().then(setHistory);
    loadSettings().then(setSettings);
    // Drop leftover stream pieces from a previous kill (can be multi‑GB).
    TorrentStream.clearCache().catch(() => {});
  }, []);

  useEffect(() => {
    if (fontsLoaded) SplashScreen.hideAsync();
  }, [fontsLoaded]);

  // Leaving the app while idle: clear stream cache. Active downloads keep data
  // until finish/cancel; public Downloads/ copies are never touched.
  useEffect(() => {
    const sub = AppState.addEventListener('change', (state) => {
      if (state !== 'background' && state !== 'inactive') return;
      if (torrentMode === 'download' && phase === 'playing') return;
      if (phase === 'playing' || phase === 'inspecting' || phase === 'choosing') return;
      TorrentStream.clearCache().catch(() => {});
    });
    return () => sub.remove();
  }, [torrentMode, phase]);

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
    async (
      next: Item,
      fileIndex?: number,
      opts?: { keepProgress?: boolean }
    ) => {
      const items = await put({ ...next, at: Date.now() });
      setHistory(items);
      // Merged copy — `next` can arrive without the resume points already stored
      // under this uri, and both the player and the engine seek by them.
      const fresh = items.find((h) => h.uri === next.uri) ?? next;

      // Resume with a known file — skip inspect; native side uses cached meta/peers.
      if (fileIndex != null && fresh.files?.length) {
        playKnown(fresh, fileIndex, opts);
        return;
      }

      const found = await inspect(fresh);
      if (!found) return;

      if (fileIndex != null) {
        play(
          fileIndex,
          opts?.keepProgress ? 0 : resumeRatio(fresh, fileIndex),
          opts
        );
        return;
      }
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

  // Opened from another app: a magnet link, or a .torrent shared in. Only this
  // path leaves the mode unset, so only it gets the stream/download picker.
  useEffect(() => {
    const handle = (incoming: string | null) => {
      if (!incoming || !isTorrentSource(incoming)) return;
      setTorrentMode(null);
      openUri(incoming);
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
      setTorrentMode(null);
      reset();
      return true;
    });
    return () => sub.remove();
  }, [phase, reset]);

  /** Ask for notification permission (Android 13+) then show the download notif. */
  const enableDownloadNotify = useCallback(async () => {
    if (Platform.OS === 'android' && Platform.Version >= 33) {
      await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
      );
    }
    TorrentStream.setNotify(true).catch(() => {});
  }, []);

  // Auto-save when a Downloads-tab transfer hits 100%, then keep it in Completed.
  const downloadDoneRef = useRef(false);
  useEffect(() => {
    if (torrentMode !== 'download' || phase !== 'playing' || !fileName) {
      downloadDoneRef.current = false;
      return;
    }
    const pct = progress?.progress ?? 0;
    if (pct < 100 || downloadDoneRef.current) return;
    downloadDoneRef.current = true;
    const size =
      meta?.files.find((f) => f.index === playingIndex)?.size ??
      item?.files?.find((f) => f.index === playingIndex)?.size ??
      0;
    TorrentStream.copyTo(playingIndex, fileName)
      .then((path) => recordDownload(fileName, size, path))
      .catch(() => {})
      .finally(() => {
        TorrentStream.setNotify(false).catch(() => {});
        reset();
        setTorrentMode(null);
      });
  }, [
    progress?.progress,
    torrentMode,
    phase,
    fileName,
    playingIndex,
    meta,
    item,
    reset,
  ]);

  if (!fontsLoaded) return null;

  if (localPlay) {
    return (
      <PlayerScreen
        title={localPlay.title}
        url={localPlay.url}
        fileIndex={-1}
        progress={null}
        onSavePosition={() => {}}
        onDuration={() => {}}
        onPoster={() => {}}
        onExit={() => setLocalPlay(null)}
      />
    );
  }

  if (phase === 'playing' && item && torrentMode !== 'download') {
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
        fileIndex={playingIndex}
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

  if (phase === 'choosing' && meta && torrentMode === null) {
    const playable = meta.files.filter((f) => f.isVideo).length;
    return (
      <View style={styles.modePicker}>
        <View style={styles.modeCard}>
          <View style={styles.modeMark}>
            <Icon name="layers" size={22} color={colors.accent} />
          </View>
          <Text style={styles.modeTitle} numberOfLines={2}>
            {meta.name}
          </Text>
          <Text style={styles.modeSub}>
            {playable} playable · {meta.files.length} files — how do you want to
            open it?
          </Text>
          <View style={styles.modeButtons}>
            <Pressable
              onPress={() => setTorrentMode('stream')}
              style={({ pressed }) => [styles.modeBtn, pressed && styles.modeBtnPressed]}
            >
              <View style={styles.modeBtnIcon}>
                <Icon name="play" size={20} color={colors.accent} />
              </View>
              <Text style={styles.modeBtnLabel}>Stream</Text>
              <Text style={styles.modeBtnSub}>Watch as pieces arrive</Text>
            </Pressable>
            <Pressable
              onPress={() => setTorrentMode('download')}
              style={({ pressed }) => [styles.modeBtn, pressed && styles.modeBtnPressed]}
            >
              <View style={styles.modeBtnIcon}>
                <Icon name="download" size={20} color={colors.accent} />
              </View>
              <Text style={styles.modeBtnLabel}>Download</Text>
              <Text style={styles.modeBtnSub}>Save fully, then open</Text>
            </Pressable>
          </View>
          <Pressable onPress={reset} hitSlop={10} style={styles.modeCancel}>
            <Text style={styles.modeCancelText}>Cancel</Text>
          </Pressable>
        </View>
      </View>
    );
  }

  if (phase === 'choosing' && meta) {
    return (
      <FilesScreen
        meta={meta}
        mode={torrentMode ?? 'stream'}
        onPlay={(file) => play(file.index, item ? resumeRatio(item, file.index) : 0)}
        onDownload={(file) => {
          setTorrentMode('download');
          setTab('downloads');
          enableDownloadNotify();
          play(file.index, 0, { keepProgress: true });
        }}
        onBack={() => {
          setTorrentMode(null);
          reset();
        }}
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
            onOpenUri={(uri) => {
              setTorrentMode('stream');
              openUri(uri);
            }}
            onPlayFile={(target, index) => open(target, index)}
            onSeeAll={() => setTab('history')}
          />
        )}
        {tab === 'history' && (
          <HistoryScreen
            history={history}
            onOpenItem={(target) => {
              setTorrentMode('stream');
              open(target);
            }}
            onPlayFile={(target, index) => open(target, index)}
            onDownloadFile={(target, index) => {
              setTorrentMode('download');
              setTab('downloads');
              enableDownloadNotify();
              open(target, index, { keepProgress: true });
            }}
            onRemove={askRemove}
            onClear={askClear}
          />
        )}
        {tab === 'downloads' && (
          <DownloadsScreen
            onOpenUri={(uri) => {
              setTorrentMode('download');
              openUri(uri);
            }}
            onPlayFile={async (entry) => {
              try {
                const uri =
                  entry.path ?? (await TorrentStream.downloadUri(entry.name));
                setLocalPlay({ url: uri, title: entry.name });
              } catch (e: any) {
                fail(String(e?.message ?? e));
              }
            }}
            active={
              torrentMode === 'download' && phase === 'playing'
                ? {
                    name:
                      fileName ??
                      meta?.files.find((f) => f.index === playingIndex)?.name ??
                      item?.title ??
                      'Downloading…',
                    size:
                      meta?.files.find((f) => f.index === playingIndex)?.size ??
                      item?.files?.find((f) => f.index === playingIndex)?.size ??
                      0,
                    progress,
                    onPause: () => {
                      TorrentStream.pause().catch(() => {});
                    },
                    onResume: () => {
                      TorrentStream.resume().catch(() => {});
                    },
                    onCancel: () => {
                      TorrentStream.setNotify(false).catch(() => {});
                      setTorrentMode(null);
                      reset();
                    },
                  }
                : null
            }
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
  modePicker: {
    flex: 1,
    backgroundColor: colors.bg,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
  },
  modeCard: {
    width: '100%',
    maxWidth: 360,
    alignItems: 'center',
    gap: 14,
  },
  modeMark: {
    width: 52,
    height: 52,
    borderRadius: 14,
    backgroundColor: colors.accentSoft,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 4,
  },
  modeTitle: {
    fontFamily: 'NotoSans_600SemiBold',
    fontSize: 18,
    color: colors.text,
    textAlign: 'center',
    lineHeight: 26,
  },
  modeSub: {
    fontFamily: 'NotoSans_400Regular',
    fontSize: 13,
    color: colors.muted,
    textAlign: 'center',
    lineHeight: 19,
    marginBottom: 4,
  },
  modeButtons: {
    flexDirection: 'row',
    gap: 12,
    width: '100%',
  },
  modeBtn: {
    flex: 1,
    alignItems: 'center',
    gap: 8,
    paddingVertical: 22,
    paddingHorizontal: 14,
    borderRadius: 18,
    backgroundColor: colors.surface,
  },
  modeBtnPressed: { opacity: 0.7 },
  modeBtnIcon: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: colors.control,
    alignItems: 'center',
    justifyContent: 'center',
  },
  modeBtnLabel: {
    fontFamily: 'NotoSans_600SemiBold',
    fontSize: 16,
    color: colors.text,
  },
  modeBtnSub: {
    fontFamily: 'NotoSans_400Regular',
    fontSize: 11,
    color: colors.faint,
    textAlign: 'center',
    lineHeight: 15,
  },
  modeCancel: {
    paddingVertical: 12,
    paddingHorizontal: 24,
  },
  modeCancelText: {
    fontFamily: 'NotoSans_500Medium',
    fontSize: 14,
    color: colors.muted,
  },
});
