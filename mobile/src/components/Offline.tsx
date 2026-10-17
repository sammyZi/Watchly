import { Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icon } from './Icon';
import { useOnline } from '../lib/net';
import { font, radius, shadow, space, type, useColors, useThemedStyles } from '../theme';

/**
 * The one connectivity indicator, floated over whatever screen is up.
 *
 * A strip rather than a full-page error: the shelves, the library and anything
 * already downloaded still work from cache while it is showing, so taking the
 * screen away would be a lie. Every failed load re-runs on its own once the
 * connection is back — see `useOnline` in each screen — so there is nothing to
 * tap here.
 */
export function OfflineBanner() {
  const online = useOnline();
  const insets = useSafeAreaInsets();
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    bar: {
      position: 'absolute' as const,
      alignSelf: 'center' as const,
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.sm,
      paddingHorizontal: space.md,
      paddingVertical: 7,
      borderRadius: radius.pill,
      backgroundColor: c.dangerSoft,
      ...shadow,
    },
    text: { ...type.meta, fontFamily: font.medium, color: c.danger },
  }));

  if (online) return null;

  return (
    <View style={[styles.bar, { top: insets.top + space.sm }]} pointerEvents="none">
      <Icon name="wifi-off" size={13} color={colors.danger} />
      <Text style={styles.text}>Offline — retrying when you're back</Text>
    </View>
  );
}
