package org.melodist.mobile

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.melodist.api.ApiLogger
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.refreshCurrentUserProfile
import org.melodist.data.DailyRecommendCacheManager
import org.melodist.data.FavoriteArtistsManager
import org.melodist.data.LocalMusicManager
import org.melodist.data.UserLibraryCacheManager
import org.melodist.data.UserSessionManager
import org.melodist.data.WebDavManager
import org.melodist.data.download.DownloadManager
import org.melodist.mobile.ui.navigation.MainNavigationScreen
import org.melodist.mobile.ui.theme.MelodistMobileTheme
import org.melodist.playback.DeviceAudioCapability
import org.melodist.playback.PlaybackManager

class MainActivity : ComponentActivity() {
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .RequestPermission(),
        ) { _ ->
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.enableEdgeToEdge(window)
        super.onCreate(savedInstanceState)
        setupHighRefreshRate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        lifecycleScope.launch(Dispatchers.Default) {
            DeviceAudioCapability.init(applicationContext)
        }
        UserSessionManager.init(this)
        DailyRecommendCacheManager.init(this) { urls ->
            val appContext = applicationContext
            lifecycleScope.launch(Dispatchers.IO) {
                // 错峰延迟 1.5 秒，避开冷启动首屏布局、JIT 编译与主线程渲染高峰
                kotlinx.coroutines.delay(1500)
                val imageLoader = SingletonImageLoader.get(appContext)
                val semaphore = kotlinx.coroutines.sync.Semaphore(2)
                for (url in urls) {
                    semaphore.withPermit {
                        try {
                            val request =
                                ImageRequest
                                    .Builder(appContext)
                                    .data(url)
                                    .memoryCachePolicy(CachePolicy.DISABLED)
                                    .diskCachePolicy(CachePolicy.ENABLED)
                                    .build()
                            imageLoader.execute(request)
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            Log.w("MainActivity", "Operation failed", e)
                        }
                    }
                }
            }
        }
        org.melodist.data.AppSettingsManager
            .init(this)
        UserLibraryCacheManager.init(this)
        PlaybackManager.init(this)
        FavoriteArtistsManager.init(this)
        WebDavManager.init(this)
        LocalMusicManager.init(this)
        DownloadManager.init(this)
        org.melodist.data.GuessRecommendManager
            .init(this)
        org.melodist.data.GuessRecommendManager.isPlayingPredicate = { PlaybackManager.isRadioMode.value }
        org.melodist.data.RecommendFeedManager
            .init(this)
        org.melodist.data.MillionRecommendManager
            .init(this)
        org.melodist.data.RecentPlaybackManager
            .init(this)
        org.melodist.data.SearchKeywordHistoryManager
            .init(this)
        org.melodist.mobile.connect.MobileConnectManager
            .init(this)

        if (UserSession.isLoggedIn) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val ok = MusicApiService().refreshCurrentUserProfile()
                    if (!ok) {
                        if (MusicApiService().forceRefreshMusicKeySafe()) {
                            MusicApiService().refreshCurrentUserProfile()
                        }
                    }
                } catch (e: Exception) {
                    ApiLogger.w("MainActivity", "Failed to refresh user profile on app start", e)
                }
            }
        }

        setContent {
            val settings by org.melodist.data.AppSettingsManager.settings
                .collectAsState()
            MelodistMobileTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                colorTheme = settings.colorTheme,
                customColorHex = settings.customColorHex,
                amoledDark = settings.amoledDark,
            ) {
                MainNavigationScreen()
            }
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: android.content.Intent?) {
        if (intent == null) return
        if (handleExternalAudioIntent(intent)) return
        handleListenTogetherIntent(intent)
    }

    private fun handleExternalAudioIntent(intent: android.content.Intent): Boolean {
        if (intent.action != android.content.Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        val type = intent.type.orEmpty()
        val scheme = uri.scheme.orEmpty()

        if (uri.host == "i.y.qq.com" && uri.path?.contains("listen_together") == true) {
            return false
        }

        val isAudioMime = type.startsWith("audio/") || type == "application/ogg" || type == "application/x-ogg" || type == "application/flac" || type == "application/x-flac"
        val isAudioScheme = scheme == "content" || scheme == "file"
        val pathLower = uri.path.orEmpty().lowercase()
        val isAudioExt = pathLower.endsWith(".mp3") || pathLower.endsWith(".flac") || pathLower.endsWith(".wav") || pathLower.endsWith(".m4a") || pathLower.endsWith(".aac") || pathLower.endsWith(".ogg") || pathLower.endsWith(".opus")

        if (!isAudioMime && !isAudioExt && !isAudioScheme) return false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                try {
                    if (scheme == android.content.ContentResolver.SCHEME_CONTENT) {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                } catch (_: Exception) {
                }

                var displayName: String? = null
                if (scheme == android.content.ContentResolver.SCHEME_CONTENT) {
                    try {
                        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (idx >= 0) displayName = cursor.getString(idx)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                val finalDisplayName = displayName?.ifBlank { null } ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "外部音频"

                val (inferredTitle, inferredArtist) = org.melodist.data.pipeline.AudioMetadataPipeline.inferTitleArtist(finalDisplayName)
                var title = inferredTitle
                var artist = inferredArtist
                var album = "外部音频"
                var durationSec = 0
                var resolvedCoverUrl = ""
                var sampleRate: Int? = null
                var bitrate: Int? = null

                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@MainActivity, uri)
                    val meta = org.melodist.data.pipeline.AudioMetadataPipeline.parseFromRetriever(
                        retriever = retriever,
                        fallbackTitle = title,
                        fallbackArtist = artist,
                        fallbackAlbum = album,
                    )
                    title = meta.title
                    artist = meta.artist
                    album = meta.album
                    durationSec = meta.durationSeconds
                    sampleRate = meta.sampleRate
                    bitrate = meta.bitrate

                    val picBytes = meta.pictureBytes
                    if (picBytes != null && picBytes.size >= 512) {
                        val hash = uri.toString().hashCode().toString().replace("-", "n")
                        val coverFolder = java.io.File(cacheDir, "covers")
                        val savedCoverPath = org.melodist.data.pipeline.AudioMetadataPipeline.saveThumbnailWebp(
                            bytes = picBytes,
                            folder = coverFolder,
                            baseHash = hash,
                            prefix = "external_",
                        )
                        if (!savedCoverPath.isNullOrBlank()) {
                            resolvedCoverUrl = "file://$savedCoverPath"
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MainActivity", "Failed to extract metadata for external URI: $uri", e)
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                    }
                }

                val ext = finalDisplayName.substringAfterLast('.', "flac")
                val inferredTier = if (sampleRate != null && sampleRate > 0) {
                    org.melodist.model.AudioQualityTier.inferFromAudioFormat(
                        sampleRate = sampleRate,
                        mimeType = "audio/$ext",
                        bitrate = bitrate ?: 0,
                    )
                } else {
                    org.melodist.model.AudioQualityTier.Standard
                }

                val uriStr = uri.toString()
                val hash = kotlin.math.abs(uriStr.hashCode())
                val song = org.melodist.model.Song(
                    songId = hash.toLong(),
                    songMid = "local_ext_$hash",
                    name = title,
                    singer = artist,
                    album = album,
                    durationSeconds = durationSec,
                    currentTier = inferredTier,
                    coverUrl = resolvedCoverUrl,
                    rawCoverUrl = resolvedCoverUrl,
                    localFilePath = uriStr,
                    mediaMid = uriStr,
                    dateAdded = System.currentTimeMillis() / 1000L,
                )

                withContext(Dispatchers.Main) {
                    PlaybackManager.setPlaylist(
                        songs = listOf(song),
                        startIndex = 0,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("MainActivity", "Failed to handle external audio intent", e)
            }
        }
        return true
    }

    private fun handleListenTogetherIntent(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        if (uri.host == "i.y.qq.com" && uri.path?.contains("listen_together") == true) {
            val showId = uri.getQueryParameter("showid").orEmpty()
            val encUin = uri.getQueryParameter("share_encrypt_uin").orEmpty()
            val songId = uri.getQueryParameter("songid")?.toLongOrNull() ?: 0L
            if (showId.isNotBlank()) {
                lifecycleScope.launch(Dispatchers.Main) {
                    android.widget.Toast
                        .makeText(this@MainActivity, "正在加入一起听房间...", android.widget.Toast.LENGTH_SHORT)
                        .show()
                    val result =
                        org.melodist.playback.ListenTogetherManager
                            .joinRoom(showId, encUin, songId)
                    result
                        .onSuccess {
                            android.widget.Toast
                                .makeText(this@MainActivity, "成功加入一起听", android.widget.Toast.LENGTH_SHORT)
                                .show()
                        }.onFailure {
                            android.widget.Toast
                                .makeText(this@MainActivity, "加入失败: ${it.message}", android.widget.Toast.LENGTH_SHORT)
                                .show()
                        }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupHighRefreshRate()
    }

    override fun onStop() {
        super.onStop()
        PlaybackManager.savePlaybackState()
        PlaybackManager.savePlaybackProgress()
    }

    private fun setupHighRefreshRate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val currentDisplay =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        display
                    } else {
                        @Suppress("DEPRECATION")
                        windowManager.defaultDisplay
                    }
                currentDisplay?.let { disp ->
                    val defaultMode = disp.mode
                    val targetWidth = defaultMode.physicalWidth
                    val targetHeight = defaultMode.physicalHeight
                    val modes = disp.supportedModes
                    // 选取当前屏幕物理分辨率下的最高刷新率模式
                    val maxRefreshMode =
                        modes
                            .filter { it.physicalWidth == targetWidth && it.physicalHeight == targetHeight }
                            .maxByOrNull { it.refreshRate }
                            ?: modes.maxByOrNull { it.refreshRate }

                    if (maxRefreshMode != null && maxRefreshMode.refreshRate > 60f) {
                        val targetRate = maxRefreshMode.refreshRate.coerceAtLeast(120f)
                        val lp = window.attributes
                        var changed = false
                        if (lp.preferredDisplayModeId != maxRefreshMode.modeId) {
                            lp.preferredDisplayModeId = maxRefreshMode.modeId
                            changed = true
                        }
                        if (lp.preferredRefreshRate != targetRate) {
                            lp.preferredRefreshRate = targetRate
                            changed = true
                        }
                        if (changed) {
                            window.attributes = lp
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("MainActivity", "Operation failed", e)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            org.melodist.data.AppLifecycleManager
                .onTrimMemoryAction
                ?.invoke(level)
        }
    }
}
