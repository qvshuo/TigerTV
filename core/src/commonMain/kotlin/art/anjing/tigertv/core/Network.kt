package art.anjing.tigertv.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

const val MAX_RESPONSE_SIZE = 10 * 1024 * 1024
const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/27.2 Safari/605.1.15"

fun configureClient(client: HttpClient) = client.config {
    followRedirects = false
    install(HttpTimeout)
}

internal data class NetworkResponse(val url: String, val text: String, val domains: Set<String>)

class Network(private val client: HttpClient) {
    suspend fun get(url: String, timeoutMillis: Long = 20_000, extraHeaders: Map<String, String> = emptyMap()): String =
        response(url, timeoutMillis, extraHeaders).text

    internal suspend fun response(
        url: String, timeoutMillis: Long = 20_000, extraHeaders: Map<String, String> = emptyMap(),
        head: Boolean = false, skipMediaBody: Boolean = false
    ): NetworkResponse = withTimeoutOrNull(timeoutMillis) {
        var current = normalizeUrl(url)
        val domains = mutableSetOf<String>()
        repeat(11) { hop ->
            domains += domain(current)
            var redirect: String? = null
            val result = client.prepareRequest(current) {
                if (head) method = HttpMethod.Head
                header(HttpHeaders.UserAgent, USER_AGENT)
                extraHeaders.forEach { (name, value) -> header(name, value) }
                timeout {
                    requestTimeoutMillis = timeoutMillis
                    connectTimeoutMillis = minOf(5_000, timeoutMillis)
                    socketTimeoutMillis = minOf(10_000, timeoutMillis)
                }
            }.execute { response ->
                if (response.status.value in listOf(301, 302, 303, 307, 308)) {
                    check(hop < 10) { "Too many redirects" }
                    redirect = resolveUrl(current, response.headers[HttpHeaders.Location] ?: error("Missing redirect location"))
                    null
                } else {
                    check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
                    val type = response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';').lowercase()
                    val media = skipMediaBody && (type == "video/mp4" || type == "video/mp2t" || type == "application/octet-stream" && Url(current).encodedPath.endsWith(".mp4"))
                    if (head || media) NetworkResponse(current, "", domains.toSet())
                    else {
                        check((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) <= MAX_RESPONSE_SIZE) { "Response exceeds 10 MiB limit" }
                        val channel = response.bodyAsChannel()
                        var bytes = ByteArray(8192)
                        var size = 0
                        while (true) {
                            coroutineContext.ensureActive()
                            if (size == bytes.size) bytes = bytes.copyOf(minOf(bytes.size * 2, MAX_RESPONSE_SIZE + 1))
                            val read = channel.readAvailable(bytes, size, bytes.size - size)
                            if (read == -1) break
                            size += read
                            check(size <= MAX_RESPONSE_SIZE) { "Response exceeds 10 MiB limit" }
                        }
                        NetworkResponse(current, bytes.decodeToString(0, size), domains.toSet())
                    }
                }
            }
            if (result != null) return@withTimeoutOrNull result
            current = redirect!!
        }
        error("Too many redirects")
    } ?: error("Request exceeded ${timeoutMillis}ms")
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
    } else {
        listOf("ac", "wd", "ids", "pg", "pagesize").forEach { builder.parameters.remove(it) }
        parameters.forEach { (key, value) -> builder.parameters[key] = value }
    }
    return builder.buildString()
}

fun resolveUrl(base: String, relative: String): String = URLBuilder(normalizeUrl(base)).apply {
    takeFrom(normalizeUrl(relative))
}.buildString()

fun domain(raw: String): String = Url(normalizeUrl(if ("://" in raw) raw else "http://$raw")).host

fun percentDecode(raw: String): String = runCatching { raw.decodeURLPart() }.getOrDefault(raw)
