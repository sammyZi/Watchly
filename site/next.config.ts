import type { NextConfig } from 'next';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const nextConfig: NextConfig = {
  // This folder lives inside a larger repo with its own lockfile; pin the
  // Turbopack root so module resolution never walks up into the Expo app.
  turbopack: {
    root: join(dirname(fileURLToPath(import.meta.url))),
  },
  // Browser console output in the terminal beside the server logs. The default
  // is 'warn', which silently dropped every console.log the engine and player
  // emit — the whole point of having them.
  logging: {
    browserToTerminal: true,
  },
  // The torrent engine (lib/engine.ts) runs in Node with native deps
  // (node-datachannel, utp-native); load it from node_modules, never bundle it.
  serverExternalPackages: ['webtorrent', 'webrtc-polyfill', 'node-datachannel', 'utp-native'],
};

export default nextConfig;