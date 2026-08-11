import { useMemo } from 'react';
import {
  Pressable,
  SectionList,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { IconButton } from '../components/Button';
import { Icon } from '../components/Icon';
import { bytes } from '../lib/format';
import type { TorrentFile, TorrentMeta } from '../../modules/torrent-stream';
import { colors, font, radius, space, type } from '../theme';

/** Files inside the torrent, grouped by folder and in natural order. */
export function FilesScreen({
  meta,
  mode,
  onPlay,
  onDownload,
  onBack,
}: {
  meta: TorrentMeta;
  mode?: 'stream' | 'download';
  onPlay: (file: TorrentFile) => void;
  onDownload: (file: TorrentFile) => void;
  onBack: () => void;
}) {
  const sections = useMemo(() => {
    const byFolder = new Map<string, TorrentFile[]>();
    for (const file of meta.files) {
      const parts = file.path.split(/[/\\]/);
      const folder = parts.slice(0, -1).join('/') || meta.name;
      const list = byFolder.get(folder);
      if (list) list.push(file);
      else byFolder.set(folder, [file]);
    }

    // Packed order is arbitrary — sort so episode 2 lands between 1 and 10,
    // and keep the playable files above the samples and nfo clutter.
    const natural = (a: string, b: string) =>
      a.localeCompare(b, undefined, { numeric: true });

    return [...byFolder.entries()]
      .map(([title, data]) => ({
        title,
        data: data.sort(
          (a, b) => Number(b.isVideo) - Number(a.isVideo) || natural(a.name, b.name)
        ),
      }))
      .sort((a, b) => natural(a.title, b.title));
  }, [meta]);

  const videos = meta.files.filter((f) => f.isVideo).length;
  const insets = useSafeAreaInsets();
  const downloading = mode === 'download';

  return (
    <View style={styles.screen}>
      <StatusBar barStyle="light-content" backgroundColor={colors.bg} />

      <View style={[styles.header, { paddingTop: insets.top + space.sm }]}>
        <IconButton name="chevron-left" onPress={onBack} background="transparent" />
        <View style={styles.headerText}>
          <Text numberOfLines={1} style={styles.title}>
            {meta.name}
          </Text>
          <Text style={styles.subtitle}>
            {videos} playable · {meta.files.length} files
          </Text>
        </View>
        <View style={[styles.modeChip, downloading && styles.modeChipDownload]}>
          <Icon
            name={downloading ? 'download' : 'play'}
            size={12}
            color={downloading ? colors.onAccent : colors.accent}
          />
          <Text style={[styles.modeChipText, downloading && styles.modeChipTextOn]}>
            {downloading ? 'Download' : 'Stream'}
          </Text>
        </View>
      </View>

      <View style={styles.hintBar}>
        <Icon name="info" size={13} color={colors.muted} />
        <Text style={styles.hintText}>
          {downloading
            ? 'Tap a video to download it to your device'
            : 'Tap a video to start watching'}
        </Text>
      </View>

      <SectionList
        sections={sections}
        keyExtractor={(f) => String(f.index)}
        contentContainerStyle={styles.list}
        stickySectionHeadersEnabled={false}
        showsVerticalScrollIndicator={false}
        renderSectionHeader={({ section }) =>
          sections.length > 1 ? (
            <View style={styles.folder}>
              <Icon name="folder" size={13} color={colors.faint} />
              <Text numberOfLines={1} style={styles.folderName}>
                {section.title}
              </Text>
              <Text style={styles.folderCount}>{section.data.length}</Text>
            </View>
          ) : null
        }
        renderItem={({ item }) => {
          const action = () =>
            downloading ? onDownload(item) : onPlay(item);

          return (
            <Pressable
              disabled={!item.isVideo}
              onPress={action}
              style={({ pressed }) => [
                styles.row,
                item.isVideo ? styles.playable : styles.muted,
                pressed && item.isVideo && styles.pressed,
              ]}
            >
              <View
                style={[
                  styles.mark,
                  item.isVideo && styles.markVideo,
                  item.isSubtitle && styles.markSub,
                ]}
              >
                <Icon
                  name={item.isVideo ? (downloading ? 'download' : 'play') : item.isSubtitle ? 'type' : 'file'}
                  size={14}
                  color={item.isVideo ? colors.accent : colors.faint}
                />
              </View>

              <View style={styles.body}>
                <Text
                  numberOfLines={2}
                  style={[styles.name, !item.isVideo && styles.nameMuted]}
                >
                  {item.name}
                </Text>
                <Text style={styles.size}>
                  {bytes(item.size)}
                  {item.isSubtitle
                    ? ' · subtitle'
                    : item.isVideo
                      ? ''
                      : ' · not playable'}
                </Text>
              </View>

              {item.isVideo && (
                <View style={styles.actions}>
                  {!downloading && (
                    <Pressable
                      onPress={() => onDownload(item)}
                      hitSlop={8}
                      style={styles.sideBtn}
                    >
                      <Icon name="download" size={14} color={colors.muted} />
                    </Pressable>
                  )}
                  <View style={[styles.goBtn, downloading && styles.goBtnFill]}>
                    <Icon
                      name={downloading ? 'download' : 'chevron-right'}
                      size={14}
                      color={downloading ? colors.onAccent : colors.accent}
                    />
                  </View>
                </View>
              )}
            </Pressable>
          );
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.bg },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.sm,
    paddingBottom: space.md,
    // Same gutter and tone as every other screen's header — see Screen.tsx.
    paddingHorizontal: space.xl,
    backgroundColor: colors.bg,
  },
  headerText: { flex: 1, gap: 2 },
  title: { ...type.title, fontFamily: font.semibold, fontSize: 16, color: colors.text },
  subtitle: { ...type.meta, color: colors.faint },
  modeChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    paddingHorizontal: space.sm,
    paddingVertical: 5,
    borderRadius: radius.pill,
    backgroundColor: colors.accentSoft,
  },
  modeChipDownload: { backgroundColor: colors.accent },
  modeChipText: { ...type.meta, fontSize: 11, fontFamily: font.semibold, color: colors.accent },
  modeChipTextOn: { color: colors.onAccent },

  hintBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.sm,
    paddingHorizontal: space.xl,
    paddingVertical: space.md,
    backgroundColor: colors.surface,
  },
  hintText: { ...type.meta, color: colors.muted, flex: 1 },

  list: { padding: space.lg, paddingBottom: space.xxl },
  folder: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.sm,
    marginTop: space.lg,
    marginBottom: space.sm,
    paddingHorizontal: space.xs,
  },
  folderName: { ...type.overline, color: colors.faint, flex: 1 },
  folderCount: { ...type.meta, fontSize: 11, color: colors.faint },

  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    padding: space.md,
    marginBottom: space.sm,
    borderRadius: radius.lg,
  },
  playable: { backgroundColor: colors.surface },
  muted: { backgroundColor: 'transparent', opacity: 0.65 },
  pressed: { opacity: 0.65 },
  mark: {
    width: 38,
    height: 38,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  markVideo: { backgroundColor: colors.accentSoft },
  markSub: { backgroundColor: colors.surface },
  body: { flex: 1, gap: 2 },
  name: { ...type.body, fontFamily: font.medium, color: colors.text },
  nameMuted: { fontFamily: font.regular, color: colors.muted },
  size: { ...type.meta, color: colors.faint },
  actions: { flexDirection: 'row', alignItems: 'center', gap: space.xs },
  sideBtn: {
    width: 32,
    height: 32,
    borderRadius: radius.pill,
    backgroundColor: colors.control,
    alignItems: 'center',
    justifyContent: 'center',
  },
  goBtn: {
    width: 32,
    height: 32,
    borderRadius: radius.pill,
    backgroundColor: colors.accentSoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  goBtnFill: { backgroundColor: colors.accent },
});
