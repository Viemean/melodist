package org.melodist.mobile

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MelodistMobileApp :
    Application(),
    SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        org.melodist.data.AppLifecycleManager
            .init(this)
        org.melodist.data.AppLifecycleManager.onTrimMemoryAction = {
            try {
                SingletonImageLoader.get(this@MelodistMobileApp).memoryCache?.clear()
            } catch (_: Exception) {
            }
        }
        org.melodist.data.AppSettingsManager
            .init(this)
        org.melodist.playback.AudioQualityAuditor
            .cleanOrphanProbeFiles(this)
        org.melodist.data.AppSettingsManager.imageCacheClearAction = {
            try {
                SingletonImageLoader.get(this@MelodistMobileApp).diskCache?.clear()
                SingletonImageLoader.get(this@MelodistMobileApp).memoryCache?.clear()
                true
            } catch (_: Exception) {
                false
            }
        }
        try {
            if (packageName == "com.tencent.qqmusic") {
                val serviceIntent =
                    android.content.Intent().apply {
                        setComponent(android.content.ComponentName("com.tencent.qqmusic", "com.tencent.qqmusic.third.api.QQMusicApiService"))
                    }
                startService(serviceIntent)
            }
        } catch (_: Exception) {
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val okHttpClient =
            OkHttpClient
                .Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .addInterceptor { chain: Interceptor.Chain ->
                    val original = chain.request()
                    val request =
                        original
                            .newBuilder()
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16; MelodistMobile) AppleWebKit/537.36")
                            .header("Referer", "https://y.qq.com/")
                            .build()
                    chain.proceed(request)
                }.build()

        return ImageLoader
            .Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }.memoryCache {
                MemoryCache
                    .Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }.diskCache {
                DiskCache
                    .Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }.build()
    }
}
