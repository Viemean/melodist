package org.melodist.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.model.AudioQualityTier
import java.io.File

enum class LyricFontSize(
    val label: String,
    val scaleFactor: Float,
    val titleSp: Int,
    val subSp: Int,
) {
    Normal("标准", 1.0f, 22, 14),
    Large("偏大", 1.18f, 26, 16),
    ExtraLarge("超大", 1.36f, 30, 18),
    ;

    val spValue: Int get() = titleSp
}

data class CacheUsageDetail(
    val imageCacheBytes: Long = 0L,
    val mediaCacheBytes: Long = 0L,
    val lyricsCacheBytes: Long = 0L,
    val otherCacheBytes: Long = 0L,
) {
    val totalBytes: Long get() = imageCacheBytes + mediaCacheBytes + lyricsCacheBytes + otherCacheBytes

    val totalFormatted: String get() = formattedTotal()
    val imageFormatted: String get() = formattedImage()
    val mediaFormatted: String get() = formattedMedia()
    val matchedLyricsFormatted: String get() = formattedLyrics()
    val tempFormatted: String get() = formattedOther()

    fun formattedTotal(): String = formatBytes(totalBytes)

    fun formattedImage(): String = formatBytes(imageCacheBytes)

    fun formattedMedia(): String = formatBytes(mediaCacheBytes)

    fun formattedLyrics(): String = formatBytes(lyricsCacheBytes)

    fun formattedOther(): String = formatBytes(otherCacheBytes)

    companion object {
        private fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 MB"
            val mb = bytes.toDouble() / (1024.0 * 1024.0)
            return if (mb < 0.1) {
                val kb = bytes.toDouble() / 1024.0
                "%.1f KB".format(kb)
            } else {
                "%.1f MB".format(mb)
            }
        }
    }
}

enum class ScreenSaverTimeout(
    val label: String,
    val minutes: Int,
) {
    Never("关闭", 0),
    Minutes3("3 分钟", 3),
    Minutes5("5 分钟", 5),
    Minutes10("10 分钟", 10),
    Minutes15("15 分钟", 15),
    ;

    val millis: Long get() = minutes * 60 * 1000L
}

data class AppSettings(
    // 1. 音频与音质
    val preferredQualityTier: AudioQualityTier = AudioQualityTier.SQ,
    val enableAudioPassthrough: Boolean = false,
    val enableAutoMatchLyrics: Boolean = true,
    // 2. 播放与歌词
    val showBilingualLyrics: Boolean = true,
    val enableWordByWordAnim: Boolean = true,
    val lyricFontSize: LyricFontSize = LyricFontSize.Normal,
    // 3. OLED 屏保与显示保护
    val screenSaverTimeout: ScreenSaverTimeout = ScreenSaverTimeout.Minutes5,
    val enablePixelShift: Boolean = true,
    val enableScreenSaverDuringPlayback: Boolean = true,
) {
    // 向后兼容旧字段引用
    val enableAtmosPassthrough: Boolean get() = enableAudioPassthrough
}

object AppSettingsManager {
    private const val TAG = "AppSettingsManager"
    private const val PREF_NAME = "melodist_app_settings"

    private const val KEY_PREFERRED_TIER = "preferred_tier"
    private const val KEY_AUDIO_PASSTHROUGH = "audio_passthrough"
    private const val KEY_ATMOS_PASSTHROUGH = "atmos_passthrough"
    private const val KEY_AUTO_MATCH_LYRICS = "auto_match_lyrics"
    private const val KEY_BILINGUAL_TRANS = "bilingual_translation"
    private const val KEY_WORD_ANIM = "word_animation"
    private const val KEY_LYRIC_FONT_SIZE = "lyric_font_size"
    private const val KEY_SCREENSAVER_TIMEOUT = "screensaver_timeout"
    private const val KEY_SCREENSAVER_PIXEL_SHIFT = "screensaver_pixel_shift"
    private const val KEY_SCREENSAVER_DURING_PLAYBACK = "screensaver_during_playback"

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _cacheUsage = MutableStateFlow(CacheUsageDetail())
    val cacheUsage: StateFlow<CacheUsageDetail> = _cacheUsage.asStateFlow()

    var onAudioPassthroughChangedListener: ((Boolean) -> Unit)? = null

    fun init(context: Context) {
        if (prefs == null) {
            val app = context.applicationContext
            appContext = app
            prefs = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            loadSettings()
            refreshCacheUsage()
            cleanStaleInstallersAsync()
        }
    }

    private fun loadSettings() {
        val p = prefs ?: return
        val tierName = p.getString(KEY_PREFERRED_TIER, AudioQualityTier.SQ.name) ?: AudioQualityTier.SQ.name
        val tier =
            try {
                AudioQualityTier.valueOf(tierName)
            } catch (_: Exception) {
                AudioQualityTier.SQ
            }

        val passthrough =
            if (p.contains(KEY_AUDIO_PASSTHROUGH)) {
                p.getBoolean(KEY_AUDIO_PASSTHROUGH, false)
            } else {
                p.getBoolean(KEY_ATMOS_PASSTHROUGH, false)
            }
        val autoLyrics = p.getBoolean(KEY_AUTO_MATCH_LYRICS, true)
        val bilingual = p.getBoolean(KEY_BILINGUAL_TRANS, true)
        val wordAnim = p.getBoolean(KEY_WORD_ANIM, true)
        val fontName = p.getString(KEY_LYRIC_FONT_SIZE, LyricFontSize.Normal.name) ?: LyricFontSize.Normal.name
        val font =
            try {
                LyricFontSize.valueOf(fontName)
            } catch (_: Exception) {
                LyricFontSize.Normal
            }

        val timeoutName = p.getString(KEY_SCREENSAVER_TIMEOUT, ScreenSaverTimeout.Minutes5.name) ?: ScreenSaverTimeout.Minutes5.name
        val timeout =
            try {
                ScreenSaverTimeout.valueOf(timeoutName)
            } catch (_: Exception) {
                ScreenSaverTimeout.Minutes5
            }

        val pixelShift = p.getBoolean(KEY_SCREENSAVER_PIXEL_SHIFT, true)
        val duringPlayback = p.getBoolean(KEY_SCREENSAVER_DURING_PLAYBACK, true)

        _settings.value =
            AppSettings(
                preferredQualityTier = tier,
                enableAudioPassthrough = passthrough,
                enableAutoMatchLyrics = autoLyrics,
                showBilingualLyrics = bilingual,
                enableWordByWordAnim = wordAnim,
                lyricFontSize = font,
                screenSaverTimeout = timeout,
                enablePixelShift = pixelShift,
                enableScreenSaverDuringPlayback = duringPlayback,
            )
    }

    fun setPreferredQualityTier(tier: AudioQualityTier) {
        _settings.value = _settings.value.copy(preferredQualityTier = tier)
        prefs?.edit()?.putString(KEY_PREFERRED_TIER, tier.name)?.apply()
    }

    fun updatePreferredQualityTier(tier: AudioQualityTier) = setPreferredQualityTier(tier)

    fun setEnableAudioPassthrough(enable: Boolean) {
        _settings.value = _settings.value.copy(enableAudioPassthrough = enable)
        prefs?.edit()?.putBoolean(KEY_AUDIO_PASSTHROUGH, enable)?.apply()
        onAudioPassthroughChangedListener?.invoke(enable)
    }

    fun updateAudioPassthrough(enable: Boolean) = setEnableAudioPassthrough(enable)

    fun setEnableAtmosPassthrough(enable: Boolean) = setEnableAudioPassthrough(enable)

    fun updateAtmosPassthrough(enable: Boolean) = setEnableAudioPassthrough(enable)

    fun setEnableAutoMatchLyrics(enable: Boolean) {
        _settings.value = _settings.value.copy(enableAutoMatchLyrics = enable)
        prefs?.edit()?.putBoolean(KEY_AUTO_MATCH_LYRICS, enable)?.apply()
    }

    fun updateAutoMatchLyrics(enable: Boolean) = setEnableAutoMatchLyrics(enable)

    fun setShowBilingualTranslation(enable: Boolean) {
        _settings.value = _settings.value.copy(showBilingualLyrics = enable)
        prefs?.edit()?.putBoolean(KEY_BILINGUAL_TRANS, enable)?.apply()
    }

    fun updateShowBilingualLyrics(enable: Boolean) = setShowBilingualTranslation(enable)

    fun setEnableWordByWordAnimation(enable: Boolean) {
        _settings.value = _settings.value.copy(enableWordByWordAnim = enable)
        prefs?.edit()?.putBoolean(KEY_WORD_ANIM, enable)?.apply()
    }

    fun updateWordByWordAnim(enable: Boolean) = setEnableWordByWordAnimation(enable)

    fun setLyricFontSize(size: LyricFontSize) {
        _settings.value = _settings.value.copy(lyricFontSize = size)
        prefs?.edit()?.putString(KEY_LYRIC_FONT_SIZE, size.name)?.apply()
    }

    fun updateLyricFontSize(size: LyricFontSize) = setLyricFontSize(size)

    fun setScreenSaverTimeout(timeout: ScreenSaverTimeout) {
        _settings.value = _settings.value.copy(screenSaverTimeout = timeout)
        prefs?.edit()?.putString(KEY_SCREENSAVER_TIMEOUT, timeout.name)?.apply()
    }

    fun updateScreenSaverTimeout(timeout: ScreenSaverTimeout) = setScreenSaverTimeout(timeout)

    fun setEnablePixelShift(enable: Boolean) {
        _settings.value = _settings.value.copy(enablePixelShift = enable)
        prefs?.edit()?.putBoolean(KEY_SCREENSAVER_PIXEL_SHIFT, enable)?.apply()
    }

    fun updateEnablePixelShift(enable: Boolean) = setEnablePixelShift(enable)

    fun setEnableScreenSaverDuringPlayback(enable: Boolean) {
        _settings.value = _settings.value.copy(enableScreenSaverDuringPlayback = enable)
        prefs?.edit()?.putBoolean(KEY_SCREENSAVER_DURING_PLAYBACK, enable)?.apply()
    }

    fun updateEnableScreenSaverDuringPlayback(enable: Boolean) = setEnableScreenSaverDuringPlayback(enable)

    var mediaCacheSizeProvider: (() -> Long)? = null
    var mediaCacheClearAction: (suspend () -> Boolean)? = null
    var imageCacheClearAction: (suspend () -> Boolean)? = null

    fun calculateCacheUsage() = refreshCacheUsage()

    fun clearImageAndTempCache() {
        scope.launch { clearMediaAndImageCache() }
    }

    fun clearAllCaches() {
        scope.launch { clearAllCacheData() }
    }

    fun clearMatchedLyricCache() {
        scope.launch { clearMatchedLyricsCache() }
    }

    /**
     * 异步后台精准统计应用缓存体积
     */
    fun refreshCacheUsage() {
        val ctx = appContext ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val cacheDir = ctx.cacheDir
                var imgBytes = 0L
                var lyricsBytes = 0L
                var otherBytes = 0L

                val mediaBytes =
                    mediaCacheSizeProvider?.invoke()
                        ?: calculateDirSize(File(cacheDir, "media_cache"))

                cacheDir.listFiles()?.forEach { file ->
                    when (file.name) {
                        "image_cache", "coil_cache", "local_covers" -> imgBytes += calculateDirSize(file)
                        "matched_lyrics" -> lyricsBytes += calculateDirSize(file)
                        "media_cache" -> {
                            // 已由 mediaCacheSizeProvider 精准获取
                        }
                        else -> {
                            if (file.isDirectory) {
                                otherBytes += calculateDirSize(file)
                            } else {
                                otherBytes += file.length()
                            }
                        }
                    }
                }

                _cacheUsage.value =
                    CacheUsageDetail(
                        imageCacheBytes = imgBytes,
                        mediaCacheBytes = mediaBytes,
                        lyricsCacheBytes = lyricsBytes,
                        otherCacheBytes = otherBytes,
                    )
            } catch (e: Exception) {
                Log.w(TAG, "Error calculating cache usage", e)
            }
        }
    }

    /**
     * 清理图片与媒体缓存（保留已匹配的歌词和登录状态）
     */
    suspend fun clearMediaAndImageCache(): Boolean =
        withContext(Dispatchers.IO) {
            val ctx = appContext ?: return@withContext false
            try {
                mediaCacheClearAction?.invoke()
                imageCacheClearAction?.invoke()

                val cacheDir = ctx.cacheDir
                cacheDir.listFiles()?.forEach { file ->
                    if (file.name != "matched_lyrics" && file.name != "media_cache" && file.name != "image_cache") {
                        deleteRecursively(file)
                    }
                }
                File(cacheDir, "covers").mkdirs()
                File(cacheDir, "local_covers").mkdirs()
                File(cacheDir, "webdav").mkdirs()
                File(cacheDir, "lyrics").mkdirs()
                refreshCacheUsage()
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear media and image cache", e)
                false
            }
        }

    /**
     * 一键清空所有缓存（图片、媒体、歌词与临时数据）
     */
    suspend fun clearAllCacheData(): Boolean =
        withContext(Dispatchers.IO) {
            val ctx = appContext ?: return@withContext false
            try {
                mediaCacheClearAction?.invoke()
                imageCacheClearAction?.invoke()

                val cacheDir = ctx.cacheDir
                cacheDir.listFiles()?.forEach { file ->
                    if (file.name != "media_cache" && file.name != "image_cache") {
                        deleteRecursively(file)
                    }
                }
                File(cacheDir, "covers").mkdirs()
                File(cacheDir, "local_covers").mkdirs()
                File(cacheDir, "webdav").mkdirs()
                File(cacheDir, "lyrics").mkdirs()
                File(cacheDir, "matched_lyrics").mkdirs()
                cleanStaleInstallers()
                refreshCacheUsage()
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear all cache", e)
                false
            }
        }

    /**
     * 清空匹配歌词缓存
     */
    suspend fun clearMatchedLyricsCache(): Boolean =
        withContext(Dispatchers.IO) {
            val ctx = appContext ?: return@withContext false
            try {
                val lyricsDir = File(ctx.cacheDir, "matched_lyrics")
                if (lyricsDir.exists()) {
                    deleteRecursively(lyricsDir)
                    lyricsDir.mkdirs()
                }
                refreshCacheUsage()
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear lyrics cache", e)
                false
            }
        }

    private fun calculateDirSize(dir: File): Long {
        var size = 0L
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        dir.listFiles()?.forEach { child ->
            size += if (child.isDirectory) calculateDirSize(child) else child.length()
        }
        return size
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }

    /**
     * 清理应用私有目录中的历史 APK 安装包
     */
    fun cleanStaleInstallers() {
        val ctx = appContext ?: return
        try {
            val candidateDirs = mutableListOf<File>()
            candidateDirs.add(ctx.cacheDir)
            ctx.externalCacheDir?.let { candidateDirs.add(it) }
            ctx.filesDir?.let { candidateDirs.add(it) }
            ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)?.let { candidateDirs.add(it) }

            for (dir in candidateDirs) {
                if (dir.exists()) {
                    deleteApkFilesRecursively(dir)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean stale installer files", e)
        }
    }

    fun cleanStaleInstallersAsync() {
        scope.launch(Dispatchers.IO) {
            cleanStaleInstallers()
        }
    }

    private fun deleteApkFilesRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteApkFilesRecursively(it) }
        } else if (file.isFile) {
            val name = file.name.lowercase()
            if (name.endsWith(".apk") || name.endsWith(".apk.tmp") || name.endsWith(".apk.download")) {
                file.delete()
            }
        }
    }
}
