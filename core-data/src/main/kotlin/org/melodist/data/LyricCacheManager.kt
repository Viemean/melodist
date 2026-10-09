package org.melodist.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.melodist.model.LyricLine
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object LyricCacheManager {
    private const val TAG = "LyricCacheManager"
    private const val LYRIC_SUBDIR = "cached_lyrics"
    private const val OFFSET_FILE_NAME = "lyric_offsets.json"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    private var lyricsDir: File? = null
    private var baseCacheDir: File? = null
    private val memoryCache = ConcurrentHashMap<String, List<LyricLine>>()
    private val remotelySyncedMids = ConcurrentHashMap.newKeySet<String>()
    private val offsetMap = ConcurrentHashMap<String, Long>()

    /**
     * 初始化歌词缓存管理器，构建磁盘缓存目录并加载时间轴偏移记录。
     *
     * @param context 应用程序上下文
     */
    fun init(context: Context) {
        if (lyricsDir != null) return
        val appCache = context.applicationContext.cacheDir
        baseCacheDir = appCache
        val dir = File(appCache, LYRIC_SUBDIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        lyricsDir = dir
        loadOffsetMap(appCache)
    }

    /**
     * 判定候选歌词质量是否优于当前已有歌词（判定标准为有效性 > 逐行翻译覆盖 > 总行数完整度）。
     *
     * @param candidate 待评估的候选歌词行列表
     * @param current 当前持有的歌词行列表
     * @return 候选歌词质量更优时返回 true，否则返回 false
     */
    fun isBetterQuality(
        candidate: List<LyricLine>,
        current: List<LyricLine>,
    ): Boolean {
        if (candidate.isEmpty()) return false
        val candidateIsPlaceholder =
            org.melodist.api.LyricParser
                .isPlaceholderLyrics(candidate)
        val currentIsPlaceholder =
            org.melodist.api.LyricParser
                .isPlaceholderLyrics(current)

        if (candidateIsPlaceholder && !currentIsPlaceholder) return false
        // 有效歌词必定优于占位歌词
        if (!candidateIsPlaceholder && currentIsPlaceholder) return true

        if (current.isEmpty()) return true

        val candidateHasTrans = candidate.any { it.hasTranslation }
        val currentHasTrans = current.any { it.hasTranslation }

        if (candidateHasTrans && !currentHasTrans) return true
        if (!candidateHasTrans && currentHasTrans) return false

        return candidate.size > current.size
    }

    /**
     * 查询指定曲目的歌词是否已完成远端全量同步标记。
     *
     * @param songMid 歌曲唯一标识符
     * @return 已同步返回 true，否则返回 false
     */
    fun isRemoteSynced(songMid: String): Boolean {
        if (songMid.isBlank()) return false
        return remotelySyncedMids.contains(songMid)
    }

    /**
     * 标记指定曲目已完成远端全量歌词同步。
     *
     * @param songMid 歌曲唯一标识符
     */
    fun markRemoteSynced(songMid: String) {
        if (songMid.isNotBlank()) {
            remotelySyncedMids.add(songMid)
        }
    }

    /**
     * 获取指定歌曲的缓存歌词（内存缓存优先，未命中则同步从磁盘 JSON 文件读取并回填内存）。
     *
     * @param songMid 歌曲唯一标识符
     * @return 解析后的歌词行列表；缓存未命中或解析失败返回 null
     */
    fun getLyrics(songMid: String): List<LyricLine>? {
        if (songMid.isBlank()) return null
        memoryCache[songMid]?.let { return it }

        val dir = lyricsDir ?: return null
        val file = File(dir, "${sanitizeFileName(songMid)}.json")
        if (file.exists() && file.length() > 0L) {
            return try {
                val content = file.readText()
                val list = json.decodeFromString<List<LyricLine>>(content)
                if (list.isNotEmpty()) {
                    memoryCache[songMid] = list
                }
                list
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read cached lyric for $songMid", e)
                null
            }
        }
        return null
    }

    /**
     * 写入歌词缓存（同步更新内存，并在 IO 协程中异步落盘为 JSON 文件）。
     *
     * @param songMid 歌曲唯一标识符
     * @param lyrics 待缓存的歌词行列表
     * @param isRemoteSynced 是否同步打上远端已同步标记
     */
    fun saveLyrics(
        songMid: String,
        lyrics: List<LyricLine>,
        isRemoteSynced: Boolean = false,
    ) {
        if (songMid.isBlank() || lyrics.isEmpty()) return
        memoryCache[songMid] = lyrics
        if (isRemoteSynced) {
            remotelySyncedMids.add(songMid)
        }

        scope.launch {
            val dir = lyricsDir ?: return@launch
            try {
                val file = File(dir, "${sanitizeFileName(songMid)}.json")
                val content = json.encodeToString(lyrics)
                file.writeText(content)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save cached lyric for $songMid", e)
            }
        }
    }

    /**
     * 检查是否存在针对指定曲目的歌词时间轴校准偏移记录。
     *
     * @param songKey 歌曲唯一定位 Key
     * @return 存在偏移记录返回 true，否则返回 false
     */
    fun hasLyricOffsetRecord(songKey: String): Boolean {
        if (songKey.isBlank()) return false
        return offsetMap.containsKey(songKey)
    }

    /**
     * 读取指定曲目的歌词时间轴校准偏移量。
     *
     * @param songKey 歌曲唯一定位 Key
     * @return 时间轴偏移毫秒数（正数为延后，负数为提前），未设置时返回 0L
     */
    fun getLyricOffsetMs(songKey: String): Long {
        if (songKey.isBlank()) return 0L
        return offsetMap[songKey] ?: 0L
    }

    /**
     * 持久化保存曲目的歌词时间轴校准偏移量。
     *
     * @param songKey 歌曲唯一定位 Key
     * @param offsetMs 偏移毫秒数
     */
    fun saveLyricOffsetMs(
        songKey: String,
        offsetMs: Long,
    ) {
        if (songKey.isBlank()) return
        offsetMap[songKey] = offsetMs
        scope.launch {
            val cacheDir = baseCacheDir ?: return@launch
            try {
                val file = File(cacheDir, OFFSET_FILE_NAME)
                val snapshot = HashMap(offsetMap)
                val text = json.encodeToString(snapshot)
                file.writeText(text)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist lyric offset for $songKey", e)
            }
        }
    }

    private fun loadOffsetMap(cacheDir: File) {
        try {
            val file = File(cacheDir, OFFSET_FILE_NAME)
            if (file.exists() && file.length() > 0L) {
                val text = file.readText()
                val loaded = json.decodeFromString<Map<String, Long>>(text)
                offsetMap.putAll(loaded)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load lyric offset map", e)
        }
    }

    private fun sanitizeFileName(name: String): String = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}
