import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseBackup } from './backup.ts';

test('keeps known keys holding JSON, drops the rest', () => {
  const text = JSON.stringify({
    app: 'watchly',
    version: 1,
    data: { saved: '[]', settings: '{bad', 'torrent-downloads': '[]', evil: '1' },
  });
  assert.deepEqual(parseBackup(text), [['saved', '[]']]);
});

test('rejects anything that is not a backup', () => {
  assert.throws(() => parseBackup('not json'));
  assert.throws(() => parseBackup('{"app":"other","data":{}}'));
  assert.throws(() => parseBackup('{"app":"watchly","data":{"saved":"{bad"}}'));
});
