import { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Image,
  Linking,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icon } from '../components/Icon';
import { ProgressBar } from '../components/ProgressBar';
import { bytes } from '../lib/format';
import { hueOf } from '../lib/title';
import {
  episodeTag,
  loadTitle,
  reviewLinks,
  type Episode,
  type Title,
} from '../lib/meta';
import { WATCHED } from '../lib/watched';
import { health, searchTorrents, type Hit } from '../lib/search';
import { colors, fadeToBg, font, radius, space, TAB_INSET, type } from '../theme';

/**
 * One actor.
 *
 * Cinemeta carries names only — no headshots, and every source that has them
 * (TMDB, IMDb) wants an API key. So the portrait is generated: initials over a
 * colour derived from the name, the same trick `Thumb` uses for torrents, which
 * at least makes the same actor look the same everywhere in the app.
 */
function CastCard({ name }: { name: string }) {
  const initials = name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('');

  return (
    <View style={styles.castCard}>
      <View style={[styles.face, { backgroundColor: `hsl(${hueOf(name)}, 42%, 34%)` }]}>
        <Text style={styles.initials}>{initials}</Text>
      </View>
      <Text style={styles.castName} numberOfLines={2}>
        {name}
      </Text>
    </View>
  );
}

const HEALTH = {
  fast: { label: 'Fast', fg: colors.good, bg: colors.goodSoft },
  good: { label: 'Good', fg: colors.good, bg: colors.goodSoft },
  weak: { label: 'Weak', fg: colors.warn, bg: colors.warnSoft },
  dead: { label: 'Dead', fg: colors.danger, bg: colors.dangerSoft },
};

/**
 * One title: the artwork and the facts up top, the releases that can actually
 * play it underneath.
 *
 * `seed` is whatever the caller already had — a shelf entry carries the whole
 * payload, a search result only an id — so the page paints immediately and
 * fills in the rest if it has to.
 */
export function DetailScreen({
  seed,
  busy,
  error: opening,
  progress,
  onPlay,
  onDownload,
  onBack,
}: {
  seed: Title;
  /** A picked source is being resolved — the file picker takes over shortly. */
  busy?: boolean;
  error?: string | null;
  /** Episode id -> how much of it has been watched, 0-100. */
  progress?: Record<string, number>;
  /** `tag` is the SxxEyy the torrent's own file list should be matched against. */
  onPlay: (magnet: string, episodeId?: string, tag?: string) => void;
  /** Same source, kept to storage instead of streamed. */
  onDownload: (magnet: string, tag?: string) => void;
  onBack: () => void;
}) {
  const insets = useSafeAreaInsets();
  const [title, setTitle] = useState<Title>(seed);
  const [sources, setSources] = useState<Hit[] | null>(null);
  /** Plot expanded. Three lines is enough to decide; the rest is on request. */
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abort = useRef<AbortController | null>(null);
  const scroller = useRef<ScrollView>(null);
  /** Where the release list starts, so tapping an episode can bring it up. */
  const sourcesY = useRef(0);

  // A `seed` can outlive the shape that built it — one held in App state across
  // a reload, say — so nothing here indexes into a list it has not defaulted.
  const episodes = title.episodes ?? [];
  const cast = title.cast ?? [];
  const genres = title.genres ?? [];
  const directors = title.directors ?? [];

  const seasons = [...new Set(episodes.map((e) => e.season))];
  const [season, setSeason] = useState<number | null>(null);
  /** Which episode the releases below are for. Null on a film. */
  const [picked, setPicked] = useState<Episode | null>(null);

  const tag = picked ? episodeTag(picked.season, picked.episode) : undefined;
  /** Hands the caller everything it needs to open the right file straight away. */
  const start = (magnet: string) => onPlay(magnet, picked?.id, tag);
  const save = (magnet: string) => onDownload(magnet, tag);

  // A catalog row carries no episode list, so a series always needs the full
  // record even when the shelf already gave us a plot.
  useEffect(() => {
    if (seed.plot && (seed.kind === 'movie' || (seed.episodes?.length ?? 0) > 0)) return;
    let live = true;
    loadTitle(seed.imdb, seed.kind).then((full) => live && full && setTitle(full));
    return () => {
      live = false;
    };
  }, [seed]);

  // Open on the first episode not yet finished — carrying on is the common
  // case, and it gives the Play button something to mean on a series.
  useEffect(() => {
    if (title.kind !== 'tv' || !episodes.length || picked) return;
    const next =
      episodes.find((e) => e.season > 0 && (progress?.[e.id] ?? 0) < WATCHED) ??
      episodes[0];
    setPicked(next);
    setSeason(next.season);
  }, [title, picked, progress]);

  // Releases are found by name, not by id: the index's own IMDb tagging is
  // patchy, and a title search returns the rips that were never tagged too.
  useEffect(() => {
    const ctrl = new AbortController();
    abort.current = ctrl;
    const query = [
      title.title,
      picked ? episodeTag(picked.season, picked.episode) : title.kind === 'movie' && title.year,
    ]
      .filter(Boolean)
      .join(' ');
    setSources(null);
    searchTorrents(query, ctrl.signal)
      .then((hits) => !ctrl.signal.aborted && setSources(hits))
      .catch((e) => {
        if (ctrl.signal.aborted) return;
        setSources([]);
        setError(String(e?.message ?? e));
      });
    return () => ctrl.abort();
  }, [title.title, title.year, title.kind, picked]);

  return (
    <View style={styles.page}>
      <StatusBar barStyle="light-content" backgroundColor="transparent" translucent />
      <ScrollView
        ref={scroller}
        showsVerticalScrollIndicator={false}
        contentContainerStyle={styles.body}
      >
        <View style={styles.art}>
          {!!(title.backdrop ?? title.poster) && (
            <Image
              source={{ uri: title.backdrop ?? title.poster! }}
              style={StyleSheet.absoluteFill}
              resizeMode="cover"
            />
          )}
          {/* One CSS gradient (RN 0.86) — the stacked washes this replaced cut
              a hard black line across the middle of every backdrop. */}
          <View style={styles.fade} />
        </View>

        <Pressable
          onPress={onBack}
          hitSlop={12}
          style={[styles.back, { top: insets.top + space.md }]}
          accessibilityLabel="Back"
        >
          <Icon name="arrow-left" size={20} color={colors.text} />
        </Pressable>

        <View style={styles.head}>
          {!!title.poster && (
            <Image source={{ uri: title.poster }} style={styles.poster} resizeMode="cover" />
          )}
          <View style={styles.headText}>
            <Text style={styles.name}>{title.title}</Text>
            <View style={styles.facts}>
              {!!title.rating && (
                <View style={styles.rating}>
                  <Icon name="star" size={12} color={colors.warn} />
                  <Text style={styles.ratingText}>{title.rating}</Text>
                </View>
              )}
              <Text style={styles.factsText}>
                {[title.year, title.runtime, title.kind === 'tv' ? 'Series' : 'Film']
                  .filter(Boolean)
                  .join(' · ')}
              </Text>
            </View>
            <View style={styles.genres}>
              {genres.map((g) => (
                <View key={g} style={styles.genre}>
                  <Text style={styles.genreText}>{g}</Text>
                </View>
              ))}
            </View>
          </View>
        </View>

        {/* The one thing most people want: start the healthiest release. The
            list below is for when that is not the one you wanted. */}
        <View style={styles.actions}>
          <Pressable
            disabled={!sources?.length || busy}
            onPress={() => sources?.[0] && start(sources[0].magnet)}
            style={({ pressed }) => [
              styles.play,
              (!sources?.length || busy) && styles.playOff,
              pressed && styles.pressed,
            ]}
          >
            <Icon name="play" size={17} color={colors.onAccent} />
            <Text style={styles.playText}>
              {busy
                ? 'Opening…'
                : !sources?.length
                  ? 'Finding releases…'
                  : tag
                    ? `Play ${tag}`
                    : `Play ${sources[0].quality ?? 'best release'}`}
            </Text>
          </Pressable>

          {/* Same release, kept rather than streamed — the Downloads tab takes
              over from here. */}
          <Pressable
            disabled={!sources?.length || busy}
            onPress={() => sources?.[0] && save(sources[0].magnet)}
            accessibilityLabel="Download"
            style={({ pressed }) => [
              styles.download,
              (!sources?.length || busy) && styles.downloadOff,
              pressed && styles.pressed,
            ]}
          >
            <Icon name="download" size={18} color={colors.text} />
          </Pressable>
        </View>

        {!!title.plot && (
          <Pressable onPress={() => setOpen((o) => !o)}>
            <Text style={styles.plot} numberOfLines={open ? undefined : 3}>
              {title.plot}
            </Text>
          </Pressable>
        )}

        {directors.length > 0 && (
          <Text style={styles.crew}>
            <Text style={styles.crewLabel}>Directed by </Text>
            {directors.join(', ')}
          </Text>
        )}

        {cast.length > 0 && (
          <View style={styles.castBlock}>
            <Text style={[styles.heading, styles.castHeading]}>Cast</Text>
            <FlatList
              horizontal
              data={cast.slice(0, 12)}
              keyExtractor={(who) => who}
              showsHorizontalScrollIndicator={false}
              contentContainerStyle={styles.castRail}
              renderItem={({ item: who }) => <CastCard name={who} />}
            />
          </View>
        )}

        {seasons.length > 0 && (
          <View style={styles.block}>
            <View style={styles.seasonRow}>
              <Text style={styles.heading}>Episodes</Text>
              <Text style={styles.seasonCount}>
                {episodes.filter((e) => e.season === season).length} in season
              </Text>
            </View>

            {seasons.length > 1 && (
              <FlatList
                horizontal
                data={seasons}
                keyExtractor={(s) => String(s)}
                showsHorizontalScrollIndicator={false}
                contentContainerStyle={styles.seasonChips}
                renderItem={({ item: s }) => (
                  <Pressable
                    onPress={() => setSeason(s)}
                    style={[styles.seasonChip, s === season && styles.seasonChipOn]}
                  >
                    <Text
                      style={[styles.seasonText, s === season && styles.seasonTextOn]}
                    >
                      {s === 0 ? 'Specials' : `Season ${s}`}
                    </Text>
                  </Pressable>
                )}
              />
            )}

            {episodes
              .filter((e) => e.season === season)
              .map((ep) => {
                const seen = progress?.[ep.id] ?? 0;
                const done = seen >= WATCHED;
                const on = picked?.id === ep.id;
                return (
                  <Pressable
                    key={ep.id}
                    // Selecting the episode re-runs the release search for it;
                    // bringing that list into view is the whole point of the tap.
                    onPress={() => {
                      setPicked(ep);
                      scroller.current?.scrollTo({ y: sourcesY.current, animated: true });
                    }}
                    style={({ pressed }) => [
                      styles.episode,
                      on && styles.episodeOn,
                      pressed && styles.pressed,
                    ]}
                  >
                    <View>
                      {ep.thumbnail ? (
                        <Image source={{ uri: ep.thumbnail }} style={styles.still} />
                      ) : (
                        <View style={[styles.still, styles.stillEmpty]}>
                          <Icon name="film" size={16} color={colors.faint} />
                        </View>
                      )}
                      {seen > 0 && !done && (
                        <ProgressBar value={seen} style={styles.stillBar} height={3} />
                      )}
                      {done && (
                        <View style={styles.seen}>
                          <Icon name="check" size={11} color={colors.onAccent} />
                        </View>
                      )}
                    </View>

                    <View style={styles.episodeText}>
                      <Text style={styles.episodeName} numberOfLines={1}>
                        {ep.episode}. {ep.name}
                      </Text>
                      <Text style={styles.episodeMeta}>
                        {done
                          ? 'Watched'
                          : seen > 0
                            ? `${Math.round(seen)}% watched`
                            : (ep.released?.slice(0, 10) ?? 'Not watched')}
                      </Text>
                      {!!ep.overview && (
                        <Text style={styles.episodePlot} numberOfLines={2}>
                          {ep.overview}
                        </Text>
                      )}
                    </View>
                  </Pressable>
                );
              })}
          </View>
        )}

        {!!title.awards && (
          <View style={styles.awards}>
            <Icon name="award" size={14} color={colors.warn} />
            <Text style={styles.awardsText}>{title.awards}</Text>
          </View>
        )}

        <View style={styles.block}>
          <Text style={styles.heading}>Ratings & reviews</Text>
          <View style={styles.chips}>
            {reviewLinks(title).map((link) => (
              <Pressable
                key={link.label}
                // Opens the phone's browser — these are other people's sites,
                // not something to render in here.
                onPress={() => Linking.openURL(link.url).catch(() => {})}
                style={({ pressed }) => [styles.link, pressed && styles.pressed]}
              >
                <Text style={styles.linkText}>{link.label}</Text>
                <Icon name="external-link" size={12} color={colors.muted} />
              </Pressable>
            ))}
          </View>
        </View>

        <View
          style={styles.sources}
          onLayout={(e) => (sourcesY.current = e.nativeEvent.layout.y)}
        >
          <Text style={styles.heading}>
            {sources ? `${sources.length} release${sources.length === 1 ? '' : 's'}` : 'Finding releases…'}
          </Text>

          {!sources && <ActivityIndicator color={colors.accent} style={styles.spin} />}

          {busy && (
            <View style={styles.opening}>
              <ActivityIndicator color={colors.accent} size="small" />
              <Text style={styles.openingText}>Reading torrent…</Text>
            </View>
          )}

          {!!(error ?? opening) && <Text style={styles.error}>{error ?? opening}</Text>}

          {sources?.length === 0 && !error && (
            <Text style={styles.none}>
              Nothing on the index for this title. Try the Search tab with a
              different spelling.
            </Text>
          )}

          {sources?.map((hit, i) => {
            const tone = HEALTH[health(hit.seeders)];
            return (
              <Pressable
                key={hit.id}
                onPress={() => start(hit.magnet)}
                style={({ pressed }) => [styles.source, pressed && styles.pressed]}
              >
                {/* The first one is what a play button would pick. */}
                <View style={[styles.mark, i === 0 && styles.markBest]}>
                  <Icon name="play" size={14} color={i === 0 ? colors.onAccent : colors.muted} />
                </View>
                <View style={styles.sourceText}>
                  <Text style={styles.sourceName} numberOfLines={1}>
                    {hit.name}
                  </Text>
                  <Text style={styles.sourceMeta}>
                    {bytes(hit.size)}
                    {hit.quality ? ` · ${hit.quality}` : ''}
                  </Text>
                </View>
                <View style={[styles.pill, { backgroundColor: tone.bg }]}>
                  <View style={[styles.dot, { backgroundColor: tone.fg }]} />
                  <Text style={[styles.pillText, { color: tone.fg }]}>{hit.seeders}</Text>
                </View>
              </Pressable>
            );
          })}
        </View>
      </ScrollView>
    </View>
  );
}

const ART = 300;

const styles = StyleSheet.create({
  page: { flex: 1, backgroundColor: colors.bg },
  body: { paddingBottom: TAB_INSET },
  art: { height: ART, backgroundColor: colors.surface },
  fade: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    height: ART,
    experimental_backgroundImage: fadeToBg(0.3),
  },
  back: {
    position: 'absolute',
    left: space.lg,
    width: 38,
    height: 38,
    borderRadius: radius.pill,
    backgroundColor: '#0b0e14aa',
    alignItems: 'center',
    justifyContent: 'center',
  },
  head: { flexDirection: 'row', gap: space.lg, paddingHorizontal: space.xl, marginTop: -70 },
  poster: {
    width: 108,
    height: 158,
    borderRadius: radius.md,
    backgroundColor: colors.surface,
  },
  headText: { flex: 1, gap: space.sm, paddingTop: 74 },
  name: { ...type.display, fontSize: 22, color: colors.text, lineHeight: 28 },
  facts: { flexDirection: 'row', alignItems: 'center', gap: space.md },
  rating: { flexDirection: 'row', alignItems: 'center', gap: 3 },
  ratingText: { ...type.label, fontFamily: font.semibold, color: colors.warn },
  factsText: { ...type.meta, color: colors.muted, flex: 1 },
  genres: { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs },
  genre: {
    paddingHorizontal: space.sm,
    paddingVertical: 3,
    borderRadius: radius.sm,
    backgroundColor: colors.surfaceAlt,
  },
  genreText: { ...type.meta, fontSize: 11, color: colors.muted },
  actions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    marginHorizontal: space.xl,
    marginTop: space.xl,
  },
  download: {
    width: 50,
    height: 50,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.surfaceAlt,
  },
  downloadOff: { opacity: 0.4 },
  play: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: space.sm,
    paddingVertical: space.md + 2,
    borderRadius: radius.pill,
    backgroundColor: colors.accent,
  },
  playOff: { backgroundColor: colors.surfaceAlt },
  playText: { ...type.label, fontSize: 15, fontFamily: font.semibold, color: colors.onAccent },
  plot: {
    ...type.body,
    color: colors.muted,
    paddingHorizontal: space.xl,
    marginTop: space.lg,
  },
  seasonRow: { flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between' },
  seasonCount: { ...type.meta, fontSize: 11, color: colors.faint },
  seasonChips: { gap: space.sm, paddingVertical: space.xs },
  seasonChip: {
    paddingHorizontal: space.md,
    paddingVertical: 6,
    borderRadius: radius.pill,
    backgroundColor: colors.surface,
  },
  seasonChipOn: { backgroundColor: colors.accent },
  seasonText: { ...type.meta, fontFamily: font.medium, color: colors.muted },
  seasonTextOn: { color: colors.onAccent },
  episode: {
    flexDirection: 'row',
    gap: space.md,
    padding: space.sm,
    borderRadius: radius.md,
    backgroundColor: colors.surface,
  },
  // The selected one is what the Play button and the release list refer to.
  episodeOn: { backgroundColor: colors.surfaceAlt },
  still: {
    width: 116,
    height: 66,
    borderRadius: radius.sm,
    backgroundColor: colors.surfaceAlt,
  },
  stillEmpty: { alignItems: 'center', justifyContent: 'center' },
  stillBar: { position: 'absolute', left: space.sm, right: space.sm, bottom: space.sm },
  seen: {
    position: 'absolute',
    top: 4,
    right: 4,
    width: 18,
    height: 18,
    borderRadius: 9,
    backgroundColor: colors.accent,
    alignItems: 'center',
    justifyContent: 'center',
  },
  episodeText: { flex: 1, gap: 2, paddingVertical: 2 },
  episodeName: { ...type.label, color: colors.text },
  episodeMeta: { ...type.meta, fontSize: 11, color: colors.faint },
  episodePlot: { ...type.meta, fontSize: 11, color: colors.muted, lineHeight: 15 },
  crew: { ...type.meta, color: colors.muted, paddingHorizontal: space.xl, marginTop: space.md },
  crewLabel: { color: colors.faint },
  block: { paddingHorizontal: space.xl, marginTop: space.xl, gap: space.sm },
  // The rail bleeds to the screen edge, so its heading holds the gutter alone.
  castBlock: { marginTop: space.xl, gap: space.md },
  castHeading: { paddingHorizontal: space.xl },
  castRail: { paddingHorizontal: space.xl, gap: space.md },
  castCard: { width: 78, alignItems: 'center', gap: space.sm },
  face: {
    width: 68,
    height: 68,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
  },
  initials: { ...type.label, fontSize: 20, fontFamily: font.semibold, color: '#fff' },
  castName: { ...type.meta, fontSize: 11, color: colors.muted, textAlign: 'center' },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  link: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.sm,
    paddingHorizontal: space.md,
    paddingVertical: 8,
    borderRadius: radius.pill,
    backgroundColor: colors.surfaceAlt,
  },
  linkText: { ...type.meta, fontFamily: font.medium, color: colors.text },
  awards: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: space.sm,
    marginHorizontal: space.xl,
    marginTop: space.lg,
    padding: space.md,
    borderRadius: radius.md,
    backgroundColor: colors.warnSoft,
  },
  awardsText: { ...type.meta, color: colors.warn, flex: 1, lineHeight: 17 },
  sources: { paddingHorizontal: space.xl, marginTop: space.xl, gap: space.sm },
  heading: { ...type.overline, color: colors.faint, marginBottom: space.xs },
  spin: { marginTop: space.lg },
  opening: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    padding: space.md,
    borderRadius: radius.md,
    backgroundColor: colors.accentSoft,
  },
  openingText: { ...type.label, color: colors.accent },
  error: { ...type.meta, color: colors.danger },
  none: { ...type.meta, color: colors.faint, lineHeight: 18 },
  source: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    padding: space.md,
    borderRadius: radius.md,
    backgroundColor: colors.surface,
  },
  pressed: { opacity: 0.6 },
  mark: {
    width: 34,
    height: 34,
    borderRadius: radius.pill,
    backgroundColor: colors.surfaceAlt,
    alignItems: 'center',
    justifyContent: 'center',
  },
  markBest: { backgroundColor: colors.accent },
  sourceText: { flex: 1, gap: 2 },
  sourceName: { ...type.label, color: colors.text },
  sourceMeta: { ...type.meta, fontSize: 11, color: colors.faint },
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.xs,
    paddingHorizontal: space.sm,
    paddingVertical: 4,
    borderRadius: radius.pill,
  },
  dot: { width: 6, height: 6, borderRadius: 3 },
  pillText: { ...type.meta, fontSize: 11, fontFamily: font.medium },
});
