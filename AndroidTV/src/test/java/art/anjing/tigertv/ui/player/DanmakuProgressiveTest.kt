package art.anjing.tigertv.ui.player

import art.anjing.tigertv.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DanmakuProgressiveTest {
    private fun comments(n:Int) = """{"videoDuration":2800,"comments":[${(0 until n).joinToString(",") { """{"p":"$it,1,16777215","m":"评论$it"}""" }}]}"""
    @Test fun firstTrackDisplaysBeforeSequentialUpgrade() = runBlocking {
        val gate=CompletableDeferred<Unit>(); val calls=mutableListOf<String>();val writes=mutableListOf<Long>()
        val http=configureClient(HttpClient(MockEngine { r ->
            val p=r.url.encodedPath;calls+=p
            respond(when {
                p.contains("search") -> """{"success":true,"animes":[{"animeId":4,"animeTitle":"示例","source":"iqiyi"},{"animeId":2,"animeTitle":"示例(2026)","source":"tencent"},{"animeId":1,"animeTitle":"示例(2026)","source":"iqiyi"},{"animeId":3,"animeTitle":"示例(2026)","source":"360"},{"animeId":5,"animeTitle":"示例(2025)","source":"iqiyi"}]}"""
                p.contains("bangumi") -> """{"success":true,"bangumi":{"episodes":[{"episodeId":${p.substringAfterLast('/').toInt()*10},"episodeNumber":"1"}]}}"""
                p.endsWith("20") -> { gate.await(); comments(9) }
                p.endsWith("30") -> comments(10)
                else -> comments(3)
            })
        }))
        try {
            val state=DanmakuState(this,{DanmakuClient(Network(http),it)},true,"1",{_,id->writes+=id}, workYear = "2026")
            state.configure("https://example.test");state.search("示例")
            withTimeout(5_000){while(state.track==null)delay(10)}
            assertEquals(1L,state.selectedTitle?.id);assertTrue(state.busy)
            gate.complete(Unit)
            withTimeout(5_000){while(state.busy)delay(10)}
            assertEquals(2L,state.selectedTitle?.id);assertTrue(writes.isEmpty())
            assertEquals(listOf("/api/v2/comment/10","/api/v2/comment/20","/api/v2/comment/30"),calls.filter{it.contains("comment")})
            assertFalse(calls.any { it.endsWith("bangumi/4") || it.endsWith("bangumi/5") })
            state.close()
        } finally {http.close()}
    }
    @Test fun manualChoiceCancelsPendingReplacementAndOnlyPersistsValidData() = runBlocking {
        val gate=CompletableDeferred<Unit>();val manualGate=CompletableDeferred<Unit>();val writes=mutableListOf<Long>()
        val http=configureClient(HttpClient(MockEngine { r ->
            val p=r.url.encodedPath
            respond(when {
                p.contains("search") -> """{"success":true,"animes":[{"animeId":1,"animeTitle":"示例"},{"animeId":2,"animeTitle":"示例"}]}"""
                p.contains("bangumi") -> """{"success":true,"bangumi":{"episodes":[{"episodeId":${p.substringAfterLast('/').toInt()*10},"episodeNumber":"1"}]}}"""
                p.endsWith("20") -> { gate.await(); comments(10) }
                p.endsWith("30") -> { manualGate.await(); comments(0) }
                else -> comments(3)
            })
        }))
        try {
            val state=DanmakuState(this,{DanmakuClient(Network(http),it)},true,"1",{_,id->writes+=id})
            state.configure("https://example.test");state.search("示例")
            withTimeout(5_000){while(state.track==null)delay(10)}
            state.select(state.titles.first{it.id==1L})
            withTimeout(5_000){while(state.busy)delay(10)}
            assertTrue(writes.isEmpty())
            state.select(state.episodes.single())
            withTimeout(5_000){while(state.busy)delay(10)}
            gate.complete(Unit);delay(50)
            assertEquals(1L,state.selectedTitle?.id);assertEquals(listOf(1L),writes)
            state.select(DanmakuTitle(3,"示例",""))
            withTimeout(5_000){while(state.busy)delay(10)}
            state.select(state.episodes.single())
            state.toggleEnabled()
            assertFalse(state.enabled);assertTrue(state.busy)
            manualGate.complete(Unit)
            withTimeout(5_000){while(state.busy)delay(10)}
            assertEquals(listOf(1L),writes)
            assertTrue(state.hasComments)
            state.select(state.titles.first{it.id==1L})
            withTimeout(5_000){while(state.busy)delay(10)}
            state.select(state.episodes.single())
            withTimeout(5_000){while(state.busy)delay(10)}
            assertFalse(state.enabled);assertTrue(state.hasComments)
            state.close()
        } finally {http.close()}
    }
    @Test fun exactManualEpisodeOverridesAnUnmatchedProviderLabelOnReplay() = runBlocking {
        val calls = mutableListOf<String>()
        val http = configureClient(HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            calls += path
            respond(when {
                path.endsWith("bangumi/3") -> """{"success":true,"bangumi":{"episodes":[{"episodeId":42,"episodeTitle":"第5集","episodeNumber":"5"}]}}"""
                path.endsWith("comment/42") -> comments(3)
                else -> error("Unexpected request $path")
            })
        }))
        try {
            val state = DanmakuState(this, { DanmakuClient(Network(http), it) },
                episodeLabel = "正片", preferredEpisode = {
                    DanmakuChoice(DanmakuTitle(3, "示例", "test"), DanmakuEpisode(42, "第5集", "5"))
                })
            state.configure("https://example.test")
            state.search("示例")
            withTimeout(5_000) { while (state.busy) delay(10) }
            assertEquals(42L, state.selectedEpisode?.id)
            assertEquals("test", state.selectedTitle?.source)
            assertTrue(state.hasComments)
            assertEquals(listOf("/api/v2/comment/42"), calls)
            state.close()
        } finally { http.close() }
    }
    @Test fun rateLimitStopsComparisonAndKeepsTheFirstTrack() = runBlocking {
        val calls=mutableListOf<String>()
        val http=configureClient(HttpClient(MockEngine { r ->
            val p=r.url.encodedPath;calls+=p
            if(p.endsWith("bangumi/2")) respond("",HttpStatusCode.TooManyRequests)
            else respond(when {
                p.contains("search") -> """{"success":true,"animes":[{"animeId":1,"animeTitle":"示例"},{"animeId":2,"animeTitle":"示例"},{"animeId":3,"animeTitle":"示例"}]}"""
                p.contains("bangumi") -> """{"success":true,"bangumi":{"episodes":[{"episodeId":10,"episodeNumber":"1"}]}}"""
                else -> comments(3)
            })
        }))
        try {
            val state=DanmakuState(this,{DanmakuClient(Network(http),it)},true,"1")
            state.configure("https://example.test");state.search("示例")
            withTimeout(5_000){while(state.busy)delay(10)}
            assertEquals(1L,state.selectedTitle?.id);assertNotNull(state.track)
            assertFalse(calls.any{it.endsWith("bangumi/3")});state.close()
        } finally {http.close()}
    }
    @Test fun laterValidCandidateIsNotCutOffAtThree() = runBlocking {
        val http=configureClient(HttpClient(MockEngine { r ->
            val p=r.url.encodedPath
            respond(when {
                p.contains("search") -> """{"success":true,"animes":[${(1..5).joinToString(","){"""{"animeId":$it,"animeTitle":"示例"}"""}}]}"""
                p.endsWith("bangumi/5") -> """{"success":true,"bangumi":{"episodes":[{"episodeId":50,"episodeNumber":"1"}]}}"""
                p.contains("bangumi") -> """{"success":true,"bangumi":{"episodes":[]}}"""
                else -> comments(1)
            })
        }))
        try {
            val state=DanmakuState(this,{DanmakuClient(Network(http),it)},true,"1")
            state.search("未配置");assertNotNull(state.error)
            state.configure("https://example.test");state.search("示例")
            withTimeout(5_000){while(state.busy)delay(10)}
            assertEquals(5L,state.selectedTitle?.id);state.close()
        } finally {http.close()}
    }
    @Test fun closingStopsComparisonButKeepsTrackForReopening() = runBlocking {
        val secondStarted=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
        val never=CompletableDeferred<Unit>();val calls=mutableListOf<String>()
        val http=configureClient(HttpClient(MockEngine { r ->
            val path=r.url.encodedPath;calls+=path
            respond(when {
                path.contains("search") -> """{"success":true,"animes":[{"animeId":1,"animeTitle":"示例"},{"animeId":2,"animeTitle":"示例"},{"animeId":3,"animeTitle":"示例"}]}"""
                path.contains("bangumi") -> """{"success":true,"bangumi":{"episodes":[{"episodeId":${path.substringAfterLast('/').toInt()*10},"episodeNumber":"1"}]}}"""
                path.endsWith("20") -> { secondStarted.complete(Unit);try { never.await();comments(8) } finally { cancelled.complete(Unit) } }
                else -> comments(3)
            })
        }))
        try {
            val state=DanmakuState(this,{DanmakuClient(Network(http),it)},true,"1")
            state.configure("https://example.test");state.search("示例")
            withTimeout(5_000){secondStarted.await(); while (!state.hasComments) delay(10)}
            val loaded=state.track;assertNotNull(loaded)
            state.toggleEnabled()
            withTimeout(5_000){cancelled.await()}
            assertFalse(state.enabled);assertFalse(state.busy)
            assertSame(loaded,state.track);assertEquals(1L,state.selectedTitle?.id)
            assertFalse(calls.any{it.endsWith("bangumi/3")})
            val before=calls.size
            state.toggleEnabled();delay(50)
            assertTrue(state.enabled);assertSame(loaded,state.track);assertEquals(before,calls.size)
            state.close()
        } finally {http.close()}
    }
    @Test fun closingBeforeFirstResultAllowsFreshMatchingOnReopen() = runBlocking {
        val started=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
        val never=CompletableDeferred<Unit>();var searches=0
        val http=configureClient(HttpClient(MockEngine { r ->
            val path=r.url.encodedPath
            respond(when {
                path.contains("search") -> {
                    searches++
                    if(searches==1){started.complete(Unit);try{never.await()}finally{cancelled.complete(Unit)}}
                    """{"success":true,"animes":[{"animeId":1,"animeTitle":"示例"}]}"""
                }
                path.contains("bangumi") -> """{"success":true,"bangumi":{"episodes":[{"episodeId":10,"episodeNumber":"1"}]}}"""
                else -> comments(1)
            })
        }))
        try {
            val state=DanmakuState(this,{DanmakuClient(Network(http),it)},true,"1")
            state.configure("https://example.test");state.search("示例")
            withTimeout(5_000){started.await()}
            state.toggleEnabled()
            withTimeout(5_000){cancelled.await()}
            assertNull(state.track);assertFalse(state.busy)
            state.toggleEnabled()
            state.search("示例")
            withTimeout(5_000){while(state.busy)delay(10)}
            assertEquals(2,searches);assertEquals(1L,state.selectedTitle?.id);assertNotNull(state.track)
            state.close()
        } finally {http.close()}
    }

}
