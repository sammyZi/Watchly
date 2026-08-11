import { memo, useEffect, useState } from 'react';
import { FlatList, Pressable, Text, View } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import type { Progress } from '../../modules/torrent-stream';
import { Icon } from '../components/Icon';
import { Thumb } from '../components/Thumb';
import { ProgressBar } from '../components/ProgressBar';
import { Screen } from '../components/Screen';
import { SourceInput } from '../components/SourceInput';
import { ago, bytes, speed } from '../lib/format';
import { font, radius, space, TAB_INSET, type, useColors, useThemedStyles } from '../theme';

type DownloadEntry = {
  name: string;
  size: number;
  at: number;
  /** `file://` path when known — older entries resolve via downloadUri(name). */
  path?: string;
};

/** Live transfer shown at the top of the Downloads tab. */
export type ActiveDownload = {
  name: string;
  size: number;
  progress: Progress | null;
  onPause: () => void;
  onResume: () => void;
  onCancel: () => void;
};

const KEY = 'torrent-downloads';

async function loadDownloads(): Promise<DownloadEntry[]> {
  try {
    return JSON.parse((await AsyncStorage.getItem(KEY)) ?? '[]');
  } catch {
    return [];
  }
}

/** Persist a finished transfer so it stays in the Completed list. */
export async function recordDownload(name: string, size: number, path?: string) {
  const items = await loadDownloads();
  const rest = items.filter((d) => d.name !== name);
  await AsyncStorage.setItem(
    KEY,
    JSON.stringify([{ name, size, at: Date.now(), path }, ...rest])
  );
}

function eta(remainingBytes: number, bytesPerSec: number): string {
  if (bytesPerSec <= 0 || remainingBytes <= 0) return '—';
  const secs = Math.round(remainingBytes / bytesPerSec);
  if (secs < 60) return `${secs}s left`;
  if (secs < 3600) return `${Math.round(secs / 60)}m left`;
  return `${Math.floor(secs / 3600)}h ${Math.round((secs % 3600) / 60)}m left`;
}

function stageLabel(state?: string, paused?: boolean): string {
  if (paused) return 'Paused';
  if (!state) return 'Starting…';
  if (state === 'DOWNLOADING_METADATA') return 'Fetching metadata';
  if (state === 'CHECKING_FILES' || state === 'CHECKING_RESUME_DATA') return 'Checking files';
  if (state === 'DOWNLOADING') return 'Downloading';
  if (state === 'FINISHED' || state === 'SEEDING') return 'Finishing…';
  return 'Finding peers';
}

function DownloadsScreenView({
  onOpenUri,
  onPlayFile,
  active,
}: {
  onOpenUri: (uri: string) => void;
  onPlayFile: (item: { name: string; path?: string }) => void;
  active?: ActiveDownload | null;
}) {
  const [items, setItems] = useState<DownloadEntry[]>([]);
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    body: { padding: space.xl, gap: space.md, paddingBottom: TAB_INSET },
    head: { gap: space.lg, marginBottom: space.sm },
    active: {
      gap: space.md,
      padding: space.lg,
      borderRadius: radius.lg,
      backgroundColor: c.surface,
    },
    activeTop: { flexDirection: 'row' as const, alignItems: 'flex-start' as const, gap: space.md },
    activeIcon: {
      width: 42,
      height: 42,
      borderRadius: radius.md,
      backgroundColor: c.accentSoft,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    activeIconPaused: { backgroundColor: c.control },
    activeBody: { flex: 1, gap: 2 },
    activeName: {
      ...type.body,
      fontFamily: font.semibold,
      color: c.text,
      lineHeight: 20,
    },
    activeStage: { ...type.meta, color: c.accent },
    activeStagePaused: { color: c.muted },
    bar: { marginTop: space.xs },
    stats: {
      flexDirection: 'row' as const,
      justifyContent: 'space-between' as const,
      gap: space.sm,
    },
    stat: { ...type.meta, fontFamily: font.medium, color: c.text },
    statMuted: { ...type.meta, color: c.faint },
    actions: { flexDirection: 'row' as const, gap: space.sm, marginTop: space.xs },
    actionBtn: {
      flex: 1,
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      gap: space.sm,
      height: 40,
      borderRadius: radius.pill,
      backgroundColor: c.control,
    },
    actionLabel: { ...type.label, color: c.accent },
    cancelBtn: {
      flex: 1,
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      gap: space.sm,
      height: 40,
      borderRadius: radius.pill,
      backgroundColor: c.dangerSoft,
    },
    cancelLabel: { ...type.label, color: c.danger },
    sectionHead: {
      flexDirection: 'row' as const,
      alignItems: 'baseline' as const,
      justifyContent: 'space-between' as const,
    },
    heading: { ...type.overline, color: c.faint },
    headingMeta: { ...type.meta, fontSize: 11, color: c.faint },
    empty: {
      alignItems: 'center' as const,
      gap: space.md,
      paddingVertical: space.xxl,
      paddingHorizontal: space.lg,
      borderRadius: radius.lg,
      backgroundColor: c.surface,
    },
    emptyMark: {
      width: 52,
      height: 52,
      borderRadius: radius.lg,
      backgroundColor: c.control,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    emptyTitle: {
      ...type.label,
      fontFamily: font.semibold,
      fontSize: 15,
      color: c.text,
    },
    emptyText: {
      ...type.meta,
      color: c.muted,
      textAlign: 'center' as const,
      lineHeight: 18,
    },
    row: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      padding: space.sm,
      borderRadius: radius.lg,
      backgroundColor: c.surface,
    },
    rowPressed: { opacity: 0.7 },
    rowBody: { flex: 1, gap: 2, paddingRight: space.xs },
    rowSaved: { ...type.meta, fontSize: 11, color: c.good },
    rowName: {
      ...type.body,
      fontFamily: font.medium,
      color: c.text,
      lineHeight: 20,
    },
    rowMeta: { ...type.meta, color: c.muted },
  }));

  useEffect(() => {
    loadDownloads().then(setItems);
  }, [active?.name, active?.progress?.progress]);

  useEffect(() => {
    const id = setInterval(() => loadDownloads().then(setItems), 2000);
    return () => clearInterval(id);
  }, []);

  const pct = Math.min(100, active?.progress?.progress ?? 0);
  const paused = !!active?.progress?.paused;
  const rate = paused ? 0 : active?.progress?.speed ?? 0;
  const remaining = active ? Math.max(0, active.size * (1 - pct / 100)) : 0;

  return (
    <Screen title="Downloads" icon="download">
      <FlatList
        data={items}
        keyExtractor={(d) => `${d.name}-${d.at}`}
        contentContainerStyle={styles.body}
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
        ListHeaderComponent={
          <View style={styles.head}>
            <SourceInput
              onSubmit={onOpenUri}
              actionLabel="Download"
              title="Add download"
              subtitle="Magnet link or .torrent file"
            />

            {!!active && (
              <View style={styles.active}>
                <View style={styles.activeTop}>
                  <View style={[styles.activeIcon, paused && styles.activeIconPaused]}>
                    <Icon
                      name={paused ? 'pause' : 'download-cloud'}
                      size={18}
                      color={paused ? colors.muted : colors.accent}
                    />
                  </View>
                  <View style={styles.activeBody}>
                    <Text numberOfLines={2} style={styles.activeName}>
                      {active.name}
                    </Text>
                    <Text style={[styles.activeStage, paused && styles.activeStagePaused]}>
                      {pct >= 100
                        ? 'Saving to Downloads…'
                        : stageLabel(active.progress?.state, paused)}
                    </Text>
                  </View>
                </View>

                <ProgressBar value={pct} height={5} style={styles.bar} />

                <View style={styles.stats}>
                  <Text style={styles.stat}>{pct.toFixed(1)}%</Text>
                  <Text style={styles.stat}>{paused ? '—' : speed(rate)}</Text>
                  <Text style={styles.stat}>{paused ? '—' : eta(remaining, rate)}</Text>
                </View>
                <View style={styles.stats}>
                  <Text style={styles.statMuted}>
                    {active.progress?.seeds ?? 0} seeds ·{' '}
                    {active.progress?.peers ?? 0} peers
                  </Text>
                  {active.size > 0 && (
                    <Text style={styles.statMuted}>
                      {bytes(active.size * (pct / 100))} / {bytes(active.size)}
                    </Text>
                  )}
                </View>

                <View style={styles.actions}>
                  <Pressable
                    onPress={paused ? active.onResume : active.onPause}
                    style={styles.actionBtn}
                  >
                    <Icon
                      name={paused ? 'play' : 'pause'}
                      size={15}
                      color={colors.accent}
                    />
                    <Text style={styles.actionLabel}>
                      {paused ? 'Resume' : 'Pause'}
                    </Text>
                  </Pressable>
                  <Pressable onPress={active.onCancel} style={styles.cancelBtn}>
                    <Icon name="x" size={15} color={colors.danger} />
                    <Text style={styles.cancelLabel}>Cancel</Text>
                  </Pressable>
                </View>
              </View>
            )}

            {(items.length > 0 || !!active) && (
              <View style={styles.sectionHead}>
                <Text style={styles.heading}>Completed</Text>
                <Text style={styles.headingMeta}>
                  {items.length} {items.length === 1 ? 'file' : 'files'}
                </Text>
              </View>
            )}
          </View>
        }
        ListEmptyComponent={
          active ? null : (
            <View style={styles.empty}>
              <View style={styles.emptyMark}>
                <Icon name="download" size={22} color={colors.faint} />
              </View>
              <Text style={styles.emptyTitle}>No downloads yet</Text>
              <Text style={styles.emptyText}>
                Add a magnet above. Progress shows here and in a notification —
                finished files stay in Completed.
              </Text>
            </View>
          )
        }
        renderItem={({ item }) => (
          <Pressable
            onPress={() => onPlayFile(item)}
            style={({ pressed }) => [styles.row, pressed && styles.rowPressed]}
          >
            {/* No frame is ever captured for a download, so the tile falls back
                to its name colour — still enough to tell rows apart. */}
            <Thumb name={item.name} icon="play" width={124} height={70} />
            <View style={styles.rowBody}>
              <Text numberOfLines={2} style={styles.rowName}>
                {item.name}
              </Text>
              <Text style={styles.rowMeta}>
                {item.size > 0 ? `${bytes(item.size)} · ` : ''}
                {ago(item.at)}
              </Text>
              <Text style={styles.rowSaved}>Saved to Downloads</Text>
            </View>
          </Pressable>
        )}
      />
    </Screen>
  );
}

/** Kept mounted behind the other tabs — see TabPage in App. */
export const DownloadsScreen = memo(DownloadsScreenView);
