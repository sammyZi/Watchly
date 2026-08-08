import { Pressable, StyleSheet, Text, type ViewStyle } from 'react-native';
import { Icon, type IconName } from './Icon';
import { colors, font, radius, space, type } from '../theme';

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
  color = colors.text,
  background = colors.surfaceAlt,
  size = 18,
}: {
  name: IconName;
  onPress: () => void;
  color?: string;
  background?: string;
  size?: number;
}) {
  return (
    <Pressable
      onPress={onPress}
      hitSlop={10}
      style={({ pressed }) => [
        styles.iconBtn,
        { backgroundColor: background },
        pressed && styles.pressed,
      ]}
    >
      <Icon name={name} size={size} color={color} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  base: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: space.sm,
    borderRadius: radius.pill,
    paddingHorizontal: space.lg,
    height: 40,
  },
  // White on a tinted card — no border needed to read as a button.
  ghost: { backgroundColor: colors.control },
  solid: { backgroundColor: colors.accent },
  danger: { backgroundColor: colors.danger },
  pressed: { opacity: 0.65 },
  disabled: { opacity: 0.4 },
  label: { ...type.label },
  labelFilled: { fontFamily: font.semibold },
  iconBtn: {
    width: 40,
    height: 40,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
