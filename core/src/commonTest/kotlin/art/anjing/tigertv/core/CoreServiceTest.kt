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
            assertEquals("🎬11", merged.displaySource.site)
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

    @Test fun sharedServicesMergePersistentHistoryAndPlaybackWithoutOverwritingOtherWindows() = runTest {
        val store = MemoryStorage()
        val first = CoreService("cache1", storage = store, historyDirectory = "data")
        val second = CoreService("cache2", storage = store, historyDirectory = "data")
        try {
            listOf(async(Dispatchers.Default) { first.addHistory("甲") }, async(Dispatchers.Default) { second.addHistory("乙") }).awaitAll()
            assertEquals(setOf("甲", "乙"), first.history().toSet())
            first.removeHistory("甲")
            second.addHistory("丙")
            assertEquals(listOf("丙", "乙"), first.history())
            listOf(async(Dispatchers.Default) { first.recordPlayback("work", "A", "0", "一") },
                async(Dispatchers.Default) { second.recordPlayback("work", "B", "2", "二") }).awaitAll()
            listOf(async(Dispatchers.Default) { first.recordPlaybackPosition("work", "A", "0", "一", 1234) },
                async(Dispatchers.Default) { second.recordPlaybackPosition("work", "B", "2", "二", 5678) }).awaitAll()
            val work = wireJson.parseToJsonElement(store.files.getValue("data/last-played-sources.json"))
                .jsonObject.getValue("byWork").jsonObject.getValue("work").jsonObject
            assertEquals(setOf("A", "B"), work.getValue("resources").jsonObject.keys)
            val positions = work.getValue("positions").jsonObject
            assertEquals(1234L, positions.getValue("A").jsonObject.getValue("0").jsonObject.getValue("一").jsonPrimitive.long)
            assertEquals(5678L, positions.getValue("B").jsonObject.getValue("2").jsonObject.getValue("二").jsonPrimitive.long)
            second.clearHistory()
            assertTrue(first.history().isEmpty())
        } finally { first.close(); second.close() }
    }

    @Test fun recentPlaybackReusesExactBookmarksAndKeepsActualPlayOrder() = runTest {
        val store = MemoryStorage().apply {
            files["data/last-played-sources.json"] = """{"byWork":{"legacy":{"site":"A","resources":{"A":{"line":"0","episode":"旧集"}},"positions":{"A":{"0":{"旧集":900}}}}}}"""
        }
        var now = 1_000L
        val core = CoreService("cache", storage = store, historyDirectory = "data", clock = { now++ })
        val source = art.anjing.tigertv.domain.SearchResult("A", 42, "作品", vodPic = "https://a.test/cover")
        try {
            assertTrue(core.recentPlayback().isEmpty())
            core.recordPlayback("one", "A", "0", "首集", source, source, "https://a.test/display-poster", 0)
            core.recordPlaybackPosition("one", "A", "0", "首集", 800, 20, source.coverKey, 0)
            core.recordPlayback("two", "A", "0", "首集", source.copy(vodId = 43), index = 0)
            core.recordPlaybackPosition("one", "A", "0", "首集", 900, 25, source.coverKey, 0)
            assertEquals(listOf("two", "one"), core.recentPlayback().map { it.workId })
            val otherSource = source.copy(site = "B", vodId = 7)
            core.recordPlayback("one", "B", "2", "第二集", otherSource, index = 1)
            core.recordPlaybackPosition("one", "A", "0", "首集", 1_000, 30, source.coverKey, 0)
            val resumed = core.recentPlayback().first()
            assertEquals(otherSource, resumed.source)
            assertEquals(source, resumed.cardSource)
            assertEquals("https://a.test/display-poster", resumed.cardPoster)
            assertEquals(0L, resumed.positionMillis)
            assertNull(resumed.progressPercent)
            assertEquals(listOf("one", "two"), core.recentPlayback().map { it.workId })
            core.recordPlaybackPosition("one", "B", "2", "第二集", 23_000, 40, otherSource.coverKey, 1)
            core.recordPlaybackPosition("one", "B", "2", "第二集", 0, 100, otherSource.coverKey, 1)
            val completed = core.recentPlayback().first()
            assertEquals(0L, completed.positionMillis)
            assertEquals(100, completed.progressPercent)
        } finally { core.close() }
    }

    @Test fun historyMutationsRecoverFromCorruptStorageAndPropagateCancellation() = runTest {
        val store = MemoryStorage().apply { files["cache/search-history.json"] = "invalid" }
        val core = CoreService("cache", storage = store)
        try {
            core.addHistory("甲")
            core.addHistory("乙")
            core.addHistory("甲")
            assertEquals(listOf("甲", "乙"), core.history())
            core.removeHistory("乙")
            assertEquals(listOf("甲"), core.history())
            store.files["cache/search-history.json"] = "invalid"
            core.clearHistory()
            assertTrue(core.history().isEmpty())
        } finally { core.close() }
        val cancelled = CoreService("cache", storage = object : Storage {
            override fun read(path: String): String? = throw CancellationException("cancelled read")
            override fun writeAtomic(path: String, text: String) = error("must not write")
        })
        try { assertFailsWith<CancellationException> { cancelled.addHistory("甲") } }
        finally { cancelled.close() }
    }

    @Test fun historyWriteFailuresAreLoggedAndCancellationPropagates() = runTest {
        val files = mutableMapOf<String, String>()
        val diagnostics = mutableListOf<String>()
        var writeError: Exception? = IllegalStateException("disk unavailable")
        val store = object : Storage {
            override fun read(path: String) = files[path]
            override fun writeAtomic(path: String, text: String) {
                writeError?.let { throw it }
                files[path] = text
            }
        }
        val core = CoreService("cache", storage = store, logger = Logger { _, context, message -> diagnostics += "$context: $message" })
        try {
            core.addHistory("甲")
            assertTrue(core.history().isEmpty())
            assertTrue(diagnostics.single().contains("disk unavailable"))
            writeError = null
            core.addHistory("甲")
            assertEquals(listOf("甲"), core.history())
            writeError = CancellationException("cancelled write")
            assertFailsWith<CancellationException> { core.removeHistory("甲") }
            assertEquals(listOf("甲"), core.history())
            assertEquals(1, diagnostics.size)
        } finally { core.close() }
    }

    @Test fun fetchPreservesRawPlaybackAddressesWithoutProbingThem() = runTest {
        val expected = wireJson.decodeFromString<art.anjing.tigertv.domain.FetchResponse>(contractFixtures.getValue("fetch-playback.sample.json"))
        val episodes = expected.playSources.single().episodes
        assertEquals(listOf("HLS", "MP4", "HTML", ""), episodes.map { it.name })
        assertEquals("https://media.example/中文/index.m3u8?sign=a%2Fb%2Bc%3D&literal=a+b", episodes.first().url)
        assertEquals("https://page.example/player?id=42&sign=a%2Fb%2Bc%3D", episodes[2].url)
        val item = buildJsonObject {
            put("vod_id", 1)
            put("vod_play_url", episodes.joinToString("#") { "${it.name}${'$'}${it.url}" })
            put("vod_play_from", "原始地址")
        }
        var requests = 0
        val core = CoreService("cache", "source", MemoryStorage().apply { files["source"] = config },
            configureClient(HttpClient(testEngine { request ->
                requests++
                assertEquals("a.test", request.url.host)
                respond("""{"code":1,"list":[$item]}""")
            })))
        try {
            assertEquals(expected.playSources, core.fetch("🎬一", 1).playSources)
            assertEquals(1, requests)
        } finally { core.close() }
    }

    @Test fun cancellingSearchPropagatesWithoutPublishingResults() = runTest {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        var updates = 0
        val core = CoreService("cache", "source", MemoryStorage().apply { files["source"] = config },
            configureClient(HttpClient(testEngine {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled = true }
            })))
        try {
            val request = async { core.search("test") { updates++ } }
            started.await()
            request.cancelAndJoin()
            assertTrue(cancelled)
            assertTrue(request.isCancelled)
            assertEquals(0, updates)
        } finally { core.close() }
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
    }

    @Test fun filteringAndUpstreamIntegerValidation() {
        assertEquals(listOf("🎬一"), parseConfig(config).apiSite.values.map { it.name })
        fun number(raw: String) = apiInt(wireJson.parseToJsonElement(raw), "test")
        for (raw in listOf("1", "1.0", "\"1\"", "\"1.0\"")) assertEquals(1, number(raw))
        for (raw in listOf("1.9", "true", "null", "\"abc\"", "2147483648")) assertFails { number(raw) }
        for (raw in listOf("""{"list":[]}""", """{"code":null,"list":[]}""", """{"code":1,"list":[null]}""")) assertFails { parseVodList(raw) }
    }

    @Test fun upstreamIntegersMustBeExactEvenBeyondFloatingPointPrecision() {
        val cases = wireJson.parseToJsonElement(contractFixtures.getValue("api-integers.sample.json")).jsonObject
        for (entry in cases.getValue("valid").jsonArray) {
            val item = entry.jsonObject
            assertEquals(item.getValue("expected").jsonPrimitive.int, apiInt(item.getValue("value"), "value"))
        }
        for (value in cases.getValue("invalid").jsonArray) {
            assertFails { apiInt(value, "value") }
            assertFails { parseVodList("""{"code":$value,"list":[]}""") }
            assertFails { wireJson.decodeFromString<SearchPage>("""{"code":1,"pagecount":$value}""").pageCount() }
            assertFails { wireJson.decodeFromString<SearchPage>("""{"code":1,"list":[{"vod_id":$value}]}""").results("🎬站点") }
        }
    }

    @Test fun parsingPreservesEpisodeNamesAndSignedUrls() {
        assertEquals("第一集", parsePlaybackSources(" 第一集 ${'$'}https://a.test/a?x=1+2#二${'$'}u${'$'}${'$'}${'$'}三${'$'}v", "").first().episodes.first().name)
        assertEquals("https://a.test/%E4%B8%AD%E6%96%87/%20?a=+", normalizeUrl("https://a.test/中文/%20?a=+"))
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
        val client = configureClient(HttpClient(testEngine { request ->
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
        val store = MemoryStorage().apply { files["cache/source-config-cache.json"] = """{"fetchedAtMillis":0,"config":$config}""" }
        val core = CoreService("cache", storage = store, client = configureClient(HttpClient(testEngine { error("offline") })), clock = { 99 * CoreService.DAY })
        try {
            assertEquals(1, core.loadConfig().apiSite.size)
            (1..30).forEach { core.addHistory("剧$it") }
            assertEquals((30 downTo 19).map { "剧$it" }, core.history())
        } finally { core.close() }
    }

    @Test fun remoteFallbackPersistsRawConfigAndFreshCacheSkipsNetwork() = runTest {
        val store = MemoryStorage()
        var requests = 0
        val core = CoreService("cache", storage = store, client = configureClient(HttpClient(testEngine {
            requests++
            if (it.url.host == "cdn.test") error("CDN unavailable")
            respond(config)
        })), clock = { 10_000 }, configUrls = listOf("https://cdn.test" to 10_000L, "https://raw.test" to 5000L))
        try {
            assertEquals(1, core.loadConfig().apiSite.size)
            assertEquals(1, core.loadConfig().apiSite.size)
            assertEquals(2, requests)
            assertTrue(store.files.getValue("cache/source-config-cache.json").contains("disabled"))
        } finally { core.close() }
    }

    @Test fun coverFailuresRetryAndSuccessfulUrlsExpireAfterSevenDays() = runTest {
        val store = MemoryStorage().apply { files["source"] = config }
        var requests = 0
        var clock = 100L
        val core = CoreService("cache", "source", store, configureClient(HttpClient(testEngine {
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
        val core = CoreService("cache", "source", store, configureClient(HttpClient(testEngine {
            respond(if (it.url.host == "bad.test") """{"code":1,"list":[null]}""" else """{"code":1,"list":[{"vod_id":1}]}""")
        })))
        try { assertEquals("🎬好", core.search("test").works.single().sources.single().site) }
        finally { core.close() }
    }

    @Test fun historyUsesPersistentDirectoryIndependentlyOfCaches() = runTest {
        val store = MemoryStorage()
        val core = CoreService("cache", storage = store, historyDirectory = "data")
        try {
            core.addHistory("逐玉🎬")
            assertTrue("data/search-history.json" in store.files)
            assertFalse("cache/search-history.json" in store.files)
            assertEquals(listOf("逐玉🎬"), core.history())
        } finally { core.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun networkDeadlineIsAnOperationFailureAndRedirectsShareTheBudget() = runTest {
        val client = configureClient(HttpClient(testEngine {
            delay(6_000)
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://next.test/path"))
        }))
        try {
            assertFailsWith<IllegalStateException> { Network(client).get("https://first.test", 10_000) }
            assertEquals(10_000, testScheduler.currentTime)
        } finally { client.close() }
    }

    @Test fun responseLimitAndCancellation() = runTest {
        val exact = configureClient(HttpClient(testEngine { respond("x".repeat(MAX_RESPONSE_SIZE)) }))
        try { assertEquals(MAX_RESPONSE_SIZE, Network(exact).get("https://a.test").length) }
        finally { exact.close() }
        val client = configureClient(HttpClient(testEngine { respond("x".repeat(MAX_RESPONSE_SIZE + 1)) }))
        try { assertFailsWith<IllegalStateException> { Network(client).get("https://a.test") } }
        finally { client.close() }
        val cancelled = configureClient(HttpClient(testEngine { throw CancellationException("cancelled") }))
        try { assertFailsWith<CancellationException> { Network(cancelled).get("https://a.test") } }
        finally { cancelled.close() }
    }
}
