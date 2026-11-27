import heuristics from '../../shared/hls-heuristics.json' with { type: 'json' }

export type Interval = { start: number; end: number }
type ByteRange = { length: number; offset: number }
type InitMap = { tag: string; key: string }
type Segment = { uri: string; duration: string; micros: number; discontinuity: boolean; key: string; implicitIv: boolean; tags: string[]; map?: InitMap; byteRange?: ByteRange }

function parseRange(raw: string, previousEnd?: number): ByteRange | undefined {
  const parts = /^([0-9]+)(?:@([0-9]+))?$/.exec(raw)
  if (!parts) return
  const length = Number(parts[1])
  const offset = parts[2] === undefined ? previousEnd : Number(parts[2])
  if (offset === undefined || length <= 0 || !Number.isSafeInteger(length + offset)) return
  return { length, offset }
}

function attributes(text: string) {
  const items = [...text.matchAll(/([A-Z-]+)=("[^"]*"|[^,]+)/g)]
  const values = new Map(items.map(([, name, value]) => [name, value]))
  if (values.size === items.length && items.map(item => item[0]).join(',') === text) return values
}

function vod(text: string) {
  const lines = text.split(/\r?\n/).filter(Boolean)
  if (lines[0] !== '#EXTM3U' || lines.at(-1) !== '#EXT-X-ENDLIST') return
  const end = lines.indexOf('#EXT-X-ENDLIST')
  // Some responses append a partial URI and an exact copy of the final discontinuity and segment.
  if (end !== lines.length - 1 && (end < 4 || lines.length !== end + 6 ||
    !/^[0-9a-f]+$/.test(lines[end + 1]) || lines[end - 3] !== '#EXT-X-DISCONTINUITY' ||
    lines.slice(end - 3, end).some((line, index) => line !== lines[end + 2 + index]))) return
  const headers = ['#EXTM3U']
  const segments: Segment[] = []
  let duration = ''
  let micros = 0
  let total = 0
  let key = ''
  let implicitIv = false
  let map: InitMap | undefined
  let range: string | undefined
  let tags: string[] = []
  let discontinuity = false
  for (const line of lines.slice(1, end)) {
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
      const attributes = parseKey(line.slice(11))
      if (duration || !attributes) return
      key = line
      implicitIv = attributes.get('METHOD') === 'AES-128' && !attributes.has('IV')
    } else if (line.startsWith('#EXT-X-MAP:')) {
      const attrs = attributes(line.slice(11))
      if (duration || implicitIv || !attrs || [...attrs.keys()].some(name => name !== 'URI' && name !== 'BYTERANGE') ||
          !/^"[^"\s]+"$/.test(attrs.get('URI') ?? '')) return
      const mapRange = attrs.get('BYTERANGE')
      if (mapRange !== undefined && (!/^"[^"]+"$/.test(mapRange) || !parseRange(mapRange.slice(1, -1)))) return
      map = { tag: line, key }
    } else if (line.startsWith('#EXT-X-BYTERANGE:')) {
      if (range !== undefined) return
      range = line.slice(17)
    } else if (line.startsWith('#EXT-X-PROGRAM-DATE-TIME:') || line.startsWith('#EXT-X-CUE-OUT:') || line === '#EXT-X-CUE-IN') {
      if (duration) return
      tags.push(line)
    } else if (line.startsWith('#') && !line.startsWith('#EXT')) {
      continue
    } else if (line.startsWith('#')) {
      if (duration || segments.length || !/^#EXT-X-(?:VERSION:\d+|TARGETDURATION:\d+|MEDIA-SEQUENCE:\d+|PLAYLIST-TYPE:VOD)$/.test(line)) return
      headers.push(line)
    } else {
      if (!duration || line.trim() !== line || /\s/.test(line)) return
      const previous = segments.at(-1)
      const prior = previous?.uri === line ? previous.byteRange : undefined
      const byteRange = range === undefined ? undefined : parseRange(range, prior && prior.offset + prior.length)
      if (range !== undefined && !byteRange) return
      segments.push({ uri: line, duration, micros, discontinuity, key, implicitIv, tags, map, byteRange })
      range = undefined
      tags = []
      discontinuity = false
      duration = ''
    }
  }
  if (duration || range !== undefined || tags.length || !segments.length) return
  const sequences = headers.filter(line => line.startsWith('#EXT-X-MEDIA-SEQUENCE:'))
  if (sequences.length > 1) return
  const sequence = sequences[0] ? Number(sequences[0].slice('#EXT-X-MEDIA-SEQUENCE:'.length)) : 0
  if (!Number.isSafeInteger(sequence) || !Number.isSafeInteger(sequence + segments.length)) return
  return { headers, segments, sequence, trailingDiscontinuity: discontinuity }
}

function parseKey(text: string) {
  const values = attributes(text)
  if (!values) return
  if (text === 'METHOD=NONE') return values
  const iv = values.get('IV')
  const format = values.get('KEYFORMAT')
  if (values.get('METHOD') !== 'AES-128' || !/^"[^"]+"$/.test(values.get('URI') ?? '') ||
    (iv !== undefined && !/^0x[\da-f]{32}$/i.test(iv)) || (format !== undefined && format !== '"identity"') ||
    [...values.keys()].some(key => !['METHOD', 'URI', 'IV', 'KEYFORMAT'].includes(key))) return
  return values
}

const encrypted = (segment: Segment) => Boolean(segment.key && segment.key !== '#EXT-X-KEY:METHOD=NONE')
const range = (start: number, end: number) => Array.from({ length: end - start }, (_, index) => start + index)

function smartAdIndices(segments: Segment[], playlistHost: string) {
  const { minSeconds, maxSeconds } = heuristics.common
  const accepted = new Set<number>()
  function propose(start: number, end: number) {
    const indices = range(start, end)
    if (!indices.length || indices.some(index => accepted.has(index))) return
    const seconds = indices.reduce((sum, index) => sum + segments[index].micros, 0) / 1e6
    if (seconds < minSeconds || seconds > maxSeconds) return
    for (const index of indices) accepted.add(index)
  }

  const paths = segments.map(segment => segment.uri.split(/[?#]/, 1)[0])
  const spliceBoundaries = [...segments.flatMap((segment, index) => segment.discontinuity ? [index] : []), segments.length]
  for (const profile of heuristics.sourceRules) {
    if (!new RegExp(`^(?:${profile.manifestHost})$`).test(playlistHost)) continue
    const segmentPattern = new RegExp(`^(?:${profile.segmentUri})$`)
    const contentPattern = profile.contentUri ? new RegExp(`^(?:${profile.contentUri})$`) : undefined
    const contentSide = (uri: string) => contentPattern ? contentPattern.test(uri) : !segmentPattern.test(uri)
    const matches = new Map<string, [number, number][]>()
    const seenDurations = new Set<number>()
    const innerOffsets = profile.innerDiscontinuityOffsets
    const boundaryStep = innerOffsets?.length ? 2 : 1
    for (let index = 0; index + boundaryStep < spliceBoundaries.length; index++) {
      const start = spliceBoundaries[index]
      const end = spliceBoundaries[index + boundaryStep]
      if (innerOffsets?.length && !innerOffsets.includes(spliceBoundaries[index + 1] - start)) continue
      if (!start || (end === segments.length && !profile.allowEnd) || !contentSide(paths[start - 1]) ||
        (end !== segments.length && !contentSide(paths[end]))) continue
      const group = segments.slice(start, end)
      if (!group.every((segment, offset) => !encrypted(segment) && segmentPattern.test(paths[start + offset]))) continue
      const durationIndex = profile.durationsMicros?.findIndex(durations => durations.length === group.length &&
        group.every((segment, offset) => segment.micros === durations[offset])) ?? -1
      const windows = profile.durationWindowsMicros
      const matchesWindows = windows?.length === group.length && windows.every(([min, max], offset) =>
        group[offset].micros >= min && group[offset].micros <= max)
      if (durationIndex < 0 && !matchesWindows) continue
      if (durationIndex >= 0) seenDurations.add(durationIndex)
      const key = JSON.stringify(group.map((segment, offset) => [paths[start + offset], segment.byteRange]))
      const runs = matches.get(key) ?? []
      runs.push([start, end])
      matches.set(key, runs)
    }
    if (profile.requireAllDurations && seenDurations.size !== profile.durationsMicros?.length) continue
    for (const runs of matches.values()) if (!profile.repeated || runs.length > 1) {
      for (const [start, end] of runs) propose(start, end)
    }
  }

  return accepted
}

export function filterPlaylist(text: string, playlistHost = ''): { text: string; removed: Interval[] } | undefined {
  const playlist = vod(text)
  if (!playlist) return
  const { segments } = playlist
  const dropped = smartAdIndices(segments, playlistHost)
  if (!dropped.size || dropped.size === segments.length) return
  const output = [...playlist.headers]
  const removed: Interval[] = []
  let total = 0
  let pending = false
  let kept = false
  let outputKey = ''
  let outputMap: InitMap | undefined
  function emitKey(key: string) {
    if (key === outputKey) return
    output.push(key || '#EXT-X-KEY:METHOD=NONE')
    outputKey = key
  }
  let synthesizedIv = false
  for (const [index, segment] of segments.entries()) {
    pending ||= segment.discontinuity
    const start = Math.round(total / 1000) / 1000
    total += segment.micros
    const end = Math.round(total / 1000) / 1000
    if (end <= start) return
    if (dropped.has(index)) {
      pending = true
      const previous = removed.at(-1)
      if (previous?.end === start) previous.end = end
      else removed.push({ start, end })
      continue
    }
    if (pending && kept) output.push('#EXT-X-DISCONTINUITY')
    const key = segment.implicitIv && removed.length
      ? segment.key + ',IV=0x' + (playlist.sequence + index).toString(16).padStart(32, '0') : segment.key
    synthesizedIv ||= key !== segment.key
    const { map, byteRange } = segment
    if (map && (map.tag !== outputMap?.tag || map.key !== outputMap?.key)) {
      emitKey(map.key)
      output.push(map.tag)
      outputMap = map
    }
    emitKey(key)
    output.push(...segment.tags.filter(tag => tag !== '#EXT-X-CUE-IN' || !dropped.has(index - 1)), segment.duration)
    if (byteRange) output.push(`#EXT-X-BYTERANGE:${byteRange.length}@${byteRange.offset}`)
    output.push(segment.uri)
    pending = false
    kept = true
  }
  if (!kept) return
  if (synthesizedIv) {
    const version = playlist.headers.findIndex(line => line.startsWith('#EXT-X-VERSION:'))
    if (version < 0) output.splice(1, 0, '#EXT-X-VERSION:2')
    else if (Number(output[version].slice('#EXT-X-VERSION:'.length)) < 2) output[version] = '#EXT-X-VERSION:2'
  }
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
