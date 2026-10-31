package art.anjing.tigertv.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchResponse(
    @SerialName("query") val keyword: String,
    val works: List<VideoWork>
)

@Serializable
data class SearchResult(
    val site: String,
    @SerialName("vod_id") val vodId: Int,
    @SerialName("vod_name") val vodName: String,
    @SerialName("vod_time") val vodTime: String = "",
    @SerialName("vod_remarks") val vodRemarks: String = "",
    @SerialName("vod_pic") val vodPic: String = "",
    @SerialName("vod_year") val vodYear: String = "",
    @SerialName("type_name") val typeName: String = ""
) {
    /** 封面兜底缓存统一 key（内存 / 磁盘 / UI 三处共用，禁止手拼）。 */
    val coverKey: String get() = coverCacheKey(site, vodId)
}

/** 封面兜底缓存 key 的唯一构造点：`site-vodId`。 */
fun coverCacheKey(site: String, vodId: Int): String = "$site-$vodId"

@Serializable
data class FetchResponse(
    @SerialName("vod_id") val vodId: Int,
    val site: String,
    @SerialName("vod_name") val vodName: String = "",
    @SerialName("vod_pic") val vodPic: String = "",
    @SerialName("vod_year") val vodYear: String = "",
    @SerialName("type_name") val typeName: String = "",
    @SerialName("vod_remarks") val vodRemarks: String = "",
    @SerialName("vod_content") val vodContent: String = "",
    @SerialName("vod_actor") val vodActor: String = "",
    @SerialName("vod_director") val vodDirector: String = "",
    @SerialName("vod_area") val vodArea: String = "",
    @SerialName("vod_lang") val vodLang: String = "",
    @SerialName("play_sources") val playSources: List<PlaybackSource>,
    @SerialName("download_sources") val downloadSources: List<PlaybackSource>
)

@Serializable
data class EpisodeLink(
    val name: String,
    val url: String
)

@Serializable
data class SourceConfig(
    @SerialName("api_site") val apiSite: Map<String, SourceSite> = emptyMap()
)

@Serializable
data class SourceSite(
    val name: String,
    val api: String,
    val detail: String? = null
)

/** A work retains independent source IDs; resource order is separate from card order. */
@Serializable
data class VideoWork(
    val id: String, val title: String, val sources: List<SearchResult>,
    @SerialName("last_played_site") val lastPlayedSite: String? = null,
    @SerialName("recent_played_site") val recentPlayedSite: String? = null,
    val playback: Map<String, PlaybackBookmark> = emptyMap()
) {
    val orderedSources: List<SearchResult> get() = sources.sortedBy {
        when (it.site) { lastPlayedSite -> 0; recentPlayedSite -> 1; else -> 2 }
    }
    val defaultSource: SearchResult get() = sources.firstOrNull { it.site == lastPlayedSite }
        ?: sources.firstOrNull { it.site == recentPlayedSite }
        ?: sources.firstOrNull { it.vodPic.isNotBlank() } ?: sources.first()
}

@Serializable
data class PlaybackSource(val id: String, val name: String, val episodes: List<EpisodeLink>) {
    fun resumeEpisodeIndex(bookmark: PlaybackBookmark?): Int =
        if (bookmark?.line == id) episodes.indexOfFirst { it.name == bookmark.episode }.coerceAtLeast(0) else 0
}

fun aggregateWorks(results: List<SearchResult>): List<VideoWork> = results.distinctBy { it.coverKey }.groupBy { result ->
    val title = result.vodName.filterNot { it.isWhitespace() }.lowercase()
    val year = result.vodYear.trim().takeUnless { it == "0" }.orEmpty()
    // JSON tuple avoids delimiter collisions. Empty titles must never merge unrelated items.
    kotlinx.serialization.json.JsonArray(listOf(title.ifEmpty { result.coverKey }, year).map {
        kotlinx.serialization.json.JsonPrimitive(it)
    }).toString()
}.map { (id, sources) -> VideoWork(id, sources.first().vodName, sources) }

@Serializable
data class PlaybackBookmark(val line: String, val episode: String)

@Serializable
data class HotSearchCategory(val id: String, val name: String)
