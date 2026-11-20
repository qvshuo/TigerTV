package art.anjing.tigertv.ui.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import art.anjing.tigertv.core.supportsHlsAdFiltering
import art.anjing.tigertv.core.MediaTimeline
import art.anjing.tigertv.core.HlsFilterResult
import art.anjing.tigertv.core.filterKnownHlsAds
import art.anjing.tigertv.core.singleHlsVariant
import java.io.ByteArrayOutputStream

internal fun mayFilterHls(site: String, uri: Uri): Boolean =
    supportsHlsAdFiltering(site) && uri.path?.endsWith(".m3u8", ignoreCase = true) == true

/** Bound to one media item. A new playback session gets a new instance. */
internal class HlsPlaybackSession(val uri: Uri, val site: String, val filterAds: Boolean = true) {
    @Volatile var timeline: MediaTimeline? = null
    @Volatile var childPlaylist: Uri? = null
}

@OptIn(UnstableApi::class)
internal class HlsAdDataSource(
    private val upstream: DataSource,
    private val session: HlsPlaybackSession?,
    private val filter: (String, String) -> HlsFilterResult? = ::filterKnownHlsAds,
) : DataSource {
    private var bytes: ByteArray? = null
    private var position = 0
    private var resolved: Uri? = null
    private var delegated = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val current = session
        delegated = current == null || !current.filterAds || !mayFilterHls(current.site, current.uri) ||
            (dataSpec.uri != current.uri && dataSpec.uri != current.childPlaylist) || dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()
        if (delegated) return upstream.open(dataSpec)
        upstream.open(dataSpec)
        resolved = upstream.uri ?: dataSpec.uri
        val collected = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var tooLarge = false
        try {
            while (true) {
                val count = upstream.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                if (collected.size() + count > 10 * 1024 * 1024) { tooLarge = true; break }
                collected.write(buffer, 0, count)
            }
        } finally { upstream.close() }
        if (tooLarge) {
            current!!.timeline = null
            delegated = true
            return upstream.open(dataSpec)
        }
        val filterStart = android.os.SystemClock.elapsedRealtimeNanos()
        val original = collected.toByteArray()
        val text = original.toString(Charsets.UTF_8)
        val master = text.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF:") }
        if (master && dataSpec.uri == current!!.uri) {
            current.childPlaylist = singleHlsVariant(text, resolved.toString())?.let(Uri::parse)
            current.timeline = null
        }
        val filtered = try {
            if (master) null else filter(current!!.site, text)
        } catch (_: IllegalArgumentException) { null }
        android.util.Log.d("TigerTVHls", "source=${current!!.site} matched=${filtered != null} filterMs=${(android.os.SystemClock.elapsedRealtimeNanos() - filterStart) / 1_000_000.0}")
        if (!master) current.timeline = filtered?.timeline
        bytes = filtered?.playlist?.toByteArray(Charsets.UTF_8) ?: original
        position = 0
        return bytes!!.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (delegated) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        val data = bytes ?: return C.RESULT_END_OF_INPUT
        if (position >= data.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, data.size - position)
        data.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }
    override fun getUri(): Uri? = if (delegated) upstream.uri else resolved
    override fun getResponseHeaders(): Map<String, List<String>> = if (delegated) upstream.responseHeaders else emptyMap()
    override fun close() {
        if (delegated) upstream.close()
        bytes = null
        resolved = null
    }
}
