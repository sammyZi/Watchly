/**
 * Copies the ffmpeg WASM runtime out of node_modules into public/. Run after install (npm run setup) — the assets
 * are too large (30MB+ of wasm) to commit.
 */
import { copyFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const from = (...p) => join(root, 'node_modules', ...p);
const to = (...p) => join(root, 'public', ...p);

const copies = [
  [from('@ffmpeg', 'core', 'dist', 'esm', 'ffmpeg-core.js'), to('ffmpeg', 'ffmpeg-core.js')],
  [from('@ffmpeg', 'core', 'dist', 'esm', 'ffmpeg-core.wasm'), to('ffmpeg', 'ffmpeg-core.wasm')],
  [from('@ffmpeg', 'ffmpeg', 'dist', 'esm', 'worker.js'), to('ffmpeg', 'worker.js')],
  [from('@ffmpeg', 'ffmpeg', 'dist', 'esm', 'const.js'), to('ffmpeg', 'const.js')],
  [from('@ffmpeg', 'ffmpeg', 'dist', 'esm', 'errors.js'), to('ffmpeg', 'errors.js')],
];

for (const [src, dest] of copies) {
  if (!existsSync(src)) {
    console.error(`missing ${src} — run npm install first`);
    process.exitCode = 1;
    continue;
  }
  mkdirSync(dirname(dest), { recursive: true });
  copyFileSync(src, dest);
  console.log(`copied ${src} -> ${dest}`);
}