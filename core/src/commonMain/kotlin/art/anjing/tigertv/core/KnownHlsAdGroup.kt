package art.anjing.tigertv.core

import kotlin.math.roundToLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Reviewed sources for the playlist adapter. */
fun supportsHlsAdFiltering(site: String): Boolean = knownHlsRules.any { it.site == site }

internal val knownHlsRules: List<KnownHlsAdGroup> by lazy { Json.decodeFromString(hlsAdRulesJson) }

/** A known asset's URI fingerprints, not hashes of the downloaded media bytes. */
@Serializable
internal data class KnownHlsAdGroup(
    val site: String,
    val uriFingerprints: List<String>,
    val durations: List<Double>,
    val pathPrefix: String? = null,
    val allowEnd: Boolean = false,
    val continuous: Boolean = false,
)

fun filterKnownHlsAds(site: String, playlist: String): HlsFilterResult? =
    filterKnownHlsAds(site, playlist, knownHlsRules)

internal fun filterKnownHlsAd(site: String, playlist: String, rule: KnownHlsAdGroup): HlsFilterResult? =
    filterKnownHlsAds(site, playlist, listOf(rule))

internal fun filterKnownHlsAds(site: String, playlist: String, rules: List<KnownHlsAdGroup>): HlsFilterResult? {
    val applicable = rules.filter { it.site == site && it.uriFingerprints.isNotEmpty() && it.uriFingerprints.size == it.durations.size }
    if (applicable.isEmpty()) return null
    val vod = parseHlsVod(playlist) ?: return null
    return rewriteHlsVod(vod, exactAdIndices(vod, applicable))
}

internal fun exactAdIndices(vod: HlsVod, applicable: List<KnownHlsAdGroup>): Set<Int> {
    val segments = vod.segments
    val indices = mutableSetOf<Int>()
    val matched = mutableSetOf<String>()
    var validatedCount = 0
    for (i in segments.indices) {
        for (rule in applicable) {
            val n = rule.uriFingerprints.size
            if (i + n > segments.size) continue
            if (rule.continuous) {
                if (i == 0 || i + n == segments.size || segments[i].discontinuity || segments[i + n].discontinuity ||
                    !segments[i].encrypted || segments[i - 1].key != segments[i].key ||
                    segments[i + n].key != segments[i].key) continue
            } else {
                if (!segments[i].discontinuity) continue
                if (i + n == segments.size) { if (!rule.allowEnd) continue }
                else if (!segments[i + n].discontinuity) continue
            }
            val group = segments.subList(i, i+n)
            if (group.withIndex().any { (j, s) ->
                    (if (rule.continuous) s.key != segments[i].key else s.encrypted) ||
                        (j > 0 && s.discontinuity) || s.micros != (rule.durations[j] * 1_000_000).roundToLong() ||
                        s.uri.contains('?') || s.uri.contains('#') ||
                        (rule.pathPrefix != null && !s.uri.startsWith(rule.pathPrefix)) || uriHash(s.uri) != rule.uriFingerprints[j]
                }) continue
            indices.addAll(i until i + n)
            matched.addAll(group.map { it.uri })
            validatedCount += n
            break
        }
    }
    return if (segments.count { it.uri in matched } == validatedCount) indices else emptySet()
}

internal fun uriHash(uri: String): String {
    var hash = 14695981039346656037UL
    for (byte in uri.encodeToByteArray()) hash = (hash xor byte.toUByte().toULong()) * 1099511628211UL
    return hash.toString(16).padStart(16, '0')
}
