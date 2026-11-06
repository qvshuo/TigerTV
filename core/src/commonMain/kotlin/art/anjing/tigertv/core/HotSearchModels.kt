package art.anjing.tigertv.core

import art.anjing.tigertv.domain.HotSearchItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
internal data class HotSearchCache(val fetchedAtMillis: Long, val titles: List<HotSearchItem>)

@Serializable
internal data class HotSearchPage(@SerialName("subject_collection_items") val items: List<DoubanItem>)

@Serializable
internal data class DoubanItem(
    val id: String, val title: String, val year: String = "", val pic: DoubanPicture? = null,
    val rating: DoubanRating? = null, @SerialName("episodes_info") val status: String = ""
)
@Serializable
internal data class DoubanPicture(val large: String = "")
@Serializable
internal data class DoubanRating(val value: Double = 0.0, val count: Int = 0)

internal data class HotSearchCollection(val id: String, val name: String, val collection: String)

internal val HOT_SEARCH_COLLECTIONS = listOf(
    HotSearchCollection("chinese", "国产剧", "tv_domestic"),
    HotSearchCollection("tvshow", "综艺", "tv_variety_show"),
    HotSearchCollection("american", "英美剧", "tv_american"),
    HotSearchCollection("korean", "韩剧", "tv_korean"),
    HotSearchCollection("japanese", "日剧", "tv_japanese"),
    HotSearchCollection("animation", "动漫", "tv_animation")
)
