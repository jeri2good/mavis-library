import { defineConfig } from 'vite';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Writes dist/sw.js from src/sw-template.js after the bundle is emitted,
 * injecting the exact list of hashed build files to precache and a version
 * derived from their contents. Only same-origin app-shell files are listed:
 * no API responses, no user data, no auth traffic.
 */
function serviceWorkerPrecache() {
  let outDir = 'dist';
  let precache = { files: [], version: 'dev' };
  return {
    name: 'mavis-sw-precache',
    apply: 'build',
    configResolved(cfg) { outDir = cfg.build.outDir; },
    generateBundle(_opts, bundle) {
      const files = Object.keys(bundle).filter((f) => {
        if (f.endsWith('.map')) return false;
        // Keep only the Latin font subsets the app actually uses offline.
        if (f.endsWith('.woff2') || f.endsWith('.woff')) return /latin-wght|latin-soft/.test(f) && !/-ext-/.test(f);
        return true;
      });
      const hash = createHash('sha256');
      for (const f of files.sort()) {
        const item = bundle[f];
        hash.update(f);
        hash.update(item.type === 'chunk' ? item.code : item.source);
      }
      precache = { files, version: hash.digest('hex').slice(0, 12) };
    },
    writeBundle() {
      const { files, version } = precache;
      const staticFiles = ['./', 'index.html', 'manifest.webmanifest', 'icons/icon-192.png', 'icons/icon-512.png', 'icons/maskable-512.png', 'icons/icon.svg'];
      const list = [...new Set([...staticFiles, ...files.map((f) => f)])];
      const template = readFileSync(resolve('src/sw-template.js'), 'utf8');
      const out = template
        .replace('__PRECACHE_LIST__', JSON.stringify(list))
        .replace('__BUILD_VERSION__', JSON.stringify(version));
      writeFileSync(resolve(outDir, 'sw.js'), out);
    },
  };
}

export default defineConfig({
  plugins: [serviceWorkerPrecache()],
  build: {
    target: ['es2020', 'chrome80', 'safari14', 'firefox80'],
    sourcemap: false,
    chunkSizeWarningLimit: 900,
  },
  server: {
    // `netlify dev` serves functions on :8888 and proxies Vite; when running
    // plain `vite`, forward /api to a running `netlify functions:serve`.
    proxy: { '/api': { target: 'http://localhost:9999', changeOrigin: true } },
  },
});
