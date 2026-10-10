import { defineConfig, loadEnv } from 'vite';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Writes dist/sw.js from src/sw-template.js after the bundle is emitted,
 * injecting the exact list of hashed build files to precache and a version
 * derived from their contents. Only same-origin app-shell files are listed:
 * no API responses, no user data, no auth traffic.
 */
function serviceWorkerPrecache(apiBase) {
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
        .replace('__BUILD_VERSION__', JSON.stringify(version))
        .replace('__API_ORIGIN__', JSON.stringify(apiBase ? new URL(apiBase).origin : null));
      writeFileSync(resolve(outDir, 'sw.js'), out);
    },
  };
}

/**
 * Android build only: the APK can't read netlify.toml headers, so put the
 * same Content-Security-Policy in a <meta> tag. Derived from netlify.toml so
 * there is one policy to edit. The API origin is added where the app loads
 * from it; frame-ancestors and upgrade-insecure-requests are dropped because
 * they don't work in a meta tag. 'self' is https://localhost inside the app.
 */
export function androidCsp(apiBase) {
  const toml = readFileSync(resolve('netlify.toml'), 'utf8');
  const policy = /Content-Security-Policy = "([^"]+)"/.exec(toml)[1];
  const api = new URL(apiBase).origin;
  return policy.split(';').map((d) => d.trim()).filter(Boolean)
    .filter((d) => !/^(frame-ancestors|upgrade-insecure-requests)\b/.test(d))
    .map((d) => (/^(connect-src|img-src|media-src)\s/.test(d) ? `${d} ${api}` : d))
    .join('; ');
}

function androidCspMeta(mode, env) {
  return {
    name: 'mavis-android-csp',
    apply: 'build',
    transformIndexHtml(html) {
      if (mode !== 'android') return html;
      if (!env.VITE_API_BASE) throw new Error('Android build needs VITE_API_BASE (see .env.android).');
      const meta = `<meta http-equiv="Content-Security-Policy" content="${androidCsp(env.VITE_API_BASE)}" />`;
      // Right after the charset, before any script or stylesheet.
      const out = html.replace(/(<meta charset="utf-8" \/>)/, `$1\n    ${meta}`);
      if (out === html) throw new Error('index.html: charset meta not found for the Android CSP.');
      return out;
    },
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  return {
    plugins: [serviceWorkerPrecache(env.VITE_API_BASE), androidCspMeta(mode, env)],
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
  };
});
