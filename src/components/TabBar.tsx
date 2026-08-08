import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icon, type IconName } from './Icon';
import { colors, font, radius, space, type } from '../theme';

export type TabKey = 'stream' | 'history' | 'settings';

const TABS: { key: TabKey; label: string; icon: IconName }[] = [
  { key: 'stream', label: 'Stream', icon: 'play-circle' },
  { key: 'history', label: 'Library', icon: 'clock' },
  { key: 'settings', label: 'Settings', icon: 'sliders' },
];

/**
 * Floating capsule rather than an edge-to-edge bar.
 *
 * Selection stays iOS-flat — tint only, no pill behind the icon, no ripple —
 * and the capsule separates from the page by tone, like everything else here.
 */
export function TabBar({
  active,
  onChange,
}: {
  active: TabKey;
  onChange: (key: TabKey) => void;
}) {
  // Gesture bar on Android, home indicator on iOS. Floors to a real margin so
  // the capsule never sits flush on a device without either.
  const insets = useSafeAreaInsets();

  return (
    <View style={[styles.dock, { paddingBottom: Math.max(insets.bottom, space.md) }]}>
      <View style={styles.bar}>
        {TABS.map((tab) => {
          const on = tab.key === active;
          return (
            <Pressable
              key={tab.key}
              style={({ pressed }) => [styles.tab, pressed && styles.pressed]}
              onPress={() => onChange(tab.key)}
              accessibilityRole="tab"
              accessibilityLabel={tab.label}
              accessibilityState={{ selected: on }}
            >
              <Icon name={tab.icon} size={22} color={on ? colors.accent : colors.faint} />
              <Text style={[styles.label, on && styles.labelOn]}>{tab.label}</Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  // Holds the inset, so the capsule itself stays a fixed shape on every device.
  dock: { paddingHorizontal: space.lg, paddingTop: space.sm },
  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    height: 62,
    borderRadius: radius.pill,
    // Tinted, not white: with no shadow this is the only thing separating the
    // capsule from the page, and it is the same step cards use.
    backgroundColor: colors.surface,
    paddingHorizontal: space.sm,
  },
  tab: { flex: 1, alignItems: 'center', gap: 3 },
  pressed: { opacity: 0.4 },
  label: {
    ...type.meta,
    fontSize: 10,
    lineHeight: 13,
    letterSpacing: 0.1,
    color: colors.faint,
  },
  labelOn: { color: colors.accent, fontFamily: font.medium },
});
