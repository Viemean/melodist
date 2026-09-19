package org.melodist.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.melodist.model.LyricLine
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object LyricCacheManager {
    private const val TAG = "LyricCacheManager"
    private const val LYRIC_SUBDIR = "cached_lyrics"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    private var lyricsDir: File? = null
    private val memoryCache = ConcurrentHashMap<String, List<LyricLine>>()
    private val remotelySyncedMids = ConcurrentHashMap.newKeySet<String>()

    fun init(context: Context) {
        if (lyricsDir != null) return
        val dir = File(context.applicationContext.cacheDir, LYRIC_SUBDIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        lyricsDir = dir
    }

    /**
     * 判断待同步或候选歌词是否优于当前歌词。
     * 判定标准：
     * 1. 是否包含逐行中文翻译（优先级最高）
     * 2. 歌词总行数（更完整者胜）
     */
    fun isBetterQuality(candidate: List<LyricLine>, current: List<LyricLine>): Boolean {
        if (candidate.isEmpty()) return false
        if (current.isEmpty()) return true

        val candidateHasTrans = candidate.any { it.hasTranslation }
        val currentHasTrans = current.any { it.hasTranslation }

        if (candidateHasTrans && !currentHasTrans) return true
        if (!candidateHasTrans && currentHasTrans) return false

        return candidate.size > current.size
    }

    fun isRemoteSynced(songMid: String): Boolean {
        if (songMid.isBlank()) return false
        return remotelySyncedMids.contains(songMid)
    }

    fun markRemoteSynced(songMid: String) {
        if (songMid.isNotBlank()) {
            remotelySyncedMids.add(songMid)
        }
    }

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

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}
