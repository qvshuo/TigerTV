package art.anjing.tigertv.core

import art.anjing.tigertv.domain.HotSearchItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
internal data class HotSearchCache(val fetchedAtMillis: Long, val titles: List<HotSearchItem>)

@Serializable
internal data class HotSearchPage(
    @SerialName("subject_collection_items") val items: List<DoubanItem>,
    val total: Int? = null,
)

@Serializable
internal data class DoubanItem(
    val id: String, val title: String, val year: String = "", val pic: DoubanPicture? = null,
    val rating: DoubanRating? = null, @SerialName("episodes_info") val status: String = ""
)
@Serializable
internal data class DoubanPicture(val large: String = "")
@Serializable
internal data class DoubanRating(val value: Double = 0.0, val count: Int = 0)

@Serializable
internal data class HotSearchCollection(val id: String, val name: String, val collection: String)

internal val HOT_SEARCH_COLLECTIONS = wireJson.decodeFromString<List<HotSearchCollection>>(doubanCategoriesJson)
