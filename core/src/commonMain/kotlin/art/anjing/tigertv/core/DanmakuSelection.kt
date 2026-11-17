package art.anjing.tigertv.core

/** Probe order only; this does not identify a show's distributor. */
fun danmakuProbePriority(source: String): Int = when (source.lowercase()) {
    "iqiyi", "qiyi" -> 0
    "youku" -> 1
    "tencent", "qq" -> 2
    "mgtv", "imgo", "mango" -> 3
    else -> 4
}

/** Conservative provisional policy; count is never evidence of title/episode identity. */
fun shouldUpgradeDanmaku(current: DanmakuTrack, next: DanmakuTrack,
    currentCount: Int = current.comments.count { it.mode == 1 },
    nextCount: Int = next.comments.count { it.mode == 1 },
): Boolean {
    val oldDuration = current.referenceDurationMs ?: return false
    val newDuration = next.referenceDurationMs ?: return false
    if (oldDuration <= 0 || newDuration <= 0 || kotlin.math.abs(oldDuration - newDuration) > maxOf(30_000L, oldDuration / 50)) return false
    return nextCount - currentCount >= 1_000 && nextCount.toLong() >= currentCount.toLong() * 2
}
