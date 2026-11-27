package art.anjing.tigertv.core

import io.ktor.http.URLBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.roundToLong

@Serializable data class DanmakuTitle(val id: Long, val title: String, val source: String)
@Serializable data class DanmakuEpisode(val id: Long, val title: String, val number: String, val season: Int? = null, val seasonId: String = "")
@Serializable data class DanmakuChoice(val title: DanmakuTitle, val episode: DanmakuEpisode)
data class DanmakuComment(val timeMs: Long, val text: String, val mode: Int, val color: Int)
data class DanmakuTrack(val referenceDurationMs: Long?, val comments: List<DanmakuComment>)

/** Explicit title/episode selection: matching a title does not establish timeline alignment. */
class DanmakuClient internal constructor(private val network: Network, baseUrl: String, private val tracks: DanmakuTrackCache) {
    constructor(network: Network, baseUrl: String) : this(network, baseUrl, DanmakuTrackCache(::nowMillis))
    private val base = baseUrl.trimEnd('/')
    suspend fun search(keyword: String): List<DanmakuTitle> {
        val url = URLBuilder("$base/api/v2/search/anime").apply { parameters.append("keyword", keyword) }.buildString()
        val root = Json.parseToJsonElement(network.get(url, selectionRules.searchTimeoutMs)).jsonObject
        check(root["success"]?.jsonPrimitive?.booleanOrNull == true) { "Danmaku search failed" }
        return root["animes"]?.jsonArray.orEmpty().mapNotNull { value ->
            val item = value.jsonObject
            val id = item["animeId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            DanmakuTitle(id, item["animeTitle"]?.jsonPrimitive?.contentOrNull.orEmpty(), item["source"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }
    suspend fun episodes(titleId: Long): List<DanmakuEpisode> {
        require(titleId > 0)
        val root = Json.parseToJsonElement(network.get("$base/api/v2/bangumi/$titleId", selectionRules.episodesTimeoutMs)).jsonObject
        check(root["success"]?.jsonPrimitive?.booleanOrNull == true) { "Danmaku episodes failed" }
        val bangumi = root["bangumi"]?.jsonObject ?: return emptyList()
        val seasons = bangumi["seasons"]?.jsonArray.orEmpty()
        val workSeason = danmakuSeason(bangumi["animeTitle"]?.jsonPrimitive?.contentOrNull.orEmpty())
        val seasonById = seasons.associate { value ->
            val item = value.jsonObject
            val n = danmakuSeason(item["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
            item["id"]?.jsonPrimitive?.contentOrNull.orEmpty() to
                (if (seasons.size == 1 && n == 1) workSeason ?: n else n)
        }
        return bangumi["episodes"]?.jsonArray.orEmpty().mapNotNull { value ->
            val item = value.jsonObject
            val id = item["episodeId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val seasonId = item["seasonId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            DanmakuEpisode(id, item["episodeTitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                item["episodeNumber"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                seasonId.takeIf { it.isNotEmpty() }?.let { seasonById[it] }, seasonId)
        }
    }
    suspend fun comments(episodeId: Long, manual: Boolean = false): DanmakuTrack {
        require(episodeId > 0)
        val url = "$base/api/v2/comment/$episodeId?format=json&duration=true"
        tracks.get(url)?.let { return it }
        val timeout = if (manual) selectionRules.manualTrackTimeoutMs else selectionRules.trackTimeoutMs
        val text = network.get(url, timeout, socketTimeoutMillis = timeout)
        return withContext(Dispatchers.Default) { parseDanmakuTrack(text) }
            .also { if (it.comments.isNotEmpty()) tracks.put(url, it) }
    }
}

internal class DanmakuTrackCache(private val clock: () -> Long) {
    private val lock = Mutex()
    private val tracks = linkedMapOf<String, Pair<Long, DanmakuTrack>>()

    suspend fun get(url: String): DanmakuTrack? = lock.withLock {
        val entry = tracks.remove(url)?.takeIf { clock() - it.first in 0 until 600_000L } ?: return@withLock null
        tracks[url] = entry
        entry.second
    }

    suspend fun put(url: String, track: DanmakuTrack) = lock.withLock {
        tracks.remove(url)
        tracks[url] = clock() to track
        if (tracks.size > 3) tracks.remove(tracks.keys.first())
    }
}

internal fun parseDanmakuTrack(text: String): DanmakuTrack {
    val root = Json.parseToJsonElement(text).jsonObject
    fun millis(seconds: Double?): Long? = seconds?.takeIf { it.isFinite() && it >= 0 && it <= 604800 }?.let { (it * 1000).roundToLong() }
    val duration = millis(root["videoDuration"]?.jsonPrimitive?.doubleOrNull)?.takeIf { it > 0 }
    val comments = root["comments"]?.jsonArray.orEmpty().mapNotNull { value ->
        val item = value.jsonObject
        val fields = item["p"]?.jsonPrimitive?.contentOrNull?.split(',', limit = 4) ?: return@mapNotNull null
        val time = millis(fields.firstOrNull()?.toDoubleOrNull()) ?: return@mapNotNull null
        val message = item["m"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val mode = fields.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        val color = fields.getOrNull(2)?.toIntOrNull()?.takeIf { it in 0..0xFFFFFF } ?: return@mapNotNull null
        DanmakuComment(time, message, mode, color)
    }.sortedBy { it.timeMs }
    return DanmakuTrack(duration, comments)
}
