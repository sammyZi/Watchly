// node --experimental-strip-types src/lib/net.test.ts
import assert from 'node:assert';
import { isOnline, net } from './net.ts';

const real = globalThis.fetch;
const stub = (impl: typeof fetch) => (globalThis.fetch = impl as any);

// A status — any status — means the network is up.
stub(async () => new Response('', { status: 503 }));
assert.equal((await net('https://x/a')).status, 503);
assert.equal(isOnline(), true);

// A throw is the offline signal.
stub(async () => {
  throw new TypeError('Network request failed');
});
await assert.rejects(() => net('https://x/a'));
assert.equal(isOnline(), false);

// A request we cancelled ourselves is not evidence of anything.
stub(async () => new Response('', { status: 200 }));
await net('https://x/a');
assert.equal(isOnline(), true);
const ctrl = new AbortController();
ctrl.abort();
stub(async () => {
  throw new DOMException('Aborted', 'AbortError');
});
await assert.rejects(() => net('https://x/a', { signal: ctrl.signal }));
assert.equal(isOnline(), true);

globalThis.fetch = real;
console.log('ok');
