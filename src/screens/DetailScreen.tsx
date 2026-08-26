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
import MaterialIcons from '@expo/vector-icons/MaterialIcons';
import { Icon } from '../components/Icon';
import { Marquee } from '../components/Marquee';
import { ProgressBar } from '../components/ProgressBar';
import { bytes, clock, left } from '../lib/format';
import { hueOf } from '../lib/title';
import { loadTitle, reviewLinks, type Episode, type Title } from '../lib/meta';
import { useOnline } from '../lib/net';
import { onList, type List, type Saved } from '../lib/saved';
import { WATCHED } from '../lib/watched';
import { episodeTag, health, searchEpisode, searchTorrents, seasonsIn, type Hit } from '../lib/search';
import {
  fadeToBg,
  font,
  radius,
  space,
  TAB_INSET,
  type,
  useColors,
  useThemedStyles,
  type Colors,
} from '../theme';

const ART = 360;

/** The same red in both themes: this one marks a state, and a saved title has
 *  to look saved over artwork as much as over the page. */
const SAVED_RED = '#e50914';

/** Best first — the same order `quality()` reads them out of a release name. */
const QUALITIES = ['4K', '1080p', '720p', 'CAM'];

/** Episodes shown before the list has to be asked for in full. */
const EPISODE_PAGE = 10;

function useDetailStyles() {
  return useThemedStyles((c) => ({
    page: { flex: 1, backgroundColor: c.bg },
    body: { paddingBottom: TAB_INSET },
    art: { height: ART, backgroundColor: c.surface },
    fade: {
      position: 'absolute' as const,
      left: 0,
      right: 0,
      bottom: 0,
      height: ART,
      experimental_backgroundImage: fadeToBg(c.bg, 0.3),
    },
    back: {
      position: 'absolute' as const,
      left: space.lg,
      width: 38,
      height: 38,
      borderRadius: radius.pill,
      backgroundColor: c.accentSoft,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    // Opposite the back button rather than down with Play: these are about the
    // title, not about starting it.
    saveRow: {
      position: 'absolute' as const,
      right: space.lg,
      flexDirection: 'row' as const,
      gap: space.sm,
    },
    saveBtn: {
      width: 38,
      height: 38,
      borderRadius: radius.pill,
      backgroundColor: c.accentSoft,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    saveBtnOn: { backgroundColor: '#e5091426' },
    head: {
      flexDirection: 'row' as const,
      gap: space.lg,
      paddingHorizontal: space.xl,
      marginTop: -70,
    },
    poster: {
      width: 108,
      height: 158,
      borderRadius: radius.md,
      backgroundColor: c.surface,
    },
    headText: { flex: 1, gap: space.sm, paddingTop: 74 },
    name: { ...type.display, fontSize: 22, color: c.text, lineHeight: 28 },
    facts: { flexDirection: 'row' as const, alignItems: 'center' as const, gap: space.md },
    rating: { flexDirection: 'row' as const, alignItems: 'center' as const, gap: 3 },
    ratingText: { ...type.label, fontFamily: font.semibold, color: c.warn },
    factsText: { ...type.meta, color: c.accent, flex: 1 },
    genres: { flexDirection: 'row' as const, flexWrap: 'wrap' as const, gap: space.xs },
    genre: {
      paddingHorizontal: space.sm,
      paddingVertical: 3,
      borderRadius: radius.sm,
      backgroundColor: c.surfaceAlt,
    },
    genreText: { ...type.meta, fontSize: 11, color: c.accent },
    actions: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      marginHorizontal: space.xl,
      marginTop: space.xl,
    },
    download: {
      width: 50,
      height: 50,
      borderRadius: radius.pill,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      backgroundColor: c.surfaceAlt,
    },
    downloadOff: { opacity: 0.4 },
    seasonDl: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      gap: space.sm,
      marginHorizontal: space.xl,
      marginTop: space.md,
      paddingVertical: space.md,
      borderRadius: radius.pill,
      backgroundColor: c.surfaceAlt,
    },
    seasonDlText: { ...type.label, fontFamily: font.medium, color: c.text },
    play: {
      flex: 1,
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      gap: space.sm,
      paddingVertical: space.md + 2,
      borderRadius: radius.pill,
      backgroundColor: c.accent,
    },
    playOff: { backgroundColor: c.surfaceAlt },
    playText: {
      ...type.label,
      fontSize: 15,
      fontFamily: font.semibold,
      color: c.onAccent,
    },
    // One track holding four segments, so it reads as a single control the
    // way a chip row never can.
    qualityBar: {
      flexDirection: 'row' as const,
      marginHorizontal: space.xl,
      marginTop: space.md,
      padding: 3,
      borderRadius: radius.pill,
      backgroundColor: c.surface,
    },
    quality: {
      flex: 1,
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      gap: 5,
      paddingVertical: 9,
      borderRadius: radius.pill,
    },
    qualityOn: { backgroundColor: c.surfaceAlt },
    qualityText: { ...type.meta, fontFamily: font.medium, color: c.faint },
    qualityTextOn: { color: c.text, fontFamily: font.semibold },
    qualityCount: {
      ...type.meta,
      fontSize: 10,
      color: c.faint,
      opacity: 0.7,
      fontVariant: ['tabular-nums'] as const,
    },
    qualityCountOn: { color: c.accent, opacity: 1 },
    resume: {
      marginHorizontal: space.xl,
      marginTop: space.md,
      gap: space.xs,
    },
    resumeText: { ...type.meta, fontSize: 11, color: c.faint },
    plot: {
      ...type.body,
      color: c.muted,
      paddingHorizontal: space.xl,
      marginTop: space.lg,
    },
    seasonRow: {
      flexDirection: 'row' as const,
      alignItems: 'baseline' as const,
      justifyContent: 'space-between' as const,
    },
    seasonCount: { ...type.meta, fontSize: 11, color: c.faint },
    seasonChips: { gap: space.sm, paddingVertical: space.xs },
    seasonChip: {
      paddingHorizontal: space.md,
      paddingVertical: 6,
      borderRadius: radius.pill,
      backgroundColor: c.surface,
    },
    seasonChipOn: { backgroundColor: c.accent },
    seasonText: { ...type.meta, fontFamily: font.medium, color: c.muted },
    seasonTextOn: { color: c.onAccent },
    episode: {
      flexDirection: 'row' as const,
      gap: space.md,
      padding: space.sm,
      borderRadius: radius.md,
      backgroundColor: c.surface,
    },
    // The selected one is what the Play button and the release list refer to.
    episodeOn: { backgroundColor: c.surfaceAlt },
    still: {
      width: 116,
      height: 66,
      borderRadius: radius.sm,
      backgroundColor: c.surfaceAlt,
    },
    stillEmpty: { alignItems: 'center' as const, justifyContent: 'center' as const },
    stillBar: {
      position: 'absolute' as const,
      left: space.sm,
      right: space.sm,
      bottom: space.sm,
    },
    seen: {
      position: 'absolute' as const,
      top: 4,
      right: 4,
      width: 18,
      height: 18,
      borderRadius: 9,
      backgroundColor: c.accent,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    episodeText: { flex: 1, gap: 2, paddingVertical: 2 },
    episodeName: { ...type.label, color: c.text },
    more: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      gap: space.xs,
      paddingVertical: space.md,
      borderRadius: radius.md,
      backgroundColor: c.surface,
    },
    moreText: { ...type.label, fontFamily: font.medium, color: c.accent },
    episodeMeta: { ...type.meta, fontSize: 11, color: c.faint },
    episodePlot: { ...type.meta, fontSize: 11, color: c.muted, lineHeight: 15 },
    crew: {
      ...type.meta,
      color: c.muted,
      paddingHorizontal: space.xl,
      marginTop: space.md,
    },
    crewLabel: { color: c.faint },
    block: { paddingHorizontal: space.xl, marginTop: space.xl, gap: space.sm },
    // The rail bleeds to the screen edge, so its heading holds the gutter alone.
    castBlock: { marginTop: space.xl, gap: space.md },
    castHeading: { paddingHorizontal: space.xl },
    castRail: { paddingHorizontal: space.xl, gap: space.md },
    castCard: { width: 78, alignItems: 'center' as const, gap: space.sm },
    face: {
      width: 68,
      height: 68,
      borderRadius: radius.pill,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    initials: {
      ...type.label,
      fontSize: 20,
      fontFamily: font.semibold,
      color: '#fff',
    },
    castName: { ...type.meta, fontSize: 11, color: c.muted, textAlign: 'center' as const },
    chips: { flexDirection: 'row' as const, flexWrap: 'wrap' as const, gap: space.sm },
    link: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.sm,
      paddingHorizontal: space.md,
      paddingVertical: 8,
      borderRadius: radius.pill,
      backgroundColor: c.surfaceAlt,
    },
    linkText: { ...type.meta, fontFamily: font.medium, color: c.text },
    awards: {
      flexDirection: 'row' as const,
      alignItems: 'flex-start' as const,
      gap: space.sm,
      marginHorizontal: space.xl,
      marginTop: space.lg,
      padding: space.md,
      borderRadius: radius.md,
      backgroundColor: c.warnSoft,
    },
    awardsText: { ...type.meta, color: c.warn, flex: 1, lineHeight: 17 },
    sources: { paddingHorizontal: space.xl, marginTop: space.xl, gap: space.sm },
    heading: { ...type.overline, color: c.faint, marginBottom: space.xs },
    spin: { marginTop: space.lg },
    opening: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      padding: space.md,
      borderRadius: radius.md,
      backgroundColor: c.accentSoft,
    },
    openingText: { ...type.label, color: c.accent },
    error: { ...type.meta, color: c.danger },
    none: { ...type.meta, color: c.faint, lineHeight: 18 },
    source: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.md,
      padding: space.md,
      borderRadius: radius.md,
      backgroundColor: c.surface,
    },
    pressed: { opacity: 0.6 },
    mark: {
      width: 34,
      height: 34,
      borderRadius: radius.pill,
      backgroundColor: c.surfaceAlt,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    markBest: { backgroundColor: c.accent },
    sourceText: { flex: 1, gap: 2 },
    sourceName: { ...type.label, color: c.text },
    sourceMeta: { ...type.meta, fontSize: 11, color: c.faint },
    pill: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.xs,
      paddingHorizontal: space.sm,
      paddingVertical: 4,
      borderRadius: radius.pill,
    },
    dot: { width: 6, height: 6, borderRadius: 3 },
    pillText: { ...type.meta, fontSize: 11, fontFamily: font.medium },
  }));
}

function healthTone(c: Colors) {
  return {
    fast: { label: 'Fast', fg: c.good, bg: c.goodSoft },
    good: { label: 'Good', fg: c.good, bg: c.goodSoft },
    weak: { label: 'Weak', fg: c.warn, bg: c.warnSoft },
    dead: { label: 'Dead', fg: c.danger, bg: c.dangerSoft },
  };
}

/**
 * One actor.
 *
 * Cinemeta carries names only — no headshots, and every source that has them
 * (TMDB, IMDb) wants an API key. So the portrait is generated: initials over a
 * colour derived from the name, the same trick `Thumb` uses for torrents, which
 * at least makes the same actor look the same everywhere in the app.
 */
function CastCard({ name }: { name: string }) {
  const styles = useDetailStyles();
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

/**
 * Torrentio's 💾 is the one file; a pack's torrent is that times its episodes.
 *
 * Only for Torrentio, though — `perFileSize` is what says so. Every other
 * index (apibay, CSV, EZTV) reports the size of the whole pack torrent
 * already, and multiplying that by the episode count a second time is how a
 * genuine 15GB season showed up as 300GB.
 */
function torrentBytes(hit: Hit, episodes: Episode[], season: number | null): number {
  if (!hit.perFileSize || hit.scope === 'episode' || !hit.size) return hit.size;
  const claims = seasonsIn(hit.name);
  const n = (
    claims.length
      ? episodes.filter((e) => claims.includes(e.season))
      : hit.scope === 'complete'
        ? episodes
        : episodes.filter((e) => e.season === season)
  ).length;
  return n > 1 ? hit.size * n : hit.size;
}

function guessEpisode(imdb: string, progress?: Record<string, number>): Episode {
  const prefix = `${imdb}:`;
  const rows = Object.entries(progress ?? {})
    .flatMap(([id, pct]) => {
      if (!id.startsWith(prefix)) return [];
      const [season, episode] = id.slice(prefix.length).split(':').map(Number);
      if (!Number.isFinite(season) || !Number.isFinite(episode)) return [];
      return [{ id, season, episode, pct }];
    })
    .sort((a, b) => a.season - b.season || a.episode - b.episode);
  const next = rows.find((r) => r.pct < WATCHED) ?? rows[0];
  return {
    id: next?.id ?? `${imdb}:1:1`,
    season: next?.season ?? 1,
    episode: next?.episode ?? 1,
    name: '',
    overview: null,
    thumbnail: null,
    released: null,
  };
}

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
  resume,
  saved,
  onToggleSaved,
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
  /** Where the film was left, in seconds. Series use `progress`. */
  resume?: { position: number; duration: number };
  /** Both saved lists, so the two toggles can show their state. */
  saved: Saved[];
  onToggleSaved: (title: Title, list: List) => void;
  /** `tag` is the SxxEyy the torrent's own file list should be matched against;
   *  `startOver` ignores the stored resume point. */
  onPlay: (
    magnet: string,
    episodeId?: string,
    tag?: string,
    startOver?: boolean
  ) => void;
  /** Same source, kept to storage instead of streamed. `season` downloads
   *  every episode in that season from a pack, not just the picked episode. */
  onDownload: (magnet: string, tag?: string, season?: number) => void;
  onBack: () => void;
}) {
  const insets = useSafeAreaInsets();
  const colors = useColors();
  const styles = useDetailStyles();
  const HEALTH = healthTone(colors);
  const [title, setTitle] = useState<Title>(seed);
  const [sources, setSources] = useState<Hit[] | null>(null);
  /** Which episode/title `sources` belongs to — Play ignores a stale list. */
  const [sourceKey, setSourceKey] = useState<string | null>(null);
  /** Plot expanded. Three lines is enough to decide; the rest is on request. */
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** Both loads below re-run when the connection comes back. */
  const online = useOnline();
  const abort = useRef<AbortController | null>(null);
  const searchGen = useRef(0);
  const scroller = useRef<ScrollView>(null);
  /** Play button — tapping an episode scrolls here, not down to releases. */
  const playY = useRef(0);
  const sourcesY = useRef(0);

  // A `seed` can outlive the shape that built it — one held in App state across
  // a reload, say — so nothing here indexes into a list it has not defaulted.
  const episodes = title.episodes ?? [];
  const cast = title.cast ?? [];
  const genres = title.genres ?? [];
  const directors = title.directors ?? [];

  /** Guess S01E01 (or the next unfinished) so releases start before Cinemeta. */
  const [picked, setPicked] = useState<Episode | null>(() =>
    seed.kind === 'tv' ? guessEpisode(seed.imdb, progress) : null
  );
  const [season, setSeason] = useState<number | null>(() =>
    seed.kind === 'tv' ? (picked?.season ?? 1) : null
  );
  /** The whole season, rather than the first page of it. */
  const [wholeSeason, setWholeSeason] = useState(false);
  const seasons = episodes.length
    ? [...new Set(episodes.map((e) => e.season))]
    : season != null
      ? [season]
      : [];

  const inSeason = episodes.filter((e) => e.season === season);
  // A season of 100 anime episodes buries everything under it — the rest of
  // the page becomes unreachable by thumb. The first ten and a way down is
  // enough to pick from; the selected episode is always among them, or the
  // list opens itself rather than hiding where you are.
  const showsWhole =
    wholeSeason ||
    (picked ? inSeason.findIndex((e) => e.id === picked.id) >= EPISODE_PAGE : false);
  const listed = showsWhole ? inSeason : inSeason.slice(0, EPISODE_PAGE);

  const tag = picked ? episodeTag(picked.season, picked.episode) : undefined;
  const pickKey = picked
    ? `${picked.season}:${picked.episode}`
    : `movie:${title.imdb}`;
  const fresh = sourceKey === pickKey;

  /**
   * Which quality the buttons mean.
   *
   * Null is "whatever streams best", which is the right default — the top of
   * the list is already sorted by swarm health. Choosing one narrows both the
   * Play button and the list under it, so the two never disagree.
   */
  const [wanted, setWanted] = useState<string | null>(null);
  /** In the order they are worth offering, and only the ones actually found. */
  const qualities = QUALITIES.filter((q) => sources?.some((h) => h.quality === q));
  const shown = wanted ? (sources ?? []).filter((h) => h.quality === wanted) : sources;
  const best = fresh ? shown?.[0] ?? null : null;

  // A pick that the new release list cannot honour — a different episode, or a
  // title nobody ripped in 4K — would otherwise disable Play with no way back.
  useEffect(() => {
    if (wanted && sources && !sources.some((h) => h.quality === wanted)) setWanted(null);
  }, [sources, wanted]);

  /**
   * How far into whatever the buttons refer to — the selected episode on a
   * series, the film itself otherwise. Anything past `WATCHED` counts as
   * finished, so it offers a fresh start rather than the last 30 seconds.
   */
  const seen = picked
    ? (progress?.[picked.id] ?? 0)
    : resume
      ? Math.min(100, (resume.position / resume.duration) * 100)
      : 0;
  const canResume = seen >= 1 && seen < WATCHED;
  /** Hands the caller everything it needs to open the right file straight away. */
  const start = (magnet: string, startOver = false) =>
    onPlay(magnet, picked?.id, tag, startOver);
  const [saving, setSaving] = useState(false);
  const wait = busy || saving;
  const save = (magnet: string) => {
    setSaving(true);
    setTimeout(() => onDownload(magnet, tag), 0);
  };
  const saveSeason = async () => {
    if (season == null) return;
    setSaving(true);
    const isPack = (h: Hit) => {
      if (h.scope !== 'season' && h.scope !== 'complete') return false;
      const claims = seasonsIn(h.name);
      return claims.length === 0 || claims.includes(season);
    };
    let pack =
      (shown ?? []).find(isPack) ?? (sources ?? []).find(isPack);
    if (!pack) {
      const season2 = String(season).padStart(2, '0');
      const hits = await searchTorrents(title.title, undefined, {
        suffix: `S${season2}`,
      }).catch(() => [] as Hit[]);
      pack = hits.find(isPack);
    }
    if (!pack) {
      setSaving(false);
      return;
    }
    onDownload(pack.magnet, undefined, season);
  };

  // A catalog row carries no episode list, so a series always needs the full
  // record even when the shelf already gave us a plot.
  useEffect(() => {
    if (seed.plot && (seed.kind === 'movie' || (seed.episodes?.length ?? 0) > 0)) return;
    let live = true;
    loadTitle(seed.imdb, seed.kind).then((full) => live && full && setTitle(full));
    return () => {
      live = false;
    };
  }, [seed, online]);

  /** First episode of a season that has not been finished, else its first. */
  const nextIn = (which: number | null) => {
    const inSeason = episodes.filter((e) => e.season === which);
    return inSeason.find((e) => (progress?.[e.id] ?? 0) < WATCHED) ?? inSeason[0] ?? null;
  };

  /**
   * Changing season changes what is selected, not just what is listed.
   *
   * Tapping "Season 4" used to leave `picked` on the season 1 episode chosen at
   * open: the list below changed, but the Play button and every release under
   * it still referred to S01 — which reads as the season never having changed.
   */
  const chooseSeason = (which: number) => {
    setSeason(which);
    setWholeSeason(false);
    const next = nextIn(which);
    if (next) setPicked(next);
  };

  // Swap the S01E01 guess for the real next episode once Cinemeta arrives.
  useEffect(() => {
    if (title.kind !== 'tv' || !episodes.length) return;
    const next =
      episodes.find((e) => e.season > 0 && (progress?.[e.id] ?? 0) < WATCHED) ??
      episodes[0];
    setPicked((cur) => (cur?.name ? cur : next));
    if (!picked?.name) setSeason(next.season);
  }, [title.kind, title.episodes, progress]);

  // Releases are found by name, not by id: the index's own IMDb tagging is
  // patchy, and a title search returns the rips that were never tagged too.
  //
  // Do not wipe `sources` while flipping seasons — that relayouts the whole
  // page. Debounce so a swipe across S1–S4 only fetches the last one.
  useEffect(() => {
    const key = pickKey;
    const mine = ++searchGen.current;
    const ctrl = new AbortController();
    abort.current = ctrl;
    const delay = sourceKey == null ? 0 : 280;
    const timer = setTimeout(() => {
      setError(null);
      const t0 = Date.now();
      console.log('[releases] detail start', title.title, key);
      const wanted = picked
        ? searchEpisode(
            title.title,
            picked.season,
            picked.episode,
            ctrl.signal,
            title.imdb
          )
        : searchTorrents(title.title, ctrl.signal, {
            year: title.kind === 'movie' ? title.year : null,
            imdb: title.imdb,
          });
      wanted
        .then((hits) => {
          if (mine !== searchGen.current) {
            console.log('[releases] detail stale', title.title, Date.now() - t0, 'ms n=', hits.length);
            return;
          }
          console.log('[releases] detail done', title.title, Date.now() - t0, 'ms n=', hits.length);
          setSources(hits);
          setSourceKey(key);
        })
        .catch((e) => {
          if (mine !== searchGen.current) return;
          console.log('[releases] detail FAIL', title.title, Date.now() - t0, 'ms', e);
          setSources([]);
          setSourceKey(key);
          setError(String(e?.message ?? e));
        });
    }, delay);
    return () => {
      clearTimeout(timer);
      ctrl.abort();
    };
  }, [title.imdb, title.kind, pickKey]);

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
          <Icon name="arrow-left" size={20} color={colors.accent} />
        </Pressable>

        <View style={[styles.saveRow, { top: insets.top + space.md }]}>
          {(
            [
              { list: 'later', glyph: 'bookmark', label: 'Watch later' },
              { list: 'favorite', glyph: 'favorite', label: 'Favourite' },
            ] as const
          ).map(({ list, glyph, label }) => {
            const active = onList(saved, title.imdb, list);
            return (
              <Pressable
                key={list}
                onPress={() => onToggleSaved(title, list)}
                hitSlop={8}
                accessibilityLabel={active ? `Remove from ${label}` : label}
                style={({ pressed }) => [
                  styles.saveBtn,
                  active && styles.saveBtnOn,
                  pressed && styles.pressed,
                ]}
              >
                {/* Material rather than Feather: saved has to read at a glance,
                    and a solid heart does that where a red outline does not.
                    The outline is the same shape, so nothing moves on toggle. */}
                <MaterialIcons
                  name={active ? glyph : (`${glyph}-border` as const)}
                  size={20}
                  color={active ? SAVED_RED : colors.muted}
                />
              </Pressable>
            );
          })}
        </View>

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
        <View
          style={styles.actions}
          onLayout={(e) => (playY.current = e.nativeEvent.layout.y)}
        >
          <Pressable
            disabled={!best || wait}
            onPress={() => best && start(best.magnet)}
            style={({ pressed }) => [
              styles.play,
              (!best || wait) && styles.playOff,
              pressed && styles.pressed,
            ]}
          >
            <Icon name="play" size={17} color={colors.onAccent} />
            <Text style={styles.playText}>
              {wait
                ? 'Opening…'
                : !best
                  ? 'Finding releases…'
                  : canResume
                    ? `Continue${tag ? ` ${tag}` : ''} · ${Math.round(seen)}%`
                    : tag
                      ? `Play ${tag}`
                      : 'Play'}
            </Text>
          </Pressable>

          {/* Only worth offering once there is a resume point to ignore. */}
          {canResume && (
            <Pressable
              disabled={!best || wait}
              onPress={() => best && start(best.magnet, true)}
              accessibilityLabel="Start over"
              style={({ pressed }) => [
                styles.download,
                (!best || wait) && styles.downloadOff,
                pressed && styles.pressed,
              ]}
            >
              <Icon name="rotate-ccw" size={17} color={colors.text} />
            </Pressable>
          )}

          {/* Same release, kept rather than streamed — the Downloads tab takes
              over from here. */}
          <Pressable
            disabled={!best || wait}
            onPress={() => best && save(best.magnet)}
            accessibilityLabel="Download"
            style={({ pressed }) => [
              styles.download,
              (!best || wait) && styles.downloadOff,
              pressed && styles.pressed,
            ]}
          >
            {saving ? (
              <ActivityIndicator color={colors.text} />
            ) : (
              <Icon name="download" size={18} color={colors.text} />
            )}
          </Pressable>
        </View>

        {title.kind === 'tv' && season != null && (
          <Pressable
            disabled={!best || wait}
            onPress={saveSeason}
            style={({ pressed }) => [
              styles.seasonDl,
              (!best || wait) && styles.downloadOff,
              pressed && styles.pressed,
            ]}
          >
            {saving ? (
              <ActivityIndicator color={colors.text} />
            ) : (
              <Icon name="download" size={15} color={colors.text} />
            )}
            <Text style={styles.seasonDlText}>
              {saving
                ? 'Starting download…'
                : `Download season ${season === 0 ? 'specials' : season}`}
            </Text>
          </Pressable>
        )}

        {/* Directly under the button it governs, and only once there is a
            choice to make. One track rather than loose chips: these are four
            settings of the same dial, not four separate things to tap. */}
        {qualities.length > 1 && (
          <View style={styles.qualityBar}>
            {[null, ...qualities].map((q) => {
              const on = wanted === q;
              const count = q ? sources!.filter((h) => h.quality === q).length : sources!.length;
              return (
                <Pressable
                  key={q ?? 'best'}
                  onPress={() => setWanted(q)}
                  style={[styles.quality, on && styles.qualityOn]}
                >
                  <Text style={[styles.qualityText, on && styles.qualityTextOn]}>
                    {q ?? 'Best'}
                  </Text>
                  {/* What choosing it would leave you with — a 4K tab that
                      turns out to hold one dead release is worth knowing
                      before tapping it. */}
                  <Text style={[styles.qualityCount, on && styles.qualityCountOn]}>
                    {count}
                  </Text>
                </Pressable>
              );
            })}
          </View>
        )}

        {/* A film has no episode list to carry its progress, so it gets its own
            bar. Series show theirs against each episode below. */}
        {!picked && seen >= 1 && (
          <View style={styles.resume}>
            <ProgressBar value={seen} />
            {/* Minutes, not a percentage — the button already carries that. */}
            <Text style={styles.resumeText}>
              {resume
                ? `${clock(resume.position)} of ${clock(resume.duration)}` +
                  (seen >= WATCHED
                    ? ' · finished'
                    : ` · ${left(resume.duration - resume.position)}`)
                : ''}
            </Text>
          </View>
        )}

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
              <Text style={styles.seasonCount}>{inSeason.length} in season</Text>
            </View>

            {seasons.length > 1 && (
              <FlatList
                horizontal
                data={seasons}
                keyExtractor={(s) => String(s)}
                extraData={season}
                showsHorizontalScrollIndicator={false}
                contentContainerStyle={styles.seasonChips}
                renderItem={({ item: s }) => (
                  <Pressable
                    onPress={() => chooseSeason(s)}
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

            {listed.map((ep) => {
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
                      scroller.current?.scrollTo({ y: Math.max(0, playY.current - 12), animated: true });
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

            {inSeason.length > listed.length && (
              <Pressable
                onPress={() => setWholeSeason(true)}
                style={({ pressed }) => [styles.more, pressed && styles.pressed]}
              >
                <Text style={styles.moreText}>
                  Show all {inSeason.length} episodes
                </Text>
                <Icon name="chevron-down" size={16} color={colors.accent} />
              </Pressable>
            )}

            {wholeSeason && inSeason.length > EPISODE_PAGE && (
              <Pressable
                onPress={() => setWholeSeason(false)}
                style={({ pressed }) => [styles.more, pressed && styles.pressed]}
              >
                <Text style={styles.moreText}>Show less</Text>
                <Icon name="chevron-up" size={16} color={colors.accent} />
              </Pressable>
            )}
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
            {!fresh
              ? 'Finding releases…'
              : shown
                ? `${shown.length} release${shown.length === 1 ? '' : 's'}`
                : 'Finding releases…'}
          </Text>

          {(!sources || !fresh) && (
            <ActivityIndicator color={colors.accent} style={styles.spin} />
          )}

          {wait && (
            <View style={styles.opening}>
              <ActivityIndicator color={colors.accent} size="small" />
              <Text style={styles.openingText}>
                {saving ? 'Starting download…' : 'Reading torrent…'}
              </Text>
            </View>
          )}

          {!!(error ?? opening) && <Text style={styles.error}>{error ?? opening}</Text>}

          {sources?.length === 0 && fresh && !error && (
            <Text style={styles.none}>
              Nothing on the index for this title. Try the Search tab with a
              different spelling.
            </Text>
          )}

          {fresh && shown?.map((hit, i) => {
            const tone = HEALTH[health(hit.seeders)];
            return (
              <Pressable
                key={`${hit.id}:${i}`}
                onPress={() => start(hit.magnet)}
                style={({ pressed }) => [styles.source, pressed && styles.pressed]}
              >
                {/* The first one is what a play button would pick. */}
                <View style={[styles.mark, i === 0 && styles.markBest]}>
                  <Icon name="play" size={14} color={i === 0 ? colors.onAccent : colors.muted} />
                </View>
                <View style={styles.sourceText}>
                  {/* The bit that gets cut off — group, codec, audio — is the
                      bit you are choosing between, so it comes to you. */}
                  <Marquee style={styles.sourceName}>{hit.name}</Marquee>
                  <Text style={styles.sourceMeta}>
                    {bytes(torrentBytes(hit, episodes, season))}
                    {hit.quality ? ` · ${hit.quality}` : ''}
                    {picked && hit.scope !== 'episode'
                      ? ` · ${hit.scope === 'season' ? 'season pack' : 'complete series'}`
                      : ''}
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
