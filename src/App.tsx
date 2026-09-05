import { useCallback, useEffect, useMemo, useRef, useState, startTransition } from 'react';
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
import { OfflineBanner } from './components/Offline';
import { TabBar, type TabKey } from './components/TabBar';
import { resumeRatio, useTorrent } from './hooks/useTorrent';
import { useDownloads } from './hooks/useDownloads';
import { type DownloadJob } from './lib/downloadJob';
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
} from './lib/history';
import { DEFAULTS, loadSettings, saveSettings, type Settings } from './lib/settings';
import { linkEpisode, loadEpisodeLinks, type EpisodeLinks } from './lib/watched';
import { isTorrentSource } from './lib/magnet';
import { nextEpisode, pickFile, seasonFiles } from './lib/pick';
import { episodeTag, parseEpisode, searchEpisode } from './lib/search';
import { titleOf } from './lib/title';
import { loadTitle, resolveTitle, type Title } from './lib/meta';
import { isOnline, useOnline } from './lib/net';
import { loadSaved, toggleSaved, type List, type Saved } from './lib/saved';
import { CatalogScreen } from './screens/CatalogScreen';
import { DetailScreen } from './screens/DetailScreen';
import { FilesScreen } from './screens/FilesScreen';
import { HistoryScreen } from './screens/HistoryScreen';
import { DownloadsScreen, recordDownload } from './screens/DownloadsScreen';
import { HomeScreen, type Shelf } from './screens/HomeScreen';
import { PlayerScreen } from './screens/PlayerScreen';
import { SearchScreen } from './screens/SearchScreen';
import { SettingsScreen } from './screens/SettingsScreen';
import { ThemeProvider, useColors, useThemedStyles } from './theme';

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

/**
 * A tab body: mounted on first visit, then kept mounted and merely hidden.
 *
 * Rebuilding a whole screen on every tab tap is what made switching feel slow —
 * and it threw away scroll position with it. Nothing is mounted before it has
 * been asked for, so startup still only pays for the home page.
 */
const tabPageStyles = StyleSheet.create({
  page: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 },
  hidden: { display: 'none' },
});

function TabPage({
  show,
  mounted,
  children,
}: {
  show: boolean;
  mounted: boolean;
  children: React.ReactNode;
}) {
  if (!mounted) return null;
  return (
    <View
      style={[tabPageStyles.page, !show && tabPageStyles.hidden]}
      pointerEvents={show ? 'auto' : 'none'}
    >
      {children}
    </View>
  );
}

function Shell() {
  const [settings, setSettings] = useState<Settings>(DEFAULTS);
  const [settingsReady, setSettingsReady] = useState(false);
  const [fontsLoaded] = useFonts({
    NotoSans_400Regular,
    NotoSans_500Medium,
    NotoSans_600SemiBold,
    NotoSans_700Bold,
  });
  const splashHidden = useRef(false);

  useEffect(() => {
    loadSettings()
      .then((s) => {
        setSettings(s);
        setSettingsReady(true);
      })
      .catch(() => setSettingsReady(true));
  }, []);

  const changeSettings = useCallback((next: Settings) => {
    setSettings(next);
    saveSettings(next);
  }, []);

  const changeThemeMode = useCallback((themeMode: Settings['themeMode']) => {
    startTransition(() => {
      setSettings((prev) => {
        const next = { ...prev, themeMode };
        saveSettings(next);
        return next;
      });
    });
  }, []);

  /** Keep the native splash until fonts, settings, and the first layout are ready
   *  — otherwise the window color flashes between splash and the app. */
  const reveal = useCallback(() => {
    if (splashHidden.current) return;
    splashHidden.current = true;
    SplashScreen.hideAsync().catch(() => {});
  }, []);

  if (!fontsLoaded || !settingsReady) return null;

  return (
    <ThemeProvider mode={settings.themeMode} onChangeMode={changeThemeMode}>
      {/* The banner sits above every screen, including the player and the file
          picker — each of those is its own early return inside ShellApp. */}
      <View style={{ flex: 1 }}>
        <ShellApp
          settings={settings}
          onChangeSettings={changeSettings}
          onReady={reveal}
        />
        <OfflineBanner />
      </View>
    </ThemeProvider>
  );
}

function ShellApp({
  settings,
  onChangeSettings,
  onReady,
}: {
  settings: Settings;
  onChangeSettings: (next: Settings) => void;
  onReady: () => void;
}) {
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    app: { flex: 1, backgroundColor: c.bg },
    body: { flex: 1 },
    /** A full-bleed page drawn over the tabs, which stay mounted beneath it.
     *  Opaque, or the tab it covers shows through. */
    layer: {
      position: 'absolute' as const,
      top: 0,
      left: 0,
      right: 0,
      bottom: 0,
      backgroundColor: c.bg,
    },
    modePicker: {
      flex: 1,
      backgroundColor: c.bg,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      padding: 24,
    },
    modeCard: {
      width: '100%' as const,
      maxWidth: 360,
      alignItems: 'center' as const,
      gap: 14,
    },
    modeMark: {
      width: 52,
      height: 52,
      borderRadius: 14,
      backgroundColor: c.accentSoft,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      marginBottom: 4,
    },
    modeTitle: {
      fontFamily: 'NotoSans_600SemiBold',
      fontSize: 18,
      color: c.text,
      textAlign: 'center' as const,
      lineHeight: 26,
    },
    modeSub: {
      fontFamily: 'NotoSans_400Regular',
      fontSize: 13,
      color: c.muted,
      textAlign: 'center' as const,
      lineHeight: 19,
      marginBottom: 4,
    },
    modeButtons: {
      flexDirection: 'row' as const,
      gap: 12,
      width: '100%' as const,
    },
    modeBtn: {
      flex: 1,
      alignItems: 'center' as const,
      gap: 8,
      paddingVertical: 22,
      paddingHorizontal: 14,
      borderRadius: 18,
      backgroundColor: c.surface,
    },
    modeBtnPressed: { opacity: 0.7 },
    modeBtnIcon: {
      width: 44,
      height: 44,
      borderRadius: 22,
      backgroundColor: c.control,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    modeBtnLabel: {
      fontFamily: 'NotoSans_600SemiBold',
      fontSize: 16,
      color: c.text,
    },
    modeBtnSub: {
      fontFamily: 'NotoSans_400Regular',
      fontSize: 11,
      color: c.faint,
      textAlign: 'center' as const,
      lineHeight: 15,
    },
    modeCancel: {
      paddingVertical: 12,
      paddingHorizontal: 24,
    },
    modeCancelText: {
      fontFamily: 'NotoSans_500Medium',
      fontSize: 14,
      color: c.muted,
    },
  }));

  const [tab, setTab] = useState<TabKey>('home');
  const [history, setHistory] = useState<Item[]>([]);
  /** Favourites and watch-later, both lists in one array. */
  const [saved, setSaved] = useState<Saved[]>([]);
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

  const downloads = useDownloads(
    { addTrackers: settings.addTrackers, prepareMb: settings.prepareMb },
    (name, size, path, extra) => {
      void recordDownload(name, size, path, extra);
    }
  );

  const [torrentMode, setTorrentMode] = useState<'stream' | 'download' | null>(null);
  const torrentModeRef = useRef(torrentMode);
  torrentModeRef.current = torrentMode;
  const phaseRef = useRef(phase);
  phaseRef.current = phase;
  /** Title being browsed. Sits above the tabs, like a pushed route would. */
  const [detail, setDetail] = useState<Title | null>(null);
  /** Shelf opened from a "See all" — under `detail`, so a title picked from the
   *  grid comes back to the grid rather than to the home page. */
  const [shelf, setShelf] = useState<Shelf | null>(null);
  /** Episode id -> the torrent uri it was opened with. */
  const [episodeLinks, setEpisodeLinks] = useState<EpisodeLinks>({});
  /** Playing a finished file from Completed — no torrent session involved. */
  const [localPlay, setLocalPlay] = useState<{ url: string; title: string } | null>(
    null
  );

  useEffect(() => {
    // After the first paint with the real theme bg, drop the splash — never
    // earlier, or the native window color shows through.
    const t = requestAnimationFrame(() => {
      requestAnimationFrame(onReady);
    });
    return () => cancelAnimationFrame(t);
  }, [onReady]);

  useEffect(() => {
    loadHistory().then(setHistory);
    loadEpisodeLinks().then(setEpisodeLinks);
    loadSaved().then(setSaved);
  }, []);

  // Leaving the app while idle: clear stream cache. Active downloads keep data
  // until finish/cancel; public Downloads/ copies are never touched.
  useEffect(() => {
    const sub = AppState.addEventListener('change', (state) => {
      if (state !== 'background' && state !== 'inactive') return;
      if (downloads.live.length) return;
      if (torrentMode === 'download' && phase === 'playing') return;
      if (phase === 'playing' || phase === 'inspecting' || phase === 'choosing') return;
      TorrentStream.clearCache().catch(() => {});
    });
    return () => sub.remove();
  }, [torrentMode, phase, downloads.live.length]);

  // Remember the whole torrent, not just the file that got opened.
  useEffect(() => {
    if (!item || !meta) return;
    patch(item.uri, {
      torrentName: meta.name,
      files: meta.files.map(({ index, name, path, size, isVideo, isSubtitle }) => ({
        index,
        name,
        path,
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
      opts?: {
        keepProgress?: boolean;
        auto?: string | boolean;
        fullFile?: boolean;
        fileIndices?: number[];
        queueIndices?: number[];
        season?: number;
      }
    ) => {
      const items = await put({
        ...next,
        at: Date.now(),
        poster: next.poster ?? detail?.poster ?? undefined,
        imdb: next.imdb ?? detail?.imdb,
        kind: next.kind ?? detail?.kind,
        titleName: next.titleName ?? detail?.title,
      });
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
          {
            ...opts,
            fileIndices: opts?.fileIndices?.length ? opts.fileIndices : [fileIndex],
            queueIndices:
              opts?.queueIndices?.length
                ? opts.queueIndices
                : opts?.fileIndices?.length
                  ? opts.fileIndices
                  : [fileIndex],
          }
        );
        return;
      }

      // Started from a title page: the file was already decided by picking that
      // episode or that release, so the file list is a screen with one sensible
      // answer on it. Take the answer.
      if (opts?.auto) {
        const indices =
          opts.season != null
            ? seasonFiles(found.files, opts.season)
            : null;
        const chosen =
          indices?.length
            ? indices[0]
            : pickFile(
                found.files,
                typeof opts.auto === 'string' ? opts.auto : undefined
              );
        if (chosen != null) {
          const files = indices?.length ? indices : opts.fileIndices;
          play(
            chosen,
            opts.keepProgress ? 0 : resumeRatio(fresh, chosen),
            {
              ...opts,
              season: opts.season,
              fileIndices: files,
              queueIndices: opts.queueIndices?.length ? opts.queueIndices : files,
            }
          );
        }
        // Null means no video inside — the picker is then the only useful thing
        // to show, and `inspect` has already left us on it.
      }
    },
    [inspect, play, playKnown, detail]
  );

  /**
   * How far through each episode you are.
   *
   * The player records position against a torrent uri, so this is the join:
   * episode -> uri -> the history entry the player has been writing to.
   */
  const episodeProgress = useMemo(() => {
    const out: Record<string, number> = {};
    const record = (id: string, position = 0, duration?: number) => {
      if (!duration) return;
      out[id] = Math.max(out[id] ?? 0, Math.min(100, (position / duration) * 100));
    };

    // Anything watched from a title page: the episode it was opened for is
    // recorded against the torrent it came from.
    for (const [id, uri] of Object.entries(episodeLinks)) {
      const entry = history.find((h) => h.uri === uri);
      const index = entry?.lastFileIndex;
      if (!entry || index == null) continue;
      record(id, entry.positions?.[index], entry.durations?.[index]);
    }

    // Everything else, read back out of the file names. A season pack watched
    // straight from the library has no link recorded, but "S01E02" in the file
    // name says exactly which episode a resume point belongs to — and Cinemeta
    // ids episodes as "imdb:season:episode", so it lines up directly.
    for (const entry of history) {
      if (entry.kind !== 'tv' || !entry.imdb) continue;
      for (const file of entry.files ?? []) {
        const at = parseEpisode(file.name);
        if (!at) continue;
        record(
          `${entry.imdb}:${at.season}:${at.episode}`,
          entry.positions?.[file.index],
          entry.durations?.[file.index]
        );
      }
    }
    return out;
  }, [episodeLinks, history]);

  /**
   * How far into a film you are, by IMDb id.
   *
   * The episode map above covers series; this is the same idea for the single
   * file a film's torrent holds, and it is what puts "Continue" on its title
   * page.
   */
  const filmProgress = useMemo(() => {
    const out: Record<string, { position: number; duration: number }> = {};
    for (const entry of history) {
      const index = entry.lastFileIndex;
      if (!entry.imdb || index == null) continue;
      const duration = entry.durations?.[index];
      if (!duration) continue;
      out[entry.imdb] = { position: entry.positions?.[index] ?? 0, duration };
    }
    return out;
  }, [history]);

  /**
   * Fill in artwork for library rows nothing has identified yet.
   *
   * Rows used to show a frame grabbed while watching, which meant every tile
   * was a dark still of wherever you stopped. The real poster has to be looked
   * up once per torrent; after that it is stored on the entry.
   */
  const tried = useRef(new Set<string>());
  const online = useOnline();
  useEffect(() => {
    let live = true;
    (async () => {
      // Artwork already on a saved title — stamp it without waiting for the net.
      for (const h of history) {
        if (/^https?:\/\//i.test(h.poster ?? '')) continue;
        const book = saved.find(
          (s) =>
            (h.imdb && s.imdb === h.imdb) ||
            (!!h.titleName && s.title === h.titleName)
        );
        if (!book?.poster) continue;
        const items = await patch(h.uri, {
          poster: book.poster,
          imdb: h.imdb || book.imdb,
          kind: h.kind || book.kind,
          titleName: h.titleName || book.title,
        });
        if (!live) return;
        setHistory(items);
      }

      if (!online) return;
      const pending = history
        .filter((h) => !/^https?:\/\//i.test(h.poster ?? '') && !tried.current.has(h.uri))
        .slice(0, 12);
      for (const target of pending) {
        tried.current.add(target.uri);
        const found = target.imdb
          ? await loadTitle(target.imdb, target.kind ?? 'movie')
          : await resolveTitle(target.torrentName ?? target.title);
        if (!isOnline()) {
          tried.current.delete(target.uri);
          return;
        }
        if (!live || !found?.poster) continue;
        const items = await patch(target.uri, {
          imdb: found.imdb,
          kind: found.kind,
          poster: found.poster,
          titleName: found.title,
        });
        if (live) setHistory(items);
      }
    })();
    return () => {
      live = false;
    };
  }, [history, saved, online]);

  const openUri = useCallback(
    async (
      uri: string,
      opts?: {
        auto?: string | boolean;
        keepProgress?: boolean;
        fullFile?: boolean;
        imdb?: string;
        kind?: 'movie' | 'tv';
        poster?: string | null;
        titleName?: string;
        season?: number;
        fileIndices?: number[];
      }
    ) => {
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
      open(
        {
          uri: local,
          title: titleOf(uri),
          at: Date.now(),
          imdb: opts?.imdb,
          kind: opts?.kind,
          poster: opts?.poster ?? undefined,
          titleName: opts?.titleName,
        },
        undefined,
        opts
      );
      // Whatever history files this under is what episode progress reads back.
      return local;
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

  // Recorded during render, not in an effect: a tab has to mount in the same
  // commit as the tap, or the first visit shows one blank frame.
  const visited = useRef(new Set<TabKey>(['home'])).current;
  visited.add(tab);

  // Stable identities for the two screens heavy enough to care — with these the
  // hidden tabs re-render on a switch instead of rebuilding.
  const playFile = useCallback(
    (target: Item, index: number) => {
      const book = saved.find((s) => s.imdb && s.imdb === target.imdb);
      const kin = history.find(
        (h) => h.uri !== target.uri && h.imdb === target.imdb && h.poster
      );
      open(
        {
          ...target,
          poster: target.poster || kin?.poster || book?.poster || undefined,
          imdb: target.imdb || book?.imdb,
          kind: target.kind || book?.kind,
          titleName: target.titleName || book?.title,
        },
        index
      );
    },
    [open, saved, history]
  );
  const showHistory = useCallback(() => setTab('history'), []);
  const saveTitle = useCallback(
    (target: Title, list: List) => toggleSaved(target, list).then(setSaved),
    []
  );
  const streamUri = useCallback(
    (uri: string) => {
      setTorrentMode('stream');
      // Straight into the feature — a result tapped in Search is a film, not a
      // folder to go rummaging through.
      openUri(uri, { auto: true });
    },
    [openUri]
  );
  /**
   * A row in the library: carry on with whatever it was playing. The file it
   * last opened is stored, so this resumes rather than asking anything.
   */
  /**
   * A row in the library goes to its title page — artwork, episodes, other
   * releases — rather than straight into the swarm.
   *
   * History only stores release names, so the title has to be looked up, and
   * `resolveTitle` returns nothing rather than guessing. When it finds nothing
   * the row falls back to playing what it last played, which is never wrong.
   */
  const openItem = useCallback(
    async (target: Item) => {
      // Already identified: fetch the full record by id rather than searching
      // by name again — that one is disk-cached and exact.
      const found = target.imdb
        ? ((await loadTitle(target.imdb, target.kind ?? 'movie')) ??
          (await resolveTitle(target.torrentName ?? target.title)))
        : await resolveTitle(target.torrentName ?? target.title);
      if (found) {
        setDetail(found);
        // Store what it turned out to be. Without this the title page has no
        // way back to this entry's resume point, so it offers Play instead of
        // Continue. Also fill a missing poster when the id was already known.
        if (target.imdb !== found.imdb || !target.poster) {
          patch(target.uri, {
            imdb: found.imdb,
            kind: found.kind,
            poster: found.poster ?? undefined,
            titleName: found.title,
          }).then(setHistory);
        }
        return;
      }
      setTorrentMode('stream');
      open(target, target.lastFileIndex, { auto: true });
    },
    [open]
  );
  const playCompleted = useCallback(
    async (entry: { name: string; path?: string }) => {
      try {
        const uri = entry.path ?? (await TorrentStream.downloadUri(entry.name));
        setLocalPlay({ url: uri, title: entry.name });
      } catch (e: any) {
        fail(String(e?.message ?? e));
      }
    },
    [fail]
  );

  const changeSettings = onChangeSettings;

  // These reach memoised screens, so they have to keep the same identity across
  // a render — an inline arrow would defeat the memo on every progress tick.
  const askRemove = useCallback((target: Item) =>
    setConfirm({
      title: 'Remove from history?',
      message: target.torrentName ?? target.title,
      icon: 'trash-2',
      confirmLabel: 'Remove',
      destructive: true,
      onConfirm: () => drop(target.uri).then(setHistory),
    }), []);

  const askClear = useCallback(
    () =>
      setConfirm({
        title: 'Clear watch history?',
        message: `${history.length} item(s) and their resume points will be deleted.`,
        icon: 'trash-2',
        confirmLabel: 'Clear all',
        destructive: true,
        onConfirm: () => clearHistory().then(setHistory),
      }),
    [history.length]
  );

  // Back key unwinds overlays; an active download is not an overlay.
  useEffect(() => {
    if (phase === 'playing' && torrentMode !== 'download') return;
    if (phase === 'idle' && !detail && !shelf) return;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (detail) {
        setDetail(null);
        return true;
      }
      if (shelf) {
        setShelf(null);
        return true;
      }
      if (torrentMode === 'download') return false;
      setTorrentMode(null);
      reset();
      return true;
    });
    return () => sub.remove();
  }, [phase, torrentMode, detail, shelf, reset]);

  /**
   * Ask for notification permission (Android 13+), then start the foreground
   * service either way.
   *
   * `setNotify` is what keeps the download alive once the screen locks — it
   * starts the foreground service and holds the wake lock, which Android
   * grants independently of notification permission. Bailing out on a denied
   * permission used to skip that call entirely, so declining "Allow
   * notifications" (the common reflex) silently meant the download stopped
   * the moment the app left the foreground. The only thing permission
   * actually gates is whether the progress notification is visible.
   */
  const enableDownloadNotify = useCallback(async () => {
    if (Platform.OS === 'android' && Platform.Version >= 33) {
      await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
      ).catch(() => {});
    }
    TorrentStream.setNotify(true).catch(() => {});
  }, []);

  const beginDownload = useCallback(
    (job: DownloadJob) => {
      setTab('downloads');
      setDetail(null);
      setShelf(null);
      enableDownloadNotify();
      downloads.start(job);
    },
    [downloads, enableDownloadNotify]
  );

  const downloadFile = useCallback(
    (target: Item, index: number) => {
      beginDownload({
        uri: target.uri,
        fileIndex: index,
        fileIndices: [index],
        title: target.title,
        titleName: target.titleName,
        poster: target.poster,
        imdb: target.imdb,
        kind: target.kind,
      });
    },
    [beginDownload]
  );

  const downloadSeason = useCallback(
    (target: Item, indices?: number[]) => {
      const list = indices?.length
        ? indices
        : seasonFiles(target.files ?? []);
      if (!list.length) return;
      beginDownload({
        uri: target.uri,
        fileIndex: list[0],
        fileIndices: list,
        queueIndices: list,
        title: target.title,
        titleName: target.titleName,
        poster: target.poster,
        imdb: target.imdb,
        kind: target.kind,
      });
    },
    [beginDownload]
  );

  if (localPlay) {
    return (
      <PlayerScreen
        title={localPlay.title}
        url={localPlay.url}
        fileIndex={-1}
        progress={null}
        onSavePosition={() => {}}
        onDuration={() => {}}
        onExit={() => setLocalPlay(null)}
      />
    );
  }

  if (phase === 'playing' && item && torrentMode !== 'download') {
    // Freshest copy, so subtitle and resume edits reach the player.
    const stored = history.find((h) => h.uri === item.uri) ?? item;
    // Inside this torrent first — see `nextEpisode`; the engine's own file list
    // wins over the stored one while it is up.
    const following = nextEpisode(meta?.files ?? stored.files ?? [], playingIndex);

    /**
     * Up next, when the torrent holds this episode and nothing else.
     *
     * That is the normal case for anything started from a title page: the
     * release picked was the single episode asked for, so "next" is a
     * different torrent entirely and has to be found first. The title page it
     * was opened from is still in state, which is where the episode order and
     * the show's name come from.
     */
    const playingName =
      fileName ?? stored.files?.find((f) => f.index === playingIndex)?.name ?? '';
    const at = parseEpisode(playingName);
    const episodeList = detail?.kind === 'tv' ? (detail.episodes ?? []) : [];
    const atIndex = at
      ? episodeList.findIndex((e) => e.season === at.season && e.episode === at.episode)
      : -1;
    const nextEp = atIndex >= 0 ? (episodeList[atIndex + 1] ?? null) : null;

    const upNext = following
      ? {
          name: following.name,
          go: () => open(stored, following.index, { keepProgress: true }),
        }
      : nextEp && detail
        ? {
            name: `${episodeTag(nextEp.season, nextEp.episode)} · ${nextEp.name}`,
            go: async () => {
              const hits = await searchEpisode(
                detail.title,
                nextEp.season,
                nextEp.episode,
                undefined,
                detail.imdb
              ).catch(() => []);
              if (!hits.length) {
                fail(`No releases found for ${episodeTag(nextEp.season, nextEp.episode)}`);
                return;
              }
              setTorrentMode('stream');
              const local = await openUri(hits[0].magnet, {
                auto: episodeTag(nextEp.season, nextEp.episode),
                keepProgress: true,
                imdb: detail.imdb,
                kind: detail.kind,
                poster: detail.poster,
                titleName: detail.title,
              });
              if (local) linkEpisode(nextEp.id, local).then(setEpisodeLinks);
            },
          }
        : null;

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
        metaTitle={detail?.title ?? stored.titleName}
        imdb={detail?.imdb ?? stored.imdb}
        kind={detail?.kind ?? stored.kind}
        resumeAt={stored.positions?.[playingIndex]}
        url={url}
        lanUrl={torrent.lanUrl}
        subtitleFiles={subtitleFiles}
        savedSubs={stored.subtitles?.[playingIndex]}
        fileIndex={playingIndex}
        next={upNext && { name: upNext.name }}
        // Starts from the top: an episode reached by finishing the one before
        // it has no resume point worth honouring.
        onNext={upNext ? upNext.go : undefined}
        progress={progress}
        onSavePosition={(seconds) =>
          setPosition(stored, playingIndex, seconds).then(setHistory)
        }
        onDuration={(seconds) =>
          setDuration(stored, playingIndex, seconds).then(setHistory)
        }
        onSaveSubs={(subs) =>
          setFileSubs(stored, playingIndex, subs).then(setHistory)
        }
        onDownload={() => downloadFile(stored, playingIndex)}
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

  // File picker for multi-file torrents. While a download is already running,
  // stay on the Downloads tab — do not cover the progress card with this list.
  if (phase === 'choosing' && meta && torrentMode !== 'download') {
    return (
      <FilesScreen
        meta={meta}
        mode={torrentMode ?? 'stream'}
        onPlay={(file) => play(file.index, item ? resumeRatio(item, file.index) : 0)}
        onDownload={(file) => {
          beginDownload({
            uri: item?.uri ?? '',
            fileIndex: file.index,
            fileIndices: [file.index],
            title: meta.name,
            titleName: item?.titleName,
            poster: item?.poster,
            imdb: item?.imdb,
            kind: item?.kind,
          });
          setTorrentMode(null);
          reset(false);
        }}
        onDownloadAll={() => {
          const indices = seasonFiles(meta.files);
          if (!indices.length) return;
          beginDownload({
            uri: item?.uri ?? '',
            fileIndex: indices[0],
            fileIndices: indices,
            queueIndices: indices,
            title: meta.name,
            titleName: item?.titleName,
            poster: item?.poster,
            imdb: item?.imdb,
            kind: item?.kind,
          });
          setTorrentMode(null);
          reset(false);
        }}
        onBack={() => {
          setTorrentMode(null);
          reset();
        }}
      />
    );
  }

  // Need a file pick for a download (auto could not decide): show the list, then
  // return to Downloads once a file is chosen.
  if (phase === 'choosing' && meta && torrentMode === 'download') {
    return (
      <FilesScreen
        meta={meta}
        mode="download"
        onPlay={(file) => {
          beginDownload({
            uri: item?.uri ?? '',
            fileIndex: file.index,
            fileIndices: [file.index],
            title: meta.name,
            titleName: item?.titleName,
            poster: item?.poster,
            imdb: item?.imdb,
            kind: item?.kind,
          });
          setTorrentMode(null);
          reset(false);
        }}
        onDownload={(file) => {
          beginDownload({
            uri: item?.uri ?? '',
            fileIndex: file.index,
            fileIndices: [file.index],
            title: meta.name,
            titleName: item?.titleName,
            poster: item?.poster,
            imdb: item?.imdb,
            kind: item?.kind,
          });
          setTorrentMode(null);
          reset(false);
        }}
        onDownloadAll={() => {
          const indices = seasonFiles(meta.files);
          if (!indices.length) return;
          beginDownload({
            uri: item?.uri ?? '',
            fileIndex: indices[0],
            fileIndices: indices,
            queueIndices: indices,
            title: meta.name,
            titleName: item?.titleName,
            poster: item?.poster,
            imdb: item?.imdb,
            kind: item?.kind,
          });
          setTorrentMode(null);
          reset(false);
        }}
        onBack={() => {
          setTorrentMode(null);
          reset();
        }}
      />
    );
  }

  /**
   * Sits above the tabs but below the streaming flow: once a source is picked,
   * the file picker and the player take the screen from here.
   *
   * A layer rather than an early return. Returning this instead of the tab
   * stack unmounted every tab with it — so a search, its results and the scroll
   * position behind them were gone by the time you pressed Back, and the tab
   * rebuilt itself from nothing. Rendered on top instead, so what is underneath
   * is still there when it closes.
   */
  const detailLayer = detail ? (
    <DetailScreen
        seed={detail}
        busy={phase === 'inspecting'}
        error={torrent.error}
        progress={episodeProgress}
        resume={filmProgress[detail.imdb]}
        saved={saved}
        onToggleSaved={saveTitle}
        // `tag` is the SxxEyy to look for inside a season pack; true just means
        // "pick the feature". Either way, no file list.
        onPlay={async (magnet, episodeId, tag, startOver) => {
          setTorrentMode('stream');
          // `keepProgress` is what tells open() to ignore the stored resume
          // point and start from zero.
          const local = await openUri(magnet, {
            auto: tag ?? true,
            keepProgress: startOver,
            // Title page already has the artwork — stamp it on the library
            // row or the card stays a blank colour tile.
            imdb: detail.imdb,
            kind: detail.kind,
            poster: detail.poster,
            titleName: detail.title,
          });
          if (episodeId && local) linkEpisode(episodeId, local).then(setEpisodeLinks);
        }}
        onDownload={(magnet, tag, season) => {
          beginDownload({
            uri: magnet,
            fileIndices: [],
            auto: tag ?? true,
            season,
            imdb: detail.imdb,
            kind: detail.kind,
            poster: detail.poster,
            titleName: detail.title,
            title: detail.title,
          });
        }}
        onBack={() => setDetail(null)}
      />
  ) : null;

  // Same reasoning as the title page: a layer, so the tab underneath survives
  // being browsed away from and back.
  const shelfLayer = shelf ? (
    <CatalogScreen
      heading={shelf.title}
      kind={shelf.kind}
      genre={shelf.genre}
      onOpenTitle={setDetail}
      onBack={() => setShelf(null)}
    />
  ) : null;

  return (
    <View style={styles.app} onLayout={onReady}>
      <View style={styles.body}>
        <TabPage show={tab === 'home'} mounted>
          <HomeScreen
            recent={history}
            saved={saved}
            genres={settings.genres}
            onOpenTitle={setDetail}
            onOpenShelf={setShelf}
            onPlayFile={playFile}
            onSeeAll={showHistory}
          />
        </TabPage>
        <TabPage show={tab === 'search'} mounted={visited.has('search')}>
          <SearchScreen
            onOpenTitle={setDetail}
            /**
             * Straight to the file list, not the stream/download picker.
             *
             * The picker exists for a magnet arriving from another app, where
             * the intent is unknown. Someone who searched for a release and
             * tapped it has already said what they want — to see what is in it
             * — and the list itself offers play and download per file, so
             * asking first was a screen in the way of the answer.
             */
            onOpenMagnet={(magnet, name) => {
              setTorrentMode('stream');
              openUri(magnet, { titleName: name });
            }}
            onDownloadMagnet={(magnet, name) => {
              beginDownload({ uri: magnet, fileIndices: [], auto: true, title: name, titleName: name });
            }}
          />
        </TabPage>
        <TabPage show={tab === 'history'} mounted={visited.has('history')}>
          <HistoryScreen
            history={history}
            saved={saved}
            onOpenItem={openItem}
            onOpenTitle={setDetail}
            onPlayFile={playFile}
            onDownloadFile={downloadFile}
            onDownloadSeason={downloadSeason}
            onRemove={askRemove}
            onClear={askClear}
          />
        </TabPage>
        <TabPage show={tab === 'downloads'} mounted={visited.has('downloads')}>
          <DownloadsScreen
            onPlayFile={playCompleted}
            actives={downloads.live.map((l) => {
              const wanted = new Set(l.wanted);
              const files = l.files.length
                ? l.files
                : (l.job.queueIndices ?? l.job.fileIndices).map((i) => ({
                    index: i,
                    name: `File ${i}`,
                    size: 0,
                  }));
              return {
                id: l.job.uri,
                name:
                  l.job.titleName ||
                  l.meta?.name ||
                  l.job.title ||
                  files[0]?.name ||
                  'Starting…',
                size: files.filter((f) => wanted.has(f.index)).reduce((s, f) => s + f.size, 0),
                files: files.filter((f) => wanted.has(f.index)).length,
                queue: files,
                wanted: l.wanted,
                saved: l.saved,
                poster: l.job.poster,
                progress: l.progress,
                onPause: () => downloads.pause(l.job.uri),
                onResume: () => downloads.resume(l.job.uri),
                onCancel: () => {
                  void downloads.stop(l.job.uri);
                },
                onSkip: (index: number) => downloads.skip(l.job.uri, index),
                onInclude: (index: number) => downloads.include(l.job.uri, index),
                onPlayFile: (file) => {
                  TorrentStream.fileUri(file.index, l.job.uri)
                    .then((uri) =>
                      setLocalPlay({
                        url: uri,
                        title: file.name.replace(/\.[^.]+$/, ''),
                      })
                    )
                    .catch((e: any) => fail(String(e?.message ?? e)));
                },
              };
            })}
          />
        </TabPage>
        <TabPage show={tab === 'settings'} mounted={visited.has('settings')}>
          <SettingsScreen
            settings={settings}
            onChange={changeSettings}
            onClearHistory={askClear}
            historyCount={history.length}
          />
        </TabPage>
      </View>

      {/* Only the tab bar hides behind them — the title and shelf pages carry
          their own Back. */}
      {!detailLayer && !shelfLayer && <TabBar active={tab} onChange={setTab} />}
      {!!shelfLayer && <View style={styles.layer}>{shelfLayer}</View>}
      {!!detailLayer && <View style={styles.layer}>{detailLayer}</View>}
      <ConfirmModal request={confirm} onClose={() => setConfirm(null)} />
    </View>
  );
}
