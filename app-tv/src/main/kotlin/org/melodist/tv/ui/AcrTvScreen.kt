package org.melodist.tv.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.tv.material3.*
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import org.melodist.tv.acr.AcrUiState
import org.melodist.tv.acr.AcrViewModel
import org.melodist.tv.ui.components.AudioQualityDialog
import org.melodist.tv.ui.components.BottomPlayerBar
import org.melodist.tv.ui.components.CenterAlignedKaraokeLyricsView
import org.melodist.tv.ui.components.MelodistElevatedCover
import org.melodist.tv.ui.components.PlayerQueueSidebar
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.rememberTvWindowMetrics
import org.melodist.tv.ui.theme.toMonetContainer
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AcrTvScreen(
    surfaceColor: Color,
    onNavigateToPlayer: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = remember { AcrViewModel() }
    val context = LocalContext.current
    val metrics = rememberTvWindowMetrics()

    val uiState by viewModel.uiState.collectAsState()
    val audioEnergy by viewModel.audioEnergy.collectAsState()

    val currentSong by PlaybackManager.currentSong.collectAsState()
    val lyrics by PlaybackManager.lyrics.collectAsState()
    val lyricsCurrentPositionMs by PlaybackManager.currentPositionMs.collectAsState()
    val isMuted by PlaybackManager.isMuted.collectAsState()
    val favoriteSongMids by PlaybackManager.favoriteSongMids.collectAsState()
    val isLoadingLyrics by PlaybackManager.isLoading.collectAsState()
    val isPlaying by PlaybackManager.isPlaying.collectAsState()
    val durationMs by PlaybackManager.durationMs.collectAsState()
    val loopMode by PlaybackManager.loopMode.collectAsState()
    val selectedTier by PlaybackManager.currentTier.collectAsState()
    val playlist by PlaybackManager.playlist.collectAsState()

    val muteButtonFocusRequester = remember { FocusRequester() }
    val actionFocusRequester = remember { FocusRequester() }

    val btnContainer = remember(surfaceColor) { surfaceColor.toMonetContainer(0.08f) }

    // 记录进入听歌识曲前的播放快照
    val originalSong = remember { PlaybackManager.currentSong.value }
    val originalIsPlaying = remember { PlaybackManager.isPlaying.value }
    val originalPositionMs = remember { PlaybackManager.currentPositionMs.value }
    val originalPlaylist = remember { PlaybackManager.playlist.value }
    val originalIndex = remember { PlaybackManager.currentIndex.value }
    val originalTier = remember { PlaybackManager.currentTier.value }

    // 是否已点击静音按钮转正进入播放控制态（原地动画过渡）
    var hasUnmutedToPlayer by remember { mutableStateOf(false) }
    var isPlaybackRestored by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showQueueSidebar by remember { mutableStateOf(false) }

    fun restoreOriginalPlayback() {
        if (isPlaybackRestored) return
        isPlaybackRestored = true
        if (PlaybackManager.isMuted.value) {
            PlaybackManager.setMuted(false)
        }
        if (originalSong != null) {
            val currentSongNow = PlaybackManager.currentSong.value
            if (currentSongNow?.songMid != originalSong.songMid || PlaybackManager.playlist.value != originalPlaylist) {
                if (originalPlaylist.isNotEmpty()) {
                    PlaybackManager.setPlaylist(
                        songs = originalPlaylist,
                        startIndex = originalIndex.coerceIn(0, (originalPlaylist.size - 1).coerceAtLeast(0)),
                        initialSeekToMs = originalPositionMs,
                        forceTier = originalTier,
                    )
                } else {
                    PlaybackManager.playSong(originalSong, forceTier = originalTier, seekToMs = originalPositionMs)
                }
                if (!originalIsPlaying) {
                    PlaybackManager.pause()
                }
            } else {
                if (originalIsPlaying) {
                    PlaybackManager.play()
                } else {
                    PlaybackManager.pause()
                }
            }
        } else {
            if (PlaybackManager.currentSong.value != null) {
                PlaybackManager.pause()
            }
        }
    }

    // 离开界面时重置识别流，并在未转正播放识别歌曲时恢复原曲目与播放状态
    DisposableEffect(Unit) {
        onDispose {
            viewModel.reset()
            if (!hasUnmutedToPlayer) {
                restoreOriginalPlayback()
            } else if (PlaybackManager.isMuted.value) {
                PlaybackManager.setMuted(false)
            }
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            if (isGranted) {
                viewModel.startRecognition()
            } else {
                viewModel.setPermissionRequired()
            }
        }

    fun checkAndStartRecognition() {
        val hasPermission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            viewModel.startRecognition()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 首次进入自动暂停原播放（避免麦克风自拾音干扰）并启动识别
    LaunchedEffect(Unit) {
        if (originalIsPlaying) {
            PlaybackManager.pause()
        }
        checkAndStartRecognition()
    }

    // 识别成功后，自动以静音模式在对齐的时间点起播曲目并拉取双语逐字歌词
    var syncedSongMid by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState) {
        val state = uiState
        if (state is AcrUiState.Success) {
            val song = state.song
            if (syncedSongMid != song.songMid) {
                syncedSongMid = song.songMid
                val elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime() - state.anchorRealtimeMs
                val prepLatencyMs = 900L // TV 端网络与解码预加载补偿
                val seekMs = ((state.offsetSeconds * 1000).toLong() + elapsedRealtimeMs + prepLatencyMs).coerceAtLeast(0L)

                PlaybackManager.setMuted(true)
                PlaybackManager.playSong(song, seekToMs = seekMs)
            }
            // 自动将遥控器焦点定在“静音中”按钮上
            try {
                muteButtonFocusRequester.requestFocus()
            } catch (_: Exception) {
            }
        } else {
            syncedSongMid = null
            hasUnmutedToPlayer = false
        }
    }

    // 点击静音按钮：平滑解除静音，按钮向下滑出，播放控制栏向上浮现
    fun unmuteAndShowPlayerControls() {
        PlaybackManager.setMuted(false)
        hasUnmutedToPlayer = true
    }

    // 重新识别
    fun restartRecognition() {
        if (PlaybackManager.isMuted.value) {
            PlaybackManager.setMuted(false)
            PlaybackManager.pause()
        }
        syncedSongMid = null
        hasUnmutedToPlayer = false
        viewModel.reset()
        checkAndStartRecognition()
    }

    // 返回拦截
    BackHandler {
        if (showQualityDialog) {
            showQualityDialog = false
        } else if (showQueueSidebar) {
            showQueueSidebar = false
        } else {
            if (!hasUnmutedToPlayer) {
                restoreOriginalPlayback()
            }
            onBack()
        }
    }

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
        targetValue = if (hasUnmutedToPlayer) 120.dp else 28.dp,
        animationSpec = tween(durationMillis = 350),
        label = "AcrContentBottomPadding",
    )

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(surfaceColor),
    ) {
        // 顶部导航栏：仅保留左上角返回按钮
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = metrics.horizontalSafePadding,
                        end = metrics.horizontalSafePadding,
                        top = 22.dp,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AcrHeaderBackButton(
                onClick = {
                    if (!hasUnmutedToPlayer) {
                        restoreOriginalPlayback()
                    }
                    onBack()
                },
            )
        }

        // 核心内容区：同构复用 PlayerTvScreen 的左右分栏架构 (0.40f vs 0.60f)
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = metrics.horizontalSafePadding,
                        end = metrics.horizontalSafePadding,
                        top = 64.dp,
                        bottom = contentBottomPadding,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧：封面、曲目信息与控制动作组 (40%)
            Column(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .weight(0.40f),
                verticalArrangement = Arrangement.Center,
            ) {
                when (val state = uiState) {
                    is AcrUiState.Success -> {
                        val activeSong = state.song
                        // 专辑封面：干净完整呈现，不遮挡封面
                        Box(
                            modifier = Modifier.size(metrics.playerCoverSize),
                        ) {
                            MelodistElevatedCover(
                                coverUrl = activeSong.coverUrl,
                                albumMid = activeSong.albumMid,
                                visualMid = activeSong.visualMid,
                                songMid = activeSong.songMid,
                                contentDescription = "Cover",
                                shape = MelodistShapes.ButtonCorner,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // 曲名与歌手
                        Column(modifier = Modifier.offset(x = 8.dp)) {
                            Text(
                                text = activeSong.name,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${activeSong.singer} · ${activeSong.album.ifBlank { "单曲" }}",
                                fontSize = 15.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // 操作控制按钮组：点击静音后平滑向下隐藏，给用户一直在一个界面的沉浸感
                        val isFav = favoriteSongMids.contains(activeSong.songMid)
                        AnimatedVisibility(
                            visible = !hasUnmutedToPlayer,
                            enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it },
                            exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { it },
                        ) {
                            Row(
                                modifier = Modifier.offset(x = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 1. 收藏按钮
                                AcrTvButton(
                                    text = if (isFav) "已收藏" else "收藏",
                                    icon = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    containerBg = btnContainer,
                                    activeTintColor = if (isFav) Color(0xFFFF5252) else null,
                                    onClick = {
                                        PlaybackManager.toggleSongFavorite(activeSong)
                                    },
                                )

                                // 2. 静音按钮（温和 Monet 质感，绝不使用刺眼红底，显示图标与“静音中”，点击过渡隐藏）
                                AcrTvButton(
                                    text = "静音中",
                                    icon = Icons.Default.VolumeOff,
                                    containerBg = btnContainer,
                                    focusRequester = muteButtonFocusRequester,
                                    onClick = {
                                        unmuteAndShowPlayerControls()
                                    },
                                )

                                // 3. 重新识别按钮（防止换行，单行紧凑排布）
                                AcrTvButton(
                                    text = "重新识别",
                                    icon = Icons.Default.Refresh,
                                    containerBg = btnContainer,
                                    onClick = {
                                        restartRecognition()
                                    },
                                )
                            }
                        }
                    }

                    is AcrUiState.Listening -> {
                        // 正在识别中的卡片，背景与圆角完全对齐播放界面按钮底色
                        AcrListeningVisualCard(
                            coverSize = metrics.playerCoverSize,
                            audioEnergy = audioEnergy,
                            recordedSeconds = state.recordedSeconds,
                            containerBg = btnContainer,
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Column(modifier = Modifier.offset(x = 8.dp)) {
                            Text(
                                text = "正在聆听环境声音...",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = String.format(Locale.US, "已采集 %.1fs · 实时比对声学特征", state.recordedSeconds),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MelodistColors.AccentGreen,
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(modifier = Modifier.offset(x = 8.dp)) {
                            AcrTvButton(
                                text = "取消识别",
                                icon = Icons.Default.Close,
                                containerBg = btnContainer,
                                focusRequester = actionFocusRequester,
                                onClick = {
                                    viewModel.reset()
                                },
                            )
                        }
                    }

                    is AcrUiState.Failed -> {
                        AcrFailedVisualCard(
                            coverSize = metrics.playerCoverSize,
                            containerBg = btnContainer,
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Column(modifier = Modifier.offset(x = 8.dp)) {
                            Text(
                                text = "未能识别出曲目",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.message.ifBlank { "请靠近音箱或提高环境音量后重试" },
                                fontSize = 15.sp,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(modifier = Modifier.offset(x = 8.dp)) {
                            AcrTvButton(
                                text = "重新识别",
                                icon = Icons.Default.Refresh,
                                containerBg = btnContainer,
                                focusRequester = actionFocusRequester,
                                onClick = {
                                    checkAndStartRecognition()
                                },
                            )
                        }
                    }

                    is AcrUiState.PermissionRequired -> {
                        AcrPermissionVisualCard(
                            coverSize = metrics.playerCoverSize,
                            containerBg = btnContainer,
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Column(modifier = Modifier.offset(x = 8.dp)) {
                            Text(
                                text = "需要麦克风录音权限",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "听歌识曲需要录音权限以采集声学特征",
                                fontSize = 15.sp,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(modifier = Modifier.offset(x = 8.dp)) {
                            AcrTvButton(
                                text = "授予麦克风权限",
                                icon = Icons.Default.Security,
                                containerBg = btnContainer,
                                focusRequester = actionFocusRequester,
                                onClick = {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                            )
                        }
                    }

                    else -> {
                        // Idle
                        AcrListeningVisualCard(
                            coverSize = metrics.playerCoverSize,
                            audioEnergy = 0f,
                            recordedSeconds = 0f,
                            containerBg = btnContainer,
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Column(modifier = Modifier.offset(x = 8.dp)) {
                            Text(
                                text = "准备就绪",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "点击开始识别环境播放的歌曲",
                                fontSize = 15.sp,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(modifier = Modifier.offset(x = 8.dp)) {
                            AcrTvButton(
                                text = "开始识别",
                                icon = Icons.Default.PlayArrow,
                                containerBg = btnContainer,
                                focusRequester = actionFocusRequester,
                                onClick = {
                                    checkAndStartRecognition()
                                },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(32.dp))

            // 右侧：双语逐字卡拉OK歌词流 (60%)，高对比度白字清晰展现
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .weight(0.60f),
                contentAlignment = Alignment.Center,
            ) {
                if (uiState is AcrUiState.Success) {
                    if (lyrics.isNotEmpty()) {
                        CenterAlignedKaraokeLyricsView(
                            lyrics = lyrics,
                            currentPositionMs = lyricsCurrentPositionMs,
                            highlightColor = themeHighlightColor,
                        )
                    } else if (isLoadingLyrics) {
                        Text(
                            text = "已识别曲目，正在拉取同步歌词...",
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    } else {
                        Text(
                            text = "暂无同步歌词",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 18.sp,
                        )
                    }
                }
            }
        }

        // 解除静音后，底部播放器控制栏平滑向上浮现
        val activeSong = (uiState as? AcrUiState.Success)?.song ?: currentSong
        val canFavorite = PlaybackManager.isSongFavoriteSupported(activeSong)
        val isFav = activeSong != null && favoriteSongMids.contains(activeSong.songMid)
        val totalDurationMs = if (durationMs > 0L) durationMs else (activeSong?.durationSeconds?.toLong()?.times(1000L) ?: 240000L)

        AnimatedVisibility(
            visible = hasUnmutedToPlayer,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter =
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(durationMillis = 350),
                ) + fadeIn(animationSpec = tween(durationMillis = 350)),
            exit =
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(durationMillis = 300),
                ) + fadeOut(animationSpec = tween(durationMillis = 300)),
        ) {
            val canChangeQuality = !PlaybackManager.isLocalOrWebDavSong(activeSong)
            BottomPlayerBar(
                modifier = Modifier.fillMaxWidth(),
                surfaceColor = surfaceColor,
                accentColor = themeHighlightColor,
                horizontalPadding = metrics.horizontalSafePadding,
                progressMsProvider = { PlaybackManager.currentPositionMs.value },
                durationMs = totalDurationMs,
                isPlaying = isPlaying,
                isFavorite = isFav,
                canFavorite = canFavorite,
                loopMode = loopMode.label,
                qualityLabel = AudioQualityTier.getBadge(selectedTier),
                canChangeQuality = canChangeQuality,
                onFavoriteClick = {
                    activeSong?.let { PlaybackManager.toggleSongFavorite(it) }
                },
                onPrevClick = { PlaybackManager.playPrevious() },
                onPlayPauseClick = { PlaybackManager.togglePlayPause() },
                onNextClick = { PlaybackManager.playNext() },
                onLoopClick = { PlaybackManager.cycleLoopMode() },
                onQualityClick = {
                    if (canChangeQuality) {
                        showQualityDialog = true
                    }
                },
                onFullscreenClick = { hasUnmutedToPlayer = false },
                onSeekBy = { deltaMs ->
                    val currentPos = PlaybackManager.currentPositionMs.value
                    val targetMs = (currentPos + deltaMs).coerceIn(0L, totalDurationMs)
                    PlaybackManager.seekTo(targetMs)
                },
                queueCount = playlist.size,
                onQueueClick = { showQueueSidebar = true },
                onDownPress = {},
            )
        }

        // 音质选择弹窗
        if (showQualityDialog) {
            AudioQualityDialog(
                selectedTier = selectedTier,
                onSelectTier = {
                    PlaybackManager.switchTier(it)
                    showQualityDialog = false
                },
                onDismiss = { showQualityDialog = false },
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
    }
}

/**
 * 正在识别中的封面占位动效卡片（背景样式与播放界面按钮同构）
 */
@Composable
private fun AcrListeningVisualCard(
    coverSize: androidx.compose.ui.unit.Dp,
    audioEnergy: Float,
    recordedSeconds: Float,
    containerBg: Color,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "listening_wave")
    val waveScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.22f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "wave_scale",
    )

    val energyScale = 1.0f + audioEnergy.coerceIn(0f, 1f) * 0.35f

    Box(
        modifier =
            Modifier
                .size(coverSize)
                .clip(MelodistShapes.ButtonCorner)
                .background(containerBg)
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), MelodistShapes.ButtonCorner),
        contentAlignment = Alignment.Center,
    ) {
        // 声波脉冲环
        Box(
            modifier =
                Modifier
                    .size(130.dp)
                    .scale(waveScale * energyScale)
                    .background(
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    MelodistColors.AccentGreen.copy(alpha = 0.35f),
                                    Color.Transparent,
                                ),
                        ),
                        shape = CircleShape,
                    ).border(2.dp, MelodistColors.AccentGreen.copy(alpha = 0.5f), CircleShape),
        )

        // 麦克风核心图标
        Box(
            modifier =
                Modifier
                    .size(80.dp)
                    .background(Color.White.copy(alpha = 0.08f), CircleShape)
                    .border(2.dp, MelodistColors.AccentGreen, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = MelodistColors.AccentGreen,
                modifier = Modifier.size(38.dp),
            )
        }
    }
}

/**
 * 识别失败卡片（背景样式与播放界面按钮同构）
 */
@Composable
private fun AcrFailedVisualCard(
    coverSize: androidx.compose.ui.unit.Dp,
    containerBg: Color,
) {
    Box(
        modifier =
            Modifier
                .size(coverSize)
                .clip(MelodistShapes.ButtonCorner)
                .background(containerBg)
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), MelodistShapes.ButtonCorner),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(80.dp)
                    .background(Color.White.copy(alpha = 0.05f), CircleShape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.SearchOff,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(38.dp),
            )
        }
    }
}

/**
 * 权限请求卡片（背景样式与播放界面按钮同构）
 */
@Composable
private fun AcrPermissionVisualCard(
    coverSize: androidx.compose.ui.unit.Dp,
    containerBg: Color,
) {
    Box(
        modifier =
            Modifier
                .size(coverSize)
                .clip(MelodistShapes.ButtonCorner)
                .background(containerBg)
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), MelodistShapes.ButtonCorner),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(80.dp)
                    .background(Color.White.copy(alpha = 0.05f), CircleShape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.MicOff,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(38.dp),
            )
        }
    }
}

/**
 * TV 端标准交互动作按钮（与 BottomPlayerBar 按钮样式高度统一，防折行，单行呈现）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AcrTvButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerBg: Color,
    activeTintColor: Color? = null,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val normalBorder = Color.White.copy(alpha = 0.08f)
    val normalContent = activeTintColor ?: Color.White

    Button(
        onClick = onClick,
        modifier =
            Modifier
                .height(40.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) { onClick() },
        interactionSource = interactionSource,
        shape =
            ButtonDefaults.shape(
                shape = MelodistShapes.ButtonCorner,
                focusedShape = MelodistShapes.ButtonCorner,
            ),
        colors =
            ButtonDefaults.colors(
                containerColor = containerBg,
                focusedContainerColor = Color.White,
                contentColor = normalContent,
                focusedContentColor = Color.Black,
            ),
        border =
            ButtonDefaults.border(
                border =
                    Border(
                        border = BorderStroke(1.dp, normalBorder),
                        shape = MelodistShapes.ButtonCorner,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                        shape = MelodistShapes.ButtonCorner,
                    ),
            ),
        scale =
            ButtonDefaults.scale(
                focusedScale = 1.06f,
            ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = text,
                fontSize = 13.sp,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun AcrHeaderBackButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier =
            Modifier
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).focusable(interactionSource = interactionSource)
                .border(
                    border =
                        BorderStroke(
                            width = if (isFocused) 2.dp else 1.dp,
                            color = if (isFocused) MelodistColors.FocusTeal else Color.White.copy(alpha = 0.08f),
                        ),
                    shape = MelodistShapes.PillCorner,
                ).background(
                    color = if (isFocused) Color.White else Color.Black.copy(alpha = 0.3f),
                    shape = MelodistShapes.PillCorner,
                ).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = if (isFocused) Color.Black else Color.White,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "返回",
            fontSize = 14.sp,
            color = if (isFocused) Color.Black else Color.White,
        )
    }
}
