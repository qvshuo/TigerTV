package art.anjing.tigertv.ui.player

import androidx.compose.runtime.*
import art.anjing.tigertv.core.*
import kotlinx.coroutines.*

/** One cancellable request per playback session; manual choices alone update preferences. */
internal class DanmakuState(
    private val scope: CoroutineScope, private val client: (String) -> DanmakuClient,
    initialEnabled: Boolean = true, private val episodeNumber: Int? = null,
    private val rememberTitle: (String, Long) -> Unit = { _, _ -> },
    private val preferredTitle: (String) -> Long? = { null },
    private val workYear: String = "",
) {
    var enabled by mutableStateOf(initialEnabled)
    var offsetMs by mutableLongStateOf(0)
    var titles by mutableStateOf<List<DanmakuTitle>>(emptyList()); private set
    var episodes by mutableStateOf<List<DanmakuEpisode>>(emptyList()); private set
    var selectedTitle by mutableStateOf<DanmakuTitle?>(null); private set
    var selectedEpisode by mutableStateOf<DanmakuEpisode?>(null); private set
    var track by mutableStateOf<DanmakuTrack?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var job: Job? = null
    private var base = ""
    private var query = ""
    private var generation = 0
    private val counts = mutableMapOf<Long, Int>()
    private val tracks = linkedMapOf<Long, DanmakuTrack>()

    private fun request(message: String, block: suspend () -> Unit) {
        val ticket = ++generation
        job?.cancel(); busy = true; error = null
        job = scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = message; android.util.Log.w("TigerTVDanmaku", "request failed: ${e.javaClass.simpleName}") }
            finally { if (ticket == generation) busy = false }
        }
    }
    private suspend fun comments(id: Long): DanmakuTrack = tracks[id] ?: withContext(Dispatchers.IO) { client(base).comments(id) }.also {
        if (tracks.size >= 3) tracks.remove(tracks.keys.first())
        tracks[id] = it
    }
    fun configure(url: String) {
        val normalized = url.trim().trimEnd('/')
        if (base == normalized) return
        ++generation; job?.cancel(); busy = false; error = null
        base = normalized; tracks.clear(); counts.clear()
        selectedTitle = null; selectedEpisode = null; episodes = emptyList(); titles = emptyList(); track = null
    }
    fun search(url: String, keyword: String, useSaved: Boolean = true) {
        configure(url)
        query = keyword.trim()
        selectedTitle = null; selectedEpisode = null; episodes = emptyList(); titles = emptyList(); track = null
        request("弹幕匹配失败，请手动选择") {
            val completed = withTimeoutOrNull(30_000) overall@ {
                suspend fun tryTitle(candidate: DanmakuTitle): Boolean = withTimeoutOrNull(8_000) candidate@ {
                    try {
                        val list = withContext(Dispatchers.IO) { client(base).episodes(candidate.id) }
                        val episode = matchingDanmakuEpisode(list, episodeNumber, danmakuSeason(query)) ?: return@candidate false
                        val data = comments(episode.id)
                        counts[candidate.id] = data.comments.size
                        if (data.comments.none { it.mode == 1 }) return@candidate false
                        selectedTitle = candidate; episodes = list; selectedEpisode = episode; track = data
                        true
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { false }
                } == true

                val saved = (if (useSaved) preferredTitle(base) else null)?.takeIf { it > 0 }
                val savedItem = saved?.let { DanmakuTitle(it, query, "") }
                // A saved choice works even when the search endpoint is temporarily unavailable.
                if (savedItem != null && tryTitle(savedItem)) {
                    titles = listOf(savedItem)
                    return@overall true
                }
                titles = withContext(Dispatchers.IO) { client(base).search(query) }
                val matchQuery = if (workYear.matches(Regex("[0-9]{4}"))) "$query($workYear)" else query
                val candidates = titles.filter { it.id != saved && reasonableDanmakuTitle(matchQuery, it.title) }
                    .sortedByDescending { counts[it.id] ?: 0 }.take(if (saved == null) 3 else 2)
                for (candidate in candidates) if (tryTitle(candidate)) return@overall true
                false
            }
            if (completed != true) error = if (titles.isEmpty()) "没有找到弹幕作品" else "未找到当前集的有效弹幕，请手动选择"
        }
    }
    fun select(title: DanmakuTitle) {
        rememberTitle(base, title.id)
        selectedTitle = title; selectedEpisode = null; episodes = emptyList(); track = null
        request("弹幕选集加载失败，请重试") {
            episodes = withContext(Dispatchers.IO) { client(base).episodes(title.id) }
            val episode = matchingDanmakuEpisode(episodes, episodeNumber, danmakuSeason(query))
            if (episode == null) { error = "请选择对应剧集"; return@request }
            selectedEpisode = episode; track = comments(episode.id)
            counts[title.id] = track!!.comments.size
            if (track!!.comments.isEmpty()) error = "这一集没有弹幕"
        }
    }
    fun select(episode: DanmakuEpisode) {
        selectedEpisode = episode; track = null
        request("弹幕加载失败，请重新选择集数") {
            track = comments(episode.id)
            if (track!!.comments.isEmpty()) error = "这一集没有弹幕"
        }
    }
    fun close() { job?.cancel() }
}
