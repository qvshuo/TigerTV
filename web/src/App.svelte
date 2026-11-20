<script lang="ts">
  import { onMount, tick } from 'svelte'
  import Poster from './Poster.svelte'
  import { categories, getDetail, hot, search, webUrl, type Detail, type Episode, type HotItem, type Source, type Work } from './api.ts'
  import { defaultDanmakuServer, getBookmark, getPosition, getRecent, getSettings, read, write, type Bookmark, type PlaybackOptions, type Recent, type Selection } from './storage.ts'
  import snapshot from './douban-snapshot.json'
  import type PlayerComponent from './Player.svelte'

  type Page = 'home' | 'search' | 'detail' | 'play' | 'settings'
  let page = $state<Page>('home')
  let settings = $state(getSettings())
  let server = $state(getSettings().danmakuServer)
  let saved = $state(false)
  let serverError = $state('')
  let category = $state<string>(categories[0][0])
  let hotItems = $state.raw<HotItem[]>([])
  let hotFallback = $state(false)
  let recent = $state.raw<Recent[]>(getRecent())
  let query = $state('')
  let submitted = $state('')
  let historyItems = $state(read<string[]>('search-history', []))
  let searchBusy = $state(false)
  let searchStatus = $state('')
  let results = $state.raw<Work[]>([])
  let selected = $state.raw<Work>()
  let source = $state.raw<Source>()
  let detail = $state.raw<Detail>()
  let detailBusy = $state(false)
  let detailError = $state('')
  let selectedLine = $state(0)
  let selection = $state.raw<Selection>()
  let video = $state.raw<Episode>()
  let inherited = $state.raw<PlaybackOptions>()
  let Player = $state<typeof PlayerComponent>()
  let playerLoadFailed = $state(false)
  let searchRequest: AbortController | undefined
  let detailRequest: AbortController | undefined
  let focusSources = false
  let detailHeading = $state<HTMLElement>()
  let searchInput = $state<HTMLInputElement>()
  let sourceHeading = $state<HTMLElement>()
  let danmakuDraft = $state<HTMLInputElement>()
  const line = $derived(detail?.lines[selectedLine])
  const visibleWork = $derived(results.find(work => work.id === selected?.id) ?? selected)
  const asset = import.meta.env.BASE_URL + 'icon-192.png'

  const playbackSettings = [
    ['autoNext', '自动下一集'], ['rememberSpeed', '记忆播放速度'],
    ['rememberVolume', '记忆音量'], ['filterAds', '去广告'],
  ] as const

  function show(next: Page, push = true) {
    if (page === next) return
    if (page === 'play' && next !== 'play') recent = getRecent()
    if (page === 'detail' && next !== 'play') detailRequest?.abort()
    if (next === 'home') searchRequest?.abort()
    page = next
    if (push) window.history.pushState({ tigerPage: next }, '', next === 'home' ? location.pathname : '#' + next)
    window.scrollTo({ top: 0 })
    void tick().then(() => {
      if (next === 'search' && !submitted) searchInput?.focus()
      if (next === 'detail') {
        (focusSources ? sourceHeading : detailHeading)?.focus()
        focusSources = false
      }
    })
  }
  function back(fallback: Page) {
    if (window.history.state?.tigerPage && page !== 'home') window.history.back()
    else show(fallback)
  }

  onMount(() => {
    const initial = location.hash === '#settings' ? 'settings' : location.hash === '#search' ? 'search' : 'home'
    window.history.replaceState({ tigerPage: 'home' }, '', location.pathname + location.search)
    if (initial !== 'home') show(initial)
    return () => { searchRequest?.abort(); detailRequest?.abort() }
  })
  function restorePage(event: PopStateEvent) {
    const next = event.state?.tigerPage as Page | undefined
    if (next === 'play' && selection && video) show(next, false)
    else if (next === 'detail' && selected) {
      show(next, false)
      if (source && !detail) void loadDetail(source)
    } else if (next === 'search' || next === 'settings') show(next, false)
    else show('home', false)
  }
  $effect(() => {
    if (page !== 'home') return
    const id = category
    const controller = new AbortController()
    const local = snapshot.categories[id as keyof typeof snapshot.categories] ?? []
    hotItems = local
    hotFallback = local.length > 0
    void hot(id, controller.signal)
      .then(items => { if (!controller.signal.aborted && items.length) { hotItems = items; hotFallback = false } })
      .catch(error => { if (!controller.signal.aborted) console.info('豆瓣推荐读取失败', id, error) })
    return () => controller.abort()
  })

  async function runSearch(value = query) {
    const keyword = value.trim()
    if (!keyword) { searchInput?.focus(); return }
    query = keyword
    submitted = keyword
    results = []
    searchStatus = ''
    searchRequest?.abort()
    const request = new AbortController()
    searchRequest = request
    searchBusy = true
    historyItems = [keyword, ...historyItems.filter(item => item !== keyword)].slice(0, 20)
    write('search-history', historyItems)
    show('search')
    try {
      const report = await search(keyword, request.signal, works => { results = works })
      if (report.failed === report.total) searchStatus = '资源站暂不可用，可能受跨域或网络限制。'
      else if (!results.length) searchStatus = '没有找到相关作品。'
      else if (report.failed) searchStatus = `${report.failed} 个来源暂不可用`
    } catch (error) {
      if (!request.signal.aborted) {
        console.info('搜索失败', error)
        searchStatus = '搜索失败，请重试。'
      }
    } finally { if (searchRequest === request) searchBusy = false }
  }

  async function loadDetail(nextSource: Source, resume?: Recent) {
    detailRequest?.abort()
    const request = new AbortController()
    detailRequest = request
    source = nextSource
    selectedLine = 0
    detailBusy = true
    detailError = ''
    try {
      const loaded = await getDetail(nextSource, request.signal)
      if (request.signal.aborted) return
      detail = loaded
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
      if (!request.signal.aborted) {
        console.info('详情读取失败', nextSource.site.name, error)
        detail = undefined
        detailError = '无法读取此来源。请重试或选择其他来源。'
      }
    } finally { if (detailRequest === request) detailBusy = false }
  }
  function sameSource(a: Source, b?: Source) { return a.site.name === b?.site.name && a.id === b.id }
  function episodeIndex(items: Episode[], bookmark: Bookmark) {
    if (!bookmark.episode && items[bookmark.index]?.name === '') return bookmark.index
    return items.findIndex(item => item.name === bookmark.episode)
  }
  function open(work: Work, resume?: Recent) {
    selected = work
    detail = undefined
    const previous = resume ?? getRecent().find(item => item.work.id === work.id)
    const last = getRecent()[0]
    const choice = (previous && work.sources.find(item => sameSource(item, previous.source))) ??
      (last && work.sources.find(item => item.site.name === last.source.site.name)) ??
      work.sources.find(item => item.poster) ?? work.sources[0]
    show('detail')
    if (choice) void loadDetail(choice, resume)
  }
  function play(index: number, options?: PlaybackOptions) {
    const currentLine = detail?.lines[selectedLine]
    const nextEpisode = currentLine?.episodes[index]
    if (!selected || !source || !currentLine || !nextEpisode) return
    selection = { work: visibleWork ?? selected, source: detail?.source ?? source, line: currentLine.id, episode: nextEpisode.name, index }
    video = nextEpisode
    inherited = options
    show('play')
    if (!Player) {
      playerLoadFailed = false
      void import('./Player.svelte').then(module => { Player = module.default }).catch(() => { playerLoadFailed = true })
    }
  }
  function resumeDetail() {
    if (!line || !visibleWork) return
    const bookmark = source && getBookmark(visibleWork.id, source)
    play(bookmark?.line === line.id ? Math.max(0, episodeIndex(line.episodes, bookmark)) : 0)
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
        serverError = '请输入有效的 HTTPS 地址。'
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
    <a class="brand" href="./" onclick={event => { event.preventDefault(); show('home') }}>
      <img src={asset} alt="" width="36" height="36" /><span>TigerTV</span>
    </a>
    <nav aria-label="主导航" class="flex items-center gap-1 sm:gap-3">
      <a class="nav-link" href="./" aria-current={page === 'home' ? 'page' : undefined}
        onclick={event => { event.preventDefault(); show('home') }}>首页</a>
      <a class="nav-link" href="#search" aria-current={page === 'search' ? 'page' : undefined}
        onclick={event => { event.preventDefault(); show('search') }}>搜索</a>
      <a class="nav-link" href="#settings" aria-current={page === 'settings' ? 'page' : undefined}
        onclick={event => { event.preventDefault(); show('settings') }}>设置</a>
    </nav>
  </div>
</header>

<main class="shell page">
  {#if page === 'home'}
    <div class="intro">
      <h1>小老虎爱看剧</h1>
      <form class="search-form" role="search" onsubmit={event => { event.preventDefault(); void runSearch() }}>
        <input type="search" bind:this={searchInput} bind:value={query} placeholder="搜索电影、剧集或综艺" aria-label="搜索电影、剧集或综艺" required />
        <button class="primary">搜索</button>
      </form>
    </div>
    {#if recent.length}
      <section aria-labelledby="recent-heading">
        <h2 id="recent-heading">最近播放</h2>
        <ul class="recent-strip">
          {#each recent.slice(0, 12) as item (item.work.id)}
            <li>
              <button class="recent-card" onclick={() => open(item.work, item)}>
                <Poster title={item.work.display.title} candidates={[item.source.poster, item.work.display.poster]} />
                <span class="min-w-0">
                  <strong>{item.work.display.title}</strong>
                  <small>{item.episode || '继续播放'} {#if getPosition(item) > 0} · {Math.ceil(getPosition(item) / 60)} 分钟{/if}</small>
                </span>
              </button>
            </li>
          {/each}
        </ul>
      </section>
    {/if}
    <section aria-labelledby="hot-heading" class="mt-9 sm:mt-12">
      <div class="section-heading">
        <h2 id="hot-heading">豆瓣热门</h2>
        {#if hotFallback}<span class="muted snapshot-date">{snapshot.date} 快照</span>{/if}
      </div>
      <div class="choices mb-5" aria-label="热门分类">
        {#each categories as [id, name] (id)}
          <button class="choice" aria-pressed={category === id} onclick={() => { category = id }}>{name}</button>
        {/each}
      </div>
      <ul class="poster-grid">
        {#each hotItems as item, index (item.id)}
          <li>
            <button class="poster-card" onclick={() => void runSearch(item.title)}>
              <Poster title={item.title} candidates={[item.poster]} eager={index < 4} />
              <strong>{item.title}</strong>
              <small>{item.year}{item.rating ? ` · ${item.rating.toFixed(1)}` : ''}</small>
            </button>
          </li>
        {/each}
      </ul>
    </section>
  {:else if page === 'search'}
    <h1>搜索</h1>
    <form class="search-form my-6" role="search" onsubmit={event => { event.preventDefault(); if (!searchBusy || query.trim() !== submitted) void runSearch() }}>
      <input type="search" bind:this={searchInput} bind:value={query} placeholder="搜索电影、剧集或综艺" aria-label="作品名称" required />
      <button class="primary" disabled={searchBusy && query.trim() === submitted}>搜索</button>
    </form>
    {#if !submitted && historyItems.length}
      <div class="section-heading">
        <h2>搜索历史</h2>
        <button class="quiet" onclick={() => { historyItems = []; write('search-history', []) }}>清空</button>
      </div>
      <div class="choices">
        {#each historyItems as item (item)}<button class="choice" onclick={() => void runSearch(item)}>{item}</button>{/each}
      </div>
    {/if}
    {#if submitted}
      <p class="muted mb-5" role="status">{searchBusy ? '正在搜索…' : searchStatus}</p>
      <ul class="poster-grid">
        {#each results as work (work.id)}
          <li>
            <button class="poster-card" onclick={() => open(work)}>
              <Poster title={work.display.title} candidates={[work.display.poster, ...work.sources.map(item => item.poster)]} />
              <strong>{work.display.title || '未命名作品'}</strong>
              <small>{work.display.year === '0' ? '' : work.display.year} {work.display.remarks}</small>
            </button>
          </li>
        {/each}
      </ul>
    {/if}
  {:else if page === 'detail' && visibleWork && source}
    <button class="quiet mb-5" onclick={() => back('search')}>← 返回</button>
    <div class="work-overview">
      {#key source.site.name + ':' + source.id}
        <Poster title={source.title} candidates={[detailBusy ? source.poster : detail?.source.poster || source.poster]} eager />
      {/key}
      <div class="min-w-0 self-center">
        <h1 bind:this={detailHeading} tabindex="-1">{detail?.source.title || source.title}</h1>
        <p class="muted mt-2">{source.year === '0' ? '' : source.year} {source.remarks}</p>
        {#if detail?.overview}<p class="synopsis">{detail.overview}</p>{/if}
        <button class="primary mt-4" disabled={detailBusy || !line} onclick={resumeDetail}>播放</button>
      </div>
    </div>
    <section class="detail-section" aria-labelledby="source-heading">
      <h2 id="source-heading" tabindex="-1" bind:this={sourceHeading}>资源</h2>
      <div class="choices">
        {#each visibleWork.sources as item (item.site.name + ':' + item.id)}
          <button class="choice" aria-pressed={sameSource(item, source)} onclick={() => void loadDetail(item)}>{item.site.name.replace(/^🎬\s*/, '')}</button>
        {/each}
      </div>
    </section>
    <section class="detail-section" aria-labelledby="line-heading">
      <h2 id="line-heading">线路</h2>
      {#if detailBusy}<p class="muted" role="status">正在加载线路…</p>
      {:else if detailError}
        <p class="muted mb-3" role="alert">{detailError}</p>
        <button class="quiet" onclick={() => source && void loadDetail(source)}>重试</button>
      {:else if detail?.lines.length}
        <div class="choices">
          {#each detail.lines as item, index (item.id)}
            <button class="choice" aria-pressed={selectedLine === index} onclick={() => { selectedLine = index }}>{item.name}</button>
          {/each}
        </div>
      {:else}<p class="muted">此来源暂无播放线路。</p>{/if}
    </section>
    <section class="detail-section" aria-labelledby="episode-heading">
      <h2 id="episode-heading">选集</h2>
      {#if !detailBusy && line}
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
        canPrevious={selection.index > 0} canNext={selection.index < (line?.episodes.length ?? 0) - 1}
        onBack={() => back('detail')} onSources={() => { focusSources = true; back('detail') }}
        onPrevious={() => { if (selection) play(selection.index - 1) }} onNext={options => { if (selection) play(selection.index + 1, options) }} />
    {:else if playerLoadFailed}
      <p role="alert">播放器加载失败。</p>
      <button class="quiet mt-4" onclick={() => back('detail')}>返回详情</button>
    {:else}<p class="muted" role="status">正在准备播放…</p>{/if}
  {:else if page === 'settings'}
    <div class="settings-page">
      <h1>设置</h1>
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
        <form class="grid gap-3" onsubmit={saveServer} novalidate>
          <label for="server">弹幕 API 地址</label>
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
