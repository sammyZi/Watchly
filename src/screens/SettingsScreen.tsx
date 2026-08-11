import { KeyboardAvoidingView, Platform, Pressable, ScrollView, Switch, Text, TextInput, View } from 'react-native';
import { memo, useRef, useState } from 'react';
import { Icon, type IconName } from '../components/Icon';
import { Screen } from '../components/Screen';
import type { Settings } from '../lib/settings';
import {
  font,
  radius,
  space,
  TAB_INSET,
  type,
  useColors,
  useThemedStyles,
  type ThemeMode,
} from '../theme';

const BUFFER_CHOICES = [2, 3, 6];
const APPEARANCE: { mode: ThemeMode; label: string }[] = [
  { mode: 'system', label: 'System' },
  { mode: 'light', label: 'Light' },
  { mode: 'dark', label: 'Dark' },
];

function SettingsScreenView({
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
  const [showKey, setShowKey] = useState(false);
  const scrollRef = useRef<ScrollView>(null);
  const colors = useColors();
  const styles = useSettingsStyles();
  const set = <K extends keyof Settings>(key: K, value: Settings[K]) =>
    onChange({ ...settings, [key]: value });

  return (
    <KeyboardAvoidingView
      style={{ flex: 1 }}
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
    >
    <Screen title="Settings" icon="sliders">
        <ScrollView
          ref={scrollRef}
          contentContainerStyle={styles.body}
          showsVerticalScrollIndicator={false}
          keyboardShouldPersistTaps="handled"
        >
        <Group title="Appearance">
          <View style={styles.choiceRow}>
            <Row
              icon="sun"
              title="Theme"
              subtitle="Match the system, or lock light or dark"
            />
            <View style={styles.choices}>
              {APPEARANCE.map(({ mode, label }) => {
                const on = settings.themeMode === mode;
                return (
                  <Pressable
                    key={mode}
                    onPress={() => set('themeMode', mode)}
                    style={[styles.choice, on && styles.choiceOn]}
                  >
                    <Text style={[styles.choiceText, on && styles.choiceTextOn]}>
                      {label}
                    </Text>
                  </Pressable>
                );
              })}
            </View>
          </View>
        </Group>

        <View style={styles.note}>
          <View style={styles.noteHead}>
            <View style={styles.noteMark}>
              <Icon name="hard-drive" size={15} color={colors.accent} />
            </View>
            <Text style={styles.noteTitle}>How storage works here</Text>
          </View>
          <Text style={styles.noteText}>
            A stream holds what you have watched — an hour of a 3 GB film is
            about 2 GB — and clears it on stop. Needs 512 MB free to start. For
            4K remuxes use Download instead.
          </Text>
        </View>

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

        <Group title="Subtitles">
          <View style={styles.row}>
            <View style={styles.rowIcon}>
              <Icon name="type" size={16} color={colors.accent} />
            </View>
            <View style={styles.rowText}>
              <Text style={styles.rowTitle}>OpenSubtitles API key</Text>
              <Text style={styles.rowSubtitle}>
                Free signup at opensubtitles.com
              </Text>
            </View>
          </View>
          <View style={styles.keyRow}>
            <TextInput
              style={styles.apiInput}
              value={showKey ? settings.openSubtitlesKey : (settings.openSubtitlesKey ? '••••••••••••••••••••••••' : '')}
              onChangeText={(v) => set('openSubtitlesKey', v)}
              placeholder="Paste your API key..."
              placeholderTextColor={colors.faint}
              onFocus={() => {
                setTimeout(() => {
                  scrollRef.current?.scrollToEnd({ animated: true });
                }, 300);
              }}
              autoCapitalize="none"
              autoCorrect={false}
              secureTextEntry={!showKey}
            />
            <Pressable
              onPress={() => setShowKey((s) => !s)}
              hitSlop={8}
              style={styles.eyeBtn}
            >
              <Icon name={showKey ? 'eye-off' : 'eye'} size={16} color={colors.faint} />
            </Pressable>
          </View>
        </Group>

        <Text style={styles.footer}>
          Watchly · movies and series, ready when you are. You are responsible
          for what you watch and download.
        </Text>
      </ScrollView>
    </Screen>
    </KeyboardAvoidingView>
  );
}

function useSettingsStyles() {
  return useThemedStyles((c) => ({
    body: { padding: space.xl, gap: space.xl, paddingBottom: TAB_INSET },
    note: {
      gap: space.sm,
      padding: space.lg,
      borderRadius: radius.lg,
      backgroundColor: c.surface,
    },
    noteHead: { flexDirection: 'row' as const, alignItems: 'center' as const, gap: space.md },
    noteMark: {
      width: 30,
      height: 30,
      borderRadius: radius.sm,
      backgroundColor: c.accentSoft,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    noteTitle: {
      ...type.label,
      fontFamily: font.semibold,
      fontSize: 14,
      color: c.text,
    },
    noteText: { ...type.meta, color: c.muted, lineHeight: 18 },
    group: { gap: space.md },
    groupTitle: { ...type.overline, color: c.faint },
    card: {
      backgroundColor: c.surface,
      borderRadius: radius.lg,
      paddingHorizontal: space.lg,
    },
    row: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      paddingVertical: space.lg,
    },
    rowIcon: {
      width: 34,
      height: 34,
      borderRadius: radius.md,
      backgroundColor: c.accentSoft,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    rowIconDanger: { backgroundColor: c.dangerSoft },
    rowText: { flex: 1, gap: 2 },
    rowTitle: { ...type.title, color: c.text },
    rowTitleDanger: { color: c.danger },
    rowSubtitle: { ...type.meta, color: c.faint },
    divider: { height: 1, backgroundColor: c.line, marginLeft: 34 + space.md },
    choiceRow: { paddingBottom: space.lg },
    choices: { flexDirection: 'row' as const, gap: space.sm, marginLeft: 34 + space.md },
    choice: {
      paddingHorizontal: space.lg,
      paddingVertical: space.sm,
      borderRadius: radius.pill,
      backgroundColor: c.surfaceAlt,
    },
    choiceOn: { backgroundColor: c.accent },
    choiceText: { ...type.label, color: c.muted },
    choiceTextOn: { color: c.onAccent, fontFamily: font.semibold },
    footer: {
      ...type.meta,
      color: c.faint,
      textAlign: 'center' as const,
      paddingHorizontal: space.lg,
    },
    apiInput: {
      flex: 1,
      ...type.body,
      color: c.text,
      backgroundColor: c.surfaceAlt,
      borderRadius: radius.md,
      paddingHorizontal: space.lg,
      paddingVertical: space.sm,
    },
    keyRow: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.sm,
      marginBottom: space.lg,
    },
    eyeBtn: {
      width: 36,
      height: 36,
      borderRadius: radius.md,
      backgroundColor: c.surfaceAlt,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
  }));
}

function Group({ title, children }: { title: string; children: React.ReactNode }) {
  const styles = useSettingsStyles();
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
  const colors = useColors();
  const styles = useSettingsStyles();
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
}) => {
  const colors = useColors();
  return (
    <Switch
      value={value}
      onValueChange={onChange}
      trackColor={{ false: colors.surfaceAlt, true: colors.accent }}
      thumbColor={colors.control}
    />
  );
};

const Divider = () => {
  const styles = useSettingsStyles();
  return <View style={styles.divider} />;
};

/** Kept mounted behind the other tabs — see TabPage in App. */
export const SettingsScreen = memo(SettingsScreenView);
