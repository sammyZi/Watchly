import { useState } from 'react';
import { StyleSheet, Text, TextInput, View } from 'react-native';
import * as Clipboard from 'expo-clipboard';
import * as DocumentPicker from 'expo-document-picker';
import { Button } from './Button';
import { Icon } from './Icon';
import { colors, radius, space, type } from '../theme';

/** Magnet / .torrent box with paste and file-picker shortcuts. */
export function SourceInput({ onSubmit }: { onSubmit: (uri: string) => void }) {
  const [value, setValue] = useState('');
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
          <Icon name="link-2" size={13} color={colors.accent} />
        </View>
        <Text style={styles.heading}>Magnet or torrent URL</Text>
      </View>

      <TextInput
        style={styles.input}
        value={value}
        onChangeText={setValue}
        placeholder={'magnet:?xt=urn:btih:…\nor https://site/file.torrent'}
        placeholderTextColor={colors.faint}
        autoCapitalize="none"
        autoCorrect={false}
        multiline
        scrollEnabled
      />

      {!!hint && <Text style={styles.hint}>{hint}</Text>}

      <View style={styles.divider} />

      <View style={styles.actions}>
        <Button label="Paste" icon="clipboard" onPress={paste} />
        <Button label="File" icon="folder" onPress={pickTorrent} />
        <View style={styles.spacer} />
        <Button
          label="Stream"
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
  },
  head: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  mark: {
    width: 24,
    height: 24,
    borderRadius: radius.sm,
    backgroundColor: colors.control,
    alignItems: 'center',
    justifyContent: 'center',
  },
  heading: { ...type.overline, color: colors.muted },
  input: {
    ...type.body,
    color: colors.text,
    height: 72,
    marginTop: space.md,
    padding: 0,
    textAlignVertical: 'top',
  },
  hint: { ...type.meta, color: colors.danger, marginTop: space.sm },
  divider: {
    height: StyleSheet.hairlineWidth,
    backgroundColor: colors.line,
    marginVertical: space.lg,
    marginHorizontal: -space.lg,
  },
  actions: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  spacer: { flex: 1 },
});
