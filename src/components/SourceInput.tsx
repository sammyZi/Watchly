import { useState } from 'react';
import { StyleSheet, Text, TextInput, View } from 'react-native';
import * as Clipboard from 'expo-clipboard';
import * as DocumentPicker from 'expo-document-picker';
import { Button } from './Button';
import { Icon } from './Icon';
import { colors, font, radius, space, type } from '../theme';

/** Magnet / .torrent box with paste and file-picker shortcuts. */
export function SourceInput({
  onSubmit,
  actionLabel = 'Stream',
  title = 'Start streaming',
  subtitle = 'Magnet link or .torrent file',
}: {
  onSubmit: (uri: string) => void;
  actionLabel?: string;
  title?: string;
  subtitle?: string;
}) {
  const [value, setValue] = useState('');
  const [focused, setFocused] = useState(false);
  const [hint, setHint] = useState<string | null>(null);
  const ready = value.trim().length > 0;

  const submit = (uri: string) => {
    const clean = uri.trim();
    if (!clean) return;
    setValue('');
    setHint(null);
    onSubmit(clean);
  };

  const paste = async () => {
    const text = (await Clipboard.getStringAsync()).trim();
    if (text) {
      setValue(text);
      setHint(null);
    } else {
      setHint('Clipboard is empty');
    }
  };

  const pickTorrent = async () => {
    const r = await DocumentPicker.getDocumentAsync({ copyToCacheDirectory: true });
    if (!r.canceled) submit(r.assets[0].uri);
  };

  return (
    <View style={styles.card}>
      <View style={styles.head}>
        <View style={styles.mark}>
          <Icon name="zap" size={14} color={colors.accent} />
        </View>
        <View style={styles.headText}>
          <Text style={styles.title}>{title}</Text>
          <Text style={styles.sub}>{subtitle}</Text>
        </View>
      </View>

      <View style={[styles.well, focused && styles.wellOn]}>
        <TextInput
          style={styles.input}
          value={value}
          onChangeText={setValue}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
          placeholder={'magnet:?xt=urn:btih:…\nor https://site/file.torrent'}
          placeholderTextColor={colors.faint}
          autoCapitalize="none"
          autoCorrect={false}
          multiline
          scrollEnabled
        />
      </View>

      {!!hint && <Text style={styles.hint}>{hint}</Text>}

      <View style={styles.actions}>
        <Button label="Paste" icon="clipboard" onPress={paste} />
        <Button label="File" icon="folder" onPress={pickTorrent} />
        <View style={styles.spacer} />
        <Button
          label={actionLabel}
          icon="play"
          variant="solid"
          disabled={!ready}
          onPress={() => submit(value)}
        />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: space.lg,
    gap: space.md,
  },
  head: { flexDirection: 'row', alignItems: 'center', gap: space.md },
  mark: {
    width: 36,
    height: 36,
    borderRadius: radius.md,
    backgroundColor: colors.accentSoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  headText: { flex: 1, gap: 1 },
  title: { ...type.label, fontFamily: font.semibold, color: colors.text, fontSize: 15 },
  sub: { ...type.meta, color: colors.muted },
  well: {
    backgroundColor: colors.control,
    borderRadius: radius.md,
    paddingHorizontal: space.md,
    paddingVertical: space.sm,
    borderWidth: 1.5,
    borderColor: 'transparent',
  },
  wellOn: { borderColor: colors.accent },
  input: {
    ...type.body,
    color: colors.text,
    height: 68,
    padding: 0,
    textAlignVertical: 'top',
  },
  hint: { ...type.meta, color: colors.danger, marginTop: -space.xs },
  actions: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  spacer: { flex: 1 },
});
