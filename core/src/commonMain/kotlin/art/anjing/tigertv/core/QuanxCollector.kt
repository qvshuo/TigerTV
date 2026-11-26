package art.anjing.tigertv.core

import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.domain.SourceSite
import io.ktor.http.Url
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*

private const val REQUEST_LIMIT = 1000
private const val PROBE_BATCH_SIZE = 6
private class ProbeBudgetExceeded(phase: String) : IllegalStateException("QX ${phase}达到 $REQUEST_LIMIT 次 HTTP 请求预算")
private val playlistUri = Regex("(?:^|[:,])(?:SERVER-)?URI=\"([^\"]+)\"")
private val mediaExtensions = setOf("mp4", "m4v", "mov", "mkv", "webm", "ts", "m4s", "aac", "mp3", "mpd", "zip", "srt", "vtt")
private val playlistTags = setOf("#EXT-X-MEDIA", "#EXT-X-I-FRAME-STREAM-INF", "#EXT-X-RENDITION-REPORT", "#EXT-X-IMAGE-STREAM-INF")
private val scheme = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE)

private fun httpUrl(base: String, value: String): String? {
    val link = value.trim().substringBefore('#')
    if (link.isEmpty() || link.any { it.isWhitespace() }) return null
    if (scheme.containsMatchIn(link) && !link.startsWith("http://", true) && !link.startsWith("https://", true)) return null
    val absolute = if (scheme.containsMatchIn(link)) normalizeUrl(link) else runCatching { resolveUrl(base, link) }.getOrNull() ?: return null
    return absolute.takeIf { quanxHost(it) != null }
}

private fun mediaFile(url: String): Boolean = Url(url).encodedPath.substringAfterLast('.').lowercase() in mediaExtensions

internal class QuanxCollector(
    private val network: Network,
    private val log: suspend (String, Exception) -> Unit,
) {
    private val mutex = Mutex()
    private val mediaHosts = mutableSetOf<String>()
    private val playlistProbes = mutableMapOf<String, CompletableDeferred<Probe>>()
    private val mediaProbes = mutableMapOf<String, CompletableDeferred<Probe>>()

    private class SiteScan(val site: SourceSite) {
        val lock = Mutex()
        val api = mutableSetOf<String>()
        val urls = mutableSetOf<String>()
        val roots = linkedSetOf<String>()
        val leaves = linkedSetOf<String>()
        val failures = linkedSetOf<String>()
        var phaseComplete = false
    }

    private data class Probe(
        val playlists: List<String> = emptyList(),
        val leaves: Set<String> = emptySet(),
        val error: Exception? = null,
        val leafLimitReached: Boolean = false,
        val dynamicSteering: Boolean = false,
    )

    private inner class Budget(val name: String, val timeMillis: Long, val requestMillis: Long) {
        val connections = Semaphore(24)
        private var requests = 0
        suspend fun take() = mutex.withLock {
            if (requests >= REQUEST_LIMIT) throw ProbeBudgetExceeded(name)
            requests++
        }
    }

    private suspend fun read(url: String, budget: Budget, scan: SiteScan): NetworkResponse {
        suspend fun remember(target: String) {
            quanxHost(target)?.let { host -> scan.lock.withLock { scan.api += host } }
        }
        return budget.connections.withPermit {
            network.response(url, budget.requestMillis, onRedirect = ::remember, onRequest = { target ->
                remember(target)
                budget.take()
            })
        }
    }

    private suspend fun report(scan: SiteScan, error: Exception) {
        if (scan.lock.withLock { scan.failures.add(error.message ?: "请求失败") }) log(scan.site.name, error)
    }

    private suspend fun rememberMedia(url: String) {
        val host = quanxHost(url) ?: return
        mutex.withLock { mediaHosts += host }
    }

    private suspend fun probe(url: String, isLeaf: Boolean, budget: Budget): Probe {
        var owner = false
        val memo = if (isLeaf) mediaProbes else playlistProbes
        val deferred = mutex.withLock { memo.getOrPut(url) { owner = true; CompletableDeferred() } }
        if (!owner) return deferred.await()
        try {
            suspend fun request(range: Boolean) = budget.connections.withPermit {
                network.response(
                    url, budget.requestMillis, extraHeaders = if (range) mapOf("Range" to "bytes=0-0") else emptyMap(),
                    headersOnly = isLeaf, skipMediaBody = true, onRequest = { target ->
                        rememberMedia(target)
                        budget.take()
                    }, onRedirect = ::rememberMedia,
                )
            }
            val response = if (!isLeaf) request(false) else {
                try { request(true) }
                catch (error: IllegalStateException) {
                    if (error.message !in setOf("HTTP 400", "HTTP 403", "HTTP 405", "HTTP 416", "HTTP 501")) throw error
                    request(false)
                }
            }
            val result = if (isLeaf) Probe() else parseProbe(response)
            deferred.complete(result)
            return result
        } catch (error: CancellationException) {
            deferred.complete(Probe(error = IllegalStateException("共享 QX 请求提前结束", error)))
            throw error
        } catch (error: Exception) {
            return Probe(error = error).also { deferred.complete(it) }
        }
    }

    private suspend fun parseProbe(response: NetworkResponse): Probe {
        val text = response.text.trimStart { it.isWhitespace() || it == '\uFEFF' }
        if (!text.startsWith("#EXTM3U", ignoreCase = true)) {
            val links = extractPlaybackLinks(response.url, text)
            for (link in links) rememberMedia(link)
            check(text.isBlank() || links.isNotEmpty()) { "未发现可静态解析的播放地址" }
            return Probe(links.filterNot(::mediaFile), links.filter(::mediaFile).toSet())
        }
        val playlists = linkedSetOf<String>()
        val leaves = linkedSetOf<String>()
        var leafLimitReached = false
        var dynamicSteering = false
        var variant = false
        var sampledSegment = false
        val observed = mutableSetOf<String>()
        fun append(value: String, playlist: Boolean, follow: Boolean = true): Boolean {
            val url = httpUrl(response.url, value) ?: return false
            quanxHost(url)?.let { observed += it }
            if (playlist) playlists += url
            else if (follow) {
                if (leaves.size < REQUEST_LIMIT) leaves += url
                else if (url !in leaves) leafLimitReached = true
            }
            return true
        }
        for (raw in text.lineSequence()) {
            currentCoroutineContext().ensureActive()
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith('#')) {
                val tag = line.substringBefore(':')
                if (tag == "#EXT-X-STREAM-INF") variant = true
                if (tag == "#EXT-X-CONTENT-STEERING") dynamicSteering = true
                if (tag.startsWith("#EXT-X-")) for (match in playlistUri.findAll(line)) {
                    append(match.groupValues[1], tag in playlistTags, follow = tag != "#EXT-X-PART" && tag != "#EXT-X-PRELOAD-HINT")
                }
            } else {
                val valid = append(line, variant, follow = !sampledSegment)
                if (!variant && valid) sampledSegment = true
                variant = false
            }
        }
        mutex.withLock { mediaHosts += observed }
        return Probe(playlists.toList(), leaves, leafLimitReached = leafLimitReached, dynamicSteering = dynamicSteering)
    }

    private suspend fun phase(scans: List<SiteScan>, budget: Budget, work: suspend (SiteScan) -> Unit) {
        scans.forEach { it.phaseComplete = false }
        withTimeoutOrNull(budget.timeMillis) {
            coroutineScope {
                scans.map { scan -> async {
                    try { work(scan) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { report(scan, error) }
                    scan.phaseComplete = true
                } }.awaitAll()
            }
        }
        for (scan in scans) if (!scan.phaseComplete) {
            report(scan, IllegalStateException("QX ${budget.name}超过 ${budget.timeMillis / 1000} 秒"))
        }
    }

    private suspend fun <T, R> parallel(batch: List<T>, work: suspend (T) -> R): List<R> = coroutineScope {
        batch.map { item -> async { work(item) } }.awaitAll()
    }

    suspend fun collect(sites: List<SourceSite>, keyword: String): QuanxDomains {
        val scans = sites.map { site ->
            SiteScan(site).also { scan -> quanxHost(site.api)?.let { scan.api += it } }
        }
        val discoveryBudget = Budget("资源站发现", 12_000, 5_000)
        val playlistBudget = Budget("播放入口和清单探测", 12_000, 4_000)
        val mediaBudget = Budget("媒体跳转探测", 6_000, 2_000)
        phase(scans, discoveryBudget) { discover(it, keyword, discoveryBudget) }
        phase(scans, playlistBudget) { walkPlaylists(it, playlistBudget) }
        phase(scans, mediaBudget) { scan ->
            val representatives = scan.leaves.distinctBy(::quanxHost).toSet()
            val ordered = representatives.asSequence() + scan.leaves.asSequence().filterNot { it in representatives }
            for (batch in ordered.chunked(PROBE_BATCH_SIZE)) {
                val errors = parallel(batch) { probe(it, true, mediaBudget).error }
                for (error in errors.filterNotNull()) {
                    if (error is ProbeBudgetExceeded) throw error
                    report(scan, error)
                }
            }
        }
        val incomplete = scans.filter { it.failures.isNotEmpty() }.associate { it.site.name to it.failures.toList() }
        return QuanxDomains(scans.flatMap { it.api }.toSet(), scans.flatMap { it.urls }.toSet(), mediaHosts.toSet(),
            incomplete.keys.toList(), incomplete)
    }

    private suspend fun discover(scan: SiteScan, keyword: String, budget: Budget) {
        val site = scan.site
        val ids = linkedSetOf<Int>()
        suspend fun cover(base: String, value: String) {
            httpUrl(base, value)?.let { url ->
                quanxHost(url)?.let { scan.urls += it }
                addLeaf(scan, url)
            }
        }
        try {
            var action = "list"
            suspend fun listPage(page: Int): Triple<String, Int, List<SearchResult>> {
                val response = read(apiUrl(site.api, mapOf(
                    "ac" to action, "wd" to keyword, "pg" to "$page", "pagesize" to "100",
                )), budget, scan)
                val pageData = wireJson.decodeFromString<SearchPage>(response.text)
                return Triple(response.url, pageData.pageCount(), pageData.results(site.name))
            }
            for (page in 1..100) {
                val (responseUrl, pageCount, batch) = try { listPage(page) }
                catch (error: CancellationException) { throw error }
                catch (error: ProbeBudgetExceeded) { throw error }
                catch (error: Exception) {
                    if (page != 1) throw error
                    action = "detail"
                    listPage(page)
                }
                for (item in batch) {
                    ids += item.vodId
                    cover(responseUrl, item.vodPic)
                }
                if (batch.isEmpty() || page >= pageCount) break
                check(page < 100) { "QX 搜索分页超过 100 页" }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: ProbeBudgetExceeded) { throw error }
        catch (error: Exception) { report(scan, error) }
        for (batch in ids.asSequence().chunked(PROBE_BATCH_SIZE)) {
            val errors = parallel(batch) { id -> discoverDetail(scan, id, budget) }
            for (error in errors.filterNotNull()) {
                if (error is ProbeBudgetExceeded) throw error
                report(scan, error)
            }
        }
    }

    private suspend fun discoverDetail(scan: SiteScan, id: Int, budget: Budget): Exception? = try {
        val detail = read(apiUrl(scan.site.api, mapOf("ac" to "detail", "ids" to "$id")), budget, scan)
        val item = parseVodList(detail.text).firstOrNull { it.vodId() == id } ?: error("未找到视频 $id")
        val poster = httpUrl(detail.url, item.text("vod_pic"))
        val entries = listOf("vod_play_url", "vod_down_url").flatMap { field ->
            parsePlaybackSources(item.text(field), "").map { line -> httpUrl(detail.url, line.episodes.first().url) }
        }
        val playback = entries.filterNotNull()
        val leaves = listOfNotNull(poster) + playback.filter(::mediaFile)
        var atLimit = false
        scan.lock.withLock {
            (playback + listOfNotNull(poster)).mapNotNullTo(scan.urls, ::quanxHost)
            playback.filterNotTo(scan.roots, ::mediaFile)
            for (url in leaves) if (!scan.storeLeaf(url)) atLimit = true
        }
        if (atLimit) report(scan, leafLimitError())
        if (leaves.any(::isDash)) report(scan, dashError())
        if (null in entries) IllegalStateException("存在无法解析的播放或下载链接") else null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        error
    }

    private fun SiteScan.storeLeaf(url: String): Boolean =
        if (leaves.size < REQUEST_LIMIT) { leaves += url; true } else url in leaves

    private fun isDash(url: String): Boolean = Url(url).encodedPath.endsWith(".mpd", true)
    private fun dashError() = IllegalStateException("DASH 清单仅记录入口，不展开分片")
    private fun leafLimitError() = IllegalStateException("媒体链接超过 $REQUEST_LIMIT 条，未逐一跟踪跳转")

    private suspend fun addLeaf(scan: SiteScan, url: String) {
        if (isDash(url)) report(scan, dashError())
        if (!scan.storeLeaf(url)) report(scan, leafLimitError())
    }

    private suspend fun walkPlaylists(scan: SiteScan, budget: Budget) {
        val queue = ArrayDeque(scan.roots)
        val visited = scan.roots.toMutableSet()
        while (queue.isNotEmpty()) {
            val batch = List(minOf(queue.size, PROBE_BATCH_SIZE)) { queue.removeFirst() }
            val results = parallel(batch) { probe(it, false, budget) }
            val children = mutableListOf<String>()
            for (result in results) {
                result.error?.takeUnless { it is ProbeBudgetExceeded }?.let { report(scan, it) }
                for (url in result.playlists) if (visited.add(url)) children += url
                if (result.leafLimitReached) report(scan, leafLimitError())
                if (result.dynamicSteering) report(scan, IllegalStateException("HLS Content Steering 的动态路径未展开"))
                result.leaves.forEach { addLeaf(scan, it) }
            }
            results.firstNotNullOfOrNull { it.error as? ProbeBudgetExceeded }?.let { throw it }
            children.asReversed().forEach(queue::addFirst)
        }
    }
}

private val playbackLink = Regex("[\"']([^\"'\\s]+\\.(?:m3u8|mp4|m4v|webm|mov|mkv|mpd)(?:[?#][^\"'\\s]*)?)[\"']", RegexOption.IGNORE_CASE)
private val htmlMediaLink = Regex("<(?:video|source)\\b[^>]*?\\s(?:src|data-src)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
private val wrappedUrl = Regex("^(?:https?%3a|%2f)", RegexOption.IGNORE_CASE)

private fun extractPlaybackLinks(base: String, html: String): List<String> {
    val raw = html.replace("\\/", "/")
    fun matches(text: String) = playbackLink.findAll(text).mapNotNull { match ->
        val link = match.groupValues[1].replace("&amp;", "&")
        val decoded = if (wrappedUrl.containsMatchIn(link)) percentDecode(link) else link
        httpUrl(base, decoded)
    }.toList()
    // Never percent-decode an already parsed signed URL.
    val direct = matches(raw) + htmlMediaLink.findAll(raw).mapNotNull {
        httpUrl(base, it.groupValues[1].replace("&amp;", "&"))
    }
    val encoded = playbackLink.findAll(percentDecode(raw)).mapNotNull { match ->
        val link = match.groupValues[1].replace("&amp;", "&")
        httpUrl(base, link)?.takeUnless { decoded -> direct.any { percentDecode(it) == decoded } }
    }.toList()
    return (direct + encoded).distinct()
}

internal fun extractM3u8(base: String, html: String): List<String> =
    extractPlaybackLinks(base, html).filter { Url(it).encodedPath.endsWith(".m3u8", true) }
