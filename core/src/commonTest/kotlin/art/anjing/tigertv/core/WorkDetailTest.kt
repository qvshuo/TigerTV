package art.anjing.tigertv.core

import art.anjing.tigertv.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class WorkDetailTest {
    @Test fun searchPublishesFastSitesBeforeSlowSitesAndKeepsConfiguredOrder() = runTest {
        val store = MemoryStorage().apply { files["source"] = """{"api_site":{"a":{"name":"🎬慢","api":"https://slow.test"},"b":{"name":"🎬快","api":"https://fast.test"}}}""" }
        val updates = mutableListOf<SearchResponse>()
        val core = CoreService("cache", "source", store, configureClient(HttpClient(MockEngine {
            if (it.url.host == "slow.test") delay(1000)
            respond("""{"code":1,"list":[{"vod_id":1,"vod_name":"逐玉","vod_year":"2026"}]}""")
        })))
        try {
            val result = core.search("逐玉") { updates += it }
            assertEquals(listOf("🎬快"), updates.first().works.single().sources.map { it.site })
            assertEquals(listOf("🎬慢", "🎬快"), result.works.single().sources.map { it.site })
            core.recordPlayback(result.works.single().id, "🎬快", "0", "第二集")
            val cached = core.search("逐玉").works.single()
            assertEquals(PlaybackBookmark("🎬快", "0", "第二集"), cached.playback)
            assertEquals("🎬快", cached.defaultSource.site)
        } finally { core.close() }
    }

    @Test fun searchCacheEvictsOldQueriesAndExpiredEntries() = runTest {
        val store = MemoryStorage().apply { files["source"] = """{"api_site":{"a":{"name":"🎬一","api":"https://a.test"}}}""" }
        var requests = 0
        var now = 0L
        val core = CoreService("cache", "source", store, configureClient(HttpClient(MockEngine {
            requests++
            respond("""{"code":1,"list":[{"vod_id":1,"vod_name":"剧"}]}""")
        })), clock = { now })
        try {
            repeat(6) { core.search(it.toString()) }
            core.search("5")
            assertEquals(6, requests)
            core.search("0")
            assertEquals(7, requests)
            now = 600_000
            core.search("0")
            assertEquals(8, requests)
        } finally { core.close() }
    }

    @Test fun groupedContractFixtures() {
        val saved = wireJson.decodeFromString<SearchResponse>(contractFixtures.getValue("search-playback.sample.json"))
        assertEquals(PlaybackBookmark("🎬二", "0", "第02集"), saved.works.single().playback)
        val search = wireJson.decodeFromString<SearchResponse>(contractFixtures.getValue("search.sample.json"))
        assertEquals(listOf(1, 99), search.works.single().sources.map { it.vodId })
        val detail = wireJson.decodeFromString<FetchResponse>(contractFixtures.getValue("fetch.sample.json"))
        assertEquals(listOf("0", "2"), detail.playSources.map { it.id })
        assertEquals(listOf(2, 1), detail.playSources.map { it.episodes.size })
    }

    @Test fun schemaRequiresGroupedResultsAndLines() {
        assertFails { wireJson.decodeFromString<SearchResponse>("""{"keyword":"x","results":[]}""") }
        assertFails { wireJson.decodeFromString<FetchResponse>("""{"vod_id":1,"site":"A","vod_play_url":[],"vod_down_url":[]}""") }
        val json = wireJson.encodeToString(SearchResponse("x", emptyList()))
        assertEquals("""{"query":"x","works":[]}""", json)
    }

    @Test fun mismatchedDetailIdIsRejected() = runTest {
        val store = MemoryStorage().apply { files["source"] = """{"api_site":{"a":{"name":"🎬一","api":"https://a.test"}}}""" }
        val core = CoreService("cache", "source", store, configureClient(HttpClient(MockEngine {
            respond("""{"code":1,"list":[{"vod_id":999}]}""")
        })))
        try { assertFails { core.fetch("🎬一", 42) } }
        finally { core.close() }
    }

    @Test fun groupingUsesOnlyTitleAndYear() {
        val a = SearchResult("A", 1, "逐 玉", vodYear = "2026", typeName = "国产剧")
        val b = a.copy(site = "B", vodId = 99, vodName = "逐玉", typeName = "电视剧", vodPic = "https://pic.test/a.jpg")
        val works = aggregateWorks(listOf(a, b, a, a.copy(vodId = 2, vodYear = "2020"),
            a.copy(vodId = 3, typeName = "电影"), a.copy(vodId = 4, vodName = "逐玉第二季"),
            a.copy(vodId = 5, vodYear = "")))
        assertEquals(4, works.size)
        assertEquals(listOf(1, 99, 3), works.first().sources.map { it.vodId })
        assertEquals(b, works.first().defaultSource)
        assertEquals(works, wireJson.decodeFromString<SearchResponse>(wireJson.encodeToString(SearchResponse("逐玉", aggregateWorks(listOf(a, b, a,
            a.copy(vodId = 2, vodYear = "2020"), a.copy(vodId = 3, typeName = "电影"),
            a.copy(vodId = 4, vodName = "逐玉第二季"), a.copy(vodId = 5, vodYear = "")))))).works)
        assertEquals(2, aggregateWorks(listOf(a.copy(vodName = ""), b.copy(vodName = ""))).size)
    }

    @Test fun sourcePriorityIsLastPlayedThenFirstPosterThenConfigurationOrder() {
        val first = SearchResult("First", 1, "同一作品")
        val second = first.copy(site = "Second", vodId = 2, vodPic = "https://second.test/poster.jpg")
        val third = second.copy(site = "Third", vodId = 3)
        val work = aggregateWorks(listOf(first, second, third)).single()
        assertEquals(second, work.defaultSource)
        assertEquals(first, work.copy(recentPlayedSite = first.site).defaultSource)
        assertEquals(third, work.copy(lastPlayedSite = third.site, recentPlayedSite = first.site).defaultSource)
        val watchedWithoutPoster = work.copy(lastPlayedSite = first.site, recentPlayedSite = third.site)
        assertEquals(first, watchedWithoutPoster.defaultSource)
        assertEquals(listOf(third, second), watchedWithoutPoster.orderedSources.filter { it.vodPic.isNotBlank() })
        assertEquals(first, work.copy(lastPlayedSite = first.site).defaultSource)
        assertEquals(third, work.copy(lastPlayedSite = third.site).defaultSource)
        assertEquals(second, work.copy(lastPlayedSite = "removed-site-99").defaultSource)
        assertEquals(first, work.copy(sources = listOf(first, second.copy(vodPic = " "))).defaultSource)
    }

    @Test fun lastPlayedSitePersistsOneValueAndRefreshesCachedSearch() = runTest {
        val store = MemoryStorage().apply {
            files["source"] = """{"api_site":{"a":{"name":"🎬A","api":"https://a.test"},"b":{"name":"🎬B","api":"https://b.test"}}}"""
        }
        var requests = 0
        fun client() = configureClient(HttpClient(MockEngine { request ->
            requests++
            val id = if (request.url.host == "a.test") 1 else 99
            respond("""{"code":1,"list":[{"vod_id":$id,"vod_name":"${request.url.parameters["wd"]}","vod_year":"2026"}]}""")
        }))
        val core = CoreService("cache", "source", store, client(), historyDirectory = "persistent")
        val work = core.search("同作").works.single()
        try {
            assertEquals("🎬A", work.defaultSource.site)
            core.recordPlayback(work.id, "🎬B", "0", "第一集")
            assertEquals("🎬B", core.search("同作").works.single().defaultSource.site)
            assertEquals(2, requests) // Same cached network data, current preference.
            val unwatched = core.search("其他关键词").works.single()
            assertNull(unwatched.lastPlayedSite)
            assertEquals("🎬B", unwatched.defaultSource.site)
            core.recordPlayback(work.id, "🎬A", "0", "第二集")
            val saved = wireJson.parseToJsonElement(store.files.getValue("persistent/last-played-sources.json")).jsonObject
            assertEquals("🎬A", saved.text("recentSite"))
            assertEquals(1, saved["byWork"]!!.jsonObject.size)
            assertEquals("🎬A", saved["byWork"]!!.jsonObject[work.id]!!.jsonObject.text("site"))
        } finally { core.close() }
        store.files.keys.filter { it.startsWith("cache/") }.forEach { store.files.remove(it) }
        val restarted = CoreService("cache", "source", store, client(), historyDirectory = "persistent")
        try { assertEquals("🎬A", restarted.search("同作").works.single().lastPlayedSite) }
        finally { restarted.close() }
    }

    @Test fun lineNamesStayAlignedAcrossEmptyAndMalformedGroups() {
        val raw = "第1集${'$'}https://a.test/1?token=a+b#第2集${'$'}https://a.test/2${'$'}${'$'}${'$'}${'$'}${'$'}${'$'}损坏#空${'$'}#正片${'$'}https://b.test/movie"
        val sources = parsePlaybackSources(raw, "主线${'$'}${'$'}${'$'}空线${'$'}${'$'}${'$'}备用")
        assertEquals(listOf("0", "2"), sources.map { it.id })
        assertEquals(listOf("主线", "备用"), sources.map { it.name })
        assertEquals(2, sources[0].episodes.size)
        assertEquals("https://a.test/1?token=a+b", sources[0].episodes.first().url)
        assertEquals("正片", sources[1].episodes.single().name)
        assertEquals("线路 1", parsePlaybackSources("一${'$'}u", "").single().name)
    }

    @Test fun fullSearchFollowsPaginationAndDetailRetainsMetadata() = runTest {
        val store = MemoryStorage().apply { files["source"] = """{"api_site":{"a":{"name":"🎬一","api":"https://a.test/api?ac=list"}}}""" }
        val requests = mutableListOf<String>()
        val core = CoreService("cache", "source", store, configureClient(HttpClient(MockEngine { request ->
            requests += request.url.toString()
            assertEquals(listOf("detail"), request.url.parameters.getAll("ac"))
            if (request.url.parameters["ids"] != null) {
                respond("""{"code":"1","list":[{"vod_id":42,"vod_name":"逐玉","vod_year":"2026","vod_actor":"主演","vod_content":"<p>故事&amp;人物</p>","vod_play_from":"主线${'$'}${'$'}${'$'}备用","vod_play_url":"一${'$'}https://a.test/1${'$'}${'$'}${'$'}一${'$'}https://b.test/1"}]}""")
            } else {
                val page = request.url.parameters["pg"]!!.toInt()
                assertEquals("100", request.url.parameters["pagesize"])
                assertEquals("逐玉", request.url.parameters["wd"])
                respond("""{"code":1,"pagecount":"2","list":[{"vod_id":$page,"vod_name":"逐玉","vod_year":2026,"type_name":"国产剧"}]}""")
            }
        })))
        try {
            val response = core.search("逐玉")
            assertEquals(2, response.works.sumOf { it.sources.size })
            assertEquals(2, response.works.single().sources.size)
            val detail = core.fetch("🎬一", 42)
            assertEquals("2026", detail.vodYear)
            assertEquals("主演", detail.vodActor)
            assertEquals("故事&人物", detail.vodContent)
            assertEquals(listOf("主线", "备用"), detail.playSources.map { it.name })
            assertEquals(2, detail.playSources.sumOf { it.episodes.size })
            val json = wireJson.encodeToString(detail)
            assertFalse("vod_play_url" in json)
            assertFalse("vod_down_url" in json)
            assertEquals(3, requests.size)
        } finally { core.close() }
    }

    @Test fun errorsAndProxyQueryParametersRespectProvideContract() {
        assertFails { parseVodList("""{"code":1001,"msg":"失败","list":[]}""") }
        assertEquals(1, parseVodList("""{"code":"1","list":[{"vod_id":1}]}""").size)
        val url = apiUrl("https://proxy.test/?url=https%3A%2F%2Fa.test%2Fapi%3Fac%3Dlist&token=keep", mapOf("ac" to "detail", "ids" to "42"))
        val outer = io.ktor.http.Url(url)
        assertEquals("keep", outer.parameters["token"])
        val inner = io.ktor.http.Url(outer.parameters["url"]!!)
        assertEquals(listOf("detail"), inner.parameters.getAll("ac"))
        assertEquals("42", inner.parameters["ids"])
    }
}
