import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, Image, ScrollView, Text, View } from 'react-native';
import { Focusable } from '../components/Focusable';
import { Icon } from '../components/Icon';
import { TvButton } from '../components/Ui';
import { loadTitle, type Episode, type Title } from '../lib/meta';
import { onList, type List, type Saved } from '../lib/saved';
import {
  episodeTag,
  health,
  searchEpisode,
  searchTorrents,
  type Hit,
} from '../lib/search';
import { bytes } from '../lib/format';
import {
  GUTTER,
  SAFE,
  fadeRight,
  fadeToBg,
  font,
  radius,
  space,
  type,
  useColors,
  useThemedStyles,
} from '../theme';

/**
 * Highest first, which is the order people actually choose in.
 *
 * These are the exact strings `quality()` returns — it normalises 2160p/UHD/4K
 * down to "4K", so listing "2160p" here matched nothing and sorted the best
 * releases to the end of the row.
 */
const QUALITY_ORDER = ['4K', '1080p', '720p', 'CAM'];

/** Episodes revealed at a time. See `shownEpisodes`. */
const EPISODE_PAGE = 10;

const qualityRank = (q: string) => {
  const i = QUALITY_ORDER.indexOf(q);
  return i === -1 ? QUALITY_ORDER.length : i;
};

/** Swarm health as a word — a seeder count means nothing across a room. */
const HEALTH_LABEL: Record<ReturnType<typeof health>, string> = {
  fast: 'Fast',
  good: 'Good',
  weak: 'Weak',
  dead: 'No seeds',
};

/**
 * What a release actually contains.
 *
 * An episode search deliberately returns the packs alongside the loose
 * episodes, because a pack usually has far more seeders and streams just as
 * well — only the one file inside it is ever fetched. But the rows all looked
 * alike, so a 60GB season was indistinguishable from a 2GB episode except by
 * reading the release name. Saying it outright is the difference between
 * picking a pack on purpose and picking one by accident.
 */
const SCOPE_LABEL: Record<Hit['scope'], string> = {
  episode: 'Episode',
  season: 'Season pack',
  complete: 'Complete series',
};

function Source({
  hit,
  onPress,
  first,
}: {
  hit: Hit;
  onPress: () => void;
  first?: boolean;
}) {
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    row: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.lg,
      paddingHorizontal: space.lg,
      paddingVertical: space.md,
      borderRadius: radius.md,
      backgroundColor: c.surface,
      marginBottom: space.sm,
    },
    name: { ...type.label, color: c.text },
    meta: { ...type.meta, color: c.faint, marginTop: 2 },
    tag: {
      ...type.meta,
      fontFamily: font.semibold,
      paddingHorizontal: space.md,
      paddingVertical: 4,
      borderRadius: radius.sm,
      overflow: 'hidden' as const,
    },
  }));

  const grade = health(hit.seeders);
  const tint =
    grade === 'fast' || grade === 'good'
      ? colors.good
      : grade === 'weak'
        ? colors.warn
        : colors.danger;

  return (
    <Focusable invert first={first} onPress={onPress} scale={1.01} style={styles.row}>
      {(focused) => (
        <>
          <View style={{ flex: 1 }}>
            <Text
              style={[styles.name, focused && { color: colors.bg }]}
              numberOfLines={1}
            >
              {hit.name}
            </Text>
            <Text style={[styles.meta, focused && { color: colors.bg }]}>
              {[
                SCOPE_LABEL[hit.scope],
                hit.quality,
                // For a pack this is the whole torrent, not the episode — which
                // is the number that decides whether it fits on the box.
                bytes(hit.size),
                `${hit.seeders} seeds`,
              ]
                .filter(Boolean)
                .join('   ·   ')}
            </Text>
          </View>
          <Text style={[styles.tag, { color: tint, backgroundColor: colors.control }]}>
            {HEALTH_LABEL[grade]}
          </Text>
        </>
      )}
    </Focusable>
  );
}

export function DetailScreen({
  title: initial,
  saved,
  onSave,
  onStream,
}: {
  title: Title;
  saved: Saved[];
  onSave: (title: Title, list: List) => void;
  /**
   * Start a stream. `tag` is the S01E02 to pick inside a season pack; without
   * one the engine takes the largest video, which is the film.
   */
  onStream: (hit: Hit, title: Title, tag?: string, episode?: Episode) => void;
}) {
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    screen: { flex: 1, backgroundColor: c.bg },
    art: { position: 'absolute' as const, top: 0, left: 0, right: 0, height: 340 },
    veilY: {
      position: 'absolute' as const,
      top: 0,
      left: 0,
      right: 0,
      height: 340,
      experimental_backgroundImage: fadeToBg(c.bg, 0.2),
    },
    veilX: {
      position: 'absolute' as const,
      top: 0,
      left: 0,
      right: 0,
      height: 340,
      experimental_backgroundImage: fadeRight(c.bg),
    },
    body: { paddingLeft: GUTTER, paddingRight: SAFE.x, paddingTop: SAFE.y, paddingBottom: SAFE.y * 3 },
    name: { ...type.hero, color: c.text, maxWidth: 660 },
    facts: { ...type.label, color: c.muted, marginTop: space.xs },
    plot: { ...type.body, color: c.muted, marginTop: space.sm, maxWidth: 620 },
    people: { ...type.meta, color: c.faint, marginTop: space.xs, maxWidth: 620 },
    buttons: { flexDirection: 'row' as const, gap: space.md, marginTop: space.xl },
    section: {
      ...type.title,
      fontFamily: font.semibold,
      color: c.text,
      marginTop: space.xxl,
      marginBottom: space.md,
    },
    chips: { flexDirection: 'row' as const, flexWrap: 'wrap' as const, gap: space.sm },
    chip: {
      paddingHorizontal: space.md,
      height: 40,
      justifyContent: 'center' as const,
      borderRadius: radius.pill,
      backgroundColor: c.control,
    },
    chipOn: { backgroundColor: c.accent },
    chipText: { ...type.label, color: c.muted },
    episode: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.lg,
      paddingHorizontal: space.lg,
      paddingVertical: space.md,
      borderRadius: radius.md,
      backgroundColor: c.surface,
      marginBottom: space.sm,
    },
    epName: { ...type.label, color: c.text },
    epMeta: { ...type.meta, color: c.faint, marginTop: 2 },
    note: { ...type.body, color: c.faint, marginTop: space.md },
  }));

  // The catalogue row carries art and a rating but no plot, cast or episodes.
  const [title, setTitle] = useState(initial);
  useEffect(() => {
    setTitle(initial);
    let live = true;
    loadTitle(initial.imdb, initial.kind).then((full) => {
      if (live && full) setTitle(full);
    });
    return () => {
      live = false;
    };
  }, [initial]);

  const seasons = useMemo(() => {
    const all = [...new Set(title.episodes?.map((e) => e.season) ?? [])]
      .filter((s) => s > 0)
      .sort((a, b) => a - b);
    return all;
  }, [title.episodes]);

  const [season, setSeason] = useState<number | null>(null);
  // Only when the current choice is not in the list. `title` is replaced the
  // moment loadTitle answers, which rebuilds `seasons` — and resetting on that
  // threw the viewer back to season one just as they had picked season four.
  useEffect(() => {
    setSeason((prev) => (prev != null && seasons.includes(prev) ? prev : seasons[0] ?? null));
  }, [seasons]);

  const [episode, setEpisode] = useState<Episode | null>(null);
  const [hits, setHits] = useState<Hit[] | null>(null);
  const [busy, setBusy] = useState(false);
  /** null means every quality — the ranked list as the indexes returned it. */
  const [quality, setQuality] = useState<string | null>(null);

  // Only the qualities actually on offer. A 720p chip that filters to nothing
  // is worse than no chip.
  const qualities = useMemo(() => {
    const seen = new Set(
      (hits ?? []).map((h) => h.quality).filter((q): q is string => !!q)
    );
    return [...seen].sort((a, b) => qualityRank(a) - qualityRank(b));
  }, [hits]);

  const shown = useMemo(
    () => (quality ? (hits ?? []).filter((h) => h.quality === quality) : hits ?? []),
    [hits, quality]
  );

  const episodes = useMemo(
    () => (title.episodes ?? []).filter((e) => e.season === season),
    [title.episodes, season]
  );

  /**
   * How many episodes are on screen.
   *
   * A season of twenty-four is twenty-four rows to arrow past to reach the
   * sources below, and rendering all of them costs a set-top box real time.
   * Ten at a time, with a row at the end to ask for ten more.
   */
  const [shownEpisodes, setShownEpisodes] = useState(EPISODE_PAGE);
  useEffect(() => setShownEpisodes(EPISODE_PAGE), [season, title.imdb]);

  /**
   * Which request the visible sources belong to.
   *
   * Arrowing down a season fires one of these per episode and they do not
   * return in order, so a slower answer for episode 2 could replace episode 5's
   * — and because the first row claims focus when it mounts, the remote was
   * dragged down into a list belonging to an episode nobody had selected.
   */
  const wanted = useRef(0);

  /** Releases for a film, or for one episode of a series. */
  const findSources = useCallback(
    async (ep?: Episode) => {
      const ticket = ++wanted.current;
      setBusy(true);
      setHits(null);
      setQuality(null);
      setEpisode(ep ?? null);
      try {
        const found = ep
          ? await searchEpisode(title.title, ep.season, ep.episode, undefined, title.imdb)
          : await searchTorrents(title.title, undefined, {
              imdb: title.imdb,
              year: title.year,
            });
        if (ticket === wanted.current) setHits(found);
        return ticket === wanted.current ? found : null;
      } catch {
        if (ticket === wanted.current) setHits([]);
        return null;
      } finally {
        if (ticket === wanted.current) setBusy(false);
      }
    },
    [title]
  );

  /**
   * Pressing an episode plays it.
   *
   * The list below is still filled in, so a different release is one press away
   * — but landing on a list of thirty near-identical names and having to choose
   * one is not what pressing an episode means. The top row is what the ranking
   * already decided was best, so take it.
   */
  const playEpisode = useCallback(
    async (ep: Episode) => {
      const found = await findSources(ep);
      if (found?.length) {
        onStream(found[0], title, episodeTag(ep.season, ep.episode), ep);
      }
    },
    [findSources, onStream, title]
  );

  // A film has exactly one thing to look for, so look for it on arrival rather
  // than making someone press a button that could not have meant anything else.
  //
  // Keyed on the id already searched, because `title` is replaced the moment
  // loadTitle answers — without this the effect re-runs on that swap and every
  // film is searched for twice.
  const searched = useRef<string | null>(null);
  useEffect(() => {
    if (title.kind !== 'movie') {
      setHits(null);
      setEpisode(null);
      return;
    }
    if (searched.current === title.imdb) return;
    searched.current = title.imdb;
    findSources();
  }, [title.imdb, title.kind, findSources]);

  const facts = [
    title.year,
    title.rating ? `${title.rating} / 10` : null,
    title.runtime,
    title.genres?.slice(0, 3).join(' · ') || null,
  ]
    .filter(Boolean)
    .join('   ·   ');

  const inList = onList(saved, title.imdb, 'later');
  const art = title.backdrop ?? title.poster;

  return (
    <View style={styles.screen}>
      {!!art && <Image source={{ uri: art }} style={styles.art} resizeMode="cover" />}
      <View style={styles.veilY} />
      <View style={styles.veilX} />
      <ScrollView contentContainerStyle={styles.body}>
        <Text style={styles.name} numberOfLines={2}>
          {title.title}
        </Text>
        {!!facts && <Text style={styles.facts}>{facts}</Text>}
        {!!title.plot && <Text style={styles.plot}>{title.plot}</Text>}
        {!!title.cast?.length && (
          <Text style={styles.people} numberOfLines={2}>
            {title.cast.slice(0, 5).join(', ')}
          </Text>
        )}

        <View style={styles.buttons}>
          {title.kind === 'movie' && (
            // Never disabled, even while the search is still out. A disabled
            // view is not a focus candidate, so `first` would be ignored and the
            // remote would start on "Add to list" — then stay there, because
            // nothing moves focus back once the sources land.
            <TvButton
              first
              icon="play"
              variant="solid"
              label={busy ? 'Finding sources' : shown.length ? 'Play' : 'No sources'}
              onPress={() => {
                if (shown.length) onStream(shown[0], title);
              }}
            />
          )}
          <TvButton
            first={title.kind === 'tv'}
            icon={inList ? 'check' : 'plus'}
            label={inList ? 'In your list' : 'Add to list'}
            onPress={() => onSave(title, 'later')}
          />
        </View>

        {title.kind === 'tv' && !!seasons.length && (
          <>
            <Text style={styles.section}>Seasons</Text>
            <View style={styles.chips}>
              {seasons.map((s) => {
                const on = s === season;
                return (
                  <Focusable
                    invert
                    key={s}
                    onPress={() => {
                      setSeason(s);
                      setHits(null);
                      setEpisode(null);
                    }}
                    scale={1.04}
                    style={[styles.chip, on && styles.chipOn]}
                  >
                    {(focused) => (
                      <Text
                        style={[
                          styles.chipText,
                          on && { color: colors.onAccent, fontFamily: font.semibold },
                          focused && { color: colors.bg },
                        ]}
                      >
                        Season {s}
                      </Text>
                    )}
                  </Focusable>
                );
              })}
            </View>

            <Text style={styles.section}>
              Episodes
              {episodes.length > EPISODE_PAGE
                ? `  ·  ${Math.min(shownEpisodes, episodes.length)} of ${episodes.length}`
                : ''}
            </Text>
            {episodes.slice(0, shownEpisodes).map((ep) => (
              <Focusable
                invert
                key={ep.id}
                onPress={() => playEpisode(ep)}
                scale={1.01}
                style={styles.episode}
              >
                {(focused) => (
                  <>
                    <View style={{ flex: 1 }}>
                      <Text
                        style={[styles.epName, focused && { color: colors.bg }]}
                        numberOfLines={1}
                      >
                        {ep.episode}. {ep.name}
                      </Text>
                      {!!ep.overview && (
                        <Text
                          style={[styles.epMeta, focused && { color: colors.bg }]}
                          numberOfLines={2}
                        >
                          {ep.overview}
                        </Text>
                      )}
                    </View>
                    <Icon
                      name="chevron-right"
                      size={22}
                      color={focused ? colors.bg : colors.faint}
                    />
                  </>
                )}
              </Focusable>
            ))}
            {shownEpisodes < episodes.length && (
              <Focusable
                invert
                onPress={() => setShownEpisodes((n) => n + EPISODE_PAGE)}
                scale={1.01}
                style={styles.episode}
              >
                {(focused) => (
                  <Text style={[styles.epName, focused && { color: colors.bg }]}>
                    Show {Math.min(EPISODE_PAGE, episodes.length - shownEpisodes)} more
                  </Text>
                )}
              </Focusable>
            )}
          </>
        )}

        <Text style={styles.section}>
          {episode ? `Sources for ${episodeTag(episode.season, episode.episode)}` : 'Sources'}
        </Text>
        {!busy && qualities.length > 1 && (
          <View style={[styles.chips, { marginBottom: space.md }]}>
            {[null, ...qualities].map((q) => {
              const on = q === quality;
              return (
                <Focusable
                  invert
                  key={q ?? 'all'}
                  onPress={() => setQuality(q)}
                  scale={1.04}
                  style={[styles.chip, on && styles.chipOn]}
                >
                  {(focused) => (
                    <Text
                      style={[
                        styles.chipText,
                        on && { color: colors.onAccent, fontFamily: font.semibold },
                        focused && { color: colors.bg },
                      ]}
                    >
                      {q ?? 'All'}
                    </Text>
                  )}
                </Focusable>
              );
            })}
          </View>
        )}
        {busy && <ActivityIndicator color={colors.accent} size="large" />}
        {!busy && hits?.length === 0 && (
          <Text style={styles.note}>
            Nothing found. Try another episode, or search for the release by name.
          </Text>
        )}
        {!busy && !hits && title.kind === 'tv' && (
          <Text style={styles.note}>Choose an episode to see what is available.</Text>
        )}
        {shown.slice(0, 12).map((hit, i) => (
          <Source
            key={hit.id}
            hit={hit}
            first={title.kind === 'tv' && i === 0 && !!episode}
            onPress={() =>
              onStream(
                hit,
                title,
                episode ? episodeTag(episode.season, episode.episode) : undefined,
                episode ?? undefined
              )
            }
          />
        ))}
      </ScrollView>
    </View>
  );
}
