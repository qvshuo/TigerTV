package art.anjing.tigertv.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import art.anjing.tigertv.data.Result
import art.anjing.tigertv.data.SearchHistoryStore
import art.anjing.tigertv.data.TigerTVRepository
import art.anjing.tigertv.domain.FetchResponse
import art.anjing.tigertv.domain.SearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TigerTVViewModel(
    private val repository: TigerTVRepository,
    context: Context
) : ViewModel() {

    private val historyStore = SearchHistoryStore(context)

    var keyword by mutableStateOf("")
        private set

    var submittedKeyword by mutableStateOf("")
        private set

    var results by mutableStateOf<List<SearchResult>>(emptyList())
        private set

    var selectedResult by mutableStateOf<SearchResult?>(null)
        private set

    var fetchResponse by mutableStateOf<FetchResponse?>(null)
        private set

    var selectedEpisodeIndex by mutableIntStateOf(-1)
        private set

    var isSearching by mutableStateOf(false)
        private set

    var isFetching by mutableStateOf(false)
        private set

    var isResolvingPlayback by mutableStateOf(false)
        private set

    var configErrorMessage by mutableStateOf<String?>(null)
        private set

    var searchErrorMessage by mutableStateOf<String?>(null)
        private set

    var fetchErrorMessage by mutableStateOf<String?>(null)
        private set

    var playbackErrorMessage by mutableStateOf<String?>(null)
        private set

    var resolvedPlaybackUrl by mutableStateOf<String?>(null)
        private set

    val searchHistory = mutableStateListOf<String>()

    private var searchJob: Job? = null
    private var fetchJob: Job? = null
    private var playbackJob: Job? = null

    init {
        loadConfig()
        loadSearchHistory()
    }

    private fun loadConfig() {
        viewModelScope.launch {
            configErrorMessage = when (val result = repository.loadConfig()) {
                is Result.Success -> null
                is Result.Error -> result.exception.message ?: "Config load failed"
            }
        }
    }

    private fun loadSearchHistory() {
        viewModelScope.launch {
            val saved = historyStore.load()
            searchHistory.clear()
            searchHistory.addAll(saved)
        }
    }

    private fun saveSearchHistory() {
        viewModelScope.launch {
            historyStore.save(searchHistory.toList())
        }
    }

    fun retryLoadConfig() {
        loadConfig()
    }

    fun onKeywordChange(value: String) {
        keyword = value
    }

    fun search(): Boolean {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return false
        searchJob?.cancel()
        fetchJob?.cancel()
        playbackJob?.cancel()
        submittedKeyword = trimmed
        addHistory(trimmed)
        isSearching = true
        isFetching = false
        isResolvingPlayback = false
        searchErrorMessage = null
        fetchErrorMessage = null
        playbackErrorMessage = null
        results = emptyList()
        selectedResult = null
        fetchResponse = null
        selectedEpisodeIndex = -1
        resolvedPlaybackUrl = null

        searchJob = viewModelScope.launch {
            when (val result = repository.search(trimmed)) {
                is Result.Success -> {
                    results = result.data.results
                    isSearching = false
                }
                is Result.Error -> {
                    isSearching = false
                    searchErrorMessage = result.exception.message ?: "Search failed"
                }
            }
        }
        return true
    }

    fun selectResult(result: SearchResult, forceRefresh: Boolean = false) {
        // forceRefresh=true 时跳过"同一结果"短路，专用于重试按钮。
        if (!forceRefresh && selectedResult?.let { it.site == result.site && it.vodId == result.vodId } == true) return
        fetchJob?.cancel()
        playbackJob?.cancel()
        selectedResult = result
        fetchResponse = null
        selectedEpisodeIndex = -1
        resolvedPlaybackUrl = null
        isFetching = true
        isResolvingPlayback = false
        fetchErrorMessage = null
        playbackErrorMessage = null

        fetchJob = viewModelScope.launch {
            when (val fetched = repository.fetch(result.site, result.vodId)) {
                is Result.Success -> {
                    fetchResponse = fetched.data
                    isFetching = false
                }
                is Result.Error -> {
                    isFetching = false
                    fetchErrorMessage = fetched.exception.message ?: "Fetch failed"
                }
            }
        }
    }

    /** 重试上次 fetch：绕过"同一结果"短路，确保下次真正发起请求。 */
    fun retryFetch() {
        val current = selectedResult ?: return
        selectResult(current, forceRefresh = true)
    }

    fun selectEpisode(index: Int) {
        val episodes = fetchResponse?.vodPlayUrl ?: return
        if (index < 0 || index >= episodes.size) return
        selectedEpisodeIndex = index
        playbackJob?.cancel()
        resolvedPlaybackUrl = null
        isResolvingPlayback = true
        playbackErrorMessage = null
        val episode = episodes[index]

        playbackJob = viewModelScope.launch {
            when (val result = repository.resolvePlaybackUrl(episode.url)) {
                is Result.Success -> {
                    resolvedPlaybackUrl = result.data
                    isResolvingPlayback = false
                }
                is Result.Error -> {
                    playbackErrorMessage = result.exception.message ?: "Playback resolution failed"
                    isResolvingPlayback = false
                }
            }
        }
    }

    fun retryPlayback() {
        if (selectedEpisodeIndex >= 0) {
            selectEpisode(selectedEpisodeIndex)
        }
    }

    fun clearPlayback() {
        playbackJob?.cancel()
        resolvedPlaybackUrl = null
        playbackErrorMessage = null
        selectedEpisodeIndex = -1
        isResolvingPlayback = false
    }

    fun clearSearchError() {
        searchErrorMessage = null
    }

    fun clearFetchError() {
        fetchErrorMessage = null
    }

    fun setPlaybackError(message: String) {
        playbackErrorMessage = message
    }

    private fun addHistory(item: String) {
        searchHistory.remove(item)
        searchHistory.add(0, item)
        while (searchHistory.size > MAX_HISTORY) {
            searchHistory.removeAt(searchHistory.size - 1)
        }
        saveSearchHistory()
    }

    fun removeHistory(item: String) {
        searchHistory.remove(item)
        saveSearchHistory()
    }

    fun clearHistory() {
        searchHistory.clear()
        saveSearchHistory()
    }

    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel()
        fetchJob?.cancel()
        playbackJob?.cancel()
    }

    companion object {
        private const val MAX_HISTORY = 20
    }
}

@Suppress("UNCHECKED_CAST")
class TigerTVViewModelFactory(
    private val repository: TigerTVRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TigerTVViewModel::class.java)) {
            return TigerTVViewModel(repository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
