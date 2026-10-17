import { Pressable, Text, type ViewStyle } from 'react-native';
import { Icon, type IconName } from './Icon';
import { font, radius, space, type, useColors, useThemedStyles } from '../theme';

type Variant = 'solid' | 'ghost' | 'danger';

export function Button({
  label,
  onPress,
  icon,
  variant = 'ghost',
  disabled,
  style,
}: {
  label: string;
  onPress: () => void;
  icon?: IconName;
  variant?: Variant;
  disabled?: boolean;
  style?: ViewStyle;
}) {
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    base: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      gap: space.sm,
      borderRadius: radius.pill,
      paddingHorizontal: space.lg,
      height: 40,
    },
    ghost: { backgroundColor: c.control },
    solid: { backgroundColor: c.accent },
    danger: { backgroundColor: c.danger },
    pressed: { opacity: 0.65 },
    disabled: { opacity: 0.4 },
    label: { ...type.label },
    labelFilled: { fontFamily: font.semibold },
  }));

  const filled = variant !== 'ghost';
  const fg = filled ? colors.onAccent : colors.muted;

  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      style={({ pressed }) => [
        styles.base,
        variant === 'ghost' && styles.ghost,
        variant === 'solid' && styles.solid,
        variant === 'danger' && styles.danger,
        pressed && styles.pressed,
        disabled && styles.disabled,
        style,
      ]}
    >
      {!!icon && <Icon name={icon} size={15} color={fg} />}
      <Text style={[styles.label, { color: fg }, filled && styles.labelFilled]}>
        {label}
      </Text>
    </Pressable>
  );
}

/** Round icon-only button, used over the video and in dense rows. */
export function IconButton({
  name,
  onPress,
  color,
  background,
  size = 18,
}: {
  name: IconName;
  onPress: () => void;
  color?: string;
  background?: string;
  size?: number;
}) {
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    iconBtn: {
      width: 40,
      height: 40,
      borderRadius: radius.pill,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    pressed: { opacity: 0.65 },
  }));

  return (
    <Pressable
      onPress={onPress}
      hitSlop={10}
      style={({ pressed }) => [
        styles.iconBtn,
        { backgroundColor: background ?? colors.surfaceAlt },
        pressed && styles.pressed,
      ]}
    >
      <Icon name={name} size={size} color={color ?? colors.text} />
    </Pressable>
  );
}
