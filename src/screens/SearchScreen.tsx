import { memo, useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  Text,
  TextInput,
  View,
} from 'react-native';
import { Icon } from '../components/Icon';
import { PosterGrid } from '../components/PosterGrid';
import { Screen } from '../components/Screen';
import { loadCatalog, searchTitles, type Title } from '../lib/meta';
import { font, radius, space, type, useColors, useThemedStyles } from '../theme';

const DEBOUNCE = 400;

/** Browsing only — see the note by the chips. Genre is Cinemeta's own
 *  vocabulary, and a catalog row carries its own list, so this costs no
 *  extra request. */
const GENRES = ['Action', 'Comedy', 'Drama', 'Thriller', 'Horror', 'Sci-Fi', 'Animation'];

function useSearchStyles() {
  return useThemedStyles((c) => ({
    bar: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      margin: space.xl,
      marginBottom: space.md,
      paddingHorizontal: space.md,
      borderRadius: radius.md,
      backgroundColor: c.surface,
    },
    input: { ...type.body, flex: 1, color: c.text, height: 46, padding: 0 },
    // flexGrow:0 or the row claims the whole page — a horizontal ScrollView in a
    // column has no natural height.
    chipRow: { flexGrow: 0 },
    chips: { paddingHorizontal: space.xl, gap: space.sm, alignItems: 'center' as const },
    rule: { width: 1, height: 16, backgroundColor: c.line },
    chip: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: 5,
      paddingHorizontal: space.md,
      paddingVertical: 7,
      borderRadius: radius.pill,
      backgroundColor: c.surface,
    },
    chipOn: { backgroundColor: c.accent },
    chipText: { ...type.meta, fontFamily: font.medium, color: c.muted },
    chipTextOn: { color: c.onAccent },
    heading: { ...type.overline, color: c.faint, marginBottom: space.sm },
    empty: { ...type.meta, color: c.faint, textAlign: 'center' as const, marginTop: space.xl },
  }));
}

/**
 * Titles first, releases later.
 *
 * This used to search the torrent index directly, which meant reading scene
 * names to work out what a result even was. Searching the metadata service
 * instead gives posters and plots, and the releases are then one tap away on
 * the title page — where the choice between them is an informed one.
 */
function SearchScreenView({ onOpenTitle }: { onOpenTitle: (title: Title) => void }) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Title[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [kind, setKind] = useState<'movie' | 'tv' | null>(null);
  const [genre, setGenre] = useState<string | null>(null);
  /** Only the newest query may write results — searches finish out of order. */
  const latest = useRef(0);
  const colors = useColors();
  const styles = useSearchStyles();

  const load = useCallback(async (q: string) => {
    const mine = ++latest.current;
    setBusy(true);
    // Films and series are separate catalogs; an empty box shows what is
    // popular rather than an empty page.
    const [films, series] = await Promise.all(
      q
        ? [searchTitles(q, 'movie'), searchTitles(q, 'tv')]
        : [loadCatalog('movie'), loadCatalog('tv')]
    );
    if (mine !== latest.current) return;
    // Interleaved rather than films-then-series: looking for a show should not
    // mean scrolling past thirty films first.
    const mixed: Title[] = [];
    for (let i = 0; i < Math.max(films.length, series.length); i++) {
      if (films[i]) mixed.push(films[i]);
      if (series[i]) mixed.push(series[i]);
    }
    setResults(mixed);
    setBusy(false);
  }, []);

  useEffect(() => {
    const q = query.trim();
    // A genre chosen while browsing must not survive into a search, where it
    // would filter against data the results do not carry.
    if (q) setGenre(null);
    const t = setTimeout(() => load(q), q ? DEBOUNCE : 0);
    return () => clearTimeout(t);
  }, [query, load]);

  const shown = (results ?? []).filter(
    (t) => (!kind || t.kind === kind) && (!genre || t.genres?.includes(genre))
  );

  return (
    <Screen title="Search" icon="search">
      <View style={styles.bar}>
        <Icon name="search" size={16} color={colors.faint} />
        <TextInput
          style={styles.input}
          value={query}
          onChangeText={setQuery}
          placeholder="Films and series"
          placeholderTextColor={colors.faint}
          autoCapitalize="none"
          autoCorrect={false}
          returnKeyType="search"
        />
        {busy ? (
          <ActivityIndicator color={colors.accent} size="small" />
        ) : (
          !!query && (
            <Pressable onPress={() => setQuery('')} hitSlop={10}>
              <Icon name="x" size={16} color={colors.faint} />
            </Pressable>
          )
        )}
      </View>

      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={styles.chips}
        style={styles.chipRow}
      >
        <Chip
          label="Films"
          icon="film"
          on={kind === 'movie'}
          onPress={() => setKind(kind === 'movie' ? null : 'movie')}
        />
        <Chip
          label="Series"
          icon="tv"
          on={kind === 'tv'}
          onPress={() => setKind(kind === 'tv' ? null : 'tv')}
        />
        {/* Search results come back without genres — the endpoint returns names
            and posters only — so a genre chip could only ever empty the grid.
            They belong to browsing, which is what an empty box shows. */}
        {!query.trim() && (
          <>
            <View style={styles.rule} />
            {GENRES.map((g) => (
              <Chip
                key={g}
                label={g}
                on={genre === g}
                onPress={() => setGenre(genre === g ? null : g)}
              />
            ))}
          </>
        )}
      </ScrollView>

      <PosterGrid
        titles={shown}
        onOpen={onOpenTitle}
        header={
          shown.length ? (
            <Text style={styles.heading}>
              {query.trim() ? `${shown.length} results` : 'Popular right now'}
            </Text>
          ) : null
        }
        empty={
          busy ? null : (
            <Text style={styles.empty}>
              {results ? 'Nothing matches that.' : 'Loading…'}
            </Text>
          )
        }
      />
    </Screen>
  );
}

function Chip({
  label,
  icon,
  on,
  onPress,
}: {
  label: string;
  icon?: 'film' | 'tv';
  on: boolean;
  onPress: () => void;
}) {
  const colors = useColors();
  const styles = useSearchStyles();
  return (
    <Pressable onPress={onPress} style={[styles.chip, on && styles.chipOn]}>
      {!!icon && <Icon name={icon} size={13} color={on ? colors.onAccent : colors.faint} />}
      <Text style={[styles.chipText, on && styles.chipTextOn]}>{label}</Text>
    </Pressable>
  );
}

/** Kept mounted behind the other tabs — see TabPage in App. */
export const SearchScreen = memo(SearchScreenView);
