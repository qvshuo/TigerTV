package art.anjing.tigertv.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchResponse(
    val keyword: String,
    val results: List<SearchResult>
)

@Serializable
data class SearchResult(
    val site: String,
    @SerialName("vod_id") val vodId: Int,
    @SerialName("vod_name") val vodName: String,
    @SerialName("vod_time") val vodTime: String = "",
    @SerialName("vod_remarks") val vodRemarks: String = "",
    @SerialName("vod_pic") val vodPic: String = ""
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
    @SerialName("vod_play_url") val vodPlayUrl: List<EpisodeLink>,
    @SerialName("vod_down_url") val vodDownUrl: List<EpisodeLink>
)

@Serializable
data class EpisodeLink(
    val name: String,
    val url: String
)

@Serializable
data class SourceConfig(
    @SerialName("cache_time") val cacheTime: Int? = null,
    @SerialName("api_site") val apiSite: Map<String, SourceSite> = emptyMap()
)

@Serializable
data class SourceSite(
    val name: String,
    val api: String,
    val detail: String? = null,
    @SerialName("_comment") val comment: String? = null
)
