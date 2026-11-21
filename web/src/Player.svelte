<script lang="ts">
  import { untrack } from 'svelte'
  import '@videojs/html/video/player'
  import type { VideoPlayerElement } from '@videojs/html/video'
  import '@videojs/html/video/skin'
  import '@videojs/html/i18n'
  import '@videojs/html/i18n/locales/zh-CN/register'
  import type { HlsJsVideoElement } from '@videojs/html/media/hlsjs-video'
  import { legacyWorkId, webUrl, type Episode, type Site } from './api.ts'
  import { effectiveTime, originalTime, type Interval } from './hls.ts'
  import { getPosition, getSpeed, getVolume, read, recordStarted, savePosition, saveSpeed, saveVolume, write, type PlaybackOptions, type Selection, type Settings } from './storage.ts'
  import { autoMatch, DanmakuClient, danmakuLoading, drawComments, type DanmakuEpisode, type Title, type Track } from './danmaku.ts'

  type Media = HTMLVideoElement | HlsJsVideoElement
  let { selection, episode, settings, onBack, onNext, onPrevious, onSources, canNext, canPrevious, inherited, onAdFilter }:
    { selection: Selection; episode: Episode; settings: Settings; onBack: () => void; onNext: (options?: PlaybackOptions) => void;
      onPrevious: () => void; onAdFilter: (site: Site) => void; onSources: () => void; canNext: boolean; canPrevious: boolean; inherited?: PlaybackOptions } = $props()
  let container = $state<HTMLElementTagNameMap["video-skin"]>()
  let player = $state<VideoPlayerElement>()
  let canvas = $state<HTMLCanvasElement>()
  let dialog = $state<HTMLDialogElement>()
  let media: Media | undefined
  let retry = $state(0)
  let loading = $state(true)
  let duration = $state(0)
  let volume = $state(1)
  let muted = $state(false)
  let speed = $state(1)
  let error = $state('')
  let removed = $state.raw<Interval[]>([])
  let track = $state.raw<Track>()
  let enabled = $state(true)
  let keyword = $state('')
  let titles = $state.raw<Title[]>([])
  let episodes = $state.raw<DanmakuEpisode[]>([])
  let manualTitle = $state(0)
  let manualEpisode = $state(0)
  let danmakuStatus = $state('')
  let request: AbortController | undefined
  let flush = () => {}
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
  function shortcut(event: KeyboardEvent) {
    if (event.defaultPrevented || event.isComposing || event.altKey || event.ctrlKey || event.metaKey || dialog?.open || document.querySelector(':popover-open')) return
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
    retry
    const overlay = canvas
    if (!mount || !overlay) return
    const prefs = untrack(() => ({ ...settings }))
    const url = webUrl(link)
    loading = !!url
    error = url ? '' : '此线路的播放地址不可用，请切换来源。'
    duration = 0
    removed = []
    speed = priorOptions?.speed ?? getSpeed(session.work.id, prefs.rememberSpeed, session.work.sources)
    const volumeSettings = priorOptions ?? getVolume(prefs.rememberVolume)
    volume = volumeSettings.volume
    muted = volumeSettings.muted
    if (!url) return

    const hls = /\.m3u8$/i.test(new URL(url).pathname)
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
      const events = new AbortController()
      let playedHere = false
      function save() {
        if (!playedHere || ended || el.seeking || !Number.isFinite(el.currentTime)) return
        livePosition = originalTime(el.currentTime, removed)
        if (lastSaved !== livePosition) {
          savePosition(session, livePosition)
          lastSaved = livePosition
        }
      }
      flush = save
      const on = (name: string, handler: EventListener) => el.addEventListener(name, handler, { signal: events.signal })

      const placeDanmaku = () => {
        const width = mount.clientWidth
        const height = mount.clientHeight
        const aspect = el.videoWidth > 0 && el.videoHeight > 0 ? el.videoWidth / el.videoHeight : width / height
        if (!width || !height || !Number.isFinite(aspect) || aspect <= 0) return
        const contentWidth = Math.min(width, height * aspect)
        const contentHeight = Math.min(height, width / aspect)
        overlay.style.width = `${contentWidth}px`
        overlay.style.height = `${contentHeight / 4}px`
        overlay.style.left = `${(width - contentWidth) / 2}px`
        overlay.style.top = `${(height - contentHeight) / 2}px`
      }
      const layout = new ResizeObserver(placeDanmaku)
      layout.observe(mount)
      on('resize', placeDanmaku)

      on('loadedmetadata', () => {
        placeDanmaku()
        const resume = effectiveTime(livePosition, removed)
        if (resume > 0 && (!Number.isFinite(el.duration) || resume < el.duration - 0.25)) el.currentTime = resume
        duration = Number.isFinite(el.duration) ? el.duration : 0
        el.playbackRate = speed
        if (!attemptedPlay) void el.play().catch(() => { if (media === el) loading = false })
        attemptedPlay = true
      })
      on('durationchange', () => { duration = Number.isFinite(el.duration) ? el.duration : 0 })
      on('canplay', () => { loading = false })
      on('play', () => { ended = false })
      on('playing', () => {
        playedHere = true
        if (!started) { started = true; recordStarted(session) }
        loading = false
      })
      on('pause', () => { loading = false; save() })
      on('seeked', save)
      on('ratechange', () => {
        speed = el.playbackRate
        saveSpeed(session.work.id, speed, prefs.rememberSpeed)
      })
      on('volumechange', () => {
        volume = el.volume
        muted = el.muted
        saveVolume(volume, muted, prefs.rememberVolume)
      })
      on('ended', () => {
        if (!active || !playedHere || ended || error) return
        ended = true
        savePosition(session, 0)
        lastSaved = 0
        loading = false
        if (settings.autoNext && canNext) onNext({
          speed: el.playbackRate, volume: el.volume, muted: el.muted, danmakuEnabled: enabled,
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
          loading = true
          install()
          return
        }
        loading = false
        error = '此线路播放失败。可重试或切换来源。'
      })
      const visibility = () => { if (document.hidden) save() }
      document.addEventListener('visibilitychange', visibility, { signal: events.signal })
      window.addEventListener('pagehide', save, { signal: events.signal })
      const timer = window.setInterval(save, 5000)
      cleanupMedia = () => {
        save()
        events.abort()
        layout.disconnect()
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
      el.volume = volume
      el.muted = muted
      el.playbackRate = speed
      if (el instanceof HTMLVideoElement) el.src = url
      else {
        el.source = {
          src: url, type: 'application/vnd.apple.mpegurl', preferPlayback: 'mse',
          engine: hlsLoader ? { hlsJs: { pLoader: hlsLoader(intervals => {
            if (active && media === el) {
              removed = intervals
              if (intervals.length) onAdFilter(session.source.site)
            }
          }) } } : undefined,
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
          loading = false
          error = '播放器加载失败，请重试。'
        })
    } else untrack(install)
    return () => {
      active = false
      cleanupMedia()
      media = undefined
      flush = () => {}
    }
  })

  const danmakuTitleKey = (workId: string) => 'danmaku-title:' + JSON.stringify([settings.danmakuServer, workId, selection.source.site.name])
  const danmakuEpisodeKey = (title: string) => 'danmaku-episode:' + JSON.stringify([title, selection.line, selection.episode, selection.episode ? null : selection.index])
  const titleKey = $derived(danmakuTitleKey(selection.work.id))
  const episodeKey = $derived(danmakuEpisodeKey(titleKey))
  const legacyTitleKey = $derived(danmakuTitleKey(legacyWorkId(selection.source)))
  const legacyEpisodeKey = $derived(danmakuEpisodeKey(legacyTitleKey))
  $effect(() => {
    const key = episodeKey
    const name = selection.work.display.title
    enabled = inherited?.danmakuEnabled ?? read('danmaku-enabled:' + key, read('danmaku-enabled:' + legacyEpisodeKey, true))
    keyword = name
    titles = []
    episodes = []
    manualTitle = 0
    manualEpisode = 0
    track = undefined
    danmakuStatus = ''
  })
  $effect(() => {
    const server = settings.danmakuServer
    const s = selection
    const title = titleKey
    const episode = episodeKey
    if (!server || !enabled) return
    const controller = new AbortController()
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(90_000)])
    request = controller
    void autoMatch(new DanmakuClient(server, signal, phase => {
      if (!signal.aborted && !track) danmakuStatus = danmakuLoading[phase]
    }), s.work.display.title, s.work.display.year, s.episode,
      { title: read<number | undefined>(title, undefined) ?? read<number | undefined>(legacyTitleKey, undefined),
        episode: read<{ title: number; id: number } | undefined>(episode, undefined)
          ?? read<{ title: number; id: number } | undefined>(legacyEpisodeKey, undefined) },
      result => { if (!signal.aborted) { track = result; danmakuStatus = '' } }, signal)
      .then(() => { if (!controller.signal.aborted && !track) danmakuStatus = '可手动选择对应剧集' })
      .catch(() => { if (!controller.signal.aborted) danmakuStatus = '弹幕加载失败' })
    return () => { controller.abort(); request?.abort() }
  })
  $effect(() => {
    if (!canvas) return
    const element = canvas
    let frame = 0
    let lastTime = -1
    let lastTrack: Track | undefined
    let lastWidth = 0
    let lastHeight = 0
    let layoutPending = false
    function paint() {
      const selected = enabled ? track : undefined
      const time = media && !media.seeking ? media.currentTime : -1
      if (layoutPending || time !== lastTime || selected !== lastTrack || element.clientWidth !== lastWidth || element.clientHeight !== lastHeight) {
        layoutPending = drawComments(element, selected, time)
        lastTime = time
        lastTrack = selected
        lastWidth = element.clientWidth
        lastHeight = element.clientHeight
      }
      frame = requestAnimationFrame(paint)
    }
    frame = requestAnimationFrame(paint)
    return () => cancelAnimationFrame(frame)
  })

  async function manual<T>(work: (client: DanmakuClient) => Promise<T>, apply: (value: T) => void) {
    request?.abort()
    const controller = new AbortController()
    request = controller
    try {
      const result = await work(new DanmakuClient(settings.danmakuServer, controller.signal, phase => {
        if (!controller.signal.aborted) danmakuStatus = danmakuLoading[phase]
      }))
      if (!controller.signal.aborted) { danmakuStatus = ''; apply(result) }
    } catch {
      if (!controller.signal.aborted) danmakuStatus = '弹幕加载失败'
    }
  }
  function chooseTitle(id: number) {
    manualTitle = id
    manualEpisode = 0
    episodes = []
    if (id) void manual(client => client.episodes(id), value => { episodes = value })
  }
  function chooseEpisode(id: number) {
    manualEpisode = id
    if (!id) return
    void manual(client => client.track(id), value => {
      if (!value.comments.length) { danmakuStatus = '这一集没有弹幕'; return }
      track = value
      write(titleKey, manualTitle)
      write(episodeKey, { title: manualTitle, id })
      dialog?.close()
    })
  }
</script>

<svelte:window onkeydown={shortcut} />
<svelte:document onfullscreenchange={fullscreenChanged} />
<div class="mb-5 flex flex-wrap items-center justify-between gap-3">
  <button class="quiet" onclick={() => exit(onBack)}>← 返回详情</button>
  <button class="quiet" onclick={() => exit(onSources)}>切换来源</button>
</div>
<media-i18n lang="zh-CN"><video-player bind:this={player}>
  <video-skin bind:this={container} class="video-surface">
    <canvas bind:this={canvas} class="danmaku" aria-hidden="true"></canvas>
    {#if removed.length && !error}<span class="filter-label">去广告</span>{/if}
    <p class="danmaku-status" role="status">{enabled ? danmakuStatus : ''}</p>
    {#if error}
      <div class="video-message" role="alert">
        <p>{error}</p>
        <button class="primary" onclick={() => { retry++ }}>重试</button>
      </div>
    {:else if loading}
      <div class="video-message pointer-events-none" role="status">正在准备播放…</div>
    {/if}
    <dialog bind:this={dialog} aria-labelledby="danmaku-heading">
      <form method="dialog" class="mb-5 flex items-center justify-between gap-3">
        <h2 id="danmaku-heading">选择弹幕</h2>
        <button class="quiet">关闭</button>
      </form>
      {#if settings.danmakuServer}
        <form class="search-form" onsubmit={event => {
          event.preventDefault()
          manualTitle = 0; manualEpisode = 0; episodes = []
          if (keyword.trim()) void manual(client => client.search(keyword.trim()), value => { titles = value })
        }}>
          <input aria-label="弹幕作品名" bind:value={keyword} required />
          <button class="primary">匹配</button>
        </form>
        {#if titles.length}
          <label class="mt-4 grid gap-2">弹幕来源
            <select value={manualTitle} onchange={event => chooseTitle(Number(event.currentTarget.value))}>
              <option value="0">选择来源</option>
              {#each titles as item (item.id)}<option value={item.id}>{item.title} · {item.source}</option>{/each}
            </select>
          </label>
        {/if}
        {#if episodes.length}
          <label class="mt-4 grid gap-2">剧集
            <select value={manualEpisode} onchange={event => chooseEpisode(Number(event.currentTarget.value))}>
              <option value="0">选择剧集</option>
              {#each episodes as item (item.id)}<option value={item.id}>{item.title || item.number || '未命名剧集'}</option>{/each}
            </select>
          </label>
        {/if}
        <p class="muted mt-3" role="status">{danmakuStatus}</p>
      {:else}
        <p class="muted">请在设置中填写弹幕 API 地址。</p>
      {/if}
    </dialog>
  </video-skin>
</video-player></media-i18n>

<div class="player-meta">
  <div>
    <h1>{selection.work.display.title}</h1>
    <p class="muted">{selection.episode || `第 ${selection.index + 1} 集`} · {selection.source.site.name}</p>
  </div>
  <div class="flex flex-wrap gap-2">
    <button class="quiet" disabled={!canPrevious} onclick={() => exit(onPrevious)}>上一集</button>
    <button class="quiet" disabled={!canNext} onclick={() => exit(() => onNext())}>下一集</button>
    <button class="quiet" aria-pressed={enabled} onclick={() => {
      enabled = !enabled
      write('danmaku-enabled:' + episodeKey, enabled)
      if (!enabled) track = undefined
    }}>弹幕</button>
    <button class="quiet" onclick={() => dialog?.showModal()}>选择弹幕</button>
  </div>
</div>
