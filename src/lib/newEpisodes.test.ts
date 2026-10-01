import { test } from 'node:test';
import assert from 'node:assert/strict';
import { compare } from './newEpisodes.ts';
import type { Title } from './meta.ts';

const show = (imdb: string, dates: (string | null)[]): Title =>
  ({
    imdb,
    kind: 'tv',
    episodes: dates.map((released, i) => ({
      id: `${imdb}:1:${i + 1}`,
      season: 1,
      episode: i + 1,
      name: '',
      overview: null,
      thumbnail: null,
      released,
    })),
  }) as Title;

const now = Date.parse('2026-10-01');

test('first sighting is a baseline, not news', () => {
  const { fresh, next } = compare([show('tt1', ['2026-01-01', '2026-02-01'])], {}, now);
  assert.deepEqual(fresh, []);
  assert.equal(next.tt1, 2);
});

test('counts only aired episodes past the baseline', () => {
  const t = show('tt1', ['2026-01-01', '2026-09-01', '2026-09-20', '2026-12-01', null]);
  const { fresh } = compare([t], { tt1: 1 }, now);
  assert.equal(fresh.length, 1);
  assert.equal(fresh[0].count, 2);
});

test('a title with no episode list is skipped', () => {
  const { fresh, next } = compare([show('tt2', [])], {}, now);
  assert.deepEqual(fresh, []);
  assert.equal(next.tt2, undefined);
});
