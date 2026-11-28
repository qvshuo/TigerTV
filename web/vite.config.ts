import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { defineConfig, type Plugin } from 'vite'
import { svelte, vitePreprocess } from '@sveltejs/vite-plugin-svelte'
import tailwindcss from '@tailwindcss/vite'

function developmentMetadata(): Plugin {
  return {
    name: 'tigertv-development-metadata',
    apply: 'serve',
    async configureServer(server) {
      const { configuredMetadata, metadataPrefix } = await import('./server/metadata.ts')
      const metadata = await configuredMetadata()
      server.middlewares.use(async (request, response, next) => {
        if (!request.url?.startsWith(metadataPrefix)) return next()
        const cancel = new AbortController()
        response.once('close', () => cancel.abort())
        try {
          const result = await metadata(new Request(new URL(request.url, 'http://localhost'), {
            method: request.method, headers: request.headers.origin ? { Origin: request.headers.origin } : undefined,
            signal: cancel.signal,
          }))
          response.writeHead(result.status, Object.fromEntries(result.headers))
          response.end(Buffer.from(await result.arrayBuffer()))
        } catch (error) { next(error) }
      })
    },
  }
}

function appShell(): Plugin {
  return {
    name: 'tigertv-app-shell',
    apply: 'build',
    enforce: 'post',
    generateBundle(_, bundle) {
      const shellAssets = new Set(Object.keys(bundle).filter(file => file.endsWith('.css')))
      function include(file: string) {
        const chunk = bundle[file]
        if (chunk?.type !== 'chunk' || shellAssets.has(file)) return
        shellAssets.add(file)
        for (const dependency of chunk.imports) include(dependency)
      }
      for (const chunk of Object.values(bundle)) {
        if (chunk.type === 'chunk' && chunk.isEntry) include(chunk.fileName)
      }
      const assets = [...shellAssets].sort()
      const publicFiles = ['manifest.webmanifest', 'icon-192.png', 'icon-512.png']
      const hash = createHash('sha256').update(JSON.stringify(assets))
      for (const path of ['index.html', ...publicFiles.map(file => `public/${file}`)]) {
        hash.update(readFileSync(new URL(path, import.meta.url)))
      }
      const revision = hash.digest('hex').slice(0, 12)
      const files = ['index.html', ...publicFiles, ...assets]
      this.emitFile({ type: 'asset', fileName: 'sw.js', source: `
const prefix = 'tigertv-shell-' + self.registration.scope + '-';
const name = prefix + '${revision}';
const shell = new URL('index.html', self.registration.scope).href;
const files = ${JSON.stringify(files)}.map(path => new URL(path, self.registration.scope).href);
self.addEventListener('install', event => {
  event.waitUntil(caches.open(name).then(cache => cache.addAll(files.map(url => new Request(url, { cache: 'reload' })))));
});
self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key.startsWith(prefix) && key !== name).map(key => caches.delete(key)))));
});
self.addEventListener('fetch', event => {
  const { request } = event;
  if (request.method !== 'GET' || request.url === self.location.href) return;
  const url = new URL(request.url);
  const scope = new URL(self.registration.scope);
  if (url.origin !== scope.origin) return;
  if (request.mode === 'navigate' && (url.pathname === scope.pathname || request.url === shell)) {
    event.respondWith(caches.open(name).then(cache => cache.match(shell)).then(response => response || fetch(request)));
  } else if (files.includes(request.url)) {
    event.respondWith(caches.open(name).then(cache => cache.match(request)).then(response => response || fetch(request)));
  }
});`
      })
    },
  }
}

export default defineConfig({
  base: './',
  // The full HLS engine is loaded on demand and currently builds to 578 kB.
  build: { chunkSizeWarningLimit: 600 },
  plugins: [svelte({ configFile: false, preprocess: vitePreprocess() }), tailwindcss(), appShell(), developmentMetadata()],
})
