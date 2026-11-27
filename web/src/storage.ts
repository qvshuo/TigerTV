import { type Source, type Work } from './api.ts'

export const defaults = { autoNext: true, rememberSpeed: true, rememberVolume: true, filterAds: true, danmakuServer: '' }
export type Settings = typeof defaults
export type PlaybackOptions = { speed: number; volume: number; muted: boolean; danmakuEnabled: boolean }
export type Selection = { work: Work; source: Source; line: string; episode: string; index: number }
export type Bookmark = Pick<Selection, 'line' | 'episode' | 'index'>
export type Recent = Selection & { at: number; progress?: number }
const prefix = 'tigertv:v1:'
const memory = new Map<string, unknown>()

export function read<T>(key: string, fallback: T): T {
  if (memory.has(key)) return memory.get(key) as T
  try { return JSON.parse(localStorage.getItem(prefix + key) ?? 'null') ?? fallback }
  catch { return fallback }
}
export function write(key: string, value: unknown) {
  try {
    localStorage.setItem(prefix + key, JSON.stringify(value))
    memory.delete(key)
  } catch { memory.set(key, value) }
}

export const getSettings = (): Settings => ({ ...defaults, ...read('settings', {}) })
export function getRecent(): Recent[] {
  return Object.values(read<Record<string, Recent>>('recent', {})).sort((a, b) => b.at - a.at)
}
const bookmarkKey = (work: string, source: Source) => 'bookmark:' + JSON.stringify([work, source.site.name, source.id])
export const getBookmark = (work: string, source: Source) =>
  read<Bookmark | undefined>(bookmarkKey(work, source), undefined)
export function recordStarted(selection: Selection, at = Date.now()) {
  const { line, episode, index } = selection
  write(bookmarkKey(selection.work.id, selection.source), { line, episode, index })
  const records = Object.fromEntries(getRecent().map(item => [item.work.id, item]))
  const previous = records[selection.work.id]
  records[selection.work.id] = { ...selection, at,
    progress: previous && positionKey(previous) === positionKey(selection) ? previous.progress : undefined,
    work: previous ? { ...selection.work, display: previous.work.display } : selection.work,
  }
  const recent = Object.entries(records).sort(([, a], [, b]) => b.at - a.at).slice(0, 100)
  write('recent', Object.fromEntries(recent))
}
export const episodeIdentity = (s: Selection) => [s.line, s.episode, s.index] as const
export function positionKey(s: Selection) {
  return 'position:' + JSON.stringify([s.work.id, s.source.site.name, s.source.id, ...episodeIdentity(s)])
}
export function getPosition(s: Selection) {
  return read(positionKey(s), 0)
}
export function savePosition(s: Selection, position: number, progress?: number) {
  if (!Number.isFinite(position) || position < 0) return
  write(positionKey(s), position)
  const records = read<Record<string, Recent>>('recent', {})
  const recent = records[s.work.id]
  if (progress !== undefined && recent && positionKey(recent) === positionKey(s) && recent.progress !== progress) {
    write('recent', { ...records, [s.work.id]: { ...recent, progress } })
  }
}
export const getSpeed = (work: string, remember: boolean) => remember ? read('speed:' + work, 1) : 1
export function saveSpeed(work: string, speed: number, remember: boolean) {
  if (remember) write('speed:' + work, speed)
}
export const getVolume = (remember: boolean) => remember
  ? read('volume', { volume: 1, muted: false }) : { volume: 1, muted: false }
export function saveVolume(volume: number, muted: boolean, remember: boolean) {
  if (remember) write('volume', { volume, muted })
}
