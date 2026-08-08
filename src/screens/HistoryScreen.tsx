import { useMemo, useState } from 'react';
import { FlatList, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { IconButton } from '../components/Button';
import { HistoryRow } from '../components/HistoryRow';
import { Icon } from '../components/Icon';
import { Screen } from '../components/Screen';
import type { Item } from '../lib/history';
import { colors, radius, space, type } from '../theme';

/** Torrent name, the label we stored, and every file inside it. Searching only
 *  the title would miss the episode you actually remember. */
const haystack = (item: Item) =>
  [item.torrentName, item.title, ...(item.files?.map((f) => f.name) ?? [])]
    .join(' ')
    .toLowerCase();

export function HistoryScreen({
  history,
  onOpenItem,
  onPlayFile,
  onRemove,
  onClear,
}: {
  history: Item[];
  onOpenItem: (item: Item) => void;
  onPlayFile: (item: Item, index: number) => void;
  onRemove: (item: Item) => void;
  onClear: () => void;
}) {
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState('');

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return history;
    return history.filter((item) => haystack(item).includes(q));
  }, [history, query]);

  const closeSearch = () => {
    setSearching(false);
    setQuery('');
  };

  return (
    <Screen
      title="Library"
      icon="clock"
      action={
        history.length > 0 ? (
          <View style={styles.actions}>
            <IconButton
              name={searching ? 'x' : 'search'}
              size={17}
              onPress={() => (searching ? closeSearch() : setSearching(true))}
              color={searching ? colors.onAccent : colors.muted}
              background={searching ? colors.accent : colors.surface}
            />
            <IconButton
              name="trash-2"
              size={16}
              onPress={onClear}
              color={colors.danger}
              background={colors.dangerSoft}
            />
          </View>
        ) : null
      }
    >
      {searching && (
        <View style={styles.searchWrap}>
          <View style={styles.search}>
            <Icon name="search" size={15} color={colors.faint} />
            <TextInput
              style={styles.searchInput}
              value={query}
              onChangeText={setQuery}
              placeholder="Search titles and files"
              placeholderTextColor={colors.faint}
              autoFocus
              autoCapitalize="none"
              autoCorrect={false}
              returnKeyType="search"
            />
            {!!query && (
              <Pressable onPress={() => setQuery('')} hitSlop={10}>
                <Icon name="x-circle" size={15} color={colors.faint} />
              </Pressable>
            )}
          </View>
        </View>
      )}

      <FlatList
        data={shown}
        style={styles.flex}
        keyExtractor={(i) => i.uri}
        contentContainerStyle={styles.list}
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
        ListHeaderComponent={
          shown.length ? (
            <Text style={styles.count}>
              {query
                ? `${shown.length} ${shown.length === 1 ? 'match' : 'matches'}`
                : `${history.length} ${history.length === 1 ? 'torrent' : 'torrents'} · hold a row to remove`}
            </Text>
          ) : null
        }
        ListEmptyComponent={
          query ? (
            <View style={styles.empty}>
              <View style={styles.emptyBadge}>
                <Icon name="search" size={24} color={colors.faint} />
              </View>
              <Text style={styles.emptyTitle}>No matches</Text>
              <Text style={styles.emptyText}>
                Nothing in your library matches “{query.trim()}”.
              </Text>
            </View>
          ) : (
            <View style={styles.empty}>
              <View style={styles.emptyBadge}>
                <Icon name="inbox" size={24} color={colors.faint} />
              </View>
              <Text style={styles.emptyTitle}>Nothing here yet</Text>
              <Text style={styles.emptyText}>
                Torrents you stream show up here with every file they contain, each
                with its own resume point.
              </Text>
            </View>
          )
        }
        renderItem={({ item }) => (
          <View style={styles.card}>
            <HistoryRow
              item={item}
              onOpen={() => onOpenItem(item)}
              onPlayFile={(index) => onPlayFile(item, index)}
              onRemove={() => onRemove(item)}
            />
          </View>
        )}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  actions: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  searchWrap: {
    paddingHorizontal: space.xl,
    paddingTop: space.lg,
    backgroundColor: colors.bg,
  },
  search: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    paddingHorizontal: space.lg,
    height: 44,
    borderRadius: radius.pill,
    backgroundColor: colors.surface,
  },
  searchInput: { ...type.body, color: colors.text, flex: 1, padding: 0 },
  list: { padding: space.xl, paddingTop: space.lg },
  count: { ...type.meta, color: colors.faint, marginBottom: space.md },
  card: {
    backgroundColor: colors.surface,
    paddingHorizontal: space.lg,
    borderRadius: radius.lg,
    marginBottom: space.md,
  },
  empty: { alignItems: 'center', gap: space.md, paddingVertical: space.xxl },
  emptyBadge: {
    width: 64,
    height: 64,
    borderRadius: radius.pill,
    backgroundColor: colors.surfaceAlt,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: space.xs,
  },
  emptyTitle: { ...type.title, fontSize: 16, color: colors.text },
  emptyText: {
    ...type.body,
    color: colors.faint,
    textAlign: 'center',
    maxWidth: 280,
  },
});
