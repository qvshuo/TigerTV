import selectionRules from '../../../shared/danmaku-selection.json' with { type: 'json' }
import type { DanmakuClient, DanmakuEpisode, Title, Track } from './client.ts'
import { matchingEpisode, season, titleCandidates } from './matching.ts'

export type DanmakuMatch = { title: Title; episode: DanmakuEpisode; episodes: DanmakuEpisode[] }
export type SavedDanmakuEpisode = { title: number; id: number; titleItem?: Title; episodeItem?: DanmakuEpisode }

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
