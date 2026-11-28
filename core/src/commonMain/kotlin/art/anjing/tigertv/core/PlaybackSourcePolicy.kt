package art.anjing.tigertv.core

import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.domain.VideoWork

val VideoWork.defaultSource: SearchResult get() = preferredSource()

fun VideoWork.preferredSource(available: List<SearchResult> = sources): SearchResult =
    available.firstOrNull { it.coverKey == lastPlayedSourceKey }
        ?: available.firstOrNull { it.site == recentPlayedSite }
        ?: available.firstOrNull() ?: sources.first()
