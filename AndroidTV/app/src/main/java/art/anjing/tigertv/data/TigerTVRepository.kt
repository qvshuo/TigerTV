package art.anjing.tigertv.data

import art.anjing.tigertv.core.CoreService
import art.anjing.tigertv.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Android UI adapter. All runtime business rules are implemented in KMP core. */
class TigerTVRepository(private val core: CoreService) {
    private suspend fun <T> result(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try { Result.Success(block()) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.Error(e) }
    }

    suspend fun loadConfig(): Result<SourceConfig> = result { core.loadConfig() }
    suspend fun search(keyword: String): Result<SearchResponse> = result { core.search(keyword.trim()) }
    suspend fun fetch(siteName: String, vodId: Int): Result<FetchResponse> = result { core.fetch(siteName, vodId) }
    suspend fun resolvePlaybackUrl(url: String): Result<String> = result { core.resolvePlaybackUrl(url) }
    suspend fun resolveCoverFallback(siteName: String, vodId: Int): String? = withContext(Dispatchers.IO) { core.resolveCoverFallback(siteName, vodId) }
    suspend fun loadHistory(): List<String> = withContext(Dispatchers.IO) { core.history() }
    suspend fun saveHistory(items: List<String>) = withContext(Dispatchers.IO) { core.saveHistory(items) }
    fun close() = core.close()
}

/** Legacy cache reader retained for fixture regression tests; runtime cache lives in core. */
internal class CoverFallbackDiskCache(private val directory: File) {
    @Serializable
    data class Entry(val url: String, val fetchedAtMillis: Long)

    private val file = File(directory, "cover-fallback-cache.json")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(key: String): String? = mutex.withLock {
        val entries = read()
        val entry = entries[key] ?: return@withLock null
        if (System.currentTimeMillis() - entry.fetchedAtMillis >= TTL_MILLIS) {
            write(entries - key)
            null
        } else {
            entry.url
        }
    }

    suspend fun put(key: String, url: String) {
        put(key, url, System.currentTimeMillis())
    }

    internal suspend fun put(key: String, url: String, fetchedAtMillis: Long) = mutex.withLock {
        write(read() + (key to Entry(url, fetchedAtMillis)))
    }

    private fun read(): Map<String, Entry> = try {
        json.decodeFromString<Map<String, Entry>>(file.readText())
    } catch (_: Exception) {
        emptyMap()
    }

    private fun write(entries: Map<String, Entry>) {
        try {
            directory.mkdirs()
            // tmp + rename 原子写，避免进程中断留下半截 JSON。
            val tmp = File(directory, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(entries))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
            // 缓存写失败不影响主流程。
        }
    }

    companion object {
        const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
