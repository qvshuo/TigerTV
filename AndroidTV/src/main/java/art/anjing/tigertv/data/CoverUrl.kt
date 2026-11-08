package art.anjing.tigertv.data

import art.anjing.tigertv.core.normalizeUrl

/** Blank posters are absent; URL encoding is shared with API and playback requests. */
fun normalizeCoverUrl(raw: String): String? = normalizeUrl(raw).takeIf { it.isNotEmpty() }
