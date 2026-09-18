package org.melodist.tv

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.collect
import org.melodist.api.MusicApiService
import org.melodist.data.UserSessionManager
import org.melodist.playback.DeviceAudioCapability
import org.melodist.playback.PlaybackManager
import org.melodist.tv.screensaver.ScreenSaverManager
import org.melodist.tv.ui.AcrTvScreen
import org.melodist.tv.ui.AlbumTvScreen
import org.melodist.tv.ui.ArtistTvScreen
import org.melodist.tv.ui.HomeTvScreen
import org.melodist.tv.ui.LocalMusicTvScreen
import org.melodist.tv.ui.MediaCollectionTvScreen
import org.melodist.tv.ui.PlayerTvScreen
import org.melodist.tv.ui.PlaylistScreenCache
import org.melodist.tv.ui.PlaylistTvScreen
import org.melodist.tv.ui.SearchTvScreen
import org.melodist.tv.ui.SettingsTvScreen
import org.melodist.tv.ui.WebDavTvScreen
import org.melodist.tv.connect.TvConnectManager
import org.melodist.tv.ui.connect.ConnectTvScreen
import org.melodist.tv.ui.settings.ScreenSaverOverlay
import org.melodist.tv.ui.theme.MelodistTvTheme
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import org.melodist.tv.ui.theme.rememberMonetSurfaceColor

private const val MAX_BACK_DEPTH = 3

sealed interface TvScreenDestination {
    val key: String

    data object Home : TvScreenDestination {
        override val key: String = "home"
    }
    data object Player : TvScreenDestination {
        override val key: String = "player"
    }
    data object Settings : TvScreenDestination {
        override val key: String = "settings"
    }
    data class Playlist(
        val categoryId: String = "favorites",
        val title: String = "我喜欢的音乐",
        val subtitle: String = "已收藏单曲列表",
        val coverUrl: String = "",
        val dirId: Long = 0L,
        val tid: Long = 0L,
        val isFav: Boolean = false,
        val albumMid: String = "",
    ) : TvScreenDestination {
        override val key: String = "playlist_${categoryId}_${dirId}_${tid}_$albumMid"
    }
    data class MediaCollection(val type: String = "playlists") : TvScreenDestination {
        override val key: String = "media_collection_$type"
    }
    data class Artist(val mid: String, val name: String) : TvScreenDestination {
        override val key: String = "artist_$mid"
    }
    data class Album(val mid: String, val name: String, val coverUrl: String = "") : TvScreenDestination {
        override val key: String = "album_$mid"
    }
    data object Search : TvScreenDestination {
        override val key: String = "search"
    }
    data object Acr : TvScreenDestination {
        override val key: String = "acr"
    }
    data object WebDav : TvScreenDestination {
        override val key: String = "webdav"
    }
    data object LocalMusic : TvScreenDestination {
        override val key: String = "local_music"
    }
    data object Connect : TvScreenDestination {
        override val key: String = "connect"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceAudioCapability.init(this)
        UserSessionManager.init(this)
        PlaybackManager.init(this)
        org.melodist.data.FavoriteArtistsManager
            .init(this)
        org.melodist.data.SearchKeywordHistoryManager
            .init(this)
        org.melodist.data.WebDavManager
            .init(this)
        org.melodist.data.LocalMusicManager
            .init(this)
        org.melodist.data.AppSettingsManager.init(this)
        org.melodist.data.DailyRecommendCacheManager.init(this)
        org.melodist.data.UserLibraryCacheManager.init(this)
        org.melodist.data.RecommendFeedManager.init(this)
        ScreenSaverManager.init()
        TvConnectManager.init(this)
        checkAndRequestStoragePermissions()
        setContent {
            MelodistTvTheme {
                val currentPlayingSong by PlaybackManager.currentSong.collectAsState()
                val activeMonetCoverUrl =
                    remember(currentPlayingSong) {
                        currentPlayingSong?.coverUrl?.ifBlank { null }
                            ?: currentPlayingSong?.albumMid?.takeIf { it.isNotBlank() }?.let {
                                MusicApiService.getAlbumCoverUrl(it)
                            }
                    }
                val globalMonetBg = rememberMonetSurfaceColor(activeMonetCoverUrl)

                CompositionLocalProvider(org.melodist.tv.ui.theme.LocalMonetSurface provides globalMonetBg) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(globalMonetBg),
                    ) {
                        val saveableStateHolder = rememberSaveableStateHolder()
                        var currentDestination by remember { mutableStateOf<TvScreenDestination>(TvScreenDestination.Home) }
                        val routeStack = remember { mutableStateListOf<TvScreenDestination>() }
                        var isReturningFromPlayer by remember { mutableStateOf(false) }

                        fun navigateTo(destination: TvScreenDestination, clearStack: Boolean = false) {
                            if (clearStack || destination is TvScreenDestination.Home) {
                                routeStack.forEach { saveableStateHolder.removeState(it.key) }
                                routeStack.clear()
                                currentDestination = destination
                                isReturningFromPlayer = false
                                return
                            }
                            if (currentDestination != destination) {
                                isReturningFromPlayer = false
                                while (routeStack.size >= MAX_BACK_DEPTH) {
                                    val removed = routeStack.removeAt(0)
                                    saveableStateHolder.removeState(removed.key)
                                }
                                routeStack.add(currentDestination)
                                currentDestination = destination
                            }
                        }

                        fun navigateBack() {
                            val previous = currentDestination
                            saveableStateHolder.removeState(previous.key)
                            if (routeStack.isNotEmpty()) {
                                currentDestination = routeStack.removeAt(routeStack.lastIndex)
                            } else {
                                currentDestination = TvScreenDestination.Home
                            }
                            isReturningFromPlayer = (previous is TvScreenDestination.Player)
                        }

                        var lastBackTime by remember { mutableLongStateOf(0L) }

                        LaunchedEffect(Unit) {
                            PlaybackManager.songFavoriteToggledEvent.collect { (song, isFav) ->
                                PlaylistScreenCache.onSongFavoriteChanged(song, isFav)
                                org.melodist.tv.ui.components.HomeCardsCache.onSongFavoriteChanged(song, isFav)
                                org.melodist.data.UserLibraryCacheManager.onFavoriteToggled(song, isFav)
                            }
                        }

                        LaunchedEffect(Unit) {
                            TvConnectManager.navigateToPlayerEvent.collect {
                                navigateTo(TvScreenDestination.Player)
                            }
                        }

                        // 主页返回键双击退出应用
                        BackHandler(enabled = currentDestination is TvScreenDestination.Home) {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastBackTime < 2000L) {
                                finish()
                            } else {
                                lastBackTime = currentTime
                                android.widget.Toast
                                    .makeText(this@MainActivity, "再按一次返回键退出应用", android.widget.Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                        saveableStateHolder.SaveableStateProvider(key = currentDestination.key) {
                            when (val dest = currentDestination) {
                            is TvScreenDestination.Home -> {
                                HomeTvScreen(
                                    surfaceColor = globalMonetBg,
                                    onNavigateToPlayer = {
                                        navigateTo(TvScreenDestination.Player)
                                    },
                                    onNavigateToSettings = {
                                        navigateTo(TvScreenDestination.Settings)
                                    },
                                    onNavigateToAcr = {
                                        navigateTo(TvScreenDestination.Acr)
                                    },
                                    onNavigateToSearch = {
                                        navigateTo(TvScreenDestination.Search)
                                    },
                                    onNavigateToWebDav = {
                                        navigateTo(TvScreenDestination.WebDav)
                                    },
                                    onNavigateToLocalMusic = {
                                        navigateTo(TvScreenDestination.LocalMusic)
                                    },
                                    onNavigateToConnect = {
                                        navigateTo(TvScreenDestination.Connect)
                                    },
                                    onNavigateToDetail = { categoryId ->
                                        when (categoryId) {
                                            "favorites" -> {
                                                navigateTo(
                                                    TvScreenDestination.Playlist(
                                                        categoryId = "favorites",
                                                        title = "我喜欢的音乐",
                                                        subtitle = "已收藏单曲列表",
                                                        isFav = true,
                                                        coverUrl = org.melodist.tv.ui.components.HomeCardsCache.favCover,
                                                    ),
                                                )
                                            }
                                            "daily" -> {
                                                navigateTo(
                                                    TvScreenDestination.Playlist(
                                                        categoryId = "daily",
                                                        title = "每日推荐",
                                                        subtitle = "依据收听记录每日推荐",
                                                        isFav = false,
                                                        coverUrl = org.melodist.tv.ui.components.HomeCardsCache.dailyCover,
                                                    ),
                                                )
                                            }
                                            "radar" -> {
                                                navigateTo(
                                                    TvScreenDestination.Playlist(
                                                        categoryId = "radar",
                                                        title = "猜你喜欢",
                                                        subtitle = "雷达推演",
                                                        isFav = false,
                                                        coverUrl = org.melodist.tv.ui.components.HomeCardsCache.radarCover,
                                                        albumMid = org.melodist.tv.ui.components.HomeCardsCache.radarAlbumMid,
                                                    ),
                                                )
                                            }
                                            "playlists" -> {
                                                navigateTo(TvScreenDestination.MediaCollection("playlists"))
                                            }
                                            "collections" -> {
                                                navigateTo(TvScreenDestination.MediaCollection("collections"))
                                            }
                                            else -> {
                                                navigateTo(
                                                    TvScreenDestination.Playlist(
                                                        categoryId = categoryId,
                                                        title = "歌单详情",
                                                        subtitle = "曲目列表",
                                                        isFav = false,
                                                        coverUrl = "",
                                                    ),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                            is TvScreenDestination.Player -> {
                                PlayerTvScreen(
                                    surfaceColor = globalMonetBg,
                                    onNavigateToArtist = { mid, name ->
                                        navigateTo(TvScreenDestination.Artist(mid, name))
                                    },
                                    onNavigateToAlbum = { mid, name ->
                                        navigateTo(TvScreenDestination.Album(mid, name))
                                    },
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            is TvScreenDestination.Settings -> {
                                SettingsTvScreen(
                                    surfaceColor = globalMonetBg,
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            is TvScreenDestination.MediaCollection -> {
                                MediaCollectionTvScreen(
                                    type = dest.type,
                                    onSelectPlaylist = { playlist ->
                                        navigateTo(
                                            TvScreenDestination.Playlist(
                                                categoryId = "playlist_detail",
                                                title = playlist.name,
                                                subtitle = "共 ${playlist.songCount} 首单曲",
                                                dirId = playlist.dirId,
                                                tid = playlist.tid,
                                                isFav = playlist.isFav,
                                                albumMid = "",
                                                coverUrl = playlist.picUrl,
                                            ),
                                        )
                                    },
                                    onSelectAlbum = { album ->
                                        navigateTo(TvScreenDestination.Album(album.mid, album.title, album.coverUrl))
                                    },
                                    onNavigateToSettings = {
                                        navigateTo(TvScreenDestination.Settings)
                                    },
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            is TvScreenDestination.Playlist -> {
                                PlaylistTvScreen(
                                    surfaceColor = globalMonetBg,
                                    categoryId = dest.categoryId,
                                    title = dest.title,
                                    subtitle = dest.subtitle,
                                    coverUrl = dest.coverUrl,
                                    dirId = dest.dirId,
                                    tid = dest.tid,
                                    isFav = dest.isFav,
                                    albumMid = dest.albumMid,
                                    isFavoritePlaylist = (dest.categoryId == "favorites"),
                                    isReturningFromPlayer = isReturningFromPlayer,
                                    onPlayAll = {},
                                    onPlayShuffle = {},
                                    onSongClick = {},
                                    onNavigateToArtist = { mid, name ->
                                        navigateTo(TvScreenDestination.Artist(mid, name))
                                    },
                                    onNavigateToAlbum = { mid, name ->
                                        navigateTo(TvScreenDestination.Album(mid, name))
                                    },
                                    onNavigateToSettings = {
                                        navigateTo(TvScreenDestination.Settings)
                                    },
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            is TvScreenDestination.Artist -> {
                                ArtistTvScreen(
                                    artistMid = dest.mid,
                                    artistName = dest.name,
                                    surfaceColor = globalMonetBg,
                                    onNavigateToPlayer = {},
                                    onNavigateToArtist = { mid, name ->
                                        navigateTo(TvScreenDestination.Artist(mid, name))
                                    },
                                    onNavigateToAlbum = { mid, name ->
                                        navigateTo(TvScreenDestination.Album(mid, name))
                                    },
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            is TvScreenDestination.Album -> {
                                AlbumTvScreen(
                                    albumMid = dest.mid,
                                    albumName = dest.name,
                                    surfaceColor = globalMonetBg,
                                    isReturningFromPlayer = isReturningFromPlayer,
                                    onNavigateToPlayer = {},
                                    onNavigateToArtist = { mid, name ->
                                        navigateTo(TvScreenDestination.Artist(mid, name))
                                    },
                                    onNavigateToAlbum = { mid, name ->
                                        navigateTo(TvScreenDestination.Album(mid, name))
                                    },
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            is TvScreenDestination.Acr -> {
                                AcrTvScreen(
                                    surfaceColor = globalMonetBg,
                                    onNavigateToPlayer = {
                                        navigateTo(TvScreenDestination.Player)
                                    },
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            is TvScreenDestination.Search -> {
                                SearchTvScreen(
                                    surfaceColor = globalMonetBg,
                                    onNavigateToPlayer = {
                                        navigateTo(TvScreenDestination.Player)
                                    },
                                    onNavigateToArtist = { mid, name ->
                                        navigateTo(TvScreenDestination.Artist(mid, name))
                                    },
                                    onNavigateToAlbum = { mid, name ->
                                        navigateTo(TvScreenDestination.Album(mid, name))
                                    },
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            is TvScreenDestination.WebDav -> {
                                WebDavTvScreen(
                                    surfaceColor = globalMonetBg,
                                    onNavigateToPlayer = {
                                        navigateTo(TvScreenDestination.Player)
                                    },
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            is TvScreenDestination.LocalMusic -> {
                                LocalMusicTvScreen(
                                    surfaceColor = globalMonetBg,
                                    onNavigateToPlayer = {
                                        navigateTo(TvScreenDestination.Player)
                                    },
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            is TvScreenDestination.Connect -> {
                                ConnectTvScreen(
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                        }
                    }

                        // 全局屏幕保护覆盖层：应用任何界面在无操作超时后自动触发
                        val isScreenSaverActive by ScreenSaverManager.isScreenSaverActive.collectAsState()
                        val appSettings by org.melodist.data.AppSettingsManager.settings
                            .collectAsState()

                        if (isScreenSaverActive) {
                            ScreenSaverOverlay(
                                enablePixelShift = appSettings.enablePixelShift,
                                onDismiss = {
                                    ScreenSaverManager.dismissScreenSaver()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        ScreenSaverManager.onUserInteraction()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        ScreenSaverManager.onUserInteraction()
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_SPACE -> {
                    PlaybackManager.togglePlayPause()
                    return true
                }
                android.view.KeyEvent.KEYCODE_ESCAPE -> {
                    onBackPressedDispatcher.onBackPressed()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    PlaybackManager.togglePlayPause()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    PlaybackManager.playNext()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    PlaybackManager.playPrevious()
                    return true
                }
            }
        }
        if (event.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
            event.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            val dpadEvent =
                android.view.KeyEvent(
                    event.downTime,
                    event.eventTime,
                    event.action,
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    event.repeatCount,
                    event.metaState,
                    event.deviceId,
                    event.scanCode,
                    event.flags,
                    event.source,
                )
            try {
                return super.dispatchKeyEvent(dpadEvent)
            } catch (e: IllegalStateException) {
                android.util.Log.w("MelodistTV", "Suppressed focus exception during key dispatch", e)
                return true
            }
        }
        try {
            return super.dispatchKeyEvent(event)
        } catch (e: IllegalStateException) {
            android.util.Log.w("MelodistTV", "Suppressed focus exception during key dispatch", e)
            return true
        }
    }

    override fun onStop() {
        PlaybackManager.savePlaybackState()
        super.onStop()
    }

    override fun onDestroy() {
        PlaybackManager.release()
        super.onDestroy()
    }

    private fun checkAndRequestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent =
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val needed =
            permissions.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1001)
        }
    }
}
