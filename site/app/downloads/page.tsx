'use client';
/** Downloads: what the engine is fetching right now, and files saved to disk. */
import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { activeTorrents, dropTorrent, hashOf, type Engine } from '../../lib/torrent';
import { loadHistory, drop as dropHistory, type Item } from '../../lib/history';
import { bytes, speed } from '../../lib/format';
import { titleOf } from '../../lib/title';
import { supportsSaveAPI } from '../../lib/save';

export default function DownloadsScreen() {
  const [engines, setEngines] = useState<Engine[]>([]);
  const [history, setHistory] = useState<Item[]>([]);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    const t = setInterval(() => setTick((x) => x + 1), 1000);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    activeTorrents().then(setEngines);
  }, [tick]);

  useEffect(() => {
    loadHistory().then(setHistory);
  }, [tick]);

  const stop = useCallback(async (uri: string) => {
    const hash = hashOf(uri);
    if (hash) await dropTorrent(hash);
    await dropHistory(uri);
    setTick((x) => x + 1);
  }, []);

  const saves = history.filter((h) => h.uri && h.uri.includes('saved:'));

  return (
    <main className="page">
      <div className="page-head">
        <h1 className="page-title">Downloads</h1>
      </div>

      {!supportsSaveAPI() && (
        <p className="warn-text" style={{ fontSize: 12, marginBottom: 12 }}>
          This browser has no File System Access API — “Save” files will stay in-memory only.
        </p>
      )}

      <h2 className="shelf-title" style={{ margin: '8px 0 6px' }}>Active swarms</h2>
      {engines.length === 0 && <p className="center">Nothing downloading — open a release to start a swarm.</p>}
      {engines.map((e) => (
        <div key={e.infoHash} className="row">
          <div className="row-body">
            <div className="row-title">{e.torrentName}</div>
            <div className="row-sub">
              {e.done
                ? `Downloaded · ${bytes(e.size)} · not seeding`
                : `${Math.round(e.progress * 100)}% · ${bytes(e.size)} · ${speed(e.downloadSpeed)} down / ${speed(e.uploadSpeed)} up · ${e.numPeers} peers`}
            </div>
            <div style={{ height: 3, borderRadius: 2, background: 'var(--line)', marginTop: 6, overflow: 'hidden' }}>
              <div style={{ width: `${e.progress * 100}%`, height: '100%', background: 'var(--accent)' }} />
            </div>
          </div>
          <button className="btn btn-soft" style={{ padding: '8px 12px', fontSize: 12 }} onClick={() => stop(e.uri)}>
            Stop
          </button>
        </div>
      ))}

      <h2 className="shelf-title" style={{ margin: '22px 0 6px' }}>Recent torrents</h2>
      {history
        .filter((h) => !saves.includes(h))
        .map((h) => (
          <div key={h.uri} className="row">
            <div className="row-body">
              <div className="row-title">{h.titleName ?? titleOf(h.uri)}</div>
              {h.poster && (
                <Link href={`/title/${h.imdb}?kind=${h.kind ?? 'movie'}`} className="row-sub muted">
                  View title page →
                </Link>
              )}
            </div>
            <button className="btn btn-soft" style={{ padding: '8px 12px', fontSize: 12 }} onClick={() => stop(h.uri)}>
              Forget
            </button>
          </div>
        ))}

      <h2 className="shelf-title" style={{ margin: '22px 0 6px' }}>Saved files</h2>
      {saves.length === 0 && <p className="center">Files you save from the player land here.</p>}
      {saves.map((h) => (
        <div key={h.uri} className="row">
          <div className="row-body">
            <div className="row-title">{h.title}</div>
            <div className="row-sub">{h.files?.[0] ? bytes(h.files[0].size) : ''}</div>
          </div>
          {/* Saved to the user's own disk — a web page cannot reopen it by path. */}
          <button
            className="btn btn-soft"
            style={{ padding: '8px 12px', fontSize: 12 }}
            onClick={() => dropHistory(h.uri).then(() => setTick((x) => x + 1))}
          >
            Forget
          </button>
        </div>
      ))}
    </main>
  );
}