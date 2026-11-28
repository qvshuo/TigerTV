package art.anjing.tigertv.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

@Serializable
private data class HlsHeuristics(
    val common: CommonAdRules, val strategies: List<AdStrategy>,
)

@Serializable
private data class CommonAdRules(val minSeconds: Double, val maxSeconds: Double)

@Serializable
private data class AdStrategy(
    val id: String, val segmentPath: String, val durationsMicros: List<List<Long>> = emptyList(),
    val durationWindowsMicros: List<List<Long>> = emptyList(),
    val repeated: Boolean = false, val allowEnd: Boolean = false, val contentPath: String? = null,
    val requireAllDurations: Boolean = false, val innerDiscontinuityOffsets: Set<Int> = emptySet(),
) {
    @Transient val segmentPattern = Regex(segmentPath)
    @Transient val contentPattern = contentPath?.let(::Regex)

    fun contentSide(path: String): Boolean = contentPattern?.matches(path) ?: !segmentPattern.matches(path)
}

private val heuristics by lazy { Json.decodeFromString<HlsHeuristics>(hlsHeuristicsJson) }
private val uriAuthority = Regex("^(?:[A-Za-z][A-Za-z0-9+.-]*:)?//[^/]*")
private fun uriPath(uri: String) = uri.substringBefore('?').substringBefore('#').replaceFirst(uriAuthority, "")

private fun segmentPath(uri: String, baseDirectory: String): String {
    val path = uriPath(uri)
    val absolute = if (path.startsWith('/')) path else baseDirectory + path
    val parts = mutableListOf<String>()
    for (part in absolute.drop(1).split('/')) {
        when (part) {
            "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(part)
        }
    }
    return "/" + parts.joinToString("/")
}

fun filterSmartHlsAds(playlist: String, playlistUrl: String = ""): HlsFilterResult? {
    val vod = parseHlsVod(playlist) ?: return null
    return rewriteHlsVod(vod, smartAdIndices(vod, playlistUrl))
}

private fun smartAdIndices(vod: HlsVod, playlistUrl: String): Set<Int> {
    val params = heuristics
    val segments = vod.segments
    val accepted = mutableSetOf<Int>()
    fun propose(start: Int, end: Int) {
        if (start >= end || (start until end).any { it in accepted }) return
        val seconds = segments.subList(start, end).sumOf { it.micros } / 1_000_000.0
        if (seconds !in params.common.minSeconds..params.common.maxSeconds) return
        accepted.addAll(start until end)
    }

    val baseDirectory = uriPath(playlistUrl).substringBeforeLast('/', "") + "/"
    val paths = segments.map { segmentPath(it.uri, baseDirectory) }
    val spliceBoundaries = segments.indices.filter { segments[it].discontinuity } + segments.size
    for (strategy in params.strategies) {
        val hasInnerBoundary = strategy.innerDiscontinuityOffsets.isNotEmpty()
        val candidates = spliceBoundaries.windowed(if (hasInnerBoundary) 3 else 2).mapNotNull { boundaries ->
            val start = boundaries.first()
            val end = boundaries.last()
            if (hasInnerBoundary && boundaries[1] - start !in strategy.innerDiscontinuityOffsets) return@mapNotNull null
            val group = segments.subList(start, end)
            val matches = start > 0 && (end != segments.size || strategy.allowEnd) &&
                (group.map { it.micros } in strategy.durationsMicros ||
                    group.size == strategy.durationWindowsMicros.size && group.indices.all { index ->
                        val (min, max) = strategy.durationWindowsMicros[index]
                        group[index].micros in min..max
                    }) &&
                (start until end).all { !segments[it].encrypted && strategy.segmentPattern.matches(paths[it]) } &&
                strategy.contentSide(paths[start - 1]) &&
                (end == segments.size || strategy.contentSide(paths[end]))
            if (matches) start to end else null
        }
        if (strategy.requireAllDurations) {
            val found = candidates.map { (start, end) -> segments.subList(start, end).map { it.micros } }.toSet()
            if (!found.containsAll(strategy.durationsMicros)) continue
        }
        val matching = if (strategy.repeated) {
            candidates.groupBy { (start, end) -> (start until end).map { paths[it] to segments[it].byteRange } }.values
                .filter { it.size > 1 }.flatten()
        } else candidates
        for ((start, end) in matching) propose(start, end)
    }

    return accepted
}
