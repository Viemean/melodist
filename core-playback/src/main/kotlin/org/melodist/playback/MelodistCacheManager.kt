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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.DecimalFormat
import java.util.concurrent.ConcurrentHashMap

@OptIn(UnstableApi::class)
object MelodistCacheManager {
    private const val TAG = "MelodistCache"
    private const val CACHE_SUBDIR = "media_cache"
    private const val STATS_FILE_NAME = "media_play_stats.json"

    // 默认最高配额 2GB，最低保护配额 128MB，最多占用剩余可用空间的 20%
    const val DEFAULT_MAX_QUOTA_BYTES = 2048L * 1024L * 1024L
    const val MIN_PROTECT_QUOTA_BYTES = 128L * 1024L * 1024L
    const val STORAGE_SAFE_RATIO = 0.20

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    @Volatile
    private var simpleCache: SimpleCache? = null

    @Volatile
    private var databaseProvider: StandaloneDatabaseProvider? = null

    @Volatile
    private var isInitialized = false

    private val playCountMap = ConcurrentHashMap<String, Int>()
    private var statsFile: File? = null

    @Volatile
    private var currentSessionMarkedSongMid: String? = null

    /**
     * 根据电视设备实际可用 ROM 空间动态计算缓存安全配额（上限 2GB）
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
     * 初始化全局缓存池与播放统计（线程安全）
     */
    @Synchronized
    fun init(context: Context) {
        if (isInitialized && simpleCache != null) return

        val appContext = context.applicationContext
        val cacheFolder = File(appContext.cacheDir, CACHE_SUBDIR)
        if (!cacheFolder.exists()) {
            cacheFolder.mkdirs()
        }

        statsFile = File(appContext.filesDir, STATS_FILE_NAME)
        loadStatsFromDisk()

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

    private fun loadStatsFromDisk() {
        val file = statsFile ?: return
        if (file.exists() && file.length() > 0L) {
            try {
                val text = file.readText()
                val map = json.decodeFromString<Map<String, Int>>(text)
                playCountMap.clear()
                playCountMap.putAll(map)
                Log.i(TAG, "Loaded play stats for ${playCountMap.size} songs from disk")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read play stats from disk", e)
            }
        }
    }

    private fun saveStatsAsync() {
        val file = statsFile ?: return
        scope.launch {
            try {
                val snapshot = playCountMap.toMap()
                val text = json.encodeToString(snapshot)
                file.writeText(text)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist play stats to disk", e)
            }
        }
    }

    /**
     * 歌曲开始新一轮播放时重置会话标记
     */
    fun onNewSongStarted(songMid: String?) {
        currentSessionMarkedSongMid = null
    }

    /**
     * 记录当前曲目播放进度（当播放完整度 >= 80% 或累计播放满 180 秒时累计有效播放 1 次）
     */
    fun recordPlayProgress(songMid: String, positionMs: Long, durationMs: Long) {
        if (songMid.isBlank() || durationMs <= 0L) return
        if (currentSessionMarkedSongMid == songMid) return

        val isCompletedEnough = (positionMs.toFloat() / durationMs >= 0.80f) || (positionMs >= 180_000L)
        if (isCompletedEnough) {
            currentSessionMarkedSongMid = songMid
            val newCount = (playCountMap[songMid] ?: 0) + 1
            playCountMap[songMid] = newCount
            Log.i(TAG, "Recorded valid play for songMid=$songMid, current count=$newCount (pos=$positionMs, dur=$durationMs)")
            saveStatsAsync()
        }
    }

    /**
     * 获取单曲历史有效播放次数
     */
    fun getPlayCount(songMid: String): Int = playCountMap[songMid] ?: 0

    /**
     * 智能准入判定：
     * 收藏曲目需要有效播放大于等于 1 次；普通曲目需要有效播放大于等于 2 次。
     */
    fun shouldCacheSong(songMid: String, isFavorite: Boolean): Boolean {
        if (songMid.isBlank()) return false
        val count = getPlayCount(songMid)
        val threshold = if (isFavorite) 1 else 2
        return count >= threshold
    }

    /**
     * 检查某个 URI 是否已经在磁盘缓存中
     */
    fun isUriCached(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val cache = simpleCache ?: return false
        return try {
            cache.isCached(uriString, 0, 1024) || cache.keys.contains(uriString)
        } catch (_: Exception) {
            false
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

