import { readdir } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { configuredMetadata, metadataPrefix } from './metadata.ts'

const dist = Bun.env.TIGERTV_WEB_DIR || fileURLToPath(new URL('../dist/', import.meta.url))
const assetTypes = new Map([
  ['.html', 'text/html; charset=utf-8'], ['.js', 'text/javascript; charset=utf-8'],
  ['.css', 'text/css; charset=utf-8'], ['.webmanifest', 'application/manifest+json'], ['.png', 'image/png'],
])
const assets = new Map<string, { file: ReturnType<typeof Bun.file>; headers: Record<string, string> }>()
for (const folder of ['', 'assets/']) {
  for (const entry of await readdir(join(dist, folder), { withFileTypes: true })) {
    const type = assetTypes.get(entry.name.slice(entry.name.lastIndexOf('.')))
    if (!entry.isFile() || !type) continue
    assets.set('/' + folder + entry.name, {
      file: Bun.file(join(dist, folder, entry.name)),
      headers: {
        'Content-Type': type,
        'Cache-Control': folder ? 'public, max-age=31536000, immutable' : 'no-cache',
        'Cross-Origin-Resource-Policy': 'same-origin',
        'X-Content-Type-Options': 'nosniff',
      },
    })
  }
}
if (!assets.has('/index.html')) throw new Error('Build Web before starting TigerTV')
const metadata = await configuredMetadata()
const server = Bun.serve({
  hostname: Bun.env.TIGERTV_BIND || '0.0.0.0',
  port: Number(Bun.env.PORT || 8080),
  idleTimeout: 15,
  maxRequestBodySize: 8192,
  development: false,
  fetch(request) {
    const path = new URL(request.url).pathname
    if (path.startsWith(metadataPrefix)) return metadata(request)
    if (request.method !== 'GET') return Response.json({ error: 'GET required' }, { status: 405 })
    const asset = assets.get(path === '/' ? '/index.html' : path)
    return asset ? new Response(asset.file, { headers: asset.headers }) : Response.json({ error: 'Unknown route' }, { status: 404 })
  },
})
console.info(`TigerTV listening on ${server.hostname}:${server.port}`)
