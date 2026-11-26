const ttl = 7 * 24 * 60 * 60_000
const maxBytes = 1024 * 1024
const maxEntries = 8
const storeName = 'recent'
type Entry = { url: string; at: number; bytes: ArrayBuffer; type: string }
const attempted = new Set<string>()
let writes = Promise.resolve()
let database: Promise<IDBDatabase> | undefined

function result<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

function posterDatabase() {
  if (database) return database
  const request = indexedDB.open('tigertv-posters-v1', 1)
  request.onupgradeneeded = () => request.result.createObjectStore(storeName, { keyPath: 'url' }).createIndex('at', 'at')
  return database = result(request)
}

export async function cachedRecentPoster(url: string): Promise<Blob | undefined> {
  if (!globalThis.indexedDB) return
  try {
    const db = await posterDatabase()
    const entry: Entry | undefined = await result(db.transaction(storeName).objectStore(storeName).get(url))
    if (!entry) return
    const age = Date.now() - entry.at
    if (age >= 0 && age < ttl) return new Blob([entry.bytes], { type: entry.type })
    await result(db.transaction(storeName, 'readwrite').objectStore(storeName).delete(url))
  } catch { }
}

export function rememberRecentPoster(url: string) {
  if (!globalThis.indexedDB || attempted.has(url)) return
  if (attempted.size >= maxEntries) attempted.delete(attempted.values().next().value!)
  attempted.add(url)
  writes = writes.then(async () => {
    if (await cachedRecentPoster(url)) return
    // Only persist a readable, size-bounded image. Other posters use the browser's HTTP cache.
    const response = await fetch(url, {
      credentials: 'omit', referrerPolicy: 'no-referrer', cache: 'force-cache', signal: AbortSignal.timeout(8000),
    })
    const type = response.headers.get('Content-Type') ?? ''
    if (!response.ok || !type.startsWith('image/') || !response.body) return
    const reader = response.body.getReader()
    const bytes = new Uint8Array(maxBytes)
    let size = 0
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      if (size + value.length > maxBytes) { await reader.cancel(); return }
      bytes.set(value, size)
      size += value.length
    }
    const db = await posterDatabase()
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(storeName, 'readwrite')
      tx.oncomplete = () => resolve()
      tx.onabort = () => reject(tx.error)
      const store = tx.objectStore(storeName)
      store.put({ url, at: Date.now(), bytes: bytes.buffer.slice(0, size), type } satisfies Entry)
      const keys = store.index('at').getAllKeys()
      keys.onsuccess = () => {
        for (const key of keys.result.slice(0, Math.max(0, keys.result.length - maxEntries))) store.delete(key)
      }
    })
  }).catch(() => {})
}
