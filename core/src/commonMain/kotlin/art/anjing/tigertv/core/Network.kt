package art.anjing.tigertv.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

const val MAX_RESPONSE_SIZE = 10 * 1024 * 1024
const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.4 Safari/605.1.15"

fun configureClient(client: HttpClient) = client.config { install(HttpTimeout) }

class Network(private val client: HttpClient) {
    suspend fun get(url: String, timeoutMillis: Long = 20_000): String {
        return client.prepareGet(normalizeUrl(url)) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            timeout { requestTimeoutMillis = timeoutMillis; connectTimeoutMillis = timeoutMillis; socketTimeoutMillis = timeoutMillis }
        }.execute { response ->
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(8192)
            val chunks = mutableListOf<ByteArray>()
            var size = 0
            while (true) {
                coroutineContext.ensureActive()
                val read = channel.readAvailable(buffer, 0, minOf(buffer.size, MAX_RESPONSE_SIZE + 1 - size))
                if (read == -1) break
                if (read == 0) continue
                size += read
                check(size <= MAX_RESPONSE_SIZE) { "Response exceeds 10MB limit" }
                chunks += buffer.copyOf(read)
            }
            val bytes = ByteArray(size)
            var offset = 0
            for (chunk in chunks) { chunk.copyInto(bytes, offset); offset += chunk.size }
            bytes.decodeToString(0, size, throwOnInvalidSequence = false)
        }
    }
}

/** Encode Unicode only; preserve existing escapes, plus signs and URL delimiters. */
fun normalizeUrl(raw: String): String = buildString {
    for (byte in raw.trim().encodeToByteArray()) {
        val value = byte.toInt() and 255
        if (value < 128) append(value.toChar())
        else { append('%'); append("0123456789ABCDEF"[value shr 4]); append("0123456789ABCDEF"[value and 15]) }
    }
}

fun apiUrl(api: String, parameters: Map<String, String>): String {
    val builder = URLBuilder(normalizeUrl(api))
    val nested = builder.parameters["url"]
    if (nested != null && (nested.startsWith("http://") || nested.startsWith("https://"))) {
        builder.parameters["url"] = apiUrl(nested, parameters)
    } else parameters.forEach { (key, value) -> builder.parameters.append(key, value) }
    return builder.buildString()
}

fun resolveUrl(base: String, relative: String): String = URLBuilder(normalizeUrl(base)).apply {
    takeFrom(normalizeUrl(relative))
}.buildString()

fun domain(raw: String): String = Url(normalizeUrl(if ("://" in raw) raw else "http://$raw")).host

fun percentDecode(raw: String): String = runCatching { raw.decodeURLPart() }.getOrDefault(raw)
