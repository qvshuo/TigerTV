package art.anjing.tigertv.ui.player

import android.content.Context
import android.content.SharedPreferences
import art.anjing.tigertv.core.DanmakuChoice
import art.anjing.tigertv.ui.viewmodel.TigerTVViewModel.PlaybackSession
import kotlinx.serialization.json.Json

internal const val DEFAULT_DANMAKU_SERVER = ""

/** Length framing avoids collisions; hash keeps the service token out of preference keys. */
internal fun danmakuPreferenceKey(server: String, workId: String, site: String, vararg episodeIdentity: String): String {
    val parts = listOf(server.trim().trimEnd('/'), workId, site) + episodeIdentity
    val value = parts.joinToString("") { "${it.length}:$it" }
    return "manual-title:" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}


internal class DanmakuPreferences(context: Context) {
    private val store = context.applicationContext.getSharedPreferences("danmaku", Context.MODE_PRIVATE)

    var server: String
        get() = store.getString("server", DEFAULT_DANMAKU_SERVER).orEmpty()
        set(value) { store.edit().putString("server", value).apply() }

    fun observeServer(onChange: (String) -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "server") onChange(server)
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        onChange(server)
        return { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun forEpisode(session: PlaybackSession?) = Episode(store, session)

    class Episode(private val store: SharedPreferences, session: PlaybackSession?) {
        private val workId = session?.workId.orEmpty()
        private val sourceKey = session?.sourceKey.orEmpty()
        private val legacySite = session?.site?.takeIf { session.legacySite }
        private val legacyEpisode = legacySite != null && session?.legacyEpisode == true
        private val line = session?.line.orEmpty()
        private val name = session?.episode.orEmpty()
        private val index = session?.index.toString()
        private val enabledKey = enabledKey(sourceKey, line, name, index)

        private fun enabledKey(vararg identity: String) = "enabled:" +
            (listOf(workId) + identity).joinToString("") { "${it.length}:$it" }

        var enabled: Boolean
            get() = store.getBoolean(enabledKey,
                if (legacyEpisode) store.getBoolean(enabledKey(legacySite.orEmpty(), line, name), true) else true)
            set(value) { store.edit().putBoolean(enabledKey, value).apply() }

        private fun titleKey(server: String) = danmakuPreferenceKey(server, workId, sourceKey)
        private fun episodeKey(server: String) = danmakuPreferenceKey(server, workId, sourceKey, line, name, index)

        fun rememberTitle(server: String, id: Long) {
            store.edit().putLong(titleKey(server), id).apply()
        }

        fun preferredTitle(server: String): Long? =
            store.getLong(titleKey(server), -1).takeIf { it > 0 }
                ?: legacySite?.let { site -> store.getLong(danmakuPreferenceKey(server, workId, site), -1).takeIf { it > 0 } }

        fun rememberEpisode(server: String, choice: DanmakuChoice) {
            store.edit().putString(episodeKey(server), Json.encodeToString(choice)).apply()
        }

        fun preferredEpisode(server: String): DanmakuChoice? =
            (store.getString(episodeKey(server), null) ?: if (legacyEpisode)
                store.getString(danmakuPreferenceKey(server, workId, legacySite.orEmpty(), line, name), null)
                else null)?.let { runCatching { Json.decodeFromString<DanmakuChoice>(it) }.getOrNull() }
    }
}
