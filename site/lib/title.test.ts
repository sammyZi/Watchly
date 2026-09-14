// node --experimental-strip-types site/lib/title.test.ts
import assert from 'node:assert';
import { searchableName } from './title.ts';

assert.equal(searchableName('The.Matrix.1999.1080p.BluRay.x264-GRP'), 'The Matrix');
assert.equal(searchableName('Inception (2010) [1080p]'), 'Inception');
assert.equal(searchableName('www.UIndex.org    -    Andor S01E03 1080p WEB h264'), 'Andor');
assert.equal(searchableName('[TGx] Dune Part Two 2024 2160p'), 'Dune Part Two');
assert.equal(searchableName('www.1TamilMV.cc - Leo (2023) Tamil HQ'), 'Leo');

console.log('title ok');
