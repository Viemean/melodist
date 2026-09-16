package org.melodist.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.components.AudioQualityDialog
import org.melodist.tv.ui.components.BottomPlayerBar
import org.melodist.tv.ui.components.CenterAlignedKaraokeLyricsView
import org.melodist.tv.ui.components.MelodistElevatedCover
import org.melodist.tv.ui.components.PlayerQueueSidebar
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MonetColorExtractor
import org.melodist.tv.ui.theme.rememberTvWindowMetrics

import org.melodist.tv.ui.components.TvSplitPlaybackScaffold

@Composable
fun PlayerTvScreen(
    song: Song? = null,
    surfaceColor: Color = MonetColorExtractor.DefaultSurfaceColor,
    onNavigateToArtist: (String, String) -> Unit = { _, _ -> },
    onNavigateToAlbum: (String, String) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
) {
    var showQueueSidebar by remember { mutableStateOf(false) }
    var isControlsHidden by remember { mutableStateOf(false) }
    var showArtistAlbumDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var lastInteractionTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // 播放界面 10 秒无操作自动进入全屏模式
    LaunchedEffect(isControlsHidden, showQueueSidebar, showQualityDialog, showArtistAlbumDialog, lastInteractionTimeMs) {
        if (!isControlsHidden && !showQueueSidebar && !showQualityDialog && !showArtistAlbumDialog) {
            delay(10_000L)
            isControlsHidden = true
        }
    }

    BackHandler(enabled = !showQueueSidebar && !showQualityDialog && !showArtistAlbumDialog) {
        if (isControlsHidden) {
            isControlsHidden = false
            lastInteractionTimeMs = System.currentTimeMillis()
        } else {
            onBack()
        }
    }

    val metrics = rememberTvWindowMetrics()

    val currentSong by PlaybackManager.currentSong.collectAsState()
    val playlist by PlaybackManager.playlist.collectAsState()
    val isPlaying by PlaybackManager.isPlaying.collectAsState()
    val durationMs by PlaybackManager.durationMs.collectAsState()
    val selectedTier by PlaybackManager.currentTier.collectAsState()
    val lyrics by PlaybackManager.lyrics.collectAsState()
    val loopMode by PlaybackManager.loopMode.collectAsState()
    val isLoading by PlaybackManager.isLoading.collectAsState()
    val errorMessage by PlaybackManager.errorMessage.collectAsState()

    val scope = rememberCoroutineScope()
    val apiService = remember { MusicApiService() }
    val favoriteSongMids by PlaybackManager.favoriteSongMids.collectAsState()

    LaunchedEffect(song) {
        if (song != null && currentSong?.songMid != song.songMid) {
            PlaybackManager.playSong(song)
        }
    }

    LaunchedEffect(Unit) {
        if (UserSession.isLoggedIn) {
            PlaybackManager.syncFavoriteSongsAsync()
        }
    }

    val activeSong = currentSong ?: song
    val isFavorite = activeSong?.songMid in favoriteSongMids
    val songName = activeSong?.name ?: "未选择曲目"
    val songArtist = if (activeSong != null) "${activeSong.singer} · ${activeSong.album}" else "请从歌单中选择歌曲播放"
    val coverUrl = activeSong?.coverUrl ?: ""
    val themeHighlightColor =
        remember(surfaceColor) {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(surfaceColor.toArgb(), hsv)
            val hue = hsv[0]
            val rawSat = hsv[1]
            val sat = (rawSat * 1.15f).coerceIn(0.40f, 0.68f)
            val value = 0.94f
            Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
        }

    val canFavorite = PlaybackManager.isSongFavoriteSupported(activeSong)
    val totalDurationMs = if (durationMs > 0L) durationMs else (activeSong?.durationSeconds?.toLong()?.times(1000L) ?: 240000L)

    TvSplitPlaybackScaffold(
        surfaceColor = surfaceColor,
        isControlsHidden = isControlsHidden,
        onUserInteraction = { lastInteractionTimeMs = System.currentTimeMillis() },
        leftPanel = { coverSize ->
            if (coverUrl.isNotEmpty() || activeSong != null) {
                MelodistElevatedCover(
                    coverUrl = coverUrl,
                    albumMid = activeSong?.albumMid.orEmpty(),
                    visualMid = activeSong?.visualMid.orEmpty(),
                    songMid = activeSong?.songMid.orEmpty(),
                    contentDescription = "Cover",
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.size(coverSize),
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .size(coverSize)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MelodistColors.ContainerDark),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Melodist 4K",
                        color = MelodistColors.TextMuted,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // +8.dp 微偏移对齐
            Column(modifier = Modifier.offset(x = 8.dp)) {
                Text(
                    text = songName,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MelodistColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = songArtist,
                    fontSize = 15.sp,
                    color = MelodistColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = errorMessage ?: "",
                        fontSize = 13.sp,
                        color = Color(0xFFFF6B6B),
                        maxLines = 1,
                    )
                }
            }
        },
        rightContent = {
            if (lyrics.isNotEmpty()) {
                val lyricsCurrentPositionMs by PlaybackManager.currentPositionMs.collectAsState()
                CenterAlignedKaraokeLyricsView(
                    lyrics = lyrics,
                    currentPositionMs = lyricsCurrentPositionMs,
                    highlightColor = themeHighlightColor,
                )
            } else if (isLoading) {
                Text(
                    text = "正在拉取直链与同步歌词...",
                    color = MelodistColors.TextSecondary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                Text(
                    text = "暂无同步歌词",
                    color = MelodistColors.TextMuted,
                    fontSize = 18.sp,
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = !isControlsHidden,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter =
                    slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = tween(durationMillis = 300),
                    ) + fadeIn(animationSpec = tween(durationMillis = 300)),
                exit =
                    slideOutVertically(
                        targetOffsetY = { it },
                        animationSpec = tween(durationMillis = 300),
                    ) + fadeOut(animationSpec = tween(durationMillis = 300)),
            ) {
                BottomPlayerBar(
                    modifier = Modifier.fillMaxWidth(),
                    surfaceColor = surfaceColor,
                    accentColor = themeHighlightColor,
                    horizontalPadding = metrics.horizontalSafePadding,
                    progressMsProvider = { PlaybackManager.currentPositionMs.value },
                    durationMs = totalDurationMs,
                    isPlaying = isPlaying,
                    isFavorite = isFavorite,
                    canFavorite = canFavorite,
                    loopMode = loopMode.label,
                    qualityLabel = AudioQualityTier.getBadge(selectedTier),
                    onFavoriteClick = {
                        PlaybackManager.toggleCurrentSongFavorite()
                    },
                    onPrevClick = { PlaybackManager.playPrevious() },
                    onPlayPauseClick = { PlaybackManager.togglePlayPause() },
                    onNextClick = { PlaybackManager.playNext() },
                    onLoopClick = { PlaybackManager.cycleLoopMode() },
                    onQualityClick = { showQualityDialog = true },
                    onFullscreenClick = { isControlsHidden = true },
                    onSeekBy = { deltaMs ->
                        val currentPos = PlaybackManager.currentPositionMs.value
                        val targetMs = (currentPos + deltaMs).coerceIn(0L, totalDurationMs)
                        PlaybackManager.seekTo(targetMs)
                    },
                    queueCount = playlist.size,
                    onQueueClick = { showQueueSidebar = true },
                    onDownPress = {
                        if (activeSong?.canShowArtistAlbumDialog == true) {
                            showArtistAlbumDialog = true
                        }
                    },
                )
            }
        },
        overlay = {
            // 全屏模式下全屏挡板：监听任意按键仅用于退出全屏模式，阻止任何原本操作触发
            if (isControlsHidden) {
                val restoreRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    restoreRequester.requestFocus()
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(restoreRequester)
                            .focusable()
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    isControlsHidden = false
                                    lastInteractionTimeMs = System.currentTimeMillis()
                                    true
                                } else {
                                    true
                                }
                            }.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                isControlsHidden = false
                                lastInteractionTimeMs = System.currentTimeMillis()
                            },
                )
            }

            // 侧边栏外部空白点击关闭遮罩
            if (showQueueSidebar) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { showQueueSidebar = false },
                )
            }

            // 播放队列侧边栏
            PlayerQueueSidebar(
                playlist = playlist,
                currentSong = activeSong,
                surfaceColor = surfaceColor,
                isOpen = showQueueSidebar,
                onSelectSong = { selectedSong ->
                    PlaybackManager.playSong(selectedSong)
                },
                onDismiss = { showQueueSidebar = false },
                modifier = Modifier.align(Alignment.CenterEnd),
            )

            // 浮动音质选择弹窗
            if (showQualityDialog) {
                AudioQualityDialog(
                    selectedTier = selectedTier,
                    onSelectTier = {
                        PlaybackManager.switchTier(it)
                    },
                    onDismiss = { showQualityDialog = false },
                )
            }

            // 浮动歌手/专辑选择弹窗（方向键下呼出）
            if (showArtistAlbumDialog && activeSong != null && activeSong.canShowArtistAlbumDialog) {
                org.melodist.tv.ui.components.SongArtistAlbumDialog(
                    song = activeSong,
                    onDismissRequest = { showArtistAlbumDialog = false },
                    onSelectArtist = { mid, name ->
                        showArtistAlbumDialog = false
                        onNavigateToArtist(mid, name)
                    },
                    onSelectAlbum = { mid, name ->
                        showArtistAlbumDialog = false
                        onNavigateToAlbum(mid, name)
                    },
                )
            }
        },
    )
}
