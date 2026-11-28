<script lang="ts">
  import { untrack } from 'svelte'
  import danmakuRules from '../../shared/danmaku-selection.json' with { type: 'json' }
  import '@videojs/html/video/player'
  import type { VideoPlayerElement } from '@videojs/html/video'
  import './player-skin.ts'
  import type { TigerVideoSkin } from './player-skin.ts'
  import '@videojs/html/i18n'
  import '@videojs/html/i18n/locales/zh-CN/register'
  import type { HlsJsVideoElement } from '@videojs/html/media/hlsjs-video'
  import { displaySourceName, webUrl, type Episode } from './api.ts'
  import { effectiveTime, originalTime, type Interval } from './hls.ts'
  import { getPosition, getSpeed, getVolume, recordStarted, savePosition, saveSpeed, saveVolume, type PlaybackOptions, type Selection, type Settings } from './storage.ts'
  import { drawComments } from './danmaku/canvas.ts'
  import { danmakuSession } from './danmaku/session.svelte.ts'
  import ChoicePicker from './ChoicePicker.svelte'

  type Media = HTMLVideoElement | HlsJsVideoElement
  let { selection, episode, settings, onBack, onDanmakuSettings, playbackEpisodes, onSelectEpisode, inherited }:
    { selection: Selection; episode: Episode; settings: Settings; onBack: () => void; playbackEpisodes: Episode[];
      onDanmakuSettings: () => void; onSelectEpisode: (index: number, options?: PlaybackOptions) => void;
      inherited?: PlaybackOptions } = $props()
  let container = $state<TigerVideoSkin>()
  let player = $state<VideoPlayerElement>()
  let danmakuLayer = $state<HTMLDivElement>()
  let windowedPlayerSize: { width: number; height: number } | undefined
  let danmakuFontSize = 0
  let canvas = $state<HTMLCanvasElement>()
  let dialog = $state<HTMLDialogElement>()
  let episodeDialog = $state<HTMLDialogElement>()
  let media: Media | undefined
  let duration = $state(0)
  let volume = $state(1)
  let muted = $state(false)
  let removed = $state.raw<Interval[]>([])
  const danmaku = danmakuSession(
    () => selection, () => settings.danmakuServer, () => inherited?.danmakuEnabled,
    () => Boolean(dialog?.open), () => dialog?.close(),
  )
  let flush = () => {}
  let wakeDanmaku: (reset?: boolean) => void = () => {}
  let buffering = true
  let fullscreenExitedAt = -Infinity

  function fullscreenChanged() {
    if (!document.fullscreenElement) fullscreenExitedAt = performance.now()
  }

  function toggle() {
    if (!media) return
    if (media.paused) {
      void media.play().catch(() => {})
    } else media.pause()
  }
  function seek(time: number) {
    if (media && duration > 0) media.currentTime = Math.max(0, Math.min(Math.max(0, duration - 0.1), time))
  }
  function changeVolume(value: number, mute = value === 0) {
    if (!media) return
    media.volume = Math.max(0, Math.min(1, value))
    media.muted = mute
    volume = media.volume
    muted = media.muted
    saveVolume(volume, muted, settings.rememberVolume)
  }
  function exit(action: () => void) {
    flush()
    media?.pause()
    action()
  }
  function showPicker(trigger: HTMLButtonElement, picker?: HTMLDialogElement) {
    trigger.focus({ preventScroll: true })
    picker?.showModal()
  }
  function shortcut(event: KeyboardEvent) {
    if (event.defaultPrevented || event.isComposing || event.altKey || event.ctrlKey || event.metaKey || dialog?.open || episodeDialog?.open || document.querySelector(':popover-open')) return
    const target = event.composedPath()[0]
    if (target instanceof Element && target.closest('input, textarea, select, [contenteditable]:not([contenteditable="false"])')) return
    if (event.key === ' ' && target instanceof Element && target.closest('button, a, [role=button]')) return
    if (event.key !== 'Escape' && container && event.composedPath().includes(container)) return
    const store = player?.store
    switch (event.key.toLowerCase()) {
      case ' ':
        if (!event.repeat) toggle()
        break
      case 'arrowleft': seek((media?.currentTime ?? 0) - 10); break
      case 'arrowright': seek((media?.currentTime ?? 0) + 10); break
      case 'arrowup': changeVolume(volume + 0.05, false); break
      case 'arrowdown': changeVolume(volume - 0.05, volume <= 0.05); break
      case 'f':
        if (!event.repeat && store) void (store.isFullscreen ? store.exitFullscreen() : store.requestFullscreen()).catch(() => {})
        break
      case 'escape':
        if (document.fullscreenElement || store?.isFullscreen) {
          void (document.fullscreenElement ? document.exitFullscreen() : store?.exitFullscreen())?.catch(() => {})
        } else if (!event.repeat && performance.now() - fullscreenExitedAt > 100) exit(onBack)
        break
      default: return
    }
    event.preventDefault()
  }
  $effect(() => {
    const session = selection
    const link = episode.url
    const mount = container
    const priorOptions = inherited
    const overlay = danmakuLayer
    if (!mount || !overlay) return
    const prefs = untrack(() => ({ ...settings }))
    const url = webUrl(link)
    duration = 0
    removed = []
    let speed = getSpeed(session.work.id, prefs.rememberSpeed)
    const volumeSettings = priorOptions ?? getVolume(prefs.rememberVolume)
    volume = volumeSettings.volume
    muted = volumeSettings.muted
    const hls = url !== '' && /\.m3u8$/i.test(new URL(url).pathname)
    let nativeFallback = false
    let active = true
    let attemptedPlay = false
    let started = false
    let ended = false
    let livePosition = getPosition(session)
    let lastSaved = livePosition
    let cleanupMedia = () => {}

    let hlsLoader: typeof import('./hls-loader.ts').playlistLoader | undefined
    const install = () => {
      const el = hls && !nativeFallback ? document.createElement('hlsjs-video') as HlsJsVideoElement : document.createElement('video')
      media = el
      buffering = true
      wakeDanmaku(true)
      const events = new AbortController()
      let playedHere = false
      let failed = false
      function save() {
        if (!playedHere || ended || el.seeking || !Number.isFinite(el.currentTime)) return
        livePosition = originalTime(el.currentTime, removed)
        if (lastSaved !== livePosition) {
          savePosition(session, livePosition, Number.isFinite(el.duration) && el.duration > 0
            ? Math.round(el.currentTime / el.duration * 100) : undefined)
          lastSaved = livePosition
        }
      }
      flush = save
      const on = (name: string, handler: EventListener) => el.addEventListener(name, handler, { signal: events.signal })

      const placeDanmaku = () => {
        const width = el.clientWidth
        const height = el.clientHeight
        const aspect = el.videoWidth > 0 && el.videoHeight > 0 ? el.videoWidth / el.videoHeight : width / height
        if (!width || !height || !Number.isFinite(aspect) || aspect <= 0) return
        const contentWidth = Math.min(width, height * aspect)
        const contentHeight = Math.min(height, width / aspect)
        overlay.style.width = `${contentWidth}px`
        overlay.style.blockSize = `${contentHeight * danmakuRules.render.areaRatio}px`
        overlay.style.left = `${(width - contentWidth) / 2}px`
        overlay.style.top = `${(height - contentHeight) / 2}px`
        const fullscreen = document.fullscreenElement
        if (!windowedPlayerSize || !fullscreen || !mount.contains(fullscreen)) {
          windowedPlayerSize = { width: mount.clientWidth, height: mount.clientHeight }
        }
        const fontSize = Math.min(windowedPlayerSize.height, windowedPlayerSize.width / aspect) * danmakuRules.render.fontRatio
        if (fontSize !== danmakuFontSize) {
          danmakuFontSize = fontSize
          overlay.style.fontSize = `${fontSize * danmakuRules.render.statusFontRatio}px`
          wakeDanmaku(true)
        }
      }
      const layout = new ResizeObserver(placeDanmaku)
      layout.observe(el)
      layout.observe(mount)
      on('resize', placeDanmaku)
      for (const event of ['waiting', 'seeking']) on(event, () => { buffering = true; wakeDanmaku(event === 'seeking') })
      on('playing', () => { buffering = false; wakeDanmaku() })
      on('seeked', () => { buffering = el.readyState < 3; wakeDanmaku(true) })
      for (const event of ['pause', 'ratechange', 'ended', 'loadeddata']) on(event, () => wakeDanmaku())

      on('loadedmetadata', () => {
        placeDanmaku()
        const resume = effectiveTime(livePosition, removed)
        if (resume > 0 && (!Number.isFinite(el.duration) || resume < el.duration - 0.25)) el.currentTime = resume
        duration = Number.isFinite(el.duration) ? el.duration : 0
        el.playbackRate = speed
        if (!attemptedPlay) void el.play().catch(() => {})
        attemptedPlay = true
      })
      on('durationchange', () => { duration = Number.isFinite(el.duration) ? el.duration : 0 })
      on('play', () => { ended = false })
      on('playing', () => {
        playedHere = true
        if (!started) { started = true; recordStarted(session) }
      })
      on('pause', save)
      on('seeked', save)
      on('ratechange', () => {
        if (el.playbackRate === speed) return
        speed = el.playbackRate
        el.defaultPlaybackRate = speed
        saveSpeed(session.work.id, speed, prefs.rememberSpeed)
      })
      on('volumechange', () => {
        volume = el.volume
        muted = el.muted
        saveVolume(volume, muted, prefs.rememberVolume)
      })
      on('ended', () => {
        if (!active || !playedHere || ended || failed) return
        ended = true
        savePosition(session, 0, 100)
        lastSaved = 0
        if (settings.autoNext && playbackEpisodes[session.index + 1]) onSelectEpisode(session.index + 1, {
          volume: el.volume, muted: el.muted, danmakuEnabled: danmaku.enabled,
        })
      })
      on('error', () => {
        if (!active) return
        save()
        if (!(el instanceof HTMLVideoElement) && el.engineName === 'hlsjs' &&
          document.createElement('video').canPlayType('application/vnd.apple.mpegurl')) {
          cleanupMedia()
          nativeFallback = true
          attemptedPlay = false
          removed = []
          install()
          return
        }
        failed = true
      })
      const visibility = () => { if (document.hidden) save() }
      document.addEventListener('visibilitychange', visibility, { signal: events.signal })
      window.addEventListener('pagehide', save, { signal: events.signal })
      const timer = window.setInterval(save, 5000)
      cleanupMedia = () => {
        save()
        events.abort()
        layout.disconnect()
        buffering = true
        wakeDanmaku(true)
        window.clearInterval(timer)
        el.pause()
        if (el instanceof HTMLVideoElement) {
          el.removeAttribute('src')
          el.load()
        }
        el.remove()
      }

      el.setAttribute('playsinline', '')
      el.setAttribute('preload', 'auto')
      mount.insertBefore(el, overlay)
      placeDanmaku()
      el.volume = volume
      el.muted = muted
      el.defaultPlaybackRate = speed
      el.playbackRate = speed
      if (el instanceof HTMLVideoElement) el.src = url
      else {
        const filtering = hlsLoader?.(intervals => {
          if (active && media === el) {
            const changed = removed.length !== intervals.length ||
              removed.some(({ start, end }, index) => start !== intervals[index].start || end !== intervals[index].end)
            removed = intervals
            if (changed) wakeDanmaku(true)
          }
        })
        if (filtering) el.addEventListener('loadedmetadata', () => {
          if (el.engine) events.signal.addEventListener('abort', filtering.bind(el.engine), { once: true })
        }, { once: true, signal: events.signal })
        el.source = {
          src: url, type: 'application/vnd.apple.mpegurl', preferPlayback: 'mse',
          engine: filtering ? { hlsJs: { pLoader: filtering.pLoader } } : undefined,
        }
      }
    }
    if (hls) {
      void Promise.all([import('@videojs/html/media/hlsjs-video'), prefs.filterAds ? import('./hls-loader.ts') : null])
        .then(([, adapter]) => {
          if (!active) return
          hlsLoader = adapter?.playlistLoader
          install()
        })
        .catch(() => {
          if (!active) return
          nativeFallback = true
          install()
        })
    } else untrack(install)
    return () => {
      active = false
      cleanupMedia()
      media = undefined
      flush = () => {}
    }
  })

  $effect(() => {
    const element = canvas
    if (!element) return
    const selected = danmaku.enabled ? danmaku.track : undefined
    let frame = 0
    let timer = 0
    let reset = false
    function cancelPaint() {
      cancelAnimationFrame(frame)
      window.clearTimeout(timer)
      frame = 0
    }
    function paint() {
      frame = 0
      const el = media
      const time = el?.currentTime ?? 0
      const next = drawComments(element!, selected, time, danmakuFontSize, reset)
      reset = false
      if (!selected || !el || el.paused || el.seeking || buffering || el.ended) return
      if (next === 0) frame = requestAnimationFrame(paint)
      else if (Number.isFinite(next)) timer = window.setTimeout(paint, Math.max(1, (next - time) * 1000 / el.playbackRate))
    }
    wakeDanmaku = (seek = false) => {
      reset ||= seek
      cancelPaint()
      frame = requestAnimationFrame(paint)
    }
    const resize = new ResizeObserver(() => wakeDanmaku(true))
    resize.observe(element)
    wakeDanmaku()
    return () => {
      resize.disconnect()
      cancelPaint()
      wakeDanmaku = () => {}
    }
  })

  function showDanmakuPicker(trigger: HTMLButtonElement) {
    danmaku.restoreDanmakuChoice()
    showPicker(trigger, dialog)
  }
</script>

<svelte:window onkeydown={shortcut} />
<svelte:document onfullscreenchange={fullscreenChanged} />
<div class="mb-5 flex flex-wrap items-center gap-3">
  <button class="quiet" onclick={() => exit(onBack)}>← 返回</button>
</div>
<media-i18n lang="zh-CN"><video-player bind:this={player}>
  <tiger-video-skin bind:this={container} class="video-surface">
    <div class="danmaku-layer" bind:this={danmakuLayer}>
      <canvas bind:this={canvas} class="danmaku" aria-hidden="true"></canvas>
      <p class="danmaku-status" role="status">{danmaku.enabled ? danmaku.status : ''}</p>
    </div>
  </tiger-video-skin>
</video-player></media-i18n>

<div class="player-meta">
  <div>
    <h1>{selection.work.display.title}</h1>
    <p class="muted">{selection.episode || `第 ${selection.index + 1} 集`} · {displaySourceName(selection.source.site.name)}</p>
  </div>
  <div class="player-actions">
    <button class="quiet" onclick={event => showPicker(event.currentTarget, episodeDialog)} aria-haspopup="dialog">选集</button>
    <label class="danmaku-toggle">
      <input type="checkbox" role="switch" checked={danmaku.enabled} onchange={event => danmaku.setDanmakuEnabled(event.currentTarget.checked)} />
      弹幕
    </label>
    <button class="quiet" onclick={event => showDanmakuPicker(event.currentTarget)} aria-haspopup="dialog">选择弹幕</button>
  </div>
</div>

<dialog class="episode-dialog" bind:this={episodeDialog} aria-labelledby="playback-episodes-heading">
  <form method="dialog" class="flex items-center justify-between gap-3">
    <h2 id="playback-episodes-heading">选集</h2>
    <button class="quiet">关闭</button>
  </form>
  <ol class="episode-picker" role="list">
    {#each playbackEpisodes as item, index (index)}
      <li>
        <!-- svelte-ignore a11y_autofocus (focus the current episode when the native dialog opens) -->
        <button class="choice" autofocus={index === selection.index} aria-current={index === selection.index ? 'true' : undefined}
          onclick={() => {
            episodeDialog?.close()
            if (index !== selection.index) exit(() => onSelectEpisode(index))
          }}>{item.name.trim() || `第 ${index + 1} 集`}</button>
      </li>
    {/each}
  </ol>
</dialog>

<dialog bind:this={dialog} aria-labelledby="danmaku-heading"
  onclose={() => dialog?.querySelectorAll<HTMLElement>('.picker-field ul[popover]').forEach(panel => panel.hidePopover())}>
  <form method="dialog" class="mb-5 flex items-center justify-between gap-3">
    <h2 id="danmaku-heading">选择弹幕</h2>
    <button class="quiet">关闭</button>
  </form>
  {#if settings.danmakuServer}
    <form class="search-form" onsubmit={event => {
      event.preventDefault()
      danmaku.search()
    }}>
      <input type="search" aria-label="弹幕作品名" enterkeyhint="search" bind:value={danmaku.keyword} required />
      <button class="primary">搜索</button>
    </form>
    {#if danmaku.titles.length}
      <ChoicePicker label="来源" value={danmaku.manualTitle}
        items={danmaku.titles.map(item => ({ id: item.id, label: [item.title, item.source].filter(Boolean).join(' · ') }))}
        onSelect={danmaku.chooseTitle} />
    {/if}
    {#if danmaku.episodes.length}
      <ChoicePicker label="剧集" value={danmaku.manualEpisode}
        items={danmaku.episodes.map(item => ({ id: item.id, label: item.title || item.number || '未命名剧集' }))}
        onSelect={danmaku.chooseEpisode} />
    {/if}
    <p class="muted picker-status" role="status">{danmaku.manualStatus}</p>
  {:else}
    <p class="muted">未设置弹幕服务地址</p>
    <button type="button" class="primary mt-3" onclick={() => {
      dialog?.close()
      exit(onDanmakuSettings)
    }}>前往设置</button>
  {/if}
</dialog>
