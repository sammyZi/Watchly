import { useState, type ReactNode } from 'react';
import { Pressable, type StyleProp, type ViewStyle } from 'react-native';
import { radius, useColors } from '../theme';

/**
 * Anything the remote can land on.
 *
 * A television has no cursor and no touch, so the only way to know what the
 * OK button will do is to see it. React Native's Pressable is already focusable
 * on Android TV and does fire onFocus/onBlur from the D-pad — what it does not
 * do is show anything, which is the entire job here.
 *
 * The border is drawn transparent when unfocused rather than added on focus:
 * appearing borders resize the view, and a rail of posters that grows by three
 * pixels as you arrow along it reflows the whole row.
 */
export function Focusable({
  children,
  onPress,
  onFocus,
  style,
  focusedStyle,
  first,
  scale = 1.05,
  disabled,
}: {
  children: ReactNode | ((focused: boolean) => ReactNode);
  onPress?: () => void;
  /** Fires when the D-pad arrives — rails use it to preview what is selected. */
  onFocus?: () => void;
  style?: StyleProp<ViewStyle>;
  focusedStyle?: StyleProp<ViewStyle>;
  /** Where focus should start when this screen appears. Exactly one per screen. */
  first?: boolean;
  scale?: number;
  disabled?: boolean;
}) {
  const colors = useColors();
  const [focused, setFocused] = useState(false);

  return (
    <Pressable
      focusable={!disabled}
      hasTVPreferredFocus={first}
      disabled={disabled}
      onPress={onPress}
      onFocus={() => {
        setFocused(true);
        onFocus?.();
      }}
      onBlur={() => setFocused(false)}
      style={[
        {
          borderWidth: 3,
          borderColor: 'transparent',
          borderRadius: radius.md,
          opacity: disabled ? 0.35 : 1,
        },
        style,
        focused && {
          borderColor: colors.focus,
          transform: [{ scale }],
          // Lift the focused item over its neighbours; a scaled poster is
          // otherwise clipped by the one after it in the rail.
          zIndex: 1,
        },
        focused && focusedStyle,
      ]}
    >
      {typeof children === 'function' ? children(focused) : children}
    </Pressable>
  );
}
