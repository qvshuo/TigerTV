package art.anjing.tigertv.core

import kotlinx.serialization.Serializable

@Serializable
data class ProductDefaults(
    val autoNext: Boolean,
    val rememberSpeed: Boolean,
    val filterAds: Boolean,
    val danmakuEnabled: Boolean,
    val searchHistoryLimit: Int,
)

val productDefaults = wireJson.decodeFromString<ProductDefaults>(productDefaultsJson)
