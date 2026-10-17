import AsyncStorage from '@react-native-async-storage/async-storage';

const KEY = 'torrent-active-download';

/** An unfinished transfer to pick up after the app is killed. */
export type DownloadJob = {
  uri: string;
  fileIndex?: number;
  fileIndices: number[];
  /** Full pack list so skipped episodes still show after a restart. */
  queueIndices?: number[];
  /** Indexes already copied to Movies — skip these after a restart. */
  savedIndices?: number[];
  season?: number;
  /** Resolve the file list when this job starts (queued from a title page). */
  auto?: boolean | string;
  title?: string;
  titleName?: string;
  poster?: string | null;
  imdb?: string;
  kind?: 'movie' | 'tv';
};

export function jobLabel(job: DownloadJob) {
  return job.titleName || job.title || 'Download';
}

async function readQueue(): Promise<DownloadJob[]> {
  try {
    const raw = await AsyncStorage.getItem(KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) return parsed.filter((j) => j?.uri);
    if (parsed?.uri) return [parsed as DownloadJob];
  } catch {
    /* ignore */
  }
  return [];
}

async function writeQueue(jobs: DownloadJob[]) {
  if (!jobs.length) {
    await AsyncStorage.removeItem(KEY);
    return;
  }
  await AsyncStorage.setItem(KEY, JSON.stringify(jobs));
}

export async function loadQueue(): Promise<DownloadJob[]> {
  return readQueue();
}

export async function loadDownloadJob(): Promise<DownloadJob | null> {
  return (await readQueue())[0] ?? null;
}

export async function saveDownloadJob(job: Partial<DownloadJob>) {
  const jobs = await readQueue();
  const uri = job.uri;
  if (!uri) {
    if (!jobs.length) {
      return;
    }
    jobs[0] = { ...jobs[0], ...job };
    await writeQueue(jobs);
    return;
  }
  const i = jobs.findIndex((j) => j.uri === uri);
  if (i < 0) {
    jobs.push({ fileIndices: [], ...job } as DownloadJob);
  } else {
    jobs[i] = { ...jobs[i], ...job };
  }
  await writeQueue(jobs);
}

export async function enqueueDownload(job: DownloadJob) {
  await saveDownloadJob(job);
}

export async function removeDownload(uri: string) {
  await writeQueue((await readQueue()).filter((j) => j.uri !== uri));
}

/** Drop the active job. Returns the next one, if any. */
export async function shiftDownload(): Promise<DownloadJob | null> {
  const jobs = await readQueue();
  jobs.shift();
  await writeQueue(jobs);
  return jobs[0] ?? null;
}

export async function clearDownloadJob() {
  await AsyncStorage.removeItem(KEY);
}
