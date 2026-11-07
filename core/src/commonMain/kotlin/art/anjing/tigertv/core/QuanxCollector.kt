package art.anjing.tigertv.core

import art.anjing.tigertv.domain.SourceSite
import io.ktor.http.Url
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*

private class ProbeBudgetExceeded : IllegalStateException("QX 扫描达到 1000 次请求预算")
private val playlistUri = Regex("URI=\"([^\"]+)\"")

/** Share parsed playback links during a scan; retain no API or manifest bodies. */
internal class QuanxCollector(
    private val network: Network,
    private val log: suspend (String, Exception) -> Unit
) {
    private val mutex = Mutex()
    private data class Probe(val domains: Set<String>, val playlists: List<String>)
    private val probes = mutableMapOf<String, CompletableDeferred<Probe>>()
    private var requests = 0

    private suspend fun read(url: String): NetworkResponse {
        mutex.withLock {
            if (requests >= 1000) throw ProbeBudgetExceeded()
            requests++
        }
        return network.response(url, 10_000,
            head = Url(normalizeUrl(url)).encodedPath.endsWith(".mp4", ignoreCase = true), skipMediaBody = true)
    }

    private suspend fun probe(url: String): Probe {
        var owner = false
        val result = mutex.withLock {
            probes.getOrPut(url) { owner = true; CompletableDeferred() }
        }
        if (owner) {
            try { result.complete(parseProbe(read(url))) }
            catch (e: Exception) {
                result.completeExceptionally(if (e is CancellationException) IllegalStateException("共享探测已取消", e) else e)
                throw e
            }
        }
        return result.await()
    }

    private suspend fun parseProbe(response: NetworkResponse): Probe {
        val domains = response.domains.toMutableSet()
        val playlists = mutableListOf<String>()
        if (!response.text.trimStart().startsWith("#EXTM3U", ignoreCase = true)) {
            val links = extractM3u8(response.url, response.text)
            check(response.text.isBlank() || links.isNotEmpty()) { "未发现播放地址" }
            return Probe(domains, links)
        }
        var variant = false
        for (raw in response.text.lineSequence()) {
            currentCoroutineContext().ensureActive()
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith('#')) {
                if (line.startsWith("#EXT-X-STREAM-INF:")) variant = true
                for (match in playlistUri.findAll(line)) {
                    val target = resolveUrl(response.url, match.groupValues[1])
                    if (!target.startsWith("http://") && !target.startsWith("https://")) continue
                    domains += domain(target)
                    if (line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") || line.startsWith("#EXT-X-RENDITION-REPORT:")) playlists += target
                }
            } else {
                val target = resolveUrl(response.url, line)
                domains += domain(target)
                if (variant) playlists += target
                variant = false
            }
        }
        return Probe(domains, playlists)
    }

    suspend fun collect(sites: List<SourceSite>, keyword: String): QuanxDomains {
        val semaphore = Semaphore(20)
        val parts = coroutineScope {
            sites.map { site -> async { semaphore.withPermit { scanSite(site, keyword) } } }.awaitAll()
        }
        return QuanxDomains(parts.flatMap { it.api }.toSet(), parts.flatMap { it.urls }.toSet(),
            parts.flatMap { it.m3u8 }.toSet(), parts.flatMap { it.incompleteSites })
    }

    private suspend fun scanSite(site: SourceSite, keyword: String): QuanxDomains {
        val api = mutableSetOf<String>()
        val links = mutableSetOf<String>()
        val media = mutableSetOf<String>()
        var incomplete = false
        suspend fun failed(e: Exception) { incomplete = true; log(site.name, e) }
        val completed = withTimeoutOrNull(120_000) {
            try {
                api += domain(site.api)
                val ids = mutableSetOf<Int>()
                var page = 1
                do {
                    val response = read(apiUrl(site.api, mapOf("ac" to "list", "wd" to keyword, "pg" to "$page", "pagesize" to "100")))
                    api += response.domains
                    val list = wireJson.decodeFromString<SearchPage>(response.text)
                    val batch = list.results(site.name)
                    ids += batch.map { it.vodId }
                    if (batch.isEmpty() || page >= list.pageCount()) break
                    check(page < 100) { "QX 搜索分页超过 100 页" }
                    page++
                } while (true)
                val queue = ArrayDeque<String>()
                val visited = mutableSetOf<String>()
                fun enqueue(url: String) {
                    val normalized = normalizeUrl(url)
                    if (visited.add(normalized)) queue.add(normalized)
                }
                for (id in ids) {
                    try {
                        val detail = read(apiUrl(site.api, mapOf("ac" to "detail", "ids" to "$id")))
                        api += detail.domains
                        val item = parseVodList(detail.text).firstOrNull { it.vodId() == id } ?: error("未找到视频 $id")
                        for ((field, names) in listOf("vod_play_url" to "vod_play_from", "vod_down_url" to "vod_down_from")) {
                            for (line in parsePlaybackSources(item.text(field), item.text(names))) {
                                val url = line.episodes.first().url
                                links += domain(url)
                                enqueue(url)
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: ProbeBudgetExceeded) { throw e }
                    catch (e: Exception) { failed(e) }
                    while (queue.isNotEmpty()) {
                        val url = queue.removeFirst()
                        media += domain(url)
                        try {
                            val probe = probe(url)
                            media += probe.domains
                            probe.playlists.forEach(::enqueue)
                        } catch (e: CancellationException) { throw e }
                        catch (e: ProbeBudgetExceeded) { throw e }
                        catch (e: Exception) { failed(e) }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed(e) }
            true
        }
        if (completed == null) failed(IllegalStateException("QX 站点扫描超过 120 秒"))
        return QuanxDomains(api, links, media, if (incomplete) listOf(site.name) else emptyList())
    }
}
