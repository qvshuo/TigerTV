package art.anjing.tigertv.ui.player

/** Length framing avoids collisions; hash keeps the service token out of preference keys. */
internal fun danmakuPreferenceKey(server: String, workId: String, site: String): String {
    val value = listOf(server.trim().trimEnd('/'), workId, site).joinToString("") { "${it.length}:$it" }
    return "manual-title:" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
