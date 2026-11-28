package art.anjing.tigertv.ui.player

import art.anjing.tigertv.core.productDefaults

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import art.anjing.tigertv.core.MediaTimeline
import art.anjing.tigertv.core.HlsFilterResult
import art.anjing.tigertv.core.filterSmartHlsAds
import art.anjing.tigertv.core.hlsVideoVariants
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

internal fun mayFilterHls(uri: Uri): Boolean = uri.path?.endsWith(".m3u8", ignoreCase = true) == true

internal class HlsPlaybackSession(val uri: Uri, val filterAds: Boolean = productDefaults.filterAds) {
    @Volatile var timeline: MediaTimeline? = null
        private set
    @Volatile var videoVariants: Map<Uri, Boolean> = emptyMap()
        private set
    private val variantTimelines = ConcurrentHashMap<Uri, MediaTimeline>()

    fun registerVideoVariants(variants: Map<Uri, Boolean>) {
        variantTimelines.clear()
        videoVariants = variants
        timeline = null
    }

    fun updateTimeline(playlist: Uri, value: MediaTimeline?) {
        if (videoVariants[playlist] == true) {
            if (value == null) variantTimelines.remove(playlist) else variantTimelines[playlist] = value
        }
        timeline = value
    }

    fun selectVideoVariant(playlist: Uri) {
        if (playlist in videoVariants) timeline = variantTimelines[playlist]
    }
}

@OptIn(UnstableApi::class)
internal class HlsAdDataSource(
    private val upstream: DataSource,
    private val session: HlsPlaybackSession?,
    private val filter: ((String) -> HlsFilterResult?)? = null,
) : DataSource {
    private var bytes: ByteArray? = null
    private var position = 0
    private var resolved: Uri? = null
    private var delegated = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val current = session
        val variant = current?.videoVariants?.get(dataSpec.uri)
        val fullRequest = dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong()
        delegated = current == null || !current.filterAds || !mayFilterHls(current.uri) ||
            (dataSpec.uri != current.uri && variant != true) || !fullRequest
        if (delegated) {
            val length = upstream.open(dataSpec)
            if (variant == false && fullRequest) current.selectVideoVariant(dataSpec.uri)
            return length
        }
        val playback = checkNotNull(current)
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
            playback.updateTimeline(dataSpec.uri, null)
            delegated = true
            return upstream.open(dataSpec)
        }
        val filterStart = android.os.SystemClock.elapsedRealtimeNanos()
        val original = collected.toByteArray()
        val text = original.toString(Charsets.UTF_8)
        val master = text.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF:") }
        if (master && dataSpec.uri == playback.uri) {
            playback.registerVideoVariants(hlsVideoVariants(text, resolved.toString()).mapKeys { Uri.parse(it.key) })
        }
        val filtered = try {
            if (master) null else if (filter != null) filter(text)
            else filterSmartHlsAds(text, resolved.toString())
        } catch (_: IllegalArgumentException) { null }
        android.util.Log.d("TigerTVHls", "matched=${filtered != null} filterMs=${(android.os.SystemClock.elapsedRealtimeNanos() - filterStart) / 1_000_000.0}")
        if (!master) playback.updateTimeline(dataSpec.uri, filtered?.timeline)
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
