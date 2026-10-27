package art.anjing.tigertv.bridge

import art.anjing.tigertv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Versioned, UTF-8 JSON boundary used by the SwiftUI client. */
class NativeFacade internal constructor(private val service: CoreService) {
    constructor(directory: String) : this(CoreService(directory))
    constructor(cacheDirectory: String, dataDirectory: String) : this(CoreService(cacheDirectory, historyDirectory = dataDirectory))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var nextId = 1L
    private val jobs = mutableMapOf<Long, Job>()
    private val results = mutableMapOf<Long, String>()
    private var closed = false

    fun begin(request: String): Long = runBlocking {
        lock.withLock {
            check(!closed) { "Core is closed" }
            val id = nextId++
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val response = try {
                    val payload = wireJson.parseToJsonElement(request).jsonObject
                    check(payload["version"]?.jsonPrimitive?.int == 1) { "Unsupported bridge version" }
                    val data = when (payload.text("operation")) {
                        "config" -> wireJson.encodeToJsonElement(service.loadConfig())
                        "search" -> wireJson.encodeToJsonElement(service.search(payload.text("keyword")))
                        "fetch" -> wireJson.encodeToJsonElement(service.fetch(payload.text("site"), payload["vod_id"]!!.jsonPrimitive.int))
                        "resolve" -> JsonPrimitive(service.resolvePlaybackUrl(payload.text("url")))
                        "cover" -> service.resolveCoverFallback(payload.text("site"), payload["vod_id"]!!.jsonPrimitive.int)?.let(::JsonPrimitive) ?: JsonNull
                        "history" -> wireJson.encodeToJsonElement(service.history())
                        "saveHistory" -> { service.saveHistory(wireJson.decodeFromJsonElement(payload["items"]!!)); JsonPrimitive(true) }
                        else -> error("Unknown operation")
                    }
                    buildJsonObject { put("version", 1); put("data", data) }.toString()
                } catch (e: CancellationException) { return@launch }
                catch (e: Exception) {
                    buildJsonObject { put("version", 1); put("error", e.message ?: "Unknown error") }.toString()
                }
                lock.withLock { if (id in jobs) results[id] = response }
            }
            jobs[id] = job
            job.start()
            id
        }
    }

    /** Nonblocking. A non-null result consumes the request and its native job. */
    fun takeResult(id: Long): String? = runBlocking { lock.withLock {
        results.remove(id)?.also { jobs.remove(id) }
    } }

    /** Also releases completed results; no callback can escape after cancellation. */
    fun cancel(id: Long) { runBlocking { lock.withLock { jobs.remove(id)?.cancel(); results.remove(id) } } }

    fun close() {
        runBlocking { lock.withLock { closed = true; jobs.values.forEach { it.cancel() }; jobs.clear(); results.clear() } }
        scope.cancel()
        service.close()
    }
}
