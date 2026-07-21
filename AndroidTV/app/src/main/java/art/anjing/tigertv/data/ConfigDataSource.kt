package art.anjing.tigertv.data

import android.content.Context
import android.util.Log
import art.anjing.tigertv.domain.SourceConfig
import art.anjing.tigertv.domain.SourceSite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class ConfigDataSource(
    private val context: Context,
    private val client: OkHttpClient = defaultHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
) {
    private val cacheFile: File
        get() = File(context.cacheDir, "tigertv-config-cache.json")

    suspend fun loadConfig(
        primaryUrl: String = CONFIG_URL,
        cdnUrl: String = CONFIG_CDN_URL,
        ttlMillis: Long = TimeUnit.DAYS.toMillis(1)
    ): Result<SourceConfig> = withContext(Dispatchers.IO) {
        // fetchError 用局部变量收集，避免跨调用复用同一可变字段带来的可见性问题。
        var fetchError: String? = null
        val onError: (String) -> Unit = { fetchError = it }
        try {
            val cached = readCache()

            // 读取时再过滤：缓存保留原始完整配置（含 _comment 站点），与 CLI 行为一致，
            // 过滤规则变更后无需等缓存过期即可生效。
            if (cached != null && isCacheFresh(ttlMillis)) {
                val filtered = filterConfigSafe(cached)
                if (filtered != null) {
                    Log.i(TAG, "Using fresh cached config")
                    return@withContext Result.Success(filtered)
                }
            }

            // Remote refresh priority: CDN first, then GitHub RAW；成功后缓存原始（未过滤）配置。
            val remoteRaw = fetchRemoteRaw(cdnUrl, 10_000L, onError)
                ?: fetchRemoteRaw(primaryUrl, 5_000L, onError)

            if (remoteRaw != null) {
                Log.i(TAG, "Using remote config")
                writeCache(remoteRaw)
                val filtered = filterConfigSafe(remoteRaw)
                if (filtered != null) {
                    return@withContext Result.Success(filtered)
                }
            }

            if (cached != null) {
                val filtered = filterConfigSafe(cached)
                if (filtered != null) {
                    Log.i(TAG, "Using expired cached config")
                    return@withContext Result.Success(filtered)
                }
            }

            val details = fetchError ?: "CDN and RAW config fetch failed or unusable, no cache"
            return@withContext Result.Error(IOException("Config unavailable: $details"))
        } catch (e: CancellationException) {
            // 结构化取消必须重抛，否则 ViewModel.cancel 无法中断子 job。
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load config", e)
            Result.Error(e)
        }
    }

    private fun readCache(): SourceConfig? {
        if (!cacheFile.exists()) return null
        return try {
            json.decodeFromString(SourceConfig.serializer(), cacheFile.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cache", e)
            null
        }
    }

    private fun writeCache(config: SourceConfig) {
        try {
            // 原子写：先写 tmp 再 rename，避免中断留下半截 JSON。
            val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tmp.writeText(json.encodeToString(SourceConfig.serializer(), config))
            tmp.renameTo(cacheFile)
            File(context.cacheDir, "tigertv-config-cache.timestamp")
                .writeText(System.currentTimeMillis().toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write cache", e)
        }
    }

    private fun isCacheFresh(ttlMillis: Long): Boolean {
        val tsFile = File(context.cacheDir, "tigertv-config-cache.timestamp")
        if (!tsFile.exists()) return false
        return try {
            val ts = tsFile.readText().toLong()
            System.currentTimeMillis() - ts < ttlMillis
        } catch (e: Exception) {
            false
        }
    }

    /** 获取远程原始（未过滤）配置。 */
    private fun fetchRemoteRaw(url: String, timeoutMs: Long, onError: (String) -> Unit): SourceConfig? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        val callClient = client.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return try {
            callClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    onError("$url returned HTTP ${response.code}")
                    return null
                }
                val body = response.body ?: run {
                    onError("$url returned empty body")
                    return null
                }
                // 10MB 上限：request(MAX+1) 读到上限或 EOF 即停；
                // 返回 true 说明响应 > MAX 直接拒绝，否则 buffer 即完整 body。
                val source = body.source()
                if (source.request((MAX_RESPONSE_SIZE + 1).toLong())) {
                    onError("$url response exceeds 10MB limit")
                    return null
                }
                val text = String(source.readByteArray(source.buffer.size), body.contentType()?.charset() ?: Charsets.UTF_8)
                json.decodeFromString(SourceConfig.serializer(), text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError("$url failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * 过滤：name 含 🎬、无 _comment、api 非空（与 CLI `if api and "🎬" in name and "_comment" not in value` 一致）。
     * 去重：按 api URL 去重，保留第一个遇到的站点（与 CLI、macOS 一致）。
     */
    private fun filterConfigSafe(config: SourceConfig): SourceConfig? {
        val seenApi = mutableSetOf<String>()
        val filtered = LinkedHashMap<String, SourceSite>()
        for (site in config.apiSite.values) {
            if (site.api.isEmpty()) continue
            if (!site.name.contains(MOVIE_EMOJI)) continue
            if (site.comment != null) continue
            if (!seenApi.add(site.api)) continue
            filtered[site.api] = site
        }
        return if (filtered.isEmpty()) null else SourceConfig(config.cacheTime, filtered)
    }

    companion object {
        private const val TAG = "ConfigDataSource"
        private const val MOVIE_EMOJI = "🎬"
        private const val CONFIG_URL = "https://raw.githubusercontent.com/qvshuo/TigerTV/refs/heads/main/skills/references/LunaTV-config.json"
        private const val CONFIG_CDN_URL = "https://cdn.jsdelivr.net/gh/qvshuo/TigerTV@main/skills/references/LunaTV-config.json"
        private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.4 Safari/605.1.15"
        private const val MAX_RESPONSE_SIZE = 10 * 1024 * 1024  // 10MB

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
