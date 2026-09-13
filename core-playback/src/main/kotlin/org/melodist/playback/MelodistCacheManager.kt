package org.melodist.playback

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat

@OptIn(UnstableApi::class)
object MelodistCacheManager {
    private const val TAG = "MelodistCache"
    private const val CACHE_SUBDIR = "media_cache"

    // 默认最高配额 1GB，最低保护配额 128MB
    const val DEFAULT_MAX_QUOTA_BYTES = 1024L * 1024L * 1024L
    const val MIN_PROTECT_QUOTA_BYTES = 128L * 1024L * 1024L
    const val STORAGE_SAFE_RATIO = 0.20 // 最多占用可用空间的 20%

    @Volatile
    private var simpleCache: SimpleCache? = null

    @Volatile
    private var databaseProvider: StandaloneDatabaseProvider? = null

    @Volatile
    private var isInitialized = false

    /**
     * 根据电视设备实际可用 ROM 空间动态计算缓存安全配额
     */
    fun calculateAdaptiveCacheQuotaBytes(cacheDir: File): Long {
        val availableBytes =
            try {
                if (cacheDir.exists()) cacheDir.usableSpace else 0L
            } catch (_: Exception) {
                0L
            }
        val safeQuota = (availableBytes * STORAGE_SAFE_RATIO).toLong()
        return when {
            safeQuota <= 0L -> MIN_PROTECT_QUOTA_BYTES
            safeQuota < MIN_PROTECT_QUOTA_BYTES -> MIN_PROTECT_QUOTA_BYTES
            safeQuota > DEFAULT_MAX_QUOTA_BYTES -> DEFAULT_MAX_QUOTA_BYTES
            else -> safeQuota
        }
    }

    /**
     * 初始化全局缓存池（线程安全）
     */
    @Synchronized
    fun init(context: Context) {
        if (isInitialized && simpleCache != null) return

        val appContext = context.applicationContext
        val cacheFolder = File(appContext.cacheDir, CACHE_SUBDIR)
        if (!cacheFolder.exists()) {
            cacheFolder.mkdirs()
        }

        val quotaBytes = calculateAdaptiveCacheQuotaBytes(appContext.cacheDir)
        Log.i(TAG, "Initializing TV media cache at ${cacheFolder.absolutePath} with quota: ${formatBytes(quotaBytes)}")

        try {
            val dbProvider = StandaloneDatabaseProvider(appContext).also { databaseProvider = it }
            val evictor = LeastRecentlyUsedCacheEvictor(quotaBytes)
            simpleCache = SimpleCache(cacheFolder, evictor, dbProvider)
            isInitialized = true
            Log.i(TAG, "Media cache initialized successfully, current cached space: ${formatBytes(simpleCache?.cacheSpace ?: 0L)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Media3 SimpleCache, falling back to un-cached streaming", e)
            simpleCache = null
            isInitialized = false
        }
    }

    /**
     * 为 ExoPlayer 构造装配了全局磁盘缓存与自动容错的数据源工厂
     */
    fun buildCacheDataSourceFactory(
        context: Context,
        upstreamFactory: DataSource.Factory,
    ): DataSource.Factory {
        init(context)
        val cache = simpleCache
        if (cache == null) {
            Log.w(TAG, "SimpleCache not ready, using raw upstream DataSource")
            return upstreamFactory
        }

        return CacheDataSource
            .Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            // 当电视闪存由于只读、坏块或突发写满导致缓存异常时，自动忽略错误并降级为直接网络拉取，确保播放不中断
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * 获取当前音频缓存已占用的总字节数
     */
    fun getCacheSizeBytes(): Long =
        try {
            simpleCache?.cacheSpace ?: 0L
        } catch (_: Exception) {
            0L
        }

    /**
     * 获取当前已缓存音频的键数（曲目资源数）
     */
    fun getCachedKeyCount(): Int =
        try {
            simpleCache?.keys?.size ?: 0
        } catch (_: Exception) {
            0
        }

    /**
     * 异步清空所有缓存分片
     */
    suspend fun clearAllCache(): Boolean =
        withContext(Dispatchers.IO) {
            val cache = simpleCache ?: return@withContext false
            try {
                val keys = cache.keys.toList()
                for (key in keys) {
                    try {
                        cache.removeResource(key)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove cache resource for key: $key", e)
                    }
                }
                Log.i(TAG, "Cleared media cache, remaining space: ${formatBytes(cache.cacheSpace)}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear all media cache", e)
                false
            }
        }

    /**
     * 字节单位友好格式化
     */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        if (digitGroups >= units.size) digitGroups = units.size - 1
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return DecimalFormat("#,##0.#").format(value) + " " + units[digitGroups]
    }
}
