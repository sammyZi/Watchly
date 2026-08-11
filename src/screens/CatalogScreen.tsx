import { useEffect, useState } from 'react';
import { ActivityIndicator, Pressable } from 'react-native';
import { Icon } from '../components/Icon';
import { PosterGrid } from '../components/PosterGrid';
import { Screen } from '../components/Screen';
import { loadCatalog, type Title } from '../lib/meta';
import { space, useColors, useThemedStyles } from '../theme';

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
  const colors = useColors();
  const styles = useThemedStyles(() => ({
    spin: { marginTop: space.xxl },
  }));

  useEffect(() => {
    let live = true;
    loadCatalog(kind, genre).then((t) => live && setTitles(t));
    return () => {
      live = false;
    };
  }, [kind, genre]);

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
      {!titles ? (
        <ActivityIndicator color={colors.accent} style={styles.spin} />
      ) : (
        <PosterGrid titles={titles} onOpen={onOpenTitle} />
      )}
    </Screen>
  );
}
