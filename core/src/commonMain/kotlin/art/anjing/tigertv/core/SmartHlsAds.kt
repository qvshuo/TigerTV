package art.anjing.tigertv.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

@Serializable
private data class HlsHeuristics(
    val minSeconds: Double, val maxSeconds: Double, val maxFraction: Double,
    val flankSegments: Int, val pathTokens: Set<String>,
)

private val heuristics by lazy { Json.decodeFromString<HlsHeuristics>(hlsHeuristicsJson) }
private val numbersAtEnd = Regex("[0-9]+(?=\\.[^./]+$)")
private val pathSeparator = Regex("[/._-]")

fun filterSmartHlsAds(site: String, playlist: String): HlsFilterResult? {
    if (!supportsHlsAdFiltering(site)) return null
    val vod = parseHlsVod(playlist) ?: return null
    return rewriteHlsVod(vod, smartAdIndices(vod, exactAdIndices(vod, knownHlsRules.filter { it.site == site })))
}

internal fun smartAdIndices(vod: HlsVod, exact: Set<Int>): Set<Int> {
    val params = heuristics
    val segments = vod.segments
    val total = segments.sumOf { it.micros }.toDouble()
    val accepted = exact.toMutableSet()
    var removed = exact.sumOf { segments[it].micros }.toDouble()
    fun propose(start: Int, end: Int, declared: Double? = null) {
        if (start >= end || (start until end).any { it in accepted }) return
        val duration = segments.subList(start, end).sumOf { it.micros }.toDouble()
        if (duration / 1_000_000 !in params.minSeconds..params.maxSeconds ||
            removed + duration > total * params.maxFraction ||
            (declared != null && abs(duration / 1_000_000 - declared) > 0.25)) return
        accepted.addAll(start until end)
        removed += duration
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

    val paths = segments.map { it.uri.substringBefore('?').substringBefore('#') }
    val families = paths.map { numbersAtEnd.replace(it, "") }
    val flank = params.flankSegments
    // An ad-labelled path must be bracketed by a stable content URI family.
    var start = 0
    while (start < segments.size) {
        var end = start + 1
        while (end < segments.size && families[end] == families[start]) end++
        if (start >= flank && end + flank <= segments.size &&
            (start - flank until start).all { families[it] == families[start - 1] } &&
            (end until end + flank).all { families[it] == families[start - 1] } &&
            paths[start].lowercase().split(pathSeparator).any { it in params.pathTokens } &&
            paths[start - 1].lowercase().split(pathSeparator).none { it in params.pathTokens }) {
            propose(start, end)
        }
        start = end
    }

    // Repeated short clear inserts between encrypted chapters require two discontinuity edges
    // (or the final ENDLIST), the same URI sequence, and encrypted content on every available side.
    val boundaries = (listOf(0) + segments.indices.filter { it > 0 && segments[it].discontinuity } + segments.size)
    val repeated = boundaries.zipWithNext().filter { (a, b) ->
        a >= flank && b - a >= 2 && (b == segments.size || b + flank <= segments.size) &&
            (a - flank until a).all { segments[it].encrypted } &&
            (b until minOf(b + flank, segments.size)).all { segments[it].encrypted } &&
            (a until b).none { segments[it].encrypted }
    }.groupBy { (a, b) -> paths.subList(a, b) }
    for (runs in repeated.values) if (runs.size > 1) {
        for ((a, b) in runs) propose(a, b)
    }
    return accepted
}
