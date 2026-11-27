package art.anjing.tigertv.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class DanmakuSelectionRules(
    val render: DanmakuRenderRules,
    val platformPriority: List<List<String>>,
    val durationDivisor: Long,
    val minimumToleranceMs: Long,
    val maximumToleranceMs: Long,
    val minimumCountRatio: Long,
    val matchThreshold: Double,
    val episodeMatchThreshold: Double,
    val minimumFuzzyLength: Int,
    val ambiguityGap: Double,
    val searchTimeoutMs: Long,
    val episodesTimeoutMs: Long,
    val trackTimeoutMs: Long,
    val manualTrackTimeoutMs: Long,
    val autoTimeoutMs: Long,
    val requestBudget: Int,
    val textPatterns: DanmakuTextPatterns,
)
@Serializable
internal data class DanmakuTextPatterns(
    val numericSeparator: String,
    val season: String,
    val ordinal: String,
    val englishEpisode: String,
    val date: String,
    val part: String,
    val variantPart: String,
    val numberedPart: String,
    val special: String,
    val bonus: String,
    val blooper: String,
    val metadata: String,
    val sourceSuffix: String,
    val year: String,
    val alias: String,
)
internal val selectionRules by lazy { Json.decodeFromString<DanmakuSelectionRules>(danmakuSelectionJson) }

/** Probe order only; this does not identify a show's distributor. */
fun danmakuProbePriority(source: String): Int {
    val platforms = selectionRules.platformPriority
    val normalized = source.lowercase()
    return platforms.indexOfFirst { normalized in it }.takeIf { it >= 0 } ?: platforms.size
}

/** Compare counts only after title/episode matching; duration guards against different cuts. */
fun shouldUpgradeDanmaku(current: DanmakuTrack, next: DanmakuTrack,
    currentCount: Int = current.comments.count { it.mode == 1 },
    nextCount: Int = next.comments.count { it.mode == 1 },
): Boolean {
    val oldDuration = current.referenceDurationMs ?: return false
    val newDuration = next.referenceDurationMs ?: return false
    if (oldDuration <= 0 || newDuration <= 0 || currentCount <= 0) return false
    val rules = selectionRules
    val tolerance = (minOf(oldDuration, newDuration) / rules.durationDivisor)
        .coerceIn(rules.minimumToleranceMs, rules.maximumToleranceMs)
    if (kotlin.math.abs(oldDuration - newDuration) > tolerance) return false
    return nextCount.toLong() >= currentCount.toLong() * rules.minimumCountRatio
}

@Serializable
data class DanmakuRenderRules(
    val lifetimeMs: Long, val areaRatio: Float, val fontRatio: Float, val lineHeight: Float,
    val gapEm: Float, val stroke: Float, val lanes: Int, val visibleLimit: Int,
    val statusFontRatio: Float, val statusDurationMs: Long,
) {
    fun fits(width: Float, previous: Float, incoming: Float, elapsedMs: Long, gap: Float): Boolean {
        if (elapsedMs >= lifetimeMs) return true
        val phase = elapsedMs.toFloat() / lifetimeMs
        return phase * (width + previous) - previous >= gap &&
            width - (1 - phase) * (width + incoming) >= gap
    }
}

val danmakuRenderRules: DanmakuRenderRules get() = selectionRules.render
