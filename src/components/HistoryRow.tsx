import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { Icon } from './Icon';
import { Marquee } from './Marquee';
import { ProgressBar } from './ProgressBar';
import { Thumb } from './Thumb';
import { ago, bytes, clock } from '../lib/format';
import type { Item } from '../lib/history';
import { WATCHED } from '../lib/watched';
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
  onDownloadSeason,
  onRemove,
}: {
  item: Item;
  onOpen: () => void;
  onPlayFile: (index: number) => void;
  onDownloadFile: (index: number) => void;
  onDownloadSeason?: () => void;
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
    // A card per torrent, artwork-led: the poster is what identifies it at a
    // glance, so it gets the room and the title gets the weight.
    card: {
      backgroundColor: c.surface,
      borderRadius: radius.lg,
      padding: space.md,
      overflow: 'hidden' as const,
    },
    row: { flexDirection: 'row' as const, alignItems: 'center' as const, gap: space.md },
    pressed: { opacity: 0.6 },
    body: { flex: 1, minWidth: 0, gap: space.xs, paddingRight: space.xs },
    title: {
      ...type.title,
      fontFamily: font.semibold,
      fontSize: 15,
      color: c.text,
      lineHeight: 20,
    },
    release: { ...type.meta, fontSize: 11, color: c.faint },
    progress: { gap: 4, marginTop: space.xs },
    progressText: { ...type.meta, fontSize: 11, fontFamily: font.medium, color: c.accent },
    meta: { ...type.meta, fontSize: 11, color: c.faint, marginTop: space.xs },
    more: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      gap: space.sm,
      paddingTop: space.sm,
      paddingBottom: space.xs,
    },
    moreHit: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.xs,
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
    fileBody: { flex: 1, minWidth: 0, gap: 3 },
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

  const percent =
    lastDuration && lastPosition ? Math.round((lastPosition / lastDuration) * 100) : 0;

  /**
   * How far through this is. A season pack counts episodes finished, since a
   * percentage of whichever one was open last says nothing about the show; a
   * film reports its own percentage.
   */
  const watched = videos.filter((f) => {
    const seen = item.positions?.[f.index];
    const runs = item.durations?.[f.index];
    return !!runs && !!seen && (seen / runs) * 100 >= WATCHED;
  }).length;

  const progressLabel =
    videos.length > 1
      ? watched >= videos.length
        ? 'All watched'
        : watched > 0
          ? `${watched}/${videos.length} watched`
          : percent > 0
            ? `${percent}% into ep. 1`
            : null
      : percent >= WATCHED
        ? 'Watched'
        : percent > 0
          ? `${percent}% watched`
          : null;

  const meta = [
    videos.length > 1 ? `${videos.length} episodes` : null,
    lastPosition ? `at ${clock(lastPosition)}` : null,
    ago(item.at),
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
        {/* Portrait, because that is the shape a poster is. The old landscape
            tile held a frame grabbed while watching; cropping real artwork into
            it cut the top and bottom off every one. */}
        <Thumb
          name={item.torrentName ?? item.title}
          poster={item.poster}
          icon={item.poster ? undefined : 'film'}
          width={66}
          height={99}
        />

        <View style={styles.body}>
          <Marquee style={styles.title}>
            {item.titleName ?? item.torrentName ?? item.title}
          </Marquee>

          {/* The release name is still how two rips of the same film differ,
              but it stops being the headline once the real title is known. */}
          {!!item.titleName && (
            <Marquee style={styles.release}>{item.torrentName ?? item.title}</Marquee>
          )}

          {!!progressLabel && (
            <View style={styles.progress}>
              <Text style={styles.progressText}>{progressLabel}</Text>
              {!!lastDuration && !!lastPosition && (
                <ProgressBar value={percent} height={4} />
              )}
            </View>
          )}

          <Text style={styles.meta} numberOfLines={1}>
            {meta}
          </Text>
        </View>
      </Pressable>

      {/* A season pack: the row plays what you were last on, this reaches the
          rest. Only worth a control when there is more than one thing inside. */}
      {videos.length > 1 && (
        <View style={styles.more}>
          <Pressable onPress={() => setExpanded((e) => !e)} style={styles.moreHit}>
            <Text style={styles.moreText}>
              {expanded ? 'Hide' : `All ${videos.length} files`}
            </Text>
            <Icon name={expanded ? 'chevron-up' : 'chevron-down'} size={15} color={colors.muted} />
          </Pressable>
          {!!onDownloadSeason && (
            <Pressable onPress={onDownloadSeason} hitSlop={8} style={styles.downloadBtn}>
              <Icon name="download" size={14} color={colors.accent} />
            </Pressable>
          )}
        </View>
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
                  <Marquee style={styles.fileName}>{file.name}</Marquee>
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
