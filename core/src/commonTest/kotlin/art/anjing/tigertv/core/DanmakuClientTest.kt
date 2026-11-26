package art.anjing.tigertv.core

import kotlin.test.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.test.runTest

class DanmakuClientTest {
    @Test fun tokenPathAndEpisodeTitlesUseTheUpstreamContract() = runTest {
        val http = configureClient(HttpClient(testEngine { request ->
            assertEquals("/example-token/api/v2/bangumi/42", request.url.encodedPath)
            respond("""{"success":true,"bangumi":{"seasons":[{"id":"two","name":"第二季"}],"episodes":[{"seasonId":"two","episodeId":99,"episodeTitle":"正片","episodeNumber":"2"}]}}""")
        }))
        try {
            val episodes = DanmakuClient(Network(http), "https://example.test/example-token/").episodes(42)
            assertEquals(DanmakuEpisode(99, "正片", "2"), episodes.single())
        } finally { http.close() }
    }

    @Test fun parsesCapturedServiceShapeAndRejectsInvalidTimestamps() {
        val track = parseDanmakuTrack("""{"videoDuration":2888.2,"count":3,"comments":[{"cid":1,"p":"0.00,1,16777215,[youku]","m":"测试弹幕"},{"p":"1.25,5,255,[youku]","m":"第二条"},{"p":"-1,1,0,x","m":"无效"}]}""")
        assertEquals(2_888_200L, track.referenceDurationMs)
        assertEquals(listOf(0L, 1250L), track.comments.map { it.timeMs })
        assertEquals(0xFFFFFF, track.comments.first().color)
        assertNull(parseDanmakuTrack("""{"videoDuration":0,"comments":[]}""").referenceDurationMs)
    }

    @Test fun recentTrackIsReusedOnlyForTheSameServerAndEpisode() = runTest {
        val requests = mutableListOf<String>()
        var now = 0L
        val http = configureClient(HttpClient(testEngine { request ->
            requests += request.url.encodedPath
            respond("""{"comments":[{"p":"1,1,0","m":"测试"}]}""")
        }))
        try {
            val cache = DanmakuTrackCache { now }
            val network = Network(http)
            fun client(path: String) = DanmakuClient(network, "https://example.test/$path", cache)
            val first = client("a").comments(7)
            assertSame(first, client("a").comments(7))
            assertNotSame(first, client("b").comments(7))
            assertNotSame(first, client("a").comments(8))
            now = 600_000
            assertNotSame(first, client("a").comments(7))
            assertEquals(listOf("/a/api/v2/comment/7", "/b/api/v2/comment/7", "/a/api/v2/comment/8", "/a/api/v2/comment/7"), requests)
        } finally { http.close() }
    }
}
