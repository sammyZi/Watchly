import { ActivityIndicator, ScrollView, Text, View } from 'react-native';
import { Focusable } from './Focusable';
import { Icon } from './Icon';
import { font, radius, SAFE, space, type, useColors, useThemedStyles } from '../theme';

export type Option<T> = {
  value: T;
  label: string;
  /** Second line — a release name, a language, a track description. */
  hint?: string;
};

/**
 * A list of choices down the right-hand side.
 *
 * Every option the player offers is the same shape — pick one of these — so
 * they share one panel rather than each growing its own. It sits on the right
 * because the transport row it was opened from is bottom-left: the D-pad
 * travels one way, and BACK always returns.
 */
export function Sheet<T extends string | number | null>({
  title,
  options,
  value,
  onPick,
  busy,
  empty,
}: {
  title: string;
  options: Option<T>[];
  /** The option currently in force, ticked in the list. */
  value?: T;
  onPick: (value: T) => void;
  busy?: boolean;
  /** Shown instead of the list when there is nothing to choose from. */
  empty?: string;
}) {
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    sheet: {
      position: 'absolute' as const,
      top: 0,
      right: 0,
      bottom: 0,
      width: 360,
      backgroundColor: c.bg,
      paddingTop: SAFE.y,
      paddingHorizontal: space.lg,
      zIndex: 3,
    },
    title: {
      ...type.title,
      fontFamily: font.semibold,
      color: c.text,
      marginBottom: space.md,
    },
    row: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      paddingHorizontal: space.md,
      paddingVertical: space.sm,
      borderRadius: radius.md,
      marginBottom: space.xs,
    },
    label: { ...type.label, color: c.text },
    hint: { ...type.meta, color: c.faint, marginTop: 1 },
    note: { ...type.body, color: c.faint, marginTop: space.md },
  }));

  return (
    <View style={styles.sheet}>
      <Text style={styles.title}>{title}</Text>
      {busy && <ActivityIndicator color={colors.accent} />}
      {!busy && !options.length && !!empty && <Text style={styles.note}>{empty}</Text>}
      <ScrollView showsVerticalScrollIndicator={false}>
        {options.map((option, i) => {
          const on = option.value === value;
          return (
            <Focusable
              key={`${String(option.value)}:${i}`}
              first={i === 0}
              onPress={() => onPick(option.value)}
              scale={1}
              style={styles.row}
              focusedStyle={{ backgroundColor: colors.focus }}
            >
              {(focused) => (
                <>
                  <View style={{ width: 20 }}>
                    {on && (
                      <Icon
                        name="check"
                        size={18}
                        color={focused ? colors.bg : colors.accent}
                      />
                    )}
                  </View>
                  <View style={{ flex: 1 }}>
                    <Text
                      style={[styles.label, focused && { color: colors.bg }]}
                      numberOfLines={1}
                    >
                      {option.label}
                    </Text>
                    {!!option.hint && (
                      <Text
                        style={[styles.hint, focused && { color: colors.bg }]}
                        numberOfLines={1}
                      >
                        {option.hint}
                      </Text>
                    )}
                  </View>
                </>
              )}
            </Focusable>
          );
        })}
      </ScrollView>
    </View>
  );
}
