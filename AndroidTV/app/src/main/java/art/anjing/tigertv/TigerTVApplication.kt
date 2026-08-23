package art.anjing.tigertv

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import okio.Path.Companion.toPath

/**
 * 全局 ImageLoader：Coil 3 默认不带磁盘缓存，必须显式配置，
 * 否则封面每次回到结果页都会重新下载。磁盘上限 64MB，LRU 淘汰。
 */
class TigerTVApplication : Application(), SingletonImageLoader.Factory {
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
