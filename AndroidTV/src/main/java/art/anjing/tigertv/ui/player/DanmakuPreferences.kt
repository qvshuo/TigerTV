package art.anjing.tigertv.ui.player

internal const val DEFAULT_DANMAKU_SERVER = "https://danmu.example.com"

/** Length framing avoids collisions; hash keeps the service token out of preference keys. */
internal fun danmakuPreferenceKey(server: String, workId: String, site: String, vararg episodeIdentity: String): String {
    val parts = listOf(server.trim().trimEnd('/'), workId, site) + episodeIdentity
    val value = parts.joinToString("") { "${it.length}:$it" }
    return "manual-title:" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
