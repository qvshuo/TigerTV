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
            core.recordPlayback("one", "A", "0", "首集", source, source, "https://a.test/display-poster")
            core.recordPlaybackPosition("one", "A", "0", "首集", 800, 20)
            core.recordPlayback("two", "A", "0", "首集", source.copy(vodId = 43))
            core.recordPlaybackPosition("one", "A", "0", "首集", 900, 25)
            assertEquals(listOf("two", "one"), core.recentPlayback().map { it.workId })
            val otherSource = source.copy(site = "B", vodId = 7)
            core.recordPlayback("one", "B", "2", "第二集", otherSource)
            core.recordPlaybackPosition("one", "A", "0", "首集", 1_000, 30)
            val resumed = core.recentPlayback().first()
            assertEquals(otherSource, resumed.source)
            assertEquals(source, resumed.cardSource)
            assertEquals("https://a.test/display-poster", resumed.cardPoster)
            assertEquals(0L, resumed.positionMillis)
            assertNull(resumed.progressPercent)
            assertEquals(listOf("one", "two"), core.recentPlayback().map { it.workId })
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

    @Test fun cancellingSearchAndQuanxPropagatesWithoutPublishingResults() = runTest {
        for (quanx in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            var cancelled = false
            var updates = 0
            val core = CoreService("cache", "source", MemoryStorage().apply { files["source"] = config },
                configureClient(HttpClient(testEngine {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled = true }
                })))
            try {
                val request = async {
                    if (quanx) core.collectQuanxDomains("test") else core.search("test") { updates++ }
                }
                started.await()
                request.cancelAndJoin()
                assertTrue(cancelled)
                assertTrue(request.isCancelled)
                assertEquals(0, updates)
            } finally { core.close() }
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
        for (fixture in listOf("quanx-html-absolute.sample.html", "quanx-html-relative.sample.html", "quanx-html-escaped.sample.html", "quanx-urlencoded.sample.html")) {
            assertTrue(extractM3u8("https://page.example/player", contractFixtures.getValue(fixture)).first().startsWith("https://"))
        }
        assertEquals("https://cdn.example.com/playlist/index.m3u8?sign=a%2Fb%2Bc%3D", extractM3u8("https://page.example/player", contractFixtures.getValue("quanx-urlencoded.sample.html")).first())
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
        assertEquals("https://a.test/path/a.m3u8?sign=a+b", extractM3u8("https://a.test/path/page", "'a.m3u8?sign=a+b'").single())
        assertEquals("https://a.test/%E4%B8%AD%E6%96%87/%20?a=+", normalizeUrl("https://a.test/中文/%20?a=+"))
    }

    @Test fun signedPlaybackQueriesSurviveHtmlAndWrappedUrls() = runTest {
        val signed = "https://cdn.test/video.m3u8?sign=a%2Fb%2Bc%3D&part=1"
        assertEquals(signed, extractM3u8("https://page.test/", "<video src=\"${signed.replace("&", "&amp;")}\">").single())
        val wrapped = "https%3A%2F%2Fcdn.test%2Fvideo.m3u8%3Fsign%3Da%252Fb%252Bc%253D%26part%3D1"
        assertEquals(signed, extractM3u8("https://page.test/", "'$wrapped'").single())
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
        val store = MemoryStorage().apply { files["cache/tigertv-cli-config-cache.json"] = """{"fetchedAtMillis":0,"config":$config}""" }
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
            assertTrue(store.files.getValue("cache/tigertv-cli-config-cache.json").contains("disabled"))
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

    @Test fun quanxTraversesAllVariantsAndCollectsKeyAndMediaDomains() = runTest {
        val store = MemoryStorage().apply { files["source"] = config }
        val requested = mutableListOf<String>()
        val core = CoreService("cache", "source", store, configureClient(HttpClient(testEngine { request ->
            requested += request.url.toString()
            respond(when {
                request.url.parameters["ac"] == "list" -> """{"code":1,"list":[{"vod_id":1}]}"""
                request.url.parameters["ac"] == "detail" -> """{"code":1,"list":[{"vod_id":1,"vod_play_url":"一${'$'}https://media.test/master.m3u8"}]}"""
                request.url.encodedPath == "/master.m3u8" -> "#EXTM3U\n" + (1..4).joinToString("\n") { "#EXT-X-STREAM-INF:BANDWIDTH=$it\nchild$it.m3u8" }
                else -> "#EXTM3U\n#EXT-X-KEY:URI=\"https://key.test/key\"\n#EXT-X-MEDIA:URI=\"https://audio.test/a.m3u8\"\nhttps://segments.test/1.ts"
            })
        })))
        try {
            val result = core.collectQuanxDomains("test")
            assertTrue(result.m3u8.containsAll(listOf("key.test", "audio.test", "segments.test")))
            assertTrue(requested.any { "child4" in it })
        } finally { core.close() }
    }

    @Test fun quanxReadsRawManifestWithoutPlaylistExtension() = runTest {
        val core = CoreService("cache", "source", MemoryStorage().apply { files["source"] = config },
            configureClient(HttpClient(testEngine { request ->
                respond(when {
                    request.url.parameters["ac"] == "list" -> """{"code":1,"list":[{"vod_id":1}]}"""
                    request.url.parameters["ac"] == "detail" -> """{"code":1,"list":[{"vod_id":1,"vod_play_url":"一${'$'}https://media.test/raw"}]}"""
                    else -> contractFixtures.getValue("quanx-extm3u-content.sample.txt")
                })
            })))
        try {
            val domains = core.collectQuanxDomains("test")
            assertTrue(domains.incompleteSites.isEmpty())
            assertTrue("cdn.test" in domains.m3u8)
        } finally { core.close() }
    }

    @Test fun quanxFetchesSharedPlaylistsOnce() = runTest {
        val store = MemoryStorage().apply { files["source"] = config }
        val requested = mutableListOf<String>()
        val core = CoreService("cache", "source", store, configureClient(HttpClient(testEngine { request ->
            requested += request.url.encodedPath
            respond(when {
                request.url.parameters["ac"] == "list" -> """{"code":1,"list":[{"vod_id":1}]}"""
                request.url.parameters["ac"] == "detail" -> """{"code":1,"list":[{"vod_id":1,"vod_play_url":"一${'$'}https://media.test/root.m3u8"}]}"""
                request.url.encodedPath == "/root.m3u8" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nbranch.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2\nshared.m3u8"
                request.url.encodedPath == "/branch.m3u8" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nshared.m3u8"
                request.url.encodedPath == "/shared.m3u8" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nleaf.m3u8"
                else -> "#EXTM3U\nhttps://segments.test/1.ts"
            })
        })))
        try {
            val result = core.collectQuanxDomains("test")
            assertTrue("segments.test" in result.m3u8)
            assertEquals(1, requested.count { it == "/shared.m3u8" })
            assertEquals(1, requested.count { it == "/leaf.m3u8" })
        } finally { core.close() }
    }

    @Test fun quanxFollowsFirstEpisodePerLineAndMediaRedirects() = runTest {
        val store = MemoryStorage().apply { files["source"] = config }
        val requested = mutableListOf<String>()
        val core = CoreService("cache", "source", store, configureClient(HttpClient(testEngine { request ->
            requested += "${request.method.value} ${request.url}"
            when {
                request.url.parameters["ac"] == "list" -> respond("""{"code":1,"list":[{"vod_id":1},{"vod_id":2}]}""")
                request.url.parameters["ids"] == "1" -> respond("""{"code":1,"list":[{"vod_id":1,"vod_play_url":"一${'$'}https://entry.test/start#二${'$'}https://skip.test/two.m3u8${'$'}${'$'}${'$'}一${'$'}https://backup.test/one.m3u8","vod_down_url":"一${'$'}https://download.test/file.zip#二${'$'}https://skip.test/two.zip"}]}""")
                request.url.parameters["ids"] == "2" -> respond("""{"code":1,"list":[{"vod_id":2,"vod_play_url":"一${'$'}https://file.test/video.mp4"}]}""")
                request.url.host == "entry.test" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://page.test/player"))
                request.url.host == "page.test" -> respond("""<video src="https://final.test/path/master.m3u8?sign=a%2Fb%2Bc%3D&amp;literal=a+b"><source src="https://html-mp4.test/v.mp4"></video>""")
                request.url.host == "backup.test" -> respond("#EXTM3U\nhttps://backup-segments.test/1.ts")
                request.url.encodedPath.endsWith("master.m3u8") -> respond("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=1\nvideo.m3u8")
                request.url.encodedPath.endsWith("audio.m3u8") -> respond("#EXTM3U\nhttps://audio-segments.test/a.aac")
                request.url.encodedPath.endsWith("video.m3u8") -> respond(buildString {
                    appendLine("#EXTM3U")
                    appendLine("#EXT-X-MAP:URI=\"https://init.test/init.mp4\"")
                    appendLine("#EXT-X-KEY:URI=\"https://key.test/key\"")
                    appendLine("#EXT-X-I-FRAME-STREAM-INF:URI=\"iframe.m3u8\"")
                    appendLine("#EXT-X-RENDITION-REPORT:URI=\"report.m3u8\"")
                    appendLine("https://segments.test/1.ts")
                    repeat(1100) { appendLine("https://later-segments.test/$it.ts") }
                })
                request.url.encodedPath.endsWith("iframe.m3u8") || request.url.encodedPath.endsWith("report.m3u8") -> respond("#EXTM3U\nhttps://extra-segments.test/1.ts")
                request.url.host == "segments.test" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://redirected-segments.test/1.ts"))
                request.url.host == "redirected-segments.test" && request.headers[HttpHeaders.Range] != null ->
                    respond("", HttpStatusCode.RequestedRangeNotSatisfiable)
                request.url.host == "file.test" -> {
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals("bytes=0-0", request.headers[HttpHeaders.Range])
                    respond("", headers = headersOf(HttpHeaders.ContentType, "video/mp4"))
                }
                request.url.host in setOf("audio-segments.test", "init.test", "key.test", "extra-segments.test",
                    "redirected-segments.test", "backup-segments.test", "download.test", "html-mp4.test") -> respond("")
                else -> error("Unexpected request: ${request.url}")
            }
        })))
        try {
            val domains = core.collectQuanxDomains("test")
            assertTrue(domains.incompleteSites.isEmpty())
            assertTrue(domains.m3u8.containsAll(listOf("entry.test", "page.test", "final.test", "audio-segments.test", "init.test", "key.test", "extra-segments.test", "segments.test", "file.test",
                "backup.test", "backup-segments.test", "redirected-segments.test", "html-mp4.test", "download.test")))
            assertTrue(requested.any { "master.m3u8?sign=a%2Fb%2Bc%3D&literal=a+b" in it })
            assertTrue(requested.none { "skip.test" in it })
            assertFalse("skip.test" in domains.urls)
            assertTrue("later-segments.test" in domains.m3u8)
            assertTrue(requested.none { "later-segments.test" in it })
            assertTrue(requested.any { "https://backup-segments.test/1.ts" in it })
            assertTrue(requested.any { "https://final.test/path/audio.m3u8" in it })
        } finally { core.close() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun quanxCompletesSlowScansAndProbesDistinctHostsBeforeRepeats() = runTest {
        var inFlight = 0
        var peak = 0
        var keys = 0
        var keysBeforeOtherHost = -1
        val core = CoreService("cache", "source", MemoryStorage().apply { files["source"] = config },
            configureClient(HttpClient(testEngine { request ->
                when {
                    request.url.parameters["ac"] == "list" -> respond("""{"code":1,"list":[{"vod_id":1}]}""")
                    request.url.parameters["ids"] == "1" -> respond("""{"code":1,"list":[{"vod_id":1,"vod_play_url":"一${'$'}https://media.test/index.m3u8"}]}""")
                    request.url.host == "media.test" -> respond(buildString {
                        appendLine("#EXTM3U")
                        repeat(60) { appendLine("#EXT-X-KEY:URI=\"https://segments.test/$it.key\"") }
                        appendLine("https://other.test/a.ts")
                    })
                    request.url.host == "other.test" -> {
                        keysBeforeOtherHost = keys
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other-cdn.test/a.ts"))
                    }
                    else -> {
                        if (request.url.host == "segments.test") keys++
                        peak = maxOf(peak, ++inFlight)
                        try { delay(1000) } finally { inFlight-- }
                        respond("")
                    }
                }
            })))
        try {
            val domains = core.collectQuanxDomains("test")
            assertTrue(testScheduler.currentTime > 6000)
            assertEquals(60, keys)
            assertTrue(keysBeforeOtherHost in 0..5)
            assertEquals(6, peak)
            assertTrue("other-cdn.test" in domains.m3u8)
            assertTrue(domains.incompleteReasons.isEmpty())
        } finally { core.close() }
    }

    @Test fun quanxKeepsObservedRedirectHostWhenTheChildReturnsAnError() = runTest {
        val core = CoreService("cache", "source", MemoryStorage().apply { files["source"] = config },
            configureClient(HttpClient(testEngine { request ->
                when {
                    request.url.parameters["ac"] == "list" -> respond("""{"code":1,"list":[{"vod_id":1}]}""")
                    request.url.parameters["ids"] == "1" -> respond("""{"code":1,"list":[{"vod_id":1,"vod_play_url":"一${'$'}https://entry.test/index.m3u8"}]}""")
                    request.url.host == "entry.test" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://failed-cdn.test/rejected"))
                    else -> respond("", HttpStatusCode.Forbidden)
                }
            })))
        try {
            val report = core.collectQuanxDomains("test")
            assertTrue("failed-cdn.test" in report.m3u8)
            assertEquals(listOf("🎬一"), report.incompleteSites)
            assertEquals(listOf("HTTP 403"), report.incompleteReasons.values.single())
        } finally { core.close() }
    }

    @Test fun quanxRendersExactHostsAndIpRulesAndMergesACumulativeFile() {
        val scan = QuanxDomains(setOf("api.example", "[2001:0db8:0:0:0:0:0:1]"), setOf("API.EXAMPLE.", "127.0.0.1"),
            setOf("api.example", "2001:db8::1"), listOf("资源站"))
        val groups = scan.ruleGroups("""
            ; QX 关键词：以前
            host-suffix, previous.example, direct
            ; m3u8 域名
            host, segment.example, direct
            host, api.example, direct
            ip6-cidr, [2001:db8::0001]/128, direct
            ip-cidr, 10.0.0.0/8, direct
            host, unrelated.example, proxy
        """.trimIndent())
        assertEquals(listOf("资源站 API 域名", "播放/下载域名", "m3u8 域名", "历史规则"), groups.keys.toList())
        assertEquals(listOf(
            listOf("ip6-cidr, 2001:db8::1/128, direct", "host, api.example, direct"),
            listOf("ip-cidr, 127.0.0.1/32, direct"),
            listOf("host, segment.example, direct"),
            listOf("host, previous.example, direct"),
        ), groups.values.toList())
        val empty = QuanxDomains(emptySet(), emptySet(), emptySet())
        val exported = groups.values.joinToString("\n\n", postfix = "\n") { it.joinToString("\n") }
        assertEquals(groups, empty.ruleGroups(exported))
    }

    @Test fun quanxKeepsBudgetForOtherSitesAndStillProbesEarlierWorks() = runTest {
        val store = MemoryStorage().apply { files["source"] = config.replace("\"null\":null", "\"small\":{\"name\":\"🎬小站\",\"api\":\"https://small.test/api\"}") }
        var requests = 0
        var details = 0
        var errors = 0
        val core = CoreService("cache", "source", store, configureClient(HttpClient(testEngine { request ->
            requests++
            if (request.url.parameters["ac"] == "list" && request.url.host == "a.test") {
                return@testEngine respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, request.url.toString().replace("a.test", "redirected-api.test")))
            }
            respond(when {
                request.url.host == "small.test" && request.url.parameters["ac"] == "list" ->
                    """{"code":1,"list":[{"vod_id":1}]}"""
                request.url.host == "small.test" ->
                    """{"code":1,"list":[{"vod_id":1,"vod_play_url":"一${'$'}https://small-media.test/first.m3u8"}]}"""
                request.url.parameters["ac"] == "list" -> """{"code":1,"list":[${(1..1500).joinToString(",") { """{"vod_id":$it}""" }}]}"""
                request.url.parameters["ids"] != null -> {
                    details++
                    """{"code":1,"list":[{"vod_id":${request.url.parameters["ids"]},"vod_play_url":"一${'$'}https://media.test/first.m3u8"}]}"""
                }
                else -> "#EXTM3U\n#EXT-X-KEY:URI=\"data:text/plain;base64,AAAA\"\nhttps://segments.test/1.ts"
            })
        })), logger = Logger { _, _, _ -> errors++ })
        try {
            val result = core.collectQuanxDomains("test")
            assertEquals(505, requests)
            assertEquals(498, details)
            assertEquals(1, errors)
            assertEquals(listOf("🎬一"), result.incompleteSites)
            assertTrue("redirected-api.test" in result.api)
            assertTrue("segments.test" in result.m3u8)
            assertTrue("small-media.test" in result.urls)
            assertFalse(result.m3u8.any { "data:" in it })
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
