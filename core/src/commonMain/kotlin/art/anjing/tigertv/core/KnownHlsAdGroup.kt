package art.anjing.tigertv.core

import kotlin.math.roundToLong

/** A known asset's URI fingerprints, not hashes of the downloaded media bytes. */
internal data class KnownHlsAdGroup(
    val site: String,
    val uriFingerprints: List<String>,
    val durations: List<Double>,
    val pathPrefix: String? = null,
    val allowEnd: Boolean = false,
)

internal fun filterKnownHlsAd(site: String, playlist: String, rule: KnownHlsAdGroup): HlsFilterResult? =
    filterKnownHlsAds(site, playlist, listOf(rule))

internal fun filterKnownHlsAds(site: String, playlist: String, rules: List<KnownHlsAdGroup>): HlsFilterResult? {
    val applicable = rules.filter { it.site == site && it.uriFingerprints.isNotEmpty() && it.uriFingerprints.size == it.durations.size }
    if (applicable.isEmpty()) return null
    val vod = parseHlsVod(playlist) ?: return null
    val segments = vod.segments
    val matched = mutableSetOf<String>()
    var validatedCount = 0
    for (i in segments.indices) {
        if (!segments[i].discontinuity) continue
        for (rule in applicable) {
            val n = rule.uriFingerprints.size
            if (i + n > segments.size) continue
            if (i + n == segments.size) { if (!rule.allowEnd) continue }
            else if (!segments[i+n].discontinuity) continue
            val group = segments.subList(i, i+n)
            if (group.withIndex().any { (j, s) ->
                    s.encrypted || (j > 0 && s.discontinuity) || s.micros != (rule.durations[j] * 1_000_000).roundToLong() ||
                        s.uri.contains('?') || s.uri.contains('#') ||
                        (rule.pathPrefix != null && !s.uri.startsWith(rule.pathPrefix)) || uriHash(s.uri) != rule.uriFingerprints[j]
                }) continue
            matched.addAll(group.map { it.uri })
            validatedCount += n
            break
        }
    }
    if (matched.isEmpty() || segments.count { it.uri in matched } != validatedCount) return null
    return filterHlsVod(vod) { it in matched }
}

internal fun uriHash(uri: String): String {
    var hash = 14695981039346656037UL
    for (byte in uri.encodeToByteArray()) hash = (hash xor byte.toUByte().toULong()) * 1099511628211UL
    return hash.toString(16).padStart(16, '0')
}
