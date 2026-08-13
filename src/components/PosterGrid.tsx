import { FlatList, Image, Pressable, Text, View } from 'react-native';
import { Icon } from './Icon';
import { Marquee } from './Marquee';
import type { Title } from '../lib/meta';
import { font, radius, space, TAB_INSET, type, useColors, useThemedStyles } from '../theme';

/** Three across, poster-first — the shape both browsing screens want. */
export function PosterGrid({
  titles,
  header,
  empty,
  onOpen,
}: {
  titles: Title[];
  header?: React.ReactElement | null;
  empty?: React.ReactElement | null;
  onOpen: (title: Title) => void;
}) {
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    flex: { flex: 1 },
    grid: { padding: space.xl, paddingTop: space.md, paddingBottom: TAB_INSET, gap: space.lg },
    column: { gap: space.md },
    card: { flex: 1 / 3, gap: space.xs },
    pressed: { opacity: 0.6 },
    poster: {
      width: '100%' as const,
      aspectRatio: 2 / 3,
      borderRadius: radius.md,
      backgroundColor: c.surface,
      marginBottom: space.xs,
    },
    score: {
      position: 'absolute' as const,
      top: space.sm,
      left: space.sm,
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: 2,
      paddingHorizontal: 5,
      paddingVertical: 2,
      borderRadius: radius.sm,
      backgroundColor: '#0b0e14cc',
    },
    scoreText: { ...type.meta, fontSize: 10, fontFamily: font.medium, color: '#fff' },
    name: { ...type.meta, fontSize: 12, color: c.text },
    year: { ...type.meta, fontSize: 11, color: c.faint },
  }));

  return (
    <FlatList
      data={titles}
      numColumns={3}
      keyExtractor={(t) => `${t.kind}:${t.imdb}`}
      style={styles.flex}
      contentContainerStyle={styles.grid}
      columnWrapperStyle={styles.column}
      showsVerticalScrollIndicator={false}
      keyboardShouldPersistTaps="handled"
      keyboardDismissMode="on-drag"
      initialNumToRender={9}
      windowSize={5}
      removeClippedSubviews={false}
      ListHeaderComponent={header}
      ListEmptyComponent={empty}
      renderItem={({ item }) => (
        <Pressable
          onPress={() => onOpen(item)}
          style={({ pressed }) => [styles.card, pressed && styles.pressed]}
        >
          {item.poster ? (
            <Image source={{ uri: item.poster }} style={styles.poster} resizeMode="cover" />
          ) : (
            <View style={styles.poster} />
          )}
          {!!item.rating && (
            <View style={styles.score}>
              <Icon name="star" size={9} color={colors.warn} />
              <Text style={styles.scoreText}>{item.rating}</Text>
            </View>
          )}
          <Marquee style={styles.name}>{item.title}</Marquee>
          <Text style={styles.year}>
            {[item.year, item.kind === 'tv' ? 'Series' : null].filter(Boolean).join(' · ')}
          </Text>
        </Pressable>
      )}
    />
  );
}
