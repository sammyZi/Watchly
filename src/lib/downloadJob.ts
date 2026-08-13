import AsyncStorage from '@react-native-async-storage/async-storage';

const KEY = 'torrent-active-download';
const PARKED = 'torrent-parked-downloads';

/** An unfinished transfer to pick up after the app is killed. */
export type DownloadJob = {
  uri: string;
  fileIndex: number;
  fileIndices: number[];
  season?: number;
  title?: string;
  titleName?: string;
  poster?: string | null;
  imdb?: string;
  kind?: 'movie' | 'tv';
};

/** A cancelled transfer kept so it can be started again. */
export type ParkedDownload = DownloadJob & {
  name: string;
  size: number;
  at: number;
};

export async function loadDownloadJob(): Promise<DownloadJob | null> {
  try {
    const raw = await AsyncStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as DownloadJob) : null;
  } catch {
    return null;
  }
}

export async function saveDownloadJob(job: DownloadJob) {
  await AsyncStorage.setItem(KEY, JSON.stringify(job));
}

export async function clearDownloadJob() {
  await AsyncStorage.removeItem(KEY);
}

export async function loadParked(): Promise<ParkedDownload[]> {
  try {
    return JSON.parse((await AsyncStorage.getItem(PARKED)) ?? '[]');
  } catch {
    return [];
  }
}

export async function parkDownload(job: ParkedDownload) {
  const rest = (await loadParked()).filter((d) => d.uri !== job.uri);
  await AsyncStorage.setItem(PARKED, JSON.stringify([job, ...rest]));
}

export async function dropParked(uri: string): Promise<ParkedDownload[]> {
  const next = (await loadParked()).filter((d) => d.uri !== uri);
  await AsyncStorage.setItem(PARKED, JSON.stringify(next));
  return next;
}
