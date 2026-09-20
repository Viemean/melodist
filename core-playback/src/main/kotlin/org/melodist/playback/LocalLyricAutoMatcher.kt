package org.melodist.playback

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.melodist.api.MusicApiService
import org.melodist.api.acr.AcousticRecognizeClient
import org.melodist.api.getLyrics
import org.melodist.api.search
import org.melodist.model.LyricLine
import org.melodist.model.Song
import java.io.File
import java.security.MessageDigest

@Serializable
data class CachedMatchedLyric(
    val songMid: String,
    val title: String,
    val artist: String,
    val matchedTimeMs: Long,
    val lyricLines: List<CachedLyricLine>,
)

@Serializable
data class CachedLyricLine(
    val timestampMs: Long,
    val text: String,
    val transText: String = "",
) {
    fun toLyricLine(): LyricLine = LyricLine(timestampMs, text, transText)
}

/**
 * 本地与 WebDAV 音乐智能歌词匹配器
 * 采用文本初筛与音频切片声学指纹 (ACR) 双轨级联机制，
 * 匹配到的歌词保存至应用私有缓存目录。
 */
object LocalLyricAutoMatcher {
    private const val TAG = "LocalLyricAutoMatcher"
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }
    private val acrClient = AcousticRecognizeClient()
    private val apiService = MusicApiService()

    private var appContext: Context? = null
    private var cacheDir: File? = null

    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (cacheDir == null) {
            cacheDir = File(appCtx.cacheDir, "matched_lyrics").apply { mkdirs() }
        }
    }

    private fun getSafeCacheDir(): File? {
        val dir = cacheDir ?: appContext?.cacheDir?.let { File(it, "matched_lyrics") } ?: return null
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 判断歌曲是否需要匹配官方歌词或中文翻译
     */
    @Suppress("UnusedParameter")
    fun needsMatching(
        song: Song,
        currentLyrics: List<LyricLine>,
    ): Boolean {
        if (!org.melodist.data.AppSettingsManager.settings.value.enableAutoMatchLyrics) return false
        val isPlaceholder =
            currentLyrics.isEmpty() ||
                (currentLyrics.size == 1 && (currentLyrics[0].text.contains("暂无歌词") || currentLyrics[0].text.isBlank()))
        if (isPlaceholder) return true
        if (currentLyrics.any { it.transText.isNotBlank() }) return false

        val sampleText = currentLyrics.take(15).joinToString(" ") { it.text }
        val containsJapanese = Regex("""[\u3040-\u30FF]""").containsMatchIn(sampleText)
        val containsKorean = Regex("""[\uAC00-\uD7AF]""").containsMatchIn(sampleText)
        val latinLetterCount = sampleText.count { it in 'a'..'z' || it in 'A'..'Z' }

        return containsJapanese || containsKorean || latinLetterCount > 30
    }

    /**
     * 异步为本地或 WebDAV 歌曲智能匹配官方歌词
     * @param song 当前播放歌曲
     * @param audioFile 可读的本地音频文件或已缓存的 WebDAV 文件
     * @param currentLyrics 当前已装载的本地歌词
     * @return 匹配成功返回新的双语 LyricLine 列表，未命中或已为最新则返回 null
     */
    suspend fun matchLyricsAsync(
        song: Song,
        audioFile: File?,
        currentLyrics: List<LyricLine>,
    ): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            if (!needsMatching(song, currentLyrics)) {
                return@withContext null
            }

            val cacheKey = getCacheKey(song)

            // 1. 优先从私有缓存读取（零网络请求、零延迟）
            val cached = readFromPrivateCache(cacheKey)
            if (cached != null && cached.isNotEmpty()) {
                Log.i(TAG, "Loaded matched lyrics from private cache for: ${song.name}")
                return@withContext cached
            }

            var matchedSongMid: String? = null
            var matchedTitle: String = ""
            var matchedArtist: String = ""

            // 2. 通道 1：快速文本搜索试探 (若标签清晰完整)
            val cleanTitle = cleanTrackNumber(song.name)
            val artist = if (song.singer.isNotBlank() && song.singer != "未知歌手" && song.singer != "Unknown") song.singer else ""
            if (cleanTitle.isNotBlank() && artist.isNotBlank()) {
                try {
                    val query = "$cleanTitle $artist"
                    val candidates = apiService.search(query, page = 1, pageSize = 5)
                    if (candidates.isNotEmpty()) {
                        // 挑选最符合的候选
                        val best =
                            candidates.firstOrNull { cand ->
                                cand.name.contains(cleanTitle, ignoreCase = true) || cleanTitle.contains(cand.name, ignoreCase = true)
                            } ?: candidates.first()
                        matchedSongMid = best.songMid
                        matchedTitle = best.name
                        matchedArtist = best.singer
                        Log.i(TAG, "Text search matched: ${best.name} - ${best.singer} (mid=${best.songMid})")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Text search probe failed for: ${song.name}", e)
                }
            }

            // 3. 通道 2：若文本通道未命中，执行音频切片声学指纹识别 (ACR)
            if (matchedSongMid.isNullOrBlank() && audioFile != null && audioFile.exists() && audioFile.length() > 64 * 1024L) {
                try {
                    Log.i(TAG, "Invoking audio slice ACR for: ${audioFile.name}")
                    val sliceResult = AudioSliceExtractor.extractSliceWithTime(audioFile)
                    if (sliceResult != null) {
                        val acrResult = acrClient.search(sliceResult.feature)
                        if (acrResult.success && !acrResult.song?.songMid.isNullOrBlank()) {
                            val acrSong = acrResult.song!!
                            matchedSongMid = acrSong.songMid
                            matchedTitle = acrSong.name
                            matchedArtist = acrSong.singer
                            Log.i(TAG, "ACR successfully identified: ${acrSong.name} - ${acrSong.singer} (mid=${acrSong.songMid})")

                            val calculatedOffset = Math.round((acrResult.offsetSeconds - sliceResult.startSeconds) * 1000.0)
                            val finalOffset = if (kotlin.math.abs(calculatedOffset) < 150L) 0L else calculatedOffset
                            org.melodist.data.LyricCacheManager
                                .saveLyricOffsetMs(song.songMid, finalOffset)
                            Log.i(TAG, "ACR auto-calibrated lyric offset for ${song.name}: ${finalOffset}ms")
                        } else {
                            Log.w(TAG, "ACR returned no match: ${acrResult.errorMessage}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error performing ACR on ${audioFile.name}", e)
                }
            }

            // 4. 获取官方双语歌词并持久化落盘至私有缓存
            if (!matchedSongMid.isNullOrBlank()) {
                try {
                    val officialLyrics = apiService.getLyrics(matchedSongMid)
                    if (officialLyrics.isNotEmpty()) {
                        // 若原歌词已有内容，且匹配到的新歌词没有提供翻译，则保留原歌词避免负优化
                        val newHasTrans = officialLyrics.any { it.transText.isNotBlank() }
                        if (currentLyrics.isNotEmpty() && !newHasTrans) {
                            Log.i(TAG, "Official lyrics has no translation, keeping original")
                            return@withContext null
                        }

                        // 保存至应用私有缓存目录
                        saveToPrivateCache(cacheKey, matchedSongMid, matchedTitle, matchedArtist, officialLyrics)
                        return@withContext officialLyrics
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch official lyrics for mid=$matchedSongMid", e)
                }
            }

            return@withContext null
        }

    /**
     * 为本地或 WebDAV 歌曲自动校准官方歌词时间轴偏移量 (Offset in milliseconds)
     * 针对本地音频存在片头静音削减/母带版本差异的情况 (如 5:08 本地文件 vs 5:11 官方数字版)
     * 计算规则：
     * 1. 若已有校准记录则直接返回 (0 网络开销)
     * 2. 若本地音频文件存在，提取切片声学指纹与精确起始秒数送入 ACR
     * 3. 毫秒级差分计算: offsetMs = ((acrOffset - sliceStart) * 1000).toLong()
     * 4. 误差小于 150ms 视为精准对齐，保存 0ms；反之持久化 offsetMs，供播放与歌词组件动态平移
     */
    suspend fun calibrateOffsetAsync(
        song: Song,
        audioFile: File?,
    ): Long =
        withContext(Dispatchers.IO) {
            if (!song.isLocal && song.localFilePath.isNullOrBlank() && !song.songMid.startsWith("webdav_")) {
                return@withContext 0L
            }

            if (org.melodist.data.LyricCacheManager
                    .hasLyricOffsetRecord(song.songMid)
            ) {
                return@withContext org.melodist.data.LyricCacheManager
                    .getLyricOffsetMs(song.songMid)
            }

            if (audioFile == null || !audioFile.exists() || !audioFile.canRead() || audioFile.length() < 32 * 1024L) {
                return@withContext 0L
            }

            try {
                Log.i(TAG, "Starting automatic lyric offset calibration for: ${song.name}")
                val sliceResult = AudioSliceExtractor.extractSliceWithTime(audioFile)
                if (sliceResult == null) {
                    org.melodist.data.LyricCacheManager
                        .saveLyricOffsetMs(song.songMid, 0L)
                    return@withContext 0L
                }

                val acrResult = acrClient.search(sliceResult.feature)
                if (acrResult.success && acrResult.song != null) {
                    val calculatedOffset = Math.round((acrResult.offsetSeconds - sliceResult.startSeconds) * 1000.0)
                    val finalOffset = if (kotlin.math.abs(calculatedOffset) < 150L) 0L else calculatedOffset
                    org.melodist.data.LyricCacheManager
                        .saveLyricOffsetMs(song.songMid, finalOffset)
                    Log.i(
                        TAG,
                        "Successfully calibrated lyric offset for ${song.name}: ${finalOffset}ms (officialOffset=${acrResult.offsetSeconds}s, localStart=${sliceResult.startSeconds}s)",
                    )
                    return@withContext finalOffset
                } else {
                    Log.w(TAG, "ACR offset calibration returned no match for: ${song.name}, recording 0ms")
                    org.melodist.data.LyricCacheManager
                        .saveLyricOffsetMs(song.songMid, 0L)
                    return@withContext 0L
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to calibrate lyric offset for ${song.name}", e)
                org.melodist.data.LyricCacheManager
                    .saveLyricOffsetMs(song.songMid, 0L)
                return@withContext 0L
            }
        }

    private fun getCacheKey(song: Song): String {
        val raw = song.localFilePath ?: song.mediaMid.ifBlank { song.songMid }
        return md5(raw)
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readFromPrivateCache(cacheKey: String): List<LyricLine>? {
        val dir = getSafeCacheDir() ?: return null
        val file = File(dir, "$cacheKey.json")
        if (!file.exists() || !file.canRead()) return null

        return try {
            val raw = file.readText(Charsets.UTF_8)
            val cached = json.decodeFromString<CachedMatchedLyric>(raw)
            cached.lyricLines.map { it.toLyricLine() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cached lyric: ${file.name}", e)
            null
        }
    }

    private fun saveToPrivateCache(
        cacheKey: String,
        songMid: String,
        title: String,
        artist: String,
        lyrics: List<LyricLine>,
    ) {
        val dir = getSafeCacheDir() ?: return
        try {
            val file = File(dir, "$cacheKey.json")
            file.parentFile?.mkdirs()
            val cached =
                CachedMatchedLyric(
                    songMid = songMid,
                    title = title,
                    artist = artist,
                    matchedTimeMs = System.currentTimeMillis(),
                    lyricLines = lyrics.map { CachedLyricLine(it.timestampMs, it.text, it.transText) },
                )
            file.writeText(json.encodeToString(cached), Charsets.UTF_8)
            Log.i(TAG, "Saved matched lyrics to private cache: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save matched lyrics cache", e)
        }
    }

    /**
     * 去除诸如 "01. ", "02 - ", "Track 01" 等音轨前缀
     */
    private fun cleanTrackNumber(name: String): String {
        var clean = name.trim()
        clean = clean.replace(Regex("""^\d{1,3}[\.\s\-_]+"""), "")
        clean = clean.replace(Regex("""^[Tt]rack\s*\d{1,3}[\.\s\-_]*"""), "")
        return clean.trim()
    }
}
