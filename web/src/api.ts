import doubanCategories from '../../shared/douban-categories.json' with { type: 'json' }
import networkTimeouts from '../../shared/network-timeouts.json' with { type: 'json' }
import { object, parseJson, readText } from '../common/http.ts'
import { hotLimit, posterKey } from '../common/douban.ts'
export { object, parseJson }

export type Site = { name: string; api: string; gateway?: string }
export const displaySourceName = (name: string) => name.replaceAll("🎬", "").trim()
export type Source = { site: Site; id: number; title: string; year: string; poster: string; remarks: string; category?: string; area?: string }
export type Work = { id: string; display: Source; sources: Source[] }
export type Episode = { name: string; url: string }
export type Line = { id: string; name: string; episodes: Episode[] }
export type Detail = { source: Source; overview: string; lines: Line[] }
export type HotItem = { id: string; title: string; year: string; poster: string; rating: number }

function gatewayUrl(route: string, params?: Record<string, string>) {
  const result = new URL('./api/metadata/' + route, document.baseURI)
  result.search = new URLSearchParams(params).toString()
  return result.href
}

export function webUrl(value: string): string {
  try {
    const url = new URL(value.startsWith('//') ? 'https:' + value : value)
    if (url.protocol === 'https:' || (url.protocol === 'http:' && location.protocol === 'http:')) return url.href
  } catch { }
  return ''
}

export async function getJson(url: string, signal?: AbortSignal, timeout = 10_000, parse: (text: string) => unknown = parseJson): Promise<unknown> {
  const allowed = webUrl(url)
  if (!allowed) throw new Error('来源需要 HTTPS')
  const deadline = AbortSignal.timeout(timeout)
  const response = await fetch(allowed, {
    signal: signal ? AbortSignal.any([signal, deadline]) : deadline,
    credentials: 'omit',
    referrerPolicy: 'no-referrer',
  })
  return parse(await readText(response))
}

export function scalar(raw: unknown): string {
  return typeof raw === 'string' || typeof raw === 'number' || typeof raw === 'boolean' ? String(raw) : ''
}
export function integer(raw: unknown): number {
  const value = scalar(raw).trim()
  const match = /^([+-]?)(?:(\d+)(?:\.(\d*))?|\.(\d+))(?:e([+-]?\d+))?$/i.exec(value)
  if (!match) throw new Error('无效整数')
  const fraction = match[3] || match[4] || ''
  const digits = ((match[2] ?? '') + fraction).replace(/^0+/, '')
  if (!digits) return 0
  const shift = Number(match[5] ?? 0) - fraction.length
  const count = digits.length + shift
  if (!Number.isSafeInteger(shift) || count <= 0 || count > 10 || (shift < 0 && /[1-9]/.test(digits.slice(count)))) {
    throw new Error('无效整数')
  }
  const result = Number(match[1] + digits.slice(0, count).padEnd(count, '0'))
  if (result < -2147483648 || result > 2147483647) throw new Error('整数超出范围')
  return result
}

type GatewaySite = Site & { gateway: string }

export function parseConfig(raw: unknown): GatewaySite[] {
  const sites = new Map<string, GatewaySite>()
  for (const [key, value] of Object.entries(object(object(raw).api_site))) {
    const entry = object(value)
    const name = scalar(entry.name)
    const api = scalar(entry.api)
    if (name.includes('🎬') && !('_comment' in entry) && api && !sites.has(api) &&
      /^[a-z0-9_.-]+$/i.test(key) && (entry.gateway === undefined || entry.gateway === key)) sites.set(api, { name, api, gateway: key })
  }
  if (!sites.size) throw new Error('找不到可用资源站')
  return [...sites.values()]
}

let config: Promise<GatewaySite[]> | undefined
let configExpires = 0
export function catalogSites(): Promise<GatewaySite[]> {
  if (config && Date.now() >= configExpires) config = undefined
  if (!config) configExpires = Date.now() + 24 * 3600_000
  return config ??= getJson(gatewayUrl('config'), undefined, networkTimeouts.configRequestMs).then(parseConfig)
    .catch(error => { config = undefined; throw error })
}

function sourceFrom(site: Site, value: unknown): Source {
  const row = object(value)
  const id = integer(row.vod_id)
  if (id <= 0) throw new Error('来源没有有效 ID')
  return {
    site, id, title: scalar(row.vod_name), year: scalar(row.vod_year),
    poster: webUrl(scalar(row.vod_pic)), remarks: scalar(row.vod_remarks),
    category: scalar(row.type_name), area: scalar(row.vod_area),
  }
}

function workIdentity(title: string, year: string, source: Source) {
  const text = title.replace(/[\s\u001c-\u001f]/gu, '').toLowerCase()
  return JSON.stringify(text && year ? [text, year] : [null, `${source.site.name}-${source.id}`])
}
export function foldWidth(text: string) {
  return text.replace(/[\u3000\uff01-\uff5e]/g, char => char === '\u3000' ? ' ' : String.fromCharCode(char.charCodeAt(0) - 0xfee0))
}
export function workId(source: Source) {
  const title = foldWidth(source.title).trim()
  const primary = title.replace(/[（(【](?:又名|别名|原名|英文名|aka)\s*[:：]\s*[^)）】]+[)）】]\s*$/i, '') || title
  const rawYear = foldWidth(source.year).trim()
  const year = /^((?:19|20)[0-9]{2})\s*年?$/.exec(rawYear)?.[1] ?? ''
  const tag = /[（(【]((?:19|20)[0-9]{2})[)）】]\s*$/.exec(primary)
  if (tag && (!year || tag[1] === year)) {
    const main = primary.slice(0, tag.index).trimEnd()
    if (main) return workIdentity(main, tag[1], source)
  }
  return workIdentity(primary, year, source)
}
function vodPage(value: unknown) {
  const page = object(value)
  if (integer(page.code) !== 1) throw new Error('来源返回搜索错误')
  if (page.list != null && !Array.isArray(page.list)) throw new Error('来源返回无效列表')
  return { rows: (page.list ?? []) as unknown[], pages: 'pagecount' in page ? integer(page.pagecount) : 1 }
}

const searchCache = new Map<string, { expires: number; works: Work[]; sites: Set<string> }>()
const searchCacheTtl = 10 * 60_000

export async function search(query: string, signal: AbortSignal, publish: (works: Work[], settled: Set<string>) => void) {
  signal.throwIfAborted()
  const now = Date.now()
  for (const [key, value] of searchCache) if (value.expires <= now) searchCache.delete(key)
  const cached = searchCache.get(query)
  if (cached) {
    publish(cached.works, cached.sites)
    return { failed: 0, total: cached.sites.size }
  }
  const config = await catalogSites()
  signal.throwIfAborted()
  const order = new Map(config.map((site, index) => [site.api, index]))
  const works = new Map<string, Work>()
  const settled = new Set<string>()
  let snapshot: Work[] = []
  let failures = 0
  async function searchSite(site: GatewaySite, siteIndex: number) {
    const budget = AbortSignal.any([signal, AbortSignal.timeout(networkTimeouts.searchSiteMs)])
    try {
      for (let page = 1; page <= 100; page++) {
        const url = gatewayUrl(`search/${encodeURIComponent(site.gateway)}`, { q: query, page: String(page) })
        const { rows, pages } = vodPage(await getJson(url, budget, networkTimeouts.catalogRequestMs))
        signal.throwIfAborted()
        let changed = false
        for (const row of rows) {
          const source = sourceFrom(site, row)
          const id = workId(source)
          const group = works.get(id)
          if (!group) {
            works.set(id, { id, display: source, sources: [source] })
            changed = true
          } else if (!group.sources.some(item => item.site.name === site.name && item.id === source.id)) {
            const insert = group.sources.findIndex(item => (order.get(item.site.api) ?? 0) > siteIndex)
            const sources = [...group.sources]
            sources.splice(insert < 0 ? sources.length : insert, 0, source)
            works.set(id, { ...group, sources })
            changed = true
          }
        }
        if (changed) {
          snapshot = [...works.values()]
          publish(snapshot, new Set(settled))
        }
        if (!rows.length || page >= pages) break
        if (page === 100) throw new Error('搜索分页超过 100 页')
      }
    } catch (error) {
      if (signal.aborted) return
      failures++
      console.info('资源站搜索失败', site.name, error)
    }
    if (!signal.aborted) {
      settled.add(site.name)
      publish(snapshot, new Set(settled))
    }
  }
  let next = 0
  await Promise.all(Array.from({ length: Math.min(12, config.length) }, async () => {
    while (!signal.aborted && next < config.length) {
      const index = next++
      await searchSite(config[index], index)
    }
  }))
  signal.throwIfAborted()
  const count = (items: Work[]) => items.reduce((total, work) => total + work.sources.length, 0)
  if (!failures && count(snapshot) <= 10_000) {
    searchCache.delete(query)
    searchCache.set(query, { expires: Date.now() + searchCacheTtl, works: snapshot, sites: settled })
    while (searchCache.size > 5 || [...searchCache.values()].reduce((total, entry) => total + count(entry.works), 0) > 10_000) {
      searchCache.delete(searchCache.keys().next().value!)
    }
  }
  return { failed: failures, total: config.length }
}

export function parseLines(raw: string, from: string): Line[] {
  const names = from.split('$$$')
  return raw.split('$$$').flatMap((group, index) => {
    const episodes = group.split('#').flatMap(item => {
      const split = item.indexOf('$')
      const url = item.slice(split + 1).trim()
      return split >= 0 && url ? [{ name: item.slice(0, split).trim(), url }] : []
    })
    return episodes.length ? [{ id: String(index), name: names[index]?.trim() || `线路 ${index + 1}`, episodes }] : []
  })
}

export async function getDetail(source: Source, signal: AbortSignal): Promise<Detail> {
  const site = (await catalogSites()).find(item => item.gateway === source.site.gateway)
  if (!site) throw new Error('来源未启用')
  const url = gatewayUrl(`detail/${encodeURIComponent(site.gateway)}`, { id: String(source.id) })
  const { rows } = vodPage(await getJson(url, signal, networkTimeouts.catalogRequestMs))
  const value = rows.find(row => integer(object(row).vod_id) === source.id)
  if (!value) throw new Error('来源未返回指定作品')
  const row = object(value)
  const overview = scalar(row.vod_content).replace(/<br\s*\/?\s*>|<\/p>/gi, '\n').replace(/<[^>]*>/g, '')
    .replace(/&(nbsp|lt|gt|quot|#39|amp);/g, (_, key: string) => ({ nbsp: ' ', lt: '<', gt: '>', quot: '"', '#39': "'", amp: '&' })[key] ?? '')
  return { source: sourceFrom(source.site, row), overview: overview.trim(),
    lines: parseLines(scalar(row.vod_play_url), scalar(row.vod_play_from)) }
}

export const categories = doubanCategories.map(({ collection, name }) => [collection, name] as const)

export function doubanPoster(url: string) {
  const route = posterKey(url)
  return route ? gatewayUrl(route) : ''
}

function hotCache(category: string): { at: number; items: HotItem[] } | undefined {
  try { return JSON.parse(localStorage.getItem(`tigertv:hot:all:${category}`) ?? 'null') ?? undefined } catch { }
}

export function cachedHot(category: string): HotItem[] | undefined {
  const cached = hotCache(category)
  if (cached && Date.now() - cached.at < 24 * 3600_000) return cached.items
}

export async function hot(category: string, signal: AbortSignal, onUpdate: (items: HotItem[]) => void): Promise<HotItem[]> {
  const cached = hotCache(category)
  if (cached && Date.now() - cached.at < 6 * 3600_000) return cached.items
  const items = new Map<string, HotItem>()
  try {
    let start = 0
    while (true) {
      const url = new URL(gatewayUrl(`hot/${category}`), location.href)
      url.searchParams.set('start', String(start))
      const raw = object(await getJson(url.href, signal, networkTimeouts.recommendationRequestMs))
      const page = raw.subject_collection_items
      if (!Array.isArray(page)) throw new Error('豆瓣响应异常')
      const previousSize = items.size
      for (const value of page) {
        const entry = object(value)
        const id = scalar(entry.id)
        const title = scalar(entry.title)
        if (id && title && !items.has(id)) items.set(id, {
          id, title, year: scalar(entry.year), poster: webUrl(scalar(object(entry.pic).large)),
          rating: Number(object(entry.rating).value) || 0,
        })
      }
      start += page.length
      if (items.size === previousSize || (typeof raw.total === 'number' ? start >= raw.total : page.length < hotLimit)) break
      onUpdate([...items.values()])
    }
    const complete = [...items.values()]
    if (complete.length) {
      try { localStorage.setItem(`tigertv:hot:all:${category}`, JSON.stringify({ at: Date.now(), items: complete })) } catch { }
    }
    return complete
  } catch (error) {
    if (!signal.aborted && cached && Date.now() - cached.at < 24 * 3600_000) return cached.items
    throw error
  }
}
