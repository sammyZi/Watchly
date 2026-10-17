import { useEffect, useRef, useState } from 'react';
import { Animated, Easing, ScrollView, type TextStyle } from 'react-native';

/**
 * One line of text that walks itself along when it does not fit.
 *
 * Release names are the reason: "Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.Atmos"
 * truncates to something you cannot tell apart from the next four rows, and
 * the part that got cut — the codec, the group, the audio — is exactly what
 * you were choosing between.
 *
 * The ScrollView is there to measure, not to scroll: it is the one container
 * that hands its child unbounded width, so `onContentSizeChange` reports what
 * the text actually wants rather than what it was squeezed into. Scrolling is
 * off so it never steals a drag from the row it sits in.
 */
export function Marquee({
  children,
  style,
  /** Pixels per second. Reading pace, not attention-seeking. */
  speed = 30,
}: {
  children: string;
  style?: TextStyle | TextStyle[];
  speed?: number;
}) {
  const [box, setBox] = useState(0);
  const [text, setText] = useState(0);
  const x = useRef(new Animated.Value(0)).current;
  const over = Math.max(0, Math.round(text - box));

  useEffect(() => {
    x.setValue(0);
    if (!over) return;
    const travel = (over / speed) * 1000;
    // Back and forth rather than a wrapping loop: no second copy of the text
    // to keep in sync, and the name is readable from the start on every pass.
    const walk = Animated.loop(
      Animated.sequence([
        Animated.delay(1500),
        Animated.timing(x, {
          toValue: -over,
          duration: travel,
          easing: Easing.linear,
          useNativeDriver: true,
        }),
        Animated.delay(1500),
        Animated.timing(x, {
          toValue: 0,
          duration: travel,
          easing: Easing.linear,
          useNativeDriver: true,
        }),
      ])
    );
    walk.start();
    return () => walk.stop();
  }, [over, speed, x]);

  return (
    <ScrollView
      horizontal
      scrollEnabled={false}
      showsHorizontalScrollIndicator={false}
      onLayout={(e) => setBox(e.nativeEvent.layout.width)}
      onContentSizeChange={(w) => setText(w)}
    >
      <Animated.Text numberOfLines={1} style={[style, { transform: [{ translateX: x }] }]}>
        {children}
      </Animated.Text>
    </ScrollView>
  );
}
