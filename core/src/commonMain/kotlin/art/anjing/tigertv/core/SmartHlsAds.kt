package art.anjing.tigertv.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

@Serializable
private data class HlsHeuristics(
    val common: CommonAdRules, val sourceRules: List<SourceAdGroup>, val noGamblingInSamples: Set<String>,
)

@Serializable
private data class CommonAdRules(val minSeconds: Double, val maxSeconds: Double)

@Serializable
private data class SourceAdGroup(
    val sites: Set<String>,
    val manifestHost: String, val segmentUri: String, val durationsMicros: List<List<Long>> = emptyList(),
    val durationWindowsMicros: List<List<Long>> = emptyList(),
    val repeated: Boolean = false, val allowEnd: Boolean = false, val contentUri: String? = null,
    val requireAllDurations: Boolean = false, val innerDiscontinuityOffsets: Set<Int> = emptySet(),
) {
    @Transient val hostPattern = Regex(manifestHost)
    @Transient val segmentPattern = Regex(segmentUri)
    @Transient val contentPattern = contentUri?.let(::Regex)

    fun contentSide(path: String): Boolean = contentPattern?.matches(path) ?: !segmentPattern.matches(path)
}

private val heuristics by lazy { Json.decodeFromString<HlsHeuristics>(hlsHeuristicsJson) }
fun isRecommendedSource(site: String, filterAds: Boolean = true): Boolean {
    val name = site.replace("🎬", "").trim()
    return name in heuristics.noGamblingInSamples ||
        filterAds && heuristics.sourceRules.any { name in it.sites }
}

fun filterSmartHlsAds(playlist: String, playlistHost: String = ""): HlsFilterResult? {
    val vod = parseHlsVod(playlist) ?: return null
    return rewriteHlsVod(vod, smartAdIndices(vod, playlistHost))
}

private fun smartAdIndices(vod: HlsVod, playlistHost: String): Set<Int> {
    val params = heuristics
    val segments = vod.segments
    val accepted = mutableSetOf<Int>()
    fun propose(start: Int, end: Int) {
        if (start >= end || (start until end).any { it in accepted }) return
        val seconds = segments.subList(start, end).sumOf { it.micros } / 1_000_000.0
        if (seconds !in params.common.minSeconds..params.common.maxSeconds) return
        accepted.addAll(start until end)
    }

    val paths = segments.map { it.uri.substringBefore('?').substringBefore('#') }
    val spliceBoundaries = segments.indices.filter { segments[it].discontinuity } + segments.size
    for (profile in params.sourceRules) {
        if (!profile.hostPattern.matches(playlistHost)) continue
        val hasInnerBoundary = profile.innerDiscontinuityOffsets.isNotEmpty()
        val candidates = spliceBoundaries.windowed(if (hasInnerBoundary) 3 else 2).mapNotNull { boundaries ->
            val start = boundaries.first()
            val end = boundaries.last()
            if (hasInnerBoundary && boundaries[1] - start !in profile.innerDiscontinuityOffsets) return@mapNotNull null
            val group = segments.subList(start, end)
            val matches = start > 0 && (end != segments.size || profile.allowEnd) &&
                (group.map { it.micros } in profile.durationsMicros ||
                    group.size == profile.durationWindowsMicros.size && group.indices.all { index ->
                        val (min, max) = profile.durationWindowsMicros[index]
                        group[index].micros in min..max
                    }) &&
                (start until end).all { !segments[it].encrypted && profile.segmentPattern.matches(paths[it]) } &&
                profile.contentSide(paths[start - 1]) &&
                (end == segments.size || profile.contentSide(paths[end]))
            if (matches) start to end else null
        }
        if (profile.requireAllDurations) {
            val found = candidates.map { (start, end) -> segments.subList(start, end).map { it.micros } }.toSet()
            if (!found.containsAll(profile.durationsMicros)) continue
        }
        val matching = if (profile.repeated) {
            candidates.groupBy { (start, end) -> (start until end).map { paths[it] to segments[it].byteRange } }.values
                .filter { it.size > 1 }.flatten()
        } else candidates
        for ((start, end) in matching) propose(start, end)
    }

    return accepted
}
