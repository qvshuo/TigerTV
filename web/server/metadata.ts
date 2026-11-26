import networkTimeouts from '../../shared/network-timeouts.json' with { type: 'json' }
import { isIP } from 'node:net'
import { object, parseJson, readBounded, readText } from '../common/http.ts'

import { hotLimit, posterRoute } from '../common/douban.ts'

export const metadataPrefix = '/api/metadata/'
const userAgent = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/27.2 Safari/605.1.15'
const categories = new Set(['tv_domestic', 'tv_variety_show', 'tv_american', 'tv_korean', 'tv_japanese', 'tv_animation'])
const cardFields = ['vod_id', 'vod_name', 'vod_pic', 'vod_year', 'vod_remarks', 'type_name']
const detailFields = [...cardFields, 'vod_content', 'vod_area', 'vod_play_from', 'vod_play_url']
const operations = ['ac', 'wd', 'ids', 'pg', 'pagesize']
type Site = { name: string; api: string; gateway: string }

export function loadSources(raw: unknown, subset = '') {
  const sites = new Map<string, Site>()
  const seen = new Set<string>()
  const selected = new Set(subset.split(',').map(key => key.trim()).filter(Boolean))
  for (const [key, value] of Object.entries(object(object(raw).api_site))) {
    const { name, api, ...rest } = object(value)
    if ((selected.size && !selected.has(key)) || !/^[a-z0-9_.-]+$/i.test(key) ||
      typeof name !== 'string' || !name.includes('🎬') || '_comment' in rest || typeof api !== 'string' || seen.has(api)) continue
    const authority = /^https:\/\/([^/?#\\]+)(?:[/?]|$)/i.exec(api)?.[1]
    if (!authority || authority.includes(':')) continue
    let url: URL
    try { url = new URL(api) } catch { continue }
    if (url.username || url.password || url.hash || !url.hostname.includes('.') ||
      isIP(url.hostname) || url.searchParams.has('url')) continue
    sites.set(key, { name, api, gateway: key })
    seen.add(api)
  }
  if (!sites.size) throw new Error('No HTTPS metadata sources configured')
  return sites
}

export async function configuredMetadata() {
  const config = Bun.env.TIGERTV_CONFIG || new URL('../../skills/references/LunaTV-config.json', import.meta.url)
  const sites = loadSources(await Bun.file(config).json(), Bun.env.TIGERTV_SITES)
  return createMetadata(sites, Bun.env.TIGERTV_WEB_ORIGIN)
}

const validNumber = (value: string, max: number) => /^[1-9]\d*$/.test(value) && Number(value) <= max
const pick = (value: Record<string, unknown>, fields: string[]) => Object.fromEntries(fields.map(key => [key, value[key]]))

export function createMetadata(sites: Map<string, Site>, origin = '', requestUpstream = fetch, timeout?: number) {
  let pending = 0
  return async (request: Request): Promise<Response> => {
    const headers = new Headers({ 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' })
    if (origin) {
      headers.set('Vary', 'Origin')
      if (request.headers.get('Origin') === origin) headers.set('Access-Control-Allow-Origin', origin)
    }
    const json = (value: unknown, status = 200) => Response.json(value, { status, headers })
    const fail = (status: number, error: string) => json({ error }, status)
    if (request.method !== 'GET') return fail(405, 'GET required')
    const inbound = new URL(request.url)
    const route = inbound.pathname.slice(metadataPrefix.length)
    if (route === 'config' && !inbound.search) return json({ api_site: Object.fromEntries(sites) })

    const poster = !inbound.search && posterRoute.exec(route)
    const [operation, key, ...rest] = route.split('/')
    if (rest.length && !poster) return fail(404, 'Unknown metadata route')
    let target: URL
    let fields: string[] | undefined
    const upstreamHeaders = new Headers({ 'User-Agent': userAgent })
    const site = sites.get(key)
    if (poster) {
      target = new URL(`https://${poster[1]}.doubanio.com/view/photo/m_ratio_poster/public/${poster[2]}`)
      upstreamHeaders.set('Referer', 'https://m.douban.com/')
    } else if (operation === 'hot' && categories.has(key)) {
      const start = inbound.searchParams.get('start') ?? '0'
      if (inbound.searchParams.size > (inbound.searchParams.has('start') ? 1 : 0) ||
          !/^(0|[1-9]\d{0,4})$/.test(start)) return fail(400, 'Invalid recommendation offset')
      target = new URL(`https://m.douban.com/rexxar/api/v2/subject_collection/${key}/items?start=${start}&count=${hotLimit}`)
      upstreamHeaders.set('Referer', 'https://m.douban.com/tv/')
    } else if (site && (operation === 'search' || operation === 'detail')) {
      if (/;|%(?![0-9a-f]{2})/i.test(inbound.search)) return fail(400, 'Invalid query')
      const params = inbound.searchParams
      if (new Set(params.keys()).size !== params.size) return fail(400, 'Duplicate parameter')
      target = new URL(site.api)
      for (const flag of operations) target.searchParams.delete(flag)
      const query = target.searchParams
      query.set('ac', 'detail')
      if (operation === 'search') {
        const keyword = params.get('q')?.trim()
        const page = params.get('page') ?? '1'
        if (!keyword || keyword.length > 120 || !validNumber(page, 100) ||
          [...params.keys()].some(name => name !== 'q' && name !== 'page')) return fail(400, 'Invalid search')
        query.set('wd', keyword)
        query.set('pg', page)
        query.set('pagesize', '100')
        fields = cardFields
      } else {
        const id = params.get('id') ?? ''
        if (params.size !== 1 || !validNumber(id, 2147483647)) return fail(400, 'Invalid ID')
        query.set('ids', id)
        fields = detailFields
      }
    } else return fail(404, 'Unknown metadata route')

    if (pending >= 20) return fail(503, 'Metadata busy')
    pending++
    try {
      const response = await requestUpstream(target, {
        headers: upstreamHeaders, redirect: 'error',
        signal: AbortSignal.any([request.signal, AbortSignal.timeout(timeout ?? (operation === 'hot' ? networkTimeouts.recommendationRequestMs : networkTimeouts.catalogRequestMs))]),
      })
      if (poster) {
        if (response.headers.get('Content-Type')?.split(';', 1)[0].toLowerCase() !== 'image/jpeg') {
          void response.body?.cancel().catch(() => {})
          return fail(502, 'Upstream poster unavailable')
        }
        const chunks: BlobPart[] = []
        const image = await readBounded(response, 2 * 1024 * 1024, bytes => { chunks.push(Uint8Array.from(bytes)) },
          () => new Blob(chunks, { type: 'image/jpeg' }))
        headers.set('Cache-Control', 'public, max-age=86400')
        return new Response(image, { headers })
      }
      const text = await readText(response)
      if (fields) {
        const data = object(parseJson(text))
        if (data.code == null) throw new Error('Invalid catalog response')
        const list = Array.isArray(data.list) ? data.list.slice(0, 100)
          .flatMap(row => row && typeof row === 'object' && !Array.isArray(row) ? [pick(object(row), fields)] : []) : []
        return json({ code: data.code, pagecount: data.pagecount, list })
      }
      const { subject_collection_items: rows, total } = object(JSON.parse(text))
      if (!Array.isArray(rows)) throw new Error('Invalid recommendation response')
      return json({ total, subject_collection_items: rows.slice(0, hotLimit).map(value => {
        const row = object(value)
        return { id: row.id, title: row.title, year: row.year, pic: { large: object(row.pic).large }, rating: { value: object(row.rating).value } }
      }) })
    } catch {
      return fail(502, 'Upstream metadata unavailable')
    } finally {
      pending--
    }
  }
}
