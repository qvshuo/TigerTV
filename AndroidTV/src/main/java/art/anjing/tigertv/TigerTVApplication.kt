package art.anjing.tigertv

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import art.anjing.tigertv.core.USER_AGENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import art.anjing.tigertv.data.CoverDiskCachePruner
import okio.Path.Companion.toPath

class TigerTVApplication : Application(), SingletonImageLoader.Factory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val dir = cacheDir.resolve("cover_images")
        appScope.launch { runCatching { CoverDiskCachePruner.prune(dir) } }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = {
                    OkHttpClient.Builder()
                        .addNetworkInterceptor { chain ->
                            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
                        }
                        .build()
                }))
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("cover_images").absolutePath.toPath())
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .build()
}
