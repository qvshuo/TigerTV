package art.anjing.tigertv.core

import kotlin.math.roundToLong

data class HlsFilterResult(val playlist: String, val removed: List<MediaInterval>) {
    val timeline: MediaTimeline get() = MediaTimeline(removed)
}

internal data class HlsSegment(
    val uri: String, val extinf: String, val micros: Long,
    val discontinuity: Boolean, val key: String?, val implicitIv: Boolean,
    val tags: List<String>, val map: HlsMap?, val byteRange: HlsByteRange?,
) {
    val encrypted: Boolean get() = key != null && key != "#EXT-X-KEY:METHOD=NONE"
}

internal data class HlsMap(val tag: String, val key: String?)
internal data class HlsByteRange(val length: Long, val offset: Long) {
    val end: Long get() = offset + length
}

private val rangePattern = Regex("([0-9]+)(?:@([0-9]+))?")
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
private fun byteRange(raw: String, previousEnd: Long? = null): HlsByteRange? {
    val parts = rangePattern.matchEntire(raw)?.groupValues ?: return null
    val length = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
    val offset = if (parts[2].isEmpty()) previousEnd ?: return null else parts[2].toLongOrNull() ?: return null
    if (offset > MAX_SAFE_INTEGER - length) return null
    return HlsByteRange(length, offset)
}

internal data class HlsVod(
    val headers: List<String>, val segments: List<HlsSegment>, val trailingDiscontinuity: Boolean, val sequence: Long,
)

private val keyAttribute = Regex("([A-Z-]+)=(\"[^\"]*\"|[^,]+)")
private val explicitIv = Regex("0[xX][0-9a-fA-F]{32}")
private val vodHeader = Regex("#EXT-X-(?:VERSION:[0-9]+|TARGETDURATION:[0-9]+|MEDIA-SEQUENCE:[0-9]+|PLAYLIST-TYPE:VOD)")
private fun quoted(value: String) = value.length > 2 && value.first() == '"' && value.last() == '"'
private val partialUri = Regex("[0-9a-f]+")

/** A single complete media timeline. Unsupported segment state stays with the upstream playlist. */
internal fun parseHlsVod(playlist: String): HlsVod? {
    val lines = playlist.replace("\r\n", "\n").split('\n').filter { it.isNotEmpty() }
    if (lines.firstOrNull() != "#EXTM3U" || lines.lastOrNull() != "#EXT-X-ENDLIST") return null
    val end = lines.indexOf("#EXT-X-ENDLIST")
    // Some responses append a partial URI and an exact copy of the final discontinuity and segment.
    if (end != lines.lastIndex && (end < 4 || lines.size != end + 6 ||
            !partialUri.matches(lines[end + 1]) || lines[end - 3] != "#EXT-X-DISCONTINUITY" ||
            lines.subList(end - 3, end) != lines.subList(end + 2, lines.lastIndex))) return null
    val headers = mutableListOf("#EXTM3U")
    val segments = mutableListOf<HlsSegment>()
    val tags = mutableListOf<String>()
    var key: String? = null
    var implicitIv = false
    var map: HlsMap? = null
    var range: String? = null
    var discontinuity = false
    var extinf: String? = null
    var micros = 0L
    var total = 0L
    for (line in lines.subList(1, end)) {
        when {
            line.startsWith("#EXT-X-KEY:") -> {
                if (extinf != null) return null
                val raw = line.substringAfter(':')
                val attributes = keyAttribute.findAll(raw).toList()
                if (attributes.joinToString(",") { it.value } != raw) return null
                val values = attributes.associate { it.groupValues[1] to it.groupValues[2] }
                if (values.size != attributes.size) return null
                val iv = values["IV"]
                val valid = when (values["METHOD"]) {
                    "NONE" -> values.keys == setOf("METHOD")
                    "AES-128" -> values.keys.all { it in setOf("METHOD", "URI", "IV", "KEYFORMAT") } &&
                        values["URI"]?.let { it.startsWith('"') && it.endsWith('"') && it.length > 2 } == true &&
                        (values["KEYFORMAT"] == null || values["KEYFORMAT"] == "\"identity\"") &&
                        (iv == null || explicitIv.matches(iv))
                    else -> false
                }
                if (!valid) return null
                key = line
                implicitIv = values["METHOD"] == "AES-128" && iv == null
            }
            line.startsWith("#EXT-X-MAP:") -> {
                if (extinf != null || implicitIv) return null
                val raw = line.substringAfter(':')
                val parts = keyAttribute.findAll(raw).toList()
                val attrs = parts.associate { it.groupValues[1] to it.groupValues[2] }
                if (parts.joinToString(",") { it.value } != raw || attrs.size != parts.size ||
                    attrs.keys.any { it != "URI" && it != "BYTERANGE" }) return null
                val uri = attrs["URI"] ?: return null
                if (!quoted(uri) || uri.substring(1, uri.lastIndex).any(Char::isWhitespace)) return null
                val mapRange = attrs["BYTERANGE"]
                if (mapRange != null && (!quoted(mapRange) || byteRange(mapRange.substring(1, mapRange.lastIndex)) == null)) return null
                map = HlsMap(line, key)
            }
            line.startsWith("#EXT-X-BYTERANGE:") -> {
                if (range != null) return null
                range = line.substringAfter(':')
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
            line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") || line.startsWith("#EXT-X-CUE-OUT:") || line == "#EXT-X-CUE-IN" -> {
                if (extinf != null) return null
                tags.add(line)
            }
            line.startsWith('#') && !line.startsWith("#EXT") -> Unit
            !line.startsWith('#') -> {
                val duration = extinf ?: return null
                if (line.any { it.isWhitespace() }) return null
                val previous = segments.lastOrNull()
                val segmentRange = range?.let { byteRange(it, previous?.takeIf { it.uri == line }?.byteRange?.end) ?: return null }
                segments.add(HlsSegment(line, duration, micros, discontinuity, key, implicitIv, tags.toList(), map, segmentRange))
                range = null
                extinf = null
                discontinuity = false
                tags.clear()
            }
            segments.isEmpty() && extinf == null && vodHeader.matches(line) -> headers.add(line)
            else -> return null
        }
    }
    if (extinf != null || range != null || tags.isNotEmpty() || segments.isEmpty()) return null
    val sequences = headers.filter { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
    if (sequences.size > 1) return null
    val sequence = sequences.singleOrNull()?.substringAfter(':')?.let { it.toLongOrNull() ?: return null } ?: 0
    if (sequence > MAX_SAFE_INTEGER - segments.size) return null
    return HlsVod(headers, segments, discontinuity, sequence)
}

fun filterHlsAds(playlist: String, matches: (String) -> Boolean): HlsFilterResult? =
    parseHlsVod(playlist)?.let { filterHlsVod(it, matches) }

internal fun filterHlsVod(vod: HlsVod, matches: (String) -> Boolean): HlsFilterResult? =
    rewriteHlsVod(vod, vod.segments.indices.filterTo(mutableSetOf()) { matches(vod.segments[it].uri) })

internal fun rewriteHlsVod(vod: HlsVod, dropped: Set<Int>): HlsFilterResult? {
    if (dropped.isEmpty() || dropped.size == vod.segments.size) return null
    val output = vod.headers.toMutableList()
    val removed = mutableListOf<MediaInterval>()
    var total = 0L
    var pendingDiscontinuity = false
    var outputKey: String? = null
    var outputMap: HlsMap? = null
    fun emitKey(key: String?) {
        if (key == outputKey) return
        output.add(key ?: "#EXT-X-KEY:METHOD=NONE")
        outputKey = key
    }
    var kept = false
    var synthesizedIv = false
    for ((index, segment) in vod.segments.withIndex()) {
        pendingDiscontinuity = pendingDiscontinuity || segment.discontinuity
        val start = (total + 500) / 1000
        total += segment.micros
        val end = (total + 500) / 1000
        if (end <= start) return null
        if (index in dropped) {
            removed.add(MediaInterval(start, end))
            pendingDiscontinuity = true
            continue
        }
        if (pendingDiscontinuity && kept) output.add("#EXT-X-DISCONTINUITY")
        val key = if (segment.implicitIv && removed.isNotEmpty()) {
            synthesizedIv = true
            segment.key + ",IV=0x" + (vod.sequence + index).toString(16).padStart(32, '0')
        } else segment.key
        val map = segment.map
        if (map != null && map != outputMap) {
            emitKey(map.key)
            output.add(map.tag)
            outputMap = map
        }
        emitKey(key)
        output.addAll(segment.tags.filterNot { it == "#EXT-X-CUE-IN" && index - 1 in dropped })
        output.add(segment.extinf)
        segment.byteRange?.let { output.add("#EXT-X-BYTERANGE:${it.length}@${it.offset}") }
        output.add(segment.uri)
        kept = true
        pendingDiscontinuity = false
    }
    if (synthesizedIv) {
        val version = vod.headers.indexOfFirst { it.startsWith("#EXT-X-VERSION:") }
        if (version < 0) output.add(1, "#EXT-X-VERSION:2")
        else if ((output[version].substringAfter(':').toIntOrNull() ?: 2) < 2) output[version] = "#EXT-X-VERSION:2"
    }
    if (vod.trailingDiscontinuity) output.add("#EXT-X-DISCONTINUITY")
    output.add("#EXT-X-ENDLIST")
    return HlsFilterResult(output.joinToString("\n", postfix = "\n"), MediaTimeline(removed).ads)
}
