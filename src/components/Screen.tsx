import { StatusBar, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icon, type IconName } from './Icon';
import { colors, radius, space, type } from '../theme';

/** Shared page chrome: status bar, title row, consistent gutters. */
export function Screen({
  title,
  icon,
  action,
  children,
}: {
  title: string;
  icon?: IconName;
  action?: React.ReactNode;
  children: React.ReactNode;
}) {
  // Real inset rather than StatusBar.currentHeight: that misses the cutout on a
  // notched device and is wrong the moment the app is not full-bleed.
  const insets = useSafeAreaInsets();

  return (
    <View style={styles.screen}>
      <StatusBar barStyle="dark-content" backgroundColor={colors.control} />
      <View style={[styles.header, { paddingTop: insets.top + space.md }]}>
        {!!icon && (
          <View style={styles.mark}>
            <Icon name={icon} size={17} color={colors.accent} />
          </View>
        )}
        <Text style={styles.title}>{title}</Text>
        <View style={styles.spacer} />
        {action}
      </View>
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.bg },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    paddingBottom: space.md,
    paddingHorizontal: space.xl,
    backgroundColor: colors.control,
  },
  mark: {
    width: 34,
    height: 34,
    borderRadius: radius.md,
    backgroundColor: colors.accentSoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  title: { ...type.display, color: colors.text },
  spacer: { flex: 1 },
});
