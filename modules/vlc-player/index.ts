import { requireNativeModule, requireNativeView } from 'expo';

/** Whether the app is in the picture-in-picture window right now. */
export const isInPip = (): boolean =>
  requireNativeModule<{ isInPip(): boolean }>('VlcPlayer').isInPip();
import type { ViewStyle } from 'react-native';
import type { ComponentType, Ref } from 'react';

/** One selectable stream inside the media. `id` is libVLC's, not an index. */
export type VlcTrack = { id: number; name: string };

export type VlcTracks = {
  subtitles: VlcTrack[];
  audio: VlcTrack[];
  audioId?: number;
  defaultAudio?: number;
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
  /** Shrink into a floating window. False where the device has no picture-in-picture. */
  enterPip(): Promise<boolean>;
  /** libVLC's playback statistics and the playing tracks; null before anything is loaded. */
  stats(): Promise<VlcStats | null>;
};

export type VlcStats = {
  videoCodec: string | null;
  width: number;
  height: number;
  fps: number;
  audioCodec: string | null;
  channels: number;
  sampleRate: number;
  language: string | null;
  inputKbps: number;
  /** The video's own data rate, as the demuxer reads it. */
  contentKbps: number;
  readBytes: number;
  displayed: number;
  lostFrames: number;
  playedBuffers: number;
  lostBuffers: number;
  corrupted: number;
  discontinuities: number;
};

/** libVLC's own fit modes, by their enum names. */
export type VlcScaleType =
  | 'SURFACE_BEST_FIT'
  | 'SURFACE_FIT_SCREEN'
  | 'SURFACE_FILL'
  | 'SURFACE_16_9'
  | 'SURFACE_4_3'
  | 'SURFACE_ORIGINAL';

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
  /** How the picture is fitted to the surface. Defaults to best fit. */
  scaleType?: VlcScaleType;
  /** Go to picture-in-picture on Home while playing (Android 12+). */
  autoPip?: boolean;
  /** Shown on the playback notification and the lock screen. */
  title?: string;
  /** Entered or left picture-in-picture. */
  onPip?: (e: { nativeEvent: { active: boolean } }) => void;

  onLoaded?: (e: { nativeEvent: { duration: number } }) => void;
  onProgress?: (e: { nativeEvent: { currentTime: number } }) => void;
  onBuffering?: (e: { nativeEvent: { isBuffering: boolean } }) => void;
  onEnded?: (e: { nativeEvent: Record<string, never> }) => void;
  onFailed?: (e: { nativeEvent: { message: string } }) => void;
  onTracks?: (e: { nativeEvent: VlcTracks }) => void;
  /**
   * A headset, car stereo or lock-screen button. The native side has already
   * acted on play/pause by the time this arrives — it is here so the overlay's
   * own state can follow, and so next/previous reach whoever owns the queue.
   */
  onRemote?: (e: {
    nativeEvent: {
      action: 'play' | 'pause' | 'next' | 'previous' | 'seek';
      position?: number;
    };
  }) => void;

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
