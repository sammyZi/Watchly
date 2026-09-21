import { useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, Text, View } from 'react-native';
import { Icon } from '../components/Icon';
import { PosterGrid } from '../components/PosterGrid';
import { Screen } from '../components/Screen';
import { loadCatalog, sortTitles, SORTS, type Sort, type Title } from '../lib/meta';
import { useOnline } from '../lib/net';
import { font, radius, space, type, useColors, useThemedStyles } from '../theme';

/** Everything on one shelf, three across. The rail shows what fits; this shows
 *  the other forty. */
export function CatalogScreen({
  heading,
  kind,
  genre,
  onOpenTitle,
  onBack,
}: {
  heading: string;
  kind: 'movie' | 'tv';
  genre?: string;
  onOpenTitle: (title: Title) => void;
  onBack: () => void;
}) {
  const [titles, setTitles] = useState<Title[] | null>(null);
  /** Cinemeta hands the shelf over by popularity; this reorders what arrived. */
  const [sort, setSort] = useState<Sort>('popular');
  /** Failed loads run again when the connection returns. */
  const online = useOnline();
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    spin: { marginTop: space.xxl },
    sortRow: { flexGrow: 0, minHeight: 34, marginBottom: space.md },
    sorts: { paddingHorizontal: space.xl, gap: space.sm, alignItems: 'center' as const },
    chip: {
      paddingHorizontal: space.md,
      paddingVertical: 7,
      borderRadius: radius.pill,
      backgroundColor: c.surface,
    },
    chipOn: { backgroundColor: c.accent },
    chipText: { ...type.meta, fontFamily: font.medium, color: c.muted },
    chipTextOn: { color: c.onAccent },
  }));

  const shown = useMemo(() => (titles ? sortTitles(titles, sort) : null), [titles, sort]);

  useEffect(() => {
    let live = true;
    loadCatalog(kind, genre).then((t) => live && t.length && setTitles(t));
    return () => {
      live = false;
    };
  }, [kind, genre, online]);

  return (
    <Screen
      title={heading}
      action={
        <Pressable
          onPress={onBack}
          hitSlop={12}
          accessibilityLabel="Back"
          style={{
            width: 36,
            height: 36,
            borderRadius: 18,
            alignItems: 'center',
            justifyContent: 'center',
            backgroundColor: colors.accentSoft,
          }}
        >
          <Icon name="x" size={18} color={colors.accent} />
        </Pressable>
      }
    >
      {!shown ? (
        <ActivityIndicator color={colors.accent} style={styles.spin} />
      ) : (
        <>
          <ScrollView
            horizontal
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.sorts}
            style={styles.sortRow}
          >
            <Icon name="sliders" size={13} color={colors.faint} />
            {SORTS.map(({ key, label }) => (
              <Pressable
                key={key}
                onPress={() => setSort(key)}
                style={[styles.chip, sort === key && styles.chipOn]}
                accessibilityLabel={`Sort by ${label}`}
              >
                <Text style={[styles.chipText, sort === key && styles.chipTextOn]}>{label}</Text>
              </Pressable>
            ))}
          </ScrollView>
          <PosterGrid titles={shown} onOpen={onOpenTitle} />
        </>
      )}
    </Screen>
  );
}
