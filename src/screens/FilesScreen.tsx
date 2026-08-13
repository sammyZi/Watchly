import { useMemo } from 'react';
import {
  Pressable,
  SectionList,
  StatusBar,
  Text,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { IconButton } from '../components/Button';
import { Icon } from '../components/Icon';
import { Marquee } from '../components/Marquee';
import { bytes } from '../lib/format';
import { groupByFolder } from '../lib/pick';
import { parseEpisode } from '../lib/search';
import { searchableName } from '../lib/title';
import type { TorrentFile, TorrentMeta } from '../../modules/torrent-stream';
import { font, radius, space, type, useColors, useTheme, useThemedStyles } from '../theme';

/** Files inside the torrent, grouped by folder and in natural order. */
export function FilesScreen({
  meta,
  mode,
  onPlay,
  onDownload,
  onDownloadAll,
  onBack,
}: {
  meta: TorrentMeta;
  mode?: 'stream' | 'download';
  onPlay: (file: TorrentFile) => void;
  onDownload: (file: TorrentFile) => void;
  onDownloadAll?: () => void;
  onBack: () => void;
}) {
  const sections = useMemo(
    () =>
      groupByFolder(meta.files).map((g) => ({
        title: g.folder || meta.name,
        data: g.files
          .slice()
          .sort((a, b) => Number(b.isVideo) - Number(a.isVideo)),
      })),
    [meta]
  );

  const videos = meta.files.filter((f) => f.isVideo).length;
  const insets = useSafeAreaInsets();
  const downloading = mode === 'download';
  const colors = useColors();
  const { scheme } = useTheme();
  const styles = useThemedStyles((c) => ({
    screen: { flex: 1, backgroundColor: c.bg },
    header: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.sm,
      paddingBottom: space.md,
      // Same gutter and tone as every other screen's header — see Screen.tsx.
      paddingHorizontal: space.xl,
      backgroundColor: c.bg,
    },
    headerText: { flex: 1, gap: 2 },
    title: { ...type.title, fontFamily: font.semibold, fontSize: 16, color: c.text },
    subtitle: { ...type.meta, color: c.faint },
    modeChip: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: 4,
      paddingHorizontal: space.sm,
      paddingVertical: 5,
      borderRadius: radius.pill,
      backgroundColor: c.accentSoft,
    },
    modeChipDownload: { backgroundColor: c.accent },
    modeChipText: {
      ...type.meta,
      fontSize: 11,
      fontFamily: font.semibold,
      color: c.accent,
    },
    modeChipTextOn: { color: c.onAccent },

    list: { padding: space.lg, paddingBottom: space.xxl },
    folder: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.sm,
      marginTop: space.lg,
      marginBottom: space.sm,
      paddingHorizontal: space.xs,
    },
    folderName: { ...type.overline, color: c.faint, flex: 1 },
    folderCount: { ...type.meta, fontSize: 11, color: c.faint },

    row: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      padding: space.sm,
      marginBottom: space.sm,
      borderRadius: radius.md,
    },
    playable: { backgroundColor: c.surface },
    muted: { backgroundColor: 'transparent', opacity: 0.5 },
    pressed: { opacity: 0.65 },
    mark: {
      width: 56,
      height: 56,
      borderRadius: radius.sm,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      backgroundColor: c.control,
    },
    markVideo: { backgroundColor: c.accentSoft },
    markSub: { backgroundColor: c.surface },
    ep: { ...type.title, fontFamily: font.semibold, fontSize: 16, lineHeight: 20 },
    epOn: { color: c.accent },
    epOff: { color: c.faint },
    body: { flex: 1, gap: 2 },
    name: { ...type.body, fontFamily: font.medium, color: c.text },
    nameMuted: { fontFamily: font.regular, color: c.muted },
    size: { ...type.meta, color: c.faint },
    actions: { flexDirection: 'row' as const, alignItems: 'center' as const, gap: space.xs },
    sideBtn: {
      width: 32,
      height: 32,
      borderRadius: radius.pill,
      backgroundColor: c.control,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    goBtn: {
      width: 32,
      height: 32,
      borderRadius: radius.pill,
      backgroundColor: c.accentSoft,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    goBtnFill: { backgroundColor: c.accent },
  }));

  return (
    <View style={styles.screen}>
      <StatusBar
        barStyle={scheme === 'light' ? 'dark-content' : 'light-content'}
        backgroundColor={colors.bg}
      />

      <View style={[styles.header, { paddingTop: insets.top + space.sm }]}>
        <IconButton
          name="chevron-left"
          onPress={onBack}
          color={colors.accent}
          background={colors.accentSoft}
        />
        <View style={styles.headerText}>
          <Text numberOfLines={1} style={styles.title}>
            {searchableName(meta.name) || meta.name}
          </Text>
          <Text style={styles.subtitle}>
            {videos} {videos === 1 ? 'episode' : 'episodes'}
          </Text>
        </View>
        {downloading && videos > 1 && onDownloadAll ? (
          <Pressable onPress={onDownloadAll} hitSlop={8} style={styles.modeChip}>
            <Icon name="download" size={12} color={colors.accent} />
            <Text style={styles.modeChipText}>All {videos}</Text>
          </Pressable>
        ) : (
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
        )}
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
          const at = item.isVideo ? parseEpisode(item.name) : null;

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
                <Text
                  style={[
                    styles.ep,
                    item.isVideo ? styles.epOn : styles.epOff,
                  ]}
                >
                  {at ? String(at.episode) : ''}
                </Text>
                {!at && (
                  <Icon
                    name={item.isVideo ? (downloading ? 'download' : 'play') : item.isSubtitle ? 'type' : 'file'}
                    size={14}
                    color={item.isVideo ? colors.accent : colors.faint}
                  />
                )}
              </View>

              <View style={styles.body}>
                <Marquee
                  style={[styles.name, !item.isVideo && styles.nameMuted]}
                >
                  {item.name}
                </Marquee>
                <Text style={styles.size} numberOfLines={1}>
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
