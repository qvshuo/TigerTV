package art.anjing.tigertv

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import art.anjing.tigertv.data.CoverDiskCachePruner
import okio.Path.Companion.toPath

/**
 * 全局 ImageLoader：Coil 3 默认不带磁盘缓存，必须显式配置，
 * 否则封面每次回到结果页都会重新下载。磁盘上限 64MB，LRU 淘汰。
 * 启动时另起协程按 7 天 TTL 清理过期图片文件（Coil 无内建 TTL）。
 */
class TigerTVApplication : Application(), SingletonImageLoader.Factory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 惰性清理：不阻塞启动，删不掉（正被 Coil 使用）也无害。
        val dir = cacheDir.resolve("cover_images")
        appScope.launch { runCatching { CoverDiskCachePruner.prune(dir) } }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("cover_images").absolutePath.toPath())
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .build()
}
