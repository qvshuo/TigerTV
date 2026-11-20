import type { Source, Work } from './api.ts'

export const defaultDanmakuServer = 'https://danmu-api.253520.xyz'
export const defaults = { autoNext: true, rememberSpeed: true, rememberVolume: true, filterAds: true, danmakuServer: '' }
export type Settings = typeof defaults
export type PlaybackOptions = { speed: number; volume: number; muted: boolean; danmakuEnabled: boolean }
export type Selection = { work: Work; source: Source; line: string; episode: string; index: number }
export type Bookmark = Pick<Selection, 'line' | 'episode' | 'index'>
export type Recent = Selection & { at: number }
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
export const getRecent = () => Object.values(read<Record<string, Recent>>('recent', {})).sort((a, b) => b.at - a.at)
const bookmarkKey = (work: string, source: Source) => 'bookmark:' + JSON.stringify([work, source.site.name, source.id])
export function getBookmark(work: string, source: Source): Bookmark | undefined {
  const saved = read<Bookmark | undefined>(bookmarkKey(work, source), undefined)
  return saved ?? getRecent().find(item => item.work.id === work && item.source.site.name === source.site.name && item.source.id === source.id)
}
export function recordStarted(selection: Selection, at = Date.now()) {
  const { line, episode, index } = selection
  write(bookmarkKey(selection.work.id, selection.source), { line, episode, index })
  const records = read<Record<string, Recent>>('recent', {})
  records[selection.work.id] = { ...selection, at }
  const recent = Object.entries(records).sort(([, a], [, b]) => b.at - a.at).slice(0, 100)
  write('recent', Object.fromEntries(recent))
}
export function positionKey(s: Selection) {
  return 'position:' + JSON.stringify([s.work.id, s.source.site.name, s.source.id, s.line, s.episode, s.episode ? null : s.index])
}
export const getPosition = (s: Selection) => read(positionKey(s), 0)
export function savePosition(s: Selection, position: number) {
  if (Number.isFinite(position) && position >= 0) write(positionKey(s), position)
}
export function getSpeed(work: string, remember: boolean) {
  return remember ? read('speed:' + work, 1) : 1
}
export function saveSpeed(work: string, speed: number, remember: boolean) {
  if (remember) write('speed:' + work, speed)
}
export const getVolume = (remember: boolean) => remember
  ? read('volume', { volume: 1, muted: false }) : { volume: 1, muted: false }
export function saveVolume(volume: number, muted: boolean, remember: boolean) {
  if (remember) write('volume', { volume, muted })
}
