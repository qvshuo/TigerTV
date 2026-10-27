package art.anjing.tigertv.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class MemoryStorage : Storage {
    val files = mutableMapOf<String, String>()
    override fun read(path: String) = files[path]
    override fun writeAtomic(path: String, text: String) { files[path] = text }
}

class CoreServiceTest {
    private val config = """{"api_site":{"a":{"name":"🎬一","api":"https://a.test/api"},"duplicate":{"name":"🎬重复","api":"https://a.test/api"},"disabled":{"name":"🎬禁用","api":"https://b.test/api","_comment":null},"null":null}}"""

    @Test fun sharedRepositoryFixtures() = runTest {
        assertEquals(2, parseConfig(contractFixtures.getValue("config.sample.json")).apiSite.size)
        val search = wireJson.decodeFromString<art.anjing.tigertv.domain.SearchResponse>(contractFixtures.getValue("search.sample.json"))
        val fetch = wireJson.decodeFromString<art.anjing.tigertv.domain.FetchResponse>(contractFixtures.getValue("fetch.sample.json"))
        assertEquals(73480, search.results.first().vodId)
        assertTrue(fetch.vodPlayUrl.isNotEmpty())
        for (fixture in listOf("playback-html-absolute.sample.html", "playback-html-relative.sample.html", "playback-html-escaped.sample.html", "playback-urlencoded.sample.html")) {
            assertTrue(extractM3u8("https://page.example/player", contractFixtures.getValue(fixture)).first().startsWith("https://"))
        }
        assertEquals("https://cdn.example.com/playlist/index.m3u8?sign=a/b+c=", extractM3u8("https://page.example/player", contractFixtures.getValue("playback-urlencoded.sample.html")).first())
    }

    @Test fun filteringAndPythonIntegerRules() {
        assertEquals(listOf("🎬一"), parseConfig(config).apiSite.values.map { it.name })
        fun number(raw: String) = lenientInt(wireJson.parseToJsonElement(raw))
        assertEquals(1, number("1.9")); assertEquals(1, number("\"1\""))
        assertEquals(0, number("\"1.0\"")); assertEquals(0, number("null"))
        assertFails { parseVodList("""{"code":1,"list":[null]}""") }
    }

    @Test fun parsingPreservesEpisodeNamesAndSignedUrls() {
        assertEquals(" 第一集 ", parseEpisodeLinks(" 第一集 ${'$'}https://a.test/a?x=1+2#二${'$'}u${'$'}${'$'}${'$'}三${'$'}v", true).first().name)
        assertEquals("https://a.test/path/a.m3u8?sign=a+b", extractM3u8("https://a.test/path/page", "'a.m3u8?sign=a+b'").single())
        assertEquals("https://a.test/%E4%B8%AD%E6%96%87/%20?a=+", normalizeUrl("https://a.test/中文/%20?a=+"))
    }

    @Test fun sourceIsolationSearchFailureAndFetchIdContract() = runTest {
        val store = MemoryStorage().apply { files["source"] = config }
        val requests = mutableListOf<String>()
        val client = configureClient(HttpClient(MockEngine { request ->
            requests += request.url.toString()
            respond(if (request.url.parameters["ac"] == "list") """{"code":"1","list":[{"vod_id":"42","vod_name":"测试"}]}"""
            else """{"code":1.0,"list":[{"vod_id":999,"vod_play_url":"一${'$'}https://media.test/a.m3u8"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        val core = CoreService("cache", "source", store, client)
        try {
            val result = core.search(" 中文 ")
            assertEquals(" 中文 ", result.keyword)
            assertEquals(42, result.results.single().vodId)
            assertEquals("", result.results.single().vodPic)
            assertEquals(42, core.fetch("🎬一", 42).vodId)
            assertEquals(2, requests.size)
            assertFalse(store.files.keys.any { it.startsWith("cache/") })
        } finally { core.close() }
    }

    @Test fun staleCacheFallbackAndHistoryLimit() = runTest {
        val store = MemoryStorage().apply { files["cache/tigertv-cli-config-cache.json"] = """{"fetchedAtMillis":0,"config":$config}""" }
        val core = CoreService("cache", storage = store, client = configureClient(HttpClient(MockEngine { error("offline") })), clock = { 99 * CoreService.DAY })
        try {
            assertEquals(1, core.loadConfig().apiSite.size)
            core.saveHistory((1..30).map { "剧$it" })
            assertEquals(20, core.history().size)
        } finally { core.close() }
    }

    @Test fun remoteFallbackPersistsRawConfigAndFreshCacheSkipsNetwork() = runTest {
        val store = MemoryStorage()
        var requests = 0
        val core = CoreService("cache", storage = store, client = configureClient(HttpClient(MockEngine {
            requests++
            if (it.url.host == "cdn.test") error("CDN unavailable")
            respond(config)
        })), clock = { 10_000 }, configUrls = listOf("https://cdn.test" to 10_000L, "https://raw.test" to 5000L))
        try {
            assertEquals(1, core.loadConfig().apiSite.size)
            assertEquals(1, core.loadConfig().apiSite.size)
            assertEquals(2, requests)
            assertTrue(store.files.getValue("cache/tigertv-cli-config-cache.json").contains("disabled"))
        } finally { core.close() }
    }

    @Test fun coverFailuresRetryAndSuccessfulUrlsExpireAfterSevenDays() = runTest {
        val store = MemoryStorage().apply { files["source"] = config }
        var requests = 0
        var clock = 100L
        val core = CoreService("cache", "source", store, configureClient(HttpClient(MockEngine {
            requests++
            respond(if (requests < 3) """{"code":1,"list":[{"vod_pic":""}]}""" else """{"code":1,"list":[{"vod_pic":"https://pic.test/a.jpg"}]}""")
        })), clock = { clock })
        try {
            assertNull(core.resolveCoverFallback("🎬一", 1))
            assertNull(core.resolveCoverFallback("🎬一", 1))
            assertEquals("https://pic.test/a.jpg", core.resolveCoverFallback("🎬一", 1))
            assertEquals("https://pic.test/a.jpg", core.resolveCoverFallback("🎬一", 1))
            assertEquals(3, requests)
            clock += 7 * CoreService.DAY
            core.resolveCoverFallback("🎬一", 1)
            assertEquals(4, requests)
        } finally { core.close() }
    }

    @Test fun malformedSiteDoesNotDiscardOtherSearchResults() = runTest {
        val store = MemoryStorage().apply {
            files["source"] = """{"api_site":{"a":{"name":"🎬好","api":"https://good.test"},"b":{"name":"🎬坏","api":"https://bad.test"}}}"""
        }
        val core = CoreService("cache", "source", store, configureClient(HttpClient(MockEngine {
            respond(if (it.url.host == "bad.test") """{"code":1,"list":[null]}""" else """{"code":1,"list":[{"vod_id":1}]}""")
        })))
        try { assertEquals("🎬好", core.search("test").results.single().site) }
        finally { core.close() }
    }

    @Test fun historyUsesPersistentDirectoryIndependentlyOfCaches() = runTest {
        val store = MemoryStorage()
        val core = CoreService("cache", storage = store, historyDirectory = "data")
        try {
            core.saveHistory(listOf("逐玉🎬"))
            assertTrue("data/search-history.json" in store.files)
            assertFalse("cache/search-history.json" in store.files)
            assertEquals(listOf("逐玉🎬"), core.history())
        } finally { core.close() }
    }

    @Test fun quanxMasterFanoutIsBoundedAndCollectsKeyAndMediaDomains() = runTest {
        val store = MemoryStorage().apply { files["source"] = config }
        val requested = mutableListOf<String>()
        val core = CoreService("cache", "source", store, configureClient(HttpClient(MockEngine { request ->
            requested += request.url.toString()
            respond(when {
                request.url.parameters["ac"] == "list" -> """{"code":1,"list":[{"vod_id":1}]}"""
                request.url.parameters["ac"] == "detail" -> """{"code":1,"list":[{"vod_play_url":"一${'$'}https://media.test/master.m3u8"}]}"""
                request.url.encodedPath == "/master.m3u8" -> "#EXTM3U\n" + (1..4).joinToString("\n") { "#EXT-X-STREAM-INF:BANDWIDTH=$it\nchild$it.m3u8" }
                else -> "#EXTM3U\n#EXT-X-KEY:URI=\"https://key.test/key\"\n#EXT-X-MEDIA:URI=\"https://audio.test/a.m3u8\"\nhttps://segments.test/1.ts"
            })
        })))
        try {
            val result = core.collectQuanxDomains("test")
            assertTrue(result.m3u8.containsAll(listOf("key.test", "audio.test", "segments.test")))
            assertFalse(requested.any { "child4" in it })
        } finally { core.close() }
    }

    @Test fun responseLimitAndCancellation() = runTest {
        val exact = configureClient(HttpClient(MockEngine { respond("x".repeat(MAX_RESPONSE_SIZE)) }))
        try { assertEquals(MAX_RESPONSE_SIZE, Network(exact).get("https://a.test").length) }
        finally { exact.close() }
        val client = configureClient(HttpClient(MockEngine { respond("x".repeat(MAX_RESPONSE_SIZE + 1)) }))
        try { assertFailsWith<IllegalStateException> { Network(client).get("https://a.test") } }
        finally { client.close() }
        val cancelled = configureClient(HttpClient(MockEngine { throw CancellationException("cancelled") }))
        try { assertFailsWith<CancellationException> { Network(cancelled).get("https://a.test") } }
        finally { cancelled.close() }
    }
}
