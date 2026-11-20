import { spawn } from 'node:child_process'
import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { test } from 'node:test'

test('metadata gateway exposes fixed routes and keeps local media requests out', async () => {
  const port = 21811
  const server = spawn(process.execPath, [resolve('gateway/server.mjs')], {
    env: {
      ...process.env, PORT: String(port), TIGERTV_BIND: '127.0.0.1', TIGERTV_WEB_DIR: resolve('web/dist'),
      TIGERTV_CONFIG: resolve('skills/references/LunaTV-config.json'), TIGERTV_WEB_ORIGIN: 'https://web.example',
    },
    stdio: ['ignore', 'ignore', 'inherit'],
  })
  const base = `http://127.0.0.1:${port}`
  try {
    for (let i = 0; i < 40; i++) {
      if (server.exitCode !== null) throw new Error('Gateway failed to start')
      try { await fetch(base); break } catch { await new Promise(done => setTimeout(done, 50)) }
    }
    const request = (route, options) => fetch(base + route, options)
    const prefix = '/api/metadata/'
    const config = await request(prefix + 'config', { headers: { Origin: 'https://web.example' } })
    assert.equal(config.status, 200)
    assert.equal(config.headers.get('access-control-allow-origin'), 'https://web.example')
    const { api_site } = await config.json()
    assert.ok(Object.keys(api_site).length > 0)
    const key = Object.keys(api_site)[0]
    for (const [url, status] of [
      [prefix + 'detail/unknown?id=1', 404],
      [prefix + `search/${key}?q=show&url=https://example.test/stream.m3u8`, 400],
      [prefix + `detail/${key}?id=1&id=2`, 400],
      [prefix + `detail/${key}?id=2147483648`, 400],
      [prefix + 'hot/tv_domestic?target=https://example.test', 404],
      ['/master.m3u8', 404], ['/assets/part.m4s', 404], ['/key.bin', 404],
    ]) assert.equal((await request(url)).status, status, url)
    assert.equal((await request(prefix + 'config', { method: 'POST' })).status, 405)
    assert.equal((await request(prefix + 'config', { headers: { Origin: 'https://wrong.example' } })).headers.has('access-control-allow-origin'), false)
  } finally {
    server.kill()
  }
})
