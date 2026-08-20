import { useEffect, useMemo, useState } from 'react';
import { Image, ScrollView, Text, View } from 'react-native';
import { Rail } from '../components/Ui';
import { Focusable } from '../components/Focusable';
import { ProgressBar } from '../components/ProgressBar';
import {
  ANIME_GENRE,
  kindOfGenre,
  loadCatalog,
  peekCatalog,
  watchedGenres,
  type Title,
} from '../lib/meta';
import { posterOf, type Item } from '../lib/history';
import { asTitle, type Saved } from '../lib/saved';
import { useOnline } from '../lib/net';
import { clock } from '../lib/format';
import { parseEpisode } from '../lib/search';
import { font, radius, SAFE, space, type, useColors, useThemedStyles } from '../theme';

type Shelf = { title: string; kind: 'movie' | 'tv'; genre?: string };

const SHELVES: Shelf[] = [
  { title: 'Trending films', kind: 'movie' },
  { title: 'Popular series', kind: 'tv' },
  { title: 'Anime', kind: 'tv', genre: ANIME_GENRE },
];

const shelfKey = (s: Shelf) => `${s.kind}:${s.genre ?? ''}`;

const isInProgress = (item: Item) => {
  const index = item.lastFileIndex;
  if (index == null) return false;
  const at = item.positions?.[index];
  const of = item.durations?.[index];
  return !!at && !!of && at / of > 0.02 && at / of < 0.95;
};

/** "S2 E4 · 18:20" under a continue-watching card. */
function resumeStamp(item: Item): string | null {
  const index = item.lastFileIndex;
  if (index == null) return null;
  const file = item.files?.find((f) => f.index === index);
  const at = parseEpisode(file?.name ?? item.title);
  const ep = at ? (at.season === 0 ? `Sp ${at.episode}` : `S${at.season} E${at.episode}`) : null;
  const position = item.positions?.[index];
  const bits = [ep, position ? clock(position) : null].filter(Boolean);
  return bits.length ? bits.join('  ·  ') : null;
}

/**
 * The hero.
 *
 * Follows whatever the D-pad is on, which is the only way a ten-foot page can
 * show plot and rating at all — there is no room for prose beside every poster
 * and no pointer to hover one with.
 */
function Hero({ title }: { title: Title | null }) {
  const styles = useThemedStyles((c) => ({
    wrap: { height: 420, justifyContent: 'flex-end' as const, marginBottom: space.lg },
    art: { position: 'absolute' as const, top: 0, left: 0, right: 0, bottom: 0 },
    veil: {
      position: 'absolute' as const,
      top: 0,
      left: 0,
      right: 0,
      bottom: 0,
      backgroundColor: c.bg,
      opacity: 0.72,
    },
    body: { paddingHorizontal: SAFE.x, paddingBottom: space.lg, maxWidth: 1000 },
    name: { ...type.hero, color: c.text },
    facts: { ...type.label, color: c.muted, marginTop: space.sm },
    plot: { ...type.body, color: c.muted, marginTop: space.md },
  }));

  if (!title) return <View style={{ height: 120 }} />;

  const facts = [
    title.year,
    title.rating ? `${title.rating} / 10` : null,
    title.runtime,
    title.genres?.slice(0, 3).join(' · ') || null,
  ]
    .filter(Boolean)
    .join('   ·   ');

  return (
    <View style={styles.wrap}>
      {!!(title.backdrop || title.poster) && (
        <Image
          source={{ uri: title.backdrop ?? title.poster! }}
          style={styles.art}
          resizeMode="cover"
        />
      )}
      <View style={styles.veil} />
      <View style={styles.body}>
        <Text style={styles.name} numberOfLines={2}>
          {title.title}
        </Text>
        {!!facts && <Text style={styles.facts}>{facts}</Text>}
        {!!title.plot && (
          <Text style={styles.plot} numberOfLines={3}>
            {title.plot}
          </Text>
        )}
      </View>
    </View>
  );
}

/** Continue watching. Wide cards, not posters — the stamp has to be readable. */
function ResumeRail({
  items,
  onPick,
  first,
}: {
  items: Item[];
  onPick: (item: Item) => void;
  first?: boolean;
}) {
  const styles = useThemedStyles((c) => ({
    wrap: { marginBottom: space.xl, paddingHorizontal: SAFE.x },
    heading: {
      ...type.title,
      fontFamily: font.semibold,
      color: c.text,
      marginBottom: space.md,
    },
    row: { flexDirection: 'row' as const, gap: space.md },
    card: {
      width: 330,
      height: 186,
      borderRadius: radius.md,
      overflow: 'hidden' as const,
      backgroundColor: c.surface,
      justifyContent: 'flex-end' as const,
    },
    art: { position: 'absolute' as const, top: 0, left: 0, right: 0, bottom: 0 },
    strip: { padding: space.md, backgroundColor: c.scrim },
    name: { ...type.label, color: '#ffffff' },
    stamp: { ...type.meta, color: '#cbd2e0', marginTop: 2 },
  }));

  if (!items.length) return null;

  return (
    <View style={styles.wrap}>
      <Text style={styles.heading}>Continue watching</Text>
      <View style={styles.row}>
        {items.map((item, i) => {
          const index = item.lastFileIndex;
          const at = index != null ? item.positions?.[index] : undefined;
          const of = index != null ? item.durations?.[index] : undefined;
          const art = posterOf(item);
          const stamp = resumeStamp(item);
          return (
            <Focusable
              key={item.uri}
              first={first && i === 0}
              onPress={() => onPick(item)}
              style={styles.card}
            >
              {!!art && <Image source={{ uri: art }} style={styles.art} resizeMode="cover" />}
              <View style={styles.strip}>
                <Text style={styles.name} numberOfLines={1}>
                  {item.titleName || item.title}
                </Text>
                {!!stamp && <Text style={styles.stamp}>{stamp}</Text>}
                {!!at && !!of && (
                  <ProgressBar
                    value={(at / of) * 100}
                    height={4}
                    style={{ marginTop: space.sm }}
                  />
                )}
              </View>
            </Focusable>
          );
        })}
      </View>
    </View>
  );
}

export function HomeScreen({
  history,
  saved,
  genres,
  onPickTitle,
  onResume,
}: {
  history: Item[];
  saved: Saved[];
  /** Genres chosen in Settings. A statement, so they lead the page. */
  genres: string[];
  onPickTitle: (title: Title) => void;
  onResume: (item: Item) => void;
}) {
  const colors = useColors();
  const online = useOnline();
  // Seeded from whatever resolved earlier this session, so coming back from a
  // film paints the rails on the first frame instead of flashing empty.
  const [loaded, setLoaded] = useState<Record<string, Title[]>>(() =>
    Object.fromEntries(
      SHELVES.map((s) => [shelfKey(s), peekCatalog(s.kind, s.genre)]).filter(
        ([, titles]) => titles
      ) as [string, Title[]][]
    )
  );
  const [hero, setHero] = useState<Title | null>(null);

  const resumable = useMemo(() => history.filter(isInProgress).slice(0, 6), [history]);

  // Shelves inferred from what has been watched. Nothing until there is enough
  // history to mean something — the popularity charts are the honest answer
  // before that.
  const [picks, setPicks] = useState<Shelf[]>([]);
  useEffect(() => {
    const seen = [
      ...history
        .filter((h): h is Item & { imdb: string } => !!h.imdb)
        .map((h) => ({ imdb: h.imdb, kind: h.kind ?? ('movie' as const) })),
      ...saved.map((s) => ({ imdb: s.imdb, kind: s.kind })),
    ].slice(0, 30);
    if (!seen.length) {
      setPicks([]);
      return;
    }
    let live = true;
    watchedGenres(seen, 3).then((found) => {
      if (!live) return;
      setPicks(
        found.map(({ genre, kind }, i) => ({
          title: i === 0 ? 'Recommended for you' : `Because you like ${genre}`,
          kind,
          genre,
        }))
      );
    });
    return () => {
      live = false;
    };
  }, [history, saved, online]);

  const chosen = useMemo(
    () =>
      genres.map((genre) => ({
        title: genre === ANIME_GENRE ? 'Anime' : genre,
        kind: kindOfGenre(genre),
        genre,
      })),
    [genres]
  );

  // Chosen first, then recommendations, then the fixed charts — each minus what
  // the ones above already cover, so no catalogue appears twice.
  const shelves = useMemo(() => {
    const taken = new Set(chosen.map(shelfKey));
    const rest = picks.filter((p) => !taken.has(shelfKey(p)));
    for (const p of rest) taken.add(shelfKey(p));
    return [...chosen, ...rest, ...SHELVES.filter((s) => !taken.has(shelfKey(s)))];
  }, [chosen, picks]);

  useEffect(() => {
    let live = true;
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

  const watchlist = useMemo(
    () => saved.filter((s) => s.list === 'later').map(asTitle),
    [saved]
  );

  return (
    <ScrollView
      style={{ flex: 1, backgroundColor: colors.bg }}
      contentContainerStyle={{ paddingBottom: SAFE.y * 3 }}
    >
      <Hero title={hero} />
      <ResumeRail items={resumable} onPick={onResume} first={resumable.length > 0} />
      <View style={{ paddingHorizontal: SAFE.x }}>
        {!!watchlist.length && (
          <Rail
            heading="Your list"
            titles={watchlist}
            onPick={onPickTitle}
            onFocusItem={setHero}
            firstItem={!resumable.length}
          />
        )}
        {shelves.map((shelf, i) => (
          <Rail
            key={shelfKey(shelf)}
            heading={shelf.title}
            titles={loaded[shelfKey(shelf)] ?? []}
            onPick={onPickTitle}
            onFocusItem={setHero}
            firstItem={!resumable.length && !watchlist.length && i === 0}
          />
        ))}
      </View>
    </ScrollView>
  );
}
