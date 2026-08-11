import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { Icon } from './Icon';
import { ProgressBar } from './ProgressBar';
import { Thumb } from './Thumb';
import { ago, bytes, clock } from '../lib/format';
import type { Item } from '../lib/history';
import { font, radius, space, type, useColors, useThemedStyles } from '../theme';

/**
 * One torrent. Expands to the files it contains so a season pack can be
 * resumed at any episode, not just the last one opened.
 */
export function HistoryRow({
  item,
  onOpen,
  onPlayFile,
  onDownloadFile,
  onRemove,
}: {
  item: Item;
  onOpen: () => void;
  onPlayFile: (index: number) => void;
  onDownloadFile: (index: number) => void;
  onRemove: () => void;
}) {
  // Torrents list files in whatever order they were packed; sort naturally so
  // episode 2 lands between 1 and 10.
  const videos = (item.files ?? [])
    .filter((f) => f.isVideo)
    .slice()
    .sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }));
  const [expanded, setExpanded] = useState(false);
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    // A card per torrent, artwork-led: the captured frame is the only thing that
    // says what this is at a glance.
    card: {
      backgroundColor: c.surface,
      borderRadius: radius.lg,
      padding: space.sm,
      overflow: 'hidden' as const,
    },
    row: { flexDirection: 'row' as const, alignItems: 'center' as const, gap: space.md },
    pressed: { opacity: 0.6 },
    body: { flex: 1, gap: space.xs, paddingRight: space.xs },
    title: { ...type.title, fontFamily: font.medium, color: c.text },
    meta: { ...type.meta, color: c.faint },
    bar: { marginTop: space.xs },
    action: {
      width: 34,
      height: 34,
      borderRadius: radius.pill,
      backgroundColor: c.accentSoft,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    more: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      gap: space.xs,
      paddingTop: space.sm,
      paddingBottom: space.xs,
    },
    moreText: { ...type.meta, fontFamily: font.medium, color: c.muted },

    files: {
      borderTopWidth: 1,
      borderTopColor: c.line,
      paddingTop: space.sm,
    },
    file: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      paddingVertical: space.sm,
      paddingLeft: space.sm,
    },
    fileBody: { flex: 1, gap: 3 },
    fileName: { ...type.body, fontFamily: font.medium, color: c.text },
    fileMeta: { ...type.meta, color: c.faint },
    downloadBtn: {
      width: 30,
      height: 30,
      borderRadius: radius.pill,
      backgroundColor: c.accentSoft,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
  }));

  const lastIndex = item.lastFileIndex;
  const lastPosition = lastIndex != null ? item.positions?.[lastIndex] : undefined;
  const lastDuration = lastIndex != null ? item.durations?.[lastIndex] : undefined;

  const meta = [
    ago(item.at),
    videos.length ? `${videos.length} episodes` : null,
    lastPosition ? `at ${clock(lastPosition)}` : null,
  ]
    .filter(Boolean)
    .join(' · ');

  return (
    <View style={styles.card}>
      <Pressable
        onPress={onOpen}
        onLongPress={onRemove}
        style={({ pressed }) => [styles.row, pressed && styles.pressed]}
      >
        <Thumb
          name={item.torrentName ?? item.title}
          poster={lastIndex != null ? item.posters?.[lastIndex] : undefined}
          icon="play"
          width={124}
          height={70}
          progress={
            lastDuration && lastPosition ? (lastPosition / lastDuration) * 100 : undefined
          }
        />

        <View style={styles.body}>
          <Text numberOfLines={2} style={styles.title}>
            {item.torrentName ?? item.title}
          </Text>
          <Text style={styles.meta} numberOfLines={1}>
            {meta}
          </Text>
          {!!lastDuration && !!lastPosition && (
            <ProgressBar value={(lastPosition / lastDuration) * 100} style={styles.bar} />
          )}
        </View>

        <Pressable
          onPress={() =>
            videos.length === 1 ? onDownloadFile(videos[0].index) : setExpanded((e) => !e)
          }
          hitSlop={8}
          accessibilityLabel={videos.length === 1 ? 'Download' : 'Show files'}
          style={styles.action}
        >
          <Icon name="download" size={15} color={colors.accent} />
        </Pressable>
      </Pressable>

      {/* A season pack: the row plays what you were last on, this reaches the
          rest. Only worth a control when there is more than one thing inside. */}
      {videos.length > 1 && (
        <Pressable onPress={() => setExpanded((e) => !e)} style={styles.more}>
          <Text style={styles.moreText}>
            {expanded ? 'Hide' : `All ${videos.length} files`}
          </Text>
          <Icon name={expanded ? 'chevron-up' : 'chevron-down'} size={15} color={colors.muted} />
        </Pressable>
      )}

      {expanded && (
        <View style={styles.files}>
          {videos.map((file) => {
            const position = item.positions?.[file.index];
            const duration = item.durations?.[file.index];
            return (
              <Pressable
                key={file.index}
                onPress={() => onPlayFile(file.index)}
                style={({ pressed }) => [styles.file, pressed && styles.pressed]}
              >
                <Icon name="play" size={13} color={colors.accent} />
                <View style={styles.fileBody}>
                  <Text numberOfLines={1} style={styles.fileName}>
                    {file.name}
                  </Text>
                  <Text style={styles.fileMeta}>
                    {bytes(file.size)}
                    {position ? ` · resume ${clock(position)}` : ''}
                  </Text>
                  {!!position && !!duration && (
                    <ProgressBar value={(position / duration) * 100} height={2} />
                  )}
                </View>
                <Pressable
                  onPress={() => onDownloadFile(file.index)}
                  hitSlop={8}
                  style={styles.downloadBtn}
                >
                  <Icon name="download" size={14} color={colors.accent} />
                </Pressable>
              </Pressable>
            );
          })}
        </View>
      )}
    </View>
  );
}
