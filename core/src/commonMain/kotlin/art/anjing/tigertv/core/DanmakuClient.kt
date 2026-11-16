package art.anjing.tigertv.core

import io.ktor.http.URLBuilder
import kotlinx.serialization.json.*
import kotlin.math.roundToLong

data class DanmakuTitle(val id: Long, val title: String, val source: String)
data class DanmakuEpisode(val id: Long, val title: String, val number: String)
data class DanmakuComment(val timeMs: Long, val text: String, val mode: Int, val color: Int)
data class DanmakuTrack(val referenceDurationMs: Long?, val comments: List<DanmakuComment>)

/** Explicit title/episode selection: matching a title does not establish timeline alignment. */
class DanmakuClient(private val network: Network, baseUrl: String) {
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
        return root["bangumi"]?.jsonObject?.get("episodes")?.jsonArray.orEmpty().mapNotNull { value ->
            val item = value.jsonObject
            val id = item["episodeId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            DanmakuEpisode(id, item["episodeTitle"]?.jsonPrimitive?.contentOrNull.orEmpty(), item["episodeNumber"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }
    suspend fun comments(episodeId: Long): DanmakuTrack {
        require(episodeId > 0)
        return parseDanmakuTrack(network.get("$base/api/v2/comment/$episodeId?format=json&duration=true"))
    }
}

internal fun parseDanmakuTrack(text: String): DanmakuTrack {
    val root = Json.parseToJsonElement(text).jsonObject
    fun millis(seconds: Double?): Long? = seconds?.takeIf { it.isFinite() && it >= 0 && it <= 604800 }?.let { (it * 1000).roundToLong() }
    val duration = millis(root["videoDuration"]?.jsonPrimitive?.doubleOrNull)?.takeIf { it > 0 }
    val comments = root["comments"]?.jsonArray.orEmpty().mapNotNull { value ->
        val item = value.jsonObject
        val fields = item["p"]?.jsonPrimitive?.contentOrNull?.split(',') ?: return@mapNotNull null
        val time = millis(fields.firstOrNull()?.toDoubleOrNull()) ?: return@mapNotNull null
        val message = item["m"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val mode = fields.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        val color = fields.getOrNull(2)?.toIntOrNull()?.takeIf { it in 0..0xFFFFFF } ?: return@mapNotNull null
        DanmakuComment(time, message, mode, color)
    }.sortedBy { it.timeMs }
    return DanmakuTrack(duration, comments)
}
