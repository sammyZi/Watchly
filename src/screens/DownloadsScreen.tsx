import { memo, useEffect, useState } from 'react';
import { FlatList, Pressable, Text, View } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import TorrentStream, { type Progress } from '../../modules/torrent-stream';
import { Button } from '../components/Button';
import { ConfirmModal, type Confirm } from '../components/ConfirmModal';
import { Icon } from '../components/Icon';
import { Marquee } from '../components/Marquee';
import { Thumb } from '../components/Thumb';
import { ProgressBar } from '../components/ProgressBar';
import { Screen } from '../components/Screen';
import { ago, bytes, speed } from '../lib/format';
import { groupByFolder } from '../lib/pick';
import { parseEpisode } from '../lib/search';
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
  name: string;
  size: number;
  files?: number;
  queue?: QueueFile[];
  /** Indexes currently fetching. Missing ones sit unmarked at the bottom. */
  wanted?: number[];
  poster?: string | null;
  progress: Progress | null;
  onPause: () => void;
  onResume: () => void;
  onCancel: () => void;
  onSkip?: (index: number) => void;
  onInclude?: (index: number) => void;
  onPlayFile?: (file: QueueFile) => void;
};

function queueProgress(queue: QueueFile[], pct: number, total: number) {
  let left = total > 0 ? (pct / 100) * total : 0;
  return queue.map((file) => {
    if (file.size <= 0) {
      return { ...file, done: 0, label: 'Queued' as const };
    }
    if (left >= file.size) {
      left -= file.size;
      return { ...file, done: 100, label: 'Done' as const };
    }
    if (left > 0) {
      const done = (left / file.size) * 100;
      left = 0;
      return { ...file, done, label: 'Downloading' as const };
    }
    return { ...file, done: 0, label: 'Queued' as const };
  });
}

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

async function removeDownload(name: string): Promise<DownloadEntry[]> {
  const items = (await loadDownloads()).filter((d) => d.name !== name);
  await AsyncStorage.setItem(KEY, JSON.stringify(items));
  await TorrentStream.deleteDownload(name).catch(() => {});
  return items;
}

function eta(remainingBytes: number, bytesPerSec: number): string {
  if (bytesPerSec <= 0 || remainingBytes <= 0) return '—';
  const secs = Math.round(remainingBytes / bytesPerSec);
  if (secs < 60) return `${secs}s left`;
  if (secs < 3600) return `${Math.round(secs / 60)}m left`;
  return `${Math.floor(secs / 3600)}h ${Math.round((secs % 3600) / 60)}m left`;
}

function fileTitle(name: string) {
  const at = parseEpisode(name);
  if (!at) return name.replace(/\.[^.]+$/, '');
  if (at.season === 0) return `Special ${at.episode}`;
  return `Episode ${at.episode}`;
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
  active,
}: {
  onPlayFile: (item: { name: string; path?: string }) => void;
  active?: ActiveDownload | null;
}) {
  const [items, setItems] = useState<DownloadEntry[]>([]);
  const [confirm, setConfirm] = useState<Confirm | null>(null);
  const [open, setOpen] = useState(true);
  const [openFolders, setOpenFolders] = useState<Record<string, boolean>>({});
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    flex: { flex: 1 },
    pinned: { paddingHorizontal: space.xl, paddingBottom: space.md },
    body: { paddingHorizontal: space.xl, paddingBottom: TAB_INSET, gap: space.md },
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
    metaLine: { ...type.meta, color: c.muted },
    actions: { flexDirection: 'row' as const, gap: space.sm },
    action: { flex: 1 },
    filesHead: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      justifyContent: 'space-between' as const,
      paddingTop: space.xs,
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
  }));

  useEffect(() => {
    loadDownloads().then(setItems);
  }, [active?.name, active?.progress?.progress, active?.wanted?.length]);

  useEffect(() => {
    const id = setInterval(() => {
      loadDownloads().then(setItems);
    }, 2000);
    return () => clearInterval(id);
  }, []);

  const askDelete = (item: DownloadEntry) =>
    setConfirm({
      title: 'Delete download?',
      message: `${showTitle(item.name, item.titleName)} will be removed from this list and from Downloads.`,
      icon: 'trash-2',
      confirmLabel: 'Delete',
      destructive: true,
      onConfirm: () => removeDownload(item.name).then(setItems),
    });

  const askStop = () => {
    if (!active) return;
    setConfirm({
      title: 'Stop download?',
      message: `${showTitle(active.name)} will be removed and will not come back.`,
      icon: 'x',
      confirmLabel: 'Stop',
      destructive: true,
      onConfirm: active.onCancel,
    });
  };

  const pct = Math.min(100, active?.progress?.progress ?? 0);
  const paused = !!active?.progress?.paused;
  const rate = paused ? 0 : active?.progress?.speed ?? 0;
  const remaining = active ? Math.max(0, active.size * (1 - pct / 100)) : 0;
  const queue = active?.queue && active.queue.length > 1 ? active.queue : null;
  const wantedSet = new Set(active?.wanted ?? queue?.map((f) => f.index) ?? []);
  const onQueue = queue?.filter((f) => wantedSet.has(f.index)) ?? [];
  const progressed = onQueue.length
    ? queueProgress(onQueue, pct, active.size)
    : [];
  const byIndex = new Map(progressed.map((f) => [f.index, f]));
  const queued = queue
    ? queue.map((f) =>
        wantedSet.has(f.index)
          ? (byIndex.get(f.index) ?? { ...f, done: 0, label: 'Queued' as const })
          : { ...f, done: 0, label: 'Off' as const }
      )
    : [];
  const groups = groupByFolder(queued);
  const doneCount = queued.filter((f) => f.label === 'Done').length;
  const one = active?.queue?.[0];
  const ep = one && !queue ? fileTitle(one.name) : null;
  const headline = active ? showTitle(active.name) : '';
  const stage = stageLabel(active?.progress?.state, paused);
  const sub =
    pct >= 100
      ? 'Saving to device…'
      : queue
        ? `${stage} · ${doneCount} of ${onQueue.length || queue.length}`
        : ep && parseEpisode(one!.name)
          ? `${stage} · ${ep}`
          : stage;

  return (
    <Screen title="Downloads" icon="download">
      <View style={styles.flex}>
        {!!active && (
          <View style={styles.pinned}>
            <View style={styles.card}>
              <View style={styles.hero}>
                <Thumb
                  name={active.name}
                  poster={active.poster ?? undefined}
                  width={80}
                  height={120}
                  progress={pct}
                />
                <View style={styles.heroBody}>
                  <Text numberOfLines={2} style={styles.name}>
                    {headline}
                  </Text>
                  <Text style={[styles.stage, paused && styles.stagePaused]}>
                    {sub}
                  </Text>
                  <ProgressBar value={pct} height={6} />
                  <Text style={styles.metaLine}>
                    {active.size > 0
                      ? `${bytes(active.size * (pct / 100))} of ${bytes(active.size)}`
                      : 'Starting…'}
                    {paused
                      ? ' · Paused'
                      : ` · ${speed(rate)} · ${eta(remaining, rate)}`}
                  </Text>
                  <Text style={styles.pct}>{Math.round(pct)}%</Text>
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
                  onPress={askStop}
                  style={styles.action}
                />
              </View>
            </View>
          </View>
        )}
      <FlatList
        style={styles.flex}
        data={items}
        keyExtractor={(d) => `${d.name}-${d.at}`}
        contentContainerStyle={styles.body}
        showsVerticalScrollIndicator={false}
        removeClippedSubviews={false}
        ItemSeparatorComponent={() => <View style={{ height: space.sm }} />}
        ListHeaderComponent={
          <View style={styles.head}>
            {!!active && !!queue && (
              <View style={styles.card}>
                <Pressable
                  onPress={() => setOpen((v) => !v)}
                  style={styles.filesHead}
                >
                  <Text style={styles.filesHeadText}>
                    {queue.length} episodes
                  </Text>
                  <Icon
                    name={open ? 'chevron-up' : 'chevron-down'}
                    size={16}
                    color={colors.faint}
                  />
                </Pressable>
                {open &&
                  groups.map((group) => {
                    const key = group.dir || group.folder || 'files';
                    const folderOpen = !group.folder || !!openFolders[key];
                    const folderBytes = group.files.reduce((s, f) => s + f.size, 0);
                    const skippable = group.files.filter(
                      (f) => f.label !== 'Off' && f.label !== 'Done'
                    );
                    const includable = group.files.filter((f) => f.label === 'Off');
                    return (
                    <View key={key}>
                      {!!group.folder && (
                        <View style={styles.folder}>
                          <Pressable
                            onPress={() =>
                              setOpenFolders((prev) => ({
                                ...prev,
                                [key]: !prev[key],
                              }))
                            }
                            style={{ flex: 1, flexDirection: 'row', alignItems: 'center', gap: 6 }}
                          >
                            <Icon
                              name={folderOpen ? 'chevron-down' : 'chevron-right'}
                              size={14}
                              color={colors.faint}
                            />
                            <Text numberOfLines={1} style={styles.folderName}>
                              {group.folder}
                            </Text>
                            <Text style={styles.filesHeadText}>
                              {group.files.length} · {bytes(folderBytes)}
                            </Text>
                          </Pressable>
                          {includable.length === group.files.length ? (
                            <Pressable
                              onPress={() =>
                                includable.forEach((f) => active.onInclude?.(f.index))
                              }
                              hitSlop={8}
                              style={styles.skip}
                              accessibilityLabel={`Download ${group.folder}`}
                            >
                              <Icon name="plus" size={16} color={colors.accent} />
                            </Pressable>
                          ) : skippable.length > 0 ? (
                            <Pressable
                              onPress={() =>
                                skippable.forEach((f) => active.onSkip?.(f.index))
                              }
                              hitSlop={8}
                              style={styles.skip}
                              accessibilityLabel={`Skip ${group.folder}`}
                            >
                              <Icon name="x" size={16} color={colors.faint} />
                            </Pressable>
                          ) : null}
                        </View>
                      )}
                      {folderOpen &&
                      group.files.map((file) => {
                        const off = file.label === 'Off';
                        const on = file.label === 'Downloading';
                        const done = file.label === 'Done';
                        const at = parseEpisode(file.name);
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
                                style={[
                                  styles.fileName,
                                  off && styles.fileNameOff,
                                ]}
                              >
                                {file.name}
                              </Marquee>
                              {on || done ? (
                                <ProgressBar
                                  value={file.done}
                                  height={3}
                                  color={done ? colors.good : colors.accent}
                                />
                              ) : (
                                <Text style={styles.fileMeta}>
                                  {off ? 'Skipped' : bytes(file.size)}
                                </Text>
                              )}
                            </View>
                            {off ? (
                              <Pressable
                                onPress={() => active.onInclude?.(file.index)}
                                hitSlop={8}
                                style={styles.skip}
                                accessibilityLabel={`Download ${file.name}`}
                              >
                                <Icon name="plus" size={16} color={colors.accent} />
                              </Pressable>
                            ) : done ? (
                              <Pressable
                                onPress={() => active.onPlayFile?.(file)}
                                hitSlop={8}
                                style={styles.skip}
                                accessibilityLabel={`Play ${file.name}`}
                              >
                                <Icon name="play" size={16} color={colors.accent} />
                              </Pressable>
                            ) : (
                              <Pressable
                                onPress={() => active.onSkip?.(file.index)}
                                hitSlop={8}
                                style={styles.skip}
                                accessibilityLabel={`Skip ${file.name}`}
                              >
                                <Icon name="x" size={16} color={colors.faint} />
                              </Pressable>
                            )}
                          </View>
                        );
                      })}
                    </View>
                    );
                  })}
              </View>
            )}

            {items.length > 0 && (
              <Text style={styles.heading}>Saved</Text>
            )}
          </View>
        }
        ListEmptyComponent={
          active ? null : (
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
        renderItem={({ item }) => {
          const at = parseEpisode(item.name);
          const title = showTitle(item.name, item.titleName);
          const ep = at
            ? at.season === 0
              ? `Special ${at.episode}`
              : `Episode ${at.episode}`
            : null;
          return (
            <Pressable
              onPress={() => onPlayFile(item)}
              style={({ pressed }) => [styles.row, pressed && styles.rowPressed]}
            >
              <Thumb
                name={item.titleName ?? item.name}
                poster={item.poster ?? undefined}
                width={44}
                height={66}
              />
              <View style={styles.rowBody}>
                <Text numberOfLines={1} style={styles.rowName}>
                  {title}
                </Text>
                <Text style={styles.rowMeta}>
                  {[ep, item.size > 0 ? bytes(item.size) : null, ago(item.at)]
                    .filter(Boolean)
                    .join(' · ')}
                </Text>
              </View>
              <View style={styles.playMark}>
                <Icon name="play" size={14} color={colors.accent} />
              </View>
              <Pressable
                onPress={() => askDelete(item)}
                hitSlop={8}
                accessibilityLabel={`Delete ${item.name}`}
              >
                <Icon name="trash-2" size={16} color={colors.faint} />
              </Pressable>
            </Pressable>
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
