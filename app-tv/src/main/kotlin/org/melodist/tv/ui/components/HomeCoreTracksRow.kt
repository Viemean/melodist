package org.melodist.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.getDailyRecommendSongs
import org.melodist.api.getFavoriteAlbums
import org.melodist.api.getFavoriteSongsDetail
import org.melodist.api.getGuessRecommendSongs
import org.melodist.api.getPlaylistSongs
import org.melodist.api.getPlaylists
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.MonetColorExtractor

object HomeCardsCache {
    var favCover: String = ""
    var favBgColor: Color? = null

    var dailyCover: String = ""
    var dailySubtitle: String = "今日专属甄选 30 首"
    var dailyBgColor: Color? = null

    var radarSongs: List<org.melodist.model.Song> = emptyList()
    var radarCover: String = ""
    var radarAlbumMid: String = ""
    var radarSubtitle: String = "猜你喜欢"
    var radarBgColor: Color? = null

    var playlistCover: String = ""
    var playlistTitle: String = "我的歌单"
    var playlistSubtitle: String = "自建歌单列表"
    var playlistBgColor: Color? = null

    var albumCover: String = ""
    var albumAlbumMid: String = ""
    var albumTitle: String = "我的收藏"
    var albumSubtitle: String = "收藏的专辑与歌手"
    var albumBgColor: Color? = null

    var lastFetchTimeMs: Long = 0L
    var lastRadarFetchTimeMs: Long = 0L
    private const val TTL_MS = 30 * 60 * 1000L // 30 分钟缓存保持
    const val RADAR_REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 分钟更新一次

    fun isLoaded(): Boolean =
        lastFetchTimeMs > 0L &&
            (System.currentTimeMillis() - lastFetchTimeMs < TTL_MS) &&
            (dailyCover.isNotBlank() || radarCover.isNotBlank() || favCover.isNotBlank() || playlistCover.isNotBlank())
}

data class TrackCardData(
    val id: String,
    val title: String,
    val subtitle: String,
    val defaultColors: List<Color>,
    val singleCoverUrl: String = "",
    val albumMid: String = "",
    val dynamicBgColor: Color? = null,
)

@Composable
fun HomeCoreTracksRow(
    cardWidth: Dp = 260.dp,
    favoriteCount: Int? = null,
    trackFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    onCardClick: (String) -> Unit = {},
    onPlayRadar: (List<org.melodist.model.Song>) -> Unit = {},
) {
    val favoriteSubtitle =
        when {
            favoriteCount != null && favoriteCount > 0 -> "$favoriteCount 首已收藏单曲"
            favoriteCount == 0 -> "暂无收藏曲目"
            else -> "已收藏单曲列表"
        }

    val userProfile by UserSession.profileFlow.collectAsState()
    val apiService = remember { MusicApiService() }

    var favCover by remember { mutableStateOf(HomeCardsCache.favCover) }
    var favBgColor by remember { mutableStateOf<Color?>(HomeCardsCache.favBgColor) }

    var dailyCover by remember { mutableStateOf(HomeCardsCache.dailyCover) }
    var dailySubtitle by remember { mutableStateOf(HomeCardsCache.dailySubtitle) }
    var dailyBgColor by remember { mutableStateOf<Color?>(HomeCardsCache.dailyBgColor) }

    var radarCover by remember { mutableStateOf(HomeCardsCache.radarCover) }
    var radarAlbumMid by remember { mutableStateOf(HomeCardsCache.radarAlbumMid) }
    var radarSubtitle by remember { mutableStateOf(HomeCardsCache.radarSubtitle) }
    var radarBgColor by remember { mutableStateOf<Color?>(HomeCardsCache.radarBgColor) }
    var preloadedRadarSongs by remember { mutableStateOf<List<org.melodist.model.Song>>(HomeCardsCache.radarSongs) }

    var playlistCover by remember { mutableStateOf(HomeCardsCache.playlistCover) }
    var playlistTitle by remember { mutableStateOf(HomeCardsCache.playlistTitle) }
    var playlistSubtitle by remember { mutableStateOf(HomeCardsCache.playlistSubtitle) }
    var playlistBgColor by remember { mutableStateOf<Color?>(HomeCardsCache.playlistBgColor) }

    var albumCover by remember { mutableStateOf(HomeCardsCache.albumCover) }
    var albumAlbumMid by remember { mutableStateOf(HomeCardsCache.albumAlbumMid) }
    var albumTitle by remember { mutableStateOf(HomeCardsCache.albumTitle) }
    var albumSubtitle by remember { mutableStateOf(HomeCardsCache.albumSubtitle) }
    var albumBgColor by remember { mutableStateOf<Color?>(HomeCardsCache.albumBgColor) }

    LaunchedEffect(userProfile) {
        if (!UserSession.isLoggedIn) {
            HomeCardsCache.favCover = ""
            HomeCardsCache.favBgColor = null
            HomeCardsCache.dailyCover = ""
            HomeCardsCache.dailyBgColor = null
            HomeCardsCache.radarCover = ""
            HomeCardsCache.radarAlbumMid = ""
            HomeCardsCache.radarBgColor = null
            HomeCardsCache.radarSongs = emptyList()
            HomeCardsCache.playlistCover = ""
            HomeCardsCache.playlistBgColor = null
            HomeCardsCache.albumCover = ""
            HomeCardsCache.albumAlbumMid = ""
            HomeCardsCache.albumBgColor = null
            HomeCardsCache.lastFetchTimeMs = 0L
            HomeCardsCache.lastRadarFetchTimeMs = 0L

            favCover = ""
            favBgColor = null
            dailyCover = ""
            dailyBgColor = null
            radarCover = ""
            radarAlbumMid = ""
            radarBgColor = null
            preloadedRadarSongs = emptyList()
            playlistCover = ""
            playlistBgColor = null
            albumCover = ""
            albumAlbumMid = ""
            albumBgColor = null
            return@LaunchedEffect
        }

        // 命中缓存时跳过重复请求
        if (HomeCardsCache.isLoaded()) {
            return@LaunchedEffect
        }

        // 5 个入口并发拉取
        val j1 =
            launch {
                try {
                    val fav = apiService.getFavoriteSongsDetail(page = 1, pageSize = 1)
                    val first = fav.songs.firstOrNull()
                    if (first != null && first.coverUrl.isNotBlank()) {
                        favCover = first.coverUrl
                        val bg = MonetColorExtractor.extractFromUrl(first.coverUrl)
                        favBgColor = bg
                        HomeCardsCache.favCover = first.coverUrl
                        HomeCardsCache.favBgColor = bg
                    }
                } catch (_: Exception) {
                }
            }

        // 2. 每日推荐：首张推荐封面与莫奈单图取色
        val j2 =
            launch {
                try {
                    val daily = apiService.getDailyRecommendSongs()
                    val first = daily.firstOrNull()
                    if (first != null) {
                        val sub = "${first.name} · ${first.singer}"
                        val bg = if (first.coverUrl.isNotBlank()) MonetColorExtractor.extractFromUrl(first.coverUrl) else null
                        dailyCover = first.coverUrl
                        dailySubtitle = sub
                        dailyBgColor = bg

                        HomeCardsCache.dailyCover = first.coverUrl
                        HomeCardsCache.dailySubtitle = sub
                        HomeCardsCache.dailyBgColor = bg
                    }
                } catch (_: Exception) {
                }
            }

        // 3. 猜你喜欢雷达
        val j3 =
            launch {
                try {
                    val radar = apiService.getGuessRecommendSongs(count = 30)
                    val first = radar.firstOrNull()
                    if (first != null) {
                        val bg = if (first.coverUrl.isNotBlank()) MonetColorExtractor.extractFromUrl(first.coverUrl) else null
                        val sub = "${first.name} · ${first.singer}"

                        radarCover = first.coverUrl
                        radarAlbumMid = first.albumMid
                        radarSubtitle = sub
                        radarBgColor = bg
                        preloadedRadarSongs = radar

                        HomeCardsCache.radarSongs = radar
                        HomeCardsCache.radarCover = first.coverUrl
                        HomeCardsCache.radarAlbumMid = first.albumMid
                        HomeCardsCache.radarSubtitle = sub
                        HomeCardsCache.radarBgColor = bg
                        HomeCardsCache.lastRadarFetchTimeMs = System.currentTimeMillis()
                    }
                } catch (_: Exception) {
                }
            }

        // 4. 我的歌单：首个歌单封面
        val j4 =
            launch {
                try {
                    val lists = apiService.getPlaylists().filterNot { it.isMyFavorite }
                    val first = lists.firstOrNull()
                    if (first != null) {
                        var cover = first.picUrl
                        if (cover.isBlank()) {
                            try {
                                val songs = apiService.getPlaylistSongs(first, page = 1, pageSize = 1)
                                cover = songs.firstOrNull()?.coverUrl.orEmpty()
                            } catch (_: Exception) {
                            }
                        }
                        val sub = "${first.name} · 共 ${first.songCount} 首"
                        val bg = if (cover.isNotBlank()) MonetColorExtractor.extractFromUrl(cover) else null

                        playlistCover = cover
                        playlistTitle = "我的歌单"
                        playlistSubtitle = sub
                        playlistBgColor = bg

                        HomeCardsCache.playlistCover = cover
                        HomeCardsCache.playlistTitle = "我的歌单"
                        HomeCardsCache.playlistSubtitle = sub
                        HomeCardsCache.playlistBgColor = bg
                    }
                } catch (_: Exception) {
                }
            }

        // 5. 我的收藏：首个专辑封面
        val j5 =
            launch {
                try {
                    val albums = apiService.getFavoriteAlbums()
                    val first = albums.firstOrNull()
                    if (first != null) {
                        val sub = "${first.title} · ${first.artist}"
                        val bg = if (first.coverUrl.isNotBlank()) MonetColorExtractor.extractFromUrl(first.coverUrl) else null

                        albumCover = first.coverUrl
                        albumAlbumMid = first.mid
                        albumTitle = "我的收藏"
                        albumSubtitle = sub
                        albumBgColor = bg

                        HomeCardsCache.albumCover = first.coverUrl
                        HomeCardsCache.albumAlbumMid = first.mid
                        HomeCardsCache.albumTitle = "我的收藏"
                        HomeCardsCache.albumSubtitle = sub
                        HomeCardsCache.albumBgColor = bg
                    }
                } catch (_: Exception) {
                }
            }

        kotlinx.coroutines.joinAll(j1, j2, j3, j4, j5)
        if (dailyCover.isNotBlank() || radarCover.isNotBlank() || favCover.isNotBlank() || playlistCover.isNotBlank()) {
            HomeCardsCache.lastFetchTimeMs = System.currentTimeMillis()
        }
    }

    val isRadioMode by PlaybackManager.isRadioMode.collectAsState()
    val isPlaying by PlaybackManager.isPlaying.collectAsState()
    val isPlayingRadar = isRadioMode && isPlaying

    // 若没有在播放猜你喜欢，每 5 分钟更新一批歌曲
    LaunchedEffect(userProfile, isPlayingRadar) {
        if (!UserSession.isLoggedIn) return@LaunchedEffect
        while (isActive) {
            val now = System.currentTimeMillis()
            val timeSinceLast = now - HomeCardsCache.lastRadarFetchTimeMs
            if (HomeCardsCache.lastRadarFetchTimeMs > 0L && timeSinceLast >= HomeCardsCache.RADAR_REFRESH_INTERVAL_MS) {
                if (!isPlayingRadar) {
                    try {
                        val radar = apiService.getGuessRecommendSongs(count = 30)
                        val first = radar.firstOrNull()
                        if (first != null) {
                            val bg = if (first.coverUrl.isNotBlank()) MonetColorExtractor.extractFromUrl(first.coverUrl) else null
                            val sub = "${first.name} · ${first.singer}"

                            radarCover = first.coverUrl
                            radarAlbumMid = first.albumMid
                            radarSubtitle = sub
                            radarBgColor = bg
                            preloadedRadarSongs = radar

                            HomeCardsCache.radarSongs = radar
                            HomeCardsCache.radarCover = first.coverUrl
                            HomeCardsCache.radarAlbumMid = first.albumMid
                            HomeCardsCache.radarSubtitle = sub
                            HomeCardsCache.radarBgColor = bg
                            HomeCardsCache.lastRadarFetchTimeMs = System.currentTimeMillis()
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            delay(10_000L)
        }
    }

    val currentSong by PlaybackManager.currentSong.collectAsState()

    // 若当前正在播放猜你喜欢，卡片信息跟随当前播放的歌曲动态变化（切下一首回到主页跟随变化）
    val displayRadarCover =
        if (isRadioMode && currentSong != null && currentSong?.coverUrl?.isNotBlank() == true) {
            currentSong!!.coverUrl
        } else {
            radarCover
        }

    val displayRadarAlbumMid =
        if (isRadioMode && currentSong != null) {
            currentSong!!.albumMid
        } else {
            radarAlbumMid
        }

    val displayRadarSubtitle =
        if (isRadioMode && currentSong != null) {
            "${currentSong!!.name} · ${currentSong!!.singer}"
        } else {
            radarSubtitle
        }

    var radarDynamicMonetBg by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(displayRadarCover) {
        if (displayRadarCover.isNotBlank()) {
            radarDynamicMonetBg = MonetColorExtractor.extractFromUrl(displayRadarCover)
        }
    }
    val displayRadarBgColor = radarDynamicMonetBg ?: radarBgColor

    // 状态与全局缓存实时同步
    LaunchedEffect(isRadioMode, currentSong) {
        if (isRadioMode && currentSong != null) {
            val song = currentSong!!
            val sub = "${song.name} · ${song.singer}"
            radarCover = song.coverUrl
            radarAlbumMid = song.albumMid
            radarSubtitle = sub
            HomeCardsCache.radarCover = song.coverUrl
            HomeCardsCache.radarAlbumMid = song.albumMid
            HomeCardsCache.radarSubtitle = sub
            if (song.coverUrl.isNotBlank()) {
                val bg = MonetColorExtractor.extractFromUrl(song.coverUrl)
                radarBgColor = bg
                HomeCardsCache.radarBgColor = bg
            }
        }
    }

    val cards =
        remember(
            favoriteSubtitle,
            favCover,
            favBgColor,
            dailyCover,
            dailySubtitle,
            dailyBgColor,
            displayRadarCover,
            displayRadarAlbumMid,
            displayRadarSubtitle,
            displayRadarBgColor,
            playlistCover,
            playlistTitle,
            playlistSubtitle,
            playlistBgColor,
            albumCover,
            albumAlbumMid,
            albumTitle,
            albumSubtitle,
            albumBgColor,
        ) {
            listOf(
                TrackCardData(
                    id = "favorites",
                    title = "我的喜欢",
                    subtitle = favoriteSubtitle,
                    defaultColors = listOf(Color(0xFFE91E63), Color(0xFF880E4F)),
                    singleCoverUrl = favCover,
                    dynamicBgColor = favBgColor,
                ),
                TrackCardData(
                    id = "daily",
                    title = "每日推荐",
                    subtitle = dailySubtitle,
                    defaultColors = listOf(Color(0xFF00B0FF), Color(0xFF0D47A1)),
                    singleCoverUrl = dailyCover,
                    dynamicBgColor = dailyBgColor,
                ),
                TrackCardData(
                    id = "radar",
                    title = "猜你喜欢",
                    subtitle = displayRadarSubtitle,
                    defaultColors = listOf(Color(0xFF00E676), Color(0xFF1B5E20)),
                    singleCoverUrl = displayRadarCover,
                    albumMid = displayRadarAlbumMid,
                    dynamicBgColor = displayRadarBgColor,
                ),
                TrackCardData(
                    id = "playlists",
                    title = "我的歌单",
                    subtitle = playlistSubtitle,
                    defaultColors = listOf(Color(0xFFFF9100), Color(0xFFE65100)),
                    singleCoverUrl = playlistCover,
                    dynamicBgColor = playlistBgColor,
                ),
                TrackCardData(
                    id = "collections",
                    title = "我的收藏",
                    subtitle = albumSubtitle,
                    defaultColors = listOf(Color(0xFFAA00FF), Color(0xFF4A148C)),
                    singleCoverUrl = albumCover,
                    albumMid = albumAlbumMid,
                    dynamicBgColor = albumBgColor,
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

        var focusedCardIndex by remember { mutableIntStateOf(0) }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            contentPadding = PaddingValues(start = 14.dp, end = 32.dp, top = 14.dp, bottom = 14.dp),
        ) {
            itemsIndexed(cards) { index, card ->
                CoreTrackCard(
                    data = card,
                    cardWidth = cardWidth,
                    modifier =
                        if (index == focusedCardIndex && trackFocusRequester != null) {
                            Modifier.focusRequester(trackFocusRequester)
                        } else {
                            Modifier
                        },
                    onFocused = { focusedCardIndex = index },
                    onClick = {
                        if (card.id == "radar") {
                            onPlayRadar(preloadedRadarSongs)
                        } else {
                            onCardClick(card.id)
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CoreTrackCard(
    data: TrackCardData,
    cardWidth: Dp,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val cardHeight = cardWidth * 1.22f

    val currentBgColor = data.dynamicBgColor ?: data.defaultColors[0]
    val animatedBgColor by animateColorAsState(
        targetValue = currentBgColor,
        animationSpec = tween(600),
        label = "CardBgColor",
    )

    Card(
        onClick = onClick,
        modifier =
            modifier
                .width(cardWidth)
                .height(cardHeight)
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) {
                        onFocused()
                    }
                },
        shape =
            CardDefaults.shape(
                shape = MelodistShapes.CardCorner,
                focusedShape = MelodistShapes.CardCorner,
            ),
        colors =
            CardDefaults.colors(
                containerColor = animatedBgColor.copy(alpha = 0.90f),
                focusedContainerColor = animatedBgColor,
            ),
        border =
            CardDefaults.border(
                border =
                    Border(
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        shape = MelodistShapes.CardCorner,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(3.dp, MelodistColors.FocusTeal),
                        shape = MelodistShapes.CardCorner,
                    ),
            ),
        scale =
            CardDefaults.scale(
                focusedScale = 1.05f,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(animatedBgColor),
        ) {
            // 上半部：封面展示区
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(cardHeight * 0.65f)
                        .padding(top = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (data.singleCoverUrl.isNotBlank() || data.albumMid.isNotBlank()) {
                    // 单封面展示
                    val singleSize = (cardWidth * 0.64f).coerceIn(130.dp, 175.dp)
                    MelodistElevatedCover(
                        coverUrl = data.singleCoverUrl,
                        albumMid = data.albumMid,
                        contentDescription = null,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.size(singleSize),
                    )
                } else {
                    // 无封面或未登录时的内置质感图标兜底
                    Box(
                        modifier =
                            Modifier
                                .size((cardWidth * 0.50f).coerceIn(100.dp, 130.dp))
                                .clip(MelodistShapes.CardCorner)
                                .background(Color.White.copy(alpha = 0.08f))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), MelodistShapes.CardCorner),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = data.title.take(2),
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // 下半部：冷调微透纯色遮罩与文字排版 (无渐变以防电视摩尔纹)
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .background(Color(0xFF1A2234).copy(alpha = 0.45f))
                        .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = data.title,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Text(
                        text = data.subtitle,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
