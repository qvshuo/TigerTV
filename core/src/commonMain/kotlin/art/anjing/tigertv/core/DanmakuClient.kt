package art.anjing.tigertv.core

import io.ktor.http.URLBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.math.roundToLong

data class DanmakuTitle(val id: Long, val title: String, val source: String)
data class DanmakuEpisode(val id: Long, val title: String, val number: String, val season: Int? = null, val seasonId: String = "")
data class DanmakuComment(val timeMs: Long, val text: String, val mode: Int, val color: Int)
data class DanmakuTrack(val referenceDurationMs: Long?, val comments: List<DanmakuComment>)

/** Explicit title/episode selection: matching a title does not establish timeline alignment. */
class DanmakuClient internal constructor(private val network: Network, baseUrl: String, private val tracks: DanmakuTrackCache?) {
    constructor(network: Network, baseUrl: String) : this(network, baseUrl, null)
    private val base = baseUrl.trimEnd('/')
    suspend fun search(keyword: String): List<DanmakuTitle> {
        val url = URLBuilder("$base/api/v2/search/anime").apply { parameters.append("keyword", keyword) }.buildString()
        val root = Json.parseToJsonElement(network.get(url)).jsonObject
        check(root["success"]?.jsonPrimitive?.booleanOrNull == true) { "Danmaku search failed" }
        return root["animes"]?.jsonArray.orEmpty().mapNotNull { value ->
            val item = value.jsonObject
            val id = item["animeId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            DanmakuTitle(id, item["animeTitle"]?.jsonPrimitive?.contentOrNull.orEmpty(), item["source"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }
    suspend fun episodes(titleId: Long): List<DanmakuEpisode> {
        require(titleId > 0)
        val root = Json.parseToJsonElement(network.get("$base/api/v2/bangumi/$titleId")).jsonObject
        check(root["success"]?.jsonPrimitive?.booleanOrNull == true) { "Danmaku episodes failed" }
        val bangumi = root["bangumi"]?.jsonObject
        val seasons = bangumi?.get("seasons")?.jsonArray.orEmpty().associate { value ->
            val s = value.jsonObject
            s["id"]?.jsonPrimitive?.contentOrNull.orEmpty() to danmakuSeason(s["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
        return bangumi?.get("episodes")?.jsonArray.orEmpty().mapNotNull { value ->
            val item = value.jsonObject
            val id = item["episodeId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            DanmakuEpisode(id, item["episodeTitle"]?.jsonPrimitive?.contentOrNull.orEmpty(), item["episodeNumber"]?.jsonPrimitive?.contentOrNull.orEmpty(), seasons[item["seasonId"]?.jsonPrimitive?.contentOrNull], item["seasonId"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }
    suspend fun comments(episodeId: Long): DanmakuTrack {
        require(episodeId > 0)
        val url = "$base/api/v2/comment/$episodeId?format=json&duration=true"
        tracks?.get(url)?.let { return it }
        return parseDanmakuTrack(network.get(url, 50_000)).also { if (it.comments.isNotEmpty()) tracks?.put(url, it) }
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
