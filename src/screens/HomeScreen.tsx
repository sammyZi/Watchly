import { memo, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Animated,
  FlatList,
  Image,
  Pressable,
  StatusBar,
  Text,
  useWindowDimensions,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icon } from '../components/Icon';
import { Thumb } from '../components/Thumb';
import { resumeRatio } from '../hooks/useTorrent';
import type { Item } from '../lib/history';
import {
  ANIME_GENRE,
  kindOfGenre,
  loadCatalog,
  peekCatalog,
  watchedGenres,
  type Title,
} from '../lib/meta';
import { useOnline } from '../lib/net';
import { asTitle, inList, type Saved } from '../lib/saved';
import {
  fadeToBg,
  font,
  hues,
  radius,
  space,
  TAB_INSET,
  type,
  useColors,
  useThemedStyles,
} from '../theme';

/** Fixed charts only. Genre rails come from watch history via `watchedGenres`. */
export type Shelf = { title: string; kind: 'movie' | 'tv'; genre?: string };

const SHELVES: Shelf[] = [
  { title: 'Trending films', kind: 'movie' },
  { title: 'Popular series', kind: 'tv' },
  // Its own catalogue rather than a Cinemeta genre — see ANIME_GENRE. High up
  // because it is the one shelf nothing else on the page can stand in for.
  { title: 'Anime', kind: 'tv', genre: ANIME_GENRE },
];

/** Still watching — has a resume point short of the end. */
function isInProgress(item: Item): boolean {
  if (item.lastFileIndex == null) return false;
  return resumeRatio(item, item.lastFileIndex) < 0.9;
}

/** What identifies a shelf's contents — two shelves with the same kind and
 *  genre are the same catalog however they are labelled. */
const shelfKey = (s: Shelf) => `${s.kind}:${s.genre ?? ''}`;

const HERO = 480;
const CARD = 116;

function useHomeStyles() {
  return useThemedStyles((c) => ({
    page: { flex: 1, backgroundColor: c.bg },
    body: { paddingBottom: TAB_INSET },
    hero: { height: HERO },
    heroPage: { height: HERO, justifyContent: 'flex-end' as const },
    heroArt: { position: 'absolute' as const, top: 0, left: 0, right: 0, bottom: 0 },
    heroEmpty: { height: 220, alignItems: 'center' as const, justifyContent: 'center' as const },
    heroFade: {
      position: 'absolute' as const,
      left: 0,
      right: 0,
      bottom: 0,
      height: HERO,
      experimental_backgroundImage: fadeToBg(c.bg, 0.25),
    },
    dots: {
      position: 'absolute' as const,
      bottom: space.md,
      left: 0,
      right: 0,
      flexDirection: 'row' as const,
      justifyContent: 'center' as const,
      alignItems: 'center' as const,
      gap: 6,
    },
    dot: {
      height: 6,
      borderRadius: 3,
      backgroundColor: c.faint,
    },
    heroBody: { padding: space.xl, gap: space.sm },    heroEyebrow: {
      ...type.overline,
      color: c.accent,
      fontFamily: font.bold,
    },
    heroName: { ...type.display, fontSize: 30, lineHeight: 36, color: c.text },
    heroFacts: { ...type.meta, color: c.accent },
    heroPlay: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      alignSelf: 'flex-start' as const,
      gap: space.sm,
      marginTop: space.sm,
      paddingHorizontal: space.xl,
      paddingVertical: space.md,
      borderRadius: radius.pill,
      backgroundColor: c.accent,
    },
    heroPlayText: { ...type.label, fontFamily: font.semibold, color: c.onAccent },
    shelf: { marginTop: space.xl, gap: space.md },
    head: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.sm,
      paddingHorizontal: space.xl,
    },
    // The one bit of colour that is not artwork — enough to tell shelves apart
    // while they are still loading.
    tick: { width: 3, height: 15, borderRadius: 2 },
    headText: {
      ...type.label,
      fontFamily: font.semibold,
      fontSize: 15,
      color: c.text,
      flex: 1,
    },
    headLinkBox: { flexDirection: 'row' as const, alignItems: 'center' as const, gap: 2 },
    headLink: { ...type.meta, color: c.accent, fontFamily: font.medium },
    rail: { paddingHorizontal: space.xl, gap: space.md },
    railPlaceholder: {
      height: CARD * 1.5,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    card: { width: CARD, gap: space.sm },
    pressed: { opacity: 0.6 },
    poster: {
      width: CARD,
      height: CARD * 1.5,
      borderRadius: radius.md,
      backgroundColor: c.surface,
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
    cardName: { ...type.meta, fontSize: 11, color: c.muted },
    resume: { width: 168, gap: space.sm },
    resumeName: { ...type.meta, fontSize: 11, color: c.muted },
  }));
}

function HomeScreenView({
  recent,
  saved,
  genres,
  onOpenTitle,
  onOpenShelf,
  onPlayFile,
  onSeeAll,
}: {
  recent: Item[];
  saved: Saved[];
  /** Chosen in Settings. These lead the page; history fills in behind them. */
  genres: string[];
  onOpenTitle: (title: Title) => void;
  /** The whole shelf as a grid — a rail only ever shows the first handful. */
  onOpenShelf: (shelf: Shelf) => void;
  onPlayFile: (item: Item, index: number) => void;
  onSeeAll: () => void;
}) {
  const insets = useSafeAreaInsets();
  const colors = useColors();
  const styles = useHomeStyles();
  // Seeded from whatever has already resolved this session, so switching tabs
  // and coming back paints the shelves on the first render — no spinner, no
  // second pass.
  const [loaded, setLoaded] = useState<Record<string, Title[]>>(() =>
    Object.fromEntries(
      SHELVES.map((s) => [shelfKey(s), peekCatalog(s.kind, s.genre)]).filter(
        ([, titles]) => titles
      ) as [string, Title[]][]
    )
  );

  // Re-runs when the connection comes back. A shelf that already loaded is a
  // memory-cache hit, so only the ones that failed actually go out again.
  const online = useOnline();

  /**
   * Shelves built from what has actually been watched.
   *
   * Empty until there is enough history to mean anything — see `topGenres` —
   * so a fresh install just gets the popularity charts, which is the only
   * honest thing to show someone nothing is known about yet.
   */
  const [picks, setPicks] = useState<Shelf[]>([]);
  useEffect(() => {
    // In-progress titles lead the taste signal — what you are mid-way through
    // says more than something finished last month. They never appear *in* the
    // recommendation shelves themselves (those stay fresh titles only).
    const identified = (items: Item[]) =>
      items
        .filter((h): h is Item & { imdb: string } => !!h.imdb)
        .map((h) => ({ imdb: h.imdb, kind: h.kind ?? ('movie' as const) }));

    const continuing = recent.filter(isInProgress);
    const rest = recent.filter((h) => !continuing.includes(h));
    const seen = [
      ...identified(continuing),
      ...identified(rest),
      ...saved.map((s) => ({ imdb: s.imdb, kind: s.kind })),
    ].slice(0, 30);
    if (!seen.length) {
      setPicks([]);
      return;
    }
    let live = true;
    watchedGenres(seen, 4).then((genres) => {
      if (!live) return;
      setPicks(
        genres.map(({ genre, kind }, i) => ({
          title: i === 0 ? 'Recommended for you' : `Because you like ${genre}`,
          kind,
          genre,
        }))
      );
    });
    return () => {
      live = false;
    };
  }, [recent, saved, online]);

  /**
   * What was asked for, ahead of what was inferred.
   *
   * A genre picked in Settings is a statement; a genre counted out of watch
   * history is a guess. When both point at the same catalogue the stated one
   * wins the slot, so the page never explains a shelf you chose as "because
   * you like" something.
   */
  const chosen = useMemo(
    () =>
      genres.map((genre) => ({
        title: genre === ANIME_GENRE ? 'Anime' : genre,
        kind: kindOfGenre(genre),
        genre,
      })),
    [genres]
  );

  // Chosen first, then recommendations, then the fixed charts — each minus
  // whatever the ones above already cover, so no catalogue appears twice.
  const shelves = useMemo(() => {
    const taken = new Set(chosen.map(shelfKey));
    const rest = picks.filter((p) => !taken.has(shelfKey(p)));
    for (const p of rest) taken.add(shelfKey(p));
    return [...chosen, ...rest, ...SHELVES.filter((s) => !taken.has(shelfKey(s)))];
  }, [chosen, picks]);

  useEffect(() => {
    let live = true;
    // All at once: they are independent, cached after the first visit, and a
    // shelf that arrives late just fills in where its heading already is.
    for (const shelf of shelves) {
      loadCatalog(shelf.kind, shelf.genre).then((titles) => {
        if (!live || !titles.length) return;
        setLoaded((all) => ({ ...all, [shelfKey(shelf)]: titles }));
      });
    }
    return () => {
      live = false;
    };
  }, [shelves, online]);

  // Once there is a taste to go on the hero follows it, and says so — the
  // popularity chart is what to show someone nothing is known about yet.
  const watchedIds = useMemo(
    () => new Set(recent.map((h) => h.imdb).filter(Boolean) as string[]),
    [recent]
  );
  // Whatever leads the page leads the hero: a genre chosen in Settings, else
  // the top recommendation, else the chart.
  const heroShelf = chosen[0] ?? picks[0] ?? SHELVES[0];
  // Personalized rails never re-show titles already in history — Continue
  // watching is the place for unfinished ones.
  const featured = (
    loaded[shelfKey(heroShelf)] ??
    loaded[shelfKey(SHELVES[0])] ??
    []
  )
    .filter((t) => !watchedIds.has(t.imdb))
    .slice(0, 5);
  const resumable = recent.filter(isInProgress);
  const later = inList(saved, 'later');
  const favorites = inList(saved, 'favorite');

  return (
    <View style={styles.page}>
      <StatusBar barStyle="light-content" backgroundColor="transparent" translucent />
      {/* A FlatList, not a ScrollView: a ScrollView renders every child up
          front, so all seven rails and their posters were being built — and
          held — whether or not you ever scrolled to them. */}
      <FlatList
        data={shelves}
        keyExtractor={(shelf) => shelfKey(shelf)}
        contentContainerStyle={styles.body}
        showsVerticalScrollIndicator={false}
        initialNumToRender={2}
        maxToRenderPerBatch={2}
        windowSize={3}
        removeClippedSubviews
        ListHeaderComponent={
          <>
            {featured.length > 0 ? (
              <Hero
                titles={featured}
                // The genre only holds while its shelf is the one on screen —
                // the fallback above can leave the chart showing instead.
                // Only claims to be personal while the shelf backing it is —
                // the fallback below can leave the chart showing instead.
                eyebrow={
                  loaded[shelfKey(heroShelf)]
                    ? chosen[0]
                      ? `in ${chosen[0].genre}`
                      : picks[0]
                        ? 'for you'
                        : 'today'
                    : 'today'
                }
                top={insets.top}
                onOpen={onOpenTitle}
              />
            ) : (
              <View style={[styles.heroEmpty, { paddingTop: insets.top }]}>
                <ActivityIndicator color={colors.accent} />
              </View>
            )}

            {resumable.length > 0 && (
              <View style={styles.shelf}>
                <Head title="Continue watching" hue={hues[0]} onSeeAll={onSeeAll} />
                <Rail
                  data={resumable}
                  keyOf={(i) => i.uri}
                  render={(item) => {
                    const index = item.lastFileIndex!;
                    const duration = item.durations?.[index];
                    const position = item.positions?.[index] ?? 0;
                    return (
                      <Pressable
                        onPress={() => onPlayFile(item, index)}
                        style={({ pressed }) => [styles.resume, pressed && styles.pressed]}
                      >
                        <Thumb
                          name={item.torrentName ?? item.title}
                          poster={item.poster}
                          icon="play"
                          progress={duration ? (position / duration) * 100 : 0}
                          width={168}
                          height={96}
                        />
                        <Text style={styles.resumeName} numberOfLines={2}>
                          {item.files?.find((f) => f.index === index)?.name ?? item.title}
                        </Text>
                      </Pressable>
                    );
                  }}
                />
              </View>
            )}

            {/* The two saved lists, above the recommendations: something put
                here was chosen deliberately, which beats anything guessed. */}
            {later.length > 0 && (
              <View style={styles.shelf}>
                <Head title="Watch later" hue={hues[1]} />
                <Rail
                  data={later}
                  keyOf={(s) => s.imdb}
                  render={(item) => (
                    <Poster
                      title={asTitle(item)}
                      onPress={() => onOpenTitle(asTitle(item))}
                    />
                  )}
                />
              </View>
            )}

            {favorites.length > 0 && (
              <View style={styles.shelf}>
                <Head title="Favourites" hue={hues[2]} />
                <Rail
                  data={favorites}
                  keyOf={(s) => s.imdb}
                  render={(item) => (
                    <Poster
                      title={asTitle(item)}
                      onPress={() => onOpenTitle(asTitle(item))}
                    />
                  )}
                />
              </View>
            )}
          </>
        }
        renderItem={({ item: shelf, index: i }) => {
          const raw = loaded[shelfKey(shelf)];
          // Genre shelves = recommendations: drop anything already watched.
          // Trending / Popular keep the full chart.
          const data = raw
            ? shelf.genre
              ? raw.filter((t) => !watchedIds.has(t.imdb))
              : raw
            : null;
          return (
          <View style={styles.shelf}>
            <Head
              title={shelf.title}
              hue={hues[(i + 1) % hues.length]}
              onSeeAll={data?.length ? () => onOpenShelf(shelf) : undefined}
            />
            {data ? (
              <Rail
                data={data}
                keyOf={(t) => t.imdb}
                render={(item) => (
                  <Poster title={item} onPress={() => onOpenTitle(item)} />
                )}
              />
            ) : (
              <View style={styles.railPlaceholder}>
                <ActivityIndicator color={colors.faint} size="small" />
              </View>
            )}
          </View>
          );
        }}
      />
    </View>
  );
}

/** One horizontal shelf. Windowed hard: a rail is 50 titles wide and only ever
 *  three of them are on screen, so rendering the rest is pure waste. */
function Rail<T>({
  data,
  keyOf,
  render,
}: {
  data: T[];
  keyOf: (item: T) => string;
  render: (item: T) => React.ReactElement;
}) {
  const styles = useHomeStyles();
  return (
    <FlatList
      horizontal
      data={data}
      keyExtractor={keyOf}
      showsHorizontalScrollIndicator={false}
      contentContainerStyle={styles.rail}
      initialNumToRender={4}
      maxToRenderPerBatch={4}
      windowSize={3}
      removeClippedSubviews
      renderItem={({ item }) => render(item)}
    />
  );
}

/**
 * Top of the page: the five most popular titles, one per page, swipeable.
 *
 * A plain FlatList with `pagingEnabled` — no carousel library, and no timer
 * moving it on its own, which only ever fights the thumb.
 */
function Hero({
  titles,
  eyebrow,
  top,
  onOpen,
}: {
  titles: Title[];
  /** What the ranking is of — "today", or the genre it was built from. */
  eyebrow: string;
  top: number;
  onOpen: (title: Title) => void;
}) {
  const { width } = useWindowDimensions();
  const [page, setPage] = useState(0);
  const list = useRef<FlatList<Title>>(null);
  const pageRef = useRef(0);
  const colors = useColors();
  const styles = useHomeStyles();

  pageRef.current = page;

  // Advance every 10s; a manual swipe resets the clock via the page change.
  useEffect(() => {
    if (titles.length < 2) return;
    const id = setInterval(() => {
      const next = (pageRef.current + 1) % titles.length;
      list.current?.scrollToIndex({ index: next, animated: true });
      setPage(next);
    }, 10_000);
    return () => clearInterval(id);
  }, [titles.length, page]);

  return (
    <View style={styles.hero}>
      <FlatList
        ref={list}
        data={titles}
        horizontal
        pagingEnabled
        showsHorizontalScrollIndicator={false}
        keyExtractor={(t) => t.imdb}
        getItemLayout={(_, i) => ({ length: width, offset: width * i, index: i })}
        onScroll={(e) => {
          const next = Math.round(e.nativeEvent.contentOffset.x / width);
          if (next !== page) setPage(next);
        }}
        scrollEventThrottle={16}
        onMomentumScrollEnd={(e) =>
          setPage(Math.round(e.nativeEvent.contentOffset.x / width))
        }
        renderItem={({ item, index }) => (
          <Pressable onPress={() => onOpen(item)} style={[styles.heroPage, { width }]}>
            {!!(item.backdrop ?? item.poster) && (
              <Image
                source={{ uri: item.backdrop ?? item.poster! }}
                style={styles.heroArt}
                resizeMode="cover"
              />
            )}
            <View style={styles.heroFade} />
            <View style={[styles.heroBody, { paddingTop: top + space.xl }]}>
              <Text style={styles.heroEyebrow}>
                #{index + 1} {eyebrow}
              </Text>
              <Text style={styles.heroName} numberOfLines={2}>
                {item.title}
              </Text>
              <Text style={styles.heroFacts} numberOfLines={1}>
                {[item.rating && `★ ${item.rating}`, item.year, item.genres.join(' · ')]
                  .filter(Boolean)
                  .join('   ')}
              </Text>
              <View style={styles.heroPlay}>
                <Icon name="play" size={16} color={colors.onAccent} />
                <Text style={styles.heroPlayText}>Watch</Text>
              </View>
            </View>
          </Pressable>
        )}
      />

      <View style={styles.dots}>
        {titles.map((t, i) => (
          <HeroDot key={t.imdb} active={i === page} accent={colors.accent} faint={colors.faint} />
        ))}
      </View>
    </View>
  );
}

/** Red pill that stretches when active — smoother than swapping styles. */
function HeroDot({
  active,
  accent,
  faint,
}: {
  active: boolean;
  accent: string;
  faint: string;
}) {
  const width = useRef(new Animated.Value(active ? 18 : 6)).current;
  const opacity = useRef(new Animated.Value(active ? 1 : 0.45)).current;

  useEffect(() => {
    Animated.parallel([
      Animated.spring(width, {
        toValue: active ? 18 : 6,
        useNativeDriver: false,
        friction: 7,
        tension: 80,
      }),
      Animated.timing(opacity, {
        toValue: active ? 1 : 0.45,
        duration: 220,
        useNativeDriver: false,
      }),
    ]).start();
  }, [active, width, opacity]);

  return (
    <Animated.View
      style={{
        width,
        height: 6,
        borderRadius: 3,
        opacity,
        backgroundColor: active ? accent : faint,
      }}
    />
  );
}

function Head({
  title,
  hue,
  onSeeAll,
}: {
  title: string;
  hue: string;
  onSeeAll?: () => void;
}) {
  const colors = useColors();
  const styles = useHomeStyles();
  return (
    <View style={styles.head}>
      <View style={[styles.tick, { backgroundColor: hue }]} />
      <Text style={styles.headText}>{title}</Text>
      {!!onSeeAll && (
        <Pressable onPress={onSeeAll} hitSlop={10} style={styles.headLinkBox}>
          <Text style={styles.headLink}>See all</Text>
          <Icon name="chevron-right" size={14} color={colors.accent} />
        </Pressable>
      )}
    </View>
  );
}

function Poster({ title, onPress }: { title: Title; onPress: () => void }) {
  const colors = useColors();
  const styles = useHomeStyles();
  return (
    <Pressable onPress={onPress} style={({ pressed }) => [styles.card, pressed && styles.pressed]}>
      <Image source={{ uri: title.poster! }} style={styles.poster} resizeMode="cover" />
      {!!title.rating && (
        <View style={styles.score}>
          <Icon name="star" size={9} color={colors.warn} />
          <Text style={styles.scoreText}>{title.rating}</Text>
        </View>
      )}
      <Text style={styles.cardName} numberOfLines={1}>
        {title.title}
      </Text>
    </Pressable>
  );
}

/** Memoised: it stays mounted behind the other tabs, and every prop it takes is
 *  stable, so a tab switch must not rebuild seven shelves of posters. */
export const HomeScreen = memo(HomeScreenView);
