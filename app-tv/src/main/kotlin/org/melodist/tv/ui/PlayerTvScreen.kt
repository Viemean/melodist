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
import androidx.compose.ui.input.key.onKeyEvent
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
import org.melodist.model.LyricLine
import org.melodist.model.Song
import org.melodist.model.WordSpan
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.components.AudioQualityDialog
import org.melodist.tv.ui.components.BottomPlayerBar
import org.melodist.tv.ui.components.CenterAlignedKaraokeLyricsView
import org.melodist.tv.ui.components.MelodistElevatedCover
import org.melodist.tv.ui.components.PlayerQueueSidebar
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MonetColorExtractor
import org.melodist.tv.ui.theme.rememberTvWindowMetrics

// 演示/测试默认歌词数据
private val SampleLyrics =
    listOf(
        LyricLine(0L, "七里香 - 周杰伦", "Orange Jasmine - Jay Chou"),
        LyricLine(5000L, "作词：方文山 / 作曲：周杰伦"),
        LyricLine(
            15000L,
            "窗外的麻雀 在电线杆上多嘴",
            "The sparrows outside the window are chirping on the utility pole",
            listOf(
                WordSpan("窗", 0, 300),
                WordSpan("外", 300, 300),
                WordSpan("的", 600, 200),
                WordSpan("麻", 800, 350),
                WordSpan("雀", 1150, 400),
                WordSpan("在", 1600, 300),
                WordSpan("电", 1900, 300),
                WordSpan("线", 2200, 300),
                WordSpan("杆", 2500, 300),
                WordSpan("上", 2800, 300),
                WordSpan("多", 3100, 400),
                WordSpan("嘴", 3500, 600),
            ),
        ),
        LyricLine(
            22000L,
            "你说这一句 很有夏天的感觉",
            "You say this sentence feels very like summer",
            listOf(
                WordSpan("你", 0, 300),
                WordSpan("说", 300, 400),
                WordSpan("这", 700, 300),
                WordSpan("一", 1000, 200),
                WordSpan("句", 1200, 300),
                WordSpan("很", 1500, 300),
                WordSpan("有", 1800, 300),
                WordSpan("夏", 2100, 350),
                WordSpan("天", 2450, 400),
                WordSpan("的", 2850, 200),
                WordSpan("感", 3050, 350),
                WordSpan("觉", 3400, 600),
            ),
        ),
        LyricLine(
            30000L,
            "手中的铅笔 在纸上来来回回",
            "The pencil in hand goes back and forth on the paper",
        ),
        LyricLine(
            38000L,
            "我用几行字形容你是我的谁",
            "I use a few lines of words to describe who you are to me",
        ),
        LyricLine(
            45000L,
            "秋刀鱼的滋味 猫跟你都想了解",
            "The taste of Pacific saury, both the cat and you want to understand",
        ),
        LyricLine(
            53000L,
            "初恋的香味就这样被我们寻回",
            "The aroma of first love is thus recovered by us",
        ),
    )

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

    BackHandler(enabled = !showQueueSidebar && !showArtistAlbumDialog) {
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
    val currentPositionMs by PlaybackManager.currentPositionMs.collectAsState()
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

    val contentBottomPadding by animateDpAsState(
        targetValue = if (isControlsHidden) metrics.verticalSafePadding else 100.dp,
        animationSpec = tween(durationMillis = 300),
        label = "PlayerContentBottomPadding",
    )

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        lastInteractionTimeMs = System.currentTimeMillis()
                    }
                    false
                },
    ) {
        // 主视窗：左右分栏
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = metrics.horizontalSafePadding,
                        end = metrics.horizontalSafePadding,
                        top = metrics.verticalSafePadding,
                        bottom = contentBottomPadding,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 封面与曲目信息
            Column(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .weight(0.40f),
                verticalArrangement = Arrangement.Center,
            ) {
                if (coverUrl.isNotEmpty() || activeSong != null) {
                    MelodistElevatedCover(
                        coverUrl = coverUrl,
                        albumMid = activeSong?.albumMid.orEmpty(),
                        visualMid = activeSong?.visualMid.orEmpty(),
                        songMid = activeSong?.songMid.orEmpty(),
                        contentDescription = "Cover",
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.size(metrics.playerCoverSize),
                    )
                } else {
                    Box(
                        modifier =
                            Modifier
                                .size(metrics.playerCoverSize)
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
            }

            Spacer(modifier = Modifier.width(32.dp))

            // 右侧：水平居中对齐双语逐字歌词流
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .weight(0.60f),
                contentAlignment = Alignment.Center,
            ) {
                if (lyrics.isNotEmpty()) {
                    CenterAlignedKaraokeLyricsView(
                        lyrics = lyrics,
                        currentPositionMs = currentPositionMs,
                        highlightColor = themeHighlightColor,
                    )
                } else if (isLoading) {
                    Text(
                        text = "正在拉取直链与同步歌词...",
                        color = MelodistColors.TextSecondary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                    )
                } else if (activeSong != null) {
                    Text(
                        text = "暂无同步歌词",
                        color = MelodistColors.TextMuted,
                        fontSize = 18.sp,
                    )
                } else {
                    CenterAlignedKaraokeLyricsView(
                        lyrics = SampleLyrics,
                        currentPositionMs = currentPositionMs,
                        highlightColor = themeHighlightColor,
                    )
                }
            }
        }

        val canFavorite = PlaybackManager.isSongFavoriteSupported(activeSong)
        val totalDurationMs = if (durationMs > 0L) durationMs else (activeSong?.durationSeconds?.toLong()?.times(1000L) ?: 240000L)

        // 最底部：通栏控制栏（AnimatedVisibility 进入与退出动画）
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
                progressMs = currentPositionMs,
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
                    val targetMs = (currentPositionMs + deltaMs).coerceIn(0L, totalDurationMs)
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
    }
}
