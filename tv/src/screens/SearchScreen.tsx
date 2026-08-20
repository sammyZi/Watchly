import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, ScrollView, Text, TextInput, View } from 'react-native';
import { Focusable } from '../components/Focusable';
import { Icon } from '../components/Icon';
import { Poster } from '../components/Ui';
import { searchTitles, uniqueById, type Title } from '../lib/meta';
import { clearSearches, loadSearches, rememberSearch } from '../lib/searches';
import { font, radius, SAFE, space, type, useColors, useThemedStyles } from '../theme';

/**
 * Search on a remote.
 *
 * The field opens the television's own keyboard when it takes focus, which is
 * slow to type on however it is drawn — so recent searches are given as much
 * room as the results, and both films and series are fetched at once rather
 * than making anyone choose a category first.
 */
export function SearchScreen({ onPickTitle }: { onPickTitle: (title: Title) => void }) {
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    screen: { flex: 1, backgroundColor: c.bg },
    body: { paddingHorizontal: SAFE.x, paddingTop: SAFE.y, paddingBottom: SAFE.y * 3 },
    heading: { ...type.display, color: c.text, marginBottom: space.lg },
    field: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      height: 74,
      paddingHorizontal: space.lg,
      borderRadius: radius.md,
      backgroundColor: c.control,
    },
    input: { flex: 1, ...type.title, color: c.text, padding: 0 },
    section: {
      ...type.title,
      fontFamily: font.semibold,
      color: c.text,
      marginTop: space.xl,
      marginBottom: space.md,
    },
    recents: { flexDirection: 'row' as const, flexWrap: 'wrap' as const, gap: space.md },
    recent: {
      paddingHorizontal: space.lg,
      height: 54,
      justifyContent: 'center' as const,
      borderRadius: radius.pill,
      backgroundColor: c.control,
    },
    recentText: { ...type.label, color: c.muted },
    grid: { flexDirection: 'row' as const, flexWrap: 'wrap' as const, gap: space.md },
    empty: { ...type.body, color: c.faint, marginTop: space.lg },
  }));

  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Title[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [recents, setRecents] = useState<string[]>([]);

  useEffect(() => {
    loadSearches().then(setRecents);
  }, []);

  const run = useCallback(async (text: string) => {
    const q = text.trim();
    if (!q) return;
    setBusy(true);
    setQuery(q);
    try {
      // Both catalogues at once. Asking someone with a remote to pick "film or
      // series" before they have typed anything is a screen for nothing.
      const [films, series] = await Promise.all([
        searchTitles(q, 'movie').catch(() => [] as Title[]),
        searchTitles(q, 'tv').catch(() => [] as Title[]),
      ]);
      setResults(uniqueById([...films, ...series]));
      setRecents(await rememberSearch(q));
    } finally {
      setBusy(false);
    }
  }, []);

  return (
    <View style={styles.screen}>
      <ScrollView contentContainerStyle={styles.body} keyboardShouldPersistTaps="always">
        <Text style={styles.heading}>Search</Text>

        <Focusable first scale={1.01} style={styles.field}>
          <Icon name="search" size={24} color={colors.faint} />
          <TextInput
            style={styles.input}
            value={query}
            onChangeText={setQuery}
            onSubmitEditing={() => run(query)}
            placeholder="Film or series"
            placeholderTextColor={colors.faint}
            returnKeyType="search"
            autoCorrect={false}
          />
          {busy && <ActivityIndicator color={colors.accent} />}
        </Focusable>

        {!!recents.length && !results && (
          <>
            <Text style={styles.section}>Recent</Text>
            <View style={styles.recents}>
              {recents.map((r) => (
                <Focusable key={r} onPress={() => run(r)} scale={1.04} style={styles.recent}>
                  {(focused) => (
                    <Text style={[styles.recentText, focused && { color: colors.bg }]}>
                      {r}
                    </Text>
                  )}
                </Focusable>
              ))}
              <Focusable
                onPress={() => clearSearches().then(setRecents)}
                scale={1.04}
                style={styles.recent}
              >
                {(focused) => (
                  <Text
                    style={[
                      styles.recentText,
                      { color: focused ? colors.bg : colors.faint },
                    ]}
                  >
                    Clear
                  </Text>
                )}
              </Focusable>
            </View>
          </>
        )}

        {!!results && (
          <>
            <Text style={styles.section}>
              {results.length ? `Results for “${query}”` : `Nothing found for “${query}”`}
            </Text>
            <View style={styles.grid}>
              {results.map((title) => (
                <Poster key={title.imdb} title={title} onPress={() => onPickTitle(title)} />
              ))}
            </View>
          </>
        )}
      </ScrollView>
    </View>
  );
}
