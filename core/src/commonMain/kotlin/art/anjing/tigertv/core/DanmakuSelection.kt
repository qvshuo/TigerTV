package art.anjing.tigertv.core

/** Probe order only; this does not identify a show's distributor. */
fun danmakuProbePriority(source: String): Int = when (source.lowercase()) {
    "iqiyi", "qiyi" -> 0
    "youku" -> 1
    "tencent", "qq" -> 2
    "mgtv", "imgo", "mango" -> 3
    else -> 4
}

/** Compare counts only after title/episode matching; duration guards against different cuts. */
fun shouldUpgradeDanmaku(current: DanmakuTrack, next: DanmakuTrack,
    currentCount: Int = current.comments.count { it.mode == 1 },
    nextCount: Int = next.comments.count { it.mode == 1 },
): Boolean {
    val oldDuration = current.referenceDurationMs ?: return false
    val newDuration = next.referenceDurationMs ?: return false
    if (oldDuration <= 0 || newDuration <= 0 || currentCount <= 0) return false
    val tolerance = (minOf(oldDuration, newDuration) / 100).coerceIn(1_000L, 10_000L)
    if (kotlin.math.abs(oldDuration - newDuration) > tolerance) return false
    return nextCount.toLong() >= currentCount.toLong() * 2
}
