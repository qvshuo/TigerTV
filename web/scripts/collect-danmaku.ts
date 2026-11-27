import { mkdir, rename, appendFile } from 'node:fs/promises'
import { join } from 'node:path'
import { categories } from '../src/api.ts'
import { hotLimit } from '../common/douban.ts'

const [gateway, directory] = Bun.argv.slice(2)
const danmaku = Bun.env.DANMAKU_API?.replace(/\/+$/, '')
if (!gateway || !directory || !danmaku) throw new Error('Usage: DANMAKU_API=<local setting> bun web/scripts/collect-danmaku.ts <gateway> <output directory>')
await mkdir(directory, { recursive: true, mode: 0o700 })
const metadata = new URL('api/metadata/', gateway.replace(/\/?$/, '/'))
type Row = Record<string, any>
const pick = (row: Row, fields: string) => Object.fromEntries(fields.split(' ').map(key => [key, row[key]]))
const stats: Record<string, { loaded: number; cached: number; failed: number }> = {}
async function request<T>(group: string, key: string, url: URL | string, select: (data: Row) => T): Promise<T | undefined> {
  const counts = stats[group] ??= { loaded: 0, cached: 0, failed: 0 }
  const file = join(directory, group, encodeURIComponent(key) + '.json')
  await mkdir(join(directory, group), { recursive: true })
  if (await Bun.file(file).exists()) {
    counts.cached++
    return Bun.file(file).json()
  }
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(25_000) })
    if (!response.ok) throw new Error(String(response.status))
    const result = select(await response.json() as Row)
    await Bun.write(file + '.tmp', JSON.stringify(result))
    await rename(file + '.tmp', file)
    counts.loaded++
    return result
  } catch (error) {
    counts.failed++
    const failure = error instanceof Error && /^\d{3}$/.test(error.message) ? error.message : 'network-or-invalid-response'
    await appendFile(join(directory, 'failures.jsonl'), JSON.stringify({ group, key, failure, at: new Date() }) + '\n')
  }
}
function rows(data: Row, key: string): Row[] {
  if (!Array.isArray(data[key])) throw new Error('Invalid response')
  return data[key]
}
async function pool<T>(jobs: Iterable<T>, visit: (job: T) => Promise<void>) {
  const iterator = jobs[Symbol.iterator]()
  await Promise.all(Array.from({ length: 4 }, async () => {
    for (let next = iterator.next(); !next.done; next = iterator.next()) await visit(next.value)
  }))
}
function report() { console.log(JSON.stringify(stats)) }
const works = new Map<string, { id: string; title: string; year: string; categories: Set<string> }>()
await pool(categories, async ([category]) => {
  const seen = new Set<string>()
  for (let start = 0; ; start += hotLimit) {
    const page = await request('douban', category + '-' + start, new URL(`hot/${category}?start=${start}`, metadata), data => ({
      total: Number(data.total), items: rows(data, 'subject_collection_items').map(row => pick(row, 'id title year')),
    }))
    if (!page || !page.items.length) break
    let added = false
    for (const item of page.items) {
      const id = String(item.id)
      if (!seen.has(id)) added = true
      seen.add(id)
      const work = works.get(id) ?? { id, title: String(item.title), year: String(item.year ?? ''), categories: new Set<string>() }
      work.categories.add(category)
      works.set(id, work)
    }
    if (!added || (page.total ? start + hotLimit >= page.total : page.items.length < hotLimit)) break
  }
})
await Bun.write(join(directory, 'works.json'), JSON.stringify([...works.values()].map(work => ({ ...work, categories: [...work.categories] }))))
console.log('Douban works:', works.size)
report()
const titles = new Set([...works.values()].map(work => work.title).sort())
const ids = new Set<number>()
await pool(titles, async title => {
  const matches = await request('danmaku-search', title, `${danmaku}/api/v2/search/anime?${new URLSearchParams({ keyword: title })}`, data => {
    if (data.success !== true) throw new Error('Search failed')
    return rows(data, 'animes').map(row => pick(row, 'animeId animeTitle source'))
  })
  for (const item of matches ?? []) if (Number(item.animeId) > 0) ids.add(Number(item.animeId))
})
report()
await pool(ids, async id => {
  await request('danmaku-episodes', String(id), `${danmaku}/api/v2/bangumi/${id}`, data => {
    if (data.success !== true || !data.bangumi) throw new Error('Details failed')
    const work = data.bangumi
    return { ...pick(work, 'animeId animeTitle source season seasonId'), keys: Object.keys(work),
      seasons: Array.isArray(work.seasons) ? work.seasons.map((row: Row) => pick(row, 'id name airDate episodeCount')) : [],
      episodes: rows(work, 'episodes').map(row => ({ ...pick(row, 'episodeId episodeTitle episodeNumber season seasonId'), keys: Object.keys(row) })) }
  })
})
report()
const configuration = await request('catalog', 'config', new URL('config', metadata), data => {
  if (!data.api_site) throw new Error('Source configuration missing')
  return Object.entries(data.api_site as Record<string, Row>).map(([key, site]) => ({ key, name: String(site.name) }))
})
function* catalogJobs() {
  for (const title of titles) for (const site of configuration ?? []) yield { site, title }
}
const detailed = new Set<string>()
async function catalogDetail(site: string, id: string) {
  const key = site + '-' + id
  if (detailed.has(key)) return
  detailed.add(key)
  await request('catalog-detail', key, new URL(`detail/${site}?id=${id}`, metadata), data => rows(data, 'list').map(row => {
    const names = String(row.vod_play_from ?? '').split('$$$')
    const lines = String(row.vod_play_url ?? '').split('$$$').map((line, index) => ({
      name: names[index] ?? '', episodes: line.split('#').map(episode => episode.includes('$') ? episode.slice(0, episode.indexOf('$')) : ''),
    }))
    return { ...pick(row, 'vod_id vod_name vod_year type_name'), lines }
  }))
}
await pool(catalogJobs(), async ({ site, title }) => {
  for (let page = 1; page <= 100; page++) {
    const result = await request('catalog-search', JSON.stringify([site.key, title, page]),
      new URL(`search/${site.key}?${new URLSearchParams({ q: title, page: String(page) })}`, metadata), data => ({
        pageCount: Math.max(1, Number(data.pagecount) || 1), items: rows(data, 'list').map(row => pick(row, 'vod_id vod_name vod_year type_name')),
      }))
    if (!result) break
    for (const item of result.items) {
      const id = String(item.vod_id)
      if (/^[1-9]\d*$/.test(id)) await catalogDetail(site.key, id)
    }
    if (page >= result.pageCount) break
    if (page === 100) await appendFile(join(directory, 'failures.jsonl'), JSON.stringify({ group: 'catalog-search', site: site.key, title, failure: 'gateway-page-limit' }) + '\n')
  }
})
report()
