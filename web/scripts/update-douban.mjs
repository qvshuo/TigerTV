import { writeFile } from 'node:fs/promises'
import { categories } from '../src/api.ts'

const collections = await Promise.all(categories.map(async ([id]) => {
  const url = new URL(`https://m.douban.com/rexxar/api/v2/subject_collection/${id}/items?start=0&count=12`)
  const response = await fetch(url, {
    headers: { Referer: 'https://m.douban.com/tv/' },
    signal: AbortSignal.timeout(12_000),
  })
  if (!response.ok) throw new Error(`${id} HTTP ${response.status}`)
  const raw = await response.json()
  const cards = raw.subject_collection_items.slice(0, 12).map(item => ({
    id: String(item.id),
    title: item.title,
    year: String(item.year ?? ''),
    poster: item.pic?.large ?? '',
    rating: item.rating?.value ?? 0,
  }))
  if (!cards.length) throw new Error(`${id} 没有推荐作品`)
  return [id, cards]
}))
const snapshot = { date: new Date().toISOString().slice(0, 10), categories: Object.fromEntries(collections) }
await writeFile(new URL('../src/douban-snapshot.json', import.meta.url), JSON.stringify(snapshot, null, 2) + '\n')
