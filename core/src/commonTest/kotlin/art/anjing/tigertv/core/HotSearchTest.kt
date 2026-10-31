package art.anjing.tigertv.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HotSearchTest {
    @Test fun hotTitlesAreBoundedCachedAndDoNotRequireSourceConfig() = runTest {
        val store = MemoryStorage()
        var now = 0L
        var requests = 0
        var unavailable = false
        val core = CoreService("cache", storage = store, client = configureClient(HttpClient(MockEngine { request ->
            requests++
            assertEquals("m.douban.com", request.url.host)
            assertTrue(request.url.encodedPath.contains("tv_domestic/items"))
            assertEquals(USER_AGENT, request.headers["User-Agent"])
            assertEquals("https://m.douban.com/tv/chinese", request.headers["Referer"])
            if (unavailable) respond("", HttpStatusCode.ServiceUnavailable)
            else respond("""{"subject_collection_items":[{"title":" 热门甲 "},{"title":"热门甲"},{"title":""},${(1..15).joinToString(",") { """{"title":"剧$it"}""" }}]}""")
        })), clock = { now })
        try {
            val titles = core.hotSearches("chinese")
            assertEquals(12, titles.size)
            assertEquals(listOf("热门甲", "剧1"), titles.take(2))
            assertEquals(titles, core.hotSearches("chinese"))
            assertEquals(1, requests)
            unavailable = true
            now = 6 * 60 * 60 * 1000L
            assertEquals(titles, core.hotSearches("chinese"))
            now = CoreService.DAY
            assertTrue(core.hotSearches("chinese").isEmpty())
        } finally { core.close() }
    }

    @Test fun categoriesHaveSeparateCachesAndRejectUnknownIds() = runTest {
        val store = MemoryStorage()
        var requests = 0
        val core = CoreService("cache", storage = store, client = configureClient(HttpClient(MockEngine {
            requests++
            respond("""{"subject_collection_items":[{"title":"${it.url.encodedPath}"}]}""")
        })))
        try {
            assertEquals(listOf("国产剧", "综艺", "英美剧", "韩剧", "日剧", "动漫"), core.hotSearchCategories().map { it.name })
            val chinese = core.hotSearches("chinese")
            val korean = core.hotSearches("korean")
            assertNotEquals(chinese, korean)
            assertEquals(chinese, core.hotSearches("chinese"))
            assertEquals(2, requests)
            assertFailsWith<IllegalStateException> { core.hotSearches("../unknown") }
            assertEquals(2, requests)
        } finally { core.close() }
    }

    @Test fun warmingLoadsFirstCategoryBeforeOthersAndSharesInFlightRequests() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val backgroundGate = CompletableDeferred<Unit>()
        val requests = mutableListOf<String>()
        val engine = MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                val path = it.url.encodedPath
                requests += path
                if (path.contains("tv_domestic")) firstGate.await() else backgroundGate.await()
                respond("""{"subject_collection_items":[{"title":"$path"}]}""")
            }
        })
        val core = CoreService("cache", storage = MemoryStorage(), client = configureClient(HttpClient(engine)))
        try {
            val warming = async { core.warmHotSearches("chinese") }
            runCurrent()
            val visible = async { core.hotSearches("chinese") }
            runCurrent()
            assertEquals(1, requests.size)
            firstGate.complete(Unit)
            runCurrent()
            assertTrue(visible.await().isNotEmpty())
            assertEquals(6, requests.size)
            val selected = async { core.hotSearches("korean") }
            runCurrent()
            assertEquals(6, requests.size)
            backgroundGate.complete(Unit)
            warming.await()
            assertTrue(selected.await().isNotEmpty())
            core.hotSearches("japanese")
            assertEquals(6, requests.size)
        } finally { core.close() }
    }

    @Test fun cancellingWarmupKeepsCompletedCategoryAndCancelsBackgroundRequests() = runTest {
        val store = MemoryStorage()
        var started = 0
        var cancelled = 0
        val engine = MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                if (it.url.encodedPath.contains("tv_domestic")) respond("""{"subject_collection_items":[{"title":"首类"}]}""")
                else {
                    started++
                    try { awaitCancellation() } finally { cancelled++ }
                }
            }
        })
        val core = CoreService("cache", storage = store, client = configureClient(HttpClient(engine)))
        try {
            val job = launch { core.warmHotSearches("chinese") }
            runCurrent()
            assertEquals(5, started)
            job.cancelAndJoin()
            assertEquals(5, cancelled)
            assertTrue(store.files.containsKey("cache/hot-searches-chinese.json"))
            assertFalse(store.files.containsKey("cache/hot-searches-korean.json"))
        } finally { core.close() }
    }

    @Test fun failedHotSearchIsNotCachedAndCancellationPropagates() = runTest {
        val store = MemoryStorage()
        val core = CoreService("cache", storage = store, client = configureClient(HttpClient(MockEngine {
            awaitCancellation()
        })))
        try {
            val job = async { core.hotSearches("chinese") }
            runCurrent()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertFalse(store.files.containsKey("cache/hot-searches-chinese.json"))
        } finally { core.close() }
    }

    @Test fun allFailedSitesAreAnErrorButSuccessfulEmptySitesAreNot() = runTest {
        val store = MemoryStorage().apply { files["source"] = """{"api_site":{"a":{"name":"🎬一","api":"https://a.test"}}}""" }
        var failed = true
        val core = CoreService("cache", "source", store, configureClient(HttpClient(MockEngine {
            if (failed) respond("", HttpStatusCode.ServiceUnavailable)
            else respond("""{"code":1,"list":[]}""")
        })))
        try {
            assertFailsWith<IllegalStateException> { core.search("剧") }
            failed = false
            assertTrue(core.search("剧").works.isEmpty())
        } finally { core.close() }
    }
}
