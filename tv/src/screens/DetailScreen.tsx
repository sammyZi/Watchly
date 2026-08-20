import { useCallback, useEffect, useMemo, useState } from 'react';
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
import { font, radius, SAFE, space, type, useColors, useThemedStyles } from '../theme';

/** Swarm health as a word — a seeder count means nothing across a room. */
const HEALTH_LABEL: Record<ReturnType<typeof health>, string> = {
  fast: 'Fast',
  good: 'Good',
  weak: 'Weak',
  dead: 'No seeds',
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
    <Focusable first={first} onPress={onPress} scale={1.01} style={styles.row}>
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
              {[hit.quality, bytes(hit.size), `${hit.seeders} seeds`]
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
    art: { position: 'absolute' as const, top: 0, left: 0, right: 0, height: 560 },
    veil: {
      position: 'absolute' as const,
      top: 0,
      left: 0,
      right: 0,
      height: 560,
      backgroundColor: c.bg,
      opacity: 0.76,
    },
    body: { paddingHorizontal: SAFE.x, paddingTop: SAFE.y * 2, paddingBottom: SAFE.y * 3 },
    name: { ...type.hero, color: c.text, maxWidth: 1100 },
    facts: { ...type.label, color: c.muted, marginTop: space.sm },
    plot: { ...type.body, color: c.muted, marginTop: space.md, maxWidth: 900 },
    people: { ...type.meta, color: c.faint, marginTop: space.sm, maxWidth: 900 },
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
      paddingHorizontal: space.lg,
      height: 52,
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
  useEffect(() => setSeason(seasons[0] ?? null), [seasons]);

  const [episode, setEpisode] = useState<Episode | null>(null);
  const [hits, setHits] = useState<Hit[] | null>(null);
  const [busy, setBusy] = useState(false);

  const episodes = useMemo(
    () => (title.episodes ?? []).filter((e) => e.season === season),
    [title.episodes, season]
  );

  /** Releases for a film, or for one episode of a series. */
  const findSources = useCallback(
    async (ep?: Episode) => {
      setBusy(true);
      setHits(null);
      setEpisode(ep ?? null);
      try {
        const found = ep
          ? await searchEpisode(title.title, ep.season, ep.episode, undefined, title.imdb)
          : await searchTorrents(title.title, undefined, {
              imdb: title.imdb,
              year: title.year,
            });
        setHits(found);
      } catch {
        setHits([]);
      } finally {
        setBusy(false);
      }
    },
    [title]
  );

  // A film has exactly one thing to look for, so look for it on arrival rather
  // than making someone press a button that could not have meant anything else.
  useEffect(() => {
    if (title.kind === 'movie') findSources();
    else {
      setHits(null);
      setEpisode(null);
    }
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
      <View style={styles.veil} />
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
            <TvButton
              first
              icon="play"
              variant="solid"
              label={hits?.length ? 'Play' : busy ? 'Finding…' : 'Play'}
              disabled={!hits?.length}
              onPress={() => hits?.[0] && onStream(hits[0], title)}
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

            <Text style={styles.section}>Episodes</Text>
            {episodes.map((ep) => (
              <Focusable
                key={ep.id}
                onPress={() => findSources(ep)}
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
          </>
        )}

        <Text style={styles.section}>
          {episode ? `Sources for ${episodeTag(episode.season, episode.episode)}` : 'Sources'}
        </Text>
        {busy && <ActivityIndicator color={colors.accent} size="large" />}
        {!busy && hits?.length === 0 && (
          <Text style={styles.note}>
            Nothing found. Try another episode, or search for the release by name.
          </Text>
        )}
        {!busy && !hits && title.kind === 'tv' && (
          <Text style={styles.note}>Choose an episode to see what is available.</Text>
        )}
        {hits?.slice(0, 12).map((hit, i) => (
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
