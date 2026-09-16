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
import org.melodist.tv.ui.theme.rememberMonetSurfaceColor

enum class ScreenRoute {
    Home,
    Player,
    Settings,
    Playlist,
    MediaCollection,
    Acr,
    Search,
    WebDav,
    LocalMusic,
    Connect,
    Artist,
    Album,
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
        org.melodist.data.AppSettingsManager
            .init(this)
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
                        var currentRoute by remember { mutableStateOf(ScreenRoute.Home) }
                        val routeStack = remember { mutableStateListOf<ScreenRoute>() }
                        var isReturningFromPlayer by remember { mutableStateOf(false) }

                        fun navigateTo(route: ScreenRoute) {
                            if (currentRoute != route) {
                                isReturningFromPlayer = false
                                routeStack.add(currentRoute)
                                currentRoute = route
                            }
                        }

                        fun navigateBack() {
                            val previousRoute = currentRoute
                            if (routeStack.isNotEmpty()) {
                                currentRoute = routeStack.removeAt(routeStack.lastIndex)
                            } else {
                                currentRoute = ScreenRoute.Home
                            }
                            isReturningFromPlayer = (previousRoute == ScreenRoute.Player)
                        }

                        var currentCategoryId by remember { mutableStateOf("favorites") }
                        var playlistTitle by remember { mutableStateOf("我喜欢的音乐") }
                        var playlistSubtitle by remember { mutableStateOf("已收藏单曲列表") }
                        var isFavoritePlaylist by remember { mutableStateOf(true) }
                        var collectionType by remember { mutableStateOf("playlists") }
                        var selectedPlaylistDirId by remember { mutableLongStateOf(0L) }
                        var selectedPlaylistTid by remember { mutableLongStateOf(0L) }
                        var selectedPlaylistIsFav by remember { mutableStateOf(false) }
                        var selectedAlbumMid by remember { mutableStateOf("") }
                        var selectedAlbumName by remember { mutableStateOf("") }
                        var selectedArtistMid by remember { mutableStateOf("") }
                        var selectedArtistName by remember { mutableStateOf("") }
                        var selectedCoverUrl by remember { mutableStateOf("") }
                        var lastBackTime by remember { mutableLongStateOf(0L) }

                        LaunchedEffect(Unit) {
                            PlaybackManager.songFavoriteToggledEvent.collect { (song, isFav) ->
                                PlaylistScreenCache.onSongFavoriteChanged(song, isFav)
                                org.melodist.tv.ui.components.HomeCardsCache.onSongFavoriteChanged(song, isFav)
                            }
                        }

                        LaunchedEffect(Unit) {
                            TvConnectManager.navigateToPlayerEvent.collect {
                                navigateTo(ScreenRoute.Player)
                            }
                        }

                        // 主页返回键双击退出应用
                        BackHandler(enabled = currentRoute == ScreenRoute.Home) {
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
                        when (currentRoute) {
                            ScreenRoute.Home -> {
                                HomeTvScreen(
                                    surfaceColor = globalMonetBg,
                                    onNavigateToPlayer = {
                                        navigateTo(ScreenRoute.Player)
                                    },
                                    onNavigateToSettings = {
                                        navigateTo(ScreenRoute.Settings)
                                    },
                                    onNavigateToAcr = {
                                        navigateTo(ScreenRoute.Acr)
                                    },
                                    onNavigateToSearch = {
                                        navigateTo(ScreenRoute.Search)
                                    },
                                    onNavigateToWebDav = {
                                        navigateTo(ScreenRoute.WebDav)
                                    },
                                    onNavigateToLocalMusic = {
                                        navigateTo(ScreenRoute.LocalMusic)
                                    },
                                    onNavigateToConnect = {
                                        navigateTo(ScreenRoute.Connect)
                                    },
                                    onNavigateToDetail = { categoryId ->
                                        currentCategoryId = categoryId
                                        selectedAlbumMid = ""
                                        selectedAlbumName = ""
                                        selectedCoverUrl = ""
                                        selectedPlaylistDirId = 0L
                                        selectedPlaylistTid = 0L
                                        selectedPlaylistIsFav = false
                                        isReturningFromPlayer = false
                                        when (categoryId) {
                                            "favorites" -> {
                                                playlistTitle = "我喜欢的音乐"
                                                playlistSubtitle = "已收藏单曲列表"
                                                isFavoritePlaylist = true
                                                selectedCoverUrl = org.melodist.tv.ui.components.HomeCardsCache.favCover
                                                navigateTo(ScreenRoute.Playlist)
                                            }
                                            "daily" -> {
                                                playlistTitle = "每日推荐"
                                                playlistSubtitle = "依据收听记录每日推荐"
                                                isFavoritePlaylist = false
                                                selectedCoverUrl = org.melodist.tv.ui.components.HomeCardsCache.dailyCover
                                                navigateTo(ScreenRoute.Playlist)
                                            }
                                            "radar" -> {
                                                playlistTitle = "猜你喜欢"
                                                playlistSubtitle = "雷达推演"
                                                isFavoritePlaylist = false
                                                selectedCoverUrl = org.melodist.tv.ui.components.HomeCardsCache.radarCover
                                                selectedAlbumMid = org.melodist.tv.ui.components.HomeCardsCache.radarAlbumMid
                                                navigateTo(ScreenRoute.Playlist)
                                            }
                                            "playlists" -> {
                                                collectionType = "playlists"
                                                navigateTo(ScreenRoute.MediaCollection)
                                            }
                                            "collections" -> {
                                                collectionType = "collections"
                                                navigateTo(ScreenRoute.MediaCollection)
                                            }
                                            else -> {
                                                playlistTitle = "歌单详情"
                                                playlistSubtitle = "曲目列表"
                                                isFavoritePlaylist = false
                                                navigateTo(ScreenRoute.Playlist)
                                            }
                                        }
                                    },
                                )
                            }
                            ScreenRoute.Player -> {
                                PlayerTvScreen(
                                    surfaceColor = globalMonetBg,
                                    onNavigateToArtist = { mid, name ->
                                        selectedArtistMid = mid
                                        selectedArtistName = name
                                        navigateTo(ScreenRoute.Artist)
                                    },
                                    onNavigateToAlbum = { mid, name ->
                                        selectedAlbumMid = mid
                                        selectedAlbumName = name
                                        navigateTo(ScreenRoute.Album)
                                    },
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            ScreenRoute.Settings -> {
                                SettingsTvScreen(
                                    surfaceColor = globalMonetBg,
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            ScreenRoute.MediaCollection -> {
                                MediaCollectionTvScreen(
                                    type = collectionType,
                                    onSelectPlaylist = { playlist ->
                                        playlistTitle = playlist.name
                                        playlistSubtitle = "共 ${playlist.songCount} 首单曲"
                                        selectedPlaylistDirId = playlist.dirId
                                        selectedPlaylistTid = playlist.tid
                                        selectedPlaylistIsFav = playlist.isFav
                                        selectedAlbumMid = ""
                                        selectedCoverUrl = playlist.picUrl
                                        currentCategoryId = "playlist_detail"
                                        navigateTo(ScreenRoute.Playlist)
                                    },
                                    onSelectAlbum = { album ->
                                        selectedAlbumMid = album.mid
                                        selectedAlbumName = album.title
                                        selectedCoverUrl = album.coverUrl
                                        navigateTo(ScreenRoute.Album)
                                    },
                                    onNavigateToSettings = {
                                        navigateTo(ScreenRoute.Settings)
                                    },
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            ScreenRoute.Playlist -> {
                                PlaylistTvScreen(
                                    surfaceColor = globalMonetBg,
                                    categoryId = currentCategoryId,
                                    title = playlistTitle,
                                    subtitle = playlistSubtitle,
                                    coverUrl = selectedCoverUrl,
                                    dirId = selectedPlaylistDirId,
                                    tid = selectedPlaylistTid,
                                    isFav = selectedPlaylistIsFav,
                                    albumMid = selectedAlbumMid,
                                    isFavoritePlaylist = (currentCategoryId == "favorites"),
                                    isReturningFromPlayer = isReturningFromPlayer,
                                    onPlayAll = {
                                        navigateTo(ScreenRoute.Player)
                                    },
                                    onPlayShuffle = {
                                        navigateTo(ScreenRoute.Player)
                                    },
                                    onSongClick = {
                                        navigateTo(ScreenRoute.Player)
                                    },
                                    onNavigateToArtist = { mid, name ->
                                        selectedArtistMid = mid
                                        selectedArtistName = name
                                        navigateTo(ScreenRoute.Artist)
                                    },
                                    onNavigateToAlbum = { mid, name ->
                                        selectedAlbumMid = mid
                                        selectedAlbumName = name
                                        navigateTo(ScreenRoute.Album)
                                    },
                                    onNavigateToSettings = {
                                        navigateTo(ScreenRoute.Settings)
                                    },
                                    onBack = {
                                        if (currentCategoryId == "playlist_detail") {
                                            navigateBack()
                                        } else {
                                            navigateBack()
                                        }
                                    },
                                )
                            }
                            ScreenRoute.Artist -> {
                                ArtistTvScreen(
                                    artistMid = selectedArtistMid,
                                    artistName = selectedArtistName,
                                    surfaceColor = globalMonetBg,
                                    onNavigateToPlayer = {
                                        navigateTo(ScreenRoute.Player)
                                    },
                                    onNavigateToArtist = { mid, name ->
                                        selectedArtistMid = mid
                                        selectedArtistName = name
                                        navigateTo(ScreenRoute.Artist)
                                    },
                                    onNavigateToAlbum = { mid, name ->
                                        selectedAlbumMid = mid
                                        selectedAlbumName = name
                                        navigateTo(ScreenRoute.Album)
                                    },
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            ScreenRoute.Album -> {
                                AlbumTvScreen(
                                    albumMid = selectedAlbumMid,
                                    albumName = selectedAlbumName,
                                    surfaceColor = globalMonetBg,
                                    isReturningFromPlayer = isReturningFromPlayer,
                                    onNavigateToPlayer = {
                                        navigateTo(ScreenRoute.Player)
                                    },
                                    onNavigateToArtist = { mid, name ->
                                        selectedArtistMid = mid
                                        selectedArtistName = name
                                        navigateTo(ScreenRoute.Artist)
                                    },
                                    onNavigateToAlbum = { mid, name ->
                                        selectedAlbumMid = mid
                                        selectedAlbumName = name
                                        navigateTo(ScreenRoute.Album)
                                    },
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            ScreenRoute.Acr -> {
                                AcrTvScreen(
                                    surfaceColor = globalMonetBg,
                                    onNavigateToPlayer = {
                                        navigateTo(ScreenRoute.Player)
                                    },
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            ScreenRoute.Search -> {
                                SearchTvScreen(
                                    surfaceColor = globalMonetBg,
                                    onNavigateToPlayer = {
                                        navigateTo(ScreenRoute.Player)
                                    },
                                    onNavigateToArtist = { mid, name ->
                                        selectedArtistMid = mid
                                        selectedArtistName = name
                                        navigateTo(ScreenRoute.Artist)
                                    },
                                    onNavigateToAlbum = { mid, name ->
                                        selectedAlbumMid = mid
                                        selectedAlbumName = name
                                        navigateTo(ScreenRoute.Album)
                                    },
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            ScreenRoute.WebDav -> {
                                WebDavTvScreen(
                                    surfaceColor = globalMonetBg,
                                    onNavigateToPlayer = {
                                        navigateTo(ScreenRoute.Player)
                                    },
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            ScreenRoute.LocalMusic -> {
                                LocalMusicTvScreen(
                                    surfaceColor = globalMonetBg,
                                    onNavigateToPlayer = {
                                        navigateTo(ScreenRoute.Player)
                                    },
                                    onBack = {
                                        navigateBack()
                                    },
                                )
                            }
                            ScreenRoute.Connect -> {
                                ConnectTvScreen(
                                    onBack = {
                                        navigateBack()
                                    },
                                )
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
