import { untrack } from 'svelte'
import danmakuRules from '../../../shared/danmaku-selection.json' with { type: 'json' }
import productDefaults from '../../../shared/product-defaults.json' with { type: 'json' }
import { episodeIdentity, read, write, type Selection } from '../storage.ts'
import { DanmakuClient, danmakuLoading, type DanmakuEpisode, type Title, type Track } from './client.ts'
import { autoMatch, type DanmakuMatch, type SavedDanmakuEpisode } from './selection.ts'

export function danmakuSession(
  currentSelection: () => Selection,
  serverAddress: () => string,
  inheritedEnabled: () => boolean | undefined,
  isPickerOpen: () => boolean,
  closePicker: () => void,
) {
  const selection = $derived(currentSelection())
  const server = $derived(serverAddress())
  let track = $state.raw<Track>()
  let enabled = $state(productDefaults.danmakuEnabled)
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

  function setDanmakuStatus(message: string, transient = false) {
    clearTimeout(statusTimer)
    danmakuStatus = message
    if (transient) statusTimer = setTimeout(() => { danmakuStatus = '' }, danmakuRules.render.statusDurationMs)
  }

  const danmakuTitleKey = (server: string, session: Selection) => 'danmaku-title:' + JSON.stringify([server, session.work.id, session.source.site.name, session.source.id])
  const danmakuEpisodeKey = (title: string, session: Selection) => 'danmaku-episode:' + JSON.stringify([title, ...episodeIdentity(session)])
  const titleKey = $derived(danmakuTitleKey(server, selection))
  const episodeKey = $derived(danmakuEpisodeKey(titleKey, selection))
  $effect(() => {
    const session = selection
    const title = titleKey
    const episode = episodeKey
    const showDanmaku = inheritedEnabled() ?? read('danmaku-enabled:' + episode, productDefaults.danmakuEnabled)
    enabled = showDanmaku
    untrack(closePicker)
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

  function owns(controller: AbortController, session: Selection, server: string) {
    return request === controller && !controller.signal.aborted && session === selection && server === serverAddress()
  }

  function startDanmaku(server: string, session: Selection, title: string, episode: string) {
    const controller = new AbortController()
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(danmakuRules.autoTimeoutMs)])
    request = automatic = controller
    void autoMatch(new DanmakuClient(server, signal, phase => {
      if (!signal.aborted && owns(controller, session, server) && !track) setDanmakuStatus(danmakuLoading[phase])
    }), session.work.display.title, session.work.display.year, session.episode,
      { title: read<number | undefined>(title, undefined),
        episode: read<SavedDanmakuEpisode | undefined>(episode, undefined) },
      (result, match) => {
        if (signal.aborted || !owns(controller, session, server)) return
        track = result
        activeDanmaku = match
        if (isPickerOpen()) restoreDanmakuChoice()
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
    } else if (enabled && !track && !request && serverAddress()) {
      startDanmaku(serverAddress(), selection, titleKey, episodeKey)
    }
  }
  async function manual<T>(work: (client: DanmakuClient) => Promise<T>, apply: (value: T) => void) {
    request?.abort()
    setDanmakuStatus('')
    const session = selection
    const server = serverAddress()
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
      closePicker()
    })
  }
  function restoreDanmakuChoice() {
    const match = activeDanmaku
    if (match && !titles.some(item => item.id === match.title.id)) titles = [match.title, ...titles]
    manualTitle = match?.title.id ?? 0
    manualEpisode = match?.episode.id ?? 0
    episodes = match?.episodes ?? []
  }

  function search() {
    manualTitle = 0
    manualEpisode = 0
    episodes = []
    if (keyword.trim()) void manual(client => client.search(keyword.trim()), value => {
      titles = value
      if (!value.length) manualStatus = '未找到本集弹幕'
    })
  }

  return {
    get track() { return track },
    get enabled() { return enabled },
    get status() { return danmakuStatus },
    get manualStatus() { return manualStatus },
    get titles() { return titles },
    get episodes() { return episodes },
    get manualTitle() { return manualTitle },
    get manualEpisode() { return manualEpisode },
    get keyword() { return keyword },
    set keyword(value: string) { keyword = value },
    chooseTitle, chooseEpisode, search, restoreDanmakuChoice, setDanmakuEnabled,
  }
}
