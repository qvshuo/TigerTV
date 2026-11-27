import { getDetail, type Detail, type Source } from './api.ts'

export const sourceKey = (item: Source) => JSON.stringify([item.site.name, item.id])

export function catalogDetails() {
  const detailCache = new Map<string, { expires: number; value: Detail }>()
  const detailPending = new Map<string, Promise<Detail>>()
  let detailStates = $state.raw(new Map<string, 'ready' | 'empty' | 'failed'>())
  let detailVisit = $state.raw<AbortController>()

  function ensureDetailVisit() {
    if (!detailVisit || detailVisit.signal.aborted) {
      detailVisit = new AbortController()
      detailPending.clear()
      detailStates = new Map()
    }
    return detailVisit
  }

  function cachedDetail(key: string) {
    const entry = detailCache.get(key)
    if (entry && entry.expires > Date.now()) return entry.value
    if (entry) detailCache.delete(key)
  }

  function requestDetail(item: Source, visit = ensureDetailVisit()) {
    const key = sourceKey(item)
    const cached = cachedDetail(key)
    if (cached) return Promise.resolve(cached)
    const pending = detailPending.get(key)
    if (pending) return pending
    const request = getDetail(item, visit.signal).then(value => {
      if (!visit.signal.aborted) {
        if (detailCache.size >= 64) detailCache.delete(detailCache.keys().next().value!)
        detailCache.set(key, { expires: Date.now() + 5 * 60_000, value })
        detailStates = new Map(detailStates).set(key, value.lines.length ? 'ready' : 'empty')
      }
      return value
    }).catch(error => {
      if (!visit.signal.aborted) detailStates = new Map(detailStates).set(key, 'failed')
      throw error
    }).finally(() => {
      if (detailPending.get(key) === request) detailPending.delete(key)
    })
    detailPending.set(key, request)
    return request
  }

  return {
    get visit() { return detailVisit },
    enter: ensureDetailVisit,
    leave: () => detailVisit?.abort(),
    cached: cachedDetail,
    request: requestDetail,
    state: (key: string) => detailStates.get(key),
  }
}
