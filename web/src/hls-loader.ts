import Hls, { type HlsConfig, type LoaderCallbacks, type LoaderConfiguration, type PlaylistLoaderContext } from 'hls.js'
import { filterPlaylist, type Interval } from './hls.ts'

function attributes(line: string) {
  return new Map([...line.slice(line.indexOf(':') + 1).matchAll(/([A-Z0-9-]+)=("[^"]*"|[^,]+)/g)]
    .map(([, key, value]) => [key, value.replace(/^"|"$/g, '')]))
}
const audioCodecs = new Set(['mp4a', 'ac-3', 'ec-3', 'ac-4', 'opus', 'flac', 'alac', 'mp3', 'mha1', 'mhm1'])

export function hlsVideoVariants(text: string, base: string) {
  const lines = text.split(/\r?\n/).filter(Boolean)
  const variants = new Map<string, boolean>()
  if (lines[0] !== '#EXTM3U' || lines.some(line => /^#(?:EXTINF:|EXT-X-(?:DEFINE|SESSION-KEY):)/.test(line))) return variants
  const resolve = (uri: string | undefined) => {
    if (!uri || uri.startsWith('#') || /\s/.test(uri)) return
    try { return new URL(uri, base).href } catch { return }
  }
  const audio = lines.filter(line => line.startsWith('#EXT-X-MEDIA:')).map(attributes)
    .filter(entry => entry.get('TYPE') === 'AUDIO' && entry.has('URI'))
  const audioUrls = new Set(audio.map(entry => resolve(entry.get('URI'))))
  const audioGroups = new Set(audio.map(entry => entry.get('GROUP-ID')).filter(group => group !== undefined))
  for (const [index, line] of lines.entries()) {
    if (!line.startsWith('#EXT-X-STREAM-INF:')) continue
    const uri = resolve(lines[index + 1])
    if (!uri) return new Map<string, boolean>()
    const entry = attributes(line)
    const codecs = entry.get('CODECS')?.split(',')
    if (audioUrls.has(uri) || (!entry.has('RESOLUTION') && !entry.has('VIDEO') &&
      codecs?.every(codec => audioCodecs.has(codec.trim().toLowerCase().split('.')[0])))) continue
    variants.set(uri, variants.get(uri) !== false && !audioGroups.has(entry.get('AUDIO') ?? ''))
  }
  return variants
}

export function playlistLoader(publish: (intervals: Interval[]) => void): HlsConfig['pLoader'] {
  let variants = new Map<string, boolean>()
  // The stock loader also handles the playlist-specific context fields.
  const Base = Hls.DefaultConfig.loader as NonNullable<HlsConfig['pLoader']>
  return class extends Base {
    load(context: PlaylistLoaderContext, config: LoaderConfiguration, callbacks: LoaderCallbacks<PlaylistLoaderContext>) {
      const type = context.type
      super.load(context, config, {
        ...callbacks,
        onSuccess(response, stats, ctx, networkDetails) {
          const raw = response.data
          if (typeof raw === 'string' && raw.length <= 10 * 1024 * 1024) {
            if (type === 'manifest') variants = hlsVideoVariants(raw, response.url || ctx.url)
            if (type === 'manifest' || (type === 'level' && variants.get(ctx.url) === true)) {
              const filtered = filterPlaylist(raw, new URL(response.url || ctx.url).hostname)
              publish(filtered?.removed ?? [])
              if (filtered) response = { ...response, data: filtered.text }
            }
          }
          callbacks.onSuccess(response, stats, ctx, networkDetails)
        },
      })
    }
  }
}
