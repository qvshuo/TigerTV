package art.anjing.tigertv.data

import android.content.Context
import android.util.Log
import art.anjing.tigertv.domain.SourceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
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
        try {
            lastFetchError = null

            val cached = readCache()
            if (cached != null && isCacheFresh(ttlMillis)) {
                val filtered = filterConfigSafe(cached)
                if (filtered != null) {
                    Log.i(TAG, "Using fresh cached config")
                    return@withContext Result.Success(filtered)
                }
            }

            // Remote refresh priority: CDN first, then GitHub RAW
            val remote = tryRemote(cdnUrl, timeoutMs = 10_000)
                ?: tryRemote(primaryUrl, timeoutMs = 5_000)

            if (remote != null) {
                Log.i(TAG, "Using remote config")
                writeCache(remote)
                return@withContext Result.Success(remote)
            }

            if (cached != null) {
                val filtered = filterConfigSafe(cached)
                if (filtered != null) {
                    Log.i(TAG, "Using expired cached config")
                    return@withContext Result.Success(filtered)
                }
            }

            val details = lastFetchError ?: "CDN and RAW config fetch failed or unusable, no cache"
            return@withContext Result.Error(IOException("Config unavailable: $details"))
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
            cacheFile.writeText(json.encodeToString(SourceConfig.serializer(), config))
            File(context.cacheDir, "tigertv-config-cache.timestamp").writeText(System.currentTimeMillis().toString())
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

    private var lastFetchError: String? = null

    private fun fetchRemote(url: String, timeoutMs: Long): SourceConfig? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        val callClient = client.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return try {
            callClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastFetchError = "$url returned HTTP ${response.code}"
                    Log.w(TAG, lastFetchError!!)
                    return null
                }
                val body = response.body?.string() ?: run {
                    lastFetchError = "$url returned empty body"
                    Log.w(TAG, lastFetchError!!)
                    return null
                }
                json.decodeFromString(SourceConfig.serializer(), body)
            }
        } catch (e: Exception) {
            lastFetchError = "$url failed: ${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, lastFetchError!!)
            null
        }
    }

    private fun filterConfigSafe(config: SourceConfig): SourceConfig? {
        val filtered = config.apiSite.filterValues { site ->
            site.name.contains(MOVIE_EMOJI) && site.comment == null
        }
        return if (filtered.isEmpty()) null else config.copy(apiSite = filtered)
    }

    private fun tryRemote(url: String, timeoutMs: Long): SourceConfig? {
        val raw = fetchRemote(url, timeoutMs) ?: return null
        val filtered = filterConfigSafe(raw)
        if (filtered == null) {
            lastFetchError = "$url has no usable sites"
            Log.w(TAG, lastFetchError!!)
        }
        return filtered
    }

    companion object {
        private const val TAG = "ConfigDataSource"
        private const val MOVIE_EMOJI = "🎬"
        private const val CONFIG_URL = "https://raw.githubusercontent.com/qvshuo/TigerTV/refs/heads/main/skills/references/LunaTV-config.json"
        private const val CONFIG_CDN_URL = "https://cdn.jsdelivr.net/gh/qvshuo/TigerTV@main/skills/references/LunaTV-config.json"
        private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
