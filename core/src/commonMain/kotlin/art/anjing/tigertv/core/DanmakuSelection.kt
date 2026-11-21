package art.anjing.tigertv.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class DanmakuSelectionRules(
    val platformPriority: List<List<String>>,
    val durationDivisor: Long,
    val minimumToleranceMs: Long,
    val maximumToleranceMs: Long,
    val minimumCountRatio: Long,
)
private val selectionRules by lazy { Json.decodeFromString<DanmakuSelectionRules>(danmakuSelectionJson) }

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
