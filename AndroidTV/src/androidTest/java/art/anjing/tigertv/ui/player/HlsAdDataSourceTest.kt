package art.anjing.tigertv.ui.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import art.anjing.tigertv.core.filterHlsAds

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class HlsAdDataSourceTest {
    private val uri = Uri.parse("https://example.test/video.m3u8")
    private fun playlist(): String {
        val ad = (0..8).joinToString("") { "#EXTINF:${if (it == 8) "1.76" else "3.0"},\n/video/adjump/time/$it.ts\n" }
        return "#EXTM3U\n#EXTINF:3,\nbefore.ts\n#EXT-X-DISCONTINUITY\n${ad}#EXT-X-DISCONTINUITY\n#EXTINF:3,\nafter.ts\n#EXT-X-ENDLIST\n"
    }
    private class Fake(private val data: ByteArray, private val address: Uri) : DataSource {
        var position = 0
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long { position = 0; return data.size.toLong() }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position == data.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, data.size - position)
            data.copyInto(buffer, offset, position, position + count); position += count
            return count
        }
        override fun getUri(): Uri = address
        override fun close() = Unit
    }
    private fun read(source: DataSource, address: Uri = uri): String {
        source.open(DataSpec(address))
        val out = ByteArrayOutputStream(); val buffer = ByteArray(128)
        while (true) { val count = source.read(buffer, 0, buffer.size); if (count == C.RESULT_END_OF_INPUT) break; out.write(buffer, 0, count) }
        source.close()
        return out.toString("UTF-8")
    }
    @Test fun filtersSameResponseAndBindsTimelineToSession() {
        val old = HlsPlaybackSession(uri, "🎬暴风资源")
        val next = HlsPlaybackSession(uri, "🎬暴风资源")
        val filtered = read(HlsAdDataSource(Fake(playlist().toByteArray(), uri), old) { _, text ->
            filterHlsAds(text) { it.startsWith("/video/adjump/time/") }
        })
        assertFalse(filtered.contains("/video/adjump/time/"))
        assertTrue(filtered.contains("before.ts")); assertTrue(filtered.contains("after.ts"))
        assertEquals(28_760L, old.timeline!!.effectiveToVideo(3_000))
        assertNull(next.timeline)
        val changed = playlist().replace("/video/adjump/time/", "/new-content/")
        val refreshed = read(HlsAdDataSource(Fake(changed.toByteArray(), uri), old) { _, text ->
            filterHlsAds(text) { it.startsWith("/video/adjump/time/") }
        })
        assertEquals(changed, refreshed)
        assertNull(old.timeline)
    }
    @Test fun switchingFilteringOffKeepsOriginalBytesAndTimeline() {
        val session = HlsPlaybackSession(uri, "🎬暴风资源", filterAds = false)
        val input = playlist()
        val source = HlsAdDataSource(Fake(input.toByteArray(), uri), session) { _, _ ->
            fail("Disabled filtering must not invoke the parser")
            null
        }
        assertEquals(input, read(source))
        assertNull(session.timeline)
    }

    @Test fun unsupportedTagsAndOtherSourcesReturnOriginal() {
        val input = playlist().replace("#EXTM3U", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"")
        val session = HlsPlaybackSession(uri, "🎬暴风资源")
        assertEquals(input, read(HlsAdDataSource(Fake(input.toByteArray(), uri), session)))
        assertNull(session.timeline)
        val other = HlsPlaybackSession(uri, "🎬360 资源")
        assertEquals(playlist(), read(HlsAdDataSource(Fake(playlist().toByteArray(), uri), other)))
        assertNull(other.timeline)
    }
    @Test fun singleVariantIsBoundToCurrentMasterResponse() {
        val session = HlsPlaybackSession(uri, "🎬360 资源")
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nchild/index.m3u8\n"
        assertEquals(master, read(HlsAdDataSource(Fake(master.toByteArray(), uri), session)))
        val child = Uri.parse("https://example.test/child/index.m3u8")
        assertEquals(child, session.childPlaylist)
        val filtered = read(HlsAdDataSource(Fake(playlist().toByteArray(), child), session) { _, text ->
            filterHlsAds(text) { it.startsWith("/video/adjump/time/") }
        }, child)
        assertFalse(filtered.contains("/video/adjump/time/"))
        assertNotNull(session.timeline)
        val changed = master + "#EXT-X-STREAM-INF:BANDWIDTH=2000\nother.m3u8\n"
        assertEquals(changed, read(HlsAdDataSource(Fake(changed.toByteArray(), uri), session)))
        assertNull(session.childPlaylist)
        assertNull(session.timeline)
    }

    @Test fun moduAnimeRemainsUnfilteredEvenWithMatchingCandidate() {
        val session = HlsPlaybackSession(uri, "🎬魔都动漫")
        var invoked = false
        assertEquals(playlist(), read(HlsAdDataSource(Fake(playlist().toByteArray(), uri), session) { _, text ->
            invoked = true
            filterHlsAds(text) { it.startsWith("/video/adjump/time/") }
        }))
        assertFalse(invoked)
        assertNull(session.timeline)
    }

}
