package org.melodist.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.getSongDiary
import org.melodist.api.getSongListenRecords
import org.melodist.model.SongDiaryData
import org.melodist.model.SongListenRecordsPage

/**
 * 单曲倒带日记与历史收听记录短时内存缓存管理。
 */
object SongDiaryCacheManager {
    const val DEFAULT_TTL_MILLIS = 60_000L
    const val DEFAULT_MAX_SIZE = 30

    internal var timeProvider: () -> Long = { System.currentTimeMillis() }

    internal val diaryCache = TimedLruCache<String, SongDiaryData>(DEFAULT_MAX_SIZE, DEFAULT_TTL_MILLIS) { timeProvider() }
    internal val recordsCache = TimedLruCache<String, SongListenRecordsPage>(DEFAULT_MAX_SIZE, DEFAULT_TTL_MILLIS) { timeProvider() }

    private val apiService = MusicApiService()

    /**
     * 获取单曲倒带日记概览数据。
     *
     * @param songMid 歌曲唯一标识
     * @param forceRefresh 是否强制忽略缓存发起网络请求
     * @return 单曲倒带日记数据，获取失败或无数据时返回 null
     */
    suspend fun getSongDiary(
        songMid: String,
        forceRefresh: Boolean = false,
    ): SongDiaryData? =
        withContext(Dispatchers.IO) {
            if (songMid.isBlank()) return@withContext null
            if (!forceRefresh) {
                diaryCache.get(songMid)?.let { return@withContext it }
            }
            val remote = apiService.getSongDiary(songMid)
            if (remote != null) {
                diaryCache.put(songMid, remote)
            }
            remote
        }

    /**
     * 获取单曲历史收听记录流水分页。
     *
     * @param songMid 歌曲唯一标识
     * @param cursor 分页游标
     * @param forceRefresh 是否强制忽略缓存发起网络请求
     * @return 收听流水记录分页，获取失败时返回 null
     */
    suspend fun getSongListenRecords(
        songMid: String,
        cursor: String? = null,
        forceRefresh: Boolean = false,
    ): SongListenRecordsPage? =
        withContext(Dispatchers.IO) {
            if (songMid.isBlank()) return@withContext null
            val cacheKey = "$songMid:${cursor.orEmpty()}"
            if (!forceRefresh) {
                recordsCache.get(cacheKey)?.let { return@withContext it }
            }
            val remote = apiService.getSongListenRecords(songMid, cursor)
            if (remote != null) {
                recordsCache.put(cacheKey, remote)
            }
            remote
        }

    /**
     * 清理所有日记与收听记录内存缓存。
     */
    fun clear() {
        diaryCache.clear()
        recordsCache.clear()
    }
}
