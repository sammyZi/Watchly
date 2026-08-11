const BASE = 'https://api.opensubtitles.com/api/v1';

export type SubtitleResult = {
  id: string;
  type: 'movie' | 'episode' | 'tvshow';
  attributes: {
    language: string;
    download_count: number;
    release: string;
    files: Array<{ file_id: number; file_name: string }>;
    feature_details?: {
      title: string;
      year: number;
      season_number?: number;
      episode_number?: number;
    };
  };
};

type SearchResponse = {
  data: SubtitleResult[];
  total_count: number;
};

/** Search OpenSubtitles for a given file name. Requires a free API key from opensubtitles.com. */
export async function searchSubtitles(
  query: string,
  apiKey: string,
  language = 'en'
): Promise<SubtitleResult[]> {
  const url = `${BASE}/subtitles?languages=${language}&query=${encodeURIComponent(query)}`;
  const res = await fetch(url, { headers: { 'Api-Key': apiKey } });
  if (!res.ok) throw new Error(`OpenSubtitles search returned ${res.status}`);
  const body: SearchResponse = await res.json();
  // SubRip only, to match what the player loads from a torrent. A result with
  // no files at all is a listing for something that cannot be downloaded.
  return body.data.filter((r) =>
    r.attributes.files?.some((f) => !f.file_name || /\.srt$/i.test(f.file_name))
  );
}

/** Get the download URL for a subtitle file. */
export async function getDownloadUrl(
  fileId: number,
  apiKey: string
): Promise<string> {
  const res = await fetch(`${BASE}/download`, {
    method: 'POST',
    headers: {
      'Api-Key': apiKey,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ file_id: fileId }),
  });
  if (!res.ok) throw new Error(`OpenSubtitles download returned ${res.status}`);
  const body = await res.json();
  return body.link;
}

/** Download subtitle content as a UTF-8 string. */
export async function downloadSubtitle(fileId: number, apiKey: string): Promise<string> {
  const link = await getDownloadUrl(fileId, apiKey);
  const res = await fetch(link);
  if (!res.ok) throw new Error(`Subtitle file download returned ${res.status}`);
  return res.text();
}
