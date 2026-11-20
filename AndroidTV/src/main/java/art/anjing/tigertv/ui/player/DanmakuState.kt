package art.anjing.tigertv.ui.player

import androidx.compose.runtime.*
import art.anjing.tigertv.core.*
import kotlinx.coroutines.*
import javax.net.ssl.SSLHandshakeException

/** One cancellable sequential search per playback session; only successful manual choices persist. */
internal class DanmakuState(
    private val scope: CoroutineScope, private val client: (String) -> DanmakuClient,
    initialEnabled: Boolean = true, private val episodeLabel: String = "",
    private val rememberTitle: (String, Long) -> Unit = { _, _ -> },
    private val preferredTitle: (String) -> Long? = { null },
    private val workYear: String = "",
    private val rememberEpisode: (String, Long, Long) -> Unit = { _, _, _ -> },
    private val preferredEpisode: (String) -> Pair<Long, Long>? = { null },
) {
    var enabled by mutableStateOf(initialEnabled); private set
    var offsetMs by mutableLongStateOf(0)
    var titles by mutableStateOf<List<DanmakuTitle>>(emptyList()); private set
    var episodes by mutableStateOf<List<DanmakuEpisode>>(emptyList()); private set
    var selectedTitle by mutableStateOf<DanmakuTitle?>(null); private set
    var selectedEpisode by mutableStateOf<DanmakuEpisode?>(null); private set
    var track by mutableStateOf<DanmakuTrack?>(null, referentialEqualityPolicy()); private set
    val hasComments: Boolean get() = track?.comments?.isNotEmpty() == true
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var job: Job? = null
    private var automaticRequest = false
    private var base by mutableStateOf("")
    val isConfigured: Boolean get() = base.isNotBlank()
    private var workSeason: Int? = null
    private var generation = 0
    private val tracks = linkedMapOf<Long, DanmakuTrack>()
    private data class Loaded(val title: DanmakuTitle, val episodes: List<DanmakuEpisode>, val episode: DanmakuEpisode, val track: DanmakuTrack)

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
                if (ticket == generation && track == null) error = serviceError(e) ?: message
                android.util.Log.w("TigerTVDanmaku", "request failed: ${e.javaClass.simpleName}")
            }
            finally { if (ticket == generation) { busy = false; automaticRequest = false } }
        }
    }
    private fun serviceError(e: Exception): String? = when {
        e.message == "HTTP 401" || e.message == "HTTP 403" -> "弹幕服务拒绝访问，请检查 API 地址"
        e.message == "HTTP 429" -> "弹幕服务请求过多，请稍后重试"
        generateSequence<Throwable>(e) { it.cause }.any { it is SSLHandshakeException } ->
            "弹幕服务安全连接失败，请检查设备日期和时间"
        else -> null
    }

    private suspend fun comments(id: Long): DanmakuTrack {
        tracks[id]?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            val response = client(base).comments(id)
            response.copy(comments = response.comments.filter { it.mode == 1 })
        }
        if (tracks.size >= 3) tracks.remove(tracks.keys.first())
        tracks[id] = loaded
        return loaded
    }
    fun configure(url: String) {
        val normalized = url.trim().trimEnd('/')
        if (base == normalized) return
        cancelRequest(); error = null
        base = normalized; tracks.clear()
        selectedTitle = null; selectedEpisode = null; episodes = emptyList(); titles = emptyList(); track = null
    }
    private fun show(data: Loaded) {
        selectedTitle = data.title; episodes = data.episodes; selectedEpisode = data.episode; track = data.track
    }
    fun search(keyword: String, useSaved: Boolean = true) {
        if (!isConfigured) { error = "请先保存弹幕 API 地址"; return }
        val query = keyword.trim()
        workSeason = danmakuSeason(query) ?: danmakuSeason(episodeLabel)
        selectedTitle = null; selectedEpisode = null; episodes = emptyList(); titles = emptyList(); track = null
        request("弹幕匹配失败，请手动选择", automatic = true) {
            var failure: String? = null
            withTimeoutOrNull(90_000) overall@ {
                var requests = 0
                suspend fun load(candidate: DanmakuTitle, manualEpisode: Long? = null): Loaded? = withTimeoutOrNull(65_000) {
                    try {
                        if (requests >= 12) return@withTimeoutOrNull null
                        requests++
                        val (list, episode) = withContext(Dispatchers.IO) {
                            val list = client(base).episodes(candidate.id)
                            val episode = if (manualEpisode != null) list.singleOrNull { it.id == manualEpisode }
                                else matchingDanmakuEpisode(list, episodeLabel, workSeason)
                            list to episode
                        }
                        if (episode == null) return@withTimeoutOrNull null
                        if (episode.id !in tracks) { if (requests >= 12) return@withTimeoutOrNull null; requests++ }
                        Loaded(candidate, list, episode, comments(episode.id)).takeIf { it.track.comments.isNotEmpty() }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val serviceFailure = serviceError(e)
                        if (serviceFailure != null) {
                            failure = serviceFailure
                            requests = 12
                        }
                        null
                    }
                }
                val savedEpisode = if (useSaved) preferredEpisode(base) else null
                if (savedEpisode != null && savedEpisode.first > 0 && savedEpisode.second > 0) {
                    val item = DanmakuTitle(savedEpisode.first, query, "")
                    val data = load(item, savedEpisode.second)
                    if (data != null) { titles = listOf(item); show(data); return@overall }
                }
                val saved = (if (useSaved) preferredTitle(base) else null)?.takeIf { it > 0 }
                if (saved != null) {
                    val item = DanmakuTitle(saved, query, "")
                    val data = load(item)
                    if (data != null) { titles = listOf(item); show(data); return@overall }
                }
                suspend fun probe(keyword: String) {
                    if (requests >= 12) return
                    requests++
                    val results = withContext(Dispatchers.IO) { client(base).search(keyword) }
                    val seen = titles.mapTo(mutableSetOf()) { it.id }
                    titles = (titles + results).distinctBy { it.id }
                    val candidates = results.distinctBy { it.id }.mapNotNull { item ->
                        if (item.id == saved || item.id in seen) return@mapNotNull null
                        danmakuTitleRank(query, item.title, workYear, workSeason)?.let { it to item }
                    }.sortedWith(compareBy({ it.first }, { danmakuProbePriority(it.second.source) }))
                    var current: Loaded? = null
                    var currentRank: Int? = null
                    for ((rank, candidate) in candidates) {
                        if (requests >= 12 || currentRank?.let { rank > it } == true) break
                        val next = load(candidate) ?: continue
                        val previous = current
                        if (previous == null || shouldUpgradeDanmaku(previous.track, next.track, previous.track.comments.size, next.track.comments.size)) {
                            current = next
                            currentRank = rank
                            show(next)
                        }
                    }
                }
                probe(query)
                val normalized = danmakuSearchKeyword(query)
                if (track == null && normalized.isNotBlank() && normalized != query) probe(normalized)
            }
            if (track == null) error = failure ?: if (titles.isEmpty()) "没有找到弹幕作品" else "未找到当前集的有效弹幕，请手动选择"
        }
    }
    private fun rememberIfValid(data: DanmakuTrack) {
        if (data.comments.isEmpty()) { error = "这一集没有滚动弹幕"; return }
        val title = selectedTitle ?: return
        val episode = selectedEpisode ?: return
        rememberTitle(base, title.id)
        rememberEpisode(base, title.id, episode.id)
    }
    fun select(title: DanmakuTitle) {
        selectedTitle = title; selectedEpisode = null; episodes = emptyList(); track = null
        request("弹幕选集加载失败，请重试") {
            episodes = withContext(Dispatchers.IO) { client(base).episodes(title.id) }
            val episode = matchingDanmakuEpisode(episodes, episodeLabel, workSeason)
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
