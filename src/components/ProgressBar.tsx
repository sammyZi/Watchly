import { StyleSheet, View, type ViewStyle } from 'react-native';
import { colors } from '../theme';

/** `value` is 0-100. */
export function ProgressBar({
  value,
  style,
  height = 3,
}: {
  value: number;
  style?: ViewStyle;
  height?: number;
}) {
  const pct = Math.max(0, Math.min(100, value));
  return (
    <View style={[styles.track, { height, borderRadius: height }, style]}>
      <View
        style={[styles.fill, { width: `${pct}%`, height, borderRadius: height }]}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  track: { backgroundColor: colors.line, overflow: 'hidden' },
  fill: { backgroundColor: colors.accent },
});
