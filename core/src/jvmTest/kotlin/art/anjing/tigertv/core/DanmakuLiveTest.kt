package art.anjing.tigertv.core

import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Opt-in integration test against the authorized development server. */
class DanmakuLiveTest {
    @Test fun deployedServerReturnsRealEpisodesAndComments() = runBlocking {
        val base = System.getenv("TIGERTV_DANMU_URL") ?: return@runBlocking
        val http = configureClient(HttpClient())
        try {
            val client = DanmakuClient(Network(http), base)
            val titles = client.search("嫁金钗")
            assertTrue(titles.isNotEmpty())
            val title = titles.firstOrNull { it.source == "youku" } ?: titles.first()
            val episodes = client.episodes(title.id)
            assertTrue(episodes.size >= 2)
            val first = episodes.first { it.number == "1" }
            val track = client.comments(first.id)
            println("danmu live: titles=${titles.size}, episodes=${episodes.size}, comments=${track.comments.size}, durationMs=${track.referenceDurationMs}")
            assertTrue(track.comments.isNotEmpty())
            assertTrue((track.referenceDurationMs ?: 0) > 0)
        } finally { http.close() }
    }
}
