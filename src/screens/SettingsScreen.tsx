import { KeyboardAvoidingView, Platform, Pressable, ScrollView, Switch, Text, View } from 'react-native';
import { memo, useEffect, useRef, useState } from 'react';
import TorrentStream, { type DownloadPrefs } from '../../modules/torrent-stream';
import { Icon, type IconName } from '../components/Icon';
import { Screen } from '../components/Screen';
import { SelectSheet, type SelectOption } from '../components/SelectSheet';
import { bytes } from '../lib/format';
import { LANGS } from '../lib/lang';
import { GENRES } from '../lib/meta';
import type { Settings, SubSize } from '../lib/settings';
import {
  font,
  radius,
  space,
  TAB_INSET,
  type,
  useColors,
  useTheme,
  useThemedStyles,
  type ThemeMode,
} from '../theme';

const BUFFER_CHOICES = [1, 2, 4];
const APPEARANCE: { mode: ThemeMode; label: string }[] = [
  { mode: 'system', label: 'System' },
  { mode: 'light', label: 'Light' },
  { mode: 'dark', label: 'Dark' },
];
const SIZES: SelectOption<SubSize>[] = [
  { key: 'small', label: 'Small' },
  { key: 'normal', label: 'Normal' },
  { key: 'large', label: 'Large' },
  { key: 'huge', label: 'Huge' },
];
const SUB_LANGS: SelectOption<string>[] = [
  { key: '', label: 'Off' },
  ...LANGS.map((l) => ({ key: l.code, label: l.name })),
];
const AUDIO_LANGS: SelectOption<string>[] = [
  { key: '', label: 'File default' },
  ...LANGS.map((l) => ({ key: l.code, label: l.name })),
];

/** The list a select row opens, or none. */
type Picking = 'subLang' | 'audioLang' | 'subSize' | null;

function SettingsScreenView({
  settings,
  onChange,
  onClearHistory,
  historyCount,
  onBackup,
  onRestore,
}: {
  settings: Settings;
  onChange: (next: Settings) => void;
  onClearHistory: () => void;
  historyCount: number;
  onBackup: () => void;
  onRestore: () => void;
}) {
  const scrollRef = useRef<ScrollView>(null);
  const colors = useColors();
  const { mode: themeMode, setMode } = useTheme();
  const styles = useSettingsStyles();
  const set = <K extends keyof Settings>(key: K, value: Settings[K]) =>
    onChange({ ...settings, [key]: value });
  const [picking, setPicking] = useState<Picking>(null);

  /** Kept natively — they have to hold for a download resumed with no JS. */
  const [dl, setDl] = useState<DownloadPrefs | null>(null);
  const [storageHint, setStorageHint] = useState<string | null>(null);
  const readDl = () => TorrentStream.downloadPrefs().then(setDl).catch(() => {});
  useEffect(() => {
    readDl();
  }, []);
  const chooseCard = async (on: boolean) => {
    const ok = await TorrentStream.setSdCard(on).catch(() => false);
    setStorageHint(ok ? null : 'Stop the stream and any downloads first, then switch.');
    readDl();
  };

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
                const on = themeMode === mode;
                return (
                  <Pressable
                    key={mode}
                    onPress={() => setMode(mode)}
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

        <Group title="Your genres">
          <View style={styles.choiceRow}>
            <Row
              icon="heart"
              title="Favourite genres"
              subtitle={
                settings.genres.length
                  ? `${settings.genres.length} chosen — these lead the home page`
                  : 'Pick a few and the home page opens with them'
              }
            />
            <View style={styles.genres}>
              {GENRES.map((g) => {
                const on = settings.genres.includes(g);
                return (
                  <Pressable
                    key={g}
                    onPress={() =>
                      set(
                        'genres',
                        on
                          ? settings.genres.filter((x) => x !== g)
                          : [...settings.genres, g]
                      )
                    }
                    style={[styles.choice, on && styles.choiceOn]}
                  >
                    <Text style={[styles.choiceText, on && styles.choiceTextOn]}>{g}</Text>
                  </Pressable>
                );
              })}
            </View>
          </View>
        </Group>

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
              title="Max buffer before playback"
              subtitle="Fast connections start sooner; slow ones wait for up to this much"
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

        <Group title="Subtitles & audio">
          <SelectRow
            icon="message-square"
            title="Subtitle language"
            subtitle="Turned on by itself when a video has it, and what online search looks for"
            value={labelOf(SUB_LANGS, settings.subLang)}
            onPress={() => setPicking('subLang')}
          />

          <Divider />

          <SelectRow
            icon="volume-2"
            title="Audio language"
            subtitle="Picked first when a release carries several dubs"
            value={labelOf(AUDIO_LANGS, settings.audioLang)}
            onPress={() => setPicking('audioLang')}
          />

          <Divider />

          <SelectRow
            icon="type"
            title="Subtitle size"
            subtitle="Also in the player's subtitle panel"
            value={labelOf(SIZES, settings.subSize)}
            onPress={() => setPicking('subSize')}
          />

          <Divider />

          <View style={styles.choiceRow}>
            <Row icon="droplet" title="Subtitle colour" />
            <Chips
              options={[
                { key: 'white' as const, label: 'White' },
                { key: 'yellow' as const, label: 'Yellow' },
              ]}
              value={settings.subColor}
              onPick={(v) => set('subColor', v)}
            />
          </View>

          <Divider />

          <Row
            icon="square"
            title="Background box"
            subtitle="A dark band behind the text, for bright scenes"
          >
            <Toggle value={settings.subBackground} onChange={(v) => set('subBackground', v)} />
          </Row>
        </Group>

        <Group title="Downloads">
          <Row
            icon="wifi"
            title="Download on Wi-Fi only"
            subtitle="Waits on mobile data; Resume on a download overrides it"
          >
            <Toggle
              value={!!dl?.wifiOnly}
              onChange={(v) => {
                setDl((cur) => (cur ? { ...cur, wifiOnly: v } : cur));
                TorrentStream.setWifiOnly(v).catch(() => {});
              }}
            />
          </Row>

          {!!dl?.sdAvailable && (
            <>
              <Divider />
              <View style={styles.choiceRow}>
                <Row
                  icon="hard-drive"
                  title="Save to"
                  subtitle={
                    storageHint ??
                    `${bytes(dl.internalFree)} free on the phone, ${bytes(dl.sdFree)} on the card. ` +
                      'The card copy is removed if the app is uninstalled.'
                  }
                />
                <Chips
                  options={[
                    { key: 'phone' as const, label: 'Phone' },
                    { key: 'card' as const, label: 'SD card' },
                  ]}
                  value={dl.sdCard ? 'card' : 'phone'}
                  onPick={(v) => chooseCard(v === 'card')}
                />
              </View>
            </>
          )}
        </Group>

        <Group title="Library">
          <Pressable onPress={onBackup}>
            <Row
              icon="download"
              title="Back up library"
              subtitle="History, resume points, saved lists and settings, to Downloads"
            >
              <Icon name="chevron-right" size={18} color={colors.faint} />
            </Row>
          </Pressable>

          <Divider />

          <Pressable onPress={onRestore}>
            <Row icon="upload" title="Restore from backup" subtitle="Pick a watchly-backup file">
              <Icon name="chevron-right" size={18} color={colors.faint} />
            </Row>
          </Pressable>
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
          Watchly · movies and series, ready when you are. You are responsible
          for what you watch and download.
        </Text>
      </ScrollView>
    </Screen>

    <SelectSheet
      visible={picking === 'subLang'}
      title="Subtitle language"
      options={SUB_LANGS}
      value={settings.subLang}
      onPick={(v) => set('subLang', v)}
      onClose={() => setPicking(null)}
    />
    <SelectSheet
      visible={picking === 'audioLang'}
      title="Audio language"
      options={AUDIO_LANGS}
      value={settings.audioLang}
      onPick={(v) => set('audioLang', v)}
      onClose={() => setPicking(null)}
    />
    <SelectSheet
      visible={picking === 'subSize'}
      title="Subtitle size"
      options={SIZES}
      value={settings.subSize}
      onPick={(v) => set('subSize', v)}
      onClose={() => setPicking(null)}
    />
    </KeyboardAvoidingView>
  );
}

const labelOf = <T extends string>(options: SelectOption<T>[], key: T) =>
  options.find((o) => o.key === key)?.label ?? options[0]?.label ?? '';

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
    // Same chips as the theme picker, but there are twenty of them, so they
    // wrap and take the full width rather than sitting in the icon's gutter.
    genres: {
      flexDirection: 'row' as const,
      flexWrap: 'wrap' as const,
      gap: space.sm,
      paddingHorizontal: space.lg,
    },
    choice: {
      paddingHorizontal: space.lg,
      paddingVertical: space.sm,
      borderRadius: radius.pill,
      backgroundColor: c.surfaceAlt,
    },
    choiceOn: { backgroundColor: c.accent },
    selectValue: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: 2,
      maxWidth: 130,
    },
    selectValueText: { ...type.label, color: c.accent, fontFamily: font.medium, flexShrink: 1 },
    choiceText: { ...type.label, color: c.muted },
    choiceTextOn: { color: c.onAccent, fontFamily: font.semibold },
    footer: {
      ...type.meta,
      color: c.faint,
      textAlign: 'center' as const,
      paddingHorizontal: space.lg,
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

/** A row of choice chips, for two or three short options. Longer lists are a
 *  SelectRow — a sideways-scrolling row hid both the options and the pick. */
function Chips<T extends string>({
  options,
  value,
  onPick,
}: {
  options: { key: T; label: string }[];
  value: T;
  onPick: (key: T) => void;
}) {
  const styles = useSettingsStyles();
  const chips = options.map(({ key, label }) => {
    const on = value === key;
    return (
      <Pressable key={key} onPress={() => onPick(key)} style={[styles.choice, on && styles.choiceOn]}>
        <Text style={[styles.choiceText, on && styles.choiceTextOn]}>{label}</Text>
      </Pressable>
    );
  });
  return <View style={styles.choices}>{chips}</View>;
}

/** A setting with many choices: the current one on the right, the list on tap. */
function SelectRow({
  icon,
  title,
  subtitle,
  value,
  onPress,
}: {
  icon: IconName;
  title: string;
  subtitle?: string;
  value: string;
  onPress: () => void;
}) {
  const colors = useColors();
  const styles = useSettingsStyles();
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={`${title}: ${value}`}
      style={({ pressed }) => pressed && { opacity: 0.6 }}
    >
      <Row icon={icon} title={title} subtitle={subtitle}>
        <View style={styles.selectValue}>
          <Text style={styles.selectValueText} numberOfLines={1}>
            {value}
          </Text>
          <Icon name="chevron-right" size={16} color={colors.faint} />
        </View>
      </Row>
    </Pressable>
  );
}

const Divider = () => {
  const styles = useSettingsStyles();
  return <View style={styles.divider} />;
};

/** Kept mounted behind the other tabs — see TabPage in App. */
export const SettingsScreen = memo(SettingsScreenView);
