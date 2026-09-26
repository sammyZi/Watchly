import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, BackHandler, Text, View } from 'react-native';
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
import type { TorrentFile, TorrentMeta } from '../modules/torrent-stream';
import { Sheet } from './components/Sheet';
import { bytes as formatBytes } from './lib/format';
import { episodeTag, type Hit } from './lib/search';
import { nextEpisode, pickFile, prevEpisode } from './lib/pick';
import {
  addProfile,
  editProfile,
  isGuest,
  loadLastProfile,
  loadProfiles,
  rememberProfile,
  removeProfile,
  setPin,
  type Profile,
} from './lib/profiles';
import { useProfile, wipeProfile } from './lib/store';
import { ProfilesScreen } from './screens/ProfilesScreen';
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

/**
 * "136 min" -> 8160 seconds.
 *
 * Cinemeta's running time is the only duration a torrent that has never been
 * played carries, and without a duration there is no bitrate — so a remux gets
 * the same buffers as a 700MB encode and rebuffers all the way through.
 */
const runtimeSeconds = (runtime?: string | null) => {
  const found = /(\d+)\s*min/i.exec(runtime ?? '');
  return found ? Number(found[1]) * 60 : 0;
};

/** Bytes per second, or 0 when the running time is not known. */
const bitrateOf = (bytes: number, seconds: number) =>
  bytes > 0 && seconds > 0 ? bytes / seconds : 0;

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
      // Closed, this only has to keep four icons legible over a backdrop. At
      // full strength it reads as a black column down the side of every page.
      experimental_backgroundImage: fadeRight(c.bg, 0.4),
    },
    veilOpen: { experimental_backgroundImage: fadeRight(c.bg) },
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
        // Closed it must stop before GUTTER (NAV_W + SAFE.x), or its fade runs
        // out on top of the first card as a black strip. Open it is meant to
        // dim the page, so it deliberately spills.
        style={[
          styles.veil,
          open && styles.veilOpen,
          { width: open ? NAV_OPEN_W * 2.4 : NAV_W },
        ]}
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
  /** Set when the torrent's names cannot say which file was asked for. */
  const [choose, setChoose] = useState<{ item: Item; meta: TorrentMeta } | null>(null);

  const torrent = useTorrent(settings);
  const { phase, item, meta, url, fileName, subtitleFiles, playingIndex, progress, error, fail, inspect, play, playKnown, reset } =
    torrent;

  const styles = useThemedStyles((c) => ({
    toast: {
      position: 'absolute' as const,
      bottom: SAFE.y,
      right: SAFE.x,
      maxWidth: 560,
      paddingHorizontal: space.md,
      paddingVertical: space.sm,
      borderRadius: radius.md,
      backgroundColor: c.surface,
      zIndex: 3,
    },
    toastText: { ...type.meta, color: c.text },
    opening: {
      position: 'absolute' as const,
      top: 0,
      left: 0,
      right: 0,
      bottom: 0,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      gap: space.md,
      backgroundColor: '#000000cc',
      zIndex: 5,
    },
    openingText: { ...type.title, color: '#ffffff' },
  }));

  /**
   * Torrent failures are otherwise invisible: the engine tears its own stream
   * down, the player unmounts, and the viewer is dropped back on whatever page
   * they came from with no word as to why. A dead swarm, a blocked network and
   * a full disk all land here now.
   */
  const [note, setNote] = useState<string | null>(null);
  useEffect(() => {
    if (!error) return;
    setNote(error);
    const timer = setTimeout(() => setNote(null), 8000);
    return () => clearTimeout(timer);
  }, [error]);

  /**
   * Who is watching, decided before anything is read off disk.
   *
   * Every library call below goes through the per-profile store, so the active
   * profile has to be set first or the wrong shelf is loaded and then written
   * back over the right one.
   */
  const [profiles, setProfiles] = useState<Profile[] | null>(null);
  const [who, setWho] = useState<Profile | null>(null);
  const [switching, setSwitching] = useState(false);

  const enter = useCallback((profile: Profile) => {
    useProfile(profile.id, isGuest(profile));
    setWho(profile);
    setSwitching(false);
    rememberProfile(profile.id).catch(() => {});
    // Read after the switch, never before.
    loadHistory().then(setHistory);
    loadSaved().then(setSaved);
  }, []);

  useEffect(() => {
    (async () => {
      const list = await loadProfiles();
      setProfiles(list);
      // The picker every time, even with one profile. Whoever picked up the
      // remote is not necessarily whoever put it down, and landing straight in
      // someone else's library is the thing profiles exist to prevent.
      SplashScreen.hideAsync().catch(() => {});
    })();
  }, [enter]);

  // BACK unwinds one layer at a time. The player handles its own — it is a full
  // screen with its own two-step exit.
  useEffect(() => {
    if (phase === 'playing') return;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (choose) {
        setChoose(null);
        return true;
      }
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
  }, [choose, detail, tab, phase]);

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
    async (hit: Hit, title?: Title, tag?: string, episode?: Episode) => {
      const next: Item = {
        uri: hit.magnet,
        title: hit.name,
        at: Date.now(),
        imdb: title?.imdb,
        kind: title?.kind,
        poster: title?.poster ?? undefined,
        titleName: episode && title
          ? `${title.title} · ${episodeTag(episode.season, episode.episode)}`
          : title?.title,
      };
      try {
        const items = await put(next);
        setHistory(items);
        const fresh = items.find((h) => h.uri === next.uri) ?? next;

        const found = await inspect(fresh);
        // inspect() has already said why on its own channel.
        if (!found) return;

        // An empty picker is not a choice. Saying so beats a sheet with nothing
        // in it, which is indistinguishable from the press not registering.
        if (!found.files.some((f) => f.isVideo)) {
          fail('This torrent holds no video.');
          return;
        }

        const index = pickFile(found.files, tag);
        if (index == null) {
          // A pack whose names carry no readable episode marker. The biggest file
          // is just the longest episode, not the one that was asked for, so the
          // phone hands the choice over rather than guessing — and returning
          // silently here was the whole "nothing happens" bug.
          setChoose({ item: fresh, meta: found });
          return;
        }

        await rememberFiles(next.uri, found, index);
        const size = found.files.find((f) => f.index === index)?.size ?? 0;
        play(index, resumeRatio(fresh, index), {
          bitrate: bitrateOf(size, fresh.durations?.[index] || runtimeSeconds(title?.runtime)),
        });
      } catch (e: any) {
        // `stream` is fired from onPress and nobody awaits it, so a throw
        // anywhere above — a failed history write, a storage error — vanished
        // and the press looked ignored. It has to land somewhere visible.
        console.warn('[stream] failed', e?.message ?? e);
        fail(String(e?.message ?? e));
      }
    },
    [fail, inspect, play, rememberFiles]
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
        const size = target.files.find((f) => f.index === known)?.size ?? 0;
        playKnown(target, known, {
          bitrate: bitrateOf(size, target.durations?.[known] ?? 0),
        });
        return;
      }

      try {
        const found = await inspect(target);
        if (!found) return;
        const index = known ?? pickFile(found.files);
        if (index == null) {
          // Nothing playable, or a pack too ambiguous to pick from — either way
          // returning in silence is what made the card look dead.
          setChoose({ item: target, meta: found });
          return;
        }
        await rememberFiles(target.uri, found, index);
        const size = found.files.find((f) => f.index === index)?.size ?? 0;
        play(index, resumeRatio(target, index), {
          bitrate: bitrateOf(size, target.durations?.[index] ?? 0),
        });
      } catch (e: any) {
        console.warn('[resume] failed', e?.message ?? e);
        fail(String(e?.message ?? e));
      }
    },
    [fail, inspect, play, playKnown, rememberFiles]
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

  /**
   * The episodes either side of this one, inside the same torrent.
   *
   * Only ever a season pack — `siblingEpisode` returns nothing unless the
   * playing file is named like an episode, so a film never offers one.
   */
  const upNext = useMemo(() => {
    if (!meta?.files?.length) return null;
    const found = nextEpisode(meta.files, playingIndex);
    return found ? { index: found.index, name: found.name } : null;
  }, [meta, playingIndex]);

  const upPrev = useMemo(() => {
    if (!meta?.files?.length) return null;
    const found = prevEpisode(meta.files, playingIndex);
    return found ? { index: found.index, name: found.name } : null;
  }, [meta, playingIndex]);

  /** Same torrent, different file: no inspect, and it starts from the top
   *  rather than the resume point of whatever was watched before. */
  const playSibling = useCallback(
    (to: { index: number } | null) => {
      if (!to || !item) return;
      setPosition(item, to.index, 0);
      play(to.index, 0, {
        bitrate: bitrateOf(
          item.files?.find((f) => f.index === to.index)?.size ?? 0,
          item.durations?.[to.index] ?? 0
        ),
      });
    },
    [item, play]
  );

  const playNext = useCallback(() => playSibling(upNext), [playSibling, upNext]);
  const playPrev = useCallback(() => playSibling(upPrev), [playSibling, upPrev]);

  /** Bytes per second of what is playing, or 0 when the runtime is unknown. */
  const playingBitrate = useMemo(() => {
    if (!item) return 0;
    const size = item.files?.find((f) => f.index === playingIndex)?.size ?? 0;
    return bitrateOf(size, item.durations?.[playingIndex] ?? 0);
  }, [item, playingIndex]);

  const startAt = useMemo(() => {
    if (!item) return 0;
    return item.positions?.[playingIndex] ?? 0;
  }, [item, playingIndex]);

  /**
   * Before anything else, and above the player: switching profile mid-film
   * would write one person's resume point onto another's shelf.
   */
  if (profiles !== null && (!who || switching)) {
    return (
      <ProfilesScreen
        profiles={profiles}
        onPick={enter}
        onAdd={(name, icon, colour) => {
          addProfile(name, icon, colour).then((list) => {
            setProfiles(list);
            // Straight in — naming a profile is choosing it.
            const made = list[list.length - 1];
            if (made) enter(made);
          });
        }}
        onEdit={(id, changes) => {
          editProfile(id, changes).then((list) => {
            setProfiles(list);
            // Keep the active profile's own copy current, or Settings keeps
            // showing the name it had before the edit.
            if (who?.id === id) setWho(list.find((p) => p.id === id) ?? who);
          });
        }}
        onRemove={(profile) => {
          removeProfile(profile.id).then(setProfiles);
          wipeProfile(profile.id).catch(() => {});
          // Removing whoever is active leaves nobody watching.
          if (who?.id === profile.id) setWho(null);
        }}
        onCancel={who ? () => setSwitching(false) : undefined}
      />
    );
  }

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
        bitrate={playingBitrate}
        next={upNext}
        onNext={playNext}
        prev={upPrev}
        onPrev={playPrev}
        onPosition={savePosition}
        onDuration={saveDuration}
        onEnded={exit}
        onExit={exit}
      />
    );
  }

  return (
    <View style={{ flex: 1, backgroundColor: colors.bg }}>
      {/* The tabs stay mounted underneath a title page rather than being
          swapped out for it. Unmounting threw away the rails, the scroll
          offset and the focused card, so coming back from a film landed at the
          top of a page rebuilding itself — instead of on the film you had just
          been looking at. Hidden, not removed: the native views keep their
          scroll position, and `display: none` takes them out of the D-pad's
          reach so focus cannot wander into the page behind. */}
      <View style={[{ flex: 1 }, !!detail && { display: 'none' as const }]}>
        {tab === 'home' ? (
          <HomeScreen
            history={history}
            saved={saved}
            genres={settings.genres}
            onPickTitle={openTitle}
            onResume={resume}
            hidden={!!detail}
          />
        ) : tab === 'search' ? (
          <SearchScreen onPickTitle={openTitle} onPickHit={stream} />
        ) : tab === 'library' ? (
          <LibraryScreen
            history={history}
            onResume={resume}
            onRemove={removeItem}
            onClear={clearAll}
          />
        ) : (
          <SettingsScreen
            settings={settings}
            onChange={onChangeSettings}
            who={who?.name}
            onSwitchProfile={() => setSwitching(true)}
            pin={!!who?.pin}
            // The guest is never written down, so it has nothing to lock.
            onSetPin={
              who && !isGuest(who)
                ? (value) => {
                    setPin(who.id, value).then((list) => {
                      setProfiles(list);
                      setWho(list.find((p) => p.id === who.id) ?? who);
                    });
                  }
                : undefined
            }
          />
        )}
      </View>
      {!!detail && (
        <View style={{ flex: 1 }}>
          <DetailScreen
            title={detail}
            saved={saved}
            onSave={saveTitle}
            onStream={stream}
          />
        </View>
      )}
      {/* Last, so it paints over the backdrop rather than beside it. */}
      <Nav
        tab={tab}
        onChange={(next) => {
          setDetail(null);
          setTab(next);
        }}
      />

      {!!choose && (
        <Sheet
          title="Which file?"
          options={choose.meta.files
            .filter((f) => f.isVideo)
            .map((f) => ({
              value: f.index,
              label: f.name,
              hint: formatBytes(f.size),
            }))}
          empty="This torrent holds no video."
          onPick={async (index) => {
            const target = choose;
            setChoose(null);
            if (index == null) return;
            const file = target.meta.files.find((f) => f.index === index);
            await rememberFiles(target.item.uri, target.meta, index);
            play(index, resumeRatio(target.item, index), {
              bitrate: bitrateOf(file?.size ?? 0, target.item.durations?.[index] ?? 0),
            });
          }}
        />
      )}

      {/* Resolving a torrent's metadata is a DHT round trip — seconds, and on a
          cold magnet more than that. Nothing was drawn for it, so pressing an
          episode looked like it had done nothing at all and the set sat there
          until the player appeared. Say what is happening instead. */}
      {phase === 'inspecting' && (
        <View style={styles.opening} pointerEvents="none">
          <ActivityIndicator size="large" color={colors.accent} />
          <Text style={styles.openingText}>Opening…</Text>
        </View>
      )}

      {/* Painted last so it sits over everything; never focusable, so the
          D-pad ignores it and it leaves on its own. */}
      {!!note && (
        <View style={styles.toast} pointerEvents="none">
          <Text style={styles.toastText}>{note}</Text>
        </View>
      )}
    </View>
  );
}
