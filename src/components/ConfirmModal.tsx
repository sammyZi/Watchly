import { Modal, Pressable, Text, View } from 'react-native';
import { Button } from './Button';
import { Icon, type IconName } from './Icon';
import { radius, shadow, space, type, useColors, useThemedStyles } from '../theme';

export type Confirm = {
  title: string;
  message?: string;
  icon?: IconName;
  confirmLabel?: string;
  destructive?: boolean;
  onConfirm: () => void;
};

/** Replaces Alert.alert — same job, but it looks like the rest of the app. */
export function ConfirmModal({
  request,
  onClose,
}: {
  request: Confirm | null;
  onClose: () => void;
}) {
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    backdrop: {
      flex: 1,
      backgroundColor: c.scrim,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      padding: space.xl,
    },
    sheet: {
      width: '100%' as const,
      maxWidth: 360,
      // The one genuinely floating surface, so the one that keeps a shadow.
      backgroundColor: c.control,
      borderRadius: radius.lg,
      padding: space.xl,
      alignItems: 'center' as const,
      ...shadow,
    },
    badge: {
      width: 46,
      height: 46,
      borderRadius: radius.pill,
      backgroundColor: c.accentSoft,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      marginBottom: space.lg,
    },
    badgeDanger: { backgroundColor: c.dangerSoft },
    title: { ...type.title, fontSize: 17, color: c.text, textAlign: 'center' as const },
    message: {
      ...type.body,
      color: c.muted,
      textAlign: 'center' as const,
      marginTop: space.sm,
    },
    actions: {
      flexDirection: 'row' as const,
      gap: space.md,
      marginTop: space.xl,
      alignSelf: 'stretch' as const,
    },
    action: { flex: 1, justifyContent: 'center' as const },
  }));

  return (
    <Modal
      visible={!!request}
      transparent
      animationType="fade"
      statusBarTranslucent
      onRequestClose={onClose}
    >
      <Pressable style={styles.backdrop} onPress={onClose}>
        <Pressable style={styles.sheet} onPress={() => {}}>
          {!!request?.icon && (
            <View style={[styles.badge, request.destructive && styles.badgeDanger]}>
              <Icon
                name={request.icon}
                size={20}
                color={request.destructive ? colors.danger : colors.accent}
              />
            </View>
          )}

          <Text style={styles.title}>{request?.title}</Text>
          {!!request?.message && <Text style={styles.message}>{request.message}</Text>}

          <View style={styles.actions}>
            <Button label="Cancel" onPress={onClose} style={styles.action} />
            <Button
              label={request?.confirmLabel ?? 'Confirm'}
              variant={request?.destructive ? 'danger' : 'solid'}
              style={styles.action}
              onPress={() => {
                request?.onConfirm();
                onClose();
              }}
            />
          </View>
        </Pressable>
      </Pressable>
    </Modal>
  );
}
