import Feather from '@expo/vector-icons/Feather';
import { colors } from '../theme';

export type IconName = keyof typeof Feather.glyphMap;

/** One place to swap the icon set. Line icons only — no emoji anywhere. */
export function Icon({
  name,
  size = 18,
  color = colors.muted,
}: {
  name: IconName;
  size?: number;
  color?: string;
}) {
  return <Feather name={name} size={size} color={color} />;
}
