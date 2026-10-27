@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package art.anjing.tigertv.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.posix.*

actual fun platformHttpClient(): HttpClient = configureClient(HttpClient(Darwin))
actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
actual fun temporaryDirectory(): String = "/tmp"
actual fun platformStorage(): Storage = object : Storage {
    override fun read(path: String): String? {
        val file = fopen(path, "rb") ?: return null
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file)
            check(size in 0..(MAX_RESPONSE_SIZE * 4).toLong()) { "File too large" }
            rewind(file)
            val bytes = ByteArray(size.toInt())
            if (bytes.isNotEmpty()) bytes.usePinned { check(fread(it.addressOf(0), 1u, bytes.size.toULong(), file) == bytes.size.toULong()) }
            return bytes.decodeToString()
        } finally { fclose(file) }
    }
    override fun writeAtomic(path: String, text: String) = memScoped {
        val directory = path.substringBeforeLast('/', ".")
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        val template = "$path.XXXXXX".cstr.getPointer(this)
        val fd = mkstemp(template)
        check(fd >= 0) { "Cannot create temporary file for $path" }
        val temp = template.toKString()
        try {
            val bytes = text.encodeToByteArray()
            bytes.usePinned { pinned ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = write(fd, pinned.addressOf(offset), (bytes.size - offset).toULong())
                    check(count > 0) { "Cannot write $path" }
                    offset += count.toInt()
                }
            }
            check(rename(temp, path) == 0) { "Cannot replace $path" }
        } finally { close(fd); unlink(temp) }
    }
}
