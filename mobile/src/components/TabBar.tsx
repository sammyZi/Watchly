import { Pressable, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icon, type IconName } from './Icon';
import { fadeUp, font, radius, space, type, useColors, useThemedStyles } from '../theme';

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
  const insets = useSafeAreaInsets();
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    dock: {
      position: 'absolute' as const,
      left: 0,
      right: 0,
      bottom: 0,
      paddingHorizontal: space.lg,
      paddingTop: space.xxl,
    },
    veil: {
      position: 'absolute' as const,
      left: 0,
      right: 0,
      bottom: 0,
      top: 0,
      experimental_backgroundImage: fadeUp(c.bg),
    },
    bar: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      height: 62,
      borderRadius: radius.pill,
      backgroundColor: c.tabBar,
      paddingHorizontal: space.sm,
    },
    tab: { flex: 1, alignItems: 'center' as const, gap: 3 },
    pressed: { opacity: 0.4 },
    label: {
      ...type.meta,
      fontSize: 10,
      lineHeight: 13,
      letterSpacing: 0.1,
      color: c.faint,
    },
    labelOn: { color: c.accent, fontFamily: font.medium },
  }));

  return (
    <View
      style={[styles.dock, { paddingBottom: Math.max(insets.bottom, space.md) }]}
      pointerEvents="box-none"
    >
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
