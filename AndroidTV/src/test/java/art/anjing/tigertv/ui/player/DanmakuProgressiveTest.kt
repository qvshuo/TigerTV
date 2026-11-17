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
    @Test fun firstTrackDisplaysBeforeSequentialUpgradeAndKeepsOffset() = runBlocking {
        val gate=CompletableDeferred<Unit>(); val calls=mutableListOf<String>();val writes=mutableListOf<Long>()
        val http=configureClient(HttpClient(MockEngine { r ->
            val p=r.url.encodedPath;calls+=p
            respond(when {
                p.contains("search") -> """{"success":true,"animes":[{"animeId":2,"animeTitle":"示例","source":"tencent"},{"animeId":1,"animeTitle":"示例","source":"iqiyi"},{"animeId":3,"animeTitle":"示例","source":"360"}]}"""
                p.contains("bangumi") -> """{"success":true,"bangumi":{"episodes":[{"episodeId":${p.substringAfterLast('/').toInt()*10},"episodeNumber":"1"}]}}"""
                p.endsWith("20") -> { gate.await(); comments(4_500) }
                p.endsWith("30") -> comments(5_000)
                else -> comments(1_500)
            })
        }))
        try {
            val state=DanmakuState(this,{DanmakuClient(Network(http),it)},true,1,{_,id->writes+=id})
            state.configure("https://example.test");state.offsetMs=3000;state.search("示例")
            withTimeout(5_000){while(state.track==null)delay(10)}
            assertEquals(1L,state.selectedTitle?.id);assertTrue(state.busy)
            gate.complete(Unit)
            withTimeout(5_000){while(state.busy)delay(10)}
            assertEquals(2L,state.selectedTitle?.id);assertEquals(3000L,state.offsetMs);assertTrue(writes.isEmpty())
            assertEquals(listOf("/api/v2/comment/10","/api/v2/comment/20","/api/v2/comment/30"),calls.filter{it.contains("comment")})
            state.close()
        } finally {http.close()}
    }
    @Test fun manualChoiceCancelsPendingReplacementAndOnlyPersistsValidData() = runBlocking {
        val gate=CompletableDeferred<Unit>();val writes=mutableListOf<Long>()
        val http=configureClient(HttpClient(MockEngine { r ->
            val p=r.url.encodedPath
            respond(when {
                p.contains("search") -> """{"success":true,"animes":[{"animeId":1,"animeTitle":"示例"},{"animeId":2,"animeTitle":"示例"}]}"""
                p.contains("bangumi") -> """{"success":true,"bangumi":{"episodes":[{"episodeId":${p.substringAfterLast('/').toInt()*10},"episodeNumber":"1"}]}}"""
                p.endsWith("20") -> { gate.await(); comments(5_000) }
                p.endsWith("30") -> comments(0)
                else -> comments(1_500)
            })
        }))
        try {
            val state=DanmakuState(this,{DanmakuClient(Network(http),it)},true,1,{_,id->writes+=id})
            state.configure("https://example.test");state.search("示例")
            withTimeout(5_000){while(state.track==null)delay(10)}
            state.select(state.titles.first{it.id==1L})
            withTimeout(5_000){while(state.busy)delay(10)}
            gate.complete(Unit);delay(50)
            assertEquals(1L,state.selectedTitle?.id);assertEquals(listOf(1L),writes)
            state.select(DanmakuTitle(3,"示例",""))
            withTimeout(5_000){while(state.busy)delay(10)}
            assertEquals(listOf(1L),writes)
            state.close()
        } finally {http.close()}
    }
    @Test fun rateLimitStopsComparisonAndKeepsTheFirstTrack() = runBlocking {
        val calls=mutableListOf<String>()
        val http=configureClient(HttpClient(MockEngine { r ->
            val p=r.url.encodedPath;calls+=p
            if(p.endsWith("bangumi/2")) respond("",HttpStatusCode.TooManyRequests)
            else respond(when {
                p.contains("search") -> """{"success":true,"animes":[{"animeId":1,"animeTitle":"示例"},{"animeId":2,"animeTitle":"示例"},{"animeId":3,"animeTitle":"示例"}]}"""
                p.contains("bangumi") -> """{"success":true,"bangumi":{"episodes":[{"episodeId":10,"episodeNumber":"1"}]}}"""
                else -> comments(1_500)
            })
        }))
        try {
            val state=DanmakuState(this,{DanmakuClient(Network(http),it)},true,1)
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
            val state=DanmakuState(this,{DanmakuClient(Network(http),it)},true,1)
            state.search("未配置");assertNotNull(state.error)
            state.configure("https://example.test");state.search("示例")
            withTimeout(5_000){while(state.busy)delay(10)}
            assertEquals(5L,state.selectedTitle?.id);state.close()
        } finally {http.close()}
    }
}
