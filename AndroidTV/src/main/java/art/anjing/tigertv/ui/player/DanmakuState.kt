package art.anjing.tigertv.ui.player

import androidx.compose.runtime.*
import art.anjing.tigertv.core.*
import kotlinx.coroutines.*

/** One cancellable sequential search per playback session; only successful manual choices persist. */
internal class DanmakuState(
    private val scope: CoroutineScope, private val client: (String) -> DanmakuClient,
    initialEnabled: Boolean = true, private val episodeNumber: Int? = null,
    private val rememberTitle: (String, Long) -> Unit = { _, _ -> },
    private val preferredTitle: (String) -> Long? = { null },
    private val workYear: String = "",
) {
    var enabled by mutableStateOf(initialEnabled); private set
    var offsetMs by mutableLongStateOf(0)
    var titles by mutableStateOf<List<DanmakuTitle>>(emptyList()); private set
    var episodes by mutableStateOf<List<DanmakuEpisode>>(emptyList()); private set
    var selectedTitle by mutableStateOf<DanmakuTitle?>(null); private set
    var selectedEpisode by mutableStateOf<DanmakuEpisode?>(null); private set
    var track by mutableStateOf<DanmakuTrack?>(null); private set
    val hasComments: Boolean get() = track?.comments?.any { it.mode == 1 } == true
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var job: Job? = null
    private var automaticRequest = false
    private var base by mutableStateOf("")
    val isConfigured: Boolean get() = base.isNotBlank()
    private var query = ""
    private var generation = 0
    private var manualTitle: DanmakuTitle? = null
    private val tracks = linkedMapOf<Long, DanmakuTrack>()
    private data class Loaded(val title: DanmakuTitle, val episodes: List<DanmakuEpisode>, val episode: DanmakuEpisode, val track: DanmakuTrack) {
        val count = track.comments.count { it.mode == 1 }
    }

    private fun cancelRequest() {
        ++generation; job?.cancel(); job = null; automaticRequest = false; busy = false
    }
    fun toggleEnabled() {
        enabled = !enabled
        if (!enabled && automaticRequest) cancelRequest()
    }
    private fun request(message: String, automatic: Boolean = false, block: suspend () -> Unit) {
        cancelRequest()
        val ticket = generation
        automaticRequest = automatic; busy = true; error = null
        job = scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ticket == generation && track == null) error = message
                android.util.Log.w("TigerTVDanmaku", "request failed: ${e.javaClass.simpleName}")
            }
            finally { if (ticket == generation) { busy = false; automaticRequest = false } }
        }
    }
    private suspend fun comments(id: Long): DanmakuTrack = tracks[id] ?: withContext(Dispatchers.IO) { client(base).comments(id) }.also {
        if (tracks.size >= 3) tracks.remove(tracks.keys.first())
        tracks[id] = it
    }
    fun configure(url: String) {
        val normalized = url.trim().trimEnd('/')
        if (base == normalized) return
        cancelRequest(); error = null
        base = normalized; tracks.clear(); manualTitle = null
        selectedTitle = null; selectedEpisode = null; episodes = emptyList(); titles = emptyList(); track = null
    }
    private fun show(data: Loaded) {
        selectedTitle = data.title; episodes = data.episodes; selectedEpisode = data.episode; track = data.track
    }
    fun search(keyword: String, useSaved: Boolean = true) {
        if (!isConfigured) { error = "请先保存弹幕 API 地址"; return }
        query = keyword.trim(); manualTitle = null
        selectedTitle = null; selectedEpisode = null; episodes = emptyList(); titles = emptyList(); track = null
        request("弹幕匹配失败，请手动选择", automatic = true) {
            withTimeoutOrNull(60_000) overall@ {
                var requests = 0
                suspend fun load(candidate: DanmakuTitle): Loaded? = withTimeoutOrNull(20_000) {
                    try {
                        if (requests >= 12) return@withTimeoutOrNull null
                        requests++
                        val list = withContext(Dispatchers.IO) { client(base).episodes(candidate.id) }
                        val episode = matchingDanmakuEpisode(list, episodeNumber, danmakuSeason(query)) ?: return@withTimeoutOrNull null
                        if (episode.id !in tracks) { if (requests >= 12) return@withTimeoutOrNull null; requests++ }
                        Loaded(candidate, list, episode, comments(episode.id)).takeIf { it.count > 0 }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        // Network exposes rejected requests as an HTTP status message.
                        if (e.message == "HTTP 429") requests = 12
                        null
                    }
                }
                val saved = (if (useSaved) preferredTitle(base) else null)?.takeIf { it > 0 }
                if (saved != null) {
                    val item = DanmakuTitle(saved, query, "")
                    val data = load(item)
                    if (data != null) { titles = listOf(item); show(data); return@overall }
                }
                if (requests >= 12) return@overall
                requests++
                titles = withContext(Dispatchers.IO) { client(base).search(query) }
                val matchQuery = if (workYear.matches(Regex("[0-9]{4}"))) "$query($workYear)" else query
                val candidates = titles.filter { it.id != saved && reasonableDanmakuTitle(matchQuery, it.title) }
                    .distinctBy { it.id }.sortedBy { danmakuProbePriority(it.source) }
                var current: Loaded? = null
                for (candidate in candidates) {
                    if (requests >= 12) break
                    val next = load(candidate) ?: continue
                    val previous = current
                    if (previous == null || shouldUpgradeDanmaku(previous.track, next.track, previous.count, next.count)) {
                        current = next; show(next)
                    }
                }
            }
            if (track == null) error = if (titles.isEmpty()) "没有找到弹幕作品" else "未找到当前集的有效弹幕，请手动选择"
        }
    }
    private fun rememberIfValid(data: DanmakuTrack) {
        if (data.comments.none { it.mode == 1 }) { error = "这一集没有滚动弹幕"; return }
        manualTitle?.let { rememberTitle(base, it.id) }
        manualTitle = null
    }
    fun select(title: DanmakuTitle) {
        manualTitle = title
        selectedTitle = title; selectedEpisode = null; episodes = emptyList(); track = null
        request("弹幕选集加载失败，请重试") {
            episodes = withContext(Dispatchers.IO) { client(base).episodes(title.id) }
            val episode = matchingDanmakuEpisode(episodes, episodeNumber, danmakuSeason(query))
            if (episode == null) { error = "请选择对应剧集"; return@request }
            selectedEpisode = episode; track = comments(episode.id)
            rememberIfValid(track!!)
        }
    }
    fun select(episode: DanmakuEpisode) {
        selectedEpisode = episode; track = null
        request("弹幕加载失败，请重新选择集数") {
            track = comments(episode.id)
            rememberIfValid(track!!)
        }
    }
    fun close() { cancelRequest() }
}
