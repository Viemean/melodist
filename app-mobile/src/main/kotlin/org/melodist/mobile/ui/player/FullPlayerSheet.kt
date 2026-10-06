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
import androidx.compose.ui.text.style.TextAlign
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

        if (isLandscape) {
            // 横屏三栏布局
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .displayCutoutPadding()
                        .systemBarsPadding()
                        .padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左栏：封面与进度控制
                Column(
                    modifier = Modifier.width(260.dp).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // 专辑封面
                    val isLocalOrWebDav =
                        song?.songMid?.startsWith("webdav_") == true ||
                            song?.songMid?.startsWith("local_") == true ||
                            !song?.localFilePath.isNullOrBlank()

                    val badgeText = AudioQualityTier.getBadge(currentTier)

                    // 专辑封面
                    Box(
                        modifier = Modifier.size(240.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        PlayerCoverCarousel(
                            currentSong = song,
                            prevSong = prevSong,
                            nextSong = nextSong,
                            onPlayNext = onPlayNext,
                            onPlayPrevious = onPlayPrevious,
                            onClick = {},
                            onLongClick = { actionTargetSong = song },
                            shadowTint = monetColors.shadowTint,
                            isDark = isDark,
                            bottomOverlay = {
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.BottomCenter)
                                            .padding(horizontal = 8.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // 音质徽标
                                    Surface(
                                        onClick = {
                                            if (!isLocalOrWebDav) {
                                                PlaybackManager.ensureQualityProbed()
                                                showQualitySheet = true
                                            }
                                        },
                                        shape = RoundedCornerShape(4.dp),
                                        color = animatedAccentColor.copy(alpha = 0.12f),
                                        border = BorderStroke(0.75.dp, animatedAccentColor.copy(alpha = 0.35f)),
                                    ) {
                                        Text(
                                            text = badgeText,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = animatedAccentColor,
                                            modifier = Modifier.padding(horizontal = 4.5.dp, vertical = 1.dp),
                                        )
                                    }

                                    // 收藏按钮
                                    if (isFavSupported) {
                                        FilledTonalIconButton(
                                            onClick = { PlaybackManager.toggleCurrentSongFavorite() },
                                            modifier = Modifier.size(28.dp),
                                            colors =
                                                IconButtonDefaults.filledTonalIconButtonColors(
                                                    containerColor = controlContainerColor,
                                                    contentColor = if (isFavorite) MaterialTheme.colorScheme.error else contentPrimary,
                                                ),
                                        ) {
                                            Icon(
                                                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                                contentDescription = "收藏",
                                                tint = if (isFavorite) MaterialTheme.colorScheme.error else contentPrimary,
                                                modifier = Modifier.size(17.dp),
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 进度条与时间
                    PlayerProgressSlider(
                        durationMs = durationMs,
                        accentColor = animatedAccentColor,
                        onSeekTo = onSeekTo,
                        textColor = contentSecondary,
                        timeOnTop = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    )
                }

                Spacer(modifier = Modifier.width(20.dp))

                // 中栏：歌曲信息与歌词
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top,
                ) {
                    // 歌曲信息
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(top = 2.dp, bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = song?.name ?: "未在播放",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = contentPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(),
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = song?.singer?.ifBlank { "未知歌手" } ?: "未知歌手",
                            style = MaterialTheme.typography.bodySmall,
                            color = contentSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }

                    // 歌词视图
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 2.dp),
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

                Spacer(modifier = Modifier.width(16.dp))

                // 右栏：播放控制
                val auxButtonShape = RoundedCornerShape(16.dp)
                val auxButtonBgColor =
                    if (isDark) {
                        Color.White.copy(alpha = 0.08f)
                    } else {
                        Color.Black.copy(alpha = 0.05f)
                    }

                val queueInteractionSource = remember { MutableInteractionSource() }
                val isQueuePressed by queueInteractionSource.collectIsPressedAsState()
                val queueScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isQueuePressed) 0.86f else 1f,
                    animationSpec =
                        androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                        ),
                    label = "queue_scale",
                )

                val playPauseInteractionSource = remember { MutableInteractionSource() }
                val isPlayPausePressed by playPauseInteractionSource.collectIsPressedAsState()
                val playPauseScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isPlayPausePressed) 0.88f else 1f,
                    animationSpec =
                        androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                        ),
                    label = "play_pause_scale",
                )

                val loopInteractionSource = remember { MutableInteractionSource() }
                val isLoopPressed by loopInteractionSource.collectIsPressedAsState()
                val loopScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isLoopPressed) 0.86f else 1f,
                    animationSpec =
                        androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                        ),
                    label = "loop_scale",
                )

                Column(
                    modifier = Modifier.width(60.dp).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    // 播放队列
                    FilledTonalIconButton(
                        onClick = { showQueueSheet = true },
                        interactionSource = queueInteractionSource,
                        modifier =
                            Modifier
                                .size(48.dp)
                                .graphicsLayer {
                                    scaleX = queueScale
                                    scaleY = queueScale
                                },
                        shape = auxButtonShape,
                        colors =
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = auxButtonBgColor,
                                contentColor = contentPrimary.copy(alpha = 0.85f),
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = "播放队列",
                            tint = contentPrimary.copy(alpha = 0.85f),
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    // 播放/暂停按键
                    val playPauseContentColor =
                        if (ColorUtils.calculateLuminance(animatedAccentColor.toArgb()) < 0.45) {
                            Color.White
                        } else {
                            Color(0xFF1C1B1F)
                        }
                    FilledIconButton(
                        onClick = {
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
                        interactionSource = playPauseInteractionSource,
                        modifier =
                            Modifier
                                .size(58.dp)
                                .graphicsLayer {
                                    scaleX = playPauseScale
                                    scaleY = playPauseScale
                                }.shadow(
                                    elevation = 4.dp,
                                    shape = RoundedCornerShape(22.dp),
                                    spotColor = animatedAccentColor.copy(alpha = if (isDark) 0.38f else 0.22f),
                                    ambientColor = animatedAccentColor.copy(alpha = 0.08f),
                                    clip = false,
                                ),
                        shape = RoundedCornerShape(22.dp),
                        colors =
                            IconButtonDefaults.filledIconButtonColors(
                                containerColor = animatedAccentColor,
                                contentColor = playPauseContentColor,
                            ),
                    ) {
                        AnimatedContent(
                            targetState = isPlaying && !isMuted,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.75f))
                                    .togetherWith(fadeOut(animationSpec = tween(140)) + scaleOut(targetScale = 0.75f))
                            },
                            label = "PlayPauseLandscapeIconTransition",
                        ) { playing ->
                            Icon(
                                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (playing) "暂停" else "播放",
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }

                    // 循环模式切换
                    val isLoopHighlighted = loopMode == org.melodist.playback.PlaybackLoopMode.SingleRepeat
                    FilledTonalIconButton(
                        onClick = onToggleLoopMode,
                        interactionSource = loopInteractionSource,
                        modifier =
                            Modifier
                                .size(48.dp)
                                .graphicsLayer {
                                    scaleX = loopScale
                                    scaleY = loopScale
                                },
                        shape = auxButtonShape,
                        colors =
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (isLoopHighlighted) animatedAccentColor.copy(alpha = 0.20f) else auxButtonBgColor,
                                contentColor = if (isLoopHighlighted) animatedAccentColor else contentPrimary.copy(alpha = 0.85f),
                            ),
                    ) {
                        AnimatedContent(
                            targetState = loopMode,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.78f))
                                    .togetherWith(fadeOut(animationSpec = tween(140)) + scaleOut(targetScale = 0.78f))
                            },
                            label = "LoopModeLandscapeIconTransition",
                        ) { mode ->
                            val loopIcon =
                                when (mode) {
                                    org.melodist.playback.PlaybackLoopMode.ListRepeat -> Icons.Rounded.Repeat
                                    org.melodist.playback.PlaybackLoopMode.SingleRepeat -> Icons.Rounded.RepeatOne
                                    org.melodist.playback.PlaybackLoopMode.Shuffle -> Icons.Rounded.Shuffle
                                }
                            Icon(
                                imageVector = loopIcon,
                                contentDescription = "播放模式",
                                tint = if (isLoopHighlighted) animatedAccentColor else contentPrimary.copy(alpha = 0.85f),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        } else {
            // 竖屏布局
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .displayCutoutPadding()
                        .systemBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                // 顶部指示条
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
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

                    val curConnectState = connectState
                    if (curConnectState is MobileConnectionState.Paired) {
                        val pairedDevice = curConnectState.targetDevice
                        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                            IconButton(
                                onClick = { showTvMenu = true },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Computer,
                                    contentDescription = "已连接 ${pairedDevice.name}",
                                    tint = animatedAccentColor,
                                    modifier = Modifier.size(20.dp),
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
                                val isTakeoverPortrait =
                                    MobileConnectManager.remoteControlMode.collectAsState().value == org.melodist.core.connect.model.RemoteControlMode.TAKEOVER
                                DropdownMenuItem(
                                    text = { Text(if (isTakeoverPortrait) "接管模式" else "进度接力") },
                                    leadingIcon = { Icon(Icons.Rounded.CastConnected, contentDescription = null) },
                                    onClick = {
                                        showTvMenu = false
                                        if (isTakeoverPortrait) {
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

                // 封面与歌词区域
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

                // 播放控制区域
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .pointerInput(displayMode) {
                                if (displayMode == PlayerDisplayMode.Lyrics) {
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
                    Spacer(modifier = Modifier.height(16.dp))

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
                            if (displayMode == PlayerDisplayMode.Lyrics) {
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
                        isLandscape = false,
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // 进度滑块
                    PlayerProgressSlider(
                        durationMs = durationMs,
                        accentColor = animatedAccentColor,
                        onSeekTo = onSeekTo,
                        textColor = contentSecondary,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 控制按钮栏
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
                        isLandscape = false,
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


