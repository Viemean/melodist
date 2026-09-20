package org.melodist.playback

import android.content.Context
import android.util.Log
import coil3.SingletonImageLoader
import org.melodist.model.Song

/**
 * 封面大图内存管理器。
 * 核心策略：
 * 1. 内存滑动窗口：播放时仅在内存中保留“前一首、当前首、后一首”共 3 首曲目的大图，多余的大图及时卸载回磁盘；
 * 2. 快速切歌防内存雪崩：快速连切时即时触发驱逐，避免跳过曲目的原图在内存中积压；
 * 3. 歌单与专辑详情大图即用即清：退出详情页时定向卸载其大图缓存。
 */
object CoverMemoryManager {
    private const val TAG = "CoverMemoryManager"

    /**
     * 针对指定封面 URL，将其从 Coil 内存缓存中即时卸载释放
     */
    fun evictCoverFromMemory(context: Context, url: String) {
        if (url.isBlank()) return
        try {
            val memCache = SingletonImageLoader.get(context).memoryCache ?: return
            val cleanUrl = url.substringBefore('?')
            val keysToRemove = memCache.keys.filter { it.key.contains(cleanUrl) }
            for (key in keysToRemove) {
                memCache.remove(key)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to evict cover from memoryCache: $url", e)
        }
    }

    /**
     * 滑动窗口内存裁剪：在内存中严格保留 [prevSong], [currSong], [nextSong] 对应的大图，其余大图移出内存。
     */
    fun trimMemoryWindow(
        context: Context,
        prevSong: Song?,
        currSong: Song?,
        nextSong: Song?,
    ) {
        try {
            val memCache = SingletonImageLoader.get(context).memoryCache ?: return
            val activeSongs = listOfNotNull(prevSong, currSong, nextSong)
            val keepUrls =
                activeSongs.flatMap { song ->
                    listOfNotNull(
                        song.rawCoverUrl.takeIf { it.isNotBlank() }?.substringBefore('?'),
                        song.coverUrl.takeIf { it.isNotBlank() }?.substringBefore('?'),
                    )
                }.filter { it.isNotBlank() }.toSet()

            val keysToEvict =
                memCache.keys.filter { key ->
                    val keyStr = key.key
                    val isRawOrLarge =
                        keyStr.contains("cover_raw_") ||
                            keyStr.contains("webdav_raw_") ||
                            keyStr.contains("M000") ||
                            keyStr.contains("R1200x1200")
                    isRawOrLarge && keepUrls.none { keep -> keyStr.contains(keep) }
                }

            for (k in keysToEvict) {
                memCache.remove(k)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trim memory cache window", e)
        }
    }
}
