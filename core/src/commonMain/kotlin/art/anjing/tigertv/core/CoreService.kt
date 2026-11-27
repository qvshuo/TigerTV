package art.anjing.tigertv.core

import art.anjing.tigertv.domain.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable

const val CONFIG_CDN_URL = "https://cdn.jsdelivr.net/gh/qvshuo/TigerTV@main/skills/references/LunaTV-config.json"
const val CONFIG_RAW_URL = "https://raw.githubusercontent.com/qvshuo/TigerTV/refs/heads/main/skills/references/LunaTV-config.json"

class CoreService(
    private val directory: String,
    private val source: String? = null,
    private val storage: Storage = platformStorage(),
    private val client: HttpClient = platformHttpClient(),
    private val clock: () -> Long = ::nowMillis,
    private val logger: Logger = FileLogger(storage, "$directory/tigertv-cli.log"),
    private val searchCacheTtlMillis: Long = 600_000,
    private val configUrls: List<Pair<String, Long>> = listOf(CONFIG_CDN_URL to networkTimeouts.configRequestMs, CONFIG_RAW_URL to 5_000L),
    historyDirectory: String = directory
) {
    private val network = Network(client)
    private val danmakuTracks = DanmakuTrackCache(clock)

    fun danmakuClient(baseUrl: String): DanmakuClient = DanmakuClient(network, baseUrl, danmakuTracks)
    private val configMutex = Mutex()
    private val hotSearchLocks = HOT_SEARCH_COLLECTIONS.associate { it.id to Mutex() }
    private val logMutex = Mutex()
    private val coverSemaphore = Semaphore(4)
    private val searchCache = linkedMapOf<String, Pair<Long, SearchResponse>>()
    private val coverCache = linkedMapOf<String, Pair<Long, String>>()
    private var coverCacheLoaded = false
    private val configPath = "$directory/tigertv-cli-config-cache.json"
    private val coverPath = "$directory/cover-fallback-cache.json"
    private val historyPath = "$historyDirectory/search-history.json"
    private val playedSourcesPath = "$historyDirectory/last-played-sources.json"

    private suspend fun log(context: String, error: Exception) = logMutex.withLock {
        logger.log("WARN", context, error.message ?: error.toString())
    }

    private inline fun <T> cacheRead(block: () -> T): T? = try { block() }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    suspend fun loadConfig(): SourceConfig = configMutex.withLock {
        if (source != null) return@withLock parseConfig(storage.read(source) ?: error("读取 source 失败: $source"))
        val cached = cacheRead {
            wireJson.parseToJsonElement(storage.read(configPath) ?: "null") as? JsonObject
        }
        val raw = cached?.get("config")?.toString()
        val timestamp = (cached?.get("fetchedAtMillis") as? JsonPrimitive)?.longOrNull ?: 0
        if (raw != null && clock() - timestamp in 0 until DAY) {
            cacheRead { parseConfig(raw) }?.let { return@withLock it }
        }
        val errors = mutableListOf<String>()
        for ((url, timeout) in configUrls) {
            try {
                val text = network.get(url, timeout)
                val config = parseConfig(text)
                cacheRead {
                    storage.writeAtomic(configPath, buildJsonObject {
                        put("fetchedAtMillis", clock()); put("config", wireJson.parseToJsonElement(text))
                    }.toString())
                }
                return@withLock config
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors += "${url}: ${e.message}"; log("config", e) }
        }
        if (raw != null) cacheRead { parseConfig(raw) }?.let { return@withLock it }
        error("加载配置失败: ${errors.joinToString("; ")}")
    }

    fun hotSearchCategories(): List<HotSearchCategory> = HOT_SEARCH_COLLECTIONS.map { HotSearchCategory(it.id, it.name) }

    suspend fun warmHotSearches(category: String, onReady: suspend (String, List<HotSearchItem>) -> Unit = { _, _ -> }) {
        if (hotSearches(category).isEmpty()) return
        for (collection in HOT_SEARCH_COLLECTIONS) {
            if (collection.id == category) continue
            val titles = hotSearches(collection.id)
            if (titles.isNotEmpty()) onReady(collection.id, titles)
        }
    }

    suspend fun hotSearches(category: String, onUpdate: (suspend (List<HotSearchItem>) -> Unit)? = null): List<HotSearchItem> {
        val collection = HOT_SEARCH_COLLECTIONS.firstOrNull { it.id == category } ?: error("未知热门分类")
        return hotSearchLocks.getValue(category).withLock {
            val path = "$directory/hot-cards-v2-${collection.id}.json"
            val cached = cacheRead { wireJson.decodeFromString<HotSearchCache>(storage.read(path) ?: "") }
            val age = cached?.let { clock() - it.fetchedAtMillis }
            if (cached != null && age != null && age in 0 until 6 * 60 * 60 * 1000L) return@withLock cached.titles
            try {
                val pageSize = 50
                val items = linkedMapOf<String, HotSearchItem>()
                var start = 0
                do {
                    val raw = network.get(
                        "https://m.douban.com/rexxar/api/v2/subject_collection/${collection.collection}/items?start=$start&count=$pageSize",
                        networkTimeouts.recommendationRequestMs, mapOf("Referer" to "https://m.douban.com/tv/${collection.id}")
                    )
                    val page = wireJson.decodeFromString<HotSearchPage>(raw)
                    val previousSize = items.size
                    for (item in page.items) {
                        if (item.id.isBlank() || item.title.isBlank() || item.id in items) continue
                        items[item.id] = HotSearchItem(item.id, item.title.trim(), item.year.trim(), item.pic?.large.orEmpty().trim(),
                            item.rating?.value?.takeIf { it > 0 && it <= 10 }, (item.rating?.count ?: 0).coerceAtLeast(0), item.status)
                    }
                    start += page.items.size
                    val hasMore = page.total?.let { start < it } ?: (page.items.size >= pageSize)
                    val advanced = items.size > previousSize
                    if (advanced && hasMore) onUpdate?.invoke(items.values.toList())
                } while (advanced && hasMore)
                val titles = items.values.toList()
                check(titles.isNotEmpty()) { "热门剧集为空" }
                cacheRead { storage.writeAtomic(path, wireJson.encodeToString(HotSearchCache(clock(), titles))) }
                titles
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                log("hotSearches:$category", e)
                if (cached != null && age != null && age in 0 until DAY) cached.titles else emptyList()
            }
        }
    }

    private suspend fun list(site: SourceSite, parameters: Map<String, String>) =
        parseVodList(network.get(apiUrl(site.api, parameters)))

    suspend fun search(keyword: String, onUpdate: (suspend (SearchResponse) -> Unit)? = null): SearchResponse {
        val cached = stateMutex.withLock {
            searchCache.entries.removeAll { clock() - it.value.first !in 0 until searchCacheTtlMillis }
            searchCache[keyword]?.second
        }
        if (cached != null) return withPlayedSources(cached).also { onUpdate?.invoke(it) }
        val sites = loadConfig().apiSite.values
        val sourceOrder = sites.mapIndexed { index, site -> site.name to index }.toMap()
        val results = mutableListOf<SearchResult>()
        val updateMutex = Mutex()
        var successfulSites = 0
        val settledSites = mutableSetOf<String>()
        val savedAtStart = onUpdate?.let { stateMutex.withLock { playedSources() } }
        var indexedCount = -1
        var indexedWorks = emptyList<VideoWork>()
        fun snapshot(): SearchResponse {
            if (indexedCount != results.size) {
                indexedCount = results.size
                indexedWorks = aggregateWorks(results).map { work ->
                    work.copy(sources = work.sources.sortedBy { sourceOrder.getValue(it.site) })
                }
            }
            return SearchResponse(keyword, indexedWorks, settledSites.toSet())
        }
        suspend fun publish() {
            if (onUpdate != null && results.isNotEmpty()) onUpdate(withPlayedSources(snapshot(), checkNotNull(savedAtStart)))
        }
        coroutineScope {
            sites.map { site -> async {
                try {
                    val complete = withTimeoutOrNull(networkTimeouts.searchSiteMs) {
                        searchSite(site, keyword) { page, complete ->
                            updateMutex.withLock {
                                results += page
                                if (complete) settledSites += site.name
                                publish()
                            }
                        }
                        true
                    }
                    check(complete == true) { "站点搜索超过 60 秒" }
                    updateMutex.withLock { successfulSites++ }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { log(site.name, e) }
                updateMutex.withLock {
                    if (settledSites.add(site.name)) publish()
                }
            } }.awaitAll()
        }
        check(successfulSites > 0 || results.isNotEmpty()) { "所有资源搜索失败" }
        val response = snapshot()
        stateMutex.withLock {
            if (searchCacheTtlMillis > 0 && successfulSites == sites.size && results.size <= MAX_CACHED_RESULTS) {
                searchCache.remove(keyword)
                searchCache[keyword] = clock() to response
                while (searchCache.size > MAX_CACHED_SEARCHES || searchCache.values.sumOf { entry ->
                    entry.second.works.sumOf { it.sources.size }
                } > MAX_CACHED_RESULTS) searchCache.remove(searchCache.keys.first())
            }
        }
        return withPlayedSources(response)
    }

    private fun playedSources(): PlayedSources = cacheRead {
        wireJson.decodeFromString<PlayedSources>(storage.read(playedSourcesPath) ?: "{}")
    } ?: PlayedSources()

    private suspend fun withPlayedSources(response: SearchResponse): SearchResponse = stateMutex.withLock {
        withPlayedSources(response, playedSources())
    }

    private fun withPlayedSources(response: SearchResponse, saved: PlayedSources): SearchResponse =
        response.copy(works = response.works.map { work ->
            val playback = saved.byWork[work.id]
            work.copy(lastPlayedSite = playback?.site, recentPlayedSite = saved.recentSite,
                playback = playback?.resources.orEmpty(), playbackPositions = playback?.positions.orEmpty(),
                lastPlayedSourceKey = playback?.sourceKey)
        })

    suspend fun recentPlayback(): List<RecentPlayback> = stateMutex.withLock {
        playedSources().byWork.mapNotNull { (id, work) ->
            val recent = work.recent ?: return@mapNotNull null
            val resource = work.sourceKey ?: return@mapNotNull null
            val bookmark = work.resources[resource] ?: return@mapNotNull null
            RecentPlayback(id, recent.source, bookmark, work.positions[resource].orEmpty(),
                recent.progressPercent, recent.playedAt, recent.cardSource ?: recent.source, recent.cardPoster)
        }.sortedByDescending { it.playedAt }.take(12)
    }

    suspend fun rememberRecentPoster(workId: String, url: String) = stateMutex.withLock {
        val saved = playedSources()
        val work = saved.byWork[workId] ?: return@withLock
        val recent = work.recent ?: return@withLock
        if (recent.cardPoster == url) return@withLock
        val updated = work.copy(recent = recent.copy(cardPoster = url))
        savePlayedSources(saved.copy(byWork = saved.byWork + (workId to updated)))
    }

    suspend fun recordPlayback(workId: String, site: String, line: String, episode: String,
        source: SearchResult? = null, cardSource: SearchResult? = null, cardPoster: String? = null, index: Int? = null,
    ) = stateMutex.withLock {
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
    ) = stateMutex.withLock {
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

    private suspend fun searchSite(site: SourceSite, keyword: String,
        onPage: suspend (List<SearchResult>, Boolean) -> Unit,
    ) {
        val seen = mutableSetOf<Int>()
        var page = 1
        do {
            // detail supports wd too and supplies year/type/poster needed for safe aggregation.
            val raw = network.get(apiUrl(site.api, mapOf("ac" to "detail", "wd" to keyword, "pg" to page.toString(), "pagesize" to "100")))
            val response = wireJson.decodeFromString<SearchPage>(raw)
            val batch = response.results(site.name)
            val added = batch.filter { seen.add(it.vodId) }
            val pageCount = response.pageCount()
            val complete = batch.isEmpty() || page >= pageCount
            if (added.isNotEmpty() || complete) onPage(added, complete)
            if (complete) break
            page++
            check(page <= 100) { "搜索分页超过 100 页" }
        } while (true)
    }

    private suspend fun site(name: String): SourceSite {
        val sites = loadConfig().apiSite.values
        return sites.firstOrNull { it.name == name }
            ?: error("未找到站点: $name\n可用站点: ${sites.map { it.name }.distinct().sorted().joinToString(", ")}")
    }

    suspend fun fetch(siteName: String, vodId: Int): FetchResponse {
        check(vodId > 0) { "vod_id 必须大于 0" }
        val item = list(site(siteName), mapOf("ac" to "detail", "ids" to vodId.toString()))
            .firstOrNull { it.vodId() == vodId } ?: error("未找到该视频: $vodId")
        return FetchResponse(
            vodId, siteName,
            vodName = item.text("vod_name"), vodPic = item.text("vod_pic"), vodYear = item.text("vod_year"),
            typeName = item.text("type_name"), vodRemarks = item.text("vod_remarks"),
            vodContent = plainDescription(item.text("vod_content")), vodActor = item.text("vod_actor"),
            vodDirector = item.text("vod_director"), vodArea = item.text("vod_area"), vodLang = item.text("vod_lang"),
            playSources = parsePlaybackSources(item.text("vod_play_url"), item.text("vod_play_from")),
            downloadSources = parsePlaybackSources(item.text("vod_down_url"), item.text("vod_down_from"))
        )
    }

    suspend fun resolveCoverFallback(siteName: String, vodId: Int): String? = coverSemaphore.withPermit {
        val key = coverCacheKey(siteName, vodId)
        stateMutex.withLock {
            val now = clock()
            if (!coverCacheLoaded) {
                coverCache.putAll(readCoverCache(now))
                coverCacheLoaded = true
            }
            coverCache.entries.removeAll { now - it.value.first !in 0 until 7 * DAY }
            coverCache[key]?.let { return@withPermit it.second }
        }
        try {
            val item = list(site(siteName), mapOf("ac" to "detail", "ids" to vodId.toString())).firstOrNull { it.vodId() == vodId }
            val url = normalizeUrl(item?.text("vod_pic")?.trim()?.takeIf { it.isNotEmpty() } ?: return@withPermit null)
            stateMutex.withLock {
                val now = clock()
                val entries = readCoverCache(now) + coverCache + (key to (now to url))
                val latest = entries.entries.sortedBy { it.value.first }.takeLast(256)
                coverCache.clear()
                for ((id, value) in latest) coverCache[id] = value
                cacheRead {
                    val stored = coverCache.mapValues { (_, value) ->
                        buildJsonObject { put("url", value.second); put("fetchedAtMillis", value.first) }
                    }
                    storage.writeAtomic(coverPath, JsonObject(stored).toString())
                }
            }
            url
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { log(siteName, e); null }
    }

    private fun readCoverCache(now: Long): Map<String, Pair<Long, String>> {
        val disk = cacheRead { wireJson.parseToJsonElement(storage.read(coverPath) ?: "{}").jsonObject }
        return disk.orEmpty().entries.mapNotNull { (key, raw) ->
            val entry = raw as? JsonObject ?: return@mapNotNull null
            val time = (entry["fetchedAtMillis"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val url = entry.text("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            (key to (time to url)).takeIf { now - time in 0 until 7 * DAY }
        }.sortedBy { it.second.first }.takeLast(256).toMap()
    }

    suspend fun history(): List<String> = stateMutex.withLock {
        cacheRead { wireJson.decodeFromString<List<String>>(storage.read(historyPath) ?: "[]").take(MAX_SEARCH_HISTORY) }.orEmpty()
    }

    suspend fun addHistory(item: String) = updateHistory { (listOf(item) + it).distinct() }
    suspend fun removeHistory(item: String) = updateHistory { it.filterNot { saved -> saved == item } }
    suspend fun clearHistory() = updateHistory { emptyList() }

    private suspend fun updateHistory(update: (List<String>) -> List<String>) = stateMutex.withLock {
        val saved = cacheRead { wireJson.decodeFromString<List<String>>(storage.read(historyPath) ?: "[]") }
            .orEmpty().take(MAX_SEARCH_HISTORY)
        try { storage.writeAtomic(historyPath, wireJson.encodeToString(update(saved).take(MAX_SEARCH_HISTORY))) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { log("保存搜索历史", e) }
        Unit
    }

    suspend fun collectQuanxDomains(keyword: String): QuanxDomains =
        QuanxCollector(network, ::log).collect(loadConfig().apiSite.values.toList(), keyword)

    fun close() = client.close()

    companion object {
        // Serialize persistent read-modify-write operations across in-process services/windows.
        private val stateMutex = Mutex()
        const val DAY = 86_400_000L
        const val MAX_SEARCH_HISTORY = 12
        private const val MAX_CACHED_SEARCHES = 5
        private const val MAX_CACHED_RESULTS = 10_000
    }
}

@Serializable
private data class PlayedSources(val recentSite: String? = null, val byWork: Map<String, WorkPlayback> = emptyMap())

@Serializable
private data class WorkPlayback(val site: String, val resources: Map<String, PlaybackBookmark>,
    val positions: Map<String, Map<String, Map<String, Long>>> = emptyMap(),
    val recent: RecentPlaybackMetadata? = null, val sourceKey: String? = null)

@Serializable
private data class RecentPlaybackMetadata(
    val source: SearchResult, val playedAt: Long, val progressPercent: Int? = null,
    val cardSource: SearchResult? = null, val cardPoster: String? = null,
)
