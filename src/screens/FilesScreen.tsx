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
  onPlay,
  onBack,
}: {
  meta: TorrentMeta;
  onPlay: (file: TorrentFile) => void;
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

  return (
    <View style={styles.screen}>
      <StatusBar barStyle="dark-content" backgroundColor={colors.control} />

      <View style={[styles.header, { paddingTop: insets.top + space.sm }]}>
        <IconButton name="chevron-left" onPress={onBack} background="transparent" />
        <View style={styles.headerText}>
          <Text numberOfLines={1} style={styles.title}>
            {meta.name}
          </Text>
          <Text style={styles.subtitle}>
            {videos} playable · {meta.files.length} files total
          </Text>
        </View>
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
            </View>
          ) : null
        }
        renderItem={({ item, index }) => (
          <Pressable
            disabled={!item.isVideo}
            onPress={() => onPlay(item)}
            style={({ pressed }) => [
              styles.row,
              item.isVideo ? styles.playable : styles.muted,
              pressed && styles.pressed,
            ]}
          >
            {item.isVideo ? (
              <View style={styles.number}>
                <Text style={styles.numberText}>{index + 1}</Text>
              </View>
            ) : (
              <View style={styles.fileIcon}>
                <Icon name="file" size={15} color={colors.faint} />
              </View>
            )}

            <View style={styles.body}>
              <Text numberOfLines={2} style={[styles.name, !item.isVideo && styles.nameMuted]}>
                {item.name}
              </Text>
              <Text style={styles.size}>
                {bytes(item.size)}
                {item.isVideo ? '' : ' · not playable'}
              </Text>
            </View>

            {item.isVideo && (
              <View style={styles.play}>
                <Icon name="play" size={14} color={colors.accent} />
              </View>
            )}
          </Pressable>
        )}
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
    paddingHorizontal: space.md,
    backgroundColor: colors.control,
  },
  headerText: { flex: 1 },
  title: { ...type.title, fontFamily: font.semibold, fontSize: 16, color: colors.text },
  subtitle: { ...type.meta, color: colors.faint },

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

  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    padding: space.md,
    marginBottom: space.sm,
    borderRadius: radius.md,
  },
  playable: { backgroundColor: colors.surface },
  muted: { backgroundColor: 'transparent', opacity: 0.7 },
  pressed: { opacity: 0.6 },
  number: {
    width: 34,
    height: 34,
    borderRadius: radius.sm,
    backgroundColor: colors.accentSoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  numberText: { ...type.label, color: colors.accent, fontFamily: font.semibold },
  fileIcon: {
    width: 34,
    height: 34,
    alignItems: 'center',
    justifyContent: 'center',
  },
  body: { flex: 1, gap: 2 },
  name: { ...type.body, fontFamily: font.medium, color: colors.text },
  nameMuted: { fontFamily: font.regular, color: colors.muted },
  size: { ...type.meta, color: colors.faint },
  play: {
    width: 30,
    height: 30,
    borderRadius: radius.pill,
    backgroundColor: colors.accentSoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
