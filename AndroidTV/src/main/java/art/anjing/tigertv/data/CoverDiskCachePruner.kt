package art.anjing.tigertv.data

import java.io.File

/**
 * 封面图片磁盘缓存的 TTL 清理：Coil DiskCache 只有 LRU 上限、无过期时间，
 * 启动时按文件修改日期删除超过 [TTL_MILLIS] 的图片文件，
 * 与三端统一的“封面 7 天两级缓存”约定对齐。
 */
object CoverDiskCachePruner {
    const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** 删除目录中修改时间早于 `now - ttl` 的文件，返回删除数量。Coil 的 journal 索引文件跳过。 */
    fun prune(directory: File, nowMillis: Long = System.currentTimeMillis(), ttlMillis: Long = TTL_MILLIS): Int {
        val files = directory.listFiles() ?: return 0
        val deadline = nowMillis - ttlMillis
        var removed = 0
        for (file in files) {
            if (file.name == JOURNAL_FILE) continue
            if (file.isFile && file.lastModified() < deadline && file.delete()) {
                removed++
            }
        }
        return removed
    }

    private const val JOURNAL_FILE = "journal"
}
