package art.anjing.tigertv

import art.anjing.tigertv.core.*
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
        try {
            val repository = TigerTVRepository(core)
            val result = repository.search("逐玉") as Result.Success
            assertEquals(42, result.data.results.single().vodId)
            assertTrue(repository.fetch("不存在", 1) is Result.Error)
        } finally { core.close() }
    }
}
