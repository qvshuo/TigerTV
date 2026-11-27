package art.anjing.tigertv.core

import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath

private val masterAttribute = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]+)")
private fun masterAttributes(line: String) = masterAttribute.findAll(line.substringAfter(':'))
    .associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") }

private val audioCodecs = setOf("mp4a", "ac-3", "ec-3", "ac-4", "opus", "flac", "alac", "mp3", "mha1", "mhm1")

/** Video URI to whether its timeline can be cut without changing a separate audio rendition. */
fun hlsVideoVariants(playlist: String, baseUrl: String): Map<String, Boolean> {
    val lines = playlist.lineSequence().filter { it.isNotEmpty() }.toList()
    if (lines.firstOrNull() != "#EXTM3U" || lines.any {
        it.startsWith("#EXTINF:") || it.startsWith("#EXT-X-DEFINE:") || it.startsWith("#EXT-X-SESSION-KEY:")
    }) return emptyMap()
    fun resolve(uri: String): String? = uri.takeIf { it.isNotBlank() && !it.startsWith('#') && it.none(Char::isWhitespace) }
        ?.let { value -> runCatching {
            URLBuilder(resolveUrl(baseUrl, value)).apply {
                // Media3 and browsers resolve dot segments before loading the child URI.
                val parts = encodedPath.split('/')
                val path = mutableListOf<String>()
                for ((index, part) in parts.withIndex()) {
                    when (part.lowercase().replace("%2e", ".")) {
                        "." -> if (index == parts.lastIndex) path.add("")
                        ".." -> {
                            if (path.size > 1) path.removeAt(path.lastIndex)
                            if (index == parts.lastIndex) path.add("")
                        }
                        else -> path.add(part)
                    }
                }
                encodedPath = path.joinToString("/")
            }.buildString()
        }.getOrNull() }
    val audio = lines.filter { it.startsWith("#EXT-X-MEDIA:") }.map(::masterAttributes)
        .filter { it["TYPE"] == "AUDIO" && "URI" in it }
    val audioUrls = audio.mapNotNull { it["URI"]?.let(::resolve) }.toSet()
    val audioGroups = audio.mapNotNull { it["GROUP-ID"] }.toSet()
    val variants = mutableMapOf<String, Boolean>()
    for ((index, line) in lines.withIndex()) {
        if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
        val uri = lines.getOrNull(index + 1)?.let(::resolve) ?: return emptyMap()
        val attributes = masterAttributes(line)
        val codecs = attributes["CODECS"]?.split(',')
        if (uri in audioUrls || ("RESOLUTION" !in attributes && "VIDEO" !in attributes &&
                codecs?.all { it.trim().lowercase().substringBefore('.') in audioCodecs } == true)) continue
        val canCut = attributes["AUDIO"] !in audioGroups
        variants[uri] = variants[uri] != false && canCut
    }
    return variants
}
