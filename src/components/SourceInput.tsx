import { useState } from 'react';
import { Text, TextInput, View } from 'react-native';
import * as Clipboard from 'expo-clipboard';
import * as DocumentPicker from 'expo-document-picker';
import { Button } from './Button';
import { Icon } from './Icon';
import { font, radius, space, type, useColors, useThemedStyles } from '../theme';

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
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    card: {
      backgroundColor: c.surface,
      borderRadius: radius.lg,
      padding: space.lg,
      gap: space.md,
    },
    head: { flexDirection: 'row' as const, alignItems: 'center' as const, gap: space.md },
    mark: {
      width: 36,
      height: 36,
      borderRadius: radius.md,
      backgroundColor: c.accentSoft,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    headText: { flex: 1, gap: 1 },
    title: { ...type.label, fontFamily: font.semibold, color: c.text, fontSize: 15 },
    sub: { ...type.meta, color: c.muted },
    well: {
      backgroundColor: c.control,
      borderRadius: radius.md,
      paddingHorizontal: space.md,
      paddingVertical: space.sm,
      borderWidth: 1.5,
      borderColor: 'transparent',
    },
    wellOn: { borderColor: c.accent },
    input: {
      ...type.body,
      color: c.text,
      height: 68,
      padding: 0,
      textAlignVertical: 'top' as const,
    },
    hint: { ...type.meta, color: c.danger, marginTop: -space.xs },
    actions: { gap: space.sm },
    actionRow: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.sm,
    },
    actionGrow: { flex: 1 },
    actionFull: { alignSelf: 'stretch' as const },
  }));

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
    const r = await DocumentPicker.getDocumentAsync({
      // Only .torrent. The name check is the real guard: providers that do not
      // map the extension hand back octet-stream, so a MIME filter alone either
      // hides every torrent or lets everything through.
      type: ['application/x-bittorrent', 'application/octet-stream'],
      copyToCacheDirectory: true,
    });
    if (r.canceled) return;
    const file = r.assets[0];
    if (!file.name?.toLowerCase().endsWith('.torrent')) {
      setHint('That is not a .torrent file');
      return;
    }
    submit(file.uri);
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
        <View style={styles.actionRow}>
          <Button label="Paste" icon="clipboard" onPress={paste} style={styles.actionGrow} />
          <Button label="File" icon="folder" onPress={pickTorrent} style={styles.actionGrow} />
        </View>
        <Button
          label={actionLabel}
          icon="play"
          variant="solid"
          disabled={!ready}
          onPress={() => submit(value)}
          style={styles.actionFull}
