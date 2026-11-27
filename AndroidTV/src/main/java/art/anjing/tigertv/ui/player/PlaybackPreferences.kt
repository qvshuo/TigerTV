package art.anjing.tigertv.ui.player

import art.anjing.tigertv.core.productDefaults

import android.content.Context

internal class PlaybackPreferences(context: Context) {
    private val store = context.applicationContext.getSharedPreferences("playback", Context.MODE_PRIVATE)

    var autoNext: Boolean
        get() = store.getBoolean("autoNext", productDefaults.autoNext)
        set(value) { store.edit().putBoolean("autoNext", value).apply() }

    var rememberSpeed: Boolean
        get() = store.getBoolean("rememberSpeed", productDefaults.rememberSpeed)
        set(value) { store.edit().putBoolean("rememberSpeed", value).apply() }

    var filterAds: Boolean
        get() = store.getBoolean("filterAds", productDefaults.filterAds)
        set(value) { store.edit().putBoolean("filterAds", value).apply() }

    fun speed(workId: String): Float = if (rememberSpeed) store.getFloat("speed:$workId", 1f) else 1f

    fun saveSpeed(workId: String, speed: Float) {
        if (rememberSpeed) store.edit().putFloat("speed:$workId", speed).apply()
    }
}
