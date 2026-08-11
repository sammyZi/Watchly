import { memo, useEffect, useState } from 'react';
import {
  ActivityIndicator,
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
import type { Item } from '../lib/history';
import { loadCatalog, peekCatalog, type Title } from '../lib/meta';
import {
  fadeToBg,
  font,
  hues,
  radius,
  space,
  TAB_INSET,
  type,
  useColors,
  useTheme,
  useThemedStyles,
} from '../theme';

/** The shelves, in the order they appear. Genre rails are the recommendations:
 *  Cinemeta ranks each by popularity, which is as close to "for you" as a
 *  service that knows nothing about the user can get. */
export type Shelf = { title: string; kind: 'movie' | 'tv'; genre?: string };

const SHELVES: Shelf[] = [
  { title: 'Trending films', kind: 'movie' },
  { title: 'Popular series', kind: 'tv' },
  { title: 'Action', kind: 'movie', genre: 'Action' },
  { title: 'Comedy', kind: 'movie', genre: 'Comedy' },
  { title: 'Thriller', kind: 'movie', genre: 'Thriller' },
  { title: 'Animation', kind: 'movie', genre: 'Animation' },
  { title: 'Drama series', kind: 'tv', genre: 'Drama' },
];

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
      gap: 6,
    },
    dot: {
      width: 6,
      height: 6,
      borderRadius: 3,
      backgroundColor: c.faint,
      opacity: 0.5,
    },
    dotOn: { width: 18, backgroundColor: c.accent, opacity: 1 },
    heroBody: { padding: space.xl, gap: space.sm },
    heroEyebrow: {
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
  onOpenTitle,
  onOpenShelf,
  onPlayFile,
  onSeeAll,
}: {
  recent: Item[];
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
  const [shelves, setShelves] = useState<(Title[] | null)[]>(() =>
    SHELVES.map((s) => peekCatalog(s.kind, s.genre))
  );

  useEffect(() => {
    let live = true;
    // All at once: they are independent, cached after the first visit, and a
    // shelf that arrives late just fills in where its heading already is.
    SHELVES.forEach((shelf, i) => {
      loadCatalog(shelf.kind, shelf.genre).then((titles) => {
        if (!live) return;
        setShelves((all) => all.map((s, j) => (j === i ? titles : s)));
      });
    });
    return () => {
      live = false;
    };
  }, []);

  const featured = (shelves[0] ?? []).slice(0, 5);
  const resumable = recent.filter((i) => i.lastFileIndex != null);

  return (
    <View style={styles.page}>
      <StatusBar barStyle="light-content" backgroundColor="transparent" translucent />
      {/* A FlatList, not a ScrollView: a ScrollView renders every child up
          front, so all seven rails and their posters were being built — and
          held — whether or not you ever scrolled to them. */}
      <FlatList
        data={SHELVES}
        keyExtractor={(shelf) => shelf.title}
        contentContainerStyle={styles.body}
        showsVerticalScrollIndicator={false}
        initialNumToRender={2}
        maxToRenderPerBatch={2}
        windowSize={3}
        removeClippedSubviews
        ListHeaderComponent={
          <>
            {featured.length > 0 ? (
              <Hero titles={featured} top={insets.top} onOpen={onOpenTitle} />
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
                          poster={item.posters?.[index]}
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
          </>
        }
        renderItem={({ item: shelf, index: i }) => (
          <View style={styles.shelf}>
            <Head
              title={shelf.title}
              hue={hues[(i + 1) % hues.length]}
              onSeeAll={shelves[i]?.length ? () => onOpenShelf(shelf) : undefined}
            />
            {shelves[i] ? (
              <Rail
                data={shelves[i]!}
                keyOf={(t) => t.imdb}
                render={(item) => (
                  <Poster title={item} onPress={() => onOpenTitle(item)} />
                )}
              />
            ) : (
              // Same height as a poster, so nothing jumps when the shelf lands.
              <View style={styles.railPlaceholder}>
                <ActivityIndicator color={colors.faint} size="small" />
              </View>
            )}
          </View>
        )}
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
  top,
  onOpen,
}: {
  titles: Title[];
  top: number;
  onOpen: (title: Title) => void;
}) {
  const { width } = useWindowDimensions();
  const [page, setPage] = useState(0);
  const colors = useColors();
  const styles = useHomeStyles();

  return (
    <View style={styles.hero}>
      <FlatList
        data={titles}
        horizontal
        pagingEnabled
        showsHorizontalScrollIndicator={false}
        keyExtractor={(t) => t.imdb}
        // Every page is exactly the screen wide, so the list never has to
        // measure anything to know where page N starts.
        getItemLayout={(_, i) => ({ length: width, offset: width * i, index: i })}
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
            {/* One gradient, art to page — no band, no seam. */}
            <View style={styles.heroFade} />
            <View style={[styles.heroBody, { paddingTop: top + space.xl }]}>
              <Text style={styles.heroEyebrow}>
                {index === 0 ? '#1 today' : `#${index + 1} today`}
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
          <View key={t.imdb} style={[styles.dot, i === page && styles.dotOn]} />
        ))}
      </View>
    </View>
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
