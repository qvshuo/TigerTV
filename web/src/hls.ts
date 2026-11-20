import rules from '../../shared/hls-ad-rules.json' with { type: 'json' }

export type Interval = { start: number; end: number }
type Segment = { uri: string; duration: string; micros: number; discontinuity: boolean; key: string }
export const supportsFilter = (site: string) => rules.some(rule => rule.site === site)

function fingerprint(uri: string) {
  let hash = 14695981039346656037n
  for (const byte of new TextEncoder().encode(uri)) hash = BigInt.asUintN(64, (hash ^ BigInt(byte)) * 1099511628211n)
  return hash.toString(16).padStart(16, '0')
}

function vod(text: string) {
  const lines = text.split(/\r?\n/).filter(Boolean)
  if (lines[0] !== '#EXTM3U' || lines.at(-1) !== '#EXT-X-ENDLIST') return
  const headers = ['#EXTM3U']
  const segments: Segment[] = []
  let duration = ''
  let micros = 0
  let total = 0
  let key = ''
  let discontinuity = false
  for (const line of lines.slice(1, -1)) {
    if (line.startsWith('#EXTINF:')) {
      if (duration || !line.includes(',')) return
      const seconds = Number(line.slice(8, line.indexOf(',')))
      micros = Math.round(seconds * 1e6)
      total += micros
      if (!Number.isSafeInteger(total) || micros <= 0 || seconds > 86400 || Math.abs(seconds * 1e6 - micros) > 1e-6) return
      duration = line
    } else if (line === '#EXT-X-DISCONTINUITY') {
      if (duration || discontinuity) return
      discontinuity = true
    } else if (line.startsWith('#EXT-X-KEY:')) {
      if (duration || !validKey(line.slice(11))) return
      key = line
    } else if (line.startsWith('#')) {
      if (duration || segments.length || !/^#EXT-X-(?:VERSION:\d+|TARGETDURATION:\d+|MEDIA-SEQUENCE:0|PLAYLIST-TYPE:VOD)$/.test(line)) return
      headers.push(line)
    } else {
      if (!duration || line.trim() !== line || /\s/.test(line)) return
      segments.push({ uri: line, duration, micros, discontinuity, key })
      discontinuity = false
      duration = ''
    }
  }
  if (duration || !segments.length) return
  return { headers, segments, trailingDiscontinuity: discontinuity }
}

function validKey(text: string) {
  if (text === 'METHOD=NONE') return true
  const items = [...text.matchAll(/([A-Z-]+)=("[^"]*"|[^,]+)/g)]
  if (items.length !== 3 || items.map(item => item[0]).join(',') !== text) return false
  const values = new Map(items.map(([, name, value]) => [name, value]))
  return values.get('METHOD') === 'AES-128' && /^"[^"]+"$/.test(values.get('URI') ?? '') &&
    /^0x[\da-f]{32}$/i.test(values.get('IV') ?? '')
}

type Rule = { site: string; uriFingerprints: string[]; durations: number[]; pathPrefix?: string; allowEnd?: boolean }

export function filterPlaylist(site: string, text: string, definitions: Rule[] = rules): { text: string; removed: Interval[] } | undefined {
  const applicable = definitions.filter(rule => rule.site === site)
  if (!applicable.length) return
  const playlist = vod(text)
  if (!playlist) return
  const { segments } = playlist
  const removedUris = new Set<string>()
  let matched = 0
  for (const [index, segment] of segments.entries()) {
    if (!segment.discontinuity) continue
    for (const rule of applicable) {
      const count = rule.uriFingerprints.length
      const end = index + count
      if (end > segments.length || (end === segments.length ? !rule.allowEnd : !segments[end].discontinuity)) continue
      const group = segments.slice(index, end)
      if (!group.every((item, i) => (!i || !item.discontinuity) && (!item.key || item.key === '#EXT-X-KEY:METHOD=NONE') &&
        item.micros === Math.round(rule.durations[i] * 1e6) && !/[?#]/.test(item.uri) &&
        (!rule.pathPrefix || item.uri.startsWith(rule.pathPrefix)) && fingerprint(item.uri) === rule.uriFingerprints[i])) continue
      for (const item of group) removedUris.add(item.uri)
      matched += count
      break
    }
  }
  if (!matched || segments.filter(segment => removedUris.has(segment.uri)).length !== matched) return
  const output = [...playlist.headers]
  const removed: Interval[] = []
  let total = 0
  let pending = false
  let kept = false
  let outputKey = ''
  for (const segment of segments) {
    pending ||= segment.discontinuity
    const start = Math.round(total / 1000) / 1000
    total += segment.micros
    const end = Math.round(total / 1000) / 1000
    if (end <= start) return
    if (removedUris.has(segment.uri)) {
      const previous = removed.at(-1)
      if (previous?.end === start) previous.end = end
      else removed.push({ start, end })
      continue
    }
    if (pending && kept) output.push('#EXT-X-DISCONTINUITY')
    if (segment.key && segment.key !== outputKey) output.push(segment.key)
    outputKey = segment.key
    output.push(segment.duration, segment.uri)
    pending = false
    kept = true
  }
  if (!kept) return
  if (playlist.trailingDiscontinuity) output.push('#EXT-X-DISCONTINUITY')
  output.push('#EXT-X-ENDLIST', '')
  return { text: output.join('\n'), removed }
}

export function originalTime(time: number, removed: Interval[]) {
  for (const { start, end } of removed) {
    if (time < start) break
    time += end - start
  }
  return time
}
export function effectiveTime(time: number, removed: Interval[]) {
  let result = time
  for (const { start, end } of removed) {
    if (time <= start) break
    result -= Math.min(time, end) - start
  }
  return result
}
