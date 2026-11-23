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
    val coverKey: String get() = coverCacheKey(site, vodId)
}

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

@Serializable
data class VideoWork(
    val id: String, val title: String, val sources: List<SearchResult>,
    @SerialName("display_source") val displaySourceId: String,
    @SerialName("last_played_site") val lastPlayedSite: String? = null,
    @SerialName("recent_played_site") val recentPlayedSite: String? = null,
    val playback: Map<String, PlaybackBookmark> = emptyMap(),
    @SerialName("playback_positions") val playbackPositions: Map<String, Map<String, Map<String, Long>>> = emptyMap()
) {
    val displaySource: SearchResult get() = sources.first { it.coverKey == displaySourceId }
    val orderedSources: List<SearchResult> get() = listOf(displaySource) + sources.filter { it.coverKey != displaySourceId }
    val defaultSource: SearchResult get() = sources.firstOrNull { it.site == lastPlayedSite }
        ?: sources.firstOrNull { it.site == recentPlayedSite }
        ?: sources.firstOrNull { it.vodPic.isNotBlank() } ?: sources.first()
}

@Serializable
data class PlaybackSource(val id: String, val name: String, val episodes: List<EpisodeLink>) {
    fun resumeEpisodeIndex(bookmark: PlaybackBookmark?): Int =
        if (bookmark?.line == id) episodes.indexOfFirst { it.name == bookmark.episode }.coerceAtLeast(0) else 0
}

private val taggedAlias = Regex("[（(【](?:又名|别名|原名|英文名|aka)\\s*[:：]\\s*[^)）】]+[)）】]\\s*$", RegexOption.IGNORE_CASE)
private val taggedYear = Regex("[（(【]((?:19|20)[0-9]{2})[)）】]\\s*$")
private val yearWithUnit = Regex("((?:19|20)[0-9]{2})\\s*年?")

internal fun foldWidth(text: String): String {
    if (text.none { it == '\u3000' || it in '\uFF01'..'\uFF5E' }) return text
    return buildString(text.length) {
        for (char in text) append(when (char) {
            '\u3000' -> ' '
            in '\uFF01'..'\uFF5E' -> char - 0xFEE0
            else -> char
        })
    }
}

private fun workIdentity(title: String, year: String, coverKey: String): String {
    val normalizedTitle = title.filterNot { it.isWhitespace() }.lowercase()
    val identity = if (normalizedTitle.isEmpty()) listOf(kotlinx.serialization.json.JsonNull,
        kotlinx.serialization.json.JsonPrimitive(coverKey))
    else listOf(kotlinx.serialization.json.JsonPrimitive(normalizedTitle), kotlinx.serialization.json.JsonPrimitive(year))
    return kotlinx.serialization.json.JsonArray(identity).toString()
}

fun legacyWorkId(source: SearchResult): String =
    workIdentity(source.vodName, source.vodYear.trim().takeUnless { it == "0" }.orEmpty(), source.coverKey)

fun workId(source: SearchResult): String {
    val title = foldWidth(source.vodName).trim()
    val withoutAlias = taggedAlias.replace(title, "").ifBlank { title }
    val rawYear = foldWidth(source.vodYear).trim().takeUnless { it == "0" }.orEmpty()
    val year = yearWithUnit.matchEntire(rawYear)?.groupValues?.get(1) ?: rawYear
    val tagged = taggedYear.find(withoutAlias)
    if (tagged != null && (year.isEmpty() || year == tagged.groupValues[1])) {
        val main = withoutAlias.substring(0, tagged.range.first).trimEnd()
        if (main.isNotEmpty()) return workIdentity(main, tagged.groupValues[1], source.coverKey)
    }
    return workIdentity(withoutAlias, year, source.coverKey)
}

fun aggregateWorks(results: List<SearchResult>): List<VideoWork> = results.distinctBy { it.coverKey }.groupBy(::workId)
    .map { (id, sources) -> VideoWork(id, sources.first().vodName, sources, sources.first().coverKey) }

@Serializable
data class PlaybackBookmark(val line: String, val episode: String)

data class RecentPlayback(
    val workId: String, val source: SearchResult, val bookmark: PlaybackBookmark,
    val positions: Map<String, Map<String, Long>>, val progressPercent: Int?, val playedAt: Long,
    val cardSource: SearchResult = source, val cardPoster: String? = null,
) {
    val positionMillis: Long get() = positions[bookmark.line]?.get(bookmark.episode) ?: 0
}

@Serializable
data class HotSearchCategory(val id: String, val name: String)

@Serializable
data class HotSearchItem(
    val id: String, val title: String, val year: String,
    val poster: String, val rating: Double?, val ratingCount: Int,
    val status: String
)
