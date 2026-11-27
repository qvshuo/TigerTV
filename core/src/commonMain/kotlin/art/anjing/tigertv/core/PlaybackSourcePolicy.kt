package art.anjing.tigertv.core

import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.domain.VideoWork

val VideoWork.defaultSource: SearchResult get() = preferredSource()

fun VideoWork.preferredSource(filterAds: Boolean = productDefaults.filterAds, available: List<SearchResult> = sources): SearchResult {
    fun recommended(result: SearchResult) = isRecommendedSource(result.site, filterAds)
    return available.firstOrNull { it.coverKey == lastPlayedSourceKey }
        ?: available.firstOrNull { it.site == recentPlayedSite && recommended(it) }
        ?: available.firstOrNull(::recommended) ?: available.firstOrNull() ?: sources.first()
}
