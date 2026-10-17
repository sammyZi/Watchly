export const speed = (bytes: number) =>
  bytes > 1e6 ? `${(bytes / 1e6).toFixed(1)} MB/s` : `${Math.round(bytes / 1e3)} kB/s`;

/** 75 -> "1:15", 3675 -> "1:01:15" */
export const clock = (seconds: number) => {
  const t = Math.max(0, Math.floor(seconds));
  const parts = [Math.floor(t / 3600), Math.floor(t / 60) % 60, t % 60];
  return parts
    .slice(parts[0] ? 0 : 1)
    .map((n, i) => (i ? String(n).padStart(2, '0') : String(n)))
    .join(':');
};

/** Coarse "how much is left" — 640 -> "11m left". Deliberately not clock():
 *  next to a progress bar, seconds are noise. */
export const left = (seconds: number) => {
  const mins = Math.round(Math.max(0, seconds) / 60);
  if (mins < 1) return 'almost done';
  if (mins < 60) return `${mins}m left`;
  return `${Math.floor(mins / 60)}h ${mins % 60}m left`;
};

export const ago = (ms: number) => {
  const mins = Math.floor((Date.now() - ms) / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  if (mins < 1440) return `${Math.floor(mins / 60)}h ago`;
  return `${Math.floor(mins / 1440)}d ago`;
};

export const bytes = (n: number) => {
  if (n >= 1024 ** 3) return `${(n / 1024 ** 3).toFixed(2)} GB`;
  if (n >= 1024 ** 2) return `${(n / 1024 ** 2).toFixed(0)} MB`;
  if (n >= 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${n} B`;
};
