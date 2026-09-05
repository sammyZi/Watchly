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

// A host that accepts the socket and then says nothing must not hang forever.
// The request is driven by a signal of net's own — the one the timeout fires —
// with the caller's abort relayed onto it. Aborting from outside exercises that
// same wiring without sitting out the fifteen seconds.
const sent: { signal: AbortSignal | null } = { signal: null };
stub(
  ((_url: string, init: RequestInit) =>
    new Promise((_, reject) => {
      sent.signal = init.signal ?? null;
      init.signal?.addEventListener('abort', () =>
        reject(new DOMException('Aborted', 'AbortError'))
      );
    })) as unknown as typeof fetch
);
const outer = new AbortController();
const hung = assert.rejects(() => net('https://x/hang', { signal: outer.signal }, true));
assert.ok(sent.signal, 'net must drive fetch with a signal of its own');
assert.notEqual(sent.signal, outer.signal);
outer.abort();
await hung;
assert.equal(isOnline(), true);

globalThis.fetch = real;
console.log('ok');
