import { Modal, Pressable, StyleSheet, Text, View } from 'react-native';
import { Button } from './Button';
import { Icon, type IconName } from './Icon';
import { colors, radius, shadow, space, type } from '../theme';

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

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: '#0b102055',
    alignItems: 'center',
    justifyContent: 'center',
    padding: space.xl,
  },
  sheet: {
    width: '100%',
    maxWidth: 360,
    // The one genuinely floating surface, so the one that keeps a shadow.
    backgroundColor: colors.control,
    borderRadius: radius.lg,
    padding: space.xl,
    alignItems: 'center',
    ...shadow,
  },
  badge: {
    width: 46,
    height: 46,
    borderRadius: radius.pill,
    backgroundColor: colors.accentSoft,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: space.lg,
  },
  badgeDanger: { backgroundColor: colors.dangerSoft },
  title: { ...type.title, fontSize: 17, color: colors.text, textAlign: 'center' },
  message: {
    ...type.body,
    color: colors.muted,
    textAlign: 'center',
    marginTop: space.sm,
  },
  actions: {
    flexDirection: 'row',
    gap: space.md,
    marginTop: space.xl,
    alignSelf: 'stretch',
  },
  action: { flex: 1, justifyContent: 'center' },
});
