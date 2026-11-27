package art.anjing.tigertv.ui.player

import androidx.compose.runtime.*
import art.anjing.tigertv.core.*
import kotlinx.coroutines.*

internal class DanmakuState(
    private val scope: CoroutineScope, private val client: (String) -> DanmakuClient,
    initialEnabled: Boolean = true, private val episodeLabel: String = "",
    private val rememberTitle: (String, Long) -> Unit = { _, _ -> },
    private val preferredTitle: (String) -> Long? = { null },
    private val workYear: String = "",
    private val rememberEpisode: (String, DanmakuChoice) -> Unit = { _, _ -> },
    private val preferredEpisode: (String) -> DanmakuChoice? = { null },
) {
    var enabled by mutableStateOf(initialEnabled); private set
    var titles by mutableStateOf<List<DanmakuTitle>>(emptyList()); private set
    var episodes by mutableStateOf<List<DanmakuEpisode>>(emptyList()); private set
    var selectedTitle by mutableStateOf<DanmakuTitle?>(null); private set
    var selectedEpisode by mutableStateOf<DanmakuEpisode?>(null); private set
    var track by mutableStateOf<DanmakuTrack?>(null, referentialEqualityPolicy()); private set
    private var activeChoice: DanmakuMatch? = null
    val hasComments: Boolean get() = track?.comments?.isNotEmpty() == true
    var loadingStep by mutableStateOf<DanmakuPhase?>(null); private set
    val busy: Boolean get() = loadingStep != null
    var error by mutableStateOf<String?>(null); private set
    private var job: Job? = null
    private var automaticRequest = false
    private var base by mutableStateOf("")
    private var configuredClient: DanmakuClient? = null
    val isConfigured: Boolean get() = base.isNotBlank()
    private var generation = 0

    private fun cancelRequest() {
        ++generation; job?.cancel(); job = null; automaticRequest = false; loadingStep = null
    }
    fun toggleEnabled() {
        enabled = !enabled
        if (!enabled && automaticRequest) cancelRequest()
    }
    private fun request(message: String, step: DanmakuPhase, automatic: Boolean = false, block: suspend () -> Unit) {
        cancelRequest()
        val ticket = generation
        automaticRequest = automatic; loadingStep = step; error = null
        job = scope.launch {
            try { block() }
            catch (e: TimeoutCancellationException) {
                if (ticket == generation && (!automatic || track == null)) error = message
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ticket == generation && (!automatic || track == null)) error = message
                android.util.Log.w("TigerTVDanmaku", "request failed: ${e.javaClass.simpleName}")
            }
            finally { if (ticket == generation) { loadingStep = null; automaticRequest = false } }
        }
    }
    private suspend fun comments(id: Long): DanmakuTrack {
        loadingStep = DanmakuPhase.Track
        return rolling(checkNotNull(configuredClient).comments(id, manual = true))
    }
    private fun rolling(track: DanmakuTrack) = track.copy(comments = track.comments.filter { it.mode == 1 })

    fun configure(url: String) {
        val normalized = url.trim().trimEnd('/')
        if (base == normalized) return
        cancelRequest(); error = null
        base = normalized
        configuredClient = normalized.takeIf { it.isNotEmpty() }?.let(client)
        activeChoice = null
        selectedTitle = null; selectedEpisode = null; episodes = emptyList(); titles = emptyList(); track = null
    }
    fun search(keyword: String, useSaved: Boolean = true) {
        val api = configuredClient ?: run { error = "未设置弹幕服务地址"; return }
        val query = keyword.trim()
        if (!useSaved) {
            request("无法加载弹幕", DanmakuPhase.Search) {
                titles = api.search(query)
                if (titles.isEmpty()) error = "未找到本集弹幕"
            }
            return
        }
        selectedTitle = null; selectedEpisode = null; episodes = emptyList(); titles = emptyList(); track = null
        activeChoice = null
        request("无法加载弹幕", DanmakuPhase.Search, automatic = true) {
            api.autoMatch(query, workYear, episodeLabel, preferredTitle(base), preferredEpisode(base),
                onPhase = { loadingStep = it },
                onTitles = { results -> titles = (titles + results).distinctBy { it.id } },
                onMatch = { match ->
                    activeChoice = match
                    selectedTitle = match.title
                    episodes = match.episodes
                    selectedEpisode = match.episode
                    track = rolling(match.track)
                },
            )
            if (track == null) error = "未找到本集弹幕"
        }
    }
    fun showCurrentChoice() {
        if (busy && !automaticRequest) return
        val match = activeChoice ?: return
        if (titles.none { it.id == match.title.id }) titles = listOf(match.title) + titles
        selectedTitle = match.title
        selectedEpisode = match.episode
        episodes = match.episodes
    }
    private fun rememberSelection() {
        val title = selectedTitle ?: return
        val episode = selectedEpisode ?: return
        rememberTitle(base, title.id)
        rememberEpisode(base, DanmakuChoice(title, episode))
    }
    fun select(title: DanmakuTitle) {
        val api = configuredClient ?: return
        selectedTitle = title; selectedEpisode = null; episodes = emptyList()
        request("无法加载弹幕", DanmakuPhase.Episodes) {
            episodes = api.episodes(title.id)
            if (episodes.isEmpty()) error = "暂无剧集"
        }
    }
    fun select(episode: DanmakuEpisode, onSelected: () -> Unit = {}) {
        request("无法加载弹幕", DanmakuPhase.Track) {
            val data = comments(episode.id)
            if (data.comments.isEmpty()) {
                error = "未找到本集弹幕"
                return@request
            }
            selectedEpisode = episode
            track = data
            selectedTitle?.let { activeChoice = DanmakuMatch(it, episodes, episode, data) }
            rememberSelection()
            onSelected()
        }
    }
    fun close() { cancelRequest() }
}
