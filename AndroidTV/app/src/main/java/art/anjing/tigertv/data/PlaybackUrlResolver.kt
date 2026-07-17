package art.anjing.tigertv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class PlaybackUrlResolver(
    private val client: OkHttpClient = ConfigDataSource.defaultHttpClient()
) {
    suspend fun resolve(urlString: String): String = withContext(Dispatchers.IO) {
        val url = URL(urlString)
        val pathLower = url.path.lowercase()
        if (pathLower.endsWith(".m3u8") || pathLower.endsWith(".mp4")) {
            return@withContext urlString
        }

        val request = Request.Builder()
            .url(urlString)
            .header("User-Agent", USER_AGENT)
            .build()

        val html = client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty body")
                // 10MB 上限：request(MAX+1) 读到上限或 EOF 即停；
                // 返回 true 说明响应 > MAX 直接拒绝，否则 buffer 即完整 body。
                val source = body.source()
                if (source.request((MAX_RESPONSE_SIZE + 1).toLong())) {
                    throw IOException("Response exceeds 10MB limit")
                }
                val bytes = source.readByteArray(source.buffer.size)
                String(bytes, body.contentType()?.charset() ?: Charsets.UTF_8)
            }

        if (html.trimStart().startsWith("#EXTM3U", ignoreCase = true)) {
            return@withContext urlString
        }

        val decoded = html.replace("\\/", "/")

        val absoluteMatcher = ABSOLUTE_M3U8.matcher(decoded)
        if (absoluteMatcher.find()) {
            return@withContext absoluteMatcher.group(1)!!.percentDecode()
        }

        val relativeMatcher = RELATIVE_M3U8.matcher(decoded)
        if (relativeMatcher.find()) {
            val relative = relativeMatcher.group(1)!!.percentDecode()
            return@withContext URL(url, relative).toString()
        }

        throw IOException("No m3u8 URL found")
    }

    private fun String.percentDecode(): String {
        return try {
            URLDecoder.decode(this, "UTF-8")
        } catch (e: IllegalArgumentException) {
            this
        }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.4 Safari/605.1.15"
        private const val MAX_RESPONSE_SIZE = 10 * 1024 * 1024  // 10MB

        private val ABSOLUTE_M3U8 = Pattern.compile(
            "(https?://[^\\s\"'<>]+\\.m3u8(?:\\?[^\\s\"'<>]+)?)",
            Pattern.CASE_INSENSITIVE
        )
        private val RELATIVE_M3U8 = Pattern.compile(
            "[\"']([^\"']*\\.m3u8(?:\\?[^\"']+)?)[\"']",
            Pattern.CASE_INSENSITIVE
        )
    }
}
