import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Icon, type IconName } from '../components/Icon';
import { ResumeCard } from '../components/ResumeCard';
import { Screen } from '../components/Screen';
import { SourceInput } from '../components/SourceInput';
import type { Item } from '../lib/history';
import { colors, font, radius, space, type } from '../theme';

const SHOWN = 3;

export function StreamScreen({
  recent,
  error,
  busy,
  onOpenUri,
  onPlayFile,
  onSeeAll,
}: {
  recent: Item[];
  error: string | null;
  busy: boolean;
  onOpenUri: (uri: string) => void;
  onPlayFile: (item: Item, index: number) => void;
  onSeeAll: () => void;
}) {
  const resumable = recent.filter((i) => i.lastFileIndex != null);
  const shown = resumable.slice(0, SHOWN);

  return (
    <Screen title="Torrent Viewer" icon="download-cloud">
      <ScrollView
        contentContainerStyle={styles.body}
        keyboardShouldPersistTaps="handled"
        showsVerticalScrollIndicator={false}
      >
        <SourceInput onSubmit={onOpenUri} />

        {busy && (
          <View style={styles.status}>
            <ActivityIndicator color={colors.accent} size="small" />
            <Text style={styles.statusText}>Reading torrent…</Text>
          </View>
        )}

        {!!error && (
          <View style={[styles.status, styles.statusBad]}>
            <Icon name="alert-circle" size={15} color={colors.danger} />
            <Text style={[styles.statusText, styles.statusTextBad]}>{error}</Text>
          </View>
        )}

        {shown.length > 0 ? (
          <View style={styles.section}>
            <View style={styles.sectionHead}>
              <Text style={styles.heading}>Continue watching</Text>
              {resumable.length > shown.length && (
                <Pressable onPress={onSeeAll} hitSlop={10}>
                  <Text style={styles.link}>All {resumable.length}</Text>
                </Pressable>
              )}
            </View>
            {shown.map((item) => (
              <ResumeCard
                key={item.uri}
                item={item}
                onResume={() => onPlayFile(item, item.lastFileIndex!)}
              />
            ))}
          </View>
        ) : (
          !busy && <FirstRun />
        )}
      </ScrollView>
    </Screen>
  );
}

/** Shown until there is something to carry on with — otherwise the screen is
 *  one input box floating in an empty page. */
function FirstRun() {
  return (
    <View style={styles.empty}>
      <Text style={styles.emptyTitle}>Nothing playing yet</Text>
      <Step
        icon="link-2"
        text="Paste a magnet link, or tap Paste to take it from the clipboard"
      />
      <Step icon="folder" text="Or open a .torrent file from your device" />
      <Step icon="play" text="Playback starts as pieces arrive — no full download" />
    </View>
  );
}

function Step({ icon, text }: { icon: IconName; text: string }) {
  return (
    <View style={styles.step}>
      <View style={styles.stepMark}>
        <Icon name={icon} size={13} color={colors.muted} />
      </View>
      <Text style={styles.stepText}>{text}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  body: { padding: space.xl, gap: space.lg, paddingBottom: space.xxl },
  status: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    padding: space.md,
    borderRadius: radius.md,
    backgroundColor: colors.accentSoft,
  },
  statusBad: { alignItems: 'flex-start', backgroundColor: colors.dangerSoft },
  statusText: { ...type.label, color: colors.accent, flex: 1 },
  statusTextBad: { ...type.meta, color: colors.danger },
  section: { gap: space.md, marginTop: space.sm },
  sectionHead: {
    flexDirection: 'row',
    alignItems: 'baseline',
    justifyContent: 'space-between',
  },
  heading: { ...type.overline, color: colors.faint },
  link: { ...type.meta, color: colors.accent, fontFamily: font.medium },
  empty: {
    gap: space.md,
    padding: space.lg,
    borderRadius: radius.lg,
    backgroundColor: colors.surface,
  },
  emptyTitle: { ...type.label, color: colors.text },
  step: { flexDirection: 'row', alignItems: 'center', gap: space.md },
  stepMark: {
    width: 26,
    height: 26,
    borderRadius: radius.sm,
    backgroundColor: colors.control,
    alignItems: 'center',
    justifyContent: 'center',
  },
  stepText: { ...type.meta, color: colors.muted, flex: 1 },
});
