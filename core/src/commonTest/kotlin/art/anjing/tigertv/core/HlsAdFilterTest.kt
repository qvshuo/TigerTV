package art.anjing.tigertv.core

import kotlin.test.*

class HlsAdFilterTest {
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
        val result = filterHlsAds(realHlsFixture) { it.startsWith("/video/adjump/time/") }!!
        assertEquals(listOf(MediaInterval(300880, 326640), MediaInterval(2428640, 2454400)), result.removed)
        assertEquals(2454400L, result.timeline.effectiveToVideo(2402880))
        assertFalse(result.playlist.contains("/video/adjump/time/"))
    }

    @Test fun baofengRuleRequiresSourceAndCompleteBoundedGroup() {
        val fingerprints = (100..108).map { uriHash("/video/adjump/time/$it.ts") }
        val rule = KnownHlsAdGroup("🎬暴风资源", fingerprints, List(8) { 3.0 } + 1.76, "/video/adjump/time/")
        val result = filterKnownHlsAd("🎬暴风资源", realHlsFixture, rule)!!
        assertEquals(2, result.removed.size)
        assertNull(filterKnownHlsAd("other", realHlsFixture, rule))
        assertNull(filterKnownHlsAd("🎬暴风资源", realHlsFixture.replaceFirst("#EXTINF:1.76,", "#EXTINF:1.75,"), rule))
    }

    @Test fun capturedCrossWorkGroupsAndAdjacentContent() {
        for (fixture in crossWorkHlsFixtures) {
            val rule = KnownHlsAdGroup("🎬暴风资源", (0..8).map { uriHash("/video/adjump/time/$it.ts") }, List(8) { 3.0 } + 1.76, "/video/adjump/time/")
            val result = filterKnownHlsAd("🎬暴风资源", fixture, rule)!!
            assertEquals(listOf(MediaInterval(1000, 26760)), result.removed)
            assertTrue(result.playlist.contains("before.ts"))
            assertTrue(result.playlist.contains("after.ts"))
        }
    }

    @Test fun fractionalDurationsRoundCumulativeTimeline() {
        val s = "#EXTM3U\n#EXTINF:1.566667,\na\n#EXTINF:1.566667,\nad\n#EXTINF:1.566667,\nb\n#EXT-X-ENDLIST\n"
        val result = filterHlsAds(s) { it == "ad" }!!
        assertEquals(listOf(MediaInterval(1567, 3133)), result.removed)
    }

    @Test fun iqiyiRequiresAllFiveKnownUrisAndPreservesContent() {
        val uris = (0 until 5).map { "known-$it" }
        val group = uris.mapIndexed { i, uri -> "#EXTINF:${if (i == 4) "1.566667" else "4.0"},\n$uri\n" }.joinToString("")
        val playlist = "#EXTM3U\n#EXTINF:5,\nbefore\n#EXT-X-DISCONTINUITY\n" + group + "#EXT-X-DISCONTINUITY\n#EXTINF:5,\nafter\n#EXT-X-ENDLIST\n"
        val fingerprints = uris.map(::uriHash)
        val rule = KnownHlsAdGroup("🎬-爱奇艺-", fingerprints, List(4) { 4.0 } + 1.566667)
        val result = filterKnownHlsAd("🎬-爱奇艺-", playlist, rule)!!
        assertEquals(listOf(MediaInterval(5000, 22567)), result.removed)
        assertTrue(result.playlist.contains("before"))
        assertTrue(result.playlist.contains("after"))
        assertNull(filterKnownHlsAd("other", playlist, rule))
        assertNull(filterKnownHlsAd("🎬-爱奇艺-", playlist.replace("known-2", "unknown"), rule))
        assertNull(filterKnownHlsAd("🎬-爱奇艺-", playlist.replace("after\n", "known-0\n"), rule))
    }

    @Test fun capturedIqiyiPlaylistPreservesFractionalTiming() {
        val rule = KnownHlsAdGroup("🎬-爱奇艺-", (0 until 5).map { uriHash("known-$it") }, List(4) { 4.0 } + 1.566667)
        val result = filterKnownHlsAd("🎬-爱奇艺-", iqiyiHlsFixture, rule)!!
        assertEquals(listOf(MediaInterval(1_108_800, 1_126_367)), result.removed)
        assertTrue(result.playlist.contains("content-0.ts"))
    }

    @Test fun explicitIvKeyStateSurvivesRemovedNoneGroup() {
        val key = "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x00000000000000000000000000000001"
        val input = "#EXTM3U\n$key\n#EXTINF:3,\na.ts\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:2,\nad.ts\n$key\n#EXTINF:3,\nb.ts\n#EXT-X-ENDLIST\n"
        val result = filterHlsAds(input) { it == "ad.ts" }!!
        assertFalse(result.playlist.contains("METHOD=NONE"))
        assertEquals(1, result.playlist.lines().count { it == key })
        assertTrue(result.playlist.contains("#EXTINF:3,\nb.ts"))
        assertNull(filterHlsAds(input.replace(",IV=0x00000000000000000000000000000001", "")) { it == "ad.ts" })
        assertNull(filterHlsAds(input.replace("AES-128", "SAMPLE-AES")) { it == "ad.ts" })
    }

    @Test fun knownPlainAssetCanEndEncryptedVodWithoutChangingKeptKey() {
        val key = "#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x00000000000000000000000000000001"
        val input = "#EXTM3U\n$key\n#EXTINF:3,\ncontent\n#EXT-X-DISCONTINUITY\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:5,\nad\n#EXT-X-ENDLIST\n"
        val rule = KnownHlsAdGroup("test", listOf(uriHash("ad")), listOf(5.0), allowEnd = true)
        val result = filterKnownHlsAd("test", input, rule)!!
        assertEquals(listOf(MediaInterval(3000, 8000)), result.removed)
        assertTrue(result.playlist.contains(key))
        assertFalse(result.playlist.contains("METHOD=NONE"))
        assertFalse(result.playlist.contains("\nad\n"))
        assertNull(filterKnownHlsAd("test", input, rule.copy(allowEnd = false)))
        assertEquals("https://media.test/a/stream.m3u8", singleHlsVariant("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nstream.m3u8\n", "https://media.test/a/master.m3u8"))
        assertNull(singleHlsVariant("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\na.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=200\nb.m3u8\n", "https://media.test/master.m3u8"))
    }

    @Test fun capturedEncryptedVodPreservesEveryNormalSegmentAndKey() {
        val rule = KnownHlsAdGroup("360", (0..3).map { uriHash("ad-$it.ts") }, listOf(5.0, 5.0, 5.0, 2.567), allowEnd = true)
        val result = filterKnownHlsAd("360", encryptedHlsFixture, rule)!!
        assertEquals(listOf(MediaInterval(851320, 868887), MediaInterval(1654727, 1672294), MediaInterval(2925254, 2942821)), result.removed)
        val original = parseHlsVod(encryptedHlsFixture)!!
        val filtered = parseHlsVod(result.playlist)!!
        assertEquals(1457, original.segments.size)
        assertEquals(original.segments.filter { it.uri.startsWith("content-") }, filtered.segments)
        assertTrue(filtered.segments.all { it.encrypted })
        assertEquals(851320L, result.timeline.videoToEffective(868887).timeMs)
        assertEquals(1672294L, result.timeline.effectiveToVideo(1637160))
        assertNull(filterKnownHlsAd("360", encryptedHlsFixture.replaceFirst("ad-2.ts", "changed.ts"), rule))
    }

    @Test fun trailingDiscontinuityOfRealVodIsPreserved() {
        val p = "#EXTM3U\n#EXTINF:2,\ncontent\n#EXT-X-DISCONTINUITY\n#EXTINF:3,\nad\n#EXT-X-DISCONTINUITY\n#EXT-X-ENDLIST\n"
        val rule = KnownHlsAdGroup("source", listOf(uriHash("ad")), listOf(3.0), allowEnd = true)
        val result = filterKnownHlsAd("source", p, rule)!!
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
