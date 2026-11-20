import { foldWidth as fold, getJson, object, scalar } from './api.ts'

export type Title = { id: number; title: string; source: string }
export type DanmakuEpisode = { id: number; title: string; number: string; season?: number; seasonId: string }
export type Comment = { time: number; mode: number; color: number; text: string }
export type Track = { duration: number; comments: Comment[] }
const numeral = '[0-9零〇一二两三四五六七八九十百]+'
const seasonPattern = new RegExp(`第\\s*(${numeral})\\s*季|(?<![a-z0-9])(?:season\\s*|s)([0-9]+)(?=e[0-9]|[^a-z0-9]|$)`, 'i')
const ordinal = new RegExp(`第\\s*(${numeral})\\s*([集期话話])`)
const special = /特别篇?|番外|加更|预告|花絮|特辑|特典|先导|前采|抢鲜|抢先|沉浸|彩蛋|\b(?:trailer|preview|ova|oad|extra|sp|special)(?=\b|\d)/i
const platforms = 'qiyi|iqiyi|youku|qq|tencent|imgo|mgtv|mango|360|bilibili|bili|douban|dandan|renren|sohu|letv|pptv|爱奇艺|优酷|腾讯(?:视频)?|芒果\\s*tv|哔哩哔哩'
const metadata = new RegExp(`[【\\[(]\\s*(?:${platforms}|国产剧|电视剧|电影|综艺|纪录片|动漫|动画|[日韩欧美港台]剧|短剧|高清|超清|蓝光|全集|完结|\\d{3,4}p|[248]k)\\s*[\\]】)]`, 'gi')
const fromTag = new RegExp(`(?:\\s+from\\s+(?:${platforms})|(?<=】)from\\s+[a-z0-9_+.-]+)\\s*$`, 'i')
const yearPattern = /[【\[(]((?:19|20)\d{2})[\]】)]|\s+((?:19|20)\d{2})\s*$/g
const aliasPattern = /[【\[(](?:又名|别名|英文名|原名|aka)\s*[:：]?\s*([^】)\]]+)[\]】)]/gi

function number(text = ''): number | undefined {
  if (/^\d+$/.test(text)) return Number(text) || undefined
  let sum = 0
  let digit = 0
  for (const char of text) {
    if (char === '十' || char === '百') {
      sum += (digit || 1) * (char === '十' ? 10 : 100)
      digit = 0
    } else {
      digit = '零一二三四五六七八九'.indexOf(char === '两' ? '二' : char === '〇' ? '零' : char)
      if (digit < 0) return
    }
  }
  return sum + digit || undefined
}
export function season(text: string) {
  const match = seasonPattern.exec(fold(text))
  return number(match?.[1] ?? match?.[2])
}

function titleParts(raw: string) {
  const text = fold(raw).replace(fromTag, '').replace(metadata, '')
  const year = [...text.matchAll(yearPattern)][0]
  const aliases = [...text.matchAll(aliasPattern)].map(match => match[1])
  const main = text.replace(aliasPattern, '').replace(yearPattern, '').trim()
  const names = [main, ...aliases].map(name => name.toLowerCase().replace(seasonPattern, '')
    .replace(/[\s·・:：_\-《》〈〉【】\[\]()、，,。.!?！？‘’“”'"—]+/g, '')).filter(Boolean)
  return { main: main.replace(/[《》]/g, '').trim(), names, year: Number(year?.[1] ?? year?.[2]) || undefined, season: season(text) }
}

export function titleRank(query: string, candidate: string, year: string, desiredSeason?: number) {
  const expected = titleParts(query)
  const actual = titleParts(candidate)
  const expectedYear = expected.year ?? (/^(19|20)\d{2}$/.test(year) ? Number(year) : undefined)
  const expectedSeason = desiredSeason ?? expected.season
  if (!expected.names.some(name => actual.names.includes(name)) ||
    (special.test(actual.main) && expected.main !== actual.main) ||
    (expectedYear && actual.year && expectedYear !== actual.year) ||
    (expectedSeason ?? 1) !== (actual.season ?? 1)) return
  return expectedSeason !== actual.season ? 2 : expectedYear && expectedYear === actual.year ? 0 : 1
}

function label(raw: string) {
  const text = fold(raw).replace(metadata, '').trim()
  const [heading, subtitle = ''] = text.split(':', 2)
  if (special.test(heading) || /上下篇|上中下/.test(heading) || special.exec(subtitle.trimStart())?.index === 0) return
  const date = text.match(/(?<!\d)(20\d{2})[-./年]?(0[1-9]|1[0-2])[-./月]?(0[1-9]|[12]\d|3[01])(?!\d)/)?.slice(1).join('')
  const part = heading.match(/[集期话話]\s*[(【\[]?\s*([上下中])(?=篇|集|部|[)】\]:：\s]|$)/)?.[1]
  const ordinalMatch = ordinal.exec(heading)
  const n = number(ordinalMatch?.[1])
  const english = heading.match(/(?<![a-z0-9])(?:s\d+\s*)?(?:episode|ep|e)\s*(\d+)(?![\d.])/i)
  return { date, part, season: season(heading), kind: ordinalMatch ? ordinalMatch[2] === '期' ? 'period' : 'episode' : undefined,
    number: n && n < 10000 ? n : number(english?.[1]) ?? (!date && /^\d+$/.test(heading) ? number(heading) : undefined) }
}

export function matchingEpisode(episodes: DanmakuEpisode[], name: string, workSeason?: number) {
  const target = label(name)
  if (!target || (!target.number && !target.date) || (target.season && workSeason && target.season !== workSeason)) return
  const expectedSeason = target.season ?? workSeason ?? 1
  const multipleSeasons = new Set(episodes.map(episode => episode.seasonId).filter(Boolean)).size > 1
  const matches = episodes.flatMap(episode => {
    const title = label(episode.title)
    const numberLabel = label(episode.number)
    if (!title || !numberLabel) return []
    const seasons = [episode.season, title.season, numberLabel.season].filter(Boolean)
    if (seasons.some(value => value !== expectedSeason) || (multipleSeasons && !seasons.length)) return []
    if ((target.kind && title.kind && target.kind !== title.kind) ||
      (title.part && numberLabel.part && title.part !== numberLabel.part) ||
      target.part !== (title.part ?? numberLabel.part) ||
      (title.date && numberLabel.date && title.date !== numberLabel.date)) return []
    const date = title.date ?? numberLabel.date
    const dateAgrees = Boolean(target.date && target.date === date)
    if ((target.date && !dateAgrees) || (target.part && !dateAgrees && title.number !== target.number)) return []
    if (title.number && numberLabel.number && title.number !== numberLabel.number && !(title.kind === 'period' && dateAgrees)) return []
    if (target.number ? target.number !== (title.number ?? numberLabel.number) : !dateAgrees) return []
    return [{ episode, explicit: dateAgrees || Boolean(title.number) }]
  })
  const explicit = matches.filter(match => match.explicit)
  const candidates = explicit.length ? explicit : matches
  return candidates.length === 1 ? candidates[0].episode : undefined
}

function rows(input: unknown) { return Array.isArray(input) ? input.map(object) : [] }
function id(value: unknown) { const result = Number(scalar(value)); return Number.isSafeInteger(result) && result > 0 ? result : 0 }

export class DanmakuClient {
  private base: string
  private signal: AbortSignal

  constructor(base: string, signal: AbortSignal) {
    this.base = base.trim().replace(/\/+$/, '')
    this.signal = signal
  }
  async search(keyword: string) {
    const raw = object(await getJson(`${this.base}/api/v2/search/anime?${new URLSearchParams({ keyword })}`, this.signal))
    if (raw.success !== true) throw new Error('弹幕搜索失败')
    return rows(raw.animes).map(item => ({ id: id(item.animeId), title: scalar(item.animeTitle), source: scalar(item.source) })).filter(item => item.id)
  }
  async episodes(titleId: number) {
    const raw = object(await getJson(`${this.base}/api/v2/bangumi/${titleId}`, this.signal, 20_000))
    if (raw.success !== true) throw new Error('弹幕选集加载失败')
    const bangumi = object(raw.bangumi)
    const seasons = new Map(rows(bangumi.seasons).map(row => [scalar(row.id), season(scalar(row.name))]))
    return rows(bangumi.episodes).map(row => ({ id: id(row.episodeId), title: scalar(row.episodeTitle), number: scalar(row.episodeNumber),
      seasonId: scalar(row.seasonId), season: seasons.get(scalar(row.seasonId)) })).filter(item => item.id)
  }
  async track(episodeId: number): Promise<Track> {
    const raw = object(await getJson(`${this.base}/api/v2/comment/${episodeId}?format=json&duration=true`, this.signal, 50_000))
    const comments = rows(raw.comments).flatMap(row => {
      const fields = scalar(row.p).split(',', 3)
      if (fields.length !== 3 || fields.some(field => !field.trim())) return []
      const [time, mode, color] = fields.map(Number)
      const text = scalar(row.m)
      return Number.isFinite(time) && time >= 0 && time <= 604800 && [1, 4, 5].includes(mode) &&
        Number.isInteger(color) && color >= 0 && color <= 0xffffff && text.trim() ? [{ time, mode, color, text }] : []
    }).sort((a, b) => a.time - b.time)
    const duration = Number(raw.videoDuration)
    return { comments, duration: Number.isFinite(duration) && duration > 0 && duration <= 604800 ? duration : 0 }
  }
}
const platformsByPriority = [['iqiyi', 'qiyi'], ['youku'], ['tencent', 'qq'], ['mgtv', 'imgo', 'mango']]
const priority = (source: string) => {
  const index = platformsByPriority.findIndex(group => group.includes(source.toLowerCase()))
  return index < 0 ? platformsByPriority.length : index
}
const scrollingCount = (track: Track) => track.comments.reduce((count, item) => count + Number(item.mode === 1), 0)
export function shouldUpgrade(current: Track, next: Track) {
  const currentCount = scrollingCount(current)
  return currentCount > 0 && current.duration > 0 && next.duration > 0 &&
    Math.abs(current.duration - next.duration) <= Math.min(10, Math.max(1, Math.min(current.duration, next.duration) / 100)) &&
    scrollingCount(next) >= currentCount * 2
}

export async function autoMatch(client: DanmakuClient, query: string, year: string, episodeName: string,
  memory: { title?: number; episode?: { title: number; id: number } }, publish: (track: Track) => void, signal: AbortSignal) {
  let budget = 12
  const expectedSeason = season(query) ?? season(episodeName)
  async function load(title: number, remembered?: number) {
    try {
      if (!budget) return
      budget--
      const episodes = await client.episodes(title)
      const episode = remembered !== undefined ? episodes.find(item => item.id === remembered)
        : matchingEpisode(episodes, episodeName, expectedSeason)
      if (!episode || !budget) return
      budget--
      const track = await client.track(episode.id)
      if (scrollingCount(track)) return track
    } catch (error) { if (signal.aborted) throw error }
  }
  if (memory.episode) {
    const track = await load(memory.episode.title, memory.episode.id)
    if (track) { publish(track); return }
  }
  if (memory.title) {
    const track = await load(memory.title)
    if (track) { publish(track); return }
  }
  const seen = new Set(memory.title ? [memory.title] : [])
  const normalized = titleParts(query).main
  for (const keyword of new Set([query, normalized].filter(Boolean))) {
    if (!budget) return
    budget--
    const candidates = (await client.search(keyword)).flatMap(item => {
      const rank = titleRank(query, item.title, year, expectedSeason)
      if (rank === undefined || seen.has(item.id)) return []
      seen.add(item.id)
      return [{ item, rank }]
    }).sort((a, b) => a.rank - b.rank || priority(a.item.source) - priority(b.item.source))
    let best: Track | undefined
    let bestRank: number | undefined
    for (const { item, rank } of candidates) {
      if (!budget || (bestRank !== undefined && rank > bestRank)) break
      const track = await load(item.id)
      if (track && (!best || shouldUpgrade(best, track))) {
        best = track
        bestRank = rank
        publish(track)
      }
    }
    if (best) return
  }
}

type PositionedComment = Comment & { x: number; y: number; end: number }
const layouts = new WeakMap<HTMLCanvasElement, {
  track: Track; width: number; height: number; font: string; velocity: number; comments: PositionedComment[]
}>()

function layoutComments(canvas: HTMLCanvasElement, ctx: CanvasRenderingContext2D, track: Track, width: number, height: number) {
  const cached = layouts.get(canvas)
  if (cached?.track === track && cached.width === width && cached.height === height) return cached
  const fontSize = Math.max(14, Math.min(26, height / 17))
  const rowHeight = fontSize * 1.4
  const lanes = new Array<number>(Math.max(1, Math.floor(height * 0.43 / rowHeight))).fill(0)
  const fixed = { 4: 0, 5: 0 }
  const velocity = width / 6
  const font = `600 ${fontSize}px system-ui, sans-serif`
  ctx.font = font
  const comments: PositionedComment[] = []
  for (const item of track.comments) {
    const text = item.text.slice(0, 100)
    if (item.mode === 1) {
      const row = lanes.findIndex(until => until <= item.time)
      if (row < 0) continue
      const size = ctx.measureText(text).width
      if (size > width * 1.65) continue
      lanes[row] = item.time + (size + 18) / velocity
      comments.push({ ...item, text, x: width, y: (row + 1) * rowHeight, end: item.time + (width + size) / velocity })
    } else if ((item.mode === 4 || item.mode === 5) && fixed[item.mode] <= item.time) {
      const size = ctx.measureText(text).width
      if (size > width) continue
      fixed[item.mode] = item.time + 4
      comments.push({ ...item, text, x: (width - size) / 2, y: item.mode === 5 ? rowHeight : height * 0.78, end: item.time + 4 })
    }
  }
  const layout = { track, width, height, font, velocity, comments }
  layouts.set(canvas, layout)
  return layout
}

export function drawComments(canvas: HTMLCanvasElement, track: Track | undefined, time: number) {
  const width = canvas.clientWidth
  const height = canvas.clientHeight
  const scale = Math.min(2, devicePixelRatio)
  if (canvas.width !== Math.round(width * scale)) canvas.width = Math.round(width * scale)
  if (canvas.height !== Math.round(height * scale)) canvas.height = Math.round(height * scale)
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.setTransform(scale, 0, 0, scale, 0, 0)
  ctx.clearRect(0, 0, width, height)
  if (!track || time < 0 || !width || !height) return
  const { comments, velocity, font } = layoutComments(canvas, ctx, track, width, height)
  let low = 0
  let high = comments.length
  while (low < high) {
    const middle = (low + high) >>> 1
    if (comments[middle].time < time - 16) low = middle + 1
    else high = middle
  }
  ctx.font = font
  ctx.lineWidth = 3
  ctx.lineJoin = 'round'
  ctx.strokeStyle = 'rgba(0, 0, 0, .85)'
  for (let index = low; index < comments.length; index++) {
    const item = comments[index]
    if (item.time > time) break
    if (item.end <= time) continue
    const x = item.mode === 1 ? item.x - (time - item.time) * velocity : item.x
    ctx.strokeText(item.text, x, item.y)
    ctx.fillStyle = '#' + item.color.toString(16).padStart(6, '0')
    ctx.fillText(item.text, x, item.y)
  }
}
