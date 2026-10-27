package art.anjing.tigertv.core

import art.anjing.tigertv.domain.*
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import kotlinx.serialization.json.*

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
    private val configMutex = Mutex()
    private val stateMutex = Mutex()
    private val logMutex = Mutex()
    private val coverSemaphore = Semaphore(4)
    private val searchCache = mutableMapOf<String, Pair<Long, SearchResponse>>()
    private val coverCache = mutableMapOf<String, Pair<Long, String>>()
    private val configPath = "$directory/tigertv-cli-config-cache.json"
    private val coverPath = "$directory/cover-fallback-cache.json"
    private val historyPath = "$historyDirectory/search-history.json"

    private suspend fun log(context: String, error: Exception) = logMutex.withLock {
        logger.log("WARN", context, error.message ?: error.toString())
    }

    private inline fun <T> cacheRead(block: () -> T): T? = try { block() } catch (_: Exception) { null }

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

    private suspend fun list(site: SourceSite, parameters: Map<String, String>) =
        parseVodList(network.get(apiUrl(site.api, parameters)))

    suspend fun search(keyword: String): SearchResponse {
        stateMutex.withLock {
            searchCache[keyword]?.takeIf { clock() - it.first in 0 until searchCacheTtlMillis }?.let { return it.second }
        }
        val sites = loadConfig().apiSite.values
        val semaphore = Semaphore(20)
        val results = coroutineScope {
            sites.map { site -> async {
                semaphore.withPermit {
                    try {
                        list(site, mapOf("ac" to "list", "wd" to keyword, "pagesize" to "100")).map {
                            SearchResult(site.name, lenientInt(it["vod_id"]), it.text("vod_name"), it.text("vod_time"), it.text("vod_remarks"), it.text("vod_pic"))
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { log(site.name, e); emptyList() }
                }
            } }.awaitAll().flatten()
        }
        return SearchResponse(keyword, results).also { response ->
            stateMutex.withLock { searchCache[keyword] = clock() to response }
        }
    }

    private suspend fun site(name: String): SourceSite {
        val sites = loadConfig().apiSite.values
        return sites.firstOrNull { it.name == name }
            ?: error("未找到站点: $name\n可用站点: ${sites.map { it.name }.distinct().sorted().joinToString(", ")}")
    }

    suspend fun fetch(siteName: String, vodId: Int): FetchResponse {
        val item = list(site(siteName), mapOf("ac" to "detail", "ids" to vodId.toString())).firstOrNull() ?: error("未找到该视频")
        return FetchResponse(vodId, siteName, parseEpisodeLinks(item.text("vod_play_url")), parseEpisodeLinks(item.text("vod_down_url")))
    }

    suspend fun resolvePlaybackUrl(url: String): String {
        val path = Url(normalizeUrl(url)).encodedPath.lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".mp4")) return normalizeUrl(url)
        val text = network.get(url, 10_000)
        if (text.trimStart().startsWith("#EXTM3U", ignoreCase = true)) return normalizeUrl(url)
        val absolute = Regex("https?://[^\\s\"'<>]+\\.m3u8(?:\\?[^\\s\"'<>]+)?", RegexOption.IGNORE_CASE)
            .find(text.replace("\\/", "/"))?.value
        if (absolute != null) return normalizeUrl(percentDecode(absolute))
        return extractM3u8(url, text).firstOrNull() ?: error("无法解析播放地址")
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
            val item = list(site(siteName), mapOf("ac" to "detail", "ids" to vodId.toString())).firstOrNull()
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

    suspend fun saveHistory(items: List<String>) = stateMutex.withLock {
        cacheRead { storage.writeAtomic(historyPath, wireJson.encodeToString(items.distinct().take(20))) }
        Unit
    }

    suspend fun collectQuanxDomains(keyword: String): QuanxDomains {
        val sites = loadConfig().apiSite.values
        val cache = mutableMapOf<String, Set<String>>()
        val cacheMutex = Mutex()
        val semaphore = Semaphore(20)
        val parts = coroutineScope {
            sites.map { site -> async {
                semaphore.withPermit {
                    val links = mutableSetOf<String>()
                    val playlists = mutableSetOf<String>()
                    try {
                        val first = list(site, mapOf("ac" to "list", "wd" to keyword, "pagesize" to "1")).firstOrNull()
                        val id = first?.get("vod_id") as? JsonPrimitive
                        if (id != null && id.contentOrNull != null && id.content != "0") {
                            val details = list(site, mapOf("ac" to "detail", "ids" to id.content))
                            for (item in details) for (field in listOf("vod_play_url", "vod_down_url")) {
                                for (link in parseEpisodeLinks(item.text(field), firstOnly = true)) {
                                    if (link.url.isEmpty()) continue
                                    links += domain(link.url)
                                    val urls = try { probeM3u8(link.url) } catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { log(site.name, e); emptyList() }
                                    for (url in urls) playlists += playlistDomains(url, 0, cache, cacheMutex)
                                }
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { log(site.name, e) }
                    links to playlists
                }
            } }.awaitAll()
        }
        return QuanxDomains(sites.map { domain(it.api) }.toSet(), parts.flatMap { it.first }.toSet(), parts.flatMap { it.second }.toSet())
    }

    private suspend fun probeM3u8(url: String): List<String> {
        val path = Url(normalizeUrl(url)).encodedPath.lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".mp4")) return listOf(url)
        val text = network.get(url, 10_000)
        return if (text.trimStart().startsWith("#EXTM3U")) listOf(url) else extractM3u8(url, text)
    }

    private suspend fun playlistDomains(url: String, depth: Int, cache: MutableMap<String, Set<String>>, mutex: Mutex): Set<String> {
        mutex.withLock { cache[url]?.let { return it } }
        if (depth > 2) return emptySet() // Never cache a depth-truncated result.
        val domains = mutableSetOf(domain(url))
        try {
            val lines = network.get(url, 10_000).lines().map { it.trim() }
            val children = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }.mapNotNull { index ->
                lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
            }.take(3)
            // KEY/MEDIA declarations can occur in both master and media playlists.
            for (line in lines.filter { it.startsWith("#EXT-X-KEY") || it.startsWith("#EXT-X-MEDIA") }) {
                Regex("URI=[\"']?([^,\"']+)").find(line)?.groupValues?.get(1)?.let { domains += domain(resolveUrl(url, it)) }
            }
            if (children.isNotEmpty()) {
                for (child in children) {
                    val resolved = resolveUrl(url, child)
                    domains += domain(resolved)
                    domains += playlistDomains(resolved, depth + 1, cache, mutex)
                }
            } else for (line in lines.filter { it.isNotEmpty() && !it.startsWith('#') }) domains += domain(resolveUrl(url, line))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { log("m3u8", e) }
        return domains.toSet().also { mutex.withLock { cache[url] = it } }
    }

    fun close() = client.close()

    companion object { const val DAY = 86_400_000L }
}

data class QuanxDomains(val api: Set<String>, val urls: Set<String>, val m3u8: Set<String>) {
    fun render(): String = listOf("资源站 API 域名" to api, "播放/下载域名" to urls, "m3u8 域名" to m3u8).joinToString("\n") { (title, domains) ->
        (listOf("; $title") + domains.sorted().map { "host-suffix, $it, direct" }).joinToString("\n")
    }
}
