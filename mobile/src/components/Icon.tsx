import Feather from '@expo/vector-icons/Feather';
import { useColors } from '../theme';

export type IconName = keyof typeof Feather.glyphMap;

/** One place to swap the icon set. Line icons only — no emoji anywhere. */
export function Icon({
  name,
  size = 18,
  color,
}: {
  name: IconName;
  size?: number;
  color?: string;
}) {
  const colors = useColors();
  return <Feather name={name} size={size} color={color ?? colors.muted} />;
}
