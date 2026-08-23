package art.anjing.tigertv

import art.anjing.tigertv.data.CoverFallbackDiskCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CoverFallbackDiskCacheTest {

    private fun tempDir(): File = Files.createTempDirectory("covercache").toFile()

    @Test
    fun `persists across instances`() = runBlocking {
        // 模拟进程重启：新实例从同一目录读回应命中。
        val dir = tempDir()
        dir.deleteOnExit()
        CoverFallbackDiskCache(dir).put("site-1", "https://pic.example.com/a.jpg")

        assertEquals("https://pic.example.com/a.jpg", CoverFallbackDiskCache(dir).get("site-1"))
    }

    @Test
    fun `expires after 7 days and purges entry`() = runBlocking {
        val dir = tempDir()
        dir.deleteOnExit()
        val cache = CoverFallbackDiskCache(dir)
        cache.put("site-2", "https://pic.example.com/old.jpg", fetchedAtMillis = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)

        assertNull(cache.get("site-2"))
        // 过期项已被惰性清除，重建实例也读不到。
        assertNull(CoverFallbackDiskCache(dir).get("site-2"))
    }

    @Test
    fun `corrupt file degrades to empty cache`() = runBlocking {
        val dir = tempDir()
        dir.deleteOnExit()
        dir.mkdirs()
        File(dir, "cover-fallback-cache.json").writeText("{not json")

        assertNull(CoverFallbackDiskCache(dir).get("any"))
    }
}
