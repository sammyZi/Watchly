import { test } from 'node:test';
import assert from 'node:assert/strict';
import { trackForLang } from './lang.ts';

const tracks = [
  { id: 1, name: 'Forced - [English]' },
  { id: 2, name: 'Track 2 - [Italian]' },
  { id: 3, name: 'SDH - [English]' },
  { id: 4, name: 'Commentary - it is fun' },
];

test('prefers a full track over a forced one', () => {
  assert.equal(trackForLang(tracks, 'EN')?.id, 3);
});

test('falls back to forced when it is all there is', () => {
  assert.equal(trackForLang(tracks.slice(0, 2), 'EN')?.id, 1);
});

test('matches by name, not by the word "it"', () => {
  assert.equal(trackForLang(tracks, 'IT')?.id, 2);
  assert.equal(trackForLang([tracks[3]], 'IT'), null);
});

test('three-letter codes and unknown languages', () => {
  assert.equal(trackForLang([{ name: 'ger' }], 'DE')?.name, 'ger');
  assert.equal(trackForLang(tracks, 'XX'), null);
});
