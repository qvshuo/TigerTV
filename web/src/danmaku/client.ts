import selectionRules from '../../../shared/danmaku-selection.json' with { type: 'json' }
import { getJson, object, scalar } from '../api.ts'
import { season } from './matching.ts'

export type Title = { id: number; title: string; source: string }
export type DanmakuEpisode = { id: number; title: string; number: string; season?: number; seasonId?: string }
export type Comment = { time: number; mode: number; color: number; text: string }
export type Track = { duration: number; comments: Comment[] }

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
