import Hls, { type HlsConfig, type LoaderCallbacks, type LoaderConfiguration, type PlaylistLoaderContext } from 'hls.js'
import { filterPlaylist, type Interval } from './hls.ts'

function singleVariant(text: string, base: string) {
  const lines = text.split(/\r?\n/).filter(Boolean)
  const variants = lines.flatMap((line, i) => line.startsWith('#EXT-X-STREAM-INF:') ? [lines[i + 1]] : [])
  if (lines[0] !== '#EXTM3U' || variants.length !== 1 || !variants[0] || variants[0].startsWith('#') ||
    lines.some(line => /^#EXT-X-(?:MEDIA:|I-FRAME-STREAM-INF:|SESSION-KEY:|DEFINE:)/.test(line) || line.startsWith('#EXTINF:'))) return
  try { return new URL(variants[0], base).href } catch { return undefined }
}

export function playlistLoader(site: string, publish: (intervals: Interval[]) => void): HlsConfig['pLoader'] {
  let child: string | undefined
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
            if (type === 'manifest') child = singleVariant(raw, response.url || ctx.url)
            if ((type === 'manifest' && !child) || (type === 'level' && ctx.url === child)) {
              const filtered = filterPlaylist(site, raw)
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
