<script lang="ts">
  import { onMount, tick } from 'svelte'
  import sourceEvidence from '../../shared/hls-heuristics.json' with { type: 'json' }
  import Poster from './Poster.svelte'
  import { categories, doubanPoster, getDetail, hot, search, webUrl, type Detail, type Episode, type HotItem, type Site, type Source, type Work } from './api.ts'
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
  let hotError = $state(false)
  let hotAttempt = $state(0)
  let recent = $state.raw<Recent[]>(getRecent())
  let query = $state('')
  let submitted = $state('')
  let historyItems = $state(read<string[]>('search-history', []))
  let workPosters = $state.raw(read<Record<string, string>>('work-posters', {}))
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
  let searchPanel = $state<HTMLElement>()
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
    if (initial === 'search') void tick().then(() => searchPanel?.showPopover())
    else if (initial !== 'home') show(initial)
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
    const attempt = hotAttempt
    const controller = new AbortController()
    const local = snapshot.categories[id as keyof typeof snapshot.categories] ?? []
    hotItems = local
    hotFallback = local.length > 0
    hotError = false
    function showHot(items: HotItem[]) {
      if (!controller.signal.aborted && items.length) { hotItems = items; hotFallback = false }
    }
    void hot(id, controller.signal, showHot)
      .then(showHot)
      .catch(error => {
        if (!controller.signal.aborted) {
          hotError = true
          console.info('豆瓣推荐读取失败', id, attempt, error)
        }
      })
    return () => controller.abort()
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

  const sourceGroups = ['filter', 'sample', 'other'] as const
  function evidenceFor(site: Site) {
    const name = site.name.replaceAll('🎬', '').trim()
    if (sourceEvidence.verifiedFilterSources.includes(name)) return 'filter'
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
    selection = { work: { ...(visibleWork ?? selected), display: cardMetadata(visibleWork ?? selected) }, source: detail?.source ?? source, line: currentLine.id, episode: nextEpisode.name, index }
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
    onbeforetoggle={event => { if (event.newState === 'open') query = '' }}
    ontoggle={event => { if (event.newState === 'open') searchInput?.focus() }}>
    <form class="search-form" role="search" onsubmit={event => { event.preventDefault(); if (!searchBusy || query.trim() !== submitted) void runSearch() }}>
      <input type="search" bind:this={searchInput} bind:value={query} placeholder="搜索电影、剧集或综艺" aria-label="作品名称" required />
      <button class="primary" disabled={searchBusy && query.trim() === submitted}>搜索</button>
    </form>
    <section aria-labelledby="history-heading" class="history-section">
      <div class="section-heading">
        <h2 id="history-heading">搜索历史</h2>
        {#if historyItems.length}<button class="quiet" onclick={clearHistory}>清空</button>{/if}
      </div>
      {#if historyItems.length}
        <div class="choices">
          {#each historyItems.filter(item => !query.trim() || item.includes(query.trim())) as item (item)}<button class="choice" onclick={() => void runSearch(item)}>{item}</button>{/each}
        </div>
      {:else}<p class="muted">暂无搜索历史</p>{/if}
    </section>
  </div>
</header>

<main class="shell page">
  {#if page === 'home'}
    {#if recent.length}
      <section aria-labelledby="recent-heading">
        <h2 id="recent-heading">最近播放</h2>
        <ul class="recent-strip">
          {#each recent.slice(0, 12) as item (item.work.id)}
            <li>
              <button class="recent-card" onclick={() => open(item.work, item)}>
                <Poster title={item.work.display.title} candidates={posterCandidates(item.work)} onShown={url => rememberPoster(item.work, url)} />
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
    <section aria-labelledby="hot-heading" class={recent.length ? 'mt-6' : undefined}>
      <div class="section-heading">
        <h2 id="hot-heading">豆瓣热门</h2>
        {#if hotFallback}<span class="muted snapshot-date">{snapshot.date} 快照</span>{/if}
      </div>
      <div class="choices mb-3" aria-label="热门分类">
        {#each categories as [id, name] (id)}
          <button class="choice" aria-pressed={category === id} onclick={() => { category = id }}>{name}</button>
        {/each}
      </div>
      <ul class="poster-grid hot-grid">
        {#each hotItems as item, index (item.id)}
          <li>
            <button class="poster-card" onclick={() => void runSearch(item.title)}>
              <Poster title={item.title} candidates={[doubanPoster(item.poster), item.poster]} eager={index < 4} />
              <strong>{item.title}</strong>
              <small>{item.year}{item.rating ? ` · ${item.rating.toFixed(1)}` : ''}</small>
            </button>
          </li>
        {/each}
      </ul>
      {#if hotError}
        <button class="quiet mt-4" onclick={() => { hotAttempt += 1 }}>重试加载豆瓣热门</button>
      {/if}
    </section>
  {:else if page === 'search'}
    {#if submitted}
      <h1 class="mb-2 text-xl">“{submitted}”的搜索结果</h1>
      <p class="muted mb-3" role="status">{searchBusy ? '正在搜索…' : searchStatus}</p>
      <ul class="poster-grid">
        {#each results as work (work.id)}
          {@const display = cardMetadata(work)}
          <li>
            <button class="poster-card" onclick={() => open(work)}>
              <Poster title={display.title} candidates={posterCandidates(work)} onShown={url => rememberPoster(work, url)} />
              <strong>{display.title || '未命名作品'}</strong>
              <small>{display.year === '0' ? '' : display.year} {display.remarks}</small>
            </button>
          </li>
        {/each}
      </ul>
    {/if}
  {:else if page === 'detail' && visibleWork && source}
    {@const detailPoster = !detailBusy && detail ? detail.source.poster : ''}
    {@const year = detail?.source.year && detail.source.year !== '0' ? detail.source.year : source.year}
    {@const metadata = [year === '0' ? '' : year, detail?.source.category || source.category, detail?.source.area].filter(Boolean).join(' · ')}
    <button class="quiet mb-3" onclick={() => back('search')}>← 返回</button>
    <div class="work-overview">
      {#key source.site.name + ':' + source.id + ':' + detailPoster}
        <Poster title={source.title} candidates={[detailPoster, source.poster]} eager />
      {/key}
      <div class="min-w-0 self-center">
        <h1 bind:this={detailHeading} tabindex="-1">{detail?.source.title || source.title}</h1>
        {#if metadata}<p class="muted mt-2">{metadata}</p>{/if}
        {#if detail?.overview}<p class="synopsis">{detail.overview}</p>{/if}
        <button class="primary mt-3" disabled={detailBusy || !line} onclick={resumeDetail}>播放</button>
      </div>
    </div>
    <section class="detail-section" aria-labelledby="source-heading">
      <h2 id="source-heading" tabindex="-1" bind:this={sourceHeading}>资源</h2>
      {#if visibleWork.sources.some(item => evidenceFor(item.site) !== 'other')}
        <h3 class="mb-2 mt-3">推荐资源 <span class="source-badge">弹幕</span></h3>
      {/if}
      {#each sourceGroups as evidence (evidence)}
        {@const group = visibleWork.sources.filter(item => evidenceFor(item.site) === evidence)}
        {#if group.length}
          {#if evidence === 'other'}
            <h3 class="mb-2 mt-3">其他资源</h3>
          {:else if evidence === 'sample' || settings.filterAds}
            <h4 class="mb-2 mt-3">
              <span class="source-badge">{evidence === 'sample' ? '样本未见博彩插播' : '去广告'}</span>
            </h4>
          {/if}
          <div class="choices">
            {#each group as item (item.site.name + ':' + item.id)}
              <button class="choice source-choice" aria-pressed={sameSource(item, source)} onclick={() => void loadDetail(item)}>
                {item.site.name.replace(/^🎬\s*/, '')}
              </button>
            {/each}
          </div>
        {/if}
      {/each}
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
