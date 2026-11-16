package art.anjing.tigertv.core

/** Only the observed single-variant layout; separate tracks and adaptive variants pass through. */
fun singleHlsVariant(playlist: String, baseUrl: String): String? {
    val lines = playlist.replace("\r\n", "\n").split('\n').filter { it.isNotEmpty() }
    if (lines.firstOrNull() != "#EXTM3U" || lines.any { it.startsWith("#EXT-X-MEDIA:") || it.startsWith("#EXT-X-I-FRAME-STREAM-INF:") || it.startsWith("#EXTINF:") }) return null
    val variants = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF:") }
    if (variants.size != 1) return null
    val uri = lines.getOrNull(variants.single() + 1)?.takeIf { !it.startsWith('#') } ?: return null
    return resolveUrl(baseUrl, uri)
}
