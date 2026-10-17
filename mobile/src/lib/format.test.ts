// node --experimental-strip-types src/lib/format.test.ts
import assert from 'node:assert';
import { ago, clock, left, speed } from './format.ts';

assert.equal(clock(0), '0:00');
assert.equal(clock(75), '1:15');
assert.equal(clock(3675), '1:01:15');
assert.equal(clock(-5), '0:00');

assert.equal(speed(2_500_000), '2.5 MB/s');
assert.equal(speed(45_000), '45 kB/s');

assert.equal(left(20), 'almost done');
assert.equal(left(640), '11m left');
assert.equal(left(3600), '1h 0m left');
assert.equal(left(7500), '2h 5m left');
assert.equal(left(-5), 'almost done');

assert.equal(ago(Date.now()), 'just now');
assert.equal(ago(Date.now() - 90 * 60_000), '1h ago');
assert.equal(ago(Date.now() - 3 * 1440 * 60_000), '3d ago');

console.log('ok');
