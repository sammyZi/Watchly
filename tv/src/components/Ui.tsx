import { FlatList, Image, Text, View, type StyleProp, type ViewStyle } from 'react-native';
import { Focusable } from './Focusable';
import { Icon, type IconName } from './Icon';
import { font, radius, space, type, useColors, useThemedStyles } from '../theme';
import type { Title } from '../lib/meta';
import { hueOf } from '../lib/title';
import { hues } from '../theme';

/** Poster aspect, and the one place the rail geometry is decided. */
export const POSTER_W = 190;
export const POSTER_H = 285;

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
      paddingHorizontal: space.xl,
      height: 62,
    },
    ghost: { backgroundColor: c.control },
    solid: { backgroundColor: c.accent },
    danger: { backgroundColor: c.danger },
    label: { ...type.label, fontSize: 20 },
  }));

  const filled = variant !== 'ghost';

  return (
    <Focusable
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
            {!!icon && <Icon name={icon} size={20} color={fg} />}
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
export function Poster({
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
            <Image source={{ uri: title.poster }} style={styles.art} resizeMode="cover" />
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
}

/**
 * A horizontal shelf of posters.
 *
 * FlatList rather than a ScrollView because a catalog row is 50-odd items and
 * a television has the memory of a mid-range phone from 2018. Focus moving off
 * the end scrolls the list itself — Android does that for us once the child
 * that took focus asks its parent to reveal it.
 */
export function Rail({
  heading,
  titles,
  onPick,
  onFocusItem,
  firstItem,
}: {
  heading: string;
  titles: Title[];
  onPick: (title: Title) => void;
  onFocusItem?: (title: Title) => void;
  /** Marks the very first poster of the very first rail as the entry point. */
  firstItem?: boolean;
}) {
  const styles = useThemedStyles((c) => ({
    wrap: { marginBottom: space.xl },
    heading: {
      ...type.title,
      fontFamily: font.semibold,
      color: c.text,
      marginBottom: space.md,
    },
    // Room for the focus scale to grow into without clipping.
    list: { paddingVertical: space.sm },
    gap: { width: space.md },
  }));

  if (!titles.length) return null;

  return (
    <View style={styles.wrap}>
      <Text style={styles.heading}>{heading}</Text>
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
        renderItem={({ item, index }) => (
          <Poster
            title={item}
            first={firstItem && index === 0}
            onPress={() => onPick(item)}
            onFocus={onFocusItem ? () => onFocusItem(item) : undefined}
          />
        )}
      />
    </View>
  );
}
