package art.anjing.tigertv

import android.util.Log
import art.anjing.tigertv.core.*
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import art.anjing.tigertv.data.Result
import art.anjing.tigertv.data.TigerTVRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TigerTVRepositoryTest {
    @Test fun sharedCoreAdapterPreservesResultsAndErrors() = runBlocking {
        val store = object : Storage {
            override fun read(path: String): String? = if (path == "source")
                """{"api_site":{"a":{"name":"🎬一","api":"https://a.test"}}}""" else null
            override fun writeAtomic(path: String, text: String) {}
        }
        val core = CoreService("cache", "source", store, configureClient(HttpClient(MockEngine {
            respond("""{"code":1,"list":[{"vod_id":"42","vod_name":"逐玉"}]}""")
        })))
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        try {
            val repository = TigerTVRepository(core)
            val result = repository.search("逐玉") as Result.Success
            assertEquals(42, result.data.works.single().sources.single().vodId)
            assertTrue(repository.fetch("不存在", 1) is Result.Error)
        } finally {
            unmockkStatic(Log::class)
            core.close()
        }
    }
    @Test fun pendingWritesAreOrderedAndVisibleToRepositoryReads() = runBlocking {
        val files = java.util.concurrent.ConcurrentHashMap<String, String>()
        val storage = object : Storage {
            override fun read(path: String) = files[path]
            override fun writeAtomic(path: String, text: String) { files[path] = text }
        }
        val core = CoreService("cache", storage = storage)
        try {
            val repository = TigerTVRepository(core)
            val source = art.anjing.tigertv.domain.SearchResult("来源", 42, "逐玉")
            repository.recordPlayback("work", source.site, "0", "第一集", source)
            repository.recordPlaybackPosition("work", source.site, "0", "第一集", 123_000, 25)
            assertEquals(123_000L, repository.recentPlayback().single().positions.getValue("0").getValue("第一集"))

            repository.addHistory("旧")
            repository.clearHistory()
            repository.addHistory("新")
            assertEquals(listOf("新"), repository.loadHistory())
        } finally {
            core.close()
        }
    }
}
