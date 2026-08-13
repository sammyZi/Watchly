import AsyncStorage from '@react-native-async-storage/async-storage';

const KEY = 'torrent-active-download';

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
