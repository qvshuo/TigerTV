package art.anjing.tigertv.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

enum class DanmakuPhase(val label: String) {
    Search("正在匹配弹幕…"),
    Episodes("正在匹配弹幕…"),
    Track("正在加载弹幕…"),
}

data class DanmakuMatch(
    val title: DanmakuTitle,
    val episodes: List<DanmakuEpisode>,
    val episode: DanmakuEpisode,
    val track: DanmakuTrack,
)

private data class EpisodeMatch(val title: DanmakuTitle, val episodes: List<DanmakuEpisode>, val episode: DanmakuEpisode) {
    fun withTrack(track: DanmakuTrack) = DanmakuMatch(title, episodes, episode, track)
}

suspend fun DanmakuClient.autoMatch(
    query: String, year: String, episodeLabel: String, savedTitle: Long? = null,
    savedEpisode: DanmakuChoice? = null, onPhase: (DanmakuPhase) -> Unit = {},
    onTitles: (List<DanmakuTitle>) -> Unit = {}, onMatch: (DanmakuMatch) -> Unit,
) = withTimeout(selectionRules.autoTimeoutMs) {
    coroutineScope {
        var budget = selectionRules.requestBudget
        var lastError: Exception? = null
        var blocked = false
        suspend fun <T> attempt(call: suspend () -> T): T? = try {
            call()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            lastError = error
            blocked = blocked || error.message in setOf("HTTP 401", "HTTP 403", "HTTP 429")
            null
        }
        val season = danmakuSeason(query) ?: danmakuSeason(episodeLabel)
        suspend fun resolve(title: DanmakuTitle): EpisodeMatch? {
            if (budget <= 0 || blocked) return null
            budget--
            onPhase(DanmakuPhase.Episodes)
            val episodes = attempt { episodes(title.id) } ?: return null
            val episode = matchingDanmakuEpisode(episodes, episodeLabel, season) ?: return null
            return EpisodeMatch(title, episodes, episode)
        }
        suspend fun readTrack(id: Long): DanmakuTrack? =
            attempt { comments(id) }?.takeIf { track -> track.comments.any { it.mode == 1 } }

        if (savedEpisode != null) {
            val (title, episode) = savedEpisode
            budget--
            onPhase(DanmakuPhase.Track)
            val track = readTrack(episode.id)
            if (track != null) {
                onTitles(listOf(title))
                onMatch(DanmakuMatch(title, listOf(episode), episode, track))
                return@coroutineScope
            }
        }
        if (savedTitle != null) {
            val match = resolve(DanmakuTitle(savedTitle, query, ""))
            if (match != null && budget > 0 && !blocked) {
                budget--
                onPhase(DanmakuPhase.Track)
                val track = readTrack(match.episode.id)
                if (track != null) {
                    onTitles(listOf(match.title))
                    onMatch(match.withTrack(track))
                    return@coroutineScope
                }
            }
        }

        val seen = mutableSetOf<Long>()
        if (savedTitle != null) seen.add(savedTitle)
        for (keyword in listOf(query, danmakuSearchKeyword(query)).filter(String::isNotBlank).distinct()) {
            if (budget <= 0 || blocked) break
            budget--
            onPhase(DanmakuPhase.Search)
            val titles = attempt { search(keyword) }.orEmpty()
            onTitles(titles)
            val candidates = danmakuTitleCandidates(query, titles.filter { seen.add(it.id) }, year, season)
                .sortedWith(compareBy({ it.first }, { danmakuProbePriority(it.second.source) }))
            var best: DanmakuTrack? = null
            var bestRank: Int? = null
            var index = 0
            while (index < candidates.size && budget > 0 && !blocked) {
                val rank = candidates[index].first
                if (bestRank?.let { rank > it } == true) break
                var downloads = 0
                var publishOrder = async {}
                while (downloads < 2 && budget > 0 && !blocked && candidates.getOrNull(index)?.first == rank) {
                    val candidate = resolve(candidates[index++].second) ?: continue
                    if (budget <= 0 || blocked) break
                    budget--
                    downloads++
                    onPhase(DanmakuPhase.Track)
                    val download = async { readTrack(candidate.episode.id) }
                    val previous = publishOrder
                    publishOrder = async {
                        previous.await()
                        val track = download.await() ?: return@async
                        val current = best
                        if (current == null || shouldUpgradeDanmaku(current, track)) {
                            best = track
                            bestRank = rank
                            onMatch(candidate.withTrack(track))
                        }
                    }
                }
                publishOrder.await()
            }
            if (best != null) return@coroutineScope
        }
        lastError?.let { throw it }
    }
}
