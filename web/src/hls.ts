import heuristics from '../../shared/hls-heuristics.json' with { type: 'json' }

export type Interval = { start: number; end: number }
type Segment = { uri: string; duration: string; micros: number; discontinuity: boolean; key: string; implicitIv: boolean; tags: string[] }

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
    } else if (line.startsWith('#EXT-X-PROGRAM-DATE-TIME:') || line.startsWith('#EXT-X-CUE-OUT:') || line === '#EXT-X-CUE-IN') {
      if (duration) return
      tags.push(line)
    } else if (line.startsWith('#')) {
      if (duration || segments.length || !/^#EXT-X-(?:VERSION:\d+|TARGETDURATION:\d+|MEDIA-SEQUENCE:\d+|PLAYLIST-TYPE:VOD)$/.test(line)) return
      headers.push(line)
    } else {
      if (!duration || line.trim() !== line || /\s/.test(line)) return
      segments.push({ uri: line, duration, micros, discontinuity, key, implicitIv, tags })
      tags = []
      discontinuity = false
      duration = ''
    }
  }
  if (duration || tags.length || !segments.length) return
  const sequences = headers.filter(line => line.startsWith('#EXT-X-MEDIA-SEQUENCE:'))
  if (sequences.length > 1) return
  const sequence = sequences[0] ? Number(sequences[0].slice('#EXT-X-MEDIA-SEQUENCE:'.length)) : 0
  if (!Number.isSafeInteger(sequence) || !Number.isSafeInteger(sequence + segments.length)) return
  return { headers, segments, sequence, trailingDiscontinuity: discontinuity }
}

function parseKey(text: string) {
  const items = [...text.matchAll(/([A-Z-]+)=("[^"]*"|[^,]+)/g)]
  if (items.map(item => item[0]).join(',') !== text) return
  const values = new Map(items.map(([, name, value]) => [name, value]))
  if (values.size !== items.length) return
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
  const { minSeconds, maxSeconds, maxFraction, flankSegments: flank, pathTokens } = heuristics
  const total = segments.reduce((sum, segment) => sum + segment.micros, 0)
  const accepted = new Set<number>()
  let removed = 0
  function propose(start: number, end: number, declared?: number, verified = false) {
    if (start >= end) return
    const indices = range(start, end)
    if (indices.some(index => accepted.has(index))) return
    const duration = indices.reduce((sum, index) => sum + segments[index].micros, 0)
    const seconds = duration / 1e6
    if (seconds < minSeconds || seconds > maxSeconds || (!verified && removed + duration > total * maxFraction) ||
      (declared !== undefined && Math.abs(seconds - declared) > 0.25)) return
    for (const index of indices) accepted.add(index)
    removed += duration
  }

  const paths = segments.map(segment => segment.uri.split(/[?#]/, 1)[0])
  const spliceBoundaries = [...segments.flatMap((segment, index) => segment.discontinuity ? [index] : []), segments.length]
  for (const profile of heuristics.sourceGroups) {
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
      const durationIndex = profile.durationsMicros.findIndex(durations => durations.length === group.length &&
        group.every((segment, index) => segment.micros === durations[index] && !encrypted(segment) &&
          segmentPattern.test(paths[start + index])))
      if (durationIndex < 0) continue
      seenDurations.add(durationIndex)
      const key = JSON.stringify(paths.slice(start, end))
      const runs = matches.get(key) ?? []
      runs.push([start, end])
      matches.set(key, runs)
    }
    if (profile.requireAllDurations && seenDurations.size !== profile.durationsMicros.length) continue
    for (const runs of matches.values()) if (!profile.repeated || runs.length > 1) {
      for (const [start, end] of runs) propose(start, end, undefined, true)
    }
  }

  let cueStart = -1
  let cueSeconds = 0
  for (const [index, segment] of segments.entries()) {
    if (segment.tags.includes('#EXT-X-CUE-IN') && cueStart >= 0) {
      propose(cueStart, index, cueSeconds)
      cueStart = -1
    }
    const cues = segment.tags.filter(tag => tag.startsWith('#EXT-X-CUE-OUT:'))
    if (cues.length === 1) {
      const seconds = Number(cues[0].slice('#EXT-X-CUE-OUT:'.length).replace(/^DURATION=/, ''))
      cueStart = cueStart < 0 && Number.isFinite(seconds) ? index : -1
      cueSeconds = seconds
    }
  }

  const families = paths.map(path => path.replace(/[0-9]+(?=\.[^./]+$)/, ''))
  const adPaths = paths.map(path => path.toLowerCase().split(/[/._-]/).some(part => pathTokens.includes(part)))
  for (let start = 0; start < segments.length;) {
    if (!adPaths[start]) { start++; continue }
    let end = start + 1
    while (end < segments.length && adPaths[end]) end++
    if (start >= flank && end + flank <= segments.length &&
      !adPaths.slice(start - flank, start).some(Boolean) && !adPaths.slice(end, end + flank).some(Boolean)) {
      const sameFamily = families.slice(start - flank, start).every(family => family === families[start - 1]) &&
        families.slice(end, end + flank).every(family => family === families[start - 1])
      if (sameFamily || (segments[start].discontinuity && segments[end].discontinuity)) propose(start, end)
    }
    start = end
  }

  const boundaries = [0, ...segments.flatMap((s, i) => i && (s.discontinuity || encrypted(segments[i - 1]) !== encrypted(s)) ? [i] : []), segments.length]
  const repeated = new Map<string, [number, number][]>()
  for (const [i, start] of boundaries.slice(0, -1).entries()) {
    const end = boundaries[i + 1]
    if (start < flank || end - start < 2 || (end !== segments.length && end + flank > segments.length) ||
      !segments.slice(start - flank, start).every(encrypted) || !segments.slice(end, end + flank).every(encrypted) ||
      segments.slice(start, end).some(encrypted)) continue
    const signature = JSON.stringify(range(start, end).map(index => [paths[index], segments[index].micros]))
    const runs = repeated.get(signature) ?? []
    runs.push([start, end])
    repeated.set(signature, runs)
  }
  for (const runs of repeated.values()) if (runs.length > 1) {
    for (const [start, end] of runs) propose(start, end)
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
    if (key && key !== outputKey) output.push(key)
    outputKey = key
    output.push(...segment.tags.filter(tag => tag !== '#EXT-X-CUE-IN' || !dropped.has(index - 1)), segment.duration, segment.uri)
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
