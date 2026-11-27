import selectionRules from '../../shared/danmaku-selection.json' with { type: 'json' }
import { foldWidth as fold, getJson, object, scalar } from './api.ts'

export type Title = { id: number; title: string; source: string }
export type DanmakuEpisode = { id: number; title: string; number: string; season?: number; seasonId?: string }
export type Comment = { time: number; mode: number; color: number; text: string }
export type Track = { duration: number; comments: Comment[] }
export type DanmakuMatch = { title: Title; episode: DanmakuEpisode; episodes: DanmakuEpisode[] }
export type SavedDanmakuEpisode = { title: number; id: number; titleItem?: Title; episodeItem?: DanmakuEpisode }
const textPatterns = selectionRules.textPatterns
const seasonPattern = new RegExp(textPatterns.season, 'i')
const ordinal = new RegExp(textPatterns.ordinal, 'g')
const special = new RegExp(textPatterns.special, 'i')
const metadata = new RegExp(textPatterns.metadata, 'gi')
const fromTag = new RegExp(textPatterns.sourceSuffix, 'i')
const yearPattern = new RegExp(textPatterns.year, 'g')
const aliasPattern = new RegExp(textPatterns.alias, 'gi')
const datePattern = new RegExp(textPatterns.date)
const bonusPattern = new RegExp(textPatterns.bonus)
const blooperPattern = new RegExp(textPatterns.blooper)
const partPattern = new RegExp(textPatterns.part)
const variantPart = new RegExp(textPatterns.variantPart)
const numberedPart = new RegExp(textPatterns.numberedPart)
const numericSeparator = new RegExp(textPatterns.numericSeparator, 'gu')
const englishEpisode = new RegExp(textPatterns.englishEpisode, 'i')

function number(text = ''): number | undefined {
  if (/^\d+$/.test(text)) {
    const value = Number(text)
    return value > 0 && value <= 2147483647 ? value : undefined
  }
  if (/^[零〇一二两兩三四五六七八九]+$/.test(text)) {
    return number([...text].map(char => '零一二三四五六七八九'.indexOf(
      char === '两' || char === '兩' ? '二' : char === '〇' ? '零' : char)).join(''))
  }
  let sum = 0
  let digit = 0
  for (const char of text) {
    if (char === '十' || char === '百') {
      sum += (digit || 1) * (char === '十' ? 10 : 100)
      digit = 0
    } else {
      digit = '零一二三四五六七八九'.indexOf(char === '两' || char === '兩' ? '二' : char === '〇' ? '零' : char)
      if (digit < 0) return
    }
  }
  return sum + digit || undefined
}
export function season(text: string) {
  const match = seasonPattern.exec(fold(text))
  return number(match?.[1] ?? match?.[2])
}

function matchKey(text: string) {
  return text.toLowerCase().replace(numericSeparator, '+')
    .replace(/[\p{P}\p{Z}\s・]+/gu, '')
    .replace(/[0-9]+/g, run => run.replace(/^0+(?=\d)/, ''))
}

function similarity(left: string, right: string, threshold = selectionRules.matchThreshold) {
  if (!left || !right) return 0
  if (left === right) return 1
  const size = Math.max(left.length, right.length)
  const length = Math.min(left.length, right.length)
  if (length < selectionRules.minimumFuzzyLength || length < size * threshold ||
    (left.match(/[0-9]+/g) ?? []).join(',') !== (right.match(/[0-9]+/g) ?? []).join(',')) return 0
  const row = Array.from({ length: right.length + 1 }, (_, index) => index)
  for (let i = 0; i < left.length; i++) {
    let diagonal = row[0]
    row[0] = i + 1
    for (let j = 0; j < right.length; j++) {
      const previous = row[j + 1]
      row[j + 1] = Math.min(previous + 1, row[j] + 1, diagonal + Number(left[i] !== right[j]))
      diagonal = previous
    }
  }
  return 1 - row[right.length] / size
}

function titleParts(raw: string) {
  const text = fold(raw).replace(fromTag, '').replace(metadata, '')
  const year = [...text.matchAll(yearPattern)][0]
  const aliases = [...text.matchAll(aliasPattern)].map(match => match[1])
  const main = text.replace(aliasPattern, '').replace(yearPattern, '').replace(/[《》]/g, '').trim()
  const key = (name: string) => matchKey(name.replace(seasonPattern, ''))
  const names = [main, ...aliases].map(key).filter(Boolean)
  return { main, key: key(main), names, year: Number(year?.[1] ?? year?.[2]) || undefined, season: season(main) }
}

function titleMatch(expected: ReturnType<typeof titleParts>, actual: ReturnType<typeof titleParts>, year: string, desiredSeason?: number) {
  const expectedYear = expected.year ?? (/^(19|20)\d{2}$/.test(year) ? Number(year) : undefined)
  const expectedSeason = desiredSeason ?? expected.season
  if ((expectedYear && actual.year && expectedYear !== actual.year) ||
    (expectedSeason ?? 1) !== (actual.season ?? 1) || !actual.key) return
  let score = 0
  for (const left of expected.names) for (const right of actual.names) score = Math.max(score, similarity(left, right))
  if (score < selectionRules.matchThreshold ||
    special.exec(expected.main)?.[0].toLowerCase() !== special.exec(actual.main)?.[0].toLowerCase()) return
  const rank = expected.key === actual.key ? expectedYear && expectedYear === actual.year ? 0 : 1 : score === 1 ? 2 : 3
  return { rank, score, identity: JSON.stringify([actual.key, actual.year, actual.season ?? 1]) }
}

export function titleRank(query: string, candidate: string, year: string, desiredSeason?: number) {
  return titleMatch(titleParts(query), titleParts(candidate), year, desiredSeason)?.rank
}

export function titleCandidates(query: string, candidates: Title[], year: string, desiredSeason?: number) {
  const expected = titleParts(query)
  const matches = candidates.flatMap(item => {
    const match = titleMatch(expected, titleParts(item.title), year, desiredSeason)
    return match ? [{ ...match, item }] : []
  })
  const rank = Math.min(...matches.map(match => match.rank))
  const identities = new Map<string, number>()
  for (const match of matches) {
    if (match.rank === rank) identities.set(match.identity, Math.max(match.score, identities.get(match.identity) ?? 0))
  }
  const [best, runnerUp] = [...identities].sort((a, b) => b[1] - a[1])
  return best && (!runnerUp || best[1] - runnerUp[1] >= selectionRules.ambiguityGap)
    ? matches.filter(match => match.rank === rank && match.identity === best[0]) : []
}

function specialKind(value: string) {
  if (['特别', '特别篇', 'sp', 'special'].includes(value)) return '特别篇'
  if (value === 'extra') return '番外'
  if (value === 'trailer' || value === 'preview') return '预告'
  return value
}

function label(raw: string) {
  const text = fold(raw).replace(metadata, '').trim()
  const separator = text.indexOf(':')
  const heading = separator < 0 ? text : text.slice(0, separator)
  const subtitle = separator < 0 ? '' : text.slice(separator + 1).trimStart()
  const subtitleSpecial = special.exec(subtitle)
  const headingVariant = special.exec(heading)
  const tag = headingVariant?.[0] ?? (subtitleSpecial?.index === 0 ? subtitleSpecial[0] : undefined)
  const variant = bonusPattern.test(heading) ? '加更' : blooperPattern.test(heading) ? '花絮特辑' :
    tag && specialKind(tag.toLowerCase())
  if (/上下篇|上中下/.test(heading)) return
  const date = text.match(datePattern)?.slice(1).join('')
  const partNumber = number(heading.match(numberedPart)?.[1])
  const part = heading.match(partPattern)?.[1] ?? partNumber?.toString()
    ?? (headingVariant ? heading.slice(headingVariant.index + headingVariant[0].length).match(variantPart)?.[1] : undefined)
  const ordinalMatch = [...heading.matchAll(ordinal)].find(match => {
    const value = number(match[1])
    return value !== undefined && value < 10000
  })
  const n = number(ordinalMatch?.[1])
  const english = heading.match(englishEpisode)
  return { date, part, variant, season: season(heading), kind: ordinalMatch ? ordinalMatch[2] === '期' ? 'period' : 'episode' : undefined,
    number: n && n < 10000 ? n : number(english?.[1]) ?? (!date && /^\d+$/.test(heading) ? number(heading) : undefined) }
}

export function matchingEpisode(episodes: DanmakuEpisode[], name: string, workSeason?: number) {
  const target = label(name)
  if (!target || (target.season && workSeason && target.season !== workSeason)) return
  const expectedSeason = target.season ?? workSeason
  const matches = episodes.flatMap(episode => {
    const title = label(episode.title)
    if (!title) return []
    const actualSeason = title.season ?? episode.season
    if (actualSeason && actualSeason !== (expectedSeason ?? 1)) return []
    if (target.variant !== title.variant || target.part !== title.part) return []
    if (target.kind && title.kind && target.kind !== title.kind) return []
    if (target.date && title.date && target.date !== title.date) return []
    const dateAgrees = Boolean(target.date && target.date === title.date)
    if (target.number && title.number && target.number !== title.number) return []
    if (dateAgrees) return [{ episode, score: title.number === target.number ? 1 : .9 }]
    const episodeNumber = title.number ?? number(episode.number.trim())
    if (target.number && target.number === episodeNumber)
      return [{ episode, score: !title.number ? .5 : target.date === title.date ? 1 : .8 }]
    if (target.number || target.date || title.number || title.date) return []
    const key = (text: string) => matchKey(fold(text).replace(metadata, '').replace(seasonPattern, ''))
    const threshold = selectionRules.episodeMatchThreshold
    const score = similarity(key(name), key(episode.title), threshold)
    return score >= threshold ? [{ episode, score }] : []
  }).sort((a, b) => b.score - a.score)
  const [best, next] = matches
  return best && (!next || best.score - next.score >= selectionRules.ambiguityGap) ? best.episode : undefined
}

function rows(input: unknown) { return Array.isArray(input) ? input.map(object) : [] }
function id(value: unknown) { const result = Number(scalar(value)); return Number.isSafeInteger(result) && result > 0 ? result : 0 }

const recentTracks = new Map<string, { expires: number; track: Track }>()

export type DanmakuPhase = 'search' | 'episodes' | 'track'

export const danmakuLoading: Record<DanmakuPhase, string> = {
  search: '正在匹配弹幕…',
  episodes: '正在匹配弹幕…',
  track: '正在加载弹幕…',
}

export class DanmakuClient {
  private base: string
  private signal: AbortSignal

  constructor(base: string, signal: AbortSignal, private onPhase?: (phase: DanmakuPhase) => void) {
    this.base = base.trim().replace(/\/+$/, '')
    this.signal = signal
  }
  async search(keyword: string) {
    this.onPhase?.('search')
    const raw = object(await getJson(`${this.base}/api/v2/search/anime?${new URLSearchParams({ keyword })}`, this.signal, selectionRules.searchTimeoutMs))
    if (raw.success !== true) throw new Error('弹幕搜索失败')
    return rows(raw.animes).map(item => ({ id: id(item.animeId), title: scalar(item.animeTitle), source: scalar(item.source) })).filter(item => item.id)
  }
  async episodes(titleId: number) {
    this.onPhase?.('episodes')
    const raw = object(await getJson(`${this.base}/api/v2/bangumi/${titleId}`, this.signal, selectionRules.episodesTimeoutMs))
    if (raw.success !== true) throw new Error('弹幕选集加载失败')
    const bangumi = object(raw.bangumi)
    const seasons = rows(bangumi.seasons)
    const workSeason = season(scalar(bangumi.animeTitle))
    const seasonById = new Map(seasons.map(row => {
      const n = season(scalar(row.name))
      return [scalar(row.id), seasons.length === 1 && n === 1 ? workSeason ?? n : n] as const
    }))
    return rows(bangumi.episodes).map(row => {
      const seasonId = scalar(row.seasonId)
      return {
        id: id(row.episodeId), title: scalar(row.episodeTitle), number: scalar(row.episodeNumber), seasonId,
        season: seasonId ? seasonById.get(seasonId) : undefined,
      }
    }).filter(item => item.id)
  }
  async track(episodeId: number, timeout = selectionRules.trackTimeoutMs): Promise<Track> {
    this.onPhase?.('track')
    this.signal.throwIfAborted()
    const url = `${this.base}/api/v2/comment/${episodeId}?format=json&duration=true`
    const cached = recentTracks.get(url)
    if (cached) {
      recentTracks.delete(url)
      if (cached.expires > Date.now()) {
        recentTracks.set(url, cached)
        return cached.track
      }
    }
    const raw = object(await getJson(url, this.signal, timeout, JSON.parse))
    this.signal.throwIfAborted()
    const comments: Comment[] = []
    for (const item of Array.isArray(raw.comments) ? raw.comments : []) {
      const row = object(item)
      const fields = scalar(row.p).split(',', 3)
      if (fields.length !== 3 || fields.some(field => !field.trim())) continue
      const time = Number(fields[0]), mode = Number(fields[1]), color = Number(fields[2])
      const text = scalar(row.m)
      if (Number.isFinite(time) && time >= 0 && time <= 604800 && mode === 1 &&
        Number.isInteger(color) && color >= 0 && color <= 0xffffff && text.trim()) comments.push({ time, mode, color, text })
    }
    comments.sort((a, b) => a.time - b.time)
    const duration = Number(raw.videoDuration)
    const track = { comments, duration: Number.isFinite(duration) && duration > 0 && duration <= 604800 ? duration : 0 }
    if (comments.length) {
      recentTracks.delete(url)
      recentTracks.set(url, { expires: Date.now() + 600_000, track })
      if (recentTracks.size > 3) recentTracks.delete(recentTracks.keys().next().value!)
    }
    return track
  }
}
const { platformPriority, durationDivisor, minimumToleranceMs, maximumToleranceMs, minimumCountRatio } = selectionRules
const priority = (source: string) => {
  const index = platformPriority.findIndex(group => group.includes(source.toLowerCase()))
  return index < 0 ? platformPriority.length : index
}
const scrollingCount = (track: Track) => track.comments.reduce((count, item) => count + Number(item.mode === 1), 0)
export function shouldUpgrade(current: Track, next: Track) {
  const currentCount = scrollingCount(current)
  const currentMs = Math.round(current.duration * 1000)
  const nextMs = Math.round(next.duration * 1000)
  const tolerance = Math.min(maximumToleranceMs,
    Math.max(minimumToleranceMs, Math.floor(Math.min(currentMs, nextMs) / durationDivisor)))
  return currentCount > 0 && currentMs > 0 && nextMs > 0 &&
    Math.abs(currentMs - nextMs) <= tolerance && scrollingCount(next) >= currentCount * minimumCountRatio
}

export async function autoMatch(client: DanmakuClient, query: string, year: string, episodeName: string,
  memory: { title?: number; episode?: SavedDanmakuEpisode }, publish: (track: Track, match: DanmakuMatch) => void,
  signal: AbortSignal, onTitles?: (titles: Title[]) => void) {
  let budget = selectionRules.requestBudget
  let loadError: unknown
  let blocked = false
  function failed(error: unknown) {
    if (signal.aborted) return
    loadError = error
    blocked ||= error instanceof Error && /^HTTP (401|403|429)$/.test(error.message)
  }
  const expectedSeason = season(query) ?? season(episodeName)
  async function resolveEpisode(title: Title): Promise<DanmakuMatch | undefined> {
    try {
      if (!budget || blocked) return
      budget--
      const episodes = await client.episodes(title.id)
      const episode = matchingEpisode(episodes, episodeName, expectedSeason)
      if (episode) return { title, episode, episodes }
    } catch (error) {
      failed(error)
    }
  }
  async function loadTrack(episodeId: number) {
    try {
      if (!budget) return
      budget--
      const track = await client.track(episodeId)
      if (scrollingCount(track)) return track
    } catch (error) {
      failed(error)
    }
  }
  if (memory.episode) {
    const track = await loadTrack(memory.episode.id)
    if (track) {
      const title = memory.episode.titleItem ?? { id: memory.episode.title, title: query, source: '' }
      const episode = memory.episode.episodeItem ?? { id: memory.episode.id, title: episodeName, number: '' }
      publish(track, { title, episode, episodes: [episode] })
      return
    }
  }
  if (memory.title) {
    const match = await resolveEpisode({ id: memory.title, title: query, source: '' })
    const track = match && await loadTrack(match.episode.id)
    if (match && track) { publish(track, match); return }
  }
  const seen = new Set(memory.title ? [memory.title] : [])
  const normalized = titleParts(query).main
  for (const keyword of new Set([query, normalized].filter(Boolean))) {
    signal.throwIfAborted()
    if (!budget || blocked) break
    budget--
    const titles = await client.search(keyword).catch(error => { failed(error); return [] })
    if (!signal.aborted) onTitles?.(titles)
    const candidates = titleCandidates(query, titles.filter(item => {
      if (seen.has(item.id)) return false
      seen.add(item.id)
      return true
    }), year, expectedSeason).sort((a, b) => a.rank - b.rank || priority(a.item.source) - priority(b.item.source))
    let best: Track | undefined
    let bestRank: number | undefined
    let index = 0
    while (index < candidates.length && budget && !blocked) {
      const { rank } = candidates[index]
      if (bestRank !== undefined && rank > bestRank) break
      let publishOrder = Promise.resolve()
      let downloads = 0
      // Only matching episodes occupy download slots. Consume results in source priority order.
      while (downloads < 2 && budget && !blocked && candidates[index]?.rank === rank) {
        const match = await resolveEpisode(candidates[index++].item)
        signal.throwIfAborted()
        if (!match || !budget || blocked) continue
        downloads++
        const fetched = loadTrack(match.episode.id)
        publishOrder = publishOrder.then(async () => {
          const track = await fetched
          if (!signal.aborted && track && (!best || shouldUpgrade(best, track))) {
            best = track
            bestRank = rank
            publish(track, match)
          }
        })
      }
      await publishOrder
      signal.throwIfAborted()
    }
    if (best) return
  }
  if (loadError) throw loadError
}


const render = selectionRules.render
const lifetime = render.lifetimeMs / 1000
type Active = { comment: Comment; text: string; width: number; lane: number }
type Layout = {
  track: Track; width: number; height: number; time: number; cursor: number;
  widths: Map<string, number>; active: Active[]; lanes: (Active | undefined)[]
}
const layouts = new WeakMap<HTMLCanvasElement, Layout>()

export function fitsDanmaku(width: number, previous: number, incoming: number, elapsed: number, gap: number) {
  if (elapsed >= lifetime) return true
  const phase = elapsed / lifetime
  return phase * (width + previous) - previous >= gap &&
    width - (1 - phase) * (width + incoming) >= gap
}

export function drawComments(canvas: HTMLCanvasElement, track: Track | undefined, time: number, reset = false) {
  const width = canvas.clientWidth
  const height = canvas.clientHeight
  const scale = Math.min(2, devicePixelRatio)
  if (canvas.width !== Math.round(width * scale)) canvas.width = Math.round(width * scale)
  if (canvas.height !== Math.round(height * scale)) canvas.height = Math.round(height * scale)
  const ctx = canvas.getContext('2d')
  if (!ctx) return Infinity
  ctx.setTransform(scale, 0, 0, scale, 0, 0)
  ctx.clearRect(0, 0, width, height)
  if (!track || !width || !height) { layouts.delete(canvas); return Infinity }
  const font = height / render.areaRatio * render.fontRatio
  const row = font * render.lineHeight
  const lanes = Math.max(1, Math.min(render.lanes, Math.floor(height / row)))
  ctx.font = `700 ${font}px system-ui, sans-serif`
  let layout = layouts.get(canvas)
  if (!layout || layout.track !== track || layout.width !== width || layout.height !== height ||
      reset || time < layout.time || time - layout.time > 1.5) {
    let low = 0
    let high = track.comments.length
    while (low < high) {
      const mid = (low + high) >>> 1
      if (track.comments[mid].time < time - 2 * lifetime) low = mid + 1
      else high = mid
    }
    const widths = layout?.height === height ? layout.widths : new Map<string, number>()
    layout = { track, width, height, time, cursor: low, widths, active: [], lanes: Array(lanes) }
    layouts.set(canvas, layout)
  }
  const state = layout
  while (state.cursor < track.comments.length && track.comments[state.cursor].time <= time) {
    const comment = track.comments[state.cursor++]
    if (comment.mode !== 1) continue
    state.active = state.active.filter(item => comment.time - item.comment.time < lifetime)
    if (state.active.length >= render.visibleLimit) continue
    const text = comment.text.slice(0, 100)
    let size = state.widths.get(text)
    if (size === undefined) {
      size = ctx.measureText(text).width
      if (state.widths.size >= 256) state.widths.delete(state.widths.keys().next().value!)
      state.widths.set(text, size)
    }
    const lane = state.lanes.findIndex(previous => !previous ||
      fitsDanmaku(width, previous.width, size, comment.time - previous.comment.time, font * render.gapEm))
    if (lane < 0) continue
    const item = { comment, text, width: size, lane }
    state.lanes[lane] = item
    state.active.push(item)
  }
  state.active = state.active.filter(item => time - item.comment.time < lifetime)
  state.time = time
  ctx.lineWidth = 2 * render.stroke
  ctx.lineJoin = 'round'
  ctx.strokeStyle = '#000'
  for (const item of state.active) {
    const x = width - (time - item.comment.time) / lifetime * (width + item.width)
    const y = font * 1.3 + item.lane * row
    ctx.strokeText(item.text, x, y)
    ctx.fillStyle = '#' + item.comment.color.toString(16).padStart(6, '0')
    ctx.fillText(item.text, x, y)
  }
  let next = state.cursor
  while (next < track.comments.length && track.comments[next].mode !== 1) next++
  return state.active.length ? 0 : track.comments[next]?.time ?? Infinity
}
