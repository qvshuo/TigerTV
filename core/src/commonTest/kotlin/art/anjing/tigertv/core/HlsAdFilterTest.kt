package art.anjing.tigertv.core

import kotlin.test.*
import kotlinx.serialization.json.*

class HlsAdFilterTest {
    @Test fun sharedWebFixturePreservesTheOriginalKeyAndRemovesTheSameInterval() {
        val fixture = Json.parseToJsonElement(contractFixtures.getValue("hls-filter.sample.json")).jsonObject
        val input = fixture.getValue("playlist").jsonPrimitive.content
        val result = filterSmartHlsAds(input)!!
        assertEquals(fixture.getValue("expected").jsonPrimitive.content, result.playlist)
        assertEquals(listOf(MediaInterval(fixture.getValue("startMs").jsonPrimitive.long,
            fixture.getValue("endMs").jsonPrimitive.long)), result.removed)
    }

    @Test fun sharedHeuristicsAndImplicitIvPreserveOriginalSegmentSequence() {
        val cases = Json.parseToJsonElement(contractFixtures.getValue("hls-smart.sample.json")).jsonArray
        for (element in cases) {
            val fixture = element.jsonObject
            val name = fixture.getValue("name").jsonPrimitive.content
            val input = fixture.getValue("playlist").jsonPrimitive.content
            val removed = fixture.getValue("removed").jsonArray.map {
                MediaInterval(it.jsonArray[0].jsonPrimitive.long, it.jsonArray[1].jsonPrimitive.long)
            }
            val result = filterSmartHlsAds(input)
            assertEquals(removed, result?.removed.orEmpty(), name)
            if (result == null) continue
            assertTrue("IV=0x00000000000000000000000000000069" in result.playlist, name)
            assertTrue("#EXT-X-VERSION:${if (removed.size == 2) 3 else 2}" in result.playlist, name)
            assertTrue("#EXT-X-DISCONTINUITY" in result.playlist, name)
            assertFalse("#EXT-X-CUE-" in result.playlist, name)
            assertEquals(68000L, result.timeline.effectiveToVideo(60000))
            assertEquals(60000L, result.timeline.videoToEffective(65000).timeMs)
            if (name == "repeated-clear-inserts-without-discontinuities") {
                assertEquals(136000L, result.timeline.effectiveToVideo(120000))
                assertEquals(121000L, result.timeline.videoToEffective(137000).timeMs)
                assertTrue("IV=0x0000000000000000000000000000006e" in result.playlist)
            }
        }
    }

    @Test fun sharedSourceProfilesKeepUnknownAndOrdinaryContent() {
        for (item in Json.parseToJsonElement(contractFixtures.getValue("hls-source-groups.sample.json")).jsonArray) {
            val fixture = item.jsonObject
            val name = fixture.getValue("name").jsonPrimitive.content
            val result = filterSmartHlsAds(fixture.getValue("playlist").jsonPrimitive.content,
                fixture.getValue("host").jsonPrimitive.content)
            val intervals = fixture.getValue("removed").jsonArray.map {
                MediaInterval(it.jsonArray[0].jsonPrimitive.long, it.jsonArray[1].jsonPrimitive.long)
            }
            assertEquals(intervals, result?.removed.orEmpty(), name)
            if (result != null) {
                assertEquals(intervals.first().endMs, result.timeline.effectiveToVideo(intervals.first().startMs), name)
            }
        }
    }

    @Test fun removesOnlySelectedSegmentsAndMapsTime() {
        val input = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:3,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:2,\nad.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:3,\nb.ts\n#EXT-X-ENDLIST\n"
        val out = filterHlsAds(input) { it == "ad.ts" }!!
        assertEquals(listOf(MediaInterval(3000, 5000)), out.removed)
        assertEquals(5000L, out.timeline.effectiveToVideo(3000))
        assertFalse(out.playlist.contains("ad.ts"))
        assertNull(filterHlsAds(input.replace("#EXTINF:3,\na.ts", "#EXTINF:3\na.ts")) { it == "ad.ts" })
        assertNull(filterHlsAds(input.replace("#EXT-X-DISCONTINUITY\n#EXTINF:2,", "#EXT-X-DISCONTINUITY\n#EXT-X-DISCONTINUITY\n#EXTINF:2,")) { it == "ad.ts" })
        assertTrue(out.playlist.contains("#EXT-X-DISCONTINUITY\n#EXTINF:3,\nb.ts"))
    }
    @Test fun rejectsUnsupportedKeyAndUnmatchedPlaylist() {
        val s = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:2,\nad.ts\n#EXT-X-ENDLIST\n"
        assertNull(filterHlsAds(s) { true })
        assertNull(filterHlsAds(s.replace("#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n", "")) { false })
    }
    @Test fun sanitizedRealPlaylistRemovesRepeatedAds() {
        val result = filterSmartHlsAds(realHlsFixture)!!
        assertEquals(listOf(MediaInterval(300880, 326640), MediaInterval(2428640, 2454400)), result.removed)
        assertEquals(2454400L, result.timeline.effectiveToVideo(2402880))
        assertFalse(result.playlist.contains("/video/adjump/time/"))
    }

    @Test fun adPathBetweenDistinctContentChaptersNeedsBothSpliceEdges() {
        val padBefore = (1..3).joinToString("") { "#EXTINF:30,\nleft/$it.ts\n" }
        val padAfter = (1..3).joinToString("") { "#EXTINF:30,\nright/$it.ts\n" }
        for (fixture in crossWorkHlsFixtures) {
            val input = fixture.replace("#EXTINF:1,\nbefore.ts", padBefore + "#EXTINF:1,\nbefore.ts")
                .replace("#EXT-X-ENDLIST", padAfter + "#EXT-X-ENDLIST")
            val filtered = filterSmartHlsAds(input)!!
            assertEquals(listOf(MediaInterval(91000, 116760)), filtered.removed)
            assertTrue("before.ts" in filtered.playlist && "after.ts" in filtered.playlist)
            assertNull(filterSmartHlsAds(input.replaceFirst("#EXT-X-DISCONTINUITY\n", "")))
        }
    }

    @Test fun fractionalDurationsRoundCumulativeTimeline() {
        val s = "#EXTM3U\n#EXTINF:1.566667,\na\n#EXTINF:1.566667,\nad\n#EXTINF:1.566667,\nb\n#EXT-X-ENDLIST\n"
        val result = filterHlsAds(s) { it == "ad" }!!
        assertEquals(listOf(MediaInterval(1567, 3133)), result.removed)
    }

    @Test fun archivedSingleUnlabelledSpliceRemainsIntactWithoutCorroboration() {
        assertNull(filterSmartHlsAds(iqiyiHlsFixture))
    }

    @Test fun explicitIvKeyStateSurvivesRemovedNoneGroup() {
        val key = "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x00000000000000000000000000000001"
        val input = "#EXTM3U\n$key\n#EXTINF:3,\na.ts\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:2,\nad.ts\n$key\n#EXTINF:3,\nb.ts\n#EXT-X-ENDLIST\n"
        val result = filterHlsAds(input) { it == "ad.ts" }!!
        assertFalse(result.playlist.contains("METHOD=NONE"))
        assertEquals(1, result.playlist.lines().count { it == key })
        assertTrue(result.playlist.contains("#EXTINF:3,\nb.ts"))
        assertTrue(filterHlsAds(input.replace(",IV=0x00000000000000000000000000000001", "")) { it == "ad.ts" }!!
            .playlist.contains("IV=0x00000000000000000000000000000002"))
        assertNull(filterHlsAds(input.replace("AES-128", "SAMPLE-AES")) { it == "ad.ts" })
    }

    @Test fun selectedFinalPlainAssetDoesNotChangeKeptKey() {
        val key = "#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x00000000000000000000000000000001"
        val input = "#EXTM3U\n$key\n#EXTINF:3,\ncontent\n#EXT-X-DISCONTINUITY\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:5,\nad\n#EXT-X-ENDLIST\n"
        val result = filterHlsAds(input) { it == "ad" }!!
        assertEquals(listOf(MediaInterval(3000, 8000)), result.removed)
        assertTrue(result.playlist.contains(key))
        assertFalse(result.playlist.contains("METHOD=NONE"))
        assertFalse(result.playlist.contains("\nad\n"))
        assertNull(filterSmartHlsAds(input))
        assertEquals("https://media.test/a/stream.m3u8", singleHlsVariant("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nstream.m3u8\n", "https://media.test/a/master.m3u8"))
        assertNull(singleHlsVariant("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\na.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=200\nb.m3u8\n", "https://media.test/master.m3u8"))
    }

    @Test fun capturedEncryptedVodPreservesEveryNormalSegmentAndKey() {
        val result = filterSmartHlsAds(encryptedHlsFixture)!!
        assertEquals(listOf(MediaInterval(851320, 868887), MediaInterval(1654727, 1672294), MediaInterval(2925254, 2942821)), result.removed)
        val original = parseHlsVod(encryptedHlsFixture)!!
        val filtered = parseHlsVod(result.playlist)!!
        assertEquals(1457, original.segments.size)
        assertEquals(original.segments.filter { it.uri.startsWith("content-") }, filtered.segments)
        assertTrue(filtered.segments.all { it.encrypted })
        assertEquals(851320L, result.timeline.videoToEffective(868887).timeMs)
        assertEquals(1672294L, result.timeline.effectiveToVideo(1637160))
        assertEquals(2, filterSmartHlsAds(encryptedHlsFixture.replaceFirst("ad-2.ts", "changed.ts"))?.removed?.size)
    }

    @Test fun trailingDiscontinuityOfRealVodIsPreserved() {
        val p = "#EXTM3U\n#EXTINF:2,\ncontent\n#EXT-X-DISCONTINUITY\n#EXTINF:3,\nad\n#EXT-X-DISCONTINUITY\n#EXT-X-ENDLIST\n"
        val result = filterHlsAds(p) { it == "ad" }!!
        assertEquals(listOf(MediaInterval(2000, 5000)), result.removed)
        assertTrue(result.playlist.endsWith("#EXT-X-DISCONTINUITY\n#EXT-X-ENDLIST\n"))
        assertNotNull(parseHlsVod(result.playlist))
    }

    @Test fun unsupportedStructuresNeverProducePartialEdits() {
        for (tag in listOf("#EXT-X-MAP:URI=\"init.mp4\"", "#EXT-X-BYTERANGE:100@0", "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"", "#EXT-X-MEDIA-SEQUENCE:42")) {
            val input = realHlsFixture.replaceFirst("#EXTM3U", "#EXTM3U\n$tag")
            assertNull(filterHlsAds(input) { it.startsWith("/video/adjump/time/") })
        }
    }

}
