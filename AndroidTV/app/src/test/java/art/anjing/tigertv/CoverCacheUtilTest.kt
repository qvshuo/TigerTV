package art.anjing.tigertv

import art.anjing.tigertv.data.CoverDiskCachePruner
import art.anjing.tigertv.util.normalizeCoverUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CoverDiskCachePrunerTest {

    private fun tempDir(): File = Files.createTempDirectory("coverimages").toFile()

    @Test
    fun `deletes only files older than ttl`() {
        val dir = tempDir()
        dir.deleteOnExit()
        val now = 1_000_000_000L
        val fresh = File(dir, "fresh.bin").apply { writeBytes(byteArrayOf(1)) }
        val stale = File(dir, "stale.bin").apply { writeBytes(byteArrayOf(2)) }
        stale.setLastModified(now - CoverDiskCachePruner.TTL_MILLIS - 1)

        assertEquals(1, CoverDiskCachePruner.prune(dir, nowMillis = now))

        assertTrue(fresh.exists())
        assertFalse(stale.exists())
    }

    @Test
    fun `skips coil journal file`() {
        val dir = tempDir()
        dir.deleteOnExit()
        val journal = File(dir, "journal").apply { writeText("x") }
        journal.setLastModified(0)

        assertEquals(0, CoverDiskCachePruner.prune(dir, nowMillis = Long.MAX_VALUE / 2))

        assertTrue(journal.exists())
    }

    @Test
    fun `missing directory is a no-op`() {
        assertEquals(0, CoverDiskCachePruner.prune(File("/nonexistent/tigertv-prune-test")))
    }
}

class NormalizeCoverUrlTest {

    @Test
    fun `ascii url passes through untouched`() {
        val url = "https://pic.example.com/cover/73480.jpg?sign=abc"
        assertEquals(url, normalizeCoverUrl(url))
    }

    @Test
    fun `existing percent escapes are not double encoded`() {
        val url = "https://pic.example.com/cover/%E9%80%90.jpg"
        assertEquals(url, normalizeCoverUrl(url))
    }

    @Test
    fun `non-ascii path is percent encoded as utf-8`() {
        // 与 macOS HTTPClient.percentEncodedURL(from:) 同语义：只编非 ASCII。
        assertEquals(
            "https://pic.example.com/%E9%80%90%E7%8E%89/73480.jpg",
            normalizeCoverUrl("https://pic.example.com/逐玉/73480.jpg")
        )
    }

    @Test
    fun `blank input yields null and whitespace is trimmed`() {
        assertNull(normalizeCoverUrl("   "))
        assertNull(normalizeCoverUrl(""))
        assertEquals("https://a.com/x.jpg", normalizeCoverUrl(" https://a.com/x.jpg "))
    }
}
