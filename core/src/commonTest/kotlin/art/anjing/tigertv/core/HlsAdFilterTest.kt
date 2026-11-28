package art.anjing.tigertv.core

import kotlin.test.*
import kotlinx.serialization.json.*

class HlsAdFilterTest {
    @Test fun sharedWebFixturePreservesTheOriginalKeyAndRemovesTheSameInterval() {
        val fixture = Json.parseToJsonElement(contractFixtures.getValue("hls-filter.sample.json")).jsonObject
        val input = fixture.getValue("playlist").jsonPrimitive.content
        val result = filterSmartHlsAds(input, "https://fixtures.example/video.m3u8")!!
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

        }
    }

    @Test fun sharedStrategiesMatchIndependentlyOfPlaylistOrigin() {
        for (item in Json.parseToJsonElement(contractFixtures.getValue("hls-strategies.sample.json")).jsonArray) {
            val fixture = item.jsonObject
            val name = fixture.getValue("name").jsonPrimitive.content
            val playlist = fixture.getValue("playlist").jsonPrimitive.content
            val base = fixture.getValue("base").jsonPrimitive.content
            val mirror = "http://mirror.test:8800" + base.substringAfter("://").substringAfter('/', "").let { "/$it" }
            val intervals = fixture.getValue("removed").jsonArray.map {
                MediaInterval(it.jsonArray[0].jsonPrimitive.long, it.jsonArray[1].jsonPrimitive.long)
            }
            for (url in listOf(base, mirror)) {
                val result = filterSmartHlsAds(playlist, url)
                assertEquals(intervals, result?.removed.orEmpty(), name)
                if (result != null) {
                    assertEquals(intervals.first().endMs, result.timeline.effectiveToVideo(intervals.first().startMs), name)
                    for (text in fixture["contains"]?.jsonArray.orEmpty()) {
                        assertContains(result.playlist, text.jsonPrimitive.content, message = name)
                    }
                }
            }
        }
    }

    @Test fun sharedAdaptivePlaylists() {
        val fixture = Json.parseToJsonElement(contractFixtures.getValue("hls-adaptive.sample.json")).jsonObject
        for (item in fixture.getValue("masters").jsonArray) {
            val master = item.jsonObject
            assertEquals(master.getValue("variants").jsonObject.mapValues { it.value.jsonPrimitive.content.toBoolean() },
                hlsVideoVariants(master.getValue("playlist").jsonPrimitive.content, master.getValue("base").jsonPrimitive.content))
        }
        for (item in fixture.getValue("media").jsonArray) {
            val media = item.jsonObject
            val result = filterSmartHlsAds(media.getValue("playlist").jsonPrimitive.content, "https://fixtures.example/video.m3u8")
            val removed = media.getValue("removed").jsonArray.map {
                MediaInterval(it.jsonArray[0].jsonPrimitive.long, it.jsonArray[1].jsonPrimitive.long)
            }
            assertEquals(removed, result?.removed.orEmpty())
            if (removed.isEmpty()) assertNull(result)
            for (tag in media["contains"]?.jsonArray.orEmpty()) assertTrue(result!!.playlist.contains(tag.jsonPrimitive.content))
            for (tag in media["excludes"]?.jsonArray.orEmpty()) assertFalse(result!!.playlist.contains(tag.jsonPrimitive.content))
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
    @Test fun unverifiedRepeatedGroupsAndAdPathsAreKept() {
        assertNull(filterSmartHlsAds(realHlsFixture))
        for (fixture in crossWorkHlsFixtures) assertNull(filterSmartHlsAds(fixture))
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
    }

    @Test fun capturedEncryptedVodPreservesEveryNormalSegmentAndKey() {
        assertNull(filterSmartHlsAds(encryptedHlsFixture))
        val result = filterHlsAds(encryptedHlsFixture) { it.startsWith("ad-") }!!
        assertEquals(listOf(MediaInterval(851320, 868887), MediaInterval(1654727, 1672294), MediaInterval(2925254, 2942821)), result.removed)
        val original = parseHlsVod(encryptedHlsFixture)!!
        val filtered = parseHlsVod(result.playlist)!!
        assertEquals(1457, original.segments.size)
        assertEquals(original.segments.filter { it.uri.startsWith("content-") }, filtered.segments)
        assertTrue(filtered.segments.all { it.encrypted })
        assertEquals(851320L, result.timeline.videoToEffective(868887).timeMs)
        assertEquals(1672294L, result.timeline.effectiveToVideo(1637160))
    }

    @Test fun trailingDiscontinuityOfRealVodIsPreserved() {
        val p = "#EXTM3U\n#EXTINF:2,\ncontent\n#EXT-X-DISCONTINUITY\n#EXTINF:3,\nad\n#EXT-X-DISCONTINUITY\n#EXT-X-ENDLIST\n"
        val result = filterHlsAds(p) { it == "ad" }!!
        assertEquals(listOf(MediaInterval(2000, 5000)), result.removed)
        assertTrue(result.playlist.endsWith("#EXT-X-DISCONTINUITY\n#EXT-X-ENDLIST\n"))
        assertNotNull(parseHlsVod(result.playlist))
    }

    @Test fun unsupportedStructuresNeverProducePartialEdits() {
        for (tag in listOf("#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"20\"", "#EXT-X-BYTERANGE:0@0", "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"", "#EXT-X-MEDIA-SEQUENCE:42")) {
            val input = realHlsFixture.replaceFirst("#EXTM3U", "#EXTM3U\n$tag")
            assertNull(filterHlsAds(input) { it.startsWith("/video/adjump/time/") })
        }
    }

}
