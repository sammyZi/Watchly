import { useEffect } from 'react';
import {
  Linking,
  Modal,
  Pressable,
  StatusBar,
  Text,
  TurboModuleRegistry,
  useWindowDimensions,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import * as ScreenOrientation from 'expo-screen-orientation';
import type { WebView as WebViewType } from 'react-native-webview';
import { Icon } from './Icon';
import { font, radius, space, type } from '../theme';

/**
 * Where the embed believes it is hosted. YouTube refuses to play an embed that
 * arrives with no Referer (error 152/153 — "Video player configuration error"),
 * which is exactly what an inline HTML page in a WebView sends. A base URL gives
 * the page an origin; the app id is what YouTube asks apps to identify with.
 */
const ORIGIN = 'https://com.torrentviewer.app';

const page = (id: string) => `<!doctype html>
<html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  html, body { margin: 0; height: 100%; background: #000; overflow: hidden; }
  iframe { position: absolute; inset: 0; width: 100%; height: 100%; border: 0; }
</style>
</head><body>
<iframe
  src="https://www.youtube.com/embed/${encodeURIComponent(id)}?autoplay=1&playsinline=1&rel=0&fs=0"
  allow="autoplay; encrypted-media; picture-in-picture"
  referrerpolicy="strict-origin-when-cross-origin"></iframe>
</body></html>`;

export const watchUrl = (id: string) =>
  `https://www.youtube.com/watch?v=${encodeURIComponent(id)}`;

/**
 * The WebView, when the installed build carries its native half.
 *
 * Asked for rather than imported: react-native-webview looks its native module
 * up the moment it loads and throws if it is missing, and this file loads with
 * the title page — so a build made before the package was added would die on
 * launch, not just on the trailer. Without it, trailers open in YouTube.
 */
const WebView: typeof WebViewType | null = TurboModuleRegistry.get('RNCWebViewModule')
  ? require('react-native-webview').WebView
  : null;

/** Trailers play in here; otherwise the button hands off to YouTube. */
export const canPlayTrailers = WebView != null;

/**
 * A title's trailer, played over the page in YouTube's own embedded player.
 *
 * Rotation is let go while it is open, and turning the phone *is* full screen:
 * the embed's own full-screen button is hidden (`fs=0`) because on Android the
 * WebView puts full-screen video in the activity's window — behind this modal.
 * Portrait is handed back on close, as the rest of the app keeps it.
 */
export function TrailerModal({
  id,
  title,
  onClose,
}: {
  /** YouTube video id. Null keeps the modal shut. */
  id: string | null;
  title: string;
  onClose: () => void;
}) {
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const landscape = width > height;

  useEffect(() => {
    if (!id) return;
    ScreenOrientation.unlockAsync().catch(() => {});
    return () => {
      ScreenOrientation.lockAsync(ScreenOrientation.OrientationLock.PORTRAIT_UP).catch(() => {});
    };
  }, [id]);

  const openOutside = () => {
    if (!id) return;
    onClose();
    Linking.openURL(watchUrl(id)).catch(() => {});
  };

  return (
    <Modal
      visible={!!id}
      animationType="fade"
      onRequestClose={onClose}
      statusBarTranslucent
      navigationBarTranslucent
      supportedOrientations={['portrait', 'landscape']}
    >
      <StatusBar barStyle="light-content" backgroundColor="#000" hidden={landscape} />
      <View
        style={{
          flex: 1,
          backgroundColor: '#000',
          paddingTop: landscape ? 0 : insets.top,
          paddingBottom: insets.bottom,
          paddingLeft: insets.left,
          paddingRight: insets.right,
        }}
      >
        <View
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            gap: space.md,
            paddingHorizontal: space.lg,
            paddingVertical: space.sm,
          }}
        >
          <Pressable
            onPress={onClose}
            hitSlop={12}
            accessibilityLabel="Close trailer"
            style={({ pressed }) => [
              {
                width: 40,
                height: 40,
                borderRadius: radius.pill,
                alignItems: 'center',
                justifyContent: 'center',
                backgroundColor: '#ffffff1a',
              },
              pressed && { opacity: 0.6 },
            ]}
          >
            <Icon name="x" size={20} color="#fff" />
          </Pressable>
          <Text
            numberOfLines={1}
            style={{ ...type.label, fontFamily: font.semibold, color: '#fff', flex: 1 }}
          >
            {title} · Trailer
          </Text>
          {/* The way out when an embed is refused — some uploads disallow it. */}
          <Pressable
            onPress={openOutside}
            hitSlop={12}
            accessibilityLabel="Open in YouTube"
            style={({ pressed }) => [
              { flexDirection: 'row', alignItems: 'center', gap: 4 },
              pressed && { opacity: 0.6 },
            ]}
          >
            <Text style={{ ...type.meta, fontFamily: font.medium, color: '#ffffffb3' }}>
              YouTube
            </Text>
            <Icon name="external-link" size={13} color="#ffffffb3" />
          </Pressable>
        </View>

        {!!id && WebView && (
          <WebView
            source={{ html: page(id), baseUrl: ORIGIN }}
            style={{ flex: 1, backgroundColor: '#000' }}
            containerStyle={{ backgroundColor: '#000' }}
            allowsInlineMediaPlayback
            mediaPlaybackRequiresUserAction={false}
            // Only the embed belongs in here. "Watch on YouTube", the channel
            // name and the like leave for the app or browser instead of turning
            // this into a cramped youtube.com.
            setSupportMultipleWindows={false}
            onShouldStartLoadWithRequest={(req) => {
              // The player's own frames load what they like; only the page
              // itself is kept to the embed.
              if (!req.isTopFrame) return true;
              if (req.url.startsWith(ORIGIN) || req.url === 'about:blank') return true;
              Linking.openURL(req.url).catch(() => {});
              return false;
            }}
          />
        )}
      </View>
    </Modal>
  );
}
