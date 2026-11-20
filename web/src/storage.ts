import { legacyWorkId, workId, type Source, type Work } from './api.ts'

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
export function getRecent(): Recent[] {
  const deduplicated = new Map<string, Recent>()
  for (const item of Object.values(read<Record<string, Recent>>('recent', {})).sort((a, b) => b.at - a.at)) {
    const display = item.work.display
    const id = display?.title != null && item.work.id === legacyWorkId(display) ? workId(display) : item.work.id
    if (!deduplicated.has(id)) deduplicated.set(id, { ...item, work: { ...item.work, id } })
  }
  return [...deduplicated.values()]
}
const bookmarkKey = (work: string, source: Source) => 'bookmark:' + JSON.stringify([work, source.site.name, source.id])
export function getBookmark(work: string, source: Source): Bookmark | undefined {
  const saved = read<Bookmark | undefined>(bookmarkKey(work, source), undefined)
  return saved ?? (source.title == null ? undefined : read<Bookmark | undefined>(bookmarkKey(legacyWorkId(source), source), undefined)) ?? getRecent().find(item => item.work.id === work && item.source.site.name === source.site.name && item.source.id === source.id)
}
export function recordStarted(selection: Selection, at = Date.now()) {
  const { line, episode, index } = selection
  write(bookmarkKey(selection.work.id, selection.source), { line, episode, index })
  const records = Object.fromEntries(getRecent().map(item => [item.work.id, item]))
  records[selection.work.id] = { ...selection, at }
  const recent = Object.entries(records).sort(([, a], [, b]) => b.at - a.at).slice(0, 100)
  write('recent', Object.fromEntries(recent))
}
export function positionKey(s: Selection, id = s.work.id) {
  return 'position:' + JSON.stringify([id, s.source.site.name, s.source.id, s.line, s.episode, s.episode ? null : s.index])
}
export const getPosition = (s: Selection) => read<number | undefined>(positionKey(s), undefined)
  ?? (s.source.title == null ? 0 : read(positionKey(s, legacyWorkId(s.source)), 0))
export function savePosition(s: Selection, position: number) {
  if (Number.isFinite(position) && position >= 0) write(positionKey(s), position)
}
export function getSpeed(work: string, remember: boolean, sources: Source[] = []) {
  if (!remember) return 1
  return read<number | undefined>('speed:' + work, undefined)
    ?? sources.map(source => read<number | undefined>('speed:' + legacyWorkId(source), undefined)).find(value => value !== undefined)
    ?? 1
}
export function saveSpeed(work: string, speed: number, remember: boolean) {
  if (remember) write('speed:' + work, speed)
}
export const getVolume = (remember: boolean) => remember
  ? read('volume', { volume: 1, muted: false }) : { volume: 1, muted: false }
export function saveVolume(volume: number, muted: boolean, remember: boolean) {
  if (remember) write('volume', { volume, muted })
}
