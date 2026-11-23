package art.anjing.tigertv.data

import java.io.File

// Coil 的 LRU 磁盘缓存没有 TTL。
object CoverDiskCachePruner {
    const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

    fun prune(directory: File, nowMillis: Long = System.currentTimeMillis(), ttlMillis: Long = TTL_MILLIS): Int {
        val files = directory.listFiles() ?: return 0
        val deadline = nowMillis - ttlMillis
        var removed = 0
        for (file in files) {
            if (file.name.startsWith("journal")) continue
            if (file.isFile && file.lastModified() < deadline && file.delete()) {
                removed++
            }
        }
        return removed
    }

}
