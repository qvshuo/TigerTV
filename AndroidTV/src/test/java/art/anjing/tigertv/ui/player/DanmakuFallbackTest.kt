package art.anjing.tigertv.ui.player

import art.anjing.tigertv.core.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DanmakuFallbackTest {
    @Test fun fallbackDoesNotOverwriteManualChoiceAndReusesFetchedTracks() = runBlocking {
        val calls = mutableListOf<String>(); val remembered = mutableListOf<Long>()
        val http = configureClient(HttpClient(MockEngine { request ->
            val path = request.url.encodedPath; calls += path
            respond(when {
                path.contains("search") -> """{"success":true,"animes":[{"animeId":1,"animeTitle":"示例(2026)"},{"animeId":2,"animeTitle":"示例(2026)"},{"animeId":5,"animeTitle":"示例(2025)"},{"animeId":3,"animeTitle":"示例(2026)"},{"animeId":4,"animeTitle":"示例第二季"}]}"""
                path.endsWith("bangumi/1") -> """{"success":true,"bangumi":{"episodes":[{"episodeId":11,"episodeNumber":"1"}]}}"""
                path.contains("bangumi") -> { val id=path.substringAfterLast('/').toInt()*10+2; """{"success":true,"bangumi":{"episodes":[{"episodeId":$id,"episodeNumber":"2"}]}}""" }
                path.endsWith("22") -> """{"comments":[]}"""
                else -> """{"comments":[{"p":"1,1,16777215","m":"有效"},{"p":"2,5,16777215","m":"固定弹幕"}]}"""
            })
        }))
        try {
            val state = DanmakuState(this,{ DanmakuClient(Network(http),it) },true,"2",{_,id->remembered+=id},{1}, workYear = "2026")
            state.configure("https://example.test/token"); state.search("示例")
            withTimeout(5_000) { while(state.busy) delay(10) }
            assertEquals(3L,state.selectedTitle?.id)
            assertEquals(listOf("有效"), state.track?.comments?.map { it.text })
            assertTrue(remembered.isEmpty())
            assertFalse(calls.any { it.endsWith("bangumi/4") || it.endsWith("bangumi/5") })
            val commentCalls = calls.count { it.contains("comment") }
            state.select(state.titles.first { it.id==3L })
            withTimeout(5_000) { while(state.busy) delay(10) }
            assertTrue(remembered.isEmpty())
            state.select(state.episodes.single())
            withTimeout(5_000) { while(state.busy) delay(10) }
            assertEquals(listOf(3L),remembered)
            assertEquals(commentCalls,calls.count { it.contains("comment") })
            state.close()
        } finally {http.close()}
    }
    @Test fun savedChoiceSkipsSearchAndServiceChangesInvalidateTracks() = runBlocking {
        val calls = mutableListOf<String>(); val writes = mutableListOf<Long>()
        val http = configureClient(HttpClient(MockEngine { request ->
            val path = request.url.encodedPath; calls += path
            when {
                path.contains("search") -> respond("""{"success":true,"animes":[{"animeId":10,"animeTitle":"示例"}]}""")
                path.endsWith("bangumi/9") || path.endsWith("bangumi/10") -> respond("""{"success":true,"bangumi":{"episodes":[{"episodeId":91,"episodeNumber":"1"},{"episodeId":92,"episodeNumber":"2"}]}}""")
                path.contains("comment") -> respond("""{"comments":[{"p":"1,1,16777215","m":"有效"}]}""")
                else -> error("Search must not be necessary for a valid saved choice")
            }
        }))
        try {
            val state = DanmakuState(this, { DanmakuClient(Network(http), it) }, true, "1", { _, id -> writes += id }, { 9 })
            state.configure("https://example.test/first"); state.search("示例")
            withTimeout(5_000) { while (state.busy) delay(10) }
            assertEquals(9L, state.selectedTitle?.id)
            assertTrue(writes.isEmpty())
            val nextEpisode = DanmakuState(this, { DanmakuClient(Network(http), it) }, true, "2", preferredTitle = { 9 })
            nextEpisode.configure("https://example.test/first"); nextEpisode.search("示例")
            withTimeout(5_000) { while (nextEpisode.busy) delay(10) }
            assertEquals(9L, nextEpisode.selectedTitle?.id)
            assertEquals(92L, nextEpisode.selectedEpisode?.id)
            nextEpisode.close()
            assertFalse(calls.any { it.contains("search") })
            state.configure("https://example.test/first"); state.search("示例", useSaved = false)
            withTimeout(5_000) { while (state.busy) delay(10) }
            assertEquals(listOf(10L), state.titles.map { it.id })
            assertEquals(9L, state.selectedTitle?.id)
            assertEquals(2, calls.count { it.contains("comment") })
            assertTrue(writes.isEmpty())
            state.configure("https://example.test/second")
            assertNull(state.track); assertNull(state.selectedTitle)
            state.configure("https://example.test/second"); state.search("示例")
            withTimeout(5_000) { while (state.busy) delay(10) }
            assertEquals(3, calls.count { it.contains("comment") })
            state.close()
        } finally { http.close() }
    }
    @Test fun failingCandidateFallsBackWithoutChoosingAnotherSeason() = runBlocking {
        val http = configureClient(HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.contains("search") -> respond("""{"success":true,"animes":[{"animeId":1,"animeTitle":"示例"},{"animeId":2,"animeTitle":"示例"}]}""")
                path.endsWith("bangumi/1") -> error("Unavailable candidate")
                path.endsWith("bangumi/2") -> respond("""{"success":true,"bangumi":{"episodes":[{"episodeId":21,"episodeNumber":"1"}]}}""")
                else -> respond("""{"comments":[{"p":"1,1,16777215","m":"有效"}]}""")
            }
        }))
        try {
            val state = DanmakuState(this, { DanmakuClient(Network(http), it) }, true, "1")
            state.configure("https://example.test"); state.search("示例")
            withTimeout(5_000) { while (state.busy) delay(10) }
            assertEquals(2L, state.selectedTitle?.id)
            assertNull(state.error)
            state.close()
        } finally { http.close() }
    }
    @Test fun preferenceIdentityIsWorkSiteAndServiceOnly() {
        val key=danmakuPreferenceKey("https://host/key/","work","site")
        assertEquals(key,danmakuPreferenceKey("https://host/key","work","site"))
        assertNotEquals(key,danmakuPreferenceKey("https://host/key","work","other"))
        assertNotEquals(key,danmakuPreferenceKey("https://host/key","other","site"))
        assertNotEquals(key,danmakuPreferenceKey("https://host/new-key","work","site"))
        assertFalse(key.contains("key/"))
    }
}
