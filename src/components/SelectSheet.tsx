import { FlatList, Modal, Pressable, Text, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icon } from './Icon';
import { font, radius, shadow, space, type, useColors, useThemedStyles } from '../theme';

export type SelectOption<T extends string> = { key: T; label: string };

/**
 * Pick one from a list, in a sheet up from the bottom.
 *
 * For choices too many to sit in a row of chips — thirty languages scrolled
 * sideways hid both the list and which one was picked. Opens scrolled to the
 * current pick.
 */
export function SelectSheet<T extends string>({
  visible,
  title,
  options,
  value,
  onPick,
  onClose,
}: {
  visible: boolean;
  title: string;
  options: SelectOption<T>[];
  value: T;
  onPick: (key: T) => void;
  onClose: () => void;
}) {
  const colors = useColors();
  const insets = useSafeAreaInsets();
  const { height } = useWindowDimensions();
  const styles = useThemedStyles((c) => ({
    backdrop: { flex: 1, backgroundColor: c.scrim, justifyContent: 'flex-end' as const },
    sheet: {
      backgroundColor: c.control,
      borderTopLeftRadius: radius.lg,
      borderTopRightRadius: radius.lg,
      paddingTop: space.sm,
      ...shadow,
    },
    grip: {
      alignSelf: 'center' as const,
      width: 36,
      height: 4,
      borderRadius: 2,
      backgroundColor: c.line,
      marginBottom: space.md,
    },
    title: {
      ...type.title,
      fontSize: 17,
      color: c.text,
      paddingHorizontal: space.xl,
      paddingBottom: space.md,
    },
    option: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      height: ROW,
      paddingHorizontal: space.xl,
      gap: space.md,
    },
    optionOn: { backgroundColor: c.accentSoft },
    pressed: { opacity: 0.6 },
    label: { ...type.body, color: c.text, flex: 1 },
    labelOn: { color: c.accent, fontFamily: font.semibold },
  }));

  const at = Math.max(0, options.findIndex((o) => o.key === value));

  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      statusBarTranslucent
      navigationBarTranslucent
      onRequestClose={onClose}
    >
      <Pressable style={styles.backdrop} onPress={onClose}>
        {/* A Pressable of its own, so a tap on the sheet is not one on the backdrop. */}
        <Pressable style={[styles.sheet, { paddingBottom: insets.bottom + space.md }]}>
          <View style={styles.grip} />
          <Text style={styles.title}>{title}</Text>
          <FlatList
            data={options}
            keyExtractor={(o) => o.key || '(none)'}
            style={{ maxHeight: height * 0.6 }}
            initialScrollIndex={at > 3 ? at - 2 : 0}
            getItemLayout={(_, i) => ({ length: ROW, offset: ROW * i, index: i })}
            renderItem={({ item }) => {
              const on = item.key === value;
              return (
                <Pressable
                  onPress={() => {
                    onPick(item.key);
                    onClose();
                  }}
                  style={({ pressed }) => [
                    styles.option,
                    on && styles.optionOn,
                    pressed && styles.pressed,
                  ]}
                  accessibilityState={{ selected: on }}
                >
                  <Text style={[styles.label, on && styles.labelOn]}>{item.label}</Text>
                  {on && <Icon name="check" size={18} color={colors.accent} />}
                </Pressable>
              );
            }}
          />
        </Pressable>
      </Pressable>
    </Modal>
  );
}

const ROW = 50;
