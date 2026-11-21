package art.anjing.tigertv.ui.player

import android.content.Context
import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.domain.legacyWorkId

internal class PlaybackPreferences(context: Context) {
    private val store = context.applicationContext.getSharedPreferences("playback", Context.MODE_PRIVATE)

    var autoNext: Boolean
        get() = store.getBoolean("autoNext", true)
        set(value) { store.edit().putBoolean("autoNext", value).apply() }

    var rememberSpeed: Boolean
        get() = store.getBoolean("rememberSpeed", true)
        set(value) { store.edit().putBoolean("rememberSpeed", value).apply() }

    var filterAds: Boolean
        get() = store.getBoolean("filterAds", true)
        set(value) { store.edit().putBoolean("filterAds", value).apply() }

    fun speed(workId: String, sources: List<SearchResult> = emptyList()): Float {
        if (!rememberSpeed) return 1f
        val key = "speed:$workId"
        if (store.contains(key)) return store.getFloat(key, 1f)
        val legacyKey = sources.firstNotNullOfOrNull { source ->
            "speed:${legacyWorkId(source)}".takeIf(store::contains)
        }
        return legacyKey?.let { store.getFloat(it, 1f) } ?: 1f
    }

    fun saveSpeed(workId: String, speed: Float) {
        if (rememberSpeed) store.edit().putFloat("speed:$workId", speed).apply()
    }
}
