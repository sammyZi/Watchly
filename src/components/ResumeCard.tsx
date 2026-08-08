import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Icon } from './Icon';
import { Thumb } from './Thumb';
import { clock, left } from '../lib/format';
import type { Item } from '../lib/history';
import { colors, font, radius, space, type } from '../theme';

/**
 * A one-tap way back into whatever was last playing. Deliberately not the
 * expandable history row: here the only useful action is "carry on".
 */
export function ResumeCard({ item, onResume }: { item: Item; onResume: () => void }) {
  const index = item.lastFileIndex;
  if (index == null) return null;

  const position = item.positions?.[index] ?? 0;
  const duration = item.durations?.[index];
  const file = item.files?.find((f) => f.index === index);
  const percent = duration ? (position / duration) * 100 : 0;
  const showTorrent = !!item.torrentName && item.torrentName !== (file?.name ?? item.title);

  return (
    <Pressable
      onPress={onResume}
      style={({ pressed }) => [styles.card, pressed && styles.pressed]}
    >
      <Thumb
        name={item.torrentName ?? item.title}
        poster={item.posters?.[index]}
        progress={percent}
        width={96}
        height={60}
      />

      <View style={styles.body}>
        {showTorrent && (
          <Text numberOfLines={1} style={styles.eyebrow}>
            {item.torrentName}
          </Text>
        )}
        <Text numberOfLines={2} style={styles.title}>
          {file?.name ?? item.title}
        </Text>
        <Text style={styles.meta}>
          {duration
            ? `${left(duration - position)} · ${clock(position)}`
            : `Resume at ${clock(position)}`}
        </Text>
      </View>

      <View style={styles.play}>
        <Icon name="play" size={14} color={colors.accent} />
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    padding: space.md,
    borderRadius: radius.lg,
    backgroundColor: colors.surface,
  },
  pressed: { opacity: 0.7 },
  body: { flex: 1, gap: 2 },
  eyebrow: { ...type.overline, color: colors.faint, fontSize: 10, letterSpacing: 0.4 },
  title: { ...type.body, fontFamily: font.medium, color: colors.text, lineHeight: 20 },
  meta: { ...type.meta, color: colors.muted, marginTop: 2 },
  play: {
    width: 34,
    height: 34,
    borderRadius: radius.pill,
    backgroundColor: colors.accentSoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
