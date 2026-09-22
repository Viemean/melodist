package org.melodist.tv.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.getGuessRecommendSongs
import org.melodist.data.DailyRecommendCacheManager
import org.melodist.data.UserLibraryCacheManager
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.theme.MelodistColors

object HomeCardsCache {
    var favSongs: List<org.melodist.model.Song> = emptyList()
    var favCover: String = ""
    var favBgColor: Color? = null

    var dailySongs: List<org.melodist.model.Song> = emptyList()
    var dailyCover: String = ""
    var dailySubtitle: String = "今日专属甄选 30 首"
    var dailyBgColor: Color? = null

    var radarSongs: List<org.melodist.model.Song> = emptyList()
    var radarCover: String = ""
    var radarAlbumMid: String = ""
    var radarSubtitle: String = "猜你喜欢"
    var radarBgColor: Color? = null

    var millionSongs: List<org.melodist.model.Song> = emptyList()
    var millionCover: String = ""
    var millionTitle: String = "百万推荐"
    var millionSubtitle: String = "官方高赞好歌专栏"
    var millionBgColor: Color? = null

    var recentSongs: List<org.melodist.model.Song> = emptyList()
    var recentCover: String = ""
    var recentTitle: String = "最近播放"
    var recentSubtitle: String = "历史播放足迹"
    var recentBgColor: Color? = null

    var playlistItems: List<org.melodist.model.Playlist> = emptyList()
    var playlistCover: String = ""
    var playlistTitle: String = "我的歌单"
    var playlistSubtitle: String = "自建歌单列表"
    var playlistBgColor: Color? = null

    var albumItems: List<org.melodist.model.Album> = emptyList()
    var albumCover: String = ""
    var albumAlbumMid: String = ""
    var albumTitle: String = "我的收藏"
    var albumSubtitle: String = "收藏的专辑与歌手"
    var albumBgColor: Color? = null

    var lastFetchTimeMs: Long = 0L
    var lastRadarFetchTimeMs: Long = 0L
    const val RADAR_REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 分钟更新一次

    fun onSongFavoriteChanged(
        song: org.melodist.model.Song,
        isFav: Boolean,
    ) {
        if (isFav) {
            val updated = mutableListOf(song)
            updated.addAll(favSongs.filterNot { it.songMid == song.songMid }.take(14))
            favSongs = updated
            if (song.coverUrl.isNotBlank()) {
                favCover = song.coverUrl
            }
        } else {
            favSongs = favSongs.filterNot { it.songMid == song.songMid }
        }
    }
}

@Composable
fun HomeCoreTracksRow(
    cardWidth: Dp = 260.dp,
    favoriteCount: Int? = null,
    trackFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    cardRequesters: List<FocusRequester> = remember { List(7) { FocusRequester() } },
    initialFocusedIndex: Int = 0,
    onCardFocused: ((Int) -> Unit)? = null,
    onCardClick: (String) -> Unit = {},
    onPlayRadar: (List<org.melodist.model.Song>) -> Unit = {},
) {
    val userProfile by UserSession.profileFlow.collectAsState()
    val apiService = remember { MusicApiService() }

    val dailyData by DailyRecommendCacheManager.recommendFlow.collectAsState()
    val favSongsList by UserLibraryCacheManager.favoriteSongsFlow.collectAsState()
    val userLibraryData by UserLibraryCacheManager.libraryFlow.collectAsState()
    val millionData by org.melodist.data.MillionRecommendManager.resultFlow
        .collectAsState()
    val recentSongsList by org.melodist.data.RecentPlaybackManager.recentSongsFlow
        .collectAsState()

    var radarSongs by remember { mutableStateOf(HomeCardsCache.radarSongs) }

    val favSongs = favSongsList
    val dailySongs = dailyData.songs
    val millionSongs = millionData.songs
    val recentSongs = recentSongsList
    val playlistItems = userLibraryData.playlists.filterNot { it.isMyFavorite }
    val albumItems = userLibraryData.favoriteAlbums

    // 7 张卡片当前选中的候选索引
    var favIndex by remember { mutableIntStateOf(0) }
    var dailyIndex by remember { mutableIntStateOf(0) }
    var radarIndex by remember { mutableIntStateOf(0) }
    var millionIndex by remember { mutableIntStateOf(0) }
    var recentIndex by remember { mutableIntStateOf(0) }
    var playlistIndex by remember { mutableIntStateOf(0) }
    var albumIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(userProfile) {
        if (!UserSession.isLoggedIn) {
            radarSongs = emptyList()
            HomeCardsCache.radarSongs = emptyList()
            return@LaunchedEffect
        }

        // 1. 每日推荐：按 00:00 自然日周期判定，今日已拉取则直接使用本地持久化缓存（0网络请求）
        launch {
            try {
                DailyRecommendCacheManager.loadRecommendSongs(apiService, forceRefresh = false)
            } catch (_: Exception) {
            }
        }

        // 2. 我的喜欢：本地持久化秒出；后台轻量探测第 1 页进行增量合并
        launch {
            try {
                if (favSongsList.isEmpty()) {
                    UserLibraryCacheManager.loadFavoriteSongs(apiService, forceRefresh = false)
                } else {
                    UserLibraryCacheManager.probeAndSyncFavoritesFirstPage(apiService)
                }
            } catch (_: Exception) {
            }
        }

        // 3. 用户库（歌单与收藏专辑）：本地持久化秒出
        launch {
            try {
                UserLibraryCacheManager.loadLibrary(apiService, forceRefresh = false)
            } catch (_: Exception) {
            }
        }

        // 4. 猜你喜欢：拉取 15 首候选队列并支持 5 分钟定时刷新
        launch {
            try {
                if (radarSongs.isEmpty()) {
                    val radar = apiService.getGuessRecommendSongs(count = 15)
                    if (radar.isNotEmpty()) {
                        val valid = radar.take(15)
                        radarSongs = valid
                        HomeCardsCache.radarSongs = valid
                        HomeCardsCache.radarCover = valid.first().coverUrl
                        HomeCardsCache.radarAlbumMid = valid.first().albumMid
                        HomeCardsCache.lastRadarFetchTimeMs = System.currentTimeMillis()
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 5. 百万推荐：自然日持久化缓存秒出；后台轻量刷新
        launch {
            try {
                org.melodist.data.MillionRecommendManager
                    .refresh(apiService, forceRefresh = false)
            } catch (_: Exception) {
            }
        }

        // 6. 最近播放：后台静默同步云端记录
        launch {
            try {
                org.melodist.data.RecentPlaybackManager
                    .syncFromCloud(force = false)
            } catch (_: Exception) {
            }
        }
    }

    val isRadioMode by PlaybackManager.isRadioMode.collectAsState()
    val isPlaying by PlaybackManager.isPlaying.collectAsState()
    val isPlayingRadar = isRadioMode && isPlaying

    // 猜你喜欢每 5 分钟在后台拉取新的一批 15 首
    LaunchedEffect(userProfile, isPlayingRadar) {
        if (!UserSession.isLoggedIn) return@LaunchedEffect
        while (isActive) {
            val now = System.currentTimeMillis()
            val timeSinceLast = now - HomeCardsCache.lastRadarFetchTimeMs
            if (HomeCardsCache.lastRadarFetchTimeMs > 0L && timeSinceLast >= HomeCardsCache.RADAR_REFRESH_INTERVAL_MS) {
                if (!isPlayingRadar) {
                    try {
                        val radar = apiService.getGuessRecommendSongs(count = 15)
                        if (radar.isNotEmpty()) {
                            val valid = radar.take(15)
                            radarSongs = valid
                            HomeCardsCache.radarSongs = valid
                            HomeCardsCache.radarCover = valid.first().coverUrl
                            HomeCardsCache.radarAlbumMid = valid.first().albumMid
                            HomeCardsCache.lastRadarFetchTimeMs = System.currentTimeMillis()
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            delay(15_000L)
        }
    }

    // 15 秒固定轮换定时器：各个卡片从自身的候选池中随机换到下一张（仅当池大小 > 1 时触发，无网/单项静止）
    LaunchedEffect(favSongs.size, dailySongs.size, radarSongs.size, millionSongs.size, playlistItems.size, albumItems.size) {
        fun pickNextRandom(
            current: Int,
            size: Int,
        ): Int {
            if (size <= 1) return 0
            var next = kotlin.random.Random.nextInt(size)
            if (next == current) {
                next = (current + 1) % size
            }
            return next
        }

        while (isActive) {
            delay(15_000L)
            if (!isPlayingRadar && radarSongs.size > 1) {
                radarIndex = pickNextRandom(radarIndex, radarSongs.size)
            }
            if (dailySongs.size > 1) {
                dailyIndex = pickNextRandom(dailyIndex, dailySongs.size)
            }
            if (favSongs.size > 1) {
                favIndex = pickNextRandom(favIndex, favSongs.size)
            }
            if (millionSongs.size > 1) {
                millionIndex = pickNextRandom(millionIndex, millionSongs.size)
            }
            val recentCandidates = recentSongs.take(15)
            if (recentCandidates.size > 1) {
                recentIndex = pickNextRandom(recentIndex, recentCandidates.size)
            }
            if (playlistItems.size > 1) {
                playlistIndex = pickNextRandom(playlistIndex, playlistItems.size)
            }
            if (albumItems.size > 1) {
                albumIndex = pickNextRandom(albumIndex, albumItems.size)
            }
        }
    }

    val currentSong by PlaybackManager.currentSong.collectAsState()

    // 构建 7 张卡片当前展示的项
    val currentRadarSong =
        if (isRadioMode && currentSong != null && currentSong?.coverUrl?.isNotBlank() == true) {
            currentSong
        } else {
            radarSongs.getOrNull(radarIndex)
        }
    val currentDailySong = dailySongs.getOrNull(dailyIndex)
    val currentFavSong = favSongs.getOrNull(favIndex)
    val currentMillionSong = millionSongs.getOrNull(millionIndex)
    val currentRecentSong = recentSongs.take(15).getOrNull(recentIndex) ?: recentSongs.firstOrNull()
    val currentPlaylist = playlistItems.getOrNull(playlistIndex)
    val currentAlbum = albumItems.getOrNull(albumIndex)

    LaunchedEffect(currentMillionSong) {
        if (currentMillionSong != null && currentMillionSong.coverUrl.isNotBlank()) {
            HomeCardsCache.millionCover = currentMillionSong.coverUrl
            HomeCardsCache.millionSongs = millionSongs
        }
    }

    LaunchedEffect(currentRecentSong) {
        if (currentRecentSong != null && currentRecentSong.coverUrl.isNotBlank()) {
            HomeCardsCache.recentCover = currentRecentSong.coverUrl
            HomeCardsCache.recentSongs = recentSongs
        }
    }

    val cardItems =
        remember(
            currentRadarSong,
            currentDailySong,
            currentFavSong,
            currentMillionSong,
            currentRecentSong,
            recentSongs.size,
            currentPlaylist,
            currentAlbum,
            favoriteCount,
        ) {
            listOf(
                // 1. 猜你喜欢（排在第一位，唯一保留播放按钮）
                RotatingCardItem(
                    id = "radar",
                    badgeText = "猜你喜欢",
                    title = currentRadarSong?.name?.ifBlank { "猜你喜欢" } ?: "猜你喜欢",
                    subtitle = currentRadarSong?.let { "${it.singer} · ${it.album}" } ?: "常听雷达推荐",
                    coverUrl = currentRadarSong?.coverUrl.orEmpty(),
                    albumMid = currentRadarSong?.albumMid.orEmpty(),
                    songMid = currentRadarSong?.songMid.orEmpty(),
                    defaultBgColor = Color(0xFF00E676),
                ),
                // 2. 每日推荐（二级页面，无播放按钮）
                RotatingCardItem(
                    id = "daily",
                    badgeText = "每日推荐",
                    title = currentDailySong?.name?.ifBlank { "每日推荐" } ?: "每日推荐",
                    subtitle = currentDailySong?.let { "${it.singer} · ${it.album}" } ?: "今日专属甄选 30 首",
                    coverUrl = currentDailySong?.coverUrl.orEmpty(),
                    albumMid = currentDailySong?.albumMid.orEmpty(),
                    songMid = currentDailySong?.songMid.orEmpty(),
                    defaultBgColor = Color(0xFF00B0FF),
                ),
                // 3. 我的喜欢（二级页面，无播放按钮）
                RotatingCardItem(
                    id = "favorites",
                    badgeText = "我的喜欢",
                    title = currentFavSong?.name?.ifBlank { "我的喜欢" } ?: "我的喜欢",
                    subtitle = currentFavSong?.let { "${it.singer} · ${it.album}" } ?: (favoriteCount?.let { "$it 首已收藏单曲" } ?: "已收藏单曲列表"),
                    coverUrl = currentFavSong?.coverUrl.orEmpty(),
                    albumMid = currentFavSong?.albumMid.orEmpty(),
                    songMid = currentFavSong?.songMid.orEmpty(),
                    defaultBgColor = Color(0xFFE91E63),
                ),
                // 4. 百万推荐（二级页面，无播放按钮）
                RotatingCardItem(
                    id = "million",
                    badgeText = "百万推荐",
                    title = currentMillionSong?.name?.ifBlank { "百万推荐" } ?: "百万推荐",
                    subtitle = currentMillionSong?.let { "${it.singer} · ${it.album}" } ?: "官方高赞好歌专栏",
                    coverUrl = currentMillionSong?.coverUrl.orEmpty(),
                    albumMid = currentMillionSong?.albumMid.orEmpty(),
                    songMid = currentMillionSong?.songMid.orEmpty(),
                    defaultBgColor = Color(0xFFFFB300),
                ),
                // 5. 最近播放（二级页面，无播放按钮）
                RotatingCardItem(
                    id = "recent",
                    badgeText = "最近播放",
                    title = currentRecentSong?.name?.ifBlank { "最近播放" } ?: "最近播放",
                    subtitle =
                        currentRecentSong?.let { "${it.singer} · 最近播放 ${recentSongs.size} 首" }
                            ?: (if (recentSongs.isNotEmpty()) "共 ${recentSongs.size} 首单曲" else "历史播放足迹"),
                    coverUrl = currentRecentSong?.coverUrl.orEmpty(),
                    albumMid = currentRecentSong?.albumMid.orEmpty(),
                    songMid = currentRecentSong?.songMid.orEmpty(),
                    defaultBgColor = Color(0xFF7C4DFF),
                ),
                // 6. 我的歌单（二级页面，无播放按钮）
                RotatingCardItem(
                    id = "playlists",
                    badgeText = "我的歌单",
                    title = currentPlaylist?.name?.ifBlank { "我的歌单" } ?: "我的歌单",
                    subtitle = currentPlaylist?.let { "共 ${it.songCount} 首" } ?: "自建歌单列表",
                    coverUrl = currentPlaylist?.picUrl.orEmpty(),
                    defaultBgColor = Color(0xFFFF9100),
                ),
                // 5. 我的收藏（二级页面，无播放按钮）
                RotatingCardItem(
                    id = "collections",
                    badgeText = "我的收藏",
                    title = currentAlbum?.title?.ifBlank { "我的收藏" } ?: "我的收藏",
                    subtitle = currentAlbum?.artist?.ifBlank { "收藏的专辑与歌手" } ?: "收藏的专辑与歌手",
                    coverUrl = currentAlbum?.coverUrl.orEmpty(),
                    albumMid = currentAlbum?.mid.orEmpty(),
                    defaultBgColor = Color(0xFFAA00FF),
                ),
            )
        }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "每日精选与常听雷达",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MelodistColors.TextPrimary,
        )

        var focusedCardIndex by remember { mutableIntStateOf(initialFocusedIndex) }
        val scrollState = rememberScrollState()

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(start = 14.dp, end = 32.dp, top = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            cardItems.forEachIndexed { index, item ->
                RotatingTrackCard(
                    item = item,
                    cardWidth = cardWidth,
                    slotIndex = index,
                    showPlayButton = (item.id == "radar"), // 仅猜你喜欢保留播放按钮，其它均为二级页面入口
                    modifier =
                        Modifier
                            .focusRequester(cardRequesters[index])
                            .then(
                                if (index == focusedCardIndex && trackFocusRequester != null) {
                                    Modifier.focusRequester(trackFocusRequester)
                                } else {
                                    Modifier
                                },
                            ).focusProperties {
                                if (upFocusRequester != null) {
                                    up = upFocusRequester
                                }
                                if (downFocusRequester != null) {
                                    down = downFocusRequester
                                }
                                left = if (index > 0) cardRequesters[index - 1] else cardRequesters.last()
                                right = if (index < cardItems.size - 1) cardRequesters[index + 1] else cardRequesters.first()
                            },
                    onFocusChanged = { focused ->
                        if (focused) {
                            focusedCardIndex = index
                            onCardFocused?.invoke(index)
                        }
                    },
                    onClick = {
                        when (item.id) {
                            "radar" -> {
                                val available = radarSongs.ifEmpty { HomeCardsCache.radarSongs }
                                if (available.isNotEmpty()) {
                                    val start = radarIndex.coerceIn(0, available.size - 1)
                                    PlaybackManager.setPlaylist(available, start, isRadio = true)
                                } else {
                                    onPlayRadar(emptyList())
                                }
                            }
                            else -> {
                                onCardClick(item.id)
                            }
                        }
                    },
                )
            }
        }
    }
}
