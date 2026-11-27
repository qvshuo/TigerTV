import selectionRules from '../../../shared/danmaku-selection.json' with { type: 'json' }
import type { Comment, Track } from './client.ts'

const render = selectionRules.render
const lifetime = render.lifetimeMs / 1000
type Active = { comment: Comment; text: string; width: number; lane: number }
type Layout = {
  track: Track; width: number; height: number; time: number; cursor: number;
  widths: Map<string, number>; active: Active[]; lanes: (Active | undefined)[]
}
const layouts = new WeakMap<HTMLCanvasElement, Layout>()

export function fitsDanmaku(width: number, previous: number, incoming: number, elapsed: number, gap: number) {
  if (elapsed >= lifetime) return true
  const phase = elapsed / lifetime
  return phase * (width + previous) - previous >= gap &&
    width - (1 - phase) * (width + incoming) >= gap
}

export function drawComments(canvas: HTMLCanvasElement, track: Track | undefined, time: number, reset = false) {
  const width = canvas.clientWidth
  const height = canvas.clientHeight
  const scale = Math.min(2, devicePixelRatio)
  if (canvas.width !== Math.round(width * scale)) canvas.width = Math.round(width * scale)
  if (canvas.height !== Math.round(height * scale)) canvas.height = Math.round(height * scale)
  const ctx = canvas.getContext('2d')
  if (!ctx) return Infinity
  ctx.setTransform(scale, 0, 0, scale, 0, 0)
  ctx.clearRect(0, 0, width, height)
  if (!track || !width || !height) { layouts.delete(canvas); return Infinity }
  const font = height / render.areaRatio * render.fontRatio
  const row = font * render.lineHeight
  const lanes = Math.max(1, Math.min(render.lanes, Math.floor(height / row)))
  ctx.font = `700 ${font}px system-ui, sans-serif`
  let layout = layouts.get(canvas)
  if (!layout || layout.track !== track || layout.width !== width || layout.height !== height ||
      reset || time < layout.time || time - layout.time > 1.5) {
    let low = 0
    let high = track.comments.length
    while (low < high) {
      const mid = (low + high) >>> 1
      if (track.comments[mid].time < time - 2 * lifetime) low = mid + 1
      else high = mid
    }
    const widths = layout?.height === height ? layout.widths : new Map<string, number>()
    layout = { track, width, height, time, cursor: low, widths, active: [], lanes: Array(lanes) }
    layouts.set(canvas, layout)
  }
  const state = layout
  while (state.cursor < track.comments.length && track.comments[state.cursor].time <= time) {
    const comment = track.comments[state.cursor++]
    if (comment.mode !== 1) continue
    state.active = state.active.filter(item => comment.time - item.comment.time < lifetime)
    if (state.active.length >= render.visibleLimit) continue
    const text = comment.text.slice(0, 100)
    let size = state.widths.get(text)
    if (size === undefined) {
      size = ctx.measureText(text).width
      if (state.widths.size >= 256) state.widths.delete(state.widths.keys().next().value!)
      state.widths.set(text, size)
    }
    const lane = state.lanes.findIndex(previous => !previous ||
      fitsDanmaku(width, previous.width, size, comment.time - previous.comment.time, font * render.gapEm))
    if (lane < 0) continue
    const item = { comment, text, width: size, lane }
    state.lanes[lane] = item
    state.active.push(item)
  }
  state.active = state.active.filter(item => time - item.comment.time < lifetime)
  state.time = time
  ctx.lineWidth = 2 * render.stroke
  ctx.lineJoin = 'round'
  ctx.strokeStyle = '#000'
  for (const item of state.active) {
    const x = width - (time - item.comment.time) / lifetime * (width + item.width)
    const y = font * 1.3 + item.lane * row
    ctx.strokeText(item.text, x, y)
    ctx.fillStyle = '#' + item.comment.color.toString(16).padStart(6, '0')
    ctx.fillText(item.text, x, y)
  }
  let next = state.cursor
  while (next < track.comments.length && track.comments[next].mode !== 1) next++
  return state.active.length ? 0 : track.comments[next]?.time ?? Infinity
}
