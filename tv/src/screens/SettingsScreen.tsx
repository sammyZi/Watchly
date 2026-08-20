import { ScrollView, Text, View } from 'react-native';
import { Focusable } from '../components/Focusable';
import { Icon } from '../components/Icon';
import { CACHE_CAPS, type Settings } from '../lib/settings';
import { GENRES } from '../lib/meta';
import { GUTTER, SAFE, font, radius, space, type, useColors, useThemedStyles } from '../theme';

const BUFFERS = [1, 4, 8, 16];

/** A row of mutually exclusive choices, each one a focus target. */
function Choice<T extends string | number | boolean>({
  options,
  value,
  label,
  onPick,
  first,
}: {
  options: readonly T[];
  value: T;
  label: (option: T) => string;
  onPick: (option: T) => void;
  first?: boolean;
}) {
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    row: { flexDirection: 'row' as const, flexWrap: 'wrap' as const, gap: space.md },
    chip: {
      paddingHorizontal: space.lg,
      height: 40,
      justifyContent: 'center' as const,
      borderRadius: radius.pill,
      backgroundColor: c.control,
    },
    on: { backgroundColor: c.accent },
    text: { ...type.label, color: c.muted },
  }));

  return (
    <View style={styles.row}>
      {options.map((option, i) => {
        const on = option === value;
        return (
          <Focusable
            invert
            key={String(option)}
            first={first && i === 0}
            onPress={() => onPick(option)}
            scale={1.04}
            style={[styles.chip, on && styles.on]}
          >
            {(focused) => (
              <Text
                style={[
                  styles.text,
                  on && { color: colors.onAccent, fontFamily: font.semibold },
                  focused && { color: colors.bg },
                ]}
              >
                {label(option)}
              </Text>
            )}
          </Focusable>
        );
      })}
    </View>
  );
}

function Section({
  title,
  hint,
  children,
}: {
  title: string;
  hint: string;
  children: React.ReactNode;
}) {
  const styles = useThemedStyles((c) => ({
    wrap: { marginBottom: space.xxl },
    title: { ...type.title, fontFamily: font.semibold, color: c.text },
    hint: { ...type.meta, color: c.faint, marginBottom: space.md, maxWidth: 760 },
  }));
  return (
    <View style={styles.wrap}>
      <Text style={styles.title}>{title}</Text>
      <Text style={styles.hint}>{hint}</Text>
      {children}
    </View>
  );
}

export function SettingsScreen({
  settings,
  onChange,
}: {
  settings: Settings;
  onChange: (next: Settings) => void;
}) {
  const styles = useThemedStyles((c) => ({
    screen: { flex: 1, backgroundColor: c.bg },
    body: { paddingLeft: GUTTER, paddingRight: SAFE.x, paddingTop: SAFE.y, paddingBottom: SAFE.y * 3 },
    heading: { ...type.display, color: c.text, marginBottom: space.xl },
  }));

  const set = <K extends keyof Settings>(key: K, value: Settings[K]) =>
    onChange({ ...settings, [key]: value });

  const toggleGenre = (genre: string) =>
    set(
      'genres',
      settings.genres.includes(genre)
        ? settings.genres.filter((g) => g !== genre)
        : [...settings.genres, genre]
    );

  return (
    <View style={styles.screen}>
      <ScrollView contentContainerStyle={styles.body}>
        <Text style={styles.heading}>Settings</Text>

        <Section
          title="Storage for one film"
          hint={
            'How much of a film may sit on this box at once. A 4K film is far ' +
            'larger than that, so when the cap is reached the stream starts ' +
            'again from where you are watching — you will see it pause for a ' +
            'few seconds. Pick the largest size that fits your free space.'
          }
        >
          <Choice
            first
            options={CACHE_CAPS}
            value={settings.cacheCapMb}
            label={(mb) => `${mb / 1024} GB`}
            onPick={(mb) => set('cacheCapMb', mb)}
          />
        </Section>

        <Section
          title="Buffer before playing"
          hint="Larger starts slower but is steadier on a weak swarm."
        >
          <Choice
            options={BUFFERS}
            value={settings.prepareMb}
            label={(mb) => `${mb} MB`}
            onPick={(mb) => set('prepareMb', mb)}
          />
        </Section>

        <Section
          title="Extra trackers"
          hint="Attach public trackers to bare magnet links so they resolve faster."
        >
          <Choice
            options={[true, false] as const}
            value={settings.addTrackers}
            label={(on) => (on ? 'On' : 'Off')}
            onPick={(on) => set('addTrackers', on)}
          />
        </Section>

        <Section
          title="Genres on the home screen"
          hint="Leave all off to build the home screen from what you have watched."
        >
          <View
            style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.md, maxWidth: 1100 }}
          >
            {GENRES.map((genre) => {
              const on = settings.genres.includes(genre);
              return (
                <Focusable
                  key={genre}
                  onPress={() => toggleGenre(genre)}
                  scale={1.04}
                  style={{
                    paddingHorizontal: space.lg,
                    height: 40,
                    justifyContent: 'center',
                    borderRadius: radius.pill,
                  }}
                  focusedStyle={{ backgroundColor: '#ffffff' }}
                >
                  {(focused) => (
                    <GenreLabel genre={genre} on={on} focused={focused} />
                  )}
                </Focusable>
              );
            })}
          </View>
        </Section>
      </ScrollView>
    </View>
  );
}

function GenreLabel({
  genre,
  on,
  focused,
}: {
  genre: string;
  on: boolean;
  focused: boolean;
}) {
  const colors = useColors();
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: space.sm }}>
      {on && <Icon name="check" size={18} color={focused ? colors.bg : colors.accent} />}
      <Text
        style={{
          ...type.label,
          color: focused ? colors.bg : on ? colors.accent : colors.faint,
          fontFamily: on ? font.semibold : font.medium,
        }}
      >
        {genre}
      </Text>
    </View>
  );
}
