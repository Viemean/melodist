package org.melodist.mobile.ui.player

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch
import org.melodist.core.connect.client.MobileConnectionState
import org.melodist.mobile.connect.MobileConnectManager
import org.melodist.mobile.ui.components.AudioQualityBottomSheet
import org.melodist.mobile.ui.components.FullScreenCoverViewer
import org.melodist.mobile.ui.components.SongActionSheet
import org.melodist.mobile.ui.lyrics.MobileLyricsView
import org.melodist.mobile.ui.player.components.PlayerControlBar
import org.melodist.mobile.ui.player.components.PlayerCoverCarousel
import org.melodist.mobile.ui.player.components.PlayerMonetCacheManager
import org.melodist.mobile.ui.player.components.PlayerMonetColors
import org.melodist.mobile.ui.player.components.PlayerProgressSlider
import org.melodist.mobile.ui.player.components.PlayerSongInfoSection
import org.melodist.mobile.ui.theme.isAppInAmoledDark
import org.melodist.mobile.ui.theme.isAppInDarkTheme
import org.melodist.model.AudioQualityTier
import org.melodist.model.LyricLine
import org.melodist.model.Song
import org.melodist.playback.PlaybackLoopMode
import org.melodist.playback.PlaybackManager
import kotlin.math.abs
import kotlin.math.roundToInt

enum class PlayerDisplayMode {
    Cover,
    Lyrics,
}

@Composable
fun FullPlayerSheet(
    song: Song?,
    isPlaying: Boolean,
    loopMode: PlaybackLoopMode,
    lyrics: List<LyricLine>,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    onTogglePlayPause: () -> Unit = { PlaybackManager.togglePlayPause() },
    onPlayNext: () -> Unit = { PlaybackManager.playNext() },
    onPlayPrevious: () -> Unit = { PlaybackManager.playPrevious() },
    onSeekTo: (Long) -> Unit = { PlaybackManager.seekTo(it) },
    onToggleLoopMode: () -> Unit = { PlaybackManager.cycleLoopMode() },
    onDragStart: (() -> Unit)? = null,
    onDragDown: ((Float) -> Unit)? = null,
    onDragDownEnd: ((Float) -> Unit)? = null,
    onDragDownCancel: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val durationMs by PlaybackManager.durationMs.collectAsState()
    val playlist by PlaybackManager.playlist.collectAsState()
    val currentIndex by PlaybackManager.currentIndex.collectAsState()
    val isRadioMode by PlaybackManager.isRadioMode.collectAsState()
    val isMuted by PlaybackManager.isMuted.collectAsState()
    val connectState by MobileConnectManager.connectionState.collectAsState()
    var displayMode by remember { mutableStateOf(PlayerDisplayMode.Cover) }
    var showTvMenu by remember { mutableStateOf(false) }
    var showDislikeConfirmDialog by remember { mutableStateOf(false) }

    val isFavSupported = remember(song) { PlaybackManager.isSongFavoriteSupported(song) }
    val isFavorite =
        PlaybackManager.favoriteSongMids
            .collectAsState()
            .value
            .contains(song?.songMid)

    val remotePrevSong by PlaybackManager.remotePrevSong.collectAsState()
    val remoteNextSong by PlaybackManager.remoteNextSong.collectAsState()
    val isRemoteActive by PlaybackManager.isRemoteActive.collectAsState()

    val prevSong =
        remember(playlist, currentIndex, loopMode, song?.songMid, isRemoteActive, remotePrevSong) {
            PlaybackManager.getPreviousSong()
        }
    val nextSong =
        remember(playlist, currentIndex, loopMode, song?.songMid, isRemoteActive, remoteNextSong) {
            PlaybackManager.getNextSong()
        }

    val cachedColors =
        remember(song?.songMid) {
            song?.songMid?.let { PlayerMonetCacheManager.get(it) }
        }
    val primaryColor = MaterialTheme.colorScheme.primary
    val defaultMonetColors =
        remember(primaryColor) {
            PlayerMonetColors(
                accentColor = primaryColor,
                highlightColor = primaryColor,
            )
        }
    var monetColors by remember(song?.songMid) {
        mutableStateOf<PlayerMonetColors>(cachedColors ?: defaultMonetColors)
    }

    LaunchedEffect(song?.songMid, song?.coverUrl) {
        if (song == null) {
            monetColors = defaultMonetColors
            return@LaunchedEffect
        }
        val cached = PlayerMonetCacheManager.get(song.songMid)
        if (cached != null) {
            monetColors = cached
            return@LaunchedEffect
        }
        val extracted = PlayerMonetCacheManager.extractAndCache(context, song)
        if (extracted != null) {
            monetColors = extracted
        }
    }

    val animatedAccentColor by animateColorAsState(
        targetValue = monetColors.accentColor,
        animationSpec = tween(650),
        label = "monet_accent_color",
    )

    val isDark = isAppInDarkTheme()
    val isAmoled = isAppInAmoledDark()
    val view = LocalView.current
    val window = (context as? Activity)?.window
    DisposableEffect(window, view, isDark) {
        if (window != null && !view.isInEditMode) {
            val controller = WindowInsetsControllerCompat(window, view)
            val originalStatus = controller.isAppearanceLightStatusBars
            val originalNav = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
            onDispose {
                controller.isAppearanceLightStatusBars = originalStatus
                controller.isAppearanceLightNavigationBars = originalNav
            }
        } else {
            onDispose {}
        }
    }

    val targetBgColor = monetColors.getBackgroundColor(isDark = isDark, isAmoled = isAmoled)
    val animatedBgColor by animateColorAsState(
        targetValue = targetBgColor,
        animationSpec = tween(650),
        label = "monet_bg_color",
    )
    val solidBgColor = animatedBgColor
    val contentPrimary = if (isDark) MaterialTheme.colorScheme.onSurface else Color(0xFF1C1B1F)
    val contentSecondary = if (isDark) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF49454F)
    val contentTertiary = if (isDark) MaterialTheme.colorScheme.outline else Color(0xFF79747E)
    val controlContainerColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)

    val currentTier by PlaybackManager.currentTier.collectAsState()
    val isCurrentTrackFromCache by PlaybackManager.isCurrentTrackFromCache.collectAsState()
    val availableTiers by PlaybackManager.availableTiers.collectAsState()
    val currentTrackSpec by PlaybackManager.currentTrackSpec.collectAsState()
    val probedQualityOptions by PlaybackManager.probedQualityOptions.collectAsState()
    val isProbingQuality by PlaybackManager.isProbingQuality.collectAsState()
    var showQualitySheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var actionTargetSong by remember { mutableStateOf<Song?>(null) }
    var coverTargetSong by remember { mutableStateOf<Song?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val internalSheetOffsetY = remember { Animatable(0f) }
    val verticalVelocityTracker = remember { VelocityTracker() }
    var accumulatedDragY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .offset {
                    if (onDragDown == null) {
                        IntOffset(0, internalSheetOffsetY.value.roundToInt().coerceAtLeast(0))
                    } else {
                        IntOffset.Zero
                    }
                }.background(solidBgColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ).pointerInput(displayMode) {
                    if (displayMode == PlayerDisplayMode.Cover) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                accumulatedDragY = 0f
                                verticalVelocityTracker.resetTracking()
                                onDragStart?.invoke()
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                accumulatedDragY += dragAmount
                                verticalVelocityTracker.addPosition(change.uptimeMillis, Offset(0f, accumulatedDragY))
                                if (onDragDown != null) {
                                    onDragDown(dragAmount)
                                } else {
                                    coroutineScope.launch {
                                        internalSheetOffsetY.snapTo((internalSheetOffsetY.value + dragAmount).coerceAtLeast(0f))
                                    }
                                }
                            },
                            onDragEnd = {
                                val velocityY = verticalVelocityTracker.calculateVelocity().y
                                if (onDragDownEnd != null) {
                                    onDragDownEnd(velocityY)
                                } else {
                                    val currentOffset = internalSheetOffsetY.value
                                    val dismissThreshold = (size.height * 0.32f).coerceAtLeast(with(density) { 72.dp.toPx() })
                                    val shouldDismiss =
                                        when {
                                            velocityY > 500f -> true
                                            velocityY < -400f -> false
                                            else -> currentOffset > dismissThreshold
                                        }
                                    if (shouldDismiss) {
                                        coroutineScope.launch {
                                            internalSheetOffsetY.animateTo(size.height.toFloat(), tween(200, easing = FastOutSlowInEasing))
                                            onCollapse()
                                            internalSheetOffsetY.snapTo(0f)
                                        }
                                    } else {
                                        coroutineScope.launch {
                                            internalSheetOffsetY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                                        }
                                    }
                                }
                            },
                            onDragCancel = {
                                if (onDragDownCancel != null) {
                                    onDragDownCancel()
                                } else {
                                    coroutineScope.launch {
                                        internalSheetOffsetY.animateTo(0f)
                                    }
                                }
                            },
                        )
                    }
                },
    ) {
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .displayCutoutPadding()
                    .systemBarsPadding()
                    .padding(
                        start = if (isLandscape) 20.dp else 16.dp,
                        end = if (isLandscape) 20.dp else 16.dp,
                        top = if (isLandscape) 6.dp else 4.dp,
                        bottom = if (isLandscape) 8.dp else 4.dp,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier =
                    (if (isLandscape) Modifier.weight(0.38f) else Modifier.fillMaxWidth())
                        .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (isLandscape) Arrangement.SpaceBetween else Arrangement.Top,
            ) {
                // 1. 顶部操作条（无缝形态切换）
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(if (isLandscape) 26.dp else 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLandscape) {
                        IconButton(
                            onClick = onCollapse,
                            modifier = Modifier.size(26.dp).align(Alignment.CenterStart),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "收起",
                                tint = contentSecondary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    } else {
                        Box(
                            modifier =
                                Modifier
                                    .size(width = 38.dp, height = 4.5.dp)
                                    .background(contentTertiary.copy(alpha = 0.35f), CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onCollapse,
                                    ),
                        )
                    }

                    val curConnectState = connectState
                    if (curConnectState is MobileConnectionState.Paired) {
                        val pairedDevice = curConnectState.targetDevice
                        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                            IconButton(
                                onClick = { showTvMenu = true },
                                modifier = Modifier.size(if (isLandscape) 26.dp else 32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Computer,
                                    contentDescription = "已连接 ${pairedDevice.name}",
                                    tint = animatedAccentColor,
                                    modifier = Modifier.size(if (isLandscape) 18.dp else 20.dp),
                                )
                            }

                            DropdownMenu(
                                expanded = showTvMenu,
                                onDismissRequest = { showTvMenu = false },
                            ) {
                                Text(
                                    text = pairedDevice.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                DropdownMenuItem(
                                    text = { Text("立即播放") },
                                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                                    enabled = song != null,
                                    onClick = {
                                        showTvMenu = false
                                        if (song != null) {
                                            MobileConnectManager.playOnTv(song)
                                            android.widget.Toast
                                                .makeText(context, "已发送至 TV 播放", android.widget.Toast.LENGTH_SHORT)
                                                .show()
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("稍后播放") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null) },
                                    enabled = song != null,
                                    onClick = {
                                        showTvMenu = false
                                        if (song != null) {
                                            MobileConnectManager.enqueueNextOnTv(song)
                                            android.widget.Toast
                                                .makeText(context, "已插播至 TV 队列", android.widget.Toast.LENGTH_SHORT)
                                                .show()
                                        }
                                    },
                                )
                                val isTakeover =
                                    MobileConnectManager.remoteControlMode.collectAsState().value == org.melodist.core.connect.model.RemoteControlMode.TAKEOVER
                                DropdownMenuItem(
                                    text = { Text(if (isTakeover) "接管模式" else "进度接力") },
                                    leadingIcon = { Icon(Icons.Rounded.CastConnected, contentDescription = null) },
                                    onClick = {
                                        showTvMenu = false
                                        if (isTakeover) {
                                            android.widget.Toast
                                                .makeText(
                                                    context,
                                                    "当前处于全面接管模式，播放直通 TV",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                        } else {
                                            MobileConnectManager.relayCurrentPlaybackToTv()
                                            android.widget.Toast
                                                .makeText(
                                                    context,
                                                    "已接力至 TV 播放",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("断开连接", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.LinkOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        showTvMenu = false
                                        MobileConnectManager.disconnect()
                                        android.widget.Toast
                                            .makeText(context, "已断开与 TV 的连接", android.widget.Toast.LENGTH_SHORT)
                                            .show()
                                    },
                                )
                            }
                        }
                    }
                }

                // 2. 中间主区域（横屏固定精致 130dp 封面；竖屏全高流式封面/歌词）
                if (isLandscape) {
                    Box(
                        modifier = Modifier.size(130.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        PlayerCoverCarousel(
                            currentSong = song,
                            prevSong = prevSong,
                            nextSong = nextSong,
                            onPlayNext = onPlayNext,
                            onPlayPrevious = onPlayPrevious,
                            onClick = { coverTargetSong = song },
                            onLongClick = { actionTargetSong = song },
                            shadowTint = monetColors.shadowTint,
                            isDark = isDark,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedContent(
                            targetState = displayMode,
                            transitionSpec = {
                                if (targetState == PlayerDisplayMode.Lyrics) {
                                    (
                                        fadeIn(animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                                            scaleIn(initialScale = 0.94f, animationSpec = tween(280, easing = FastOutSlowInEasing))
                                    ).togetherWith(
                                        fadeOut(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                                            scaleOut(targetScale = 1.04f, animationSpec = tween(220, easing = FastOutSlowInEasing)),
                                    )
                                } else {
                                    (
                                        fadeIn(animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                                            scaleIn(initialScale = 1.04f, animationSpec = tween(280, easing = FastOutSlowInEasing))
                                    ).togetherWith(
                                        fadeOut(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                                            scaleOut(targetScale = 0.94f, animationSpec = tween(220, easing = FastOutSlowInEasing)),
                                    )
                                }
                            },
                            label = "PlayerCoverLyricsTransition",
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { currentMode ->
                            if (currentMode == PlayerDisplayMode.Cover) {
                                PlayerCoverCarousel(
                                    currentSong = song,
                                    prevSong = prevSong,
                                    nextSong = nextSong,
                                    onPlayNext = onPlayNext,
                                    onPlayPrevious = onPlayPrevious,
                                    onClick = { displayMode = PlayerDisplayMode.Lyrics },
                                    onLongClick = { actionTargetSong = song },
                                    shadowTint = monetColors.shadowTint,
                                    isDark = isDark,
                                )
                            } else {
                                var lyricDragX by remember { mutableFloatStateOf(0f) }
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .pointerInput(Unit) {
                                                detectHorizontalDragGestures(
                                                    onDragStart = { lyricDragX = 0f },
                                                    onHorizontalDrag = { _, dragAmount -> lyricDragX += dragAmount },
                                                    onDragEnd = {
                                                        if (abs(lyricDragX) > 70f) {
                                                            displayMode = PlayerDisplayMode.Cover
                                                        }
                                                        lyricDragX = 0f
                                                    },
                                                    onDragCancel = { lyricDragX = 0f },
                                                )
                                            },
                                ) {
                                    val lyricPositionMs by PlaybackManager.currentPositionMs.collectAsState()
                                    MobileLyricsView(
                                        lyrics = lyrics,
                                        currentPositionMs = lyricPositionMs,
                                        onSeekTo = onSeekTo,
                                        highlightColor = animatedAccentColor,
                                        textColor = contentPrimary.copy(alpha = 0.72f),
                                        transColor = contentPrimary.copy(alpha = 0.55f),
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. 下方歌曲信息 + 进度条 + 播控栏（全部支持 isLandscape 平滑无缝形变）
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .pointerInput(displayMode) {
                                if (displayMode == PlayerDisplayMode.Lyrics && !isLandscape) {
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            accumulatedDragY = 0f
                                            verticalVelocityTracker.resetTracking()
                                            onDragStart?.invoke()
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            change.consume()
                                            accumulatedDragY += dragAmount
                                            verticalVelocityTracker.addPosition(change.uptimeMillis, Offset(0f, accumulatedDragY))
                                            if (onDragDown != null) {
                                                onDragDown(dragAmount)
                                            } else {
                                                coroutineScope.launch {
                                                    internalSheetOffsetY.snapTo((internalSheetOffsetY.value + dragAmount).coerceAtLeast(0f))
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            val velocityY = verticalVelocityTracker.calculateVelocity().y
                                            if (onDragDownEnd != null) {
                                                onDragDownEnd(velocityY)
                                            } else {
                                                val currentOffset = internalSheetOffsetY.value
                                                val dismissThreshold = (size.height * 0.32f).coerceAtLeast(with(density) { 72.dp.toPx() })
                                                val shouldDismiss =
                                                    when {
                                                        velocityY > 500f -> true
                                                        velocityY < -400f -> false
                                                        else -> currentOffset > dismissThreshold
                                                    }
                                                if (shouldDismiss) {
                                                    coroutineScope.launch {
                                                        internalSheetOffsetY.animateTo(size.height.toFloat(), tween(200, easing = FastOutSlowInEasing))
                                                        onCollapse()
                                                        internalSheetOffsetY.snapTo(0f)
                                                    }
                                                } else {
                                                    coroutineScope.launch {
                                                        internalSheetOffsetY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                                                    }
                                                }
                                            }
                                        },
                                        onDragCancel = {
                                            if (onDragDownCancel != null) {
                                                onDragDownCancel()
                                            } else {
                                                coroutineScope.launch {
                                                    internalSheetOffsetY.animateTo(0f)
                                                }
                                            }
                                        },
                                    )
                                }
                            },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!isLandscape) {
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // 歌曲信息
                    PlayerSongInfoSection(
                        song = song,
                        isFavorite = isFavorite,
                        isFavSupported = isFavSupported,
                        currentTier = currentTier,
                        isFromCache = isCurrentTrackFromCache,
                        animatedAccentColor = animatedAccentColor,
                        controlContainerColor = controlContainerColor,
                        contentPrimary = contentPrimary,
                        contentSecondary = contentSecondary,
                        contentTertiary = contentTertiary,
                        onSongInfoClick = {
                            if (displayMode == PlayerDisplayMode.Lyrics && !isLandscape) {
                                displayMode = PlayerDisplayMode.Cover
                            }
                        },
                        onToggleFavorite = { PlaybackManager.toggleCurrentSongFavorite() },
                        onOpenQualitySheet = {
                            if (!PlaybackManager.isLocalOrWebDavSong(song)) {
                                PlaybackManager.ensureQualityProbed()
                                showQualitySheet = true
                            }
                        },
                        isLandscape = isLandscape,
                    )

                    Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 14.dp))

                    // 进度滑块
                    PlayerProgressSlider(
                        durationMs = durationMs,
                        accentColor = animatedAccentColor,
                        onSeekTo = onSeekTo,
                        textColor = contentSecondary,
                    )

                    Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 12.dp))

                    // 控制按钮栏（平滑尺寸形变）
                    PlayerControlBar(
                        isPlaying = isPlaying,
                        loopMode = loopMode,
                        isRadioMode = isRadioMode,
                        animatedAccentColor = animatedAccentColor,
                        contentPrimary = contentPrimary,
                        isMuted = isMuted,
                        onTogglePlayPause = {
                            if (isMuted) {
                                PlaybackManager.setMuted(false)
                                val preferred = PlaybackManager.preferredTier.value
                                if (PlaybackManager.currentTier.value != preferred) {
                                    PlaybackManager.switchTier(preferred)
                                } else if (!isPlaying) {
                                    onTogglePlayPause()
                                }
                            } else {
                                onTogglePlayPause()
                            }
                        },
                        onToggleLoopMode = onToggleLoopMode,
                        onOpenQueue = { showQueueSheet = true },
                        onDislikeClick = { showDislikeConfirmDialog = true },
                        isLandscape = isLandscape,
                    )
                }
            }

            if (isLandscape) {
                // 右侧歌词展开区（横屏全高度沉浸逐字歌词流）
                Box(
                    modifier = Modifier.weight(0.62f).fillMaxHeight().padding(start = 24.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    val lyricPositionMs by PlaybackManager.currentPositionMs.collectAsState()
                    MobileLyricsView(
                        lyrics = lyrics,
                        currentPositionMs = lyricPositionMs,
                        onSeekTo = onSeekTo,
                        highlightColor = animatedAccentColor,
                        textColor = contentPrimary.copy(alpha = 0.72f),
                        transColor = contentPrimary.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // 不喜欢歌曲二次确认弹窗
        if (showDislikeConfirmDialog && song != null) {
            AlertDialog(
                onDismissRequest = { showDislikeConfirmDialog = false },
                title = { Text("不喜欢这首歌曲？") },
                text = { Text("将跳过《${song.name}》并自动为你播放下一首。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDislikeConfirmDialog = false
                            val curIdx = PlaybackManager.currentIndex.value
                            if (curIdx >= 0) {
                                PlaybackManager.removeFromPlaylist(curIdx)
                            } else {
                                PlaybackManager.playNext()
                            }
                        },
                    ) {
                        Text("确定", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDislikeConfirmDialog = false }) {
                        Text("取消")
                    }
                },
            )
        }

        // 音质选择弹窗
        if (showQualitySheet) {
            AudioQualityBottomSheet(
                currentTier = currentTier,
                availableTiers = availableTiers,
                currentTrackSpec = currentTrackSpec,
                probedQualityOptions = probedQualityOptions,
                songDurationSec = song?.durationSeconds ?: 0,
                isProbing = isProbingQuality,
                onSelectTier = { tier -> PlaybackManager.switchTier(tier) },
                onDismissRequest = { showQualitySheet = false },
            )
        }

        // 播放队列弹窗
        if (showQueueSheet) {
            PlayerQueueBottomSheet(
                onDismissRequest = { showQueueSheet = false },
                onNavigate = {
                    showQueueSheet = false
                    onCollapse()
                },
            )
        }

        // 歌曲更多操作弹窗
        actionTargetSong?.let { targetSong ->
            SongActionSheet(
                song = targetSong,
                onDismissRequest = { actionTargetSong = null },
                showNextPlay = false,
                showFavorite = false,
                isFromPlayer = true,
                onViewCover = { coverTargetSong = targetSong },
                onNavigate = {
                    actionTargetSong = null
                    onCollapse()
                },
            )
        }

        // 封面全屏大图预览
        coverTargetSong?.let { targetSong ->
            FullScreenCoverViewer(
                song = targetSong,
                onDismissRequest = { coverTargetSong = null },
            )
        }
    }
}


