import bundledConfig from '../../skills/references/LunaTV-config.json' with { type: 'json' }

export type Site = { name: string; api: string; gateway?: string }
export type Source = { site: Site; id: number; title: string; year: string; poster: string; remarks: string }
export type Work = { id: string; display: Source; sources: Source[] }
export type Episode = { name: string; url: string }
export type Line = { id: string; name: string; episodes: Episode[] }
export type Detail = { source: Source; overview: string; lines: Line[] }
export type HotItem = { id: string; title: string; year: string; poster: string; rating: number }

const cdnConfig = 'https://cdn.jsdelivr.net/gh/qvshuo/TigerTV@main/skills/references/LunaTV-config.json'
const rawConfig = 'https://raw.githubusercontent.com/qvshuo/TigerTV/refs/heads/main/skills/references/LunaTV-config.json'
const gateway = import.meta.env?.VITE_TIGERTV_GATEWAY
function gatewayUrl(route: string, params?: Record<string, string>) {
  const base = new URL(gateway.endsWith('/') ? gateway : gateway + '/', location.href)
  const result = new URL(route, base)
  for (const [key, value] of Object.entries(params ?? {})) result.searchParams.set(key, value)
  return result.href
}
const maxText = 10 * 1024 * 1024
const operations = ['ac', 'wd', 'ids', 'pg', 'pagesize']
const integerFields = new Set(['code', 'pagecount', 'vod_id'])

export function parseJson(text: string, fields = integerFields): unknown {
  return JSON.parse(text, (key: string, value: unknown, context?: { source?: string }) =>
    typeof value === 'number' && fields.has(key) && context?.source ? context.source : value)
}

export function webUrl(value: string): string {
  try {
    const url = new URL(value.startsWith('//') ? 'https:' + value : value)
    if (url.protocol === 'https:' || (url.protocol === 'http:' && location.protocol === 'http:')) return url.href
  } catch { /* Invalid upstream URL. */ }
  return ''
}

export async function getJson(url: string, signal?: AbortSignal, timeout = 10_000): Promise<unknown> {
  const allowed = webUrl(url)
  if (!allowed) throw new Error('来源需要 HTTPS')
  const deadline = AbortSignal.timeout(timeout)
  const response = await fetch(allowed, {
    signal: signal ? AbortSignal.any([signal, deadline]) : deadline,
    credentials: 'omit',
    referrerPolicy: 'no-referrer',
  })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  if (Number(response.headers.get('Content-Length')) > maxText) throw new Error('响应过大')
  const reader = response.body?.getReader()
  if (!reader) throw new Error('响应为空')
  const decoder = new TextDecoder('utf-8', { ignoreBOM: false })
  let bytes = 0
  let text = ''
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      bytes += value.length
      if (bytes > maxText) throw new Error('响应过大')
      text += decoder.decode(value, { stream: true })
    }
    return parseJson(text + decoder.decode())
  } finally {
    void reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

export function object(raw: unknown): Record<string, unknown> {
  return raw !== null && typeof raw === 'object' && !Array.isArray(raw) ? raw as Record<string, unknown> : {}
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

export function apiUrl(api: string, params: Record<string, string>) {
  const url = new URL(api)
  for (const param of operations) url.searchParams.delete(param)
  for (const [key, value] of Object.entries(params)) url.searchParams.set(key, value)
  return url.href
}

export function parseConfig(raw: unknown) {
  const sites = new Map<string, Site>()
  for (const value of Object.values(object(object(raw).api_site))) {
    const entry = object(value)
    const name = scalar(entry.name)
    const api = scalar(entry.api)
    if (name.includes('🎬') && !('_comment' in entry) && api && !sites.has(api)) sites.set(api, { name, api, ...(gateway && entry.gateway ? { gateway: scalar(entry.gateway) } : {}) })
  }
  if (!sites.size) throw new Error('找不到可用资源站')
  return [...sites.values()]
}

let config: Promise<Site[]> | undefined
function sites(): Promise<Site[]> {
  return config ??= (async () => {
    if (gateway) return parseConfig(await getJson(gatewayUrl('config'), undefined, 4000))
    for (const url of [import.meta.env?.VITE_TIGERTV_SOURCES, cdnConfig, rawConfig]) {
      if (!url) continue
      try { return parseConfig(await getJson(url, undefined, 4000)) }
      catch (error) { console.info('资源站配置读取失败', new URL(url).hostname, error) }
    }
    return parseConfig(bundledConfig)
  })().catch(error => { config = undefined; throw error })
}

function sourceFrom(site: Site, value: unknown): Source {
  const row = object(value)
  const id = integer(row.vod_id)
  if (id <= 0) throw new Error('来源没有有效 ID')
  return {
    site, id, title: scalar(row.vod_name), year: scalar(row.vod_year),
    poster: webUrl(scalar(row.vod_pic)), remarks: scalar(row.vod_remarks),
  }
}

function workIdentity(title: string, year: string, source: Source) {
  const text = title.replace(/[\s\u001c-\u001f]/gu, '').toLowerCase()
  return JSON.stringify(text ? [text, year] : [null, `${source.site.name}-${source.id}`])
}
export function legacyWorkId(source: Source) {
  const year = source.year.trim()
  return workIdentity(source.title, year === '0' ? '' : year, source)
}
export function foldWidth(text: string) {
  return text.replace(/[\u3000\uff01-\uff5e]/g, char => char === '\u3000' ? ' ' : String.fromCharCode(char.charCodeAt(0) - 0xfee0))
}
export function workId(source: Source) {
  const title = foldWidth(source.title).trim()
  const primary = title.replace(/[（(【](?:又名|别名|原名|英文名|aka)\s*[:：]\s*[^)）】]+[)）】]\s*$/i, '') || title
  const rawYear = foldWidth(source.year).trim()
  const year = /^((?:19|20)[0-9]{2})\s*年?$/.exec(rawYear)?.[1] ?? (rawYear === '0' ? '' : rawYear)
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

export async function search(query: string, signal: AbortSignal, publish: (works: Work[]) => void) {
  const config = (await sites()).filter(site => {
    try { return !/^https?:\/\//i.test(new URL(site.api).searchParams.get('url') ?? '') }
    catch { return false }
  })
  signal.throwIfAborted()
  const works = new Map<string, Work>()
  const failures: string[] = []
  async function searchSite(site: Site, siteIndex: number) {
    const budget = AbortSignal.any([signal, AbortSignal.timeout(60_000)])
    const found: Source[] = []
    try {
      for (let page = 1; page <= 100; page++) {
        const url = gateway && site.gateway ? gatewayUrl(`search/${encodeURIComponent(site.gateway)}`, { q: query, page: String(page) })
          : apiUrl(site.api, { ac: 'detail', wd: query, pg: String(page), pagesize: '100' })
        const { rows, pages } = vodPage(await getJson(url, budget, 20_000))
        found.push(...rows.map(row => sourceFrom(site, row)))
        if (!rows.length || page >= pages) break
      }
    } catch (error) {
      if (signal.aborted) return
      failures.push(site.name)
      console.info('资源站搜索失败', site.name, error)
      return
    }
    if (signal.aborted) return
    for (const source of found) {
      const id = workId(source)
      const group = works.get(id)
      if (!group) works.set(id, { id, display: source, sources: [source] })
      else if (!group.sources.some(item => item.site.name === site.name && item.id === source.id)) {
        const insert = group.sources.findIndex(item => config.indexOf(item.site) > siteIndex)
        group.sources.splice(insert < 0 ? group.sources.length : insert, 0, source)
      }
    }
    publish([...works.values()].map(work => ({ ...work, sources: [...work.sources] })))
  }
  if (gateway) {
    let next = 0
    await Promise.all(Array.from({ length: Math.min(8, config.length) }, async () => {
      while (!signal.aborted && next < config.length) {
        const index = next++
        await searchSite(config[index], index)
      }
    }))
  } else await Promise.all(config.map(searchSite))
  signal.throwIfAborted()
  return { failed: failures.length, total: config.length }
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
  // Recent records can predate a deployment-mode change. Resolve the current gateway key by API URL.
  const site = gateway ? (await sites()).find(item => item.api === source.site.api) : source.site
  const url = gateway && site?.gateway ? gatewayUrl(`detail/${encodeURIComponent(site.gateway)}`, { id: String(source.id) })
    : apiUrl(source.site.api, { ac: 'detail', ids: String(source.id) })
  const { rows } = vodPage(await getJson(url, signal, 20_000))
  const value = rows.find(row => integer(object(row).vod_id) === source.id)
  if (!value) throw new Error('来源未返回指定作品')
  const row = object(value)
  const overview = scalar(row.vod_content).replace(/<br\s*\/?\s*>|<\/p>/gi, '\n').replace(/<[^>]*>/g, '')
    .replace(/&(nbsp|lt|gt|quot|#39|amp);/g, (_, key: string) => ({ nbsp: ' ', lt: '<', gt: '>', quot: '"', '#39': "'", amp: '&' })[key] ?? '')
  return { source: sourceFrom(source.site, row), overview: overview.trim(),
    lines: parseLines(scalar(row.vod_play_url), scalar(row.vod_play_from)) }
}

export const categories = [
  ['tv_domestic', '国产剧'], ['tv_variety_show', '综艺'], ['tv_american', '英美剧'],
  ['tv_korean', '韩剧'], ['tv_japanese', '日剧'], ['tv_animation', '动漫'],
] as const

export async function hot(category: string, signal: AbortSignal): Promise<HotItem[]> {
  const key = `tigertv:hot:${category}`
  let cached: { at: number; items: HotItem[] } | undefined
  try { cached = JSON.parse(localStorage.getItem(key) ?? 'null') ?? undefined } catch { /* Storage is optional. */ }
  if (cached && Date.now() - cached.at < 6 * 3600_000) return cached.items
  try {
    const url = gateway ? gatewayUrl(`hot/${category}`) : `https://m.douban.com/rexxar/api/v2/subject_collection/${category}/items?start=0&count=12`
    const raw = object(await getJson(url, signal))
    if (!Array.isArray(raw.subject_collection_items)) throw new Error('豆瓣响应异常')
    const items = raw.subject_collection_items.slice(0, 12).map(object).map(entry => ({
      id: scalar(entry.id), title: scalar(entry.title), year: scalar(entry.year),
      poster: webUrl(scalar(object(entry.pic).large)), rating: Number(object(entry.rating).value) || 0,
    })).filter(item => item.id && item.title)
    try { localStorage.setItem(key, JSON.stringify({ at: Date.now(), items })) } catch { /* Cache is optional. */ }
    return items
  } catch (error) {
    if (!signal.aborted && cached && Date.now() - cached.at < 24 * 3600_000) return cached.items
    throw error
  }
}
