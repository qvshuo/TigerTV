package art.anjing.tigertv.data

import art.anjing.tigertv.core.normalizeUrl

fun normalizeCoverUrl(raw: String): String? = normalizeUrl(raw).takeIf { it.isNotEmpty() }
