import { View, type ViewStyle } from 'react-native';
import { useThemedStyles } from '../theme';

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
  const styles = useThemedStyles((c) => ({
    track: { backgroundColor: c.line, overflow: 'hidden' as const },
    fill: { backgroundColor: c.accent },
  }));
  return (
    <View style={[styles.track, { height, borderRadius: height }, style]}>
      <View
        style={[styles.fill, { width: `${pct}%`, height, borderRadius: height }]}
      />
    </View>
  );
}
