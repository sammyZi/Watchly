// node --experimental-strip-types site/lib/auth.test.ts
import assert from 'node:assert';
import { lockedOut, passwordMatches, recordFailure, sessionToken, tokenValid } from './auth.ts';

delete process.env.SITE_PASSWORD;
// No password configured: the site stays open.
assert.equal(tokenValid(undefined), true);

process.env.SITE_PASSWORD = 'correct horse';
assert.equal(passwordMatches('correct horse'), true);
assert.equal(passwordMatches('correct hors'), false);
assert.equal(passwordMatches(''), false);

assert.equal(tokenValid(undefined), false);
assert.equal(tokenValid('nope'), false);
assert.equal(tokenValid(sessionToken('correct horse')), true);
// A new password signs old sessions out.
const old = sessionToken('correct horse');
process.env.SITE_PASSWORD = 'battery staple';
assert.equal(tokenValid(old), false);

// Five failures lock an address; another address is unaffected.
for (let i = 0; i < 4; i++) recordFailure('1.2.3.4');
assert.equal(lockedOut('1.2.3.4'), false);
recordFailure('1.2.3.4');
assert.equal(lockedOut('1.2.3.4'), true);
assert.equal(lockedOut('5.6.7.8'), false);

console.log('auth ok');
