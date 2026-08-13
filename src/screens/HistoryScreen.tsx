import { memo, useMemo, useState } from 'react';
import { FlatList, Pressable, Text, TextInput, View } from 'react-native';
import { IconButton } from '../components/Button';
import { HistoryRow } from '../components/HistoryRow';
import { Icon } from '../components/Icon';
import { PosterGrid } from '../components/PosterGrid';
import { Screen } from '../components/Screen';
import type { Item } from '../lib/history';
import type { Title } from '../lib/meta';
import { asTitle, inList, type List, type Saved } from '../lib/saved';
import { font, radius, space, TAB_INSET, type, useColors, useThemedStyles } from '../theme';

/** Torrent name, the label we stored, and every file inside it. Searching only
 *  the title would miss the episode you actually remember. */
const haystack = (item: Item) =>
  [item.torrentName, item.title, ...(item.files?.map((f) => f.name) ?? [])]
    .join(' ')
    .toLowerCase();

function HistoryScreenView({
  history,
  saved,
  onOpenItem,
  onOpenTitle,
  onPlayFile,
  onDownloadFile,
  onDownloadSeason,
  onRemove,
  onClear,
}: {
  history: Item[];
  saved: Saved[];
  onOpenItem: (item: Item) => void;
  onOpenTitle: (title: Title) => void;
  onPlayFile: (item: Item, index: number) => void;
  onDownloadFile: (item: Item, index: number) => void;
  onDownloadSeason: (item: Item, indices?: number[]) => void;
  onRemove: (item: Item) => void;
  onClear: () => void;
}) {
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState('');
  /** Which of the three the tab is showing. The saved lists live here because
   *  this is already the "your stuff" tab — a home rail disappears when it is
   *  empty, so it is no use as the place you go looking. */
  const [view, setView] = useState<'history' | List>('history');
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    flex: { flex: 1 },
    actions: { flexDirection: 'row' as const, alignItems: 'center' as const, gap: space.sm },
    searchWrap: {
      paddingHorizontal: space.xl,
      paddingTop: space.lg,
      backgroundColor: c.bg,
    },
    search: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      paddingHorizontal: space.lg,
      height: 44,
      borderRadius: radius.pill,
      backgroundColor: c.surface,
    },
    searchInput: { ...type.body, color: c.text, flex: 1, padding: 0 },
    tabs: {
      flexDirection: 'row' as const,
      gap: space.sm,
      paddingHorizontal: space.xl,
      paddingTop: space.lg,
      paddingBottom: space.sm,
    },
    tab: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: 5,
      paddingHorizontal: space.md,
      paddingVertical: 7,
      borderRadius: radius.pill,
      backgroundColor: c.surface,
    },
    tabOn: { backgroundColor: c.accent },
    tabText: { ...type.meta, fontFamily: font.medium, color: c.muted },
    tabTextOn: { color: c.onAccent },
    list: {
      padding: space.xl,
      paddingTop: space.lg,
      paddingBottom: TAB_INSET,
      gap: space.md,
    },
    count: { ...type.meta, color: c.faint, marginBottom: space.md },
    empty: { alignItems: 'center' as const, gap: space.md, paddingVertical: space.xxl },
    emptyBadge: {
      width: 64,
      height: 64,
      borderRadius: radius.pill,
      backgroundColor: c.surfaceAlt,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      marginBottom: space.xs,
    },
    emptyTitle: { ...type.title, fontSize: 16, color: c.text },
    emptyText: {
      ...type.body,
      color: c.faint,
      textAlign: 'center' as const,
      maxWidth: 280,
    },
  }));

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
        history.length > 0 && view === 'history' ? (
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
      <View style={styles.tabs}>
        {(
          [
            { key: 'history', label: 'History', icon: 'clock', count: history.length },
            {
              key: 'later',
              label: 'Watch later',
              icon: 'bookmark',
              count: inList(saved, 'later').length,
            },
            {
              key: 'favorite',
              label: 'Favourites',
              icon: 'heart',
              count: inList(saved, 'favorite').length,
            },
          ] as const
        ).map(({ key, label, icon, count }) => {
          const on = view === key;
          return (
            <Pressable
              key={key}
              onPress={() => {
                setView(key);
                closeSearch();
              }}
              style={[styles.tab, on && styles.tabOn]}
            >
              <Icon name={icon} size={13} color={on ? colors.onAccent : colors.faint} />
              <Text style={[styles.tabText, on && styles.tabTextOn]}>
                {label}
                {count > 0 ? ` ${count}` : ''}
              </Text>
            </Pressable>
          );
        })}
      </View>

      {view !== 'history' ? (
        <PosterGrid
          titles={inList(saved, view).map(asTitle)}
          onOpen={onOpenTitle}
          empty={
            <View style={styles.empty}>
              <View style={styles.emptyBadge}>
                <Icon
                  name={view === 'later' ? 'bookmark' : 'heart'}
                  size={24}
                  color={colors.faint}
                />
              </View>
              <Text style={styles.emptyTitle}>
                {view === 'later' ? 'Nothing saved for later' : 'No favourites yet'}
              </Text>
              <Text style={styles.emptyText}>
                Open any title and use the {view === 'later' ? 'bookmark' : 'heart'} in
                the top corner to put it here.
              </Text>
            </View>
          }
        />
      ) : (
        <>
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
        removeClippedSubviews={false}
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
          <HistoryRow
            item={item}
            saved={saved}
            kin={history}
            onOpen={() => onOpenItem(item)}
            onPlayFile={(index) => onPlayFile(item, index)}
            onDownloadFile={(index) => onDownloadFile(item, index)}
            onDownloadSeason={(indices) => onDownloadSeason(item, indices)}
            onRemove={() => onRemove(item)}
          />
        )}
      />
        </>
      )}
    </Screen>
  );
}

/** Kept mounted behind the other tabs — see TabPage in App. */
export const HistoryScreen = memo(HistoryScreenView);
