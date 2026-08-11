import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icon, type IconName } from './Icon';
import { colors, fadeUp, font, radius, space, type } from '../theme';

export type TabKey = 'home' | 'search' | 'history' | 'downloads' | 'settings';

const TABS: { key: TabKey; label: string; icon: IconName }[] = [
  { key: 'home', label: 'Home', icon: 'home' },
  { key: 'search', label: 'Search', icon: 'search' },
  { key: 'history', label: 'Library', icon: 'clock' },
  { key: 'downloads', label: 'Downloads', icon: 'download' },
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
    <View
      style={[styles.dock, { paddingBottom: Math.max(insets.bottom, space.md) }]}
      pointerEvents="box-none"
    >
      {/* The page dissolves into the bar instead of sliding under a hard edge.
          A true frosted blur needs expo-blur — a native module for one strip. */}
      <View style={styles.veil} pointerEvents="none" />
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
  // Floats over the content — every scrolling screen ends TAB_INSET above its
  // own bottom edge to leave room.
  dock: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    paddingHorizontal: space.lg,
    paddingTop: space.xxl,
  },
  veil: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    top: 0,
    experimental_backgroundImage: fadeUp,
  },
  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    height: 62,
    borderRadius: radius.pill,
    // Translucent, so the artwork scrolling past shows through the capsule.
    backgroundColor: '#181d28d9',
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
