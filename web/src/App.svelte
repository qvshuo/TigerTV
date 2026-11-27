<script lang="ts">
  import sourceEvidence from '../../shared/hls-heuristics.json' with { type: 'json' }
  import { onMount, tick, untrack } from 'svelte'
  import Poster from './Poster.svelte'
  import { cachedHot, catalogSites, categories, displaySourceName, doubanPoster, getDetail, hot, search, webUrl, type Detail, type Episode, type HotItem, type Site, type Source, type Work } from './api.ts'
  import { defaultDanmakuServer, getBookmark, getPosition, getRecent, getSettings, read, write, type Bookmark, type PlaybackOptions, type Recent, type Selection } from './storage.ts'
  import snapshot from './douban-snapshot.json'
  import type PlayerComponent from './Player.svelte'

  type Page = 'home' | 'search' | 'detail' | 'play' | 'settings'
  const searchHistoryLimit = 12
  let page = $state<Page>('home')
  let settings = $state(getSettings())
  let server = $state(getSettings().danmakuServer)
  let saved = $state(false)
  let serverError = $state('')
  let category = $state<string>(categories[0][0])
  let hotItems = $state.raw<HotItem[]>([])
  let hotVisit = $state.raw<AbortController>()
  const hotReady = new Map<string, HotItem[]>()
  const hotRequests = new Map<string, Promise<HotItem[]>>()
  let hotVisible = $state(20)
  let hotSentinel = $state<HTMLElement>()
  let hotError = $state(false)
  let hotAttempt = $state(0)
  let recent = $state.raw<Recent[]>(getRecent())
  let query = $state('')
  let submitted = $state('')
  let historyItems = $state(read<string[]>('search-history', []).slice(0, searchHistoryLimit))
  let workPosters = $state.raw(read<Record<string, string>>('work-posters', {}))
  let searchBusy = $state(false)
  let configuredSites = $state.raw<Site[]>([])
  let settledSearchSites = $state.raw(new Set<string>())
  let resultLimit = $state(16)
  let resultSentinel = $state<HTMLElement>()
  let searchStatus = $state('')
  let results = $state.raw<Work[]>([])
  let selected = $state.raw<Work>()
  let detailFromResults = $state(false)
  let source = $state.raw<Source>()
  let detail = $state.raw<Detail>()
  let detailBusy = $state(false)
  let detailError = $state('')
  let selectedLine = $state(0)
  const detailCache = new Map<string, { expires: number; value: Detail }>()
  const detailPending = new Map<string, Promise<Detail>>()
  let detailStates = $state.raw(new Map<string, 'ready' | 'empty' | 'failed'>())
  let detailVisit = $state.raw<AbortController>()
  let detailSelection = 0
  let selection = $state.raw<Selection>()
  let video = $state.raw<Episode>()
  let inherited = $state.raw<PlaybackOptions>()
  let Player = $state<typeof PlayerComponent>()
  let playerLoadFailed = $state(false)
  let searchRequest: AbortController | undefined
  let detailHeading = $state<HTMLElement>()
  let resultsHeading = $state<HTMLElement>()
  let searchInput = $state<HTMLInputElement>()
  let searchPanel = $state<HTMLElement>()
  let danmakuDraft = $state<HTMLInputElement>()
  const line = $derived(detail?.lines[selectedLine])
  const visibleWork = $derived(results.find(work => work.id === selected?.id) ?? selected)
  const detailSites = $derived.by(() => {
    const sites = new Map(configuredSites.map(site => [site.name, site]))
    for (const item of visibleWork?.sources ?? []) if (!sites.has(item.site.name)) sites.set(item.site.name, item.site)
    return [...sites.values()]
  })
  const matchedSources = $derived.by(() => {
    const bySite = new Map<string, Source>()
    for (const item of visibleWork?.sources ?? []) {
      if (!bySite.has(item.site.name) || sameSource(item, source)) bySite.set(item.site.name, item)
    }
    return bySite
  })
  const bookmark = $derived.by(() => {
    const saved = recent.find(item => item.work.id === visibleWork?.id && source && sameSource(source, item.source))
    return saved ?? (visibleWork && source && getBookmark(visibleWork.id, source))
  })
  const resumeIndex = $derived(line && bookmark?.line === line.id && bookmark ? episodeIndex(line.episodes, bookmark) : -1)
  const resumeSelection = $derived(selected && source && line && resumeIndex >= 0
    ? identifyEpisode(selected, source, line, resumeIndex) : undefined)
  const resumesPlayback = $derived(resumeSelection ? getPosition(resumeSelection) > 0 : false)
  const matchingHistory = $derived(historyItems.filter(item => item.includes(query.trim())))
  const asset = import.meta.env.BASE_URL + 'icon-192.png'

  const playbackSettings = [
    ['autoNext', '自动连播'], ['rememberSpeed', '记忆倍速'],
    ['rememberVolume', '记忆音量'], ['filterAds', '去除广告'],
  ] as const

  function show(next: Page, push = true) {
    if (page === next) return
    const returningWork = !push && next === 'search' && page === 'detail' ? selected?.id : undefined
    if (page === 'play' && next !== 'play') recent = getRecent()
    if (next !== 'detail' && next !== 'play') detailVisit?.abort()
    if (next === 'home') searchRequest?.abort()
    page = next
    if (push) window.history.pushState({ tigerPage: next }, '', next === 'home' ? location.pathname : '#' + next)
    if (!returningWork) window.scrollTo({ top: 0 })
    void tick().then(() => {
      if (next === 'detail') {
        detailHeading?.focus({ preventScroll: true })
      } else if (returningWork && page === 'search') {
        const result = document.getElementById('result-' + returningWork)
        result?.focus({ preventScroll: true })
        result?.scrollIntoView({ block: 'nearest' })
      }
    })
  }
  function back(fallback: Page) {
    if (window.history.state?.tigerPage && page !== 'home') window.history.back()
    else show(fallback)
  }

  onMount(() => {
    void refreshCatalog()
    const initial = location.hash === '#settings' ? 'settings' : location.hash === '#search' ? 'search' : 'home'
    window.history.replaceState({ tigerPage: 'home' }, '', location.pathname + location.search)
    if (initial === 'search') void tick().then(() => searchPanel?.showPopover())
    else if (initial !== 'home') show(initial)
    return () => { searchRequest?.abort(); detailVisit?.abort() }
  })
  async function refreshCatalog() {
    try { configuredSites = await catalogSites() } catch { }
  }
  function restorePage(event: PopStateEvent) {
    const next = event.state?.tigerPage as Page | undefined
    if (next === 'play' && selection && video) show(next, false)
    else if (next === 'detail' && selected) {
      show(next, false)
      ensureDetailVisit()
      if (source && !detail) void loadDetail(source)
    } else if (next === 'search' || next === 'settings') show(next, false)
    else show('home', false)
  }
  $effect(() => {
    if (page !== 'home') return
    const visit = new AbortController()
    hotVisit = visit
    hotRequests.clear()
    hotReady.clear()
    const first = untrack(() => category)
    void loadRecommendations(first, visit).then(async items => {
      if (!items.length) return
      for (const [id] of categories) {
        if (visit.signal.aborted) break
        if (id !== first) await loadRecommendations(id, visit).catch(() => {})
      }
    }).catch(() => {})
    return () => visit.abort()
  })
  function loadRecommendations(id: string, visit: AbortController): Promise<HotItem[]> {
    const pending = hotRequests.get(id)
    if (pending) return pending
    const request = hot(id, visit.signal, items => {
      if (!visit.signal.aborted && category === id) hotItems = items
    }).then(items => {
      if (!visit.signal.aborted && items.length) hotReady.set(id, items)
      return items
    }).finally(() => {
      if (hotRequests.get(id) === request) hotRequests.delete(id)
    })
    hotRequests.set(id, request)
    return request
  }
  $effect(() => {
    if (page !== 'home' || !hotVisit) return
    const id = category
    const attempt = hotAttempt
    const visit = hotVisit
    let active = true
    const ready = hotReady.get(id)
    hotVisible = 20
    hotItems = ready ?? cachedHot(id) ?? snapshot.categories[id as keyof typeof snapshot.categories] ?? []
    hotError = false
    if (ready) return
    void loadRecommendations(id, visit)
      .then(items => { if (active && !visit.signal.aborted && items.length) hotItems = items })
      .catch(error => {
        if (active && !visit.signal.aborted) {
          hotError = true
          console.info('豆瓣推荐读取失败', id, attempt, error)
        }
      })
    return () => { active = false }
  })

  $effect(() => {
    if (!hotSentinel || hotVisible >= hotItems.length) return
    const observer = new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting) hotVisible += 20
    }, { rootMargin: '200px' })
    observer.observe(hotSentinel)
    return () => observer.disconnect()
  })
  $effect(() => {
    if (!resultSentinel || resultLimit >= results.length) return
    const observer = new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting) resultLimit += 16
    }, { rootMargin: '150px' })
    observer.observe(resultSentinel)
    return () => observer.disconnect()
  })

  function clearHistory() {
    historyItems = []
    write('search-history', [])
  }

  function cardMetadata(work: Work) {
    return recent.find(item => item.work.id === work.id)?.work.display ?? work.display
  }
  function posterCandidates(work: Work) {
    return [workPosters[work.id], cardMetadata(work).poster, work.display.poster, ...work.sources.map(item => item.poster)]
  }
  function rememberPoster(work: Work, url: string) {
    if (workPosters[work.id] === url) return
    workPosters = Object.fromEntries([[work.id, url],
      ...Object.entries(workPosters).filter(([id]) => id !== work.id).slice(0, 99)])
    write('work-posters', workPosters)
  }

  function evidenceFor(site: Site) {
    const name = displaySourceName(site.name)
    if (settings.filterAds && sourceEvidence.verifiedFilterSources.includes(name)) return 'filter'
    if (sourceEvidence.noGamblingInSamples.includes(name)) return 'sample'
    return 'other'
  }

  async function runSearch(value = query) {
    const keyword = value.trim()
    if (!keyword) { searchInput?.focus(); return }
    searchPanel?.hidePopover()
    query = keyword
    submitted = keyword
    results = []
    resultLimit = 16
    settledSearchSites = new Set()
    searchStatus = ''
    searchRequest?.abort()
    const request = new AbortController()
    searchRequest = request
    searchBusy = true
    historyItems = [keyword, ...historyItems.filter(item => item !== keyword)].slice(0, searchHistoryLimit)
    write('search-history', historyItems)
    show('search')
    await tick()
    if (request.signal.aborted) return
    if (page === 'search') {
      window.scrollTo({ top: 0 })
      resultsHeading?.focus({ preventScroll: true })
    }
    try {
      void refreshCatalog()
      const report = await search(keyword, request.signal, (works, settled) => {
        results = works
        settledSearchSites = settled
      })
      if (!results.length && report.failed === report.total) searchStatus = '资源暂不可用'
      else if (!results.length) searchStatus = '未找到相关作品'
    } catch (error) {
      if (!request.signal.aborted) {
        console.info('搜索失败', error)
        searchStatus = '搜索失败'
      }
    } finally { if (searchRequest === request) searchBusy = false }
  }

  function ensureDetailVisit() {
    if (!detailVisit || detailVisit.signal.aborted) {
      detailVisit = new AbortController()
      detailPending.clear()
      detailStates = new Map()
    }
    return detailVisit
  }

  function cachedDetail(key: string) {
    const entry = detailCache.get(key)
    if (entry && entry.expires > Date.now()) return entry.value
    if (entry) detailCache.delete(key)
  }

  function requestDetail(item: Source, visit: AbortController) {
    const key = sourceKey(item)
    const cached = cachedDetail(key)
    if (cached) return Promise.resolve(cached)
    const pending = detailPending.get(key)
    if (pending) return pending
    const request = getDetail(item, visit.signal).then(value => {
      if (!visit.signal.aborted) {
        if (detailCache.size >= 64) detailCache.delete(detailCache.keys().next().value!)
        detailCache.set(key, { expires: Date.now() + 5 * 60_000, value })
        detailStates = new Map(detailStates).set(key, value.lines.length ? 'ready' : 'empty')
      }
      return value
    }).catch(error => {
      if (!visit.signal.aborted) detailStates = new Map(detailStates).set(key, 'failed')
      throw error
    }).finally(() => {
      if (detailPending.get(key) === request) detailPending.delete(key)
    })
    detailPending.set(key, request)
    return request
  }

  $effect(() => {
    if (page !== 'detail' || !visibleWork || !detailVisit) return
    const visit = detailVisit
    const items = visibleWork.sources
    let active = true
    void (async () => {
      for (let index = 0; active && !visit.signal.aborted && index < items.length; index += 3) {
        await Promise.allSettled(items.slice(index, index + 3).map(item => requestDetail(item, visit)))
      }
    })()
    return () => { active = false }
  })

  async function loadDetail(nextSource: Source, resume?: Recent) {
    const visit = ensureDetailVisit()
    const attempt = ++detailSelection
    source = nextSource
    selectedLine = 0
    const cached = cachedDetail(sourceKey(nextSource))
    detailBusy = !cached
    detailError = ''
    try {
      const loaded = cached ?? await requestDetail(nextSource, visit)
      if (visit.signal.aborted || attempt !== detailSelection) return
      detail = loaded
      const directLine = loaded.lines.findIndex(item => item.episodes.every(episode => /\.(?:m3u8|mp4)$/i.test(episode.url.split(/[?#]/, 1)[0])))
      selectedLine = Math.max(0, directLine)
      const bookmark = resume ?? (selected && getBookmark(selected.id, nextSource))
      if (bookmark) {
        const lineIndex = loaded.lines.findIndex(item => item.id === bookmark.line)
        if (lineIndex >= 0) {
          selectedLine = lineIndex
          const index = episodeIndex(loaded.lines[lineIndex].episodes, bookmark)
          if (resume && index >= 0) play(index)
        }
      }
    } catch (error) {
      if (!visit.signal.aborted && attempt === detailSelection) {
        console.info('详情读取失败', nextSource.site.name, error)
        detail = undefined
        detailError = '资源加载失败'
      }
    } finally { if (attempt === detailSelection) detailBusy = false }
  }
  const sourceKey = (item: Source) => JSON.stringify([item.site.name, item.id])
  function sameSource(a: Source, b?: Source) { return a.site.name === b?.site.name && a.id === b.id }
  function identifyEpisode(work: Work, source: Source, line: Detail['lines'][number], index: number): Selection {
    const episode = line.episodes[index].name
    return { work, source, line: line.id, episode, index }
  }
  function episodeIndex(items: Episode[], bookmark: Bookmark) {
    if (items[bookmark.index]?.name === bookmark.episode) return bookmark.index
    const matches = items.map((item, index) => item.name === bookmark.episode ? index : -1).filter(index => index >= 0)
    return matches.length === 1 ? matches[0] : -1
  }
  function open(work: Work, resume?: Recent) {
    selected = work
    detailFromResults = !resume
    detail = undefined
    const previous = resume ?? getRecent().find(item => item.work.id === work.id)
    const available = work.sources.filter(item => {
      const key = sourceKey(item)
      return detailStates.get(key) !== 'empty' && detailStates.get(key) !== 'failed' && cachedDetail(key)?.lines.length !== 0
    })
    const recommended = (item: Source) => evidenceFor(item.site) !== 'other'
    const last = getRecent()[0]
    const choice = (previous && (resume ? work.sources : available).find(item => sameSource(item, previous.source))) ??
      (last && available.find(item => item.site.name === last.source.site.name && recommended(item))) ??
      available.find(recommended) ?? available[0] ?? work.sources[0]
    show('detail')
    if (choice) void loadDetail(choice, resume)
  }
  function play(index: number, options?: PlaybackOptions) {
    const currentLine = detail?.lines[selectedLine]
    const nextEpisode = currentLine?.episodes[index]
    if (!selected || !source || !currentLine || !nextEpisode) return
    const work = visibleWork ?? selected
    selection = identifyEpisode({ ...work, display: cardMetadata(work) }, detail?.source ?? source, currentLine, index)
    video = nextEpisode
    inherited = options
    show('play')
    if (!Player) {
      playerLoadFailed = false
      void import('./Player.svelte').then(module => { Player = module.default }).catch(() => { playerLoadFailed = true })
    }
  }
  function editSetting(key: typeof playbackSettings[number][0], value: boolean) {
    settings = { ...settings, [key]: value }
    write('settings', settings)
  }
  function saveServer(event: SubmitEvent) {
    event.preventDefault()
    const address = server.trim().replace(/\/+$/, '')
    if (address) {
      const valid = webUrl(address)
      if (!valid || new URL(valid).search || new URL(valid).hash) {
        serverError = '请输入有效的弹幕服务地址'
        danmakuDraft?.focus()
        return
      }
    }
    settings = { ...settings, danmakuServer: address }
    write('settings', settings)
    server = address
    serverError = ''
    saved = true
  }
</script>

<svelte:window onpopstate={restorePage} />
<header class="site-header">
  <div class="shell header-inner">
    <a class="brand" href="./" aria-current={page === 'home' ? 'page' : undefined} onclick={event => { event.preventDefault(); show('home') }}>
      <img src={asset} alt="" width="36" height="36" /><span>小老虎爱看剧</span>
    </a>
    <nav aria-label="主导航" class="flex items-center gap-1">
      <button type="button" class="nav-link" aria-label="搜索" popovertarget="catalog-search">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
          <circle cx="11" cy="11" r="6" /><path d="m16 16 4 4" />
        </svg>
      </button>
      <a class="nav-link" href="#settings" aria-label="设置" aria-current={page === 'settings' ? 'page' : undefined}
        onclick={event => { event.preventDefault(); show('settings') }}>
        <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <path d="M19.4 13a7.8 7.8 0 0 0 0-2l2-1.6-1.9-3.3-2.4 1a8 8 0 0 0-1.7-1L15 3h-4l-.4 3.1a8 8 0 0 0-1.7 1l-2.4-1-1.9 3.3L6.5 11a7.8 7.8 0 0 0 0 2l-1.9 1.6 1.9 3.3 2.4-1a8 8 0 0 0 1.7 1L11 21h4l.4-3.1a8 8 0 0 0 1.7-1l2.4 1 1.9-3.3zM13 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7" transform="translate(-1)" />
        </svg>
      </a>
    </nav>
  </div>
  <div id="catalog-search" class="search-panel" popover="auto" bind:this={searchPanel}
    onbeforetoggle={event => { if (event.newState === 'open') query = '' }}>
    <form class="search-form" role="search" onsubmit={event => { event.preventDefault(); if (!searchBusy || query.trim() !== submitted) void runSearch() }}>
      <!-- svelte-ignore a11y_autofocus (native popover focuses only when opened) -->
      <input type="search" bind:this={searchInput} bind:value={query} placeholder="搜索电影、剧集或综艺" aria-label="作品名称" enterkeyhint="search" autofocus required />
      <button class="primary" disabled={searchBusy && query.trim() === submitted}>搜索</button>
    </form>
    {#if matchingHistory.length}
      <section aria-labelledby="history-heading" class="history-section">
        <div class="section-heading">
          <h2 id="history-heading">搜索历史</h2>
          <button class="history-clear" onclick={() => { clearHistory(); searchInput?.focus() }}>清空</button>
        </div>
        <ul class="search-history" role="list">
          {#each matchingHistory as item (item)}
            <li><button onclick={() => void runSearch(item)}>{item}</button></li>
          {/each}
        </ul>
      </section>
    {/if}
  </div>
</header>

<main class="shell page">
  {#if recent.length}
      <section aria-labelledby="recent-heading" hidden={page !== 'home'}>
        <h2 id="recent-heading" class="catalog-lead">继续观看</h2>
        <ul class="recent-strip">
          {#each recent.slice(0, 4) as item, index (item.work.id)}
            <li>
              <button class="recent-card" onclick={() => open(item.work, item)}>
                <Poster title={item.work.display.title} candidates={posterCandidates(item.work)} recent eager={page === 'home'} priority={index === 0 && page === 'home'}
                  onShown={url => rememberPoster(item.work, url)} />
                <span class="min-w-0">
                  <strong>{item.work.display.title}</strong>
                  <small>{item.episode || `第 ${item.index + 1} 集`}</small>
                  <progress class="recent-progress" aria-label="观看进度" max="100" value={item.progress ?? 0}></progress>
                </span>
              </button>
            </li>
          {/each}
        </ul>
      </section>
  {/if}
  {#if page === 'home'}
    <section aria-labelledby="hot-heading" class={recent.length ? 'mt-6' : undefined}>
      <div class="section-heading">
        <h2 id="hot-heading">热门推荐</h2>
      </div>
      <div class="choices mb-3" aria-label="热门分类">
        {#each categories as [id, name] (id)}
          <button class="choice" aria-pressed={category === id} onclick={() => { category = id }}>{name}</button>
        {/each}
      </div>
      <ul class="poster-grid">
        {#each hotItems.slice(0, hotVisible) as item, index (item.id)}
          <li>
            <button class="poster-card" onclick={() => void runSearch(item.title)}>
              <Poster title={item.title} candidates={[doubanPoster(item.poster), item.poster]} eager={index < 5} priority={index === 0 && !recent.length} />
              <strong>{item.title}</strong>
              <small>{item.year}{item.rating ? ` · ${item.rating.toFixed(1)}` : ''}</small>
            </button>
          </li>
        {/each}
      </ul>
      {#if hotVisible < hotItems.length}<div bind:this={hotSentinel} aria-hidden="true"></div>{/if}
      {#if hotError}
        <button class="quiet mt-4" onclick={() => { hotAttempt += 1 }}>重试</button>
      {/if}
    </section>
  {:else if page === 'search'}
    {#if submitted}
      <h1 class="catalog-lead search-results-title" bind:this={resultsHeading} tabindex="-1">
        <span>“{submitted}”的搜索结果</span>
        {#if searchBusy}<span class="spinner" role="status" aria-label="正在搜索"></span>{/if}
      </h1>
      {#if searchStatus && !searchBusy}<p class="muted mb-4" role="status">{searchStatus}</p>{/if}
      <ul class="poster-grid">
        {#each results.slice(0, resultLimit) as work, index (work.id)}
          {@const display = cardMetadata(work)}
          <li>
            <button class="poster-card" id={'result-' + work.id} onclick={() => open(work)}>
              <Poster title={display.title} candidates={posterCandidates(work)} eager={index < 4} priority={index === 0}
                onShown={url => rememberPoster(work, url)} />
              <strong>{display.title || '未命名作品'}</strong>
              <small>{display.year === '0' ? '' : display.year} {display.remarks}</small>
            </button>
          </li>
        {/each}
      </ul>
      {#if resultLimit < results.length}<div bind:this={resultSentinel} aria-hidden="true"></div>{/if}
    {/if}
  {:else if page === 'detail' && visibleWork && source}
    {@const detailPoster = !detailBusy && detail ? detail.source.poster : ''}
    {@const year = detail?.source.year && detail.source.year !== '0' ? detail.source.year : source.year}
    {@const metadata = [year === '0' ? '' : year, detail?.source.category || source.category, detail?.source.area].filter(Boolean).join(' · ')}
    <div class="catalog-lead"><button class="quiet" onclick={() => back('search')}>← 返回</button></div>
    <div class="work-overview">
      {#key sourceKey(source)}
        <Poster title={source.title} candidates={[detailPoster, source.poster]} eager priority />
      {/key}
      <div class="min-w-0 self-center">
        <h1 bind:this={detailHeading} tabindex="-1">{detail?.source.title || source.title}</h1>
        {#if metadata}<p class="muted mt-2">{metadata}</p>{/if}
        {#if detail?.overview}<p class="synopsis">{detail.overview}</p>{/if}
        <button class="primary hero-play" disabled={detailBusy || !line} onclick={() => play(Math.max(0, resumeIndex))}>
          {resumesPlayback ? '继续播放' : '播放'}{line ? ' · ' + (line.episodes[Math.max(0, resumeIndex)]?.name.trim() || '第一集') : ''}
        </button>
      </div>
    </div>
    <section class="detail-section" aria-labelledby="source-heading">
      <h2 id="source-heading">资源</h2>
      {#if detailSites.some(site => evidenceFor(site) !== 'other')}
        <h3 class="source-group-title">推荐资源</h3>
      {/if}
      {#each ['recommended', 'other'] as groupType (groupType)}
        {@const group = detailSites.filter(site => (evidenceFor(site) === 'other') === (groupType === 'other'))}
        {#if group.length}
          {#if groupType === 'other'}<h3 class="source-group-title">其他资源</h3>{/if}
          <div class="source-grid">
            {#each group as site (site.name)}
              {@const evidence = evidenceFor(site)}
              {@const item = matchedSources.get(site.name)}
              {@const active = !!item && sameSource(item, source)}
              {@const key = item ? sourceKey(item) : ''}
              {@const cached = item && cachedDetail(key)}
              {@const busy = item ? (active && detailBusy) || (!cached && !detailStates.has(key))
                : detailFromResults && searchBusy && !settledSearchSites.has(site.name)}
              <button class="choice source-choice" aria-pressed={active}
                aria-busy={busy}
                disabled={!item || (cached ? !cached.lines.length : detailStates.get(key) === 'empty')}
                onclick={() => { if (item && !active) void loadDetail(item) }}>
                <span>{displaySourceName(site.name)}</span>
                {#if evidence !== 'other'}<span class="source-badge">{evidence === 'sample' ? '无广告' : '去广告'}</span>{/if}
                {#if busy}<span class="spinner" aria-hidden="true"></span>{/if}
              </button>
            {/each}
          </div>
        {/if}
      {/each}
    </section>
    <section class="detail-section" aria-labelledby="episode-heading">
      <h2 id="episode-heading">选集</h2>
      {#if !detailBusy && detailError}
        <p class="muted mb-3" role="alert">{detailError}</p>
        <button class="quiet" onclick={() => source && void loadDetail(source)}>重试</button>
      {:else if !detailBusy && !line}
        <p class="muted">暂无剧集</p>
      {:else if !detailBusy && line}
        <div class="choices">
          {#each line.episodes as item, index (index)}
            <button class="choice" onclick={() => play(index)}>{item.name || `第 ${index + 1} 集`}</button>
          {/each}
        </div>
      {/if}
    </section>
  {:else if page === 'play' && selection && video}
    {#if Player}
      <Player {selection} episode={video} {settings} {inherited}
        playbackEpisodes={line?.episodes ?? []}
        onBack={() => back('detail')} onSelectEpisode={play} />
    {:else if playerLoadFailed}
      <p role="alert">播放器加载失败</p>
      <button class="quiet mt-4" onclick={() => back('detail')}>← 返回</button>
    {:else}<div class="video-surface" aria-busy="true"></div>{/if}
  {:else if page === 'settings'}
    <div class="settings-page">
      <section class="detail-section" aria-labelledby="playback-heading">
        <h2 id="playback-heading">播放</h2>
        {#each playbackSettings as [key, label] (key)}
          <label class="setting-row">
            <span>{label}</span>
            <input type="checkbox" role="switch" checked={settings[key]} onchange={event => editSetting(key, event.currentTarget.checked)} />
          </label>
        {/each}
      </section>
      <section class="detail-section" aria-labelledby="danmaku-settings-heading">
        <h2 id="danmaku-settings-heading">弹幕</h2>
        <form class="settings-form grid gap-3" onsubmit={saveServer} novalidate>
          <label for="server">弹幕服务地址</label>
          <div class="search-form">
            <input id="server" type="url" inputmode="url" bind:this={danmakuDraft} bind:value={server} placeholder={defaultDanmakuServer}
              aria-invalid={!!serverError} aria-describedby="server-status" oninput={() => { saved = false; serverError = '' }} />
            <button class="primary">保存</button>
          </div>
          <p class="muted" role="status" id="server-status">{serverError || (saved ? '已保存' : '')}</p>
        </form>
      </section>
    </div>
  {/if}
</main>
