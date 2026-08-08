import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Icon } from './Icon';
import { ProgressBar } from './ProgressBar';
import { Thumb } from './Thumb';
import { ago, bytes, clock } from '../lib/format';
import type { Item } from '../lib/history';
import { colors, font, radius, space, type } from '../theme';

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
    <View>
      <Pressable
        onPress={videos.length > 1 ? () => setExpanded((e) => !e) : onOpen}
        onLongPress={onRemove}
        style={({ pressed }) => [styles.row, pressed && styles.pressed]}
      >
        <Thumb
          name={item.torrentName ?? item.title}
          poster={lastIndex != null ? item.posters?.[lastIndex] : undefined}
          icon={videos.length > 1 ? 'folder' : 'play'}
          width={64}
          height={48}
          progress={
            lastDuration && lastPosition ? (lastPosition / lastDuration) * 100 : undefined
          }
        />

        <View style={styles.body}>
          <Text numberOfLines={2} style={styles.title}>
            {item.torrentName ?? item.title}
          </Text>
          <Text style={styles.meta}>{meta}</Text>
          {!!lastDuration && !!lastPosition && (
            <ProgressBar
              value={(lastPosition / lastDuration) * 100}
              style={styles.bar}
            />
          )}
        </View>

        <Icon
          name={videos.length > 1 ? (expanded ? 'chevron-up' : 'chevron-down') : 'chevron-right'}
          size={18}
          color={colors.faint}
        />
        <Pressable
          onPress={() => {
            if (videos.length === 1) {
              onDownloadFile(videos[0].index);
            } else {
              setExpanded((e) => !e);
            }
          }}
          hitSlop={8}
          style={styles.rowBtn}
        >
          <Icon name="download" size={15} color={colors.accent} />
        </Pressable>
      </Pressable>

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

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    paddingVertical: space.md,
  },
  pressed: { opacity: 0.6 },
  body: { flex: 1, gap: space.xs },
  title: { ...type.title, color: colors.text },
  meta: { ...type.meta, color: colors.faint },
  bar: { marginTop: space.xs },

  files: {
    borderTopWidth: 1,
    borderTopColor: colors.line,
    paddingTop: space.sm,
    marginBottom: space.sm,
  },
  file: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    paddingVertical: space.sm,
    paddingLeft: space.sm,
  },
  fileBody: { flex: 1, gap: 3 },
  fileName: { ...type.body, fontFamily: font.medium, color: colors.text },
  fileMeta: { ...type.meta, color: colors.faint },
  downloadBtn: {
    width: 30,
    height: 30,
    borderRadius: radius.pill,
    backgroundColor: colors.accentSoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
