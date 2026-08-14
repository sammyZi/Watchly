import { useCallback, useEffect, useRef, useState } from 'react';
import TorrentStream, {
  type Progress,
  type TorrentMeta,
} from '../../modules/torrent-stream';
import {
  loadQueue,
  removeDownload,
  saveDownloadJob,
  type DownloadJob,
} from '../lib/downloadJob';
import { withTrackers } from '../lib/magnet';
import { pickFile, seasonFiles } from '../lib/pick';
import { searchableName } from '../lib/title';

export type LiveFile = {
  index: number;
  name: string;
  size: number;
  path?: string;
};

export type LiveDownload = {
  job: DownloadJob;
  progress: Progress | null;
  meta: TorrentMeta | null;
  wanted: number[];
  saved: number[];
  files: LiveFile[];
};

export function useDownloads(
  settings: { addTrackers: boolean; prepareMb: number },
  onSaved?: (
    name: string,
    size: number,
    path?: string,
    extra?: { poster?: string | null; titleName?: string }
  ) => void
) {
  const [live, setLive] = useState<LiveDownload[]>([]);
  const liveRef = useRef(live);
  liveRef.current = live;
  const onSavedRef = useRef(onSaved);
  onSavedRef.current = onSaved;
  const chain = useRef(Promise.resolve());

  const start = useCallback(
    (job: DownloadJob) => {
      const uri = settings.addTrackers ? withTrackers(job.uri) : job.uri;
      if (liveRef.current.some((l) => l.job.uri === uri || l.job.uri === job.uri)) {
        return;
      }
      const stored: DownloadJob = { ...job, uri };
      const row: LiveDownload = {
        job: stored,
        progress: null,
        meta: null,
        wanted: stored.fileIndices ?? [],
        saved: stored.savedIndices ?? [],
        files: [],
      };
      setLive((prev) => [...prev, row]);
      saveDownloadJob(stored).catch(() => {});
      TorrentStream.setNotify(true).catch(() => {});

      chain.current = chain.current
        .then(async () => {
          const found = await TorrentStream.inspect(uri, 60);
          let indices = (stored.fileIndices ?? []).filter((i) => i >= 0);
          if (stored.season != null) {
            const season = seasonFiles(found.files, stored.season);
            if (season.length) indices = season;
          } else if (!indices.length && stored.auto) {
            const picked = pickFile(
              found.files,
              typeof stored.auto === 'string' ? stored.auto : undefined
            );
            if (picked != null) indices = [picked];
          }
          if (!indices.length) {
            const picked = pickFile(found.files);
            if (picked != null) indices = [picked];
          }
          if (!indices.length) throw new Error('No video in this torrent');

          const files: LiveFile[] = indices.map((i) => {
            const f = found.files.find((x) => x.index === i);
            return {
              index: i,
              name: f?.name ?? `File ${i}`,
              size: f?.size ?? 0,
              path: f?.path,
            };
          });
          setLive((prev) =>
            prev.map((l) =>
              l.job.uri === uri
                ? { ...l, meta: found, wanted: indices, files }
                : l
            )
          );
          await saveDownloadJob({
            ...stored,
            fileIndices: indices,
            queueIndices: stored.queueIndices?.length ? stored.queueIndices : indices,
          });
          await TorrentStream.play(
            uri,
            indices[0],
            {
              prepareMb: settings.prepareMb,
              removeFilesAfterStop: false,
              fullFile: true,
              fileList: indices.join(','),
              season: stored.season ?? -1,
              saveFolder:
                stored.titleName ||
                searchableName(stored.title || found.name) ||
                '',
            },
            indices.join(',')
          );
        })
        .catch((e) => {
          console.warn('[download] start failed', e?.message ?? e);
          setLive((prev) => prev.filter((l) => l.job.uri !== uri));
          removeDownload(uri).catch(() => {});
        });
    },
    [settings.addTrackers, settings.prepareMb]
  );

  const stop = useCallback(async (uri: string) => {
    await TorrentStream.stopDownload(uri).catch(() => {});
    await removeDownload(uri).catch(() => {});
    setLive((prev) => {
      const next = prev.filter((l) => l.job.uri !== uri);
      if (!next.length) TorrentStream.setNotify(false).catch(() => {});
      return next;
    });
  }, []);

  const pause = useCallback((uri: string) => {
    TorrentStream.pauseDownload(uri).catch(() => {});
  }, []);

  const resume = useCallback((uri: string) => {
    TorrentStream.resumeDownload(uri).catch(() => {});
  }, []);

  const skip = useCallback((uri: string, index: number) => {
    setLive((prev) =>
      prev.map((l) =>
        l.job.uri === uri
          ? { ...l, wanted: l.wanted.filter((i) => i !== index) }
          : l
      )
    );
    TorrentStream.skipDownload(uri, index).catch(() => {});
  }, []);

  const include = useCallback((uri: string, index: number) => {
    setLive((prev) =>
      prev.map((l) =>
        l.job.uri === uri
          ? { ...l, wanted: l.wanted.includes(index) ? l.wanted : [...l.wanted, index] }
          : l
      )
    );
    TorrentStream.includeDownload(uri, index).catch(() => {});
  }, []);

  const markSaved = useCallback((uri: string, ids: number[]) => {
    setLive((prev) =>
      prev.map((l) =>
        l.job.uri === uri ? { ...l, saved: [...new Set([...l.saved, ...ids])] } : l
      )
    );
    const row = liveRef.current.find((l) => l.job.uri === uri);
    if (row) {
      saveDownloadJob({
        uri,
        savedIndices: [...new Set([...row.saved, ...ids])],
      }).catch(() => {});
    }
  }, []);

  useEffect(() => {
    const sub = TorrentStream.addListener('progress', (p: Progress) => {
      if (!p.uri) return;
      setLive((prev) =>
        prev.map((l) => (l.job.uri === p.uri ? { ...l, progress: p } : l))
      );
    });
    return () => sub.remove();
  }, []);

  useEffect(() => {
    const id = setInterval(() => {
      TorrentStream.saveReady()
        .then((rows) => {
          const grouped = new Map<string, typeof rows>();
          for (const row of rows) {
            const uri = String(row.id || '');
            if (!uri) continue;
            const g = grouped.get(uri) ?? [];
            g.push(row);
            grouped.set(uri, g);
          }
          for (const [uri, list] of grouped) {
            const cur = liveRef.current.find((l) => l.job.uri === uri);
            if (!cur) continue;
            const fresh = list.filter((r) => !cur.saved.includes(Number(r.index)));
            if (!fresh.length) continue;
            for (const row of fresh) {
              onSavedRef.current?.(row.name, Number(row.size), row.uri, {
                poster: cur.job.poster,
                titleName: cur.job.titleName,
              });
            }
            const ids = fresh.map((r) => Number(r.index));
            markSaved(uri, ids);
            const saved = new Set([...cur.saved, ...ids]);
            if (!cur.wanted.length || !cur.wanted.every((i) => saved.has(i))) continue;
            const queue = cur.job.queueIndices ?? cur.files.map((f) => f.index);
            const skipped = queue.filter((i) => !cur.wanted.includes(i) && !saved.has(i));
            if (skipped.length === 0) void stop(uri);
            else pause(uri);
          }
        })
        .catch(() => {});
    }, 3000);
    return () => clearInterval(id);
  }, [markSaved, pause, stop]);

  const startRef = useRef(start);
  startRef.current = start;
  useEffect(() => {
    loadQueue().then((jobs) => {
      if (!jobs.length) {
        TorrentStream.clearCache().catch(() => {});
        return;
      }
      for (const job of jobs) startRef.current(job);
    });
  }, []);

  return { live, start, stop, pause, resume, skip, include, markSaved };
}
