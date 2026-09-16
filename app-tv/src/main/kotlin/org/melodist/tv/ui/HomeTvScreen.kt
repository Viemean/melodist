package org.melodist.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.getGuessRecommendSongs
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.components.HomeCoreTracksRow
import org.melodist.tv.ui.components.NowPlayingHeroCard
import org.melodist.tv.ui.components.TopNavBar
import org.melodist.tv.ui.theme.MonetColorExtractor
import org.melodist.tv.ui.theme.rememberTvWindowMetrics

@Composable
fun HomeTvScreen(
    currentSong: Song? = null,
    surfaceColor: Color = MonetColorExtractor.DefaultSurfaceColor,
    onNavigateToPlayer: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToAcr: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToWebDav: () -> Unit = {},
    onNavigateToLocalMusic: () -> Unit = {},
    onNavigateToConnect: () -> Unit = {},
    onNavigateToDetail: (String) -> Unit = {},
) {
    val metrics = rememberTvWindowMetrics()

    val playingSong by PlaybackManager.currentSong.collectAsState()
    val isPlaying by PlaybackManager.isPlaying.collectAsState()
    val loopMode by PlaybackManager.loopMode.collectAsState()
    val currentTier by PlaybackManager.currentTier.collectAsState()
    val durationMs by PlaybackManager.durationMs.collectAsState()
    val favoriteCount by UserSession.favoriteSongCount.collectAsState()
    val connectedPhone by org.melodist.tv.connect.TvConnectManager.connectedDevice.collectAsState()

    var selectedNavIndex by remember { mutableIntStateOf(0) }
    val favoriteSongMids by PlaybackManager.favoriteSongMids.collectAsState()
    val displaySong = playingSong ?: currentSong
    val isFavorite =
        remember(displaySong, favoriteSongMids) {
            val mid = displaySong?.songMid
            mid != null && favoriteSongMids.contains(mid)
        }

    // 确定性 D-Pad 导航焦点网络
    val topNavTabRequester = remember { FocusRequester() }
    val heroCardRequester = remember { FocusRequester() }
    val heroButtonsRequester = remember { FocusRequester() }
    val firstTrackCardRequester = remember { FocusRequester() }
    val feedRowFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        topNavTabRequester.requestFocus()
        if (UserSession.isLoggedIn) {
            PlaybackManager.syncFavoriteSongsAsync()
        }
    }

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(
                        horizontal = metrics.horizontalSafePadding,
                        vertical = metrics.verticalSafePadding,
                    ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // 1. 顶部胶囊导航栏
            TopNavBar(
                selectedIndex = selectedNavIndex,
                downFocusRequester = heroCardRequester,
                currentTabRequester = topNavTabRequester,
                onItemSelected = { index ->
                    when (index) {
                        1 -> onNavigateToWebDav()
                        2 -> onNavigateToLocalMusic()
                        3 -> onNavigateToConnect()
                        4 -> onNavigateToAcr()
                        5 -> onNavigateToSearch()
                        6 -> onNavigateToSettings()
                        else -> selectedNavIndex = index
                    }
                },
            )

            // 2. 正在播放 Hero 展台
            NowPlayingHeroCard(
                song = playingSong ?: currentSong,
                cardHeight = metrics.heroCardHeight,
                surfaceColor = surfaceColor,
                isPlaying = isPlaying,
                isFavorite = isFavorite,
                canFavorite = PlaybackManager.isSongFavoriteSupported(playingSong ?: currentSong),
                currentTier = currentTier,
                connectedPhoneName = connectedPhone?.name,
                progressMsProvider = { PlaybackManager.currentPositionMs.value },
                durationMs = durationMs,
                cardFocusRequester = heroCardRequester,
                upFocusRequester = topNavTabRequester,
                downFocusRequester = firstTrackCardRequester,
                buttonsFocusRequester = heroButtonsRequester,
                onCardClick = onNavigateToPlayer,
                onPlayPauseClick = { PlaybackManager.togglePlayPause() },
                onFavoriteClick = { PlaybackManager.toggleCurrentSongFavorite() },
            )

            val context = androidx.compose.ui.platform.LocalContext.current
            val coroutineScope = rememberCoroutineScope()

            var isRadarLoading by remember { mutableStateOf(false) }

            // 推荐与分类轨道
            HomeCoreTracksRow(
                cardWidth = metrics.trackCardWidth,
                favoriteCount = favoriteCount,
                trackFocusRequester = firstTrackCardRequester,
                onCardClick = { category -> onNavigateToDetail(category) },
                onPlayRadar = { songs ->
                    if (PlaybackManager.isRadioMode.value && PlaybackManager.currentSong.value != null) {
                        onNavigateToPlayer()
                        return@HomeCoreTracksRow
                    }
                    val availableSongs = songs.ifEmpty { org.melodist.tv.ui.components.HomeCardsCache.radarSongs }
                    if (availableSongs.isNotEmpty()) {
                        PlaybackManager.setPlaylist(availableSongs, 0, isRadio = true)
                        onNavigateToPlayer()
                    } else if (!isRadarLoading) {
                        isRadarLoading = true
                        android.widget.Toast
                            .makeText(context, "正在获取猜你喜欢推荐...", android.widget.Toast.LENGTH_SHORT)
                            .show()
                        coroutineScope.launch {
                            try {
                                val fetched = MusicApiService().getGuessRecommendSongs(count = 30)
                                if (fetched.isNotEmpty()) {
                                    PlaybackManager.setPlaylist(fetched, 0, isRadio = true)
                                    onNavigateToPlayer()
                                }
                            } catch (_: Exception) {
                            } finally {
                                isRadarLoading = false
                            }
                        }
                    }
                },
            )

            // 专属推荐轨道（听 xxx 的也喜欢听，平滑轮换与就地播放）
            org.melodist.tv.ui.components.FeedRecommendRow(
                cardWidth = metrics.trackCardWidth,
                rowFocusRequester = feedRowFocusRequester,
                onPlaySong = { songs, startIndex ->
                    PlaybackManager.setPlaylist(songs, startIndex, isRadio = false)
                },
            )
        }
    }
}
