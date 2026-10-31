package art.anjing.tigertv.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
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

    @Test fun allSourcesSearchConcurrentlyAndMergeWithoutMovingEarlierCards() = runTest {
        val started = List(12) { CompletableDeferred<Unit>() }
        val release = List(12) { CompletableDeferred<Unit>() }
        val store = MemoryStorage().apply {
            files["source"] = """{"api_site":{${(0..11).joinToString(",") {
                """"s$it":{"name":"🎬$it","api":"https://s$it.test/api"}"""
            }}}}"""
        }
        var requestCount = 0
        val client = configureClient(HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    val index = request.url.host.substringBefore('.').removePrefix("s").toInt()
                    requestCount++
                    started[index].complete(Unit)
                    release[index].await()
                    val titles = when (index) {
                        11 -> listOf("乙", "丙")
                        0 -> listOf("甲", "乙")
                        5 -> listOf("丙", "丁")
                        else -> emptyList()
                    }
                    respond("""{"code":1,"list":[${titles.mapIndexed { id, title ->
                        """{"vod_id":${id + 1},"vod_name":"$title","vod_year":"2026","vod_pic":"https://s$index.test/cover.jpg"}"""
                    }.joinToString(",")}]}""")
                }
            }
        })
        val core = CoreService("cache", "source", store, client)
        val updates = Channel<art.anjing.tigertv.domain.SearchResponse>(Channel.UNLIMITED)
        try {
            val search = async { core.search("作品") { updates.send(it) } }
            withTimeout(5_000) { started.forEach { it.await() } }
            release[11].complete(Unit)
            assertEquals(listOf("乙", "丙"), updates.receive().works.map { it.title })
            release[0].complete(Unit)
            val second = updates.receive()
            assertEquals(listOf("乙", "丙", "甲"), second.works.map { it.title })
            val merged = second.works.first()
            assertEquals(listOf("🎬0", "🎬11"), merged.sources.map { it.site })
            assertEquals("🎬0", merged.defaultSource.site)
            release[5].complete(Unit)
            val third = updates.receive()
            assertEquals(listOf("乙", "丙", "甲", "丁"), third.works.map { it.title })
            release.forEach { it.complete(Unit) }
            val final = search.await()
            assertEquals(third.works, final.works)
            assertEquals(second.works.take(2).map { it.id }, final.works.take(2).map { it.id })
            assertEquals(final, core.search("作品"))
            assertEquals(12, requestCount)
        } finally {
            release.forEach { it.complete(Unit) }
            core.close()
            updates.close()
        }
    }

    @Test fun logRolloverKeepsExactlyTheLatestTwoThousandLines() {
        val store = MemoryStorage().apply { files["log"] = (0 until 5000).joinToString("\n", postfix = "\n") }
        FileLogger(store, "log").log("INFO", "test", "new entry")
        val lines = store.files.getValue("log").trimEnd().lines()
        assertEquals(2000, lines.size)
        assertEquals("3001", lines.first())
        assertTrue(lines.last().endsWith("new entry"))
    }

    @Test fun sharedRepositoryFixtures() = runTest {
        assertEquals(2, parseConfig(contractFixtures.getValue("config.sample.json")).apiSite.size)
        val search = wireJson.decodeFromString<art.anjing.tigertv.domain.SearchResponse>(contractFixtures.getValue("search.sample.json"))
        val fetch = wireJson.decodeFromString<art.anjing.tigertv.domain.FetchResponse>(contractFixtures.getValue("fetch.sample.json"))
        assertEquals(1, search.works.first().sources.first().vodId)
        assertTrue(fetch.playSources.isNotEmpty())
        for (fixture in listOf("playback-html-absolute.sample.html", "playback-html-relative.sample.html", "playback-html-escaped.sample.html", "playback-urlencoded.sample.html")) {
            assertTrue(extractM3u8("https://page.example/player", contractFixtures.getValue(fixture)).first().startsWith("https://"))
        }
        assertEquals("https://cdn.example.com/playlist/index.m3u8?sign=a%2Fb%2Bc%3D", extractM3u8("https://page.example/player", contractFixtures.getValue("playback-urlencoded.sample.html")).first())
    }

    @Test fun filteringAndUpstreamIntegerValidation() {
        assertEquals(listOf("🎬一"), parseConfig(config).apiSite.values.map { it.name })
        fun number(raw: String) = apiInt(wireJson.parseToJsonElement(raw), "test")
        for (raw in listOf("1", "1.0", "\"1\"", "\"1.0\"")) assertEquals(1, number(raw))
        for (raw in listOf("1.9", "true", "null", "\"abc\"", "2147483648")) assertFails { number(raw) }
        for (raw in listOf("""{"list":[]}""", """{"code":null,"list":[]}""", """{"code":1,"list":[null]}""")) assertFails { parseVodList(raw) }
    }

    @Test fun parsingPreservesEpisodeNamesAndSignedUrls() {
        assertEquals("第一集", parsePlaybackSources(" 第一集 ${'$'}https://a.test/a?x=1+2#二${'$'}u${'$'}${'$'}${'$'}三${'$'}v", "").first().episodes.first().name)
        assertEquals("https://a.test/path/a.m3u8?sign=a+b", extractM3u8("https://a.test/path/page", "'a.m3u8?sign=a+b'").single())
        assertEquals("https://a.test/%E4%B8%AD%E6%96%87/%20?a=+", normalizeUrl("https://a.test/中文/%20?a=+"))
    }

    @Test fun signedPlaybackQueriesSurviveHtmlAndWrappedUrls() = runTest {
        val signed = "https://cdn.test/video.m3u8?sign=a%2Fb%2Bc%3D&part=1"
        assertEquals(signed, extractM3u8("https://page.test/", "<video src=\"${signed.replace("&", "&amp;")}\">").single())
        val wrapped = "https%3A%2F%2Fcdn.test%2Fvideo.m3u8%3Fsign%3Da%252Fb%252Bc%253D%26part%3D1"
        assertEquals(signed, extractM3u8("https://page.test/", "'$wrapped'").single())
        val core = CoreService("cache", storage = MemoryStorage(), client = configureClient(HttpClient(MockEngine {
            respond("<script>var url=\"$signed\";</script>")
        })))
        try { assertEquals(signed, core.resolvePlaybackUrl("https://page.test/player")) }
        finally { core.close() }
    }

    @Test fun apiOperationsClearOldSearchAndFetchParameters() {
        val base = "https://a.test/api?token=keep&ids=9&wd=old&pg=7&pagesize=1&ac=list"
        val fetch = Url(apiUrl(base, mapOf("ac" to "detail", "ids" to "42")))
        assertEquals("keep", fetch.parameters["token"])
        assertEquals("42", fetch.parameters["ids"])
        assertNull(fetch.parameters["wd"])
        assertNull(fetch.parameters["pg"])
        val search = Url(apiUrl(base, mapOf("ac" to "detail", "wd" to "逐玉", "pg" to "1", "pagesize" to "100")))
        assertNull(search.parameters["ids"])
        assertEquals("逐玉", search.parameters["wd"])
    }

    @Test fun lightweightSearchSkipsLargePlaybackFieldsAndValidatesPageMetadata() {
        val raw = """{"code":"1","pagecount":"2","list":[{"vod_id":"42","vod_name":"逐玉","vod_year":2026,"vod_play_url":"${"x".repeat(2_000_000)}","vod_content":{"unused":[1,2,3]}}]}"""
        val page = wireJson.decodeFromString<SearchPage>(raw)
        assertEquals(2, page.pageCount())
        assertEquals("2026", page.results("站").single().vodYear)
        assertTrue(wireJson.encodeToString(page).length < 500)
        for (invalid in listOf("null", "true", "1.5")) {
            assertFails { wireJson.decodeFromString<SearchPage>("""{"code":1,"pagecount":$invalid}""").pageCount() }
        }
    }

    @Test fun sourceIsolationAndTypedSearch() = runTest {
        val store = MemoryStorage().apply { files["source"] = config }
        val requests = mutableListOf<String>()
        val client = configureClient(HttpClient(MockEngine { request ->
            requests += request.url.toString()
            respond(if (request.url.parameters["wd"] != null) """{"code":"1","list":[{"vod_id":"42","vod_name":"测试"}]}"""
            else """{"code":1.0,"list":[{"vod_id":42,"vod_play_url":"一${'$'}https://media.test/a.m3u8"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        val core = CoreService("cache", "source", store, client)
        try {
            val result = core.search(" 中文 ")
            assertEquals(" 中文 ", result.keyword)
            assertEquals(42, result.works.single().sources.single().vodId)
            assertEquals("", result.works.single().sources.single().vodPic)
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
            respond(if (requests < 3) """{"code":1,"list":[{"vod_id":1,"vod_pic":""}]}""" else """{"code":1,"list":[{"vod_id":1,"vod_pic":"https://pic.test/a.jpg"}]}""")
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
        try { assertEquals("🎬好", core.search("test").works.single().sources.single().site) }
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

    @Test fun quanxRevisitsSharedMasterWithMoreRemainingDepth() = runTest {
        val store = MemoryStorage().apply { files["source"] = config }
        val requested = mutableListOf<String>()
        val core = CoreService("cache", "source", store, configureClient(HttpClient(MockEngine { request ->
            requested += request.url.encodedPath
            respond(when {
                request.url.parameters["ac"] == "list" -> """{"code":1,"list":[{"vod_id":1}]}"""
                request.url.parameters["ac"] == "detail" -> """{"code":1,"list":[{"vod_play_url":"一${'$'}https://media.test/root.m3u8"}]}"""
                request.url.encodedPath == "/root.m3u8" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nbranch.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2\nshared.m3u8"
                request.url.encodedPath == "/branch.m3u8" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nshared.m3u8"
                request.url.encodedPath == "/shared.m3u8" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nleaf.m3u8"
                else -> "#EXTM3U\nhttps://segments.test/1.ts"
            })
        })))
        try {
            val result = core.collectQuanxDomains("test")
            assertTrue("segments.test" in result.m3u8)
            assertEquals(2, requested.count { it == "/shared.m3u8" })
            assertEquals(1, requested.count { it == "/leaf.m3u8" })
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
