// node --experimental-strip-types src/lib/magnet.test.ts
import assert from 'node:assert';
import { withTrackers } from './magnet.ts';

const tr = ['udp://a/announce', 'udp://b/announce'];

// bare magnet gets every tracker
assert.equal(
  withTrackers('magnet:?xt=urn:btih:ABC', tr),
  'magnet:?xt=urn:btih:ABC&tr=udp%3A%2F%2Fa%2Fannounce&tr=udp%3A%2F%2Fb%2Fannounce'
);

// already-present trackers are not duplicated
const has = 'magnet:?xt=urn:btih:ABC&tr=udp%3A%2F%2Fa%2Fannounce';
assert.equal(withTrackers(has, tr), has + '&tr=udp%3A%2F%2Fb%2Fannounce');
assert.equal(withTrackers(has, ['udp://a/announce']), has);

// non-magnets are left alone
assert.equal(withTrackers('https://x.org/a.torrent', tr), 'https://x.org/a.torrent');
assert.equal(withTrackers('file:///tmp/a.torrent', tr), 'file:///tmp/a.torrent');

console.log('ok');

// deep-link guard
import { isTorrentSource } from './magnet.ts';
assert.ok(isTorrentSource('magnet:?xt=urn:btih:ABC'));
assert.ok(isTorrentSource('content://com.android.providers.downloads/1'));
assert.ok(isTorrentSource('file:///storage/x.torrent'));
assert.ok(isTorrentSource('https://x.org/a.torrent?k=1'));
assert.ok(!isTorrentSource('exp+torrentviewer://expo-development-client/?url=x'));
assert.ok(!isTorrentSource('https://example.com/page'));
console.log('ok');
