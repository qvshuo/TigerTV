package art.anjing.tigertv.ui.viewmodel

import androidx.compose.runtime.mutableStateMapOf
import art.anjing.tigertv.data.Result
import art.anjing.tigertv.domain.FetchResponse
import art.anjing.tigertv.domain.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal class DetailRequests(private val scope: CoroutineScope, private val fetch: suspend (SearchResult) -> Result<FetchResponse>) {
    private val detailCache = mutableStateMapOf<String, Pair<TimeMark, FetchResponse>>()
    private val detailLoads = mutableMapOf<String, Deferred<Result<FetchResponse>>>()
    private val checkedDetails = mutableStateMapOf<String, Boolean>()

    fun cachedDetail(source: SearchResult): FetchResponse? = detailCache[source.coverKey]
        ?.takeIf { it.first.elapsedNow() < 5.minutes }?.second

    fun isDetailLoading(source: SearchResult): Boolean = cachedDetail(source) == null && checkedDetails[source.coverKey] != true

    fun requestDetail(source: SearchResult): Deferred<Result<FetchResponse>> {
        val key = source.coverKey
        detailLoads[key]?.let { return it }
        checkedDetails[key] = false
        lateinit var pending: Deferred<Result<FetchResponse>>
        pending = scope.async(start = CoroutineStart.LAZY) {
            try {
                fetch(source).also { result ->
                    ensureActive()
                    if (result is Result.Success) {
                        if (detailCache.size >= 64 && key !in detailCache) {
                            detailCache.maxByOrNull { it.value.first.elapsedNow() }?.let { detailCache.remove(it.key) }
                        }
                        detailCache[key] = TimeSource.Monotonic.markNow() to result.data
                    }
                }
            } finally {
                if (detailLoads[key] === pending) {
                    checkedDetails[key] = true
                    detailLoads.remove(key)
                }
            }
        }
        detailLoads[key] = pending
        pending.start()
        return pending
    }

    fun cancelPending() {
        val pending = detailLoads.values.toList()
        detailLoads.clear()
        checkedDetails.clear()
        pending.forEach { it.cancel() }
    }
}
