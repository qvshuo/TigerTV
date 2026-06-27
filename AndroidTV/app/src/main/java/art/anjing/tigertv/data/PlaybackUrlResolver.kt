package art.anjing.tigertv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URL
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
                response.body?.string() ?: throw IOException("Empty body")
            }

        val decoded = html.replace("\\/", "/")

        val absoluteMatcher = ABSOLUTE_M3U8.matcher(decoded)
        if (absoluteMatcher.find()) {
            return@withContext absoluteMatcher.group(1)!!
        }

        val relativeMatcher = RELATIVE_M3U8.matcher(decoded)
        if (relativeMatcher.find()) {
            val relative = relativeMatcher.group(1)!!
            return@withContext URL(url, relative).toString()
        }

        throw IOException("No m3u8 URL found")
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

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
