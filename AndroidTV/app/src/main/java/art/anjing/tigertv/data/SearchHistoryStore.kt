package art.anjing.tigertv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "tigertv_preferences")

class SearchHistoryStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val maxItems: Int = 20
) {
    private val dataStore = context.historyDataStore

    suspend fun load(): List<String> {
        return try {
            val prefs = dataStore.data.first()
            val encoded = prefs[HISTORY_KEY] ?: return emptyList()
            json.decodeFromString<List<String>>(encoded)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun save(history: List<String>) {
        try {
            val trimmed = history.take(maxItems)
            val encoded = json.encodeToString(trimmed)
            dataStore.edit { prefs ->
                prefs[HISTORY_KEY] = encoded
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    companion object {
        private val HISTORY_KEY = stringPreferencesKey("search_history")
    }
}