package org.melodist.tv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.launch
import org.melodist.model.Song
import org.melodist.model.AudioQualityTier
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.rememberTvWindowMetrics

enum class MediaDetailScreenMode {
    Detail,
    Player,
}

/**
 * 电视端通用媒体详情骨架屏（专辑 / 歌手通用）
 * 底层基于 TvSplitPlaybackScaffold 统一分栏底座，支持就地双模态播放展开与平滑返回
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MediaDetailTvScaffold(
    surfaceColor: Color,
    headerImageUrl: String,
    headerImageShape: Shape = RoundedCornerShape(6.dp),
    isHeaderImageCircle: Boolean = false,
    albumMid: String = "",
    artistMid: String = "",
    title: String,
    subtitle: String = "",
    metaInfo: String = "",
    description: String = "",
    songs: List<Song>,
    currentPlayingMid: String? = null,
    isLoading: Boolean = false,
    emptyMessage: String = "暂无相关曲目",
    isReturningFromPlayer: Boolean = false,
    onBack: (() -> Unit)? = null,
    onPlayAll: () -> Unit = {},
    onSongClick: (Song) -> Unit = {},
    onSongLongClick: (Song) -> Unit = {},
    onNavigateToArtist: ((artistMid: String, artistName: String) -> Unit)? = null,
    onNavigateToAlbum: ((albumMid: String, albumName: String) -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    isLoadingMore: Boolean = false,
    bottomContent: (@Composable () -> Unit)? = null,
    rightContent: (@Composable () -> Unit)? = null,
) {
    val metrics = rememberTvWindowMetrics()
    val listState = rememberLazyListState()

    // 屏幕模式与播放态
    var screenMode by remember { mutableStateOf(MediaDetailScreenMode.Detail) }
    var hasEverBeenInPlayerMode by remember { mutableStateOf(false) }
    var dynamicReturnTargetIndex by remember { mutableIntStateOf(-1) }
    var isControlsHidden by remember { mutableStateOf(false) }
    var showQueueSidebar by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showArtistAlbumDialog by remember { mutableStateOf(false) }
    var lastInteractionTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val currentPlayingSong by PlaybackManager.currentSong.collectAsState()
    val playlist by PlaybackManager.playlist.collectAsState()
    val isPlaying by PlaybackManager.isPlaying.collectAsState()
    val durationMs by PlaybackManager.durationMs.collectAsState()
    val selectedTier by PlaybackManager.currentTier.collectAsState()
    val lyrics by PlaybackManager.lyrics.collectAsState()
    val loopMode by PlaybackManager.loopMode.collectAsState()
    val isPlaybackLoading by PlaybackManager.isLoading.collectAsState()
    val playbackErrorMessage by PlaybackManager.errorMessage.collectAsState()
    val favoriteSongMids by PlaybackManager.favoriteSongMids.collectAsState()

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

    val shouldLoadMore =
        remember {
            derivedStateOf {
                val totalItems = listState.layoutInfo.totalItemsCount
                val lastVisibleItem =
                    listState.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.index ?: 0
                totalItems > 0 && lastVisibleItem >= totalItems - 5
            }
        }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value && !isLoadingMore && !isLoading) {
            onLoadMore?.invoke()
        }
    }

    val displayImageUrl =
        headerImageUrl.ifBlank {
            songs.firstOrNull()?.coverUrl.orEmpty()
        }
    val displayAlbumMid = albumMid
    val displayArtistMid = artistMid

    // 左右焦点导航
    val playAllFocusRequester = remember { FocusRequester() }
    val firstSongFocusRequester = remember { FocusRequester() }
    val returnSongFocusRequester = remember { FocusRequester() }
    var pageTargetFocusIndex by remember { mutableStateOf<Int?>(null) }
    val pageFocusRequester = remember { FocusRequester() }
    var isPlayAllFocused by remember { mutableStateOf(true) }
    var lastBackHandledTime by remember { mutableLongStateOf(0L) }

    val scaffoldCoroutineScope = rememberCoroutineScope()
    val returnToPlayAll: () -> Boolean = {
        val now = System.currentTimeMillis()
        lastBackHandledTime = now
        isPlayAllFocused = true
        scaffoldCoroutineScope.launch {
            try {
                playAllFocusRequester.requestFocus()
            } catch (_: Exception) {
            }
        }
        true
    }

    // 播放态 10 秒无操作隐藏控制栏
    LaunchedEffect(screenMode, isControlsHidden, showQueueSidebar, showQualityDialog, showArtistAlbumDialog, lastInteractionTimeMs) {
        if (screenMode == MediaDetailScreenMode.Player && !isControlsHidden && !showQueueSidebar && !showQualityDialog && !showArtistAlbumDialog) {
            kotlinx.coroutines.delay(10_000L)
            isControlsHidden = true
        }
    }

    // 播放态拦截返回键：优先收起弹窗/全屏隐藏，最后平滑返回详情模式
    BackHandler(enabled = screenMode == MediaDetailScreenMode.Player) {
        if (showArtistAlbumDialog) {
            showArtistAlbumDialog = false
        } else if (showQualityDialog) {
            showQualityDialog = false
        } else if (showQueueSidebar) {
            showQueueSidebar = false
        } else if (isControlsHidden) {
            isControlsHidden = false
            lastInteractionTimeMs = System.currentTimeMillis()
        } else {
            screenMode = MediaDetailScreenMode.Detail
        }
    }

    // 从 Player 模式返回 Detail 模式时的焦点吸附还原
    LaunchedEffect(screenMode) {
        if (screenMode == MediaDetailScreenMode.Player) {
            hasEverBeenInPlayerMode = true
        } else if (screenMode == MediaDetailScreenMode.Detail && hasEverBeenInPlayerMode && songs.isNotEmpty()) {
            val playingIndex =
                songs.indexOfFirst {
                    (it.songMid.isNotBlank() && it.songMid == currentPlayingSong?.songMid) ||
                        (it.songId != 0L && it.songId == currentPlayingSong?.songId)
                }
            val target = if (playingIndex in songs.indices) playingIndex else 0
            dynamicReturnTargetIndex = target
            listState.scrollToItem((target - 1).coerceAtLeast(0))
            kotlinx.coroutines.delay(60)
            try {
                returnSongFocusRequester.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    // 首次进入默认聚焦播放全部按钮
    LaunchedEffect(Unit) {
        if (!isReturningFromPlayer) {
            kotlinx.coroutines.delay(80)
            try {
                playAllFocusRequester.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    // 详情态下按返回键的逻辑
    if (onBack != null && screenMode == MediaDetailScreenMode.Detail) {
        BackHandler {
            val now = System.currentTimeMillis()
            if (now - lastBackHandledTime < 500L) {
                return@BackHandler
            }
            lastBackHandledTime = now
            if (!isPlayAllFocused) {
                returnToPlayAll()
            } else {
                onBack()
            }
        }
    }

    LaunchedEffect(pageTargetFocusIndex) {
        val target = pageTargetFocusIndex
        if (target != null) {
            kotlinx.coroutines.delay(60)
            try {
                pageFocusRequester.requestFocus()
            } catch (_: Exception) {
            }
            pageTargetFocusIndex = null
        }
    }

    TvSplitPlaybackScaffold(
        surfaceColor = surfaceColor,
        isControlsHidden = (screenMode == MediaDetailScreenMode.Player && isControlsHidden),
        controlsBottomPadding = if (screenMode == MediaDetailScreenMode.Player) 100.dp else 0.dp,
        onUserInteraction = { lastInteractionTimeMs = System.currentTimeMillis() },
        leftPanel = { coverSize ->
            val isPlayerMode = (screenMode == MediaDetailScreenMode.Player)
            val activePlayingSong = currentPlayingSong
            val effectiveCoverUrl =
                if (isPlayerMode && activePlayingSong != null && activePlayingSong.coverUrl.isNotBlank()) {
                    activePlayingSong.coverUrl
                } else {
                    displayImageUrl
                }
            val effectiveAlbumMid =
                if (isPlayerMode && activePlayingSong != null && activePlayingSong.albumMid.isNotBlank()) {
                    activePlayingSong.albumMid
                } else {
                    displayAlbumMid
                }
            val effectiveArtistMid =
                if (isPlayerMode) "" else displayArtistMid

            // 统一大屏大封面展示 (320dp 居中)
            if (effectiveCoverUrl.isNotEmpty() || effectiveAlbumMid.isNotEmpty() || effectiveArtistMid.isNotEmpty()) {
                MelodistElevatedCover(
                    coverUrl = effectiveCoverUrl,
                    albumMid = effectiveAlbumMid,
                    artistMid = effectiveArtistMid,
                    visualMid = if (isPlayerMode) activePlayingSong?.visualMid.orEmpty() else "",
                    songMid = if (isPlayerMode) activePlayingSong?.songMid.orEmpty() else "",
                    contentDescription = if (isPlayerMode) activePlayingSong?.name.orEmpty() else title,
                    shape = if (isPlayerMode) RoundedCornerShape(6.dp) else headerImageShape,
                    isCircle = if (isPlayerMode) false else isHeaderImageCircle,
                    modifier = Modifier.size(coverSize),
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .size(coverSize)
                            .clip(if (!isPlayerMode && isHeaderImageCircle) CircleShape else headerImageShape)
                            .background(MelodistColors.ContainerDarkSecondary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isPlayerMode) (activePlayingSong?.name?.take(2) ?: "音乐") else title.take(2),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 标题、副信息与控制区通过 Crossfade 顺畅过渡
            Crossfade(
                targetState = isPlayerMode,
                animationSpec = tween(durationMillis = 250),
                label = "MediaDetailLeftPanelCrossfade",
            ) { inPlayer ->
                if (inPlayer) {
                    Column(modifier = Modifier.offset(x = 8.dp)) {
                        Text(
                            text = activePlayingSong?.name?.ifBlank { "未选择曲目" } ?: "正在载入曲目...",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = MelodistColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (activePlayingSong != null) "${activePlayingSong.singer} · ${activePlayingSong.album}" else "正在同步曲目信息...",
                            fontSize = 15.sp,
                            color = MelodistColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (playbackErrorMessage != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = playbackErrorMessage ?: "",
                                fontSize = 13.sp,
                                color = Color(0xFFFF6B6B),
                                maxLines = 1,
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.offset(x = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = title,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MelodistColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )

                            val subText =
                                when {
                                    subtitle.isNotBlank() && metaInfo.isNotBlank() -> "$subtitle · $metaInfo"
                                    subtitle.isNotBlank() -> subtitle
                                    metaInfo.isNotBlank() -> metaInfo
                                    description.isNotBlank() -> description
                                    else -> ""
                                }
                            if (subText.isNotBlank()) {
                                Text(
                                    text = subText,
                                    fontSize = 13.sp,
                                    color = MelodistColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        // 播放全部按钮
                        Button(
                            onClick = {
                                onPlayAll()
                                screenMode = MediaDetailScreenMode.Player
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .focusRequester(playAllFocusRequester)
                                    .onFocusChanged {
                                        isPlayAllFocused = it.isFocused
                                    }.focusProperties {
                                        right = firstSongFocusRequester
                                    },
                            shape =
                                ButtonDefaults.shape(
                                    shape = MelodistShapes.CardCorner,
                                    focusedShape = MelodistShapes.CardCorner,
                                ),
                            colors =
                                ButtonDefaults.colors(
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    focusedContainerColor = MelodistColors.AccentGreen,
                                    contentColor = Color.White,
                                    focusedContentColor = Color.Black,
                                ),
                            border =
                                ButtonDefaults.border(
                                    border =
                                        Border(
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                            shape = MelodistShapes.CardCorner,
                                        ),
                                    focusedBorder =
                                        Border(
                                            border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                                            shape = MelodistShapes.CardCorner,
                                        ),
                                ),
                            scale = ButtonDefaults.scale(focusedScale = 1.0f),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxHeight(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = "播放全部",
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        text = "播放全部",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }

                        if (bottomContent != null) {
                            bottomContent()
                        }
                    }
                }
            }
        },
        rightContent = {
            // 模式 1：详情内容（外部自定义如选专辑网格，或默认曲目列表）
            AnimatedVisibility(
                visible = (screenMode == MediaDetailScreenMode.Detail),
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(tween(durationMillis = 250)) + slideInHorizontally(tween(durationMillis = 250)) { -30 },
                exit = fadeOut(tween(durationMillis = 200)) + slideOutHorizontally(tween(durationMillis = 200)) { -30 },
            ) {
                if (rightContent != null) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        rightContent()
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 表头
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "#",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(44.dp),
                            )
                            Text(
                                text = "歌曲",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(0.45f),
                            )
                            Text(
                                text = "歌手",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(0.28f),
                            )
                            Text(
                                text = "专辑",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(0.27f),
                            )
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            if (isLoading) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(text = "正在载入曲目...", color = Color.White, fontSize = 16.sp)
                                }
                            } else if (songs.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(text = emptyMessage, color = Color.White, fontSize = 16.sp)
                                }
                            } else {
                                val coroutineScope = rememberCoroutineScope()

                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(bottom = 32.dp),
                                ) {
                                    itemsIndexed(
                                        items = songs,
                                        key = { _, song -> song.songMid.ifBlank { song.songId.toString() } },
                                    ) { index, itemSong ->
                                        val isFirst = index == 0
                                        val isTarget = index == pageTargetFocusIndex
                                        val isReturnTarget = dynamicReturnTargetIndex >= 0 && index == dynamicReturnTargetIndex
                                        val isPlaying =
                                            (currentPlayingSong?.songMid == itemSong.songMid && itemSong.songMid.isNotBlank()) ||
                                                (itemSong.songId != 0L && currentPlayingSong?.songId == itemSong.songId)
                                        TvPlaylistSongItem(
                                            index = index + 1,
                                            song = itemSong,
                                            isPlaying = isPlaying,
                                            leftFocusTarget = playAllFocusRequester,
                                            modifier =
                                                Modifier
                                                    .then(
                                                        when {
                                                            isReturnTarget -> Modifier.focusRequester(returnSongFocusRequester)
                                                            isTarget -> Modifier.focusRequester(pageFocusRequester)
                                                            isFirst -> Modifier.focusRequester(firstSongFocusRequester)
                                                            else -> Modifier
                                                        },
                                                    ).onPreviewKeyEvent { event ->
                                                        if (event.key == Key.Back || event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_BACK) {
                                                            if (event.type == KeyEventType.KeyDown) {
                                                                returnToPlayAll()
                                                            }
                                                            true
                                                        } else if (event.type == KeyEventType.KeyDown) {
                                                            when (event.key) {
                                                                Key.DirectionRight -> {
                                                                    val targetIndex = (index + 8).coerceAtMost(songs.size - 1)
                                                                    if (targetIndex > index) {
                                                                        pageTargetFocusIndex = targetIndex
                                                                        coroutineScope.launch {
                                                                            listState.animateScrollToItem(targetIndex)
                                                                        }
                                                                        true
                                                                    } else {
                                                                        false
                                                                    }
                                                                }
                                                                Key.DirectionLeft -> {
                                                                    if (index >= 8) {
                                                                        val targetIndex = (index - 8).coerceAtLeast(0)
                                                                        pageTargetFocusIndex = targetIndex
                                                                        coroutineScope.launch {
                                                                            listState.animateScrollToItem(targetIndex)
                                                                        }
                                                                        true
                                                                    } else {
                                                                        false
                                                                    }
                                                                }
                                                                else -> false
                                                            }
                                                        } else {
                                                            false
                                                        }
                                                    },
                                            onClick = {
                                                onSongClick(itemSong)
                                                screenMode = MediaDetailScreenMode.Player
                                            },
                                            onLongClick = { onSongLongClick(itemSong) },
                                        )
                                    }

                                    if (isLoadingMore) {
                                        item {
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 16.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = "正在载入更多曲目...",
                                                    color = MelodistColors.TextMuted,
                                                    fontSize = 14.sp,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 模式 2：沉浸双语逐字歌词流
            AnimatedVisibility(
                visible = (screenMode == MediaDetailScreenMode.Player),
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(tween(durationMillis = 300)) + slideInHorizontally(tween(durationMillis = 300)) { 30 },
                exit = fadeOut(tween(durationMillis = 200)) + slideOutHorizontally(tween(durationMillis = 200)) { 30 },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (lyrics.isNotEmpty()) {
                        val lyricsCurrentPositionMs by PlaybackManager.currentPositionMs.collectAsState()
                        CenterAlignedKaraokeLyricsView(
                            lyrics = lyrics,
                            currentPositionMs = lyricsCurrentPositionMs,
                            highlightColor = themeHighlightColor,
                        )
                    } else if (isPlaybackLoading) {
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
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = (screenMode == MediaDetailScreenMode.Player && !isControlsHidden),
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
                val activePlayingSong = currentPlayingSong
                val canFavorite = PlaybackManager.isSongFavoriteSupported(activePlayingSong)
                val totalDurationMs =
                    if (durationMs > 0L) {
                        durationMs
                    } else {
                        activePlayingSong?.durationSeconds?.toLong()?.times(1000L) ?: 240000L
                    }

                BottomPlayerBar(
                    modifier = Modifier.fillMaxWidth(),
                    surfaceColor = surfaceColor,
                    accentColor = themeHighlightColor,
                    horizontalPadding = metrics.horizontalSafePadding,
                    progressMsProvider = { PlaybackManager.currentPositionMs.value },
                    durationMs = totalDurationMs,
                    isPlaying = isPlaying,
                    isFavorite = activePlayingSong?.songMid in favoriteSongMids,
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
                        if (activePlayingSong?.canShowArtistAlbumDialog == true) {
                            showArtistAlbumDialog = true
                        }
                    },
                )
            }
        },
        overlay = {
            // 全屏模式下全屏挡板：监听任意按键退出全屏模式
            if (screenMode == MediaDetailScreenMode.Player && isControlsHidden) {
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

            // 侧边栏遮罩
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
                currentSong = currentPlayingSong,
                surfaceColor = surfaceColor,
                isOpen = showQueueSidebar,
                onSelectSong = { selectedSong ->
                    PlaybackManager.playSong(selectedSong)
                },
                onDismiss = { showQueueSidebar = false },
                modifier = Modifier.align(Alignment.CenterEnd),
            )

            // 音质切换弹窗
            if (showQualityDialog) {
                AudioQualityDialog(
                    selectedTier = selectedTier,
                    onSelectTier = {
                        PlaybackManager.switchTier(it)
                    },
                    onDismiss = { showQualityDialog = false },
                )
            }

            // 歌手/专辑弹窗（播放态下按方向键下）
            val activeSong = currentPlayingSong
            if (showArtistAlbumDialog && activeSong != null && activeSong.canShowArtistAlbumDialog) {
                SongArtistAlbumDialog(
                    song = activeSong,
                    onDismissRequest = { showArtistAlbumDialog = false },
                    onSelectArtist = { mid, name ->
                        showArtistAlbumDialog = false
                        onNavigateToArtist?.invoke(mid, name)
                    },
                    onSelectAlbum = { mid, name ->
                        showArtistAlbumDialog = false
                        onNavigateToAlbum?.invoke(mid, name)
                    },
                )
            }
        },
    )
}

/**
 * 电视端媒体详情单曲项组件
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPlaylistSongItem(
    index: Int,
    song: Song,
    isPlaying: Boolean,
    leftFocusTarget: FocusRequester? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    Card(
        onClick = onClick,
        onLongClick = if (song.canShowArtistAlbumDialog) onLongClick else null,
        modifier =
            modifier
                .fillMaxWidth()
                .height(68.dp)
                .then(
                    if (leftFocusTarget != null) {
                        Modifier.focusProperties {
                            left = leftFocusTarget
                        }
                    } else {
                        Modifier
                    },
                ),
        shape =
            CardDefaults.shape(
                shape = MelodistShapes.ButtonCorner,
                focusedShape = MelodistShapes.ButtonCorner,
            ),
        colors =
            CardDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.White,
                contentColor = if (isPlaying) MelodistColors.AccentGreen else Color.White,
                focusedContentColor = Color.Black,
            ),
        border =
            CardDefaults.border(
                border =
                    Border(
                        border = BorderStroke(0.dp, Color.Transparent),
                        shape = MelodistShapes.ButtonCorner,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                        shape = MelodistShapes.ButtonCorner,
                    ),
            ),
        scale =
            CardDefaults.scale(
                focusedScale = 1.02f,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 序号 (44.dp)
            Text(
                text = String.format("%02d", index),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (isPlaying) MelodistColors.AccentGreen else LocalContentColor.current,
                modifier = Modifier.width(44.dp),
            )

            // 歌曲 (45%)
            Text(
                text = song.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isPlaying) MelodistColors.AccentGreen else LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(0.45f)
                        .padding(end = 12.dp),
            )

            // 歌手 (28%)
            Text(
                text = song.singer.ifBlank { "群星" },
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = if (isPlaying) MelodistColors.AccentGreen else LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(0.28f)
                        .padding(end = 12.dp),
            )

            // 专辑 (27%)
            Text(
                text = song.album.ifBlank { "单曲" },
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = if (isPlaying) MelodistColors.AccentGreen else LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.27f),
            )
        }
    }
}
