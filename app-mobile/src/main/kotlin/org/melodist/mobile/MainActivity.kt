package org.melodist.mobile

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        enableEdgeToEdge()
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
                                    .memoryCachePolicy(CachePolicy.DISABLED) // 不占用堆内存，避免触发频繁 GC 暂停
                                    .diskCachePolicy(CachePolicy.ENABLED)
                                    .build()
                            imageLoader.execute(request)
                        } catch (_: Throwable) {
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
                    MusicApiService().refreshCurrentUserProfile()
                } catch (_: Exception) {
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
    }

    override fun onResume() {
        super.onResume()
        setupHighRefreshRate()
    }

    override fun onStop() {
        super.onStop()
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
                    // 在匹配当前屏幕物理分辨率的模式中选最高刷新率，避免触发跨分辨率硬件缩放
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
        } catch (_: Exception) {
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
