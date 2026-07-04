package art.anjing.tigertv

import art.anjing.tigertv.data.PlaybackUrlResolver
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PlaybackUrlResolverTest {

    private lateinit var server: MockWebServer
    private lateinit var resolver: PlaybackUrlResolver

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        resolver = PlaybackUrlResolver()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `direct m3u8 returns itself`() = runBlocking {
        val url = "https://cdn.example.com/playlist/index.m3u8?sign=abc"
        assertEquals(url, resolver.resolve(url))
    }

    @Test
    fun `absolute m3u8 in html is extracted`() = runBlocking {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
              <script>
                var src = "https://cdn.example.com/playlist/index.m3u8?sign=abc123";
              </script>
            </body>
            </html>
        """.trimIndent()
        server.enqueue(MockResponse().setBody(html))
        val result = resolver.resolve(server.url("/player.html").toString())
        assertEquals("https://cdn.example.com/playlist/index.m3u8?sign=abc123", result)
    }

    @Test
    fun `relative m3u8 in html is resolved`() = runBlocking {
        val html = """
            <script>
              var src = "/hls/video.m3u8?token=xyz";
            </script>
        """.trimIndent()
        server.enqueue(MockResponse().setBody(html))
        val result = resolver.resolve(server.url("/player.html").toString())
        assertEquals(server.url("/hls/video.m3u8?token=xyz").toString(), result)
    }

    @Test
    fun `escaped slashes are normalized`() = runBlocking {
        val html = """
            <script>
              var src = "https:\/\/cdn.example.com\/playlist\/escaped.m3u8?sign=escaped123";
            </script>
        """.trimIndent()
        server.enqueue(MockResponse().setBody(html))
        val result = resolver.resolve(server.url("/player.html").toString())
        assertEquals("https://cdn.example.com/playlist/escaped.m3u8?sign=escaped123", result)
    }

    @Test
    fun `encoded characters in url are decoded`() = runBlocking {
        val html = """
            <script>
              var src = "https://cdn.example.com/playlist/index.m3u8?sign=a%2Fb%2Bc%3D";
            </script>
        """.trimIndent()
        server.enqueue(MockResponse().setBody(html))
        val result = resolver.resolve(server.url("/player.html").toString())
        assertEquals("https://cdn.example.com/playlist/index.m3u8?sign=a/b+c=", result)
    }

    @Test
    fun `direct mp4 returns itself`() = runBlocking {
        val url = "https://cdn.example.com/video.mp4"
        assertEquals(url, resolver.resolve(url))
    }

    @Test
    fun `raw m3u8 content returned from non m3u8 url`() = runBlocking {
        val content = "#EXTM3U\n#EXTINF:10,\nhttp://cdn.test/seg.ts\n"
        server.enqueue(MockResponse().setBody(content))
        val requestUrl = server.url("/play.php?id=1").toString()
        val result = resolver.resolve(requestUrl)
        assertEquals(requestUrl, result)
    }
}
