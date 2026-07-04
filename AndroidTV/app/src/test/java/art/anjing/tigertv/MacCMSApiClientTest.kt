package art.anjing.tigertv

import android.util.Log
import art.anjing.tigertv.data.MacCMSApiClient
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MacCMSApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: MacCMSApiClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = MacCMSApiClient()
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkStatic(Log::class)
    }

    @Test
    fun `search includes pagesize 100`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":1,"msg":"ok","list":[]}
                """.trimIndent()
            )
        )
        client.search("🎬-测试站-", server.url("/api.php/provide/vod").toString(), "逐玉")
        val request = server.takeRequest()
        val url = request.requestUrl
        assertEquals("100", url?.queryParameter("pagesize"))
        assertEquals("list", url?.queryParameter("ac"))
        assertEquals("逐玉", url?.queryParameter("wd"))
    }

    @Test
    fun `search returns empty list on failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = client.search("🎬-测试站-", server.url("/api.php/provide/vod").toString(), "逐玉")
        assertTrue(result.isEmpty())
    }
}
