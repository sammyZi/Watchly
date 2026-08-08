import { useState } from 'react';
import { Image, StyleSheet, View } from 'react-native';
import { Icon, type IconName } from './Icon';
import { hueOf } from '../lib/title';
import { colors, radius } from '../theme';

/**
 * Poster tile for a torrent.
 *
 * The colour is derived from the name: stable across launches, and enough to
 * tell rows apart at a glance.
 *
 * `poster` is a frame copied off the video surface while watching, so the tile
 * shows roughly where you left off. It lives in the cache directory, so Android
 * can reclaim it at any time — hence the colour fallback and the onError guard.
 */
export function Thumb({
  name,
  poster,
  icon,
  width = 84,
  height = 52,
  progress,
}: {
  name: string;
  poster?: string;
  icon?: IconName;
  width?: number;
  height?: number;
  /** 0-100. Draws a strip along the bottom of the tile. */
  progress?: number;
}) {
  const [broken, setBroken] = useState(false);
  const showPoster = !!poster && !broken;

  return (
    <View
      style={[
        styles.tile,
        { width, height, backgroundColor: `hsl(${hueOf(name)}, 34%, 74%)` },
      ]}
    >
      {showPoster && (
        <Image
          source={{ uri: poster }}
          style={StyleSheet.absoluteFill}
          resizeMode="cover"
          onError={(e) => {
            // Usually means Android reclaimed the cache file.
            console.warn(
              `[poster] cannot render ${poster}:`,
              e.nativeEvent?.error ?? 'unknown'
            );
            setBroken(true);
          }}
        />
      )}

      {!!icon && (
        <View style={[styles.glyph, showPoster && styles.glyphOnPhoto]}>
          <Icon name={icon} size={16} color="#fff" />
        </View>
      )}

      {progress != null && progress > 0 && (
        <View style={styles.track}>
          <View
            style={[
              styles.fill,
              { width: `${Math.max(0, Math.min(100, progress))}%` },
            ]}
          />
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  tile: {
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },
  glyph: {
    width: 30,
    height: 30,
    borderRadius: radius.pill,
    backgroundColor: '#0000003d',
    alignItems: 'center',
    justifyContent: 'center',
  },
  // A real frame can be any colour, so the glyph needs its own contrast.
  glyphOnPhoto: { backgroundColor: '#00000080' },
  track: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    height: 3,
    backgroundColor: '#ffffff59',
  },
  fill: { height: 3, backgroundColor: colors.accent },
});
