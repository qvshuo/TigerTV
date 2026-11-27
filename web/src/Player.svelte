<script lang="ts">
  import { untrack } from 'svelte'
  import danmakuRules from '../../shared/danmaku-selection.json' with { type: 'json' }
  import '@videojs/html/video/player'
  import type { VideoPlayerElement } from '@videojs/html/video'
  import '@videojs/html/video/skin'
  import '@videojs/html/ui/playback-rate-button'
  import '@videojs/html/i18n'
  import '@videojs/html/i18n/locales/zh-CN/register'
  import type { HlsJsVideoElement } from '@videojs/html/media/hlsjs-video'
  import { displaySourceName, webUrl, type Episode } from './api.ts'
  import { effectiveTime, originalTime, type Interval } from './hls.ts'
  import { episodeIdentity, getPosition, getSpeed, getVolume, read, recordStarted, savePosition, saveSpeed, saveVolume, write, type PlaybackOptions, type Selection, type Settings } from './storage.ts'
  import { autoMatch, DanmakuClient, danmakuLoading, drawComments, type DanmakuEpisode, type DanmakuMatch, type SavedDanmakuEpisode, type Title, type Track } from './danmaku.ts'
  import ChoicePicker from './ChoicePicker.svelte'

  type Media = HTMLVideoElement | HlsJsVideoElement
  let { selection, episode, settings, onBack, playbackEpisodes, onSelectEpisode, inherited }:
    { selection: Selection; episode: Episode; settings: Settings; onBack: () => void; playbackEpisodes: Episode[];
      onSelectEpisode: (index: number, options?: PlaybackOptions) => void; inherited?: PlaybackOptions } = $props()
  let container = $state<HTMLElementTagNameMap["video-skin"]>()
  let player = $state<VideoPlayerElement>()
  let danmakuLayer = $state<HTMLDivElement>()
  let canvas = $state<HTMLCanvasElement>()
  let dialog = $state<HTMLDialogElement>()
  let episodeDialog = $state<HTMLDialogElement>()
  let media: Media | undefined
  let duration = $state(0)
  let volume = $state(1)
  let muted = $state(false)
  let speed = $state(1)
  let failed = $state.raw<Selection>()
  const error = $derived(failed === selection)
  let playbackAttempt = $state(0)
  let removed = $state.raw<Interval[]>([])
  let track = $state.raw<Track>()
  let enabled = $state(true)
  let keyword = $state('')
  let titles = $state.raw<Title[]>([])
  let episodes = $state.raw<DanmakuEpisode[]>([])
  let manualTitle = $state(0)
  let manualEpisode = $state(0)
  let activeDanmaku = $state.raw<DanmakuMatch>()
  let danmakuStatus = $state('')
  let statusTimer: ReturnType<typeof setTimeout> | undefined
  let manualStatus = $state('')
  let request: AbortController | undefined
  let automatic: AbortController | undefined
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
    void playbackAttempt
    if (!mount || !overlay) return
    const prefs = untrack(() => ({ ...settings }))
    const url = webUrl(link)
    duration = 0
    removed = []
    speed = priorOptions?.speed ?? getSpeed(session.work.id, prefs.rememberSpeed)
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
        overlay.style.fontSize = `${contentHeight * danmakuRules.render.fontRatio * danmakuRules.render.statusFontRatio}px`
        overlay.style.left = `${(width - contentWidth) / 2}px`
        overlay.style.top = `${(height - contentHeight) / 2}px`
      }
      const layout = new ResizeObserver(placeDanmaku)
      layout.observe(el)
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
        savePosition(session, 0, 100)
        lastSaved = 0
        if (settings.autoNext && playbackEpisodes[session.index + 1]) onSelectEpisode(session.index + 1, {
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
          install()
          return
        }
        failed = session
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
      el.playbackRate = speed
      if (el instanceof HTMLVideoElement) el.src = url
      else {
        el.source = {
          src: url, type: 'application/vnd.apple.mpegurl', preferPlayback: 'mse',
          engine: hlsLoader ? { hlsJs: { pLoader: hlsLoader(intervals => {
            if (active && media === el) {
              removed = intervals
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
    const mount = container
    if (!mount) return
    void customElements.whenDefined('video-skin').then(() => {
      const root = mount.shadowRoot
      // The default skin exposes no slot in its control bar.
      const settingsButton = root?.querySelector<HTMLButtonElement>('.video-controls-settings-button')
      if (!settingsButton) return
      const menu = settingsButton.getAttribute('commandfor')
      if (menu) root?.getElementById(menu)?.remove()
      root?.querySelector(`media-tooltip[trigger="${settingsButton.id}"]`)?.remove()
      const rate = document.createElement('media-playback-rate-button')
      rate.className = 'media-button'
      rate.setAttribute('label', '倍速')
      rate.setAttribute('rates', '0.5 0.75 1 1.25 1.5 1.75 2')
      const label = document.createElement('span')
      label.textContent = speed + '×'
      rate.append(label)
      settingsButton.replaceWith(rate)
    })
  })
  $effect(() => {
    const label = container?.shadowRoot?.querySelector('media-playback-rate-button span')
    const rate = speed
    if (label) label.textContent = rate + '×'
  })

  function setDanmakuStatus(message: string, transient = false) {
    clearTimeout(statusTimer)
    danmakuStatus = message
    if (transient) statusTimer = setTimeout(() => { danmakuStatus = '' }, danmakuRules.render.statusDurationMs)
  }

  const danmakuTitleKey = (server: string, session: Selection) => 'danmaku-title:' + JSON.stringify([server, session.work.id, session.source.site.name, session.source.id])
  const danmakuEpisodeKey = (title: string, session: Selection) => 'danmaku-episode:' + JSON.stringify([title, ...episodeIdentity(session)])
  const titleKey = $derived(danmakuTitleKey(settings.danmakuServer, selection))
  const episodeKey = $derived(danmakuEpisodeKey(titleKey, selection))
  $effect(() => {
    const server = settings.danmakuServer
    const session = selection
    const title = titleKey
    const episode = episodeKey
    const legacy = legacyDanmakuKeys(server, session)
    const showDanmaku = inherited?.danmakuEnabled ?? read('danmaku-enabled:' + episode,
      legacy && (!session.episode || session.uniqueName) ? read('danmaku-enabled:' + legacy.episode, true) : true)
    enabled = showDanmaku
    untrack(() => dialog?.close())
    keyword = session.work.display.title
    titles = []
    episodes = []
    manualTitle = 0
    manualEpisode = 0
    track = undefined
    activeDanmaku = undefined
    setDanmakuStatus('')
    manualStatus = ''
    if (server && showDanmaku) untrack(() => startDanmaku(server, session, title, episode))
    return () => { clearTimeout(statusTimer); request?.abort(); request = automatic = undefined }
  })

  function legacyDanmakuKeys(server: string, session: Selection) {
    if (session.work.sources.filter(item => item.site.name === session.source.site.name).length !== 1) return
    const title = 'danmaku-title:' + JSON.stringify([server, session.work.id, session.source.site.name])
    const episode = 'danmaku-episode:' + JSON.stringify([title, session.line, session.episode, session.episode ? null : session.index])
    return { title, episode }
  }
  function owns(controller: AbortController, session: Selection, server: string) {
    return request === controller && !controller.signal.aborted && session === selection && server === settings.danmakuServer
  }

  function startDanmaku(server: string, session: Selection, title: string, episode: string) {
    const controller = new AbortController()
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(danmakuRules.autoTimeoutMs)])
    request = automatic = controller
    const legacy = legacyDanmakuKeys(server, session)
    void autoMatch(new DanmakuClient(server, signal, phase => {
      if (!signal.aborted && owns(controller, session, server) && !track) setDanmakuStatus(danmakuLoading[phase])
    }), session.work.display.title, session.work.display.year, session.episode,
      { title: read<number | undefined>(title, undefined) ?? (legacy && read<number | undefined>(legacy.title, undefined)),
        episode: read<SavedDanmakuEpisode | undefined>(episode, undefined) ??
          (legacy && (!session.episode || session.uniqueName) ? read<SavedDanmakuEpisode | undefined>(legacy.episode, undefined) : undefined) },
      (result, match) => {
        if (signal.aborted || !owns(controller, session, server)) return
        track = result
        activeDanmaku = match
        if (dialog?.open) restoreDanmakuChoice()
        setDanmakuStatus('')
      }, signal,
      candidates => {
        if (!owns(controller, session, server) || signal.aborted) return
        titles = [...new Map([...titles, ...candidates].map(candidate => [candidate.id, candidate])).values()]
      })
      .then(() => { if (owns(controller, session, server) && !track) setDanmakuStatus('未找到本集弹幕', true) })
      .catch(() => { if (owns(controller, session, server) && !track) setDanmakuStatus('无法加载弹幕', true) })
      .finally(() => {
        if (request === controller) request = undefined
        if (automatic === controller) automatic = undefined
      })
  }
  function setDanmakuEnabled(value: boolean) {
    enabled = value
    setDanmakuStatus('')
    write('danmaku-enabled:' + episodeKey, enabled)
    if (!enabled && automatic) {
      automatic.abort()
      if (request === automatic) request = undefined
      automatic = undefined
    } else if (enabled && !track && !request && settings.danmakuServer) {
      startDanmaku(settings.danmakuServer, selection, titleKey, episodeKey)
    }
  }
  $effect(() => {
    const element = canvas
    if (!element) return
    const selected = enabled ? track : undefined
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
      const next = drawComments(element!, selected, time, reset)
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

  async function manual<T>(work: (client: DanmakuClient) => Promise<T>, apply: (value: T) => void) {
    request?.abort()
    setDanmakuStatus('')
    const session = selection
    const server = settings.danmakuServer
    const controller = new AbortController()
    request = controller
    try {
      const result = await work(new DanmakuClient(server, controller.signal, phase => {
        if (owns(controller, session, server)) manualStatus = danmakuLoading[phase]
      }))
      if (owns(controller, session, server)) { manualStatus = ''; apply(result) }
    } catch {
      if (owns(controller, session, server)) manualStatus = '无法加载弹幕'
    } finally {
      if (request === controller) request = undefined
    }
  }
  function chooseTitle(id: number) {
    manualTitle = id
    manualEpisode = 0
    episodes = []
    if (id) void manual(client => client.episodes(id), value => {
      episodes = value
      if (!value.length) manualStatus = '暂无剧集'
    })
  }
  function chooseEpisode(id: number) {
    const title = titles.find(item => item.id === manualTitle)
    const episode = episodes.find(item => item.id === id)
    if (!title || !episode) return
    const choice = { title, episode, episodes }
    const savedTitleKey = titleKey
    const savedEpisodeKey = episodeKey
    manualEpisode = id
    void manual(client => client.track(id, danmakuRules.manualTrackTimeoutMs), value => {
      if (!value.comments.some(comment => comment.mode === 1)) { manualStatus = '未找到本集弹幕'; return }
      activeDanmaku = choice
      track = value
      write(savedTitleKey, title.id)
      write(savedEpisodeKey, { title: title.id, id, titleItem: title, episodeItem: episode })
      dialog?.close()
    })
  }
  function restoreDanmakuChoice() {
    const match = activeDanmaku
    if (match && !titles.some(item => item.id === match.title.id)) titles = [match.title, ...titles]
    manualTitle = match?.title.id ?? 0
    manualEpisode = match?.episode.id ?? 0
    episodes = match?.episodes ?? []
  }
  function showDanmakuPicker(trigger: HTMLButtonElement) {
    restoreDanmakuChoice()
    showPicker(trigger, dialog)
  }
</script>

<svelte:window onkeydown={shortcut} />
<svelte:document onfullscreenchange={fullscreenChanged} />
<div class="mb-5 flex flex-wrap items-center gap-3">
  <button class="quiet" onclick={() => exit(onBack)}>← 返回</button>
  {#if error}
    <button class="quiet" onclick={() => { failed = undefined; playbackAttempt++ }}>重试</button>
  {/if}
</div>
<media-i18n lang="zh-CN"><video-player bind:this={player}>
  <video-skin bind:this={container} class="video-surface">
    <div class="danmaku-layer" bind:this={danmakuLayer}>
      <canvas bind:this={canvas} class="danmaku" aria-hidden="true"></canvas>
      <p class="danmaku-status" role="status">{enabled ? danmakuStatus : ''}</p>
    </div>
  </video-skin>
</video-player></media-i18n>

<div class="player-meta">
  <div>
    <h1>{selection.work.display.title}</h1>
    <p class="muted">{selection.episode || `第 ${selection.index + 1} 集`} · {displaySourceName(selection.source.site.name)}</p>
  </div>
  <div class="player-actions">
    <button class="quiet" onclick={event => showPicker(event.currentTarget, episodeDialog)} aria-haspopup="dialog">选集</button>
    <label class="danmaku-toggle">
      <input type="checkbox" role="switch" checked={enabled} onchange={event => setDanmakuEnabled(event.currentTarget.checked)} />
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
      manualTitle = 0; manualEpisode = 0; episodes = []
      if (keyword.trim()) void manual(client => client.search(keyword.trim()), value => {
        titles = value
        if (!value.length) manualStatus = '未找到本集弹幕'
      })
    }}>
      <input type="search" aria-label="弹幕作品名" enterkeyhint="search" bind:value={keyword} required />
      <button class="primary">搜索</button>
    </form>
    {#if titles.length}
      <ChoicePicker label="来源" value={manualTitle}
        items={titles.map(item => ({ id: item.id, label: [item.title, item.source].filter(Boolean).join(' · ') }))}
        onSelect={chooseTitle} />
    {/if}
    {#if episodes.length}
      <ChoicePicker label="剧集" value={manualEpisode}
        items={episodes.map(item => ({ id: item.id, label: item.title || item.number || '未命名剧集' }))}
        onSelect={chooseEpisode} />
    {/if}
    <p class="muted picker-status" role="status">{manualStatus}</p>
  {:else}
    <p class="muted">未设置弹幕服务地址</p>
  {/if}
</dialog>
