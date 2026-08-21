import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, ScrollView, Text, TextInput, View } from 'react-native';
import { Focusable } from '../components/Focusable';
import { Icon } from '../components/Icon';
import { Poster } from '../components/Ui';
import { searchTitles, uniqueById, type Title } from '../lib/meta';
import { clearSearches, loadSearches, rememberSearch } from '../lib/searches';
import { GUTTER, SAFE, font, radius, space, type, useColors, useThemedStyles } from '../theme';

/** Long enough that "th" does not fetch the whole catalogue. */
const MIN_QUERY = 2;
/** Roughly one unhurried keystroke on a remote. */
const DEBOUNCE_MS = 400;

/**
 * How well a title answers what was typed. Lower is better.
 *
 * Both catalogues are searched, and simply concatenating them buried the
 * series: "the mentalist" returns twelve films before the series of that exact
 * name, which is the third row down and off the screen — so the show looked
 * missing entirely. Ranking by the title rather than by which catalogue it came
 * from puts the thing that was actually asked for first, whichever it is.
 */
const relevance = (title: string, query: string) => {
  const a = title.trim().toLowerCase();
  const q = query.trim().toLowerCase();
  if (a === q) return 0;
  if (a.startsWith(q)) return 1;
  if (a.includes(q)) return 2;
  return 3;
};

/**
 * Search on a remote.
 *
 * The field opens the television's own keyboard when it takes focus, which is
 * slow to type on however it is drawn — so results arrive as the letters do,
 * rather than making anyone find the enter key to see anything. Recent searches
 * get as much room as the results, and both films and series are fetched at
 * once rather than making anyone choose a category first.
 */
export function SearchScreen({ onPickTitle }: { onPickTitle: (title: Title) => void }) {
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    screen: { flex: 1, backgroundColor: c.bg },
    body: { paddingLeft: GUTTER, paddingRight: SAFE.x, paddingTop: SAFE.y, paddingBottom: SAFE.y * 3 },
    heading: { ...type.display, color: c.text, marginBottom: space.lg },
    field: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      height: 56,
      paddingHorizontal: space.lg,
      borderRadius: radius.md,
      backgroundColor: c.control,
      borderWidth: 2,
      borderColor: 'transparent',
    },
    fieldOn: { borderColor: c.focus },
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
      height: 40,
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
  /** Whether the field holds focus — it draws its own ring, having no wrapper. */
  const [typing, setTyping] = useState(false);

  useEffect(() => {
    loadSearches().then(setRecents);
  }, []);

  /**
   * Which query the visible results belong to.
   *
   * Two requests are usually in flight while someone is typing, and they do not
   * come back in order — without this, the answer for "ince" can land after the
   * answer for "inception" and replace it.
   */
  const latest = useRef('');

  const run = useCallback(
    async (text: string, opts?: { remember?: boolean }) => {
      const q = text.trim();
      if (!q) return;
      latest.current = q;
      setBusy(true);
      try {
        // Both catalogues at once. Asking someone with a remote to pick "film
        // or series" before they have typed anything is a screen for nothing.
        const [films, series] = await Promise.all([
          searchTitles(q, 'movie').catch(() => [] as Title[]),
          searchTitles(q, 'tv').catch(() => [] as Title[]),
        ]);
        if (latest.current !== q) return;
        // Stable sort, so within one relevance band each catalogue keeps the
        // order the index gave it.
        setResults(
          uniqueById([...films, ...series]).sort(
            (a, b) => relevance(a.title, q) - relevance(b.title, q)
          )
        );
        // Only a deliberate submit is worth remembering — every prefix on the
        // way to "interstellar" is not a search anyone wants offered back.
        if (opts?.remember) setRecents(await rememberSearch(q));
      } finally {
        if (latest.current === q) setBusy(false);
      }
    },
    []
  );

  // Search as the letters arrive. The remote's keyboard is slow enough that
  // waiting for a submit means staring at an empty screen through the whole
  // word.
  useEffect(() => {
    const q = query.trim();
    if (q.length < MIN_QUERY) {
      latest.current = '';
      setResults(null);
      setBusy(false);
      return;
    }
    const timer = setTimeout(() => run(q), DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [query, run]);

  return (
    <View style={styles.screen}>
      <ScrollView contentContainerStyle={styles.body} keyboardShouldPersistTaps="always">
        <Text style={styles.heading}>Search</Text>

        {/* The TextInput is the focus target itself, not something inside a
            Pressable. Wrapped, the D-pad lands on the wrapper and OK never
            reaches the field, so the television's keyboard is never asked to
            open. autoFocus opens it on arrival, which is the only reason to be
            on this screen. */}
        <View style={[styles.field, typing && styles.fieldOn]}>
          <Icon name="search" size={24} color={typing ? colors.accent : colors.faint} />
          <TextInput
            autoFocus
            style={styles.input}
            value={query}
            onChangeText={setQuery}
            onFocus={() => setTyping(true)}
            onBlur={() => setTyping(false)}
            onSubmitEditing={() => run(query, { remember: true })}
            placeholder="Film or series"
            placeholderTextColor={colors.faint}
            returnKeyType="search"
            autoCorrect={false}
          />
          {busy && <ActivityIndicator color={colors.accent} />}
        </View>

        {!!recents.length && !results && (
          <>
            <Text style={styles.section}>Recent</Text>
            <View style={styles.recents}>
              {recents.map((r) => (
                <Focusable
                  invert
                  key={r}
                  onPress={() => setQuery(r)}
                  scale={1.04}
                  style={styles.recent}
                >
                  {(focused) => (
                    <Text style={[styles.recentText, focused && { color: colors.bg }]}>
                      {r}
                    </Text>
                  )}
                </Focusable>
              ))}
              <Focusable
                invert
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
              {results.length
                ? `Results for “${query.trim()}”`
                : `Nothing found for “${query.trim()}”`}
            </Text>
            <View style={styles.grid}>
              {results.map((title) => (
                <Poster
                  key={title.imdb}
                  title={title}
                  sub={[title.kind === 'tv' ? 'Series' : 'Film', title.year]
                    .filter(Boolean)
                    .join('  ·  ')}
                  onPress={() => onPickTitle(title)}
                />
              ))}
            </View>
          </>
        )}
      </ScrollView>
    </View>
  );
}
