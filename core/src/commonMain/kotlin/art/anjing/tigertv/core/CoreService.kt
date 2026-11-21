package art.anjing.tigertv.core

import art.anjing.tigertv.domain.*
import art.anjing.tigertv.domain.workId as canonicalWorkId
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
    private val configUrls: List<Pair<String, Long>> = listOf(CONFIG_CDN_URL to 10_000L, CONFIG_RAW_URL to 5_000L),
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
    private val coverCache = mutableMapOf<String, Pair<Long, String>>()
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

    suspend fun warmHotSearches(category: String) {
        if (hotSearches(category).isEmpty()) return
        coroutineScope {
            HOT_SEARCH_COLLECTIONS.filter { it.id != category }.map { collection ->
                async { hotSearches(collection.id) }
            }.awaitAll()
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
                        8_000, mapOf("Referer" to "https://m.douban.com/tv/${collection.id}")
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
        fun snapshot() = SearchResponse(keyword, aggregateWorks(results).map { work ->
            // Keep arrival-based card identity while ordering resources by configuration.
            work.copy(sources = work.sources.sortedBy { sourceOrder.getValue(it.site) })
        })
        coroutineScope {
            sites.map { site -> async {
                val batch = try {
                    withTimeoutOrNull(60_000) { searchSite(site, keyword) } ?: error("站点搜索超过 60 秒")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { log(site.name, e); return@async }
                updateMutex.withLock {
                    successfulSites++
                    if (batch.isNotEmpty()) {
                        results += batch
                        onUpdate?.invoke(withPlayedSources(snapshot()))
                    }
                }
            } }.awaitAll()
        }
        check(successfulSites > 0) { "所有资源搜索失败" }
        val response = snapshot()
        stateMutex.withLock {
            if (searchCacheTtlMillis > 0 && results.size <= MAX_CACHED_RESULTS) {
                searchCache.remove(keyword)
                searchCache[keyword] = clock() to response
                while (searchCache.size > MAX_CACHED_SEARCHES || searchCache.values.sumOf { entry ->
                    entry.second.works.sumOf { it.sources.size }
                } > MAX_CACHED_RESULTS) searchCache.remove(searchCache.keys.first())
            }
        }
        return withPlayedSources(response)
    }

    private fun playedSources(): PlayedSources {
        val saved = cacheRead {
            wireJson.decodeFromString<PlayedSources>(storage.read(playedSourcesPath) ?: "{}")
        } ?: PlayedSources()
        val normalized = mutableMapOf<String, WorkPlayback>()
        for ((id, record) in saved.byWork) {
            val source = record.recent?.source
            val key = if (source != null && legacyWorkId(source) == id) canonicalWorkId(source) else id
            normalized[key] = mergePlayback(normalized[key], record)
        }
        return saved.copy(byWork = normalized)
    }

    private fun mergePlayback(first: WorkPlayback?, second: WorkPlayback): WorkPlayback {
        if (first == null) return second
        val recentSecond = (second.recent?.playedAt ?: 0) >= (first.recent?.playedAt ?: 0)
        val older = if (recentSecond) first else second
        val newer = if (recentSecond) second else first
        val positions = older.positions.toMutableMap()
        for ((site, lines) in newer.positions) {
            val merged = positions[site].orEmpty().toMutableMap()
            for ((line, episodes) in lines) merged[line] = merged[line].orEmpty() + episodes
            positions[site] = merged
        }
        return newer.copy(resources = older.resources + newer.resources, positions = positions)
    }

    private suspend fun withPlayedSources(response: SearchResponse): SearchResponse = stateMutex.withLock {
        val saved = playedSources()
        response.copy(works = response.works.map { work ->
            val playback = work.sources.map(::legacyWorkId).distinct().filter { it != work.id }
                .mapNotNull(saved.byWork::get).plus(listOfNotNull(saved.byWork[work.id]))
                .fold(null as WorkPlayback?) { accumulated, record -> mergePlayback(accumulated, record) }
            work.copy(lastPlayedSite = playback?.site, recentPlayedSite = saved.recentSite,
                playback = playback?.resources.orEmpty(), playbackPositions = playback?.positions.orEmpty())
        })
    }

    suspend fun recentPlayback(): List<RecentPlayback> = stateMutex.withLock {
        playedSources().byWork.mapNotNull { (id, work) ->
            val recent = work.recent ?: return@mapNotNull null
            val bookmark = work.resources[work.site] ?: return@mapNotNull null
            RecentPlayback(id, recent.source, bookmark, work.positions[work.site].orEmpty(),
                recent.progressPercent, recent.playedAt, recent.cardSource ?: recent.source, recent.cardPoster)
        }.sortedByDescending { it.playedAt }.take(12)
    }

    suspend fun recordPlayback(workId: String, site: String, line: String, episode: String,
        source: SearchResult? = null, cardSource: SearchResult? = null, cardPoster: String? = null,
    ) = stateMutex.withLock {
        require(workId.isNotBlank() && site.isNotBlank() && line.isNotBlank())
        require(source == null || source.site == site)
        val saved = playedSources()
        val legacy = source?.takeIf { canonicalWorkId(it) == workId }?.let(::legacyWorkId)?.takeIf { it != workId }
        val current = saved.byWork[workId]
        val work = if (current != null) mergePlayback(legacy?.let(saved.byWork::get), current)
            else legacy?.let(saved.byWork::get)
        val bookmark = PlaybackBookmark(line, episode)
        val resources = work?.resources.orEmpty() + (site to bookmark)
        val previous = work?.recent?.takeIf { work.site == site }
        val progress = previous?.progressPercent?.takeIf { work?.resources?.get(site) == bookmark }
        val recent = if (source != null) RecentPlaybackMetadata(source, clock(), progress,
            work?.recent?.cardSource ?: cardSource, cardPoster ?: work?.recent?.cardPoster)
            else previous?.copy(progressPercent = progress)
        val updated = saved.copy(recentSite = site,
            byWork = (saved.byWork - listOfNotNull(legacy)) + (workId to WorkPlayback(site, resources, work?.positions.orEmpty(), recent)))
        if (updated != saved) savePlayedSources(updated)
        Unit
    }

    suspend fun recordPlaybackPosition(workId: String, site: String, line: String, episode: String,
        positionMillis: Long, progressPercent: Int? = null,
    ) = stateMutex.withLock {
        require(workId.isNotBlank() && site.isNotBlank() && line.isNotBlank() && positionMillis >= 0)
        val saved = playedSources()
        val work = saved.byWork[workId] ?: return@withLock
        val sitePositions = work.positions[site].orEmpty()
        val priorPosition = sitePositions[line]?.get(episode)
        val recent = work.recent?.let {
            if (work.site == site && work.resources[site] == PlaybackBookmark(line, episode)) {
                it.copy(progressPercent = progressPercent ?: it.progressPercent.takeIf { positionMillis == priorPosition })
            } else it
        }
        if (priorPosition == positionMillis && recent == work.recent) return@withLock
        val episodes = sitePositions[line].orEmpty() + (episode to positionMillis)
        val positions = work.positions + (site to (sitePositions + (line to episodes)))
        val updated = saved.copy(byWork = saved.byWork + (workId to work.copy(positions = positions, recent = recent)))
        savePlayedSources(updated)
        Unit
    }

    private suspend fun savePlayedSources(saved: PlayedSources) {
        try { storage.writeAtomic(playedSourcesPath, wireJson.encodeToString(saved)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { log("保存播放记录", e) }
    }

    private suspend fun searchSite(site: SourceSite, keyword: String): List<SearchResult> {
        val items = mutableListOf<SearchResult>()
        var page = 1
        do {
            // detail supports wd too and supplies year/type/poster needed for safe aggregation.
            val raw = network.get(apiUrl(site.api, mapOf("ac" to "detail", "wd" to keyword, "pg" to page.toString(), "pagesize" to "100")))
            val response = wireJson.decodeFromString<SearchPage>(raw)
            val batch = response.results(site.name)
            items += batch
            val pageCount = response.pageCount()
            if (batch.isEmpty() || page >= pageCount) break
            page++
            check(page <= 100) { "搜索分页超过 100 页" }
        } while (true)
        return items.distinctBy { it.vodId }
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
            coverCache[key]?.takeIf { clock() - it.first in 0 until 7 * DAY }?.let { return@withPermit it.second }
            val disk = cacheRead { wireJson.parseToJsonElement(storage.read(coverPath) ?: "{}").jsonObject }
            val entry = disk?.get(key) as? JsonObject
            val time = (entry?.get("fetchedAtMillis") as? JsonPrimitive)?.longOrNull ?: 0
            val url = entry?.text("url").orEmpty()
            if (url.isNotEmpty() && clock() - time in 0 until 7 * DAY) {
                coverCache[key] = time to url
                return@withPermit url
            }
        }
        try {
            val item = list(site(siteName), mapOf("ac" to "detail", "ids" to vodId.toString())).firstOrNull { it.vodId() == vodId }
            val url = item?.text("vod_pic")?.trim()?.takeIf { it.isNotEmpty() } ?: return@withPermit null
            stateMutex.withLock {
                coverCache[key] = clock() to url
                cacheRead {
                    val entries = (wireJson.parseToJsonElement(storage.read(coverPath) ?: "{}").jsonObject).toMutableMap()
                    entries[key] = buildJsonObject { put("url", url); put("fetchedAtMillis", clock()) }
                    storage.writeAtomic(coverPath, JsonObject(entries).toString())
                }
            }
            normalizeUrl(url)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { log(siteName, e); null }
    }

    suspend fun history(): List<String> = stateMutex.withLock {
        cacheRead { wireJson.decodeFromString<List<String>>(storage.read(historyPath) ?: "[]").take(20) }.orEmpty()
    }

    suspend fun addHistory(item: String) = updateHistory { (listOf(item) + it).distinct().take(20) }
    suspend fun removeHistory(item: String) = updateHistory { it.filterNot { saved -> saved == item } }
    suspend fun clearHistory() = updateHistory { emptyList() }

    private suspend fun updateHistory(update: (List<String>) -> List<String>) = stateMutex.withLock {
        val saved = cacheRead { wireJson.decodeFromString<List<String>>(storage.read(historyPath) ?: "[]") }.orEmpty()
        try { storage.writeAtomic(historyPath, wireJson.encodeToString(update(saved))) }
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
        private const val MAX_CACHED_SEARCHES = 5
        private const val MAX_CACHED_RESULTS = 10_000
    }
}

data class QuanxDomains(val api: Set<String>, val urls: Set<String>, val m3u8: Set<String>, val incompleteSites: List<String> = emptyList()) {
    fun render(): String = listOf("资源站 API 域名" to api, "播放/下载域名" to urls, "m3u8 域名" to m3u8).joinToString("\n") { (title, domains) ->
        (listOf("; $title") + domains.sorted().map { "host-suffix, $it, direct" }).joinToString("\n")
    }
}

@Serializable
private data class PlayedSources(val recentSite: String? = null, val byWork: Map<String, WorkPlayback> = emptyMap())

@Serializable
private data class WorkPlayback(val site: String, val resources: Map<String, PlaybackBookmark>,
    val positions: Map<String, Map<String, Map<String, Long>>> = emptyMap(),
    val recent: RecentPlaybackMetadata? = null)

@Serializable
private data class RecentPlaybackMetadata(
    val source: SearchResult, val playedAt: Long, val progressPercent: Int? = null,
    val cardSource: SearchResult? = null, val cardPoster: String? = null,
)
