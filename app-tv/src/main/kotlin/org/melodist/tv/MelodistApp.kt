package org.melodist.tv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
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

class MelodistApp :
    Application(),
    SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        org.melodist.api.ApiLogger.logger = { priority, tag, message, throwable ->
            if (throwable != null) {
                android.util.Log.println(priority, tag, "$message\n${android.util.Log.getStackTraceString(throwable)}")
            } else {
                android.util.Log.println(priority, tag, message)
            }
        }
        org.melodist.data.AppLifecycleManager
            .init(this)
        org.melodist.data.AppLifecycleManager.onTrimMemoryAction = {
            try {
                SingletonImageLoader.get(this@MelodistApp).memoryCache?.clear()
            } catch (_: Exception) {
            }
        }
        org.melodist.data.AppSettingsManager
            .init(this)
        org.melodist.data.AppSettingsManager.imageCacheClearAction = {
            try {
                SingletonImageLoader.get(this@MelodistApp).diskCache?.clear()
                SingletonImageLoader.get(this@MelodistApp).memoryCache?.clear()
                true
            } catch (_: Exception) {
                false
            }
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
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; MelodistTV) AppleWebKit/537.36")
                            .header("Referer", "https://y.qq.com/")
                            .build()
                    chain.proceed(request)
                }.build()

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = activityManager?.isLowRamDevice == true
        val memoryPercent = if (isLowRam) 0.15 else 0.25

        return ImageLoader
            .Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }.memoryCache {
                MemoryCache
                    .Builder()
                    .maxSizePercent(context, memoryPercent)
                    .build()
            }.diskCache {
                DiskCache
                    .Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024) // 256MB 磁盘缓存
                    .build()
            }.build()
    }
}
