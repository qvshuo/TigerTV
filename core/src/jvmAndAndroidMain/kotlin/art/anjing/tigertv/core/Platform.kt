package art.anjing.tigertv.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual fun platformHttpClient(): HttpClient = configureClient(HttpClient(OkHttp))
actual fun nowMillis(): Long = System.currentTimeMillis()
actual fun temporaryDirectory(): String = System.getProperty("java.io.tmpdir", "/tmp").orEmpty().trimEnd('/')
actual fun platformStorage(): Storage = object : Storage {
    override fun read(path: String): String? = File(path).takeIf { it.isFile }?.readText()
    override fun writeAtomic(path: String, text: String) {
        val target = File(path).absoluteFile
        val parent = requireNotNull(target.parentFile)
        parent.mkdirs()
        val temp = File.createTempFile(target.name, ".tmp", parent)
        try {
            temp.writeText(text)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
}
