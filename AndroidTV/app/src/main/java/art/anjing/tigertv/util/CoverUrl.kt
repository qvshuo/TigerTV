package art.anjing.tigertv.util

/**
 * 封面 URL 规范化：仅对非 ASCII 字符做百分号编码（UTF-8），
 * 与 macOS `HTTPClient.percentEncodedURL(from:)` 语义一致。
 * 已编码的 `%XX` 序列与 ASCII 部分保持原样，避免双重编码。
 */
fun normalizeCoverUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (!needsEncoding(trimmed)) return trimmed
    return buildString(trimmed.length + 16) {
        for (byte in trimmed.toByteArray(Charsets.UTF_8)) {
            if (byte < 0) {
                append('%')
                append(HEX[(byte.toInt() shr 4) and 0xF])
                append(HEX[byte.toInt() and 0xF])
            } else {
                append(byte.toInt().toChar())
            }
        }
    }
}

private const val HEX = "0123456789ABCDEF"

private fun needsEncoding(value: String): Boolean = value.any { it.code > 0x7F }
