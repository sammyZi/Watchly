import { StatusBar, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icon, type IconName } from './Icon';
import { radius, space, type, useTheme, useThemedStyles } from '../theme';

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
  const insets = useSafeAreaInsets();
  const { colors, scheme } = useTheme();
  const styles = useThemedStyles((c) => ({
    screen: { flex: 1, backgroundColor: c.bg },
    header: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      paddingBottom: space.md,
      paddingHorizontal: space.xl,
      backgroundColor: c.bg,
    },
    mark: {
      width: 34,
      height: 34,
      borderRadius: radius.md,
      backgroundColor: c.accentSoft,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    title: { ...type.display, color: c.text },
    spacer: { flex: 1 },
  }));

  return (
    <View style={styles.screen}>
      <StatusBar
        barStyle={scheme === 'light' ? 'dark-content' : 'light-content'}
        backgroundColor={colors.bg}
      />
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
