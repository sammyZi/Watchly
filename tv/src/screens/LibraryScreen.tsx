import { useMemo, useState } from 'react';
import { Image, ScrollView, Text, View } from 'react-native';
import { Focusable } from '../components/Focusable';
import { Icon } from '../components/Icon';
import { ProgressBar } from '../components/ProgressBar';
import { TvButton } from '../components/Ui';
import { posterOf, type Item } from '../lib/history';
import { clock, ago } from '../lib/format';
import { parseEpisode } from '../lib/search';
import { GUTTER, SAFE, font, radius, space, type, useColors, useThemedStyles } from '../theme';

const CARD_W = 236;
const CARD_H = 133;

const ratio = (item: Item) => {
  const index = item.lastFileIndex;
  if (index == null) return 0;
  const at = item.positions?.[index];
  const of = item.durations?.[index];
  return at && of ? at / of : 0;
};

/** "S2 E4 · 18:20 · 3d ago" */
function stamp(item: Item): string {
  const index = item.lastFileIndex;
  const file = index != null ? item.files?.find((f) => f.index === index) : undefined;
  const at = parseEpisode(file?.name ?? item.title);
  const ep = at ? (at.season === 0 ? `Sp ${at.episode}` : `S${at.season} E${at.episode}`) : null;
  const position = index != null ? item.positions?.[index] : undefined;
  return [ep, position ? clock(position) : null, ago(item.at)].filter(Boolean).join('  ·  ');
}

/**
 * Everything opened before, newest first.
 *
 * A television has no downloads to manage, so this is the only place a magnet
 * that was watched once is reachable again — the home page only surfaces what
 * is part-watched, and a finished film drops off it entirely.
 */
export function LibraryScreen({
  history,
  onResume,
  onRemove,
  onClear,
}: {
  history: Item[];
  onResume: (item: Item) => void;
  onRemove: (item: Item) => void;
  onClear: () => void;
}) {
  const colors = useColors();
  const [selected, setSelected] = useState<string | null>(null);
  const styles = useThemedStyles((c) => ({
    screen: { flex: 1, backgroundColor: c.bg },
    body: {
      paddingLeft: GUTTER,
      paddingRight: SAFE.x,
      paddingTop: SAFE.y,
      paddingBottom: SAFE.y * 3,
    },
    head: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      marginBottom: space.lg,
    },
    heading: { ...type.display, color: c.text },
    grid: { flexDirection: 'row' as const, flexWrap: 'wrap' as const, gap: space.md },
    cell: { width: CARD_W },
    card: {
      width: CARD_W,
      height: CARD_H,
      borderRadius: radius.md,
      overflow: 'hidden' as const,
      backgroundColor: c.surface,
      justifyContent: 'flex-end' as const,
    },
    art: {
      position: 'absolute' as const,
      top: 0,
      left: 0,
      right: 0,
      bottom: 0,
      // Rounded here as well as on the parent. `overflow: hidden` is a clip on
      // the container, and Android drops that clip once an ancestor is
      // transformed — so the focused card, which is the one being scaled, was
      // the only one whose artwork spilled out to square corners.
      borderRadius: radius.md,
    },
    strip: {
      paddingHorizontal: space.sm,
      paddingBottom: space.sm,
      paddingTop: space.xl,
      // Faded rather than a filled block: a hard scrim edge cuts straight
      // across the artwork and reads as a rendering fault.
      experimental_backgroundImage:
        'linear-gradient(to top, rgba(0,0,0,0.92) 0%, rgba(0,0,0,0.68) 55%, rgba(0,0,0,0) 100%)',
    },
    name: { ...type.meta, fontFamily: font.semibold, color: '#ffffff' },
    stamp: { ...type.meta, fontSize: 11, color: '#cbd2e0', marginTop: 1 },
    remove: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      gap: space.xs,
      height: 32,
      marginTop: space.xs,
      borderRadius: radius.pill,
      backgroundColor: c.control,
    },
    removeText: { ...type.meta, color: c.faint },
    empty: { ...type.body, color: c.faint, marginTop: space.xl, maxWidth: 560 },
  }));

  const items = useMemo(() => [...history].sort((a, b) => b.at - a.at), [history]);

  return (
    <View style={styles.screen}>
      <ScrollView contentContainerStyle={styles.body}>
        <View style={styles.head}>
          <Text style={styles.heading}>Library</Text>
          <View style={{ flex: 1 }} />
          {!!items.length && (
            <TvButton icon="trash-2" label="Clear all" onPress={onClear} />
          )}
        </View>

        {!items.length && (
          <Text style={styles.empty}>
            Nothing here yet. Anything you play turns up in this list, with the
            point you stopped at.
          </Text>
        )}

        <View style={styles.grid}>
          {items.map((item, i) => {
            const art = posterOf(item);
            const progress = ratio(item);
            const open = selected === item.uri;
            return (
              <View key={item.uri} style={styles.cell}>
                <Focusable
                  first={i === 0}
                  onPress={() => onResume(item)}
                  onFocus={() => setSelected(item.uri)}
                  style={styles.card}
                >
                  {!!art && (
                    <Image source={{ uri: art }} style={styles.art} resizeMode="cover" />
                  )}
                  <View style={styles.strip}>
                    <Text style={styles.name} numberOfLines={1}>
                      {item.titleName || item.title}
                    </Text>
                    <Text style={styles.stamp} numberOfLines={1}>
                      {stamp(item)}
                    </Text>
                    {progress > 0 && (
                      <ProgressBar
                        value={progress * 100}
                        height={3}
                        style={{ marginTop: space.xs }}
                      />
                    )}
                  </View>
                </Focusable>
                {/* Only under the card the D-pad is on: forty remove buttons in
                    a grid is forty things to arrow past to reach the next row. */}
                {open && (
                  <Focusable invert onPress={() => onRemove(item)} scale={1.02} style={styles.remove}>
                    {(focused) => (
                      <>
                        <Icon
                          name="trash-2"
                          size={13}
                          color={focused ? colors.bg : colors.faint}
                        />
                        <Text
                          style={[styles.removeText, focused && { color: colors.bg }]}
                        >
                          Remove
                        </Text>
                      </>
                    )}
                  </Focusable>
                )}
              </View>
            );
          })}
        </View>
      </ScrollView>
    </View>
  );
}
