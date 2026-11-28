package art.anjing.tigertv

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

internal class PlaybackPreferencesBackup : ExternalResource() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val stores = listOf("danmaku", "playback").map { context.getSharedPreferences(it, 0) }
    private lateinit var original: List<Map<String, *>>

    override fun before() {
        original = stores.map { it.all }
    }

    override fun after() {
        val restored = stores.zip(original).map { (store, values) ->
            val editor = store.edit().clear()
            for ((key, value) in values) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> error("Unsupported SharedPreferences value for $key")
                }
            }
            editor.commit()
        }
        check(restored.all { it }) { "Unable to restore playback test preferences" }
    }
}
