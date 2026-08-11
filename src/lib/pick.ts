/** Just what picking needs — `TorrentFile` from the native module fits this. */
type Filed = { index: number; name: string; size: number; isVideo: boolean };

/** Sample, trailer and "extras" folders sit next to the feature in a lot of
 *  releases, and are always small — but a 200MB trailer is still smaller than
 *  the film, so they are named rather than left to the size test. */
const JUNK = /\b(sample|trailer|extras?|featurette|behind[. _-]?the[. _-]?scenes)\b/i;

/**
 * The one file a viewer meant to open.
 *
 * `tag` is an episode marker like "S01E02": a season pack holds thirty videos
 * and only one of them is the episode that was tapped, so a name match beats
 * size there. Everything else — a film, a single-episode torrent — is the
 * biggest video in the torrent, which is the feature in every layout I know of.
 *
 * Returns null when there is no video at all; the caller then has to fall back
 * to letting the user choose.
 */
export function pickFile(files: Filed[], tag?: string): number | null {
  const videos = files.filter((f) => f.isVideo && !JUNK.test(f.name));
  if (!videos.length) return null;

  if (tag) {
    const wanted = new RegExp(tag.replace(/(\d)/g, '0*$1'), 'i');
    const match = videos.filter((f) => wanted.test(f.name));
    if (match.length) {
      return match.reduce((a, b) => (b.size > a.size ? b : a)).index;
    }
  }

  return videos.reduce((a, b) => (b.size > a.size ? b : a)).index;
}
