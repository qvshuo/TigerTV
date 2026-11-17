package art.anjing.tigertv.ui.player

import art.anjing.tigertv.core.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DanmakuStateTest {
    @Test fun automaticallySelectsCurrentEpisodeAndRespectsDisabledState() = runBlocking {
        val http = configureClient(HttpClient(MockEngine { request ->
            respond(when {
                request.url.encodedPath.contains("search") -> """{"success":true,"animes":[{"animeId":3,"animeTitle":"示例(2026)","source":"test"}]}"""
                request.url.encodedPath.contains("bangumi") -> """{"success":true,"bangumi":{"episodes":[{"episodeId":11,"episodeNumber":"1"},{"episodeId":12,"episodeNumber":"2"}]}}"""
                else -> """{"comments":[{"p":"1,1,16777215","m":"接口格式"}]}"""
            })
        }))
        try {
            val state = DanmakuState(this, { DanmakuClient(Network(http), it) }, false, 2)
            state.configure("https://example.test"); state.search("示例")
            withTimeout(5_000) { while (state.track == null) delay(10) }
            assertEquals(12L, state.selectedEpisode?.id)
            assertFalse(state.enabled)
            assertFalse(state.busy)
            state.close()
        } finally { http.close() }
    }
}
