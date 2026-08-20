import { memo } from 'react';
import { FlatList, Image, Text, View, type StyleProp, type ViewStyle } from 'react-native';
import { Focusable } from './Focusable';
import { Icon, type IconName } from './Icon';
import { font, radius, space, type, useColors, useThemedStyles } from '../theme';
import type { Title } from '../lib/meta';
import { hueOf } from '../lib/title';
import { hues } from '../theme';

/**
 * Poster aspect, and the one place the rail geometry is decided.
 *
 * A television reports a 960dp-wide canvas, so anything over about 140 here
 * gives a rail of three or four — and a rail you cannot see the end of is what
 * tells the viewer to keep arrowing right.
 */
export const POSTER_W = 128;
export const POSTER_H = 192;

/** 16:9 card for the recommendation rail — see `wide` on Rail. */
export const WIDE_W = 236;
export const WIDE_H = 133;

export function TvButton({
  label,
  onPress,
  icon,
  variant = 'ghost',
  first,
  disabled,
  style,
}: {
  label: string;
  onPress: () => void;
  icon?: IconName;
  variant?: 'solid' | 'ghost' | 'danger';
  first?: boolean;
  disabled?: boolean;
  style?: StyleProp<ViewStyle>;
}) {
  const colors = useColors();
  const styles = useThemedStyles((c) => ({
    base: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      gap: space.sm,
      borderRadius: radius.pill,
      paddingHorizontal: space.lg,
      height: 46,
    },
    ghost: { backgroundColor: c.control },
    solid: { backgroundColor: c.accent },
    danger: { backgroundColor: c.danger },
    label: { ...type.label },
  }));

  const filled = variant !== 'ghost';

  return (
    <Focusable
      invert
      onPress={onPress}
      first={first}
      disabled={disabled}
      scale={1.04}
      style={[styles.base, styles[variant], style]}
    >
      {(focused) => {
        // Focus inverts the button rather than only ringing it — at three
        // metres a 3px ring on its own is not enough of a signal.
        const fg = focused ? colors.bg : filled ? colors.onAccent : colors.muted;
        return (
          <>
            {!!icon && <Icon name={icon} size={17} color={fg} />}
            <Text
              style={[
                styles.label,
                { color: fg },
                filled && { fontFamily: font.semibold },
              ]}
            >
              {label}
            </Text>
          </>
        );
      }}
    </Focusable>
  );
}

/**
 * One poster in a rail.
 *
 * Cinemeta does not always have art, and a rail with holes in it is much worse
 * to navigate than one with lettered placeholders — the eye needs something to
 * count along.
 */
export const Poster = memo(function Poster({
  title,
  onPress,
  onFocus,
  first,
}: {
  title: Title;
  onPress: () => void;
  onFocus?: () => void;
  first?: boolean;
}) {
  const styles = useThemedStyles((c) => ({
    card: {
      width: POSTER_W,
      height: POSTER_H,
      borderRadius: radius.md,
      overflow: 'hidden' as const,
      backgroundColor: c.surface,
    },
    art: { width: '100%' as const, height: '100%' as const },
    fallback: {
      flex: 1,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    letter: { fontFamily: font.bold, fontSize: 64, color: '#ffffff' },
    name: {
      ...type.meta,
      color: c.muted,
      width: POSTER_W,
      marginTop: space.sm,
    },
  }));

  return (
    <View>
      <Focusable onPress={onPress} onFocus={onFocus} first={first}>
        <View style={styles.card}>
          {title.poster ? (
            <Image
              source={{ uri: title.poster }}
              style={styles.art}
              resizeMode="cover"
              resizeMethod="resize"
            />
          ) : (
            <View
              style={[
                styles.fallback,
                { backgroundColor: hues[hueOf(title.title) % hues.length] },
              ]}
            >
              <Text style={styles.letter}>{title.title.slice(0, 1).toUpperCase()}</Text>
            </View>
          )}
        </View>
      </Focusable>
      <Text style={styles.name} numberOfLines={1}>
        {title.title}
      </Text>
    </View>
  );
});

/**
 * A landscape card carrying the backdrop.
 *
 * Used for the recommendation rail so that it does not read as one more
 * indistinguishable row of posters — a suggestion the app made deserves to look
 * different from a chart it merely fetched.
 */
const WideCard = memo(function WideCard({
  title,
  onPress,
  onFocus,
  first,
}: {
  title: Title;
  onPress: () => void;
  onFocus?: () => void;
  first?: boolean;
}) {
  const styles = useThemedStyles((c) => ({
    card: {
      width: WIDE_W,
      height: WIDE_H,
      borderRadius: radius.md,
      overflow: 'hidden' as const,
      backgroundColor: c.surface,
      justifyContent: 'flex-end' as const,
    },
    art: { position: 'absolute' as const, top: 0, left: 0, right: 0, bottom: 0 },
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
    meta: { ...type.meta, fontSize: 11, color: '#cbd2e0', marginTop: 1 },
    fallback: {
      flex: 1,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    letter: { fontFamily: font.bold, fontSize: 40, color: '#ffffff' },
  }));

  const art = title.backdrop ?? title.poster;
  const facts = [title.year, title.genres?.[0]].filter(Boolean).join('  ·  ');

  return (
    <Focusable onPress={onPress} onFocus={onFocus} first={first}>
      <View style={styles.card}>
        {art ? (
          <Image
            source={{ uri: art }}
            style={styles.art}
            resizeMode="cover"
            resizeMethod="resize"
          />
        ) : (
          <View
            style={[
              styles.fallback,
              { backgroundColor: hues[hueOf(title.title) % hues.length] },
            ]}
          >
            <Text style={styles.letter}>{title.title.slice(0, 1).toUpperCase()}</Text>
          </View>
        )}
        <View style={styles.strip}>
          <Text style={styles.name} numberOfLines={1}>
            {title.title}
          </Text>
          {!!facts && <Text style={styles.meta}>{facts}</Text>}
        </View>
      </View>
    </Focusable>
  );
});

/**
 * A horizontal shelf.
 *
 * FlatList rather than a ScrollView because a catalog row is 50-odd items and
 * a television has the memory of a mid-range phone from 2018. Focus moving off
 * the end scrolls the list itself — Android does that for us once the child
 * that took focus asks its parent to reveal it.
 */
export const Rail = memo(function Rail({
  heading,
  note,
  titles,
  onPick,
  onFocusItem,
  firstItem,
  wide,
}: {
  heading: string;
  /** One line under the heading saying why this shelf is here. */
  note?: string;
  titles: Title[];
  onPick: (title: Title) => void;
  onFocusItem?: (title: Title) => void;
  /** Marks the very first poster of the very first rail as the entry point. */
  firstItem?: boolean;
  /** Landscape backdrop cards instead of posters. */
  wide?: boolean;
}) {
  const styles = useThemedStyles((c) => ({
    wrap: { marginBottom: space.lg },
    head: {
      flexDirection: 'row' as const,
      alignItems: 'center' as const,
      gap: space.sm,
      marginBottom: space.xs,
    },
    tick: {
      width: 3,
      height: type.title.lineHeight - 6,
      borderRadius: 2,
      backgroundColor: c.accent,
    },
    heading: { ...type.title, fontFamily: font.semibold, color: c.text },
    note: { ...type.meta, color: c.faint, marginBottom: space.xs },
    // Room for the focus scale to grow into without clipping.
    list: { paddingVertical: space.sm },
    gap: { width: space.md },
  }));

  if (!titles.length) return null;

  return (
    <View style={styles.wrap}>
      <View style={styles.head}>
        {/* Marks a shelf the app chose, not one it merely fetched. */}
        {!!note && <View style={styles.tick} />}
        <Text style={styles.heading}>{heading}</Text>
      </View>
      {!!note && <Text style={styles.note}>{note}</Text>}
      <FlatList
        horizontal
        data={titles}
        keyExtractor={(t) => t.imdb}
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.list}
        ItemSeparatorComponent={() => <View style={styles.gap} />}
        initialNumToRender={8}
        windowSize={5}
        removeClippedSubviews={false}
        renderItem={({ item, index }) =>
          wide ? (
            <WideCard
              title={item}
              first={firstItem && index === 0}
              onPress={() => onPick(item)}
              onFocus={onFocusItem ? () => onFocusItem(item) : undefined}
            />
          ) : (
            <Poster
              title={item}
              first={firstItem && index === 0}
              onPress={() => onPick(item)}
              onFocus={onFocusItem ? () => onFocusItem(item) : undefined}
            />
          )
        }
      />
    </View>
  );
});
