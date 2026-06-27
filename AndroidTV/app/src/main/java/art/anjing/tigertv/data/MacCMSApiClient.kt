package art.anjing.tigertv.data

import android.util.Log
import art.anjing.tigertv.domain.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class MacCMSApiClient(
    private val client: OkHttpClient = ConfigDataSource.defaultHttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    suspend fun search(siteName: String, api: String, keyword: String): List<SearchResult> {
        val url = buildUrl(api, mapOf("ac" to "list", "wd" to keyword))
        return withContext(Dispatchers.IO) {
            try {
                val response = executeGet(url)
                val parsed = json.decodeFromString(MacCMSListResponse.serializer(), response)
                check(parsed.code == 1) { "site returned code=${parsed.code} msg=${parsed.msg}" }
                parsed.list.map { item ->
                    SearchResult(
                        site = siteName,
                        vodId = item.vodId,
                        vodName = item.vodName,
                        vodTime = item.vodTime,
                        vodRemarks = item.vodRemarks
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Search failed for $siteName", e)
                emptyList()
            }
        }
    }

    suspend fun fetchDetail(api: String, vodId: Int): MacCMSDetailResponse {
        val url = buildUrl(api, mapOf("ac" to "detail", "ids" to vodId.toString()))
        return withContext(Dispatchers.IO) {
            val response = executeGet(url)
            json.decodeFromString(MacCMSDetailResponse.serializer(), response)
        }
    }

    private fun buildUrl(api: String, params: Map<String, String>): String {
        val httpUrl = api.toHttpUrlOrNull()
            ?: throw IOException("Invalid api url: $api")
        val builder = httpUrl.newBuilder()
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build().toString()
    }

    private fun executeGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                return response.body?.string() ?: throw IOException("Empty body")
            }
    }

    companion object {
        private const val TAG = "MacCMSApiClient"
        private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"
    }
}

@Serializable
data class MacCMSListResponse(
    val code: Int = 0,
    val msg: String = "",
    val list: List<MacCMSListItem> = emptyList()
)

@Serializable
data class MacCMSListItem(
    @SerialName("vod_id") val vodId: Int = 0,
    @SerialName("vod_name") val vodName: String = "",
    @SerialName("vod_time") val vodTime: String? = null,
    @SerialName("vod_remarks") val vodRemarks: String? = null
)

@Serializable
data class MacCMSDetailResponse(
    val code: Int = 0,
    val msg: String = "",
    val list: List<MacCMSDetailItem> = emptyList()
)

@Serializable
data class MacCMSDetailItem(
    @SerialName("vod_id") val vodId: Int = 0,
    @SerialName("vod_name") val vodName: String = "",
    @SerialName("vod_play_url") val vodPlayUrl: String = "",
    @SerialName("vod_down_url") val vodDownUrl: String = ""
)
