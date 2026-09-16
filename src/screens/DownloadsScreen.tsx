import { memo, useEffect, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import TorrentStream, { type Progress } from '../../modules/torrent-stream';
import { Button } from '../components/Button';
import { ConfirmModal, type Confirm } from '../components/ConfirmModal';
import { Icon } from '../components/Icon';
import { Marquee } from '../components/Marquee';
import { Thumb } from '../components/Thumb';
import { ProgressBar } from '../components/ProgressBar';
import { Screen } from '../components/Screen';
import { ago, bytes, left, speed } from '../lib/format';
import { parseAbsolute, parseEpisode, seasonsIn } from '../lib/search';
import { searchableName } from '../lib/title';
import { font, radius, space, TAB_INSET, type, useColors, useThemedStyles } from '../theme';

type DownloadEntry = {
  name: string;
  size: number;
  at: number;
  path?: string;
  poster?: string | null;
  titleName?: string;
};

export type QueueFile = {
  index: number;
  name: string;
  size: number;
  path?: string;
};

export type ActiveDownload = {
  id?: string;
  name: string;
  size: number;
  files?: number;
  queue?: QueueFile[];
  /** Indexes currently fetching. Missing ones sit unmarked at the bottom. */
  wanted?: number[];
  /** Indexes copied to Downloads — only these show as Done. */
  saved?: number[];
  poster?: string | null;
  progress: Progress | null;
  onPause: () => void;
  onResume: () => void;
  onCancel: () => void;
  onSkip?: (index: number) => void;
  onInclude?: (index: number) => void;
  onPlayFile?: (file: QueueFile) => void;
};

const KEY = 'torrent-downloads';

async function loadDownloads(): Promise<DownloadEntry[]> {
  try {
    return JSON.parse((await AsyncStorage.getItem(KEY)) ?? '[]');
  } catch {
    return [];
  }
}

export async function recordDownload(
  name: string,
  size: number,
  path?: string,
  extra?: { poster?: string | null; titleName?: string }
) {
  const items = await loadDownloads();
  const rest = items.filter((d) => d.name !== name);
  await AsyncStorage.setItem(
    KEY,
    JSON.stringify([
      {
        name,
        size,
        at: Date.now(),
        path,
        poster: extra?.poster,
        titleName: extra?.titleName,
      },
      ...rest,
    ])
  );
}

const POS_KEY = 'torrent-download-positions';

/** Where each finished file was left off, in seconds, keyed by file name —
 *  the same key a Completed row is identified by. */
type Resume = { at: number; of: number };

async function loadPositions(): Promise<Record<string, Resume>> {
  try {
    return JSON.parse((await AsyncStorage.getItem(POS_KEY)) ?? '{}');
  } catch {
    return {};
  }
}

/** Seconds to resume a saved file at. Zero once it has been watched through —
 *  reopening the credits is never what anyone meant. */
export async function savedPosition(name: string): Promise<number> {
  const mark = (await loadPositions())[name];
  if (!mark || (mark.of > 0 && mark.at > mark.of * 0.97)) return 0;
  return mark.at;
}

export async function saveDownloadPosition(
  name: string,
  mark: { at?: number; of?: number }
) {
  const all = await loadPositions();
  const prev = all[name];
  all[name] = { at: mark.at ?? prev?.at ?? 0, of: mark.of ?? prev?.of ?? 0 };
  await AsyncStorage.setItem(POS_KEY, JSON.stringify(all));
}

async function removeDownloads(names: string[]): Promise<DownloadEntry[]> {
  const drop = new Set(names);
  const items = (await loadDownloads()).filter((d) => !drop.has(d.name));
  await AsyncStorage.setItem(KEY, JSON.stringify(items));
  const marks = await loadPositions();
  for (const name of names) delete marks[name];
  await AsyncStorage.setItem(POS_KEY, JSON.stringify(marks));
  for (const name of names) {
    await TorrentStream.deleteDownload(name).catch(() => {});
  }
  return items;
}

async function removeDownload(name: string): Promise<DownloadEntry[]> {
  return removeDownloads([name]);
}

function groupSaved(items: DownloadEntry[]) {
  type Season = { season: number | null; items: DownloadEntry[] };
  const shows = new Map<
    string,
    { key: string; title: string; poster?: string | null; seasons: Map<string, Season> }
  >();
  const order: string[] = [];
  for (const item of items) {
    const at = episodeOf(item);
    // A pack that only labels the season on the folder still groups right.
    const season = at?.season ?? seasonsIn(item.path ?? '')[0] ?? null;
    const title = item.titleName || searchableName(item.name) || item.name;
    let show = shows.get(title);
    if (!show) {
      show = { key: title, title, poster: item.poster, seasons: new Map() };
      shows.set(title, show);
      order.push(title);
    }
    if (!show.poster && item.poster) show.poster = item.poster;
    const sk = season != null ? String(season) : '_';
    let group = show.seasons.get(sk);
    if (!group) {
      group = { season, items: [] };
      show.seasons.set(sk, group);
    }
    group.items.push(item);
  }
  return order.map((key) => {
    const show = shows.get(key)!;
    const seasons = [...show.seasons.values()].sort(
      (a, b) => (a.season ?? 999) - (b.season ?? 999)
    );
    for (const season of seasons) {
      season.items.sort((a, b) => {
        const ea = episodeOf(a);
        const eb = episodeOf(b);
        if (ea && eb) return ea.episode - eb.episode;
        return a.name.localeCompare(b.name);
      });
    }
    return { key: show.key, title: show.title, poster: show.poster, seasons };
  });
}

function seasonLabel(season: number | null) {
  if (season == null) return '';
  if (season === 0) return 'Specials';
  return `Season ${season}`;
}

function eta(remainingBytes: number, bytesPerSec: number): string {
  if (bytesPerSec <= 0 || remainingBytes <= 0) return '—';
  const secs = Math.round(remainingBytes / bytesPerSec);
  if (secs < 60) return `${secs}s left`;
  if (secs < 3600) return `${Math.round(secs / 60)}m left`;
  return `${Math.floor(secs / 3600)}h ${Math.round((secs % 3600) / 60)}m left`;
}

function episodeLabel(at: { season: number; episode: number } | null) {
  if (!at) return '';
  return at.season === 0 ? `Special ${at.episode}` : `Episode ${at.episode}`;
}

/** Episode marker of one file — packs that lay episodes out as
 *  "Season 6/Show - 05.mkv" split it between the folder and the file name. */
function episodeOf(file?: { name: string; path?: string } | null) {
  if (!file) return null;
  const direct = parseEpisode(file.name) ?? parseEpisode(file.path ?? '');
  if (direct) return direct;
  const seasons = seasonsIn(file.path ?? '');
  const n = parseAbsolute(file.name);
  return seasons.length === 1 && n != null
    ? { season: seasons[0], episode: n }
    : null;
}

function fileTitle(name: string) {
  return episodeLabel(parseEpisode(name)) || name.replace(/\.[^.]+$/, '');
}

function showTitle(name: string, titleName?: string | null) {
  return titleName || searchableName(name) || name;
}

function stageLabel(state?: string, paused?: boolean): string {
  if (paused) return 'Paused';
  if (!state) return 'Starting…';
  if (state === 'DOWNLOADING_METADATA') return 'Starting…';
  if (state === 'CHECKING_FILES' || state === 'CHECKING_RESUME_DATA') return 'Checking…';
  if (state === 'DOWNLOADING') return 'Downloading';
  if (state === 'FINISHED' || state === 'SEEDING') return 'Finishing…';
  return 'Starting…';
}

function DownloadsScreenView({
  onPlayFile,
  actives = [],
}: {
  onPlayFile: (item: { name: string; path?: string }) => void;
  actives?: ActiveDownload[];
}) {
  const [items, setItems] = useState<DownloadEntry[]>([]);
  const [confirm, setConfirm] = useState<Confirm | null>(null);
  const [openQueues, setOpenQueues] = useState<Record<string, boolean>>({});
  const [openSaved, setOpenSaved] = useState<Record<string, boolean>>({});
  const [marks, setMarks] = useState<Record<string, Resume>>({});
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    flex: { flex: 1 },
    pinned: { paddingBottom: space.md },
    body: { paddingHorizontal: space.lg, paddingBottom: TAB_INSET, gap: space.md },
    head: { paddingBottom: space.sm },
    card: {
      backgroundColor: c.surface,
      borderRadius: radius.lg,
      padding: space.md,
      gap: space.md,
    },
    hero: { flexDirection: 'row' as const, gap: space.md },
    heroBody: { flex: 1, minWidth: 0, gap: 6, justifyContent: 'center' as const },
    name: {
      ...type.title,
      fontFamily: font.semibold,
      fontSize: 17,
      lineHeight: 22,
      color: c.text,
    },
    stage: { ...type.meta, fontFamily: font.medium, color: c.accent },
    stagePaused: { color: c.muted },
    pct: {
      ...type.label,
      fontFamily: font.semibold,
      fontSize: 13,
      color: c.text,
    },
    pctRow: {
      flexDirection: 'row' as const,
      alignItems: 'baseline' as const,
      justifyContent: 'space-between' as const,
      gap: 8,
    },
    metaLine: { ...type.meta, color: c.muted },
    waitTitle: { ...type.overline, color: c.faint },
    actions: { flexDirection: 'row' as const, gap: space.sm },
    action: { flex: 1 },
    filesHead: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      justifyContent: 'space-between' as const,
      paddingTop: space.md,
      borderTopWidth: StyleSheet.hairlineWidth,
      borderTopColor: c.line,
    },
    filesHeadText: { ...type.overline, color: c.faint },
    folder: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.sm,
      paddingTop: space.md,
      paddingBottom: space.xs,
    },
    folderName: { ...type.overline, color: c.muted, flex: 1 },
    file: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      paddingVertical: space.sm,
    },
    fileMark: {
      width: 36,
      height: 36,
      borderRadius: radius.sm,
      backgroundColor: c.control,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    fileMarkOn: { backgroundColor: c.accentSoft },
    fileMarkDone: { backgroundColor: c.goodSoft },
    fileNum: {
      ...type.meta,
      fontFamily: font.semibold,
      fontSize: 12,
      color: c.faint,
    },
    fileNumOn: { color: c.accent },
    fileNumDone: { color: c.good },
    fileBody: { flex: 1, minWidth: 0, gap: 4 },
    fileName: { ...type.body, fontFamily: font.medium, color: c.text },
    fileNameOff: { color: c.faint },
    fileMeta: { ...type.meta, color: c.faint },
    fileBar: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: 8,
    },
    filePct: { ...type.meta, fontFamily: font.medium, color: c.muted, minWidth: 32, textAlign: 'right' as const },
    skip: {
      width: 32,
      height: 32,
      borderRadius: radius.pill,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    heading: { ...type.overline, color: c.faint, paddingHorizontal: space.xs },
    empty: {
      alignItems: 'center' as const,
      gap: space.md,
      paddingVertical: 48,
    },
    emptyMark: {
      width: 56,
      height: 56,
      borderRadius: radius.lg,
      backgroundColor: c.surface,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    emptyTitle: { ...type.title, fontFamily: font.semibold, color: c.text },
    emptyText: {
      ...type.meta,
      color: c.muted,
      textAlign: 'center' as const,
      lineHeight: 18,
      paddingHorizontal: space.lg,
    },
    row: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      padding: space.md,
      borderRadius: radius.lg,
      backgroundColor: c.surface,
    },
    rowPressed: { opacity: 0.65 },
    rowBody: { flex: 1, minWidth: 0, gap: 3 },
    resume: { gap: 3, paddingTop: 2 },
    rowName: { ...type.body, fontFamily: font.semibold, color: c.text },
    rowMeta: { ...type.meta, color: c.faint },
    playMark: {
      width: 36,
      height: 36,
      borderRadius: radius.pill,
      backgroundColor: c.accentSoft,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    fileActions: { flexDirection: 'row' as const, alignItems: 'center' as const },
    seasonCard: {
      backgroundColor: c.surface,
      borderRadius: radius.lg,
      padding: space.md,
      gap: space.xs,
    },
    seasonHead: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
    },
    seasonToggle: {
      flex: 1,
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      minWidth: 0,
    },
    seasonLabel: { ...type.overline, color: c.muted, paddingTop: space.sm, paddingBottom: 2 },
    nestedRow: {
      backgroundColor: 'transparent',
      paddingHorizontal: 0,
      paddingVertical: space.sm,
    },
  }));

  useEffect(() => {
    loadDownloads().then(setItems);
  }, [actives.map((a) => a.name).join('|'), actives.map((a) => a.progress?.progress).join('|')]);

  useEffect(() => {
    const read = () => {
      loadDownloads().then(setItems);
      // Cheap, and it is how a row catches up with the player it just came
      // back from.
      loadPositions().then(setMarks);
    };
    read();
    const id = setInterval(read, 2000);
    return () => clearInterval(id);
  }, []);

  const askDeleteNames = (names: string[], label: string) =>
    setConfirm({
      title: names.length > 1 ? 'Delete downloads?' : 'Delete download?',
      message: `${label} will be removed from this list and from Movies.`,
      icon: 'trash-2',
      confirmLabel: 'Delete',
      destructive: true,
      onConfirm: () => removeDownloads(names).then(setItems),
    });

  const askDelete = (item: DownloadEntry) =>
    askDeleteNames([item.name], showTitle(item.name, item.titleName));

  const askStop = (target: ActiveDownload) => {
    setConfirm({
      title: 'Stop download?',
      message: `${showTitle(target.name)} will be removed and will not come back.`,
      icon: 'x',
      confirmLabel: 'Stop',
      destructive: true,
      onConfirm: target.onCancel,
    });
  };

  const renderActive = (active: ActiveDownload) => {
  const pct = Math.min(100, active.progress?.progress ?? 0);
  const paused = !!active.progress?.paused;
  const rate = paused ? 0 : active.progress?.speed ?? 0;
  const queue = active.queue && active.queue.length > 1 ? active.queue : null;
  const wantedSet = new Set(active?.wanted ?? queue?.map((f) => f.index) ?? []);
  const savedSet = new Set(active?.saved ?? []);
  const queued = queue
    ? queue.map((f) => {
        if (!wantedSet.has(f.index)) {
          return { ...f, done: 0, label: 'Off' as const };
        }
        if (savedSet.has(f.index)) {
          return { ...f, done: 100, label: 'Done' as const };
        }
        return { ...f, done: 0, label: 'Queued' as const };
      })
    : [];
  const current = queued
    .filter((f) => f.label === 'Queued')
    .sort((a, b) => {
      const ea = episodeOf(a);
      const eb = episodeOf(b);
      if (ea && eb) return ea.season - eb.season || ea.episode - eb.episode;
      return a.index - b.index;
    })[0];
  const marked = queued.map((f) =>
    current && f.index === current.index
      ? { ...f, done: pct, label: 'Downloading' as const }
      : f
  );
  const doneCount = marked.filter((f) => f.label === 'Done').length;
  const onQueue = queue?.filter((f) => wantedSet.has(f.index)) ?? [];
  const totalSize =
    onQueue.reduce((s, f) => s + f.size, 0) || active?.size || 0;
  const savedBytes = onQueue
    .filter((f) => savedSet.has(f.index))
    .reduce((s, f) => s + f.size, 0);
  const currentBytes = current
    ? (current.size * pct) / 100
    : queue
      ? 0
      : (totalSize * pct) / 100;
  const haveBytes = savedBytes + currentBytes;
  const overallPct =
    totalSize > 0 ? Math.min(100, (haveBytes / totalSize) * 100) : pct;
  const remaining = Math.max(0, totalSize - haveBytes);
  const headline = showTitle(active.name);
  // Which episode is being fetched. `active.name` is the show for anything
  // started from a title page, so only the file itself knows the episode —
  // and a one-file job has no queue list underneath to spell it out.
  const one = current ?? (active.queue?.length === 1 ? active.queue[0] : null);
  const at = episodeOf(one) ?? parseEpisode(active.name);
  const season =
    at?.season ?? seasonsIn(one?.path || one?.name || active.name)[0];
  const tags = [season != null ? seasonLabel(season) : '', episodeLabel(at)]
    .filter(Boolean)
    .join(' · ');
  const stage = stageLabel(active.progress?.state, paused);
  const sub = paused ? stage : pct >= 100 ? 'Saving to device…' : stage;
  const starting = !paused && pct < 1;
  const qid = active.id ?? active.name;

  return (
          <View key={qid} style={styles.pinned}>
            <View style={styles.card}>
              <View style={styles.hero}>
                <Thumb
                  name={active.name}
                  poster={active.poster ?? undefined}
                  width={80}
                  height={120}
                  progress={overallPct}
                />
                <View style={styles.heroBody}>
                  <Text numberOfLines={2} style={styles.name}>
                    {headline}
                  </Text>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                    {starting ? (
                      <ActivityIndicator size="small" color={colors.accent} />
                    ) : null}
                    <Text
                      numberOfLines={1}
                      style={[styles.stage, paused && styles.stagePaused, { flex: 1 }]}
                    >
                      {sub}
                    </Text>
                    {queue ? (
                      <Text
                        numberOfLines={1}
                        style={[styles.stage, paused && styles.stagePaused]}
                      >
                        {season != null ? `${seasonLabel(season)} · ` : ''}
                        {doneCount} of {onQueue.length || queue.length}
                      </Text>
                    ) : tags ? (
                      <Text
                        numberOfLines={1}
                        style={[styles.stage, paused && styles.stagePaused]}
                      >
                        {tags}
                      </Text>
                    ) : null}
                  </View>
                  <ProgressBar value={overallPct} height={6} />
                  <Text style={styles.metaLine}>
                    {totalSize > 0
                      ? `${bytes(haveBytes)} of ${bytes(totalSize)}`
                      : 'Starting…'}
                    {paused ? ' · Paused' : ` · ${speed(rate)}`}
                  </Text>
                  <View style={styles.pctRow}>
                    <Text style={styles.pct}>{Math.round(overallPct)}%</Text>
                    {!paused && pct < 100 ? (
                      <Text style={styles.metaLine}>{eta(remaining, rate)}</Text>
                    ) : null}
                  </View>
                </View>
              </View>

              <View style={styles.actions}>
                <Button
                  label={paused ? 'Resume' : 'Pause'}
                  icon={paused ? 'play' : 'pause'}
                  onPress={paused ? active.onResume : active.onPause}
                  style={styles.action}
                />
                <Button
                  label="Stop"
                  icon="x"
                  onPress={() => askStop(active)}
                  style={styles.action}
                />
              </View>
              {queue ? (
                <>
                  <Pressable
                    onPress={() =>
                      setOpenQueues((s) => ({ ...s, [qid]: !(s[qid] !== false) }))
                    }
                    style={styles.filesHead}
                  >
                    <Text style={styles.filesHeadText}>
                      {queue.length} episode{queue.length === 1 ? '' : 's'}
                    </Text>
                    <Icon
                      name={openQueues[qid] !== false ? 'chevron-up' : 'chevron-down'}
                      size={16}
                      color={colors.faint}
                    />
                  </Pressable>
                  {openQueues[qid] !== false &&
                    marked.map((file) => {
                      const off = file.label === 'Off';
                      const on = file.label === 'Downloading';
                      const done = file.label === 'Done';
                      const at = episodeOf(file);
                      const mark = at
                        ? String(at.episode).padStart(2, '0')
                        : String(file.index + 1).padStart(2, '0');
                      return (
                        <View key={file.index} style={styles.file}>
                          <View
                            style={[
                              styles.fileMark,
                              on && styles.fileMarkOn,
                              done && styles.fileMarkDone,
                            ]}
                          >
                            <Text
                              style={[
                                styles.fileNum,
                                on && styles.fileNumOn,
                                done && styles.fileNumDone,
                                off && { color: colors.faint },
                              ]}
                            >
                              {mark}
                            </Text>
                          </View>
                          <View style={styles.fileBody}>
                            <Marquee
                              style={
                                off ? [styles.fileName, styles.fileNameOff] : styles.fileName
                              }
                            >
                              {fileTitle(file.name)}
                            </Marquee>
                            {on || done ? (
                              <View style={styles.fileBar}>
                                <ProgressBar
                                  value={file.done}
                                  height={3}
                                  color={done ? colors.good : colors.accent}
                                  style={{ flex: 1 }}
                                />
                                <Text
                                  style={[
                                    styles.filePct,
                                    done && { color: colors.good },
                                    on && { color: colors.accent },
                                  ]}
                                >
                                  {Math.round(file.done)}%
                                </Text>
                              </View>
                            ) : (
                              <Text style={styles.fileMeta}>
                                {off
                                  ? `Skipped · ${bytes(file.size)}`
                                  : bytes(file.size)}
                              </Text>
                            )}
                          </View>
                          {off ? (
                            <Pressable
                              onPress={() => active.onInclude?.(file.index)}
                              hitSlop={8}
                              style={styles.skip}
                            >
                              <Icon name="plus" size={16} color={colors.accent} />
                            </Pressable>
                          ) : done ? (
                            <Pressable
                              onPress={() => active.onPlayFile?.(file)}
                              hitSlop={8}
                              style={styles.skip}
                            >
                              <Icon name="play" size={16} color={colors.accent} />
                            </Pressable>
                          ) : (
                            <Pressable
                              onPress={() => active.onSkip?.(file.index)}
                              hitSlop={8}
                              style={styles.skip}
                            >
                              <Icon name="x" size={16} color={colors.faint} />
                            </Pressable>
                          )}
                        </View>
                      );
                    })}
                </>
              ) : null}
            </View>
          </View>
  );
  };

  return (
    <Screen title="Downloads" icon="download">
      <View style={styles.flex}>
      <FlatList
        style={styles.flex}
        data={groupSaved(items)}
        keyExtractor={(g) => g.key}
        contentContainerStyle={styles.body}
        showsVerticalScrollIndicator={false}
        removeClippedSubviews={false}
        ItemSeparatorComponent={() => <View style={{ height: space.sm }} />}
        ListHeaderComponent={
          <View style={styles.head}>
            {actives.map(renderActive)}
            {items.length > 0 && (
              <Text style={styles.heading}>Saved</Text>
            )}
          </View>
        }
        ListEmptyComponent={
          actives.length ? null : (
            <View style={styles.empty}>
              <View style={styles.emptyMark}>
                <Icon name="download" size={22} color={colors.faint} />
              </View>
              <Text style={styles.emptyTitle}>Nothing saved yet</Text>
              <Text style={styles.emptyText}>
                Downloads you start live here until you delete them.
              </Text>
            </View>
          )
        }
        renderItem={({ item: group }) => {
          const row = (entry: DownloadEntry, nested?: boolean) => {
            const at = episodeOf(entry);
            const title = showTitle(entry.name, entry.titleName);
            const ep = episodeLabel(at) || null;
            const mark = marks[entry.name];
            // Where this was left off. Nothing once it has been watched
            // through — a finished episode starts over.
            const seen =
              mark && mark.of > 0 && mark.at > 0 && mark.at < mark.of * 0.97
                ? (mark.at / mark.of) * 100
                : 0;
            return (
              <Pressable
                key={`${entry.name}-${entry.at}`}
                onPress={() => onPlayFile(entry)}
                style={({ pressed }) => [
                  styles.row,
                  nested && styles.nestedRow,
                  pressed && styles.rowPressed,
                ]}
              >
                {!nested && (
                  <Thumb
                    name={entry.titleName ?? entry.name}
                    poster={entry.poster ?? undefined}
                    width={44}
                    height={66}
                  />
                )}
                <View style={styles.rowBody}>
                  <Text numberOfLines={1} style={styles.rowName}>
                    {/* Under a season header the show name is on the card
                        already — the file name at least tells them apart. */}
                    {nested ? (ep ?? fileTitle(entry.name)) : title}
                  </Text>
                  <Text style={styles.rowMeta}>
                    {[
                      !nested ? ep : null,
                      entry.size > 0 ? bytes(entry.size) : null,
                      ago(entry.at),
                    ]
                      .filter(Boolean)
                      .join(' · ')}
                  </Text>
                  {seen > 0 && (
                    <View style={styles.resume}>
                      <ProgressBar value={seen} height={3} />
                      <Text style={styles.rowMeta}>
                        {left(mark.of - mark.at)}
                      </Text>
                    </View>
                  )}
                </View>
                <View style={styles.playMark}>
                  <Icon name="play" size={14} color={colors.accent} />
                </View>
                <Pressable
                  onPress={() => askDelete(entry)}
                  hitSlop={8}
                  accessibilityLabel={`Delete ${entry.name}`}
                >
                  <Icon name="trash-2" size={16} color={colors.faint} />
                </Pressable>
              </Pressable>
            );
          };

          if (group.seasons.length === 1 && group.seasons[0].items.length === 1) {
            return row(group.seasons[0].items[0]);
          }

          const expanded = !!openSaved[group.key];
          const n = group.seasons.reduce((s, x) => s + x.items.length, 0);
          const names = group.seasons.flatMap((s) => s.items.map((e) => e.name));
          const meta =
            group.seasons.length > 1
              ? `${group.seasons.length} seasons · ${n} episodes`
              : group.seasons[0].season != null
                ? `${seasonLabel(group.seasons[0].season)} · ${n} episodes`
                : `${n} files`;
          return (
            <View style={styles.seasonCard}>
              <View style={styles.seasonHead}>
                <Pressable
                  onPress={() =>
                    setOpenSaved((s) => ({ ...s, [group.key]: !s[group.key] }))
                  }
                  style={styles.seasonToggle}
                >
                  <Thumb
                    name={group.title}
                    poster={group.poster ?? undefined}
                    width={44}
                    height={66}
                  />
                  <View style={styles.rowBody}>
                    <Text numberOfLines={1} style={styles.rowName}>
                      {group.title}
                    </Text>
                    <Text style={styles.rowMeta}>{meta}</Text>
                  </View>
                  <Icon
                    name={expanded ? 'chevron-up' : 'chevron-down'}
                    size={16}
                    color={colors.faint}
                  />
                </Pressable>
                <Pressable
                  onPress={() => askDeleteNames(names, group.title)}
                  hitSlop={8}
                  accessibilityLabel={`Delete ${group.title}`}
                >
                  <Icon name="trash-2" size={16} color={colors.faint} />
                </Pressable>
              </View>
              {expanded &&
                group.seasons.map((season) => (
                  <View key={String(season.season)}>
                    {group.seasons.length > 1 && season.season != null ? (
                      <Text style={styles.seasonLabel}>{seasonLabel(season.season)}</Text>
                    ) : null}
                    {season.items.map((entry) => row(entry, true))}
                  </View>
                ))}
            </View>
          );
        }}
        ListFooterComponent={null}
      />
      </View>
      <ConfirmModal request={confirm} onClose={() => setConfirm(null)} />
    </Screen>
  );
}

export const DownloadsScreen = memo(DownloadsScreenView);
