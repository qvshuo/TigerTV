const integerFields = new Set(['code', 'pagecount', 'vod_id'])

export function object(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
}

export function parseJson(text: string, fields = integerFields): unknown {
  return JSON.parse(text, (key: string, value: unknown, context?: { source?: string }) =>
    typeof value === 'number' && fields.has(key) && context?.source ? context.source : value)
}

export async function readBounded<T>(response: Response, limit: number, consume: (bytes: Uint8Array) => void, result: () => T): Promise<T> {
  if (!response.ok || Number(response.headers.get('Content-Length')) > limit) {
    void response.body?.cancel().catch(() => {})
    throw new Error(response.ok ? '响应过大' : `HTTP ${response.status}`)
  }
  const reader = response.body?.getReader()
  if (!reader) throw new Error('响应为空')
  let bytes = 0
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      bytes += value.byteLength
      if (bytes > limit) throw new Error('响应过大')
      consume(value)
    }
    return result()
  } finally {
    void reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}


export function readText(response: Response) {
  const decoder = new TextDecoder()
  let text = ''
  return readBounded(response, 10 * 1024 * 1024, bytes => { text += decoder.decode(bytes, { stream: true }) },
    () => text + decoder.decode())
}
