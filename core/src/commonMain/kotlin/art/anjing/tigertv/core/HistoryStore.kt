package art.anjing.tigertv.core

import art.anjing.tigertv.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

internal class HistoryStore(
    directory: String,
    private val storage: Storage,
    private val clock: () -> Long,
    private val log: suspend (String, Exception) -> Unit,
) {
    private val historyPath = "$directory/search-history.json"
    private val playedSourcesPath = "$directory/last-played-sources.json"

    private fun playedSources(): PlayedSources = cacheRead {
        wireJson.decodeFromString<PlayedSources>(storage.read(playedSourcesPath) ?: "{}")
    } ?: PlayedSources()

    suspend fun snapshot(): PlayedSources = playbackMutex.withLock { playedSources() }

    fun decorate(response: SearchResponse, saved: PlayedSources): SearchResponse =
        response.copy(works = response.works.map { work ->
            val playback = saved.byWork[work.id]
            work.copy(lastPlayedSite = playback?.site, recentPlayedSite = saved.recentSite,
                playback = playback?.resources.orEmpty(), playbackPositions = playback?.positions.orEmpty(),
                lastPlayedSourceKey = playback?.sourceKey)
        })

    suspend fun recentPlayback(): List<RecentPlayback> = playbackMutex.withLock {
        playedSources().byWork.mapNotNull { (id, work) ->
            val recent = work.recent ?: return@mapNotNull null
            val resource = work.sourceKey ?: return@mapNotNull null
            val bookmark = work.resources[resource] ?: return@mapNotNull null
            RecentPlayback(id, recent.source, bookmark, work.positions[resource].orEmpty(),
                recent.progressPercent, recent.playedAt, recent.cardSource ?: recent.source, recent.cardPoster)
        }.sortedByDescending { it.playedAt }.take(12)
    }

    suspend fun rememberRecentPoster(workId: String, url: String) = playbackMutex.withLock {
        val saved = playedSources()
        val work = saved.byWork[workId] ?: return@withLock
        val recent = work.recent ?: return@withLock
        if (recent.cardPoster == url) return@withLock
        val updated = work.copy(recent = recent.copy(cardPoster = url))
        savePlayedSources(saved.copy(byWork = saved.byWork + (workId to updated)))
    }

    suspend fun recordPlayback(workId: String, site: String, line: String, episode: String,
        source: SearchResult? = null, cardSource: SearchResult? = null, cardPoster: String? = null, index: Int? = null,
    ) = playbackMutex.withLock {
        require(workId.isNotBlank() && site.isNotBlank() && line.isNotBlank())
        require(source == null || source.site == site)
        val saved = playedSources()
        val work = saved.byWork[workId]
        val sourceKey = source?.coverKey?.takeIf { index != null }
        val resource = sourceKey ?: site
        val bookmark = PlaybackBookmark(line, episode, index)
        val resources = work?.resources.orEmpty() + (resource to bookmark)
        val previous = work?.recent?.takeIf { work.site == site && work.sourceKey == sourceKey }
        val progress = previous?.progressPercent?.takeIf { work.resources[resource] == bookmark }
        val recent = if (source != null) RecentPlaybackMetadata(source, clock(), progress,
            work?.recent?.cardSource ?: cardSource, cardPoster ?: work?.recent?.cardPoster)
            else previous?.copy(progressPercent = progress)
        val updated = saved.copy(recentSite = site,
            byWork = saved.byWork + (workId to WorkPlayback(site, resources, work?.positions.orEmpty(), recent, sourceKey)))
        if (updated != saved) savePlayedSources(updated)
        Unit
    }

    suspend fun recordPlaybackPosition(workId: String, site: String, line: String, episode: String,
        positionMillis: Long, progressPercent: Int? = null, sourceKey: String? = null, index: Int? = null,
    ) = playbackMutex.withLock {
        require(workId.isNotBlank() && site.isNotBlank() && line.isNotBlank() && positionMillis >= 0)
        val saved = playedSources()
        val work = saved.byWork[workId] ?: return@withLock
        val resource = sourceKey ?: site
        val positionKey = if (sourceKey != null && index != null) episodePositionKey(episode, index) else episode
        val sitePositions = work.positions[resource].orEmpty()
        val priorPosition = sitePositions[line]?.get(positionKey)
        val recent = work.recent?.let {
            if (work.site == site && work.sourceKey == sourceKey && work.resources[resource] == PlaybackBookmark(line, episode, index)) {
                it.copy(progressPercent = progressPercent ?: it.progressPercent.takeIf { positionMillis == priorPosition })
            } else it
        }
        if (priorPosition == positionMillis && recent == work.recent) return@withLock
        val episodes = sitePositions[line].orEmpty() + (positionKey to positionMillis)
        val positions = work.positions + (resource to (sitePositions + (line to episodes)))
        val updated = saved.copy(byWork = saved.byWork + (workId to work.copy(positions = positions, recent = recent)))
        savePlayedSources(updated)
        Unit
    }

    private suspend fun savePlayedSources(saved: PlayedSources) {
        try { storage.writeAtomic(playedSourcesPath, wireJson.encodeToString(saved)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { log("保存播放记录", e) }
    }

    suspend fun history(): List<String> = historyMutex.withLock {
        cacheRead { wireJson.decodeFromString<List<String>>(storage.read(historyPath) ?: "[]").take(productDefaults.searchHistoryLimit) }.orEmpty()
    }

    suspend fun addHistory(item: String) = updateHistory { (listOf(item) + it).distinct() }
    suspend fun removeHistory(item: String) = updateHistory { it.filterNot { saved -> saved == item } }
    suspend fun clearHistory() = updateHistory { emptyList() }

    private suspend fun updateHistory(update: (List<String>) -> List<String>) = historyMutex.withLock {
        val saved = cacheRead { wireJson.decodeFromString<List<String>>(storage.read(historyPath) ?: "[]") }
            .orEmpty().take(productDefaults.searchHistoryLimit)
        try { storage.writeAtomic(historyPath, wireJson.encodeToString(update(saved).take(productDefaults.searchHistoryLimit))) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { log("保存搜索历史", e) }
        Unit
    }

    private companion object {
        val playbackMutex = Mutex()
        val historyMutex = Mutex()
    }
}

@Serializable
internal data class PlayedSources(val recentSite: String? = null, val byWork: Map<String, WorkPlayback> = emptyMap())

@Serializable
internal data class WorkPlayback(val site: String, val resources: Map<String, PlaybackBookmark>,
    val positions: Map<String, Map<String, Map<String, Long>>> = emptyMap(),
    val recent: RecentPlaybackMetadata? = null, val sourceKey: String? = null)

@Serializable
internal data class RecentPlaybackMetadata(
    val source: SearchResult, val playedAt: Long, val progressPercent: Int? = null,
    val cardSource: SearchResult? = null, val cardPoster: String? = null,
)
