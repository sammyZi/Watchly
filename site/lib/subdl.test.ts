// node --experimental-strip-types site/lib/subdl.test.ts
import assert from 'node:assert';
import { pickEntry, publicUrl, toSubs } from './subdl.ts';

// The key SubDL writes into its links must never reach the browser.
assert.equal(
  publicUrl('https://dl.subdl.com/subtitle/1-2.zip?api_key=secret'),
  'https://dl.subdl.com/subtitle/1-2.zip'
);
assert.equal(publicUrl('/subtitle/1-2.zip'), 'https://dl.subdl.com/subtitle/1-2.zip');

// Season 0 is SubDL for "a film", not a special.
assert.equal(toSubs([{ url: '/s.zip', season: 0, episode: null }])[0].season, null);

// A real season pack, as SubDL serves it.
const pack = [
  { name: '00 Breaking Bad, SEASON 1 1080p x265 [23.976 FPS].srt', size: 296 },
  { name: '01 Pilot.en.srt', size: 43070 },
  { name: "02 Cat's in the Bag... .en.srt", size: 32766 },
  { name: '10 Something.en.srt', size: 30000 },
];
assert.equal(pickEntry(pack, 1, 2), "02 Cat's in the Bag... .en.srt");
assert.equal(pickEntry(pack, 1, 1), '01 Pilot.en.srt');
// Unknown episode: biggest file, never the note.
assert.equal(pickEntry(pack), '01 Pilot.en.srt');

const tagged = [
  { name: 'Show.S01E01.srt', size: 10 },
  { name: 'Show.S01E12.srt', size: 10 },
  { name: 'Show.S01E02.srt', size: 10 },
];
assert.equal(pickEntry(tagged, 1, 2), 'Show.S01E02.srt');
assert.equal(pickEntry(tagged, 1, 1), 'Show.S01E01.srt');

console.log('subdl ok');
