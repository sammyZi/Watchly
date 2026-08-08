import { requireNativeView } from 'expo';
import type { ViewStyle } from 'react-native';
import type { ComponentType, Ref } from 'react';

/** One selectable stream inside the media. `id` is libVLC's, not an index. */
export type VlcTrack = { id: number; name: string };

export type VlcTracks = {
  subtitles: VlcTrack[];
  audio: VlcTrack[];
};

export type VlcPlayerRef = {
  /** Absolute position, in seconds. */
  seekTo(seconds: number): Promise<void>;
  /** Stop playback now, without waiting for a `paused` prop to round-trip. */
  pause(): Promise<void>;
  /** Reopen the stream at `seconds`. Recovers from a dropped connection. */
  reload(seconds: number): Promise<void>;
  /**
   * Copy the frame on screen to a cache-directory JPEG and return its file uri,
   * or null if there is nothing to copy. Reads the decoded surface, so it costs
   * nothing on the network.
   */
  snapshot(): Promise<string | null>;
};

export type VlcPlayerProps = {
  style?: ViewStyle;
  /** http(s) or file uri. Changing it reloads the player. */
  source: string;
  paused?: boolean;
  rate?: number;
  /** Where to begin, in seconds. Read once, when the media loads. */
  startPosition?: number;
  /** libVLC track id, or null for off. */
  subtitleTrack?: number | null;
  audioTrack?: number | null;
  /** Absolute paths of external subtitle files to load alongside the media. */
  subtitleFiles?: string[] | null;

  onLoaded?: (e: { nativeEvent: { duration: number } }) => void;
  onProgress?: (e: { nativeEvent: { currentTime: number } }) => void;
  onBuffering?: (e: { nativeEvent: { isBuffering: boolean } }) => void;
  onEnded?: (e: { nativeEvent: Record<string, never> }) => void;
  onFailed?: (e: { nativeEvent: { message: string } }) => void;
  onTracks?: (e: { nativeEvent: VlcTracks }) => void;

  ref?: Ref<VlcPlayerRef>;
};

/**
 * libVLC-backed video surface.
 *
 * Chosen over ExoPlayer because it carries its own decoders: AC3/E-AC3/DTS
 * audio plays on devices that ship no platform decoder for them, and bitmap
 * subtitles (PGS, VOBSUB) render.
 */
export const VlcPlayer: ComponentType<VlcPlayerProps> =
  requireNativeView('VlcPlayer');
