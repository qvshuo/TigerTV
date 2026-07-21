package art.anjing.tigertv.data

import android.util.Log
import art.anjing.tigertv.domain.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class MacCMSApiClient(
    private val client: OkHttpClient = ConfigDataSource.defaultHttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    suspend fun search(siteName: String, api: String, keyword: String): List<SearchResult> {
        val url = buildUrl(api, mapOf("ac" to "list", "wd" to keyword, "pagesize" to "100"))
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
            } catch (e: CancellationException) {
                // 结构化取消必须重抛，否则 viewModelScope.cancel() 无法中断子 job。
                throw e
            } catch (e: Exception) {
                // 反序列化异常（含 list 元素非对象）视为该站失败，空结果继续，与 CLI 对 `{"list":[null]}` 的处理一致。
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
        return client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty body")
                // 10MB 上限：request(MAX+1) 读到上限或 EOF 即停；
                // 返回 true 说明响应 > MAX 直接拒绝，否则 buffer 即完整 body。
                // 不能用 readByteArray(MAX+1)——它是精确读取，body 不足时抛 EOFException，
                // 会让所有正常（小于 10MB）的响应全部失败。
                val source = body.source()
                if (source.request((MAX_RESPONSE_SIZE + 1).toLong())) {
                    throw IOException("Response exceeds 10MB limit")
                }
                val bytes = source.readByteArray(source.buffer.size)
                String(bytes, body.contentType()?.charset() ?: Charsets.UTF_8)
            }
    }

    companion object {
        private const val TAG = "MacCMSApiClient"
        private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.4 Safari/605.1.15"
        private const val MAX_RESPONSE_SIZE = 10 * 1024 * 1024  // 10MB
    }
}

@Serializable
data class MacCMSListResponse(
    @Serializable(with = LenientIntSerializer::class) val code: Int = 0,
    val msg: String = "",
    val list: List<MacCMSListItem> = emptyList()
)

@Serializable
data class MacCMSListItem(
    @SerialName("vod_id") @Serializable(with = LenientIntSerializer::class) val vodId: Int = 0,
    @SerialName("vod_name") val vodName: String = "",
    @SerialName("vod_time") val vodTime: String = "",
    @SerialName("vod_remarks") val vodRemarks: String = ""
)

@Serializable
data class MacCMSDetailResponse(
    @Serializable(with = LenientIntSerializer::class) val code: Int = 0,
    val msg: String = "",
    val list: List<MacCMSDetailItem> = emptyList()
)

@Serializable
data class MacCMSDetailItem(
    @SerialName("vod_id") @Serializable(with = LenientIntSerializer::class) val vodId: Int = 0,
    @SerialName("vod_name") val vodName: String = "",
    @SerialName("vod_play_url") val vodPlayUrl: String = "",
    @SerialName("vod_down_url") val vodDownUrl: String = ""
)

/**
 * 宽松 Int 反序列化：容忍 int / float / 数字字符串 / null，与 CLI 的 `int(code)` / `int(vod.get("vod_id",0) or 0)` 一致。
 *
 * 背景：部分 MacCMS 站点会把 `code` 或 `vod_id` 返回成 `"1"`（字符串）或 `1.0`（浮点）。
 * macOS 的 JSONDecoder 会自动把 `1.0` 强转成 Int，但 kotlinx.serialization 严格匹配类型，遇到浮点直接抛
 * SerializationException，导致整条响应被当作失败丢弃、该站点结果全部丢失（Android 比 macOS 少搜到结果）。
 * 这里把任何能解释成整数的值收编为 Int，失败则回落到默认值 0，与 CLI 的 `int(...) except → 0` 语义一致。
 */
object LenientIntSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val input = decoder as? JsonDecoder ?: return decoder.decodeInt()
        return coerceInt(input.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
    }

    private fun coerceInt(element: JsonElement): Int {
        if (element !is JsonPrimitive) return 0
        val content = element.content
        // 顺序：纯整数字面量 → 浮点 → 数字字符串；全部失败回落 0（含 null/JsonNull）。
        return content.toIntOrNull()
            ?: content.toDoubleOrNull()?.toInt()
            ?: content.toFloatOrNull()?.toInt()
            ?: 0
    }
}

// JSON 中的 null 值（JsonNull）content 为 "null"，toIntOrNull 返回 null → 落到 0，已覆盖。
