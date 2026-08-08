import { Pressable, StyleSheet, Text, View } from 'react-native';
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

  return (
    <Pressable
      onPress={onResume}
      style={({ pressed }) => [styles.card, pressed && styles.pressed]}
    >
      <Thumb
        name={item.torrentName ?? item.title}
        poster={item.posters?.[index]}
        progress={percent}
      />

      <View style={styles.body}>
        <Text numberOfLines={2} style={styles.title}>
          {file?.name ?? item.title}
        </Text>
        {/* The tile carries the progress bar, so the text only has to answer
            "how much longer". */}
        <Text style={styles.meta}>
          {duration ? left(duration - position) : clock(position)}
        </Text>
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
  body: { flex: 1, gap: space.xs },
  title: { ...type.body, fontFamily: font.medium, color: colors.text },
  meta: { ...type.meta, color: colors.faint },
});
