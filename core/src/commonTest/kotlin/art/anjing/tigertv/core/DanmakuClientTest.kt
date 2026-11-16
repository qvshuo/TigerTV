package art.anjing.tigertv.core

import kotlin.test.*

class DanmakuClientTest {
    @Test fun parsesCapturedServiceShapeAndRejectsInvalidTimestamps() {
        val track = parseDanmakuTrack("""{"videoDuration":2888.2,"count":3,"comments":[{"cid":1,"p":"0.00,1,16777215,[youku]","m":"测试弹幕"},{"p":"1.25,5,255,[youku]","m":"第二条"},{"p":"-1,1,0,x","m":"无效"}]}""")
        assertEquals(2_888_200L, track.referenceDurationMs)
        assertEquals(listOf(0L, 1250L), track.comments.map { it.timeMs })
        assertEquals(0xFFFFFF, track.comments.first().color)
        assertNull(parseDanmakuTrack("""{"videoDuration":0,"comments":[]}""").referenceDurationMs)
    }
}
