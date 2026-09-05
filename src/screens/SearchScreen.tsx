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
import { ANIME_GENRE, loadCatalog, searchTitles, type Title } from '../lib/meta';
import { useOnline } from '../lib/net';
import { bytes } from '../lib/format';
import { health, searchTorrents, type Hit } from '../lib/search';
import { clearSearches, loadSearches, rememberSearch } from '../lib/searches';
import { font, radius, space, type, useColors, useThemedStyles } from '../theme';

const DEBOUNCE = 400;

/** Browsing only — see the note by the chips. Genre is Cinemeta's own
 *  vocabulary, and a catalog row carries its own list, so this costs no
 *  extra request. */
const GENRES = [
  ANIME_GENRE,
  'Action',
  'Comedy',
  'Drama',
  'Thriller',
  'Horror',
  'Sci-Fi',
  'Animation',
];

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
    // minHeight because a horizontal ScrollView in a column takes its height
    // from content, and with one short chip in the row it collapsed and cropped
    // the label. flexGrow:0 or it claims the whole page instead.
    chipRow: { flexGrow: 0, minHeight: 34, marginBottom: space.md },
    chips: { paddingHorizontal: space.xl, gap: space.sm, alignItems: 'center' as const },
    rule: { width: 1, height: 16, backgroundColor: c.line },
    clearRecent: { paddingHorizontal: space.xs, paddingVertical: space.xs },
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
    hitList: { paddingHorizontal: space.lg, paddingBottom: space.xl * 3 },
    hit: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      paddingVertical: space.md,
      borderBottomWidth: 1,
      borderBottomColor: c.line,
    },
    hitBody: { flex: 1, gap: 3 },
    hitName: { ...type.label, color: c.text },
    hitMeta: { ...type.meta, color: c.faint },
    dot: { width: 8, height: 8, borderRadius: 4 },
    hitDownload: {
      width: 32,
      height: 32,
      borderRadius: 16,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      backgroundColor: c.surfaceAlt,
    },
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
function SearchScreenView({
  onOpenTitle,
  onOpenMagnet,
  onDownloadMagnet,
}: {
  onOpenTitle: (title: Title) => void;
  /** A release picked by name — opened the way a shared magnet link is. */
  onOpenMagnet: (magnet: string, name: string) => void;
  /** Same release, queued straight to Downloads instead of streamed. */
  onDownloadMagnet: (magnet: string, name: string) => void;
}) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Title[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [kind, setKind] = useState<'movie' | 'tv' | null>(null);
  const [genre, setGenre] = useState<string | null>(null);
  /**
   * Searching the indexes by release name instead of the catalogue by title.
   *
   * The catalogue only knows what Cinemeta lists, so anything without an entry
   * — a concert, a niche rip, a pack named its own way — could not be reached
   * at all. This asks the torrent indexes directly and hands whatever comes
   * back to the same magnet flow a shared link uses.
   */
  const [torrents, setTorrents] = useState(false);
  const [hits, setHits] = useState<Hit[] | null>(null);
  /** Only the newest query may write results — searches finish out of order. */
  const latest = useRef(0);
  /** Which query the rows on screen belong to. See the effect below. */
  const shownFor = useRef('');
  /** A search that failed offline runs again when the connection returns. */
  const online = useOnline();
  /** Submitted queries, newest first. */
  const [recent, setRecent] = useState<string[]>([]);
  useEffect(() => {
    loadSearches().then(setRecent);
  }, []);
  const colors = useColors();
  const styles = useSearchStyles();

  const load = useCallback(async (q: string, browsing: string | null) => {
    const mine = ++latest.current;
    shownFor.current = q;
    setBusy(true);
    // Anime is not a genre anything here carries — it is a different catalog,
    // so it has to be fetched rather than filtered for.
    if (!q && browsing === ANIME_GENRE) {
      const anime = await loadCatalog('tv', ANIME_GENRE);
      if (mine !== latest.current) return;
      setResults(anime);
      setBusy(false);
      return;
    }
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

  // Release-name search. Same ticket as the catalogue, so a slow index cannot
  // overwrite a newer query's answer.
  const loadTorrents = useCallback(async (q: string) => {
    const mine = ++latest.current;
    shownFor.current = q;
    if (!q) {
      setHits(null);
      setBusy(false);
      return;
    }
    setBusy(true);
    const found = await searchTorrents(q).catch(() => [] as Hit[]);
    if (mine !== latest.current) return;
    setHits(found);
    setBusy(false);
  }, []);

  useEffect(() => {
    const q = query.trim();
    if (torrents) {
      const t = setTimeout(() => loadTorrents(q), q ? DEBOUNCE : 0);
      return () => clearTimeout(t);
    }
    // A genre chosen while browsing must not survive into a search, where it
    // would filter against data the results do not carry.
    if (q) setGenre(null);
    /**
     * Results for a different search are not an answer to this one.
     *
     * The counter above stops a late reply overwriting a newer one, but nothing
     * cleared the rows already on screen — so starting a fresh search left the
     * previous title's list sitting there through the debounce and the request,
     * looking like the answer to what had just been typed.
     *
     * Only when the two are unrelated: refining "incep" to "incept" should not
     * blink the list away and back, while clearing the box and typing something
     * else should.
     */
    const was = shownFor.current;
    if (was && q && !q.startsWith(was) && !was.startsWith(q)) {
      shownFor.current = '';
      setResults(null);
    }
    const t = setTimeout(() => load(q, genre), q ? DEBOUNCE : 0);
    return () => clearTimeout(t);
  }, [query, genre, load, loadTorrents, torrents, online]);

  const shown = (results ?? []).filter(
    (t) =>
      (!kind || t.kind === kind) &&
      // Anime came back as its own catalog — every row already qualifies.
      (!genre || genre === ANIME_GENRE || t.genres?.includes(genre))
  );

  return (
    <Screen title="Search" icon="search">
      <View style={styles.bar}>
        <Icon name="search" size={16} color={colors.faint} />
        <TextInput
          style={styles.input}
          value={query}
          onChangeText={setQuery}
          placeholder={torrents ? 'Release or torrent name' : 'Films and series'}
          placeholderTextColor={colors.faint}
          autoCapitalize="none"
          autoCorrect={false}
          returnKeyType="search"
          onSubmitEditing={() => rememberSearch(query).then(setRecent)}
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

      {/* Only while browsing: once something is typed the box itself is the
          history, and a row of old queries under it is just noise. */}
      {!query.trim() && recent.length > 0 && (
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          keyboardShouldPersistTaps="handled"
          contentContainerStyle={styles.chips}
          style={styles.chipRow}
        >
          <Icon name="clock" size={13} color={colors.faint} />
          {recent.map((q) => (
            <Chip key={q} label={q} on={false} onPress={() => setQuery(q)} />
          ))}
          <Pressable
            onPress={() => clearSearches().then(setRecent)}
            hitSlop={8}
            style={styles.clearRecent}
          >
            <Icon name="x" size={13} color={colors.faint} />
          </Pressable>
        </ScrollView>
      )}

      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={styles.chips}
        style={styles.chipRow}
      >
        {/* The mode switch leads, because it changes what everything after it
            means — these are catalogue filters, and none of them apply to a
            raw index search. */}
        <Chip
          label="Torrents"
          icon="search"
          on={torrents}
          onPress={() => {
            setTorrents((on) => !on);
            setHits(null);
            setResults(null);
            shownFor.current = '';
          }}
        />
        {/* Only when something follows it — a divider with nothing after it is
            just a stray mark. */}
        {!torrents && (
          <>
            <View style={styles.rule} />
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
          </>
        )}
        {/* Search results come back without genres — the endpoint returns names
            and posters only — so a genre chip could only ever empty the grid.
            They belong to browsing, which is what an empty box shows. */}
        {!torrents && !query.trim() && (
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

      {torrents ? (
        <ScrollView
          contentContainerStyle={styles.hitList}
          keyboardShouldPersistTaps="handled"
        >
          {!!hits?.length && (
            <Text style={styles.heading}>{hits.length} releases</Text>
          )}
          {hits?.map((h) => (
            <Pressable
              key={h.id}
              style={styles.hit}
              onPress={() => {
                if (query.trim()) rememberSearch(query).then(setRecent);
                onOpenMagnet(h.magnet, h.name);
              }}
            >
              <View style={styles.hitBody}>
                <Text style={styles.hitName} numberOfLines={2}>
                  {h.name}
                </Text>
                <Text style={styles.hitMeta}>
                  {[
                    h.size ? bytes(h.size) : null,
                    h.quality,
                    `${h.seeders} seeders`,
                  ]
                    .filter(Boolean)
                    .join('  ·  ')}
                </Text>
              </View>
              <Pressable
                onPress={() => onDownloadMagnet(h.magnet, h.name)}
                hitSlop={8}
                style={styles.hitDownload}
                accessibilityLabel="Download"
              >
                <Icon name="download" size={15} color={colors.accent} />
              </Pressable>
              <View
                style={[
                  styles.dot,
                  {
                    backgroundColor:
                      health(h.seeders) === 'dead'
                        ? colors.danger
                        : health(h.seeders) === 'weak'
                          ? colors.warn
                          : colors.good,
                  },
                ]}
              />
            </Pressable>
          ))}
          {!busy && hits && !hits.length && (
            <Text style={styles.empty}>No releases match that.</Text>
          )}
          {!hits && !busy && (
            <Text style={styles.empty}>
              Search the indexes by release name — anything they carry, whether
              the catalogue lists it or not.
            </Text>
          )}
        </ScrollView>
      ) : (
      <PosterGrid
        titles={shown}
        // Recorded here rather than only on the keyboard's search key: most
        // searches are never submitted at all, they are typed until the right
        // poster appears and then tapped. A query that led somewhere is
        // exactly the one worth keeping, and it keeps every half-typed prefix
        // the debounce fired on out of the list.
        onOpen={(title) => {
          if (query.trim()) rememberSearch(query).then(setRecent);
          onOpenTitle(title);
        }}
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
      )}
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
  icon?: 'film' | 'tv' | 'search';
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
