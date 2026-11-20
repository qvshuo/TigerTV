import { createServer } from 'node:http'
import { readFile, readdir } from 'node:fs/promises'
import { isIP } from 'node:net'
import { extname, join } from 'node:path'

const maxBytes = 10 * 1024 * 1024
const maxRequests = 20
const deadline = 12_000
const upstreamAgent = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/27.2 Safari/605.1.15'
const operations = ['ac', 'wd', 'ids', 'pg', 'pagesize']
const categories = new Set(['tv_domestic', 'tv_variety_show', 'tv_american', 'tv_korean', 'tv_japanese', 'tv_animation'])
const webOrigin = process.env.TIGERTV_WEB_ORIGIN || ''
const directory = process.env.TIGERTV_WEB_DIR || '/app/web'
const configuration = JSON.parse(await readFile(process.env.TIGERTV_CONFIG || '/app/sources.json', 'utf8'))
const allowed = Object.create(null)
const seen = new Set()
const selected = new Set((process.env.TIGERTV_SITES || '').split(',').map(value => value.trim()).filter(Boolean))
for (const [name, entry] of Object.entries(configuration.api_site ?? {})) {
  if ((selected.size && !selected.has(name)) || !/^[a-z0-9_.-]+$/i.test(name) || !entry?.name?.includes('🎬') || '_comment' in entry || !entry.api) continue
  let url
  try { url = new URL(entry.api) } catch { continue }
  if (url.protocol !== 'https:' || url.username || url.password || url.hash ||
    url.port || !url.hostname.includes('.') || isIP(url.hostname) || url.searchParams.has('url') ||
    seen.has(entry.api)) continue
  allowed[name] = { name: entry.name, api: entry.api, gateway: name }
  seen.add(entry.api)
}
if (!seen.size) throw new Error('No HTTPS metadata sources configured')

const mime = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.webmanifest': 'application/manifest+json', '.png': 'image/png',
}
const files = new Map()
for (const [base, prefix] of [[directory, '/'], [join(directory, 'assets'), '/assets/']]) {
  for (const item of await readdir(base, { withFileTypes: true })) {
    const contentType = mime[extname(item.name)]
    if (item.isFile() && contentType) files.set(prefix + item.name, { path: join(base, item.name), contentType })
  }
}
files.set('/', files.get('/index.html'))
const commonFields = ['vod_id', 'vod_name', 'vod_pic', 'vod_year', 'vod_remarks']
const detailFields = [...commonFields, 'vod_content', 'vod_play_from', 'vod_play_url']
let pending = 0

function json(response, status, body) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
  response.end(JSON.stringify(body))
}

async function upstream(url, signal, referer) {
  const response = await fetch(url, {
    redirect: 'error', signal, headers: { 'User-Agent': upstreamAgent, ...(referer ? { Referer: referer } : {}) },
  })
  if (!response.ok || Number(response.headers.get('Content-Length')) > maxBytes) {
    await response.body?.cancel()
    throw new Error('Metadata response rejected')
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let size = 0
  let body = ''
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      size += value.length
      if (size > maxBytes) throw new Error('Metadata response too large')
      body += decoder.decode(value, { stream: true })
    }
    return JSON.parse(body + decoder.decode(), (key, value, context) =>
      typeof value === 'number' && operationsForNumbers.has(key) ? (context?.source ?? value) : value)
  } finally {
    void reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
const operationsForNumbers = new Set(['code', 'pagecount', 'vod_id'])
const numericId = value => /^[1-9]\d{0,9}$/.test(value ?? '') && Number(value) <= 2147483647
const metadataHeaders = { 'X-Content-Type-Options': 'nosniff', 'Cross-Origin-Resource-Policy': 'same-origin' }

async function handle(request, response) {
  if (request.method !== 'GET') return json(response, 405, { error: 'GET required' })
  const url = new URL(request.url, 'http://localhost')
  if (!url.pathname.startsWith('/api/metadata/')) {
    const asset = files.get(url.pathname)
    if (!asset) return json(response, 404, { error: 'Unknown route' })
    response.writeHead(200, {
      ...metadataHeaders, 'Content-Type': asset.contentType,
      'Cache-Control': url.pathname.startsWith('/assets/') ? 'public, max-age=31536000, immutable' : 'no-cache',
    })
    return response.end(await readFile(asset.path))
  }

  if (webOrigin && request.headers.origin === webOrigin) {
    response.setHeader('Access-Control-Allow-Origin', webOrigin)
    response.setHeader('Vary', 'Origin')
  }
  response.setHeader('X-Content-Type-Options', 'nosniff')
  const route = url.pathname.slice('/api/metadata/'.length)
  if (route === 'config' && !url.search) return json(response, 200, { api_site: allowed })

  const [operation, key, ...rest] = route.split('/')
  const params = url.searchParams
  let target
  let referer
  let fields
  if (!rest.length && operation === 'hot' && categories.has(key) && !url.search) {
    target = new URL(`https://m.douban.com/rexxar/api/v2/subject_collection/${key}/items?start=0&count=12`)
    referer = 'https://m.douban.com/tv/'
  } else if (!rest.length && Object.hasOwn(allowed, key) && (operation === 'search' || operation === 'detail')) {
    if ([...params.keys()].length !== new Set(params.keys()).size) return json(response, 400, { error: 'Duplicate parameter' })
    target = new URL(allowed[key].api)
    for (const flag of operations) target.searchParams.delete(flag)
    target.searchParams.set('ac', 'detail')
    fields = detailFields
    if (operation === 'search') {
      const query = params.get('q')?.trim()
      const pg = params.get('page') ?? '1'
      if (!query || query.length > 120 || !/^[1-9][0-9]{0,2}$/.test(pg) || Number(pg) > 100 ||
        [...params.keys()].some(name => name !== 'q' && name !== 'page')) return json(response, 400, { error: 'Invalid search' })
      target.searchParams.set('wd', query)
      target.searchParams.set('pg', pg)
      target.searchParams.set('pagesize', '100')
      fields = commonFields
    } else {
      const id = params.get('id')
      if (!numericId(id) || params.size !== 1) return json(response, 400, { error: 'Invalid ID' })
      target.searchParams.set('ids', id)
    }
  } else return json(response, 404, { error: 'Unknown metadata route' })

  if (pending >= maxRequests) return json(response, 503, { error: 'Metadata busy' })
  pending++
  const cancel = new AbortController()
  response.once('close', () => cancel.abort())
  try {
    const result = await upstream(target, AbortSignal.any([AbortSignal.timeout(deadline), cancel.signal]), referer)
    if (fields) {
      if (result?.code == null) throw new Error('Invalid metadata response')
      const list = (Array.isArray(result.list) ? result.list.slice(0, 100) : []).filter(row => row && typeof row === 'object')
        .map(row => Object.fromEntries(fields.map(field => [field, row[field]])))
      return json(response, 200, { code: result.code, pagecount: result.pagecount, list })
    }
    if (!Array.isArray(result?.subject_collection_items)) throw new Error('Invalid Douban metadata')
    const items = result.subject_collection_items.slice(0, 12).map(row => ({
      id: row.id, title: row.title, year: row.year, pic: { large: row.pic?.large }, rating: { value: row.rating?.value },
    }))
    return json(response, 200, { subject_collection_items: items })
  } catch {
    if (!cancel.signal.aborted) json(response, 502, { error: 'Upstream metadata unavailable' })
  } finally {
    pending--
  }
}

const server = createServer({ requestTimeout: 15_000, headersTimeout: 10_000, maxHeaderSize: 8192 }, (request, response) => {
  void handle(request, response).catch(() => {
    if (response.headersSent) response.destroy()
    else json(response, 400, { error: 'Invalid request' })
  })
})
server.listen(Number(process.env.PORT || '8080'), process.env.TIGERTV_BIND || '0.0.0.0')
