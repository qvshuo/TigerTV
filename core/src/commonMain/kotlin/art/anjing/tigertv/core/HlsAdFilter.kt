package art.anjing.tigertv.core

import kotlin.math.roundToLong

data class HlsFilterResult(val playlist: String, val removed: List<MediaInterval>) {
    val timeline: MediaTimeline get() = MediaTimeline(removed)
}

internal data class HlsSegment(
    val uri: String, val extinf: String, val micros: Long,
    val discontinuity: Boolean, val key: String?, val encrypted: Boolean,
)
internal data class HlsVod(val headers: List<String>, val segments: List<HlsSegment>, val trailingDiscontinuity: Boolean)

/** Restricted VOD parser. Unknown tags, implicit IVs and DRM return null. */
internal fun parseHlsVod(playlist: String): HlsVod? {
    val lines = playlist.replace("\r\n", "\n").split('\n').filter { it.isNotEmpty() }
    if (lines.firstOrNull() != "#EXTM3U" || lines.lastOrNull() != "#EXT-X-ENDLIST") return null
    val headers = mutableListOf<String>()
    val segments = mutableListOf<HlsSegment>()
    var key: String? = null
    var encrypted = false
    var discontinuity = false
    var extinf: String? = null
    var micros = 0L
    var total = 0L
    for ((index, line) in lines.withIndex()) {
        when {
            line == "#EXTM3U" && index == 0 -> headers.add(line)
            line == "#EXT-X-ENDLIST" && index == lines.lastIndex -> if (extinf != null) return null
            line.startsWith("#EXT-X-KEY:") -> {
                if (extinf != null) return null
                val attributes = Regex("([A-Z-]+)=(\"[^\"]*\"|[^,]+)").findAll(line.substringAfter(':')).toList()
                if (attributes.joinToString(",") { it.value } != line.substringAfter(':')) return null
                val values = attributes.associate { it.groupValues[1] to it.groupValues[2] }
                if (values.size != attributes.size) return null
                val valid = when (values["METHOD"]) {
                    "NONE" -> values.keys == setOf("METHOD")
                    "AES-128" -> values.keys == setOf("METHOD", "URI", "IV") &&
                        values["URI"]!!.let { it.startsWith('"') && it.endsWith('"') && it.length > 2 } &&
                        Regex("0[xX][0-9a-fA-F]{32}").matches(values["IV"]!!)
                    else -> false
                }
                if (!valid) return null
                key = line
                encrypted = values["METHOD"] == "AES-128"
            }
            line == "#EXT-X-DISCONTINUITY" -> {
                if (discontinuity || extinf != null) return null
                discontinuity = true
            }
            line.startsWith("#EXTINF:") -> {
                if (extinf != null || !line.contains(',')) return null
                val seconds = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: return null
                if (!seconds.isFinite() || seconds <= 0 || seconds > 86400) return null
                micros = (seconds * 1_000_000).roundToLong()
                if (micros <= 0 || kotlin.math.abs(seconds * 1_000_000 - micros) > 0.000001 || total > Long.MAX_VALUE - micros - 500) return null
                total += micros
                extinf = line
            }
            !line.startsWith('#') -> {
                val duration = extinf ?: return null
                segments.add(HlsSegment(line, duration, micros, discontinuity, key, encrypted))
                extinf = null
                discontinuity = false
            }
            segments.isEmpty() && extinf == null && (line.startsWith("#EXT-X-VERSION:") || line.startsWith("#EXT-X-TARGETDURATION:") || line == "#EXT-X-PLAYLIST-TYPE:VOD" || line == "#EXT-X-MEDIA-SEQUENCE:0") -> headers.add(line)
            else -> return null
        }
    }
    return HlsVod(headers, segments, discontinuity).takeIf { segments.isNotEmpty() }
}

/** Returns null when unchanged or unsupported, so the caller retains the original response. */
fun filterHlsAds(playlist: String, matches: (String) -> Boolean): HlsFilterResult? =
    parseHlsVod(playlist)?.let { filterHlsVod(it, matches) }

internal fun filterHlsVod(vod: HlsVod, matches: (String) -> Boolean): HlsFilterResult? {
    val output = vod.headers.toMutableList()
    val removed = mutableListOf<MediaInterval>()
    var total = 0L
    var pendingDiscontinuity = false
    var outputKey: String? = null
    var kept = false
    for (segment in vod.segments) {
        pendingDiscontinuity = pendingDiscontinuity || segment.discontinuity
        val start = (total + 500) / 1000
        total += segment.micros
        val end = (total + 500) / 1000
        if (end <= start) return null
        if (matches(segment.uri)) removed.add(MediaInterval(start, end))
        else {
            if (pendingDiscontinuity && kept) output.add("#EXT-X-DISCONTINUITY")
            if (segment.key != outputKey) {
                segment.key?.let(output::add)
                outputKey = segment.key
            }
            output.add(segment.extinf)
            output.add(segment.uri)
            kept = true
            pendingDiscontinuity = false
        }
    }
    if (!kept || removed.isEmpty()) return null
    if (vod.trailingDiscontinuity) output.add("#EXT-X-DISCONTINUITY")
    output.add("#EXT-X-ENDLIST")
    return HlsFilterResult(output.joinToString("\n", postfix = "\n"), MediaTimeline(removed).ads)
}
