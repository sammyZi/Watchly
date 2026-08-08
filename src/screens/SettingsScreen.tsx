import { Pressable, ScrollView, StyleSheet, Switch, Text, View } from 'react-native';
import { Icon, type IconName } from '../components/Icon';
import { Screen } from '../components/Screen';
import type { Settings } from '../lib/settings';
import { colors, font, radius, space, type } from '../theme';

const BUFFER_CHOICES = [2, 3, 6];

export function SettingsScreen({
  settings,
  onChange,
  onClearHistory,
  historyCount,
}: {
  settings: Settings;
  onChange: (next: Settings) => void;
  onClearHistory: () => void;
  historyCount: number;
}) {
  const set = <K extends keyof Settings>(key: K, value: Settings[K]) =>
    onChange({ ...settings, [key]: value });

  return (
    <Screen title="Settings" icon="sliders">
      <ScrollView
        contentContainerStyle={styles.body}
        showsVerticalScrollIndicator={false}
      >
        <Group title="Startup speed">
          <Row
            icon="radio"
            title="Add public trackers"
            subtitle="Bare magnet links resolve much faster than over DHT alone"
          >
            <Toggle
              value={settings.addTrackers}
              onChange={(v) => set('addTrackers', v)}
            />
          </Row>

          <Divider />

          <View style={styles.choiceRow}>
            <Row
              icon="fast-forward"
              title="Buffer before playback"
              subtitle="Less buffer starts sooner but stalls more easily"
            />
            <View style={styles.choices}>
              {BUFFER_CHOICES.map((mb) => {
                const on = settings.prepareMb === mb;
                return (
                  <Pressable
                    key={mb}
                    onPress={() => set('prepareMb', mb)}
                    style={[styles.choice, on && styles.choiceOn]}
                  >
                    <Text style={[styles.choiceText, on && styles.choiceTextOn]}>
                      {mb} MB
                    </Text>
                  </Pressable>
                );
              })}
            </View>
          </View>
        </Group>

        <Group title="Storage">
          <Row
            icon="trash"
            title="Delete files after stopping"
            subtitle="Keep the cache small; turn off to re-watch without re-downloading"
          >
            <Toggle
              value={settings.removeFilesAfterStop}
              onChange={(v) => set('removeFilesAfterStop', v)}
            />
          </Row>

          <Divider />

          <Pressable onPress={onClearHistory} disabled={!historyCount}>
            <Row
              icon="rotate-ccw"
              title="Clear watch history"
              subtitle={
                historyCount ? `${historyCount} item(s) stored` : 'Nothing stored'
              }
              danger={!!historyCount}
            >
              <Icon name="chevron-right" size={18} color={colors.faint} />
            </Row>
          </Pressable>
        </Group>

        <Text style={styles.footer}>
          Torrent Viewer · streams over BitTorrent while downloading. You are
          responsible for what you download.
        </Text>
      </ScrollView>
    </Screen>
  );
}

function Group({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={styles.group}>
      <Text style={styles.groupTitle}>{title}</Text>
      <View style={styles.card}>{children}</View>
    </View>
  );
}

function Row({
  icon,
  title,
  subtitle,
  danger,
  children,
}: {
  icon: IconName;
  title: string;
  subtitle?: string;
  danger?: boolean;
  children?: React.ReactNode;
}) {
  return (
    <View style={styles.row}>
      <View style={[styles.rowIcon, danger && styles.rowIconDanger]}>
        <Icon name={icon} size={16} color={danger ? colors.danger : colors.accent} />
      </View>
      <View style={styles.rowText}>
        <Text style={[styles.rowTitle, danger && styles.rowTitleDanger]}>{title}</Text>
        {!!subtitle && <Text style={styles.rowSubtitle}>{subtitle}</Text>}
      </View>
      {children}
    </View>
  );
}

const Toggle = ({
  value,
  onChange,
}: {
  value: boolean;
  onChange: (v: boolean) => void;
}) => (
  <Switch
    value={value}
    onValueChange={onChange}
    trackColor={{ false: colors.surfaceAlt, true: colors.accent }}
    thumbColor={colors.control}
  />
);

const Divider = () => <View style={styles.divider} />;

const styles = StyleSheet.create({
  body: { padding: space.xl, gap: space.xl, paddingBottom: space.xxl },
  group: { gap: space.md },
  groupTitle: { ...type.overline, color: colors.faint },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    paddingHorizontal: space.lg,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    paddingVertical: space.lg,
  },
  rowIcon: {
    width: 34,
    height: 34,
    borderRadius: radius.md,
    backgroundColor: colors.accentSoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  rowIconDanger: { backgroundColor: colors.dangerSoft },
  rowText: { flex: 1, gap: 2 },
  rowTitle: { ...type.title, color: colors.text },
  rowTitleDanger: { color: colors.danger },
  rowSubtitle: { ...type.meta, color: colors.faint },
  divider: { height: 1, backgroundColor: colors.line, marginLeft: 34 + space.md },
  choiceRow: { paddingBottom: space.lg },
  choices: { flexDirection: 'row', gap: space.sm, marginLeft: 34 + space.md },
  choice: {
    paddingHorizontal: space.lg,
    paddingVertical: space.sm,
    borderRadius: radius.pill,
    backgroundColor: colors.surfaceAlt,
  },
  choiceOn: { backgroundColor: colors.accent },
  choiceText: { ...type.label, color: colors.muted },
  choiceTextOn: { color: colors.onAccent, fontFamily: font.semibold },
  footer: {
    ...type.meta,
    color: colors.faint,
    textAlign: 'center',
    paddingHorizontal: space.lg,
  },
});
