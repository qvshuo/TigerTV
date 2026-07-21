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

    @Test
    fun `search parses results from small 200 body`() = runBlocking {
        // 回归测试：早期错误使用 readByteArray(MAX+1) 会对小 body 抛 EOFException，
        // 导致所有正常响应被当成失败。这里用小 body 确保能解析出结果。
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":1,"msg":"ok","list":[
                    {"vod_id":73480,"vod_name":"逐玉","vod_time":"2024-01-01","vod_remarks":"HD"}
                ]}
                """.trimIndent()
            )
        )
        val result = client.search("🎬-测试站-", server.url("/api.php/provide/vod").toString(), "逐玉")
        assertEquals(1, result.size)
        assertEquals("逐玉", result[0].vodName)
        assertEquals(73480, result[0].vodId)
    }

    @Test
    fun `float code is coerced to int so results are kept`() = runBlocking {
        // 部分站点把 code 返回成 1.0（浮点）。macOS JSONDecoder 自动强转 OK，
        // kotlinx 默认抛异常会丢站点。LenientIntSerializer 必须把 1.0 收编成 1。
        server.enqueue(
            MockResponse().setBody(
                """{"code":1.0,"msg":"ok","list":[{"vod_id":1,"vod_name":"x","vod_time":"","vod_remarks":""}]}"""
            )
        )
        val result = client.search("🎬-测试站-", server.url("/api.php/provide/vod").toString(), "666")
        assertEquals(1, result.size)
    }

    @Test
    fun `string vod_id is coerced to int`() = runBlocking {
        // 暴风资源等站点把 vod_id 返回成字符串 "102405"，与 CLI int(...) 一致收编。
        server.enqueue(
            MockResponse().setBody(
                """{"code":1,"msg":"ok","list":[{"vod_id":"102405","vod_name":"x","vod_time":"","vod_remarks":""}]}"""
            )
        )
        val result = client.search("🎬-测试站-", server.url("/api.php/provide/vod").toString(), "666")
        assertEquals(1, result.size)
        assertEquals(102405, result[0].vodId)
    }

    @Test
    fun `string code is coerced to int so results are kept`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":"1","msg":"ok","list":[{"vod_id":9,"vod_name":"y","vod_time":"","vod_remarks":""}]}"""
            )
        )
        val result = client.search("🎬-测试站-", server.url("/api.php/provide/vod").toString(), "666")
        assertEquals(1, result.size)
        assertEquals(9, result[0].vodId)
    }

    @Test
    fun `float vod_id is coerced to int`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":1,"msg":"ok","list":[{"vod_id":42.0,"vod_name":"z","vod_time":"","vod_remarks":""}]}"""
            )
        )
        val result = client.search("🎬-测试站-", server.url("/api.php/provide/vod").toString(), "666")
        assertEquals(1, result.size)
        assertEquals(42, result[0].vodId)
    }

    @Test
    fun `baofeng-style string vod_id keeps whole site results`() = runBlocking {
        // 回归：真实暴风资源形态 —— code 为 int，vod_id 为数字字符串；修复前整站 emptyList()。
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":1,"msg":"数据列表","list":[
                  {"vod_id":"136872","vod_name":"逐玉","vod_time":"2026-03-21 20:41:19","vod_remarks":"更新至第40集"}
                ]}
                """.trimIndent()
            )
        )
        val result = client.search("🎬暴风资源", server.url("/api.php/provide/vod").toString(), "逐玉")
        assertEquals(1, result.size)
        assertEquals(136872, result[0].vodId)
        assertEquals("逐玉", result[0].vodName)
        assertEquals("🎬暴风资源", result[0].site)
    }

    @Test
    fun `malformed list elements are swallowed in search`() = runBlocking {
        // list 中出现 null 元素时 search 应视为该站失败，返回空结果并继续，与 CLI 行为一致。
        server.enqueue(MockResponse().setBody("""{"code":1,"msg":"ok","list":[null]}"""))
        val result = client.search("🎬-测试站-", server.url("/api.php/provide/vod").toString(), "666")
        assertTrue(result.isEmpty())
    }

    @Test(expected = Exception::class)
    fun `malformed list elements are propagated in fetch`(): Unit = runBlocking {
        // fetch 时 list 元素非对象应抛反序列化异常，由调用方作为错误处理，与 CLI 行为一致。
        server.enqueue(MockResponse().setBody("""{"code":1,"msg":"ok","list":[null]}"""))
        client.fetchDetail(server.url("/api.php/provide/vod").toString(), 1)
    }
}
