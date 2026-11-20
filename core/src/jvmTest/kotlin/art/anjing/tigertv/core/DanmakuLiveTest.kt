package art.anjing.tigertv.core

import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Opt-in integration test against the authorized development server. */
class DanmakuLiveTest {
    @Test fun deployedServerReturnsRealEpisodesAndComments() = runBlocking {
        val base = System.getenv("TIGERTV_DANMU_URL") ?: return@runBlocking
        val http = configureClient(HttpClient())
        try {
            val client = DanmakuClient(Network(http), base)
            val works = listOf("嫁金钗" to "第1集", "逐玉" to "第1集", "后会友期" to "第20261022期第01期")
            for ((work, label) in works) {
                val candidates = client.search(work).mapNotNull { title ->
                    danmakuTitleRank(work, title.title, "2026")?.let { it to title }
                }.sortedWith(compareBy({ it.first }, { danmakuProbePriority(it.second.source) }))
                var current: DanmakuTrack? = null
                var currentRank: Int? = null
                for ((rank, candidate) in candidates.take(4)) {
                    if (currentRank?.let { rank > it } == true) break
                    val episodes = client.episodes(candidate.id)
                    val episode = matchingDanmakuEpisode(episodes, label, null)
                    if (episode == null) {
                        println("$work ${candidate.source}: no unambiguous match")
                        continue
                    }
                    val track = client.comments(episode.id)
                    val count = track.comments.count { it.mode == 1 }
                    val accepted = count > 0 && (current?.let { shouldUpgradeDanmaku(it, track) } != false)
                    if (accepted) {
                        current = track
                        currentRank = rank
                    }
                    println("$work ${candidate.source}: episode=${episode.title}, mode1=$count, durationMs=${track.referenceDurationMs}, accepted=$accepted")
                    if (work == "后会友期") assertNull(matchingDanmakuEpisode(episodes, "第20261023期抢鲜版", null))
                }
                assertNotNull(current, "No valid rolling comments for $work")
            }
        } finally { http.close() }
    }
}
