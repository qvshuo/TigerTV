package art.anjing.tigertv.core

import io.ktor.client.HttpClient

/** Platform I/O only. Business rules must stay in commonMain. */
interface Storage {
    fun read(path: String): String?
    fun writeAtomic(path: String, text: String)
}

expect fun platformStorage(): Storage
expect fun platformHttpClient(): HttpClient
expect fun nowMillis(): Long
expect fun temporaryDirectory(): String

fun interface Logger {
    fun log(level: String, context: String, message: String)
}

class FileLogger(private val storage: Storage, val path: String) : Logger {
    // All access goes through CoreService's serialized logger entry point on Native.
    override fun log(level: String, context: String, message: String) {
        runCatching {
            val old = storage.read(path).orEmpty().lines().filter { it.isNotEmpty() }
            val lines = if (old.size > 5000) old.takeLast(2000) else old
            val timestamp = kotlin.time.Clock.System.now().toString().take(19).replace('T', ' ')
            storage.writeAtomic(path, (lines + "$timestamp [$level] [$context]: ${message.replace('\n', ' ')}").joinToString("\n", postfix = "\n"))
        }
    }
}
