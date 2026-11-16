package art.anjing.tigertv.core

import kotlin.math.roundToLong

/** Half-open interval on the original media timeline, in milliseconds. */
data class MediaInterval(val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0 && endMs > startMs) }
}

/** An explicitly verified piece of the effective-to-reference mapping. No extrapolation is performed. */
data class ReferenceSpan(
    val effectiveStartMs: Long,
    val effectiveEndMs: Long,
    val referenceStartMs: Long,
    val referenceEndMs: Long,
) {
    init {
        require(effectiveStartMs >= 0 && effectiveEndMs > effectiveStartMs)
        require(referenceStartMs >= 0 && referenceEndMs > referenceStartMs)
    }
}

/** Timeline calculations only; callers decide whether an interval is an ad. */
class MediaTimeline(ads: List<MediaInterval>, referenceSpans: List<ReferenceSpan> = emptyList()) {
    val ads: List<MediaInterval> = ads.sortedBy { it.startMs }.fold(mutableListOf<MediaInterval>()) { result, next ->
        val last = result.lastOrNull()
        if (last != null && next.startMs <= last.endMs) {
            result[result.lastIndex] = MediaInterval(last.startMs, maxOf(last.endMs, next.endMs))
        } else result.add(next)
        result
    }.toList()
    private val references = referenceSpans.sortedBy { it.effectiveStartMs }.also { spans ->
        require(spans.zipWithNext().all { (a, b) -> a.effectiveEndMs <= b.effectiveStartMs && a.referenceEndMs <= b.referenceStartMs })
    }

    /** Advertising collapses onto its start; [insideAd] keeps that ambiguity explicit. */
    fun videoToEffective(videoMs: Long): EffectivePosition {
        require(videoMs >= 0)
        var removed = 0L
        for (ad in ads) {
            if (videoMs < ad.startMs) break
            if (videoMs < ad.endMs) return EffectivePosition(ad.startMs - removed, true)
            removed += ad.endMs - ad.startMs
        }
        return EffectivePosition(videoMs - removed, false)
    }

    /** At a collapsed ad boundary choose its end, so resuming does not re-enter the ad. */
    fun effectiveToVideo(effectiveMs: Long): Long {
        require(effectiveMs >= 0)
        var result = effectiveMs
        for (ad in ads) {
            if (result < ad.startMs) break
            val length = ad.endMs - ad.startMs
            require(result <= Long.MAX_VALUE - length)
            result += length
        }
        return result
    }

    /** Null means the reference relationship is unknown, including gaps and removed scenes. */
    fun effectiveToReference(effectiveMs: Long): Long? {
        require(effectiveMs >= 0)
        val span = references.firstOrNull { effectiveMs >= it.effectiveStartMs && effectiveMs < it.effectiveEndMs } ?: return null
        val fraction = (effectiveMs - span.effectiveStartMs).toDouble() / (span.effectiveEndMs - span.effectiveStartMs)
        return span.referenceStartMs + (fraction * (span.referenceEndMs - span.referenceStartMs)).roundToLong()
    }

    /** Reference gaps represent unknown or absent content; they cannot be mapped safely. */
    fun referenceToEffective(referenceMs: Long): Long? {
        require(referenceMs >= 0)
        val span = references.firstOrNull { referenceMs >= it.referenceStartMs && referenceMs < it.referenceEndMs } ?: return null
        val fraction = (referenceMs - span.referenceStartMs).toDouble() / (span.referenceEndMs - span.referenceStartMs)
        return span.effectiveStartMs + (fraction * (span.effectiveEndMs - span.effectiveStartMs)).roundToLong()
    }

}

data class EffectivePosition(val timeMs: Long, val insideAd: Boolean)
