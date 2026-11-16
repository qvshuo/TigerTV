package art.anjing.tigertv.ui.player

import androidx.compose.runtime.*
import art.anjing.tigertv.core.*
import kotlinx.coroutines.*

/** Player-session UI state only. All service parsing remains in Core. */
internal class DanmakuState(private val scope: CoroutineScope, private val client: (String) -> DanmakuClient, initialEnabled: Boolean = true, private val episodeNumber: Int? = null, private val rememberTitle: (Long) -> Unit = {}) {
    var enabled by mutableStateOf(initialEnabled)
    var offsetMs by mutableLongStateOf(0)
    var titles by mutableStateOf<List<DanmakuTitle>>(emptyList())
        private set
    var episodes by mutableStateOf<List<DanmakuEpisode>>(emptyList())
        private set
    var selectedTitle by mutableStateOf<DanmakuTitle?>(null)
        private set
    var selectedEpisode by mutableStateOf<DanmakuEpisode?>(null)
        private set
    var track by mutableStateOf<DanmakuTrack?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null
    private var base = ""
    private var generation = 0

    private fun <T> request(message: String, fetch: suspend () -> T, accept: (T) -> Unit) {
        val ticket = ++generation
        job?.cancel()
        busy = true
        error = null
        job = scope.launch {
            try { accept(withContext(Dispatchers.IO) { fetch() }) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = message
                android.util.Log.w("TigerTVDanmaku", "request failed: ${e.javaClass.simpleName}")
            } finally { if (generation == ticket) busy = false }
        }
    }
    fun search(url: String, keyword: String, preferredTitle: Long? = null) {
        base = url.trim().trimEnd('/')
        selectedTitle = null; selectedEpisode = null; episodes = emptyList(); titles = emptyList(); track = null
        request("弹幕搜索失败，请重试", { client(base).search(keyword.trim()) }) {
            titles = it
            if (it.isEmpty()) error = "没有找到弹幕作品"
            val match = it.firstOrNull { title -> title.id == preferredTitle }
                ?: it.firstOrNull { title -> title.title.substringBefore('(').trim() == keyword.trim() }
            if (match != null) select(match)
        }
    }
    fun select(title: DanmakuTitle) {
        rememberTitle(title.id)
        selectedTitle = title; selectedEpisode = null; track = null; episodes = emptyList()
        request("弹幕选集加载失败，请重试", { client(base).episodes(title.id) }) {
            episodes = it
            if (it.isEmpty()) error = "没有可用的弹幕集数"
            it.firstOrNull { episode -> episode.number.toIntOrNull() == episodeNumber }?.let(::select)
        }
    }
    fun select(episode: DanmakuEpisode) {
        selectedEpisode = episode; track = null
        request("弹幕加载失败，请重新选择集数", { client(base).comments(episode.id) }) {
            track = it
            if (it.comments.isEmpty()) error = "这一集没有弹幕"
        }
    }
    fun close() { job?.cancel() }
}
