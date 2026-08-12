import type { NextConfig } from 'next';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const nextConfig: NextConfig = {
  // This folder lives inside a larger repo with its own lockfile; pin the
  // Turbopack root so module resolution never walks up into the Expo app.
  turbopack: {
    root: join(dirname(fileURLToPath(import.meta.url))),
    // The package's `main` is the Node build, and its `browser` field only maps
    // `os`, `fs` and friends to `false` — it never swaps the entry point. So a
    // browser bundle got the Node entry with `os` stubbed out and died on
    // `os.tmpdir()` before the page rendered. `dist/webtorrent.min.js` is the
    // prebuilt browser bundle (ESM, default export); the condition keeps the
    // server bundle on the original.
    resolveAlias: {
      webtorrent: { browser: 'webtorrent/dist/webtorrent.min.js' },
    },
  },
  // Browser console output in the terminal beside the server logs. The default
  // is 'warn', which silently dropped every console.log the engine and player
  // emit — the whole point of having them.
  logging: {
    browserToTerminal: true,
  },
  // WebTorrent's browser build pulls node-datachannel (a native/WASM module).
  // It is only used client-side, so the server bundle must not try to inline
  // its assets into an ESM chunk.
  serverExternalPackages: ['webtorrent', 'webrtc-polyfill', 'node-datachannel'],
};

export default nextConfig;