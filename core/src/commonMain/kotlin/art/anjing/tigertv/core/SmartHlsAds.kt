package art.anjing.tigertv.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlin.math.abs

@Serializable
private data class HlsHeuristics(
    val minSeconds: Double, val maxSeconds: Double, val maxFraction: Double,
    val flankSegments: Int, val pathTokens: Set<String>, val sourceGroups: List<SourceAdGroup>,
)

@Serializable
private data class SourceAdGroup(
    val manifestHost: String, val segmentUri: String, val durationsMicros: List<List<Long>>,
    val repeated: Boolean = false, val allowEnd: Boolean = false, val contentUri: String? = null,
    val requireAllDurations: Boolean = false, val innerDiscontinuityOffsets: Set<Int> = emptySet(),
) {
    @Transient val hostPattern = Regex(manifestHost)
    @Transient val segmentPattern = Regex(segmentUri)
    @Transient val contentPattern = contentUri?.let(::Regex)

    fun contentSide(path: String): Boolean = contentPattern?.matches(path) ?: !segmentPattern.matches(path)
}

private val heuristics by lazy { Json.decodeFromString<HlsHeuristics>(hlsHeuristicsJson) }
private val numbersAtEnd = Regex("[0-9]+(?=\\.[^./]+$)")
private val pathSeparator = Regex("[/._-]")

fun filterSmartHlsAds(playlist: String, playlistHost: String = ""): HlsFilterResult? {
    val vod = parseHlsVod(playlist) ?: return null
    return rewriteHlsVod(vod, smartAdIndices(vod, playlistHost))
}

private fun smartAdIndices(vod: HlsVod, playlistHost: String): Set<Int> {
    val params = heuristics
    val segments = vod.segments
    val total = segments.sumOf { it.micros }.toDouble()
    val accepted = mutableSetOf<Int>()
    var removed = 0.0
    fun propose(start: Int, end: Int, declared: Double? = null, verified: Boolean = false) {
        if (start >= end || (start until end).any { it in accepted }) return
        val duration = segments.subList(start, end).sumOf { it.micros }.toDouble()
        if (duration / 1_000_000 !in params.minSeconds..params.maxSeconds ||
            (!verified && removed + duration > total * params.maxFraction) ||
            (declared != null && abs(duration / 1_000_000 - declared) > 0.25)) return
        accepted.addAll(start until end)
        removed += duration
    }

    // Source profiles verify the shape of a splice independently of its title or URI fingerprints.
    val paths = segments.map { it.uri.substringBefore('?').substringBefore('#') }
    val spliceBoundaries = segments.indices.filter { segments[it].discontinuity } + segments.size
    for (profile in params.sourceGroups) {
        if (!profile.hostPattern.matches(playlistHost)) continue
        val hasInnerBoundary = profile.innerDiscontinuityOffsets.isNotEmpty()
        val candidates = spliceBoundaries.windowed(if (hasInnerBoundary) 3 else 2).mapNotNull { boundaries ->
            val start = boundaries.first()
            val end = boundaries.last()
            if (hasInnerBoundary && boundaries[1] - start !in profile.innerDiscontinuityOffsets) return@mapNotNull null
            val group = segments.subList(start, end)
            val matches = start > 0 && (end != segments.size || profile.allowEnd) &&
                group.map { it.micros } in profile.durationsMicros &&
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
            candidates.groupBy { (start, end) -> paths.subList(start, end) }.values
                .filter { it.size > 1 }.flatten()
        } else candidates
        for ((start, end) in matching) propose(start, end, verified = true)
    }

    // Paired cue boundaries identify a splice even in a continuous encrypted directory.
    var cueStart = -1
    var cueSeconds = 0.0
    for ((index, segment) in segments.withIndex()) {
        if ("#EXT-X-CUE-IN" in segment.tags && cueStart >= 0) {
            propose(cueStart, index, cueSeconds)
            cueStart = -1
        }
        val cue = segment.tags.singleOrNull { it.startsWith("#EXT-X-CUE-OUT:") }
        if (cue != null) {
            val seconds = cue.substringAfter(':').removePrefix("DURATION=").toDoubleOrNull()
            cueStart = if (cueStart < 0 && seconds != null && seconds.isFinite()) index else -1
            cueSeconds = seconds ?: 0.0
        }
    }

    val families = paths.map { numbersAtEnd.replace(it, "") }
    val flank = params.flankSegments
    val adPaths = paths.map { path -> path.lowercase().split(pathSeparator).any { it in params.pathTokens } }
    // Explicit ad paths also occur between content chapters with unrelated URI families.
    // Require ordinary content on both sides and either consistent URI families or paired splice boundaries.
    var start = 0
    while (start < segments.size) {
        if (!adPaths[start]) { start++; continue }
        var end = start + 1
        while (end < segments.size && adPaths[end]) end++
        if (start >= flank && end + flank <= segments.size &&
            (start - flank until start).none { adPaths[it] } &&
            (end until end + flank).none { adPaths[it] }) {
            val sameFamily = (start - flank until start).all { families[it] == families[start - 1] } &&
                (end until end + flank).all { families[it] == families[start - 1] }
            if (sameFamily || (segments[start].discontinuity && segments[end].discontinuity)) propose(start, end)
        }
        start = end
    }

    // A return to encrypted content can delimit a repeated clear insert without discontinuity tags.
    val boundaries = listOf(0) + (1 until segments.size).filter {
        segments[it].discontinuity || segments[it - 1].encrypted != segments[it].encrypted
    } + segments.size
    val repeated = boundaries.zipWithNext().filter { (a, b) ->
        a >= flank && b - a >= 2 && (b == segments.size || b + flank <= segments.size) &&
            (a - flank until a).all { segments[it].encrypted } &&
            (b until minOf(b + flank, segments.size)).all { segments[it].encrypted } &&
            (a until b).none { segments[it].encrypted }
    }.groupBy { (a, b) -> (a until b).map { paths[it] to segments[it].micros } }
    for (runs in repeated.values) if (runs.size > 1) {
        for ((a, b) in runs) propose(a, b)
    }
    return accepted
}
