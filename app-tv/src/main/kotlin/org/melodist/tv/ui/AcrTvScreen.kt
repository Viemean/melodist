package org.melodist.tv.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.tv.material3.*
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import org.melodist.tv.acr.AcrUiState
import org.melodist.tv.acr.AcrViewModel
import org.melodist.tv.ui.components.MelodistAsyncImage
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.rememberTvWindowMetrics
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AcrTvScreen(
    surfaceColor: Color,
    onNavigateToPlayer: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = remember { AcrViewModel() }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.reset()
        }
    }
    val context = LocalContext.current
    val metrics = rememberTvWindowMetrics()
    val uiState by viewModel.uiState.collectAsState()
    val audioEnergy by viewModel.audioEnergy.collectAsState()

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

    BackHandler {
        viewModel.reset()
        onBack()
    }

    val primaryActionRequester = remember { FocusRequester() }

    LaunchedEffect(uiState) {
        try {
            primaryActionRequester.requestFocus()
        } catch (_: Exception) {
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(surfaceColor)
                .padding(
                    horizontal = metrics.horizontalSafePadding,
                    vertical = metrics.verticalSafePadding,
                ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 顶部导航栏 / 标题区域
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AcrHeaderBackButton(
                    onClick = {
                        viewModel.reset()
                        onBack()
                    },
                )

                Text(
                    text = "听歌识曲",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MelodistColors.TextPrimary,
                )
            }

            // 中部主体展示区域
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                when (val state = uiState) {
                    is AcrUiState.Idle -> {
                        AcrIdleContent(
                            primaryActionRequester = primaryActionRequester,
                            onStart = { checkAndStartRecognition() },
                        )
                    }
                    is AcrUiState.PermissionRequired -> {
                        AcrPermissionContent(
                            primaryActionRequester = primaryActionRequester,
                            onRequestPermission = {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                        )
                    }
                    is AcrUiState.Listening -> {
                        AcrListeningContent(
                            recordedSeconds = state.recordedSeconds,
                            audioEnergy = audioEnergy,
                            primaryActionRequester = primaryActionRequester,
                            onCancel = { viewModel.reset() },
                        )
                    }
                    is AcrUiState.Recognizing -> {
                        AcrRecognizingContent()
                    }
                    is AcrUiState.Success -> {
                        AcrSuccessContent(
                            song = state.song,
                            offsetSeconds = state.offsetSeconds,
                            primaryActionRequester = primaryActionRequester,
                            onPlayNow = {
                                val elapsedRealtimeMs =
                                    if (state.anchorRealtimeMs > 0L) {
                                        android.os.SystemClock.elapsedRealtime() - state.anchorRealtimeMs
                                    } else {
                                        0L
                                    }
                                val prepLatencyMs = 850L
                                val seekMs =
                                    ((state.offsetSeconds * 1000).toLong() + elapsedRealtimeMs + prepLatencyMs)
                                        .coerceAtLeast(0L)
                                PlaybackManager.setPlaylist(
                                    songs = listOf(state.song),
                                    startIndex = 0,
                                    initialSeekToMs = seekMs,
                                )
                                onNavigateToPlayer()
                            },
                            onAddToQueue = {
                                PlaybackManager.appendPlaylist(listOf(state.song))
                            },
                            onRestart = {
                                checkAndStartRecognition()
                            },
                        )
                    }
                    is AcrUiState.Failed -> {
                        AcrFailedContent(
                            message = state.message,
                            primaryActionRequester = primaryActionRequester,
                            onRetry = { checkAndStartRecognition() },
                        )
                    }
                }
            }

            // 底部操作说明留白
            Spacer(modifier = Modifier.height(16.dp))
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
                            color = if (isFocused) MelodistColors.FocusTeal else MelodistColors.ContainerDarkSecondary,
                        ),
                    shape = MelodistShapes.PillCorner,
                ).background(
                    color = if (isFocused) Color.White else MelodistColors.ContainerDark.copy(alpha = 0.6f),
                    shape = MelodistShapes.PillCorner,
                ).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = if (isFocused) Color.Black else MelodistColors.TextPrimary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "返回",
            fontSize = 14.sp,
            color = if (isFocused) Color.Black else MelodistColors.TextPrimary,
        )
    }
}

@Composable
private fun AcrIdleContent(
    primaryActionRequester: FocusRequester,
    onStart: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(140.dp)
                    .background(
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    MelodistColors.AccentGreen.copy(alpha = 0.25f),
                                    Color.Transparent,
                                ),
                        ),
                        shape = CircleShape,
                    ).border(2.dp, MelodistColors.AccentGreen.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = MelodistColors.AccentGreen,
                modifier = Modifier.size(64.dp),
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "准备就绪",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MelodistColors.TextPrimary,
            )
            Text(
                text = "对准遥控器或电视麦克风，点击开始识别环境播放的歌曲",
                fontSize = 15.sp,
                color = MelodistColors.TextSecondary,
            )
        }

        AcrActionButton(
            text = "开始识别",
            icon = Icons.Default.PlayArrow,
            isPrimary = true,
            focusRequester = primaryActionRequester,
            onClick = onStart,
        )
    }
}

@Composable
private fun AcrPermissionContent(
    primaryActionRequester: FocusRequester,
    onRequestPermission: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(120.dp)
                    .background(Color.White.copy(alpha = 0.05f), CircleShape)
                    .border(2.dp, MelodistColors.TextSecondary.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.MicOff,
                contentDescription = null,
                tint = MelodistColors.TextSecondary,
                modifier = Modifier.size(56.dp),
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "需要麦克风录音权限",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MelodistColors.TextPrimary,
            )
            Text(
                text = "听歌识曲需要录音权限以采集声学特征，请在弹出的系统对话框中选择允许",
                fontSize = 15.sp,
                color = MelodistColors.TextSecondary,
            )
        }

        AcrActionButton(
            text = "授予麦克风权限",
            icon = Icons.Default.Security,
            isPrimary = true,
            focusRequester = primaryActionRequester,
            onClick = onRequestPermission,
        )
    }
}

@Composable
private fun AcrListeningContent(
    recordedSeconds: Float,
    audioEnergy: Float,
    primaryActionRequester: FocusRequester,
    onCancel: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulseScale",
    )

    val energyScale = 1.0f + audioEnergy * 0.4f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(
            modifier = Modifier.size(200.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 外圈能量光晕
            Box(
                modifier =
                    Modifier
                        .size(180.dp)
                        .scale(pulseScale * energyScale)
                        .background(
                            MelodistColors.AccentGreen.copy(alpha = 0.12f + audioEnergy * 0.25f),
                            CircleShape,
                        ),
            )

            // 中圈脉冲环
            Box(
                modifier =
                    Modifier
                        .size(140.dp)
                        .scale(energyScale)
                        .border(
                            BorderStroke(2.dp, MelodistColors.AccentGreen.copy(alpha = 0.6f + audioEnergy * 0.4f)),
                            CircleShape,
                        ),
            )

            // 内核麦克风按钮
            Box(
                modifier =
                    Modifier
                        .size(90.dp)
                        .background(MelodistColors.AccentGreen, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(44.dp),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "正在聆听环境声音...",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MelodistColors.TextPrimary,
            )
            Text(
                text = String.format(Locale.US, "已录音 %.1fs · 正在比对特征", recordedSeconds),
                fontSize = 15.sp,
                color = MelodistColors.AccentGreen,
            )
        }

        AcrActionButton(
            text = "取消识别",
            icon = Icons.Default.Close,
            isPrimary = false,
            focusRequester = primaryActionRequester,
            onClick = onCancel,
        )
    }
}

@Composable
private fun AcrRecognizingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(100.dp)
                    .background(MelodistColors.ContainerDarkSecondary, CircleShape)
                    .border(2.dp, MelodistColors.AccentGreen, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = null,
                tint = MelodistColors.AccentGreen,
                modifier = Modifier.size(48.dp),
            )
        }

        Text(
            text = "特征提取完成，正在检索云端曲库...",
            fontSize = 20.sp,
            color = MelodistColors.TextPrimary,
        )
    }
}

@Composable
private fun AcrSuccessContent(
    song: Song,
    offsetSeconds: Double,
    primaryActionRequester: FocusRequester,
    onPlayNow: () -> Unit,
    onAddToQueue: () -> Unit,
    onRestart: () -> Unit,
) {
    var addedToQueueToast by remember { mutableStateOf(false) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 歌曲封面
        Box(
            modifier =
                Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(2.dp, MelodistColors.AccentGreen.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
        ) {
            MelodistAsyncImage(
                coverUrl = song.coverUrl,
                albumMid = song.albumMid,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Spacer(modifier = Modifier.width(36.dp))

        // 识别结果详情与操作按钮
        Column(
            modifier = Modifier.widthIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .background(MelodistColors.AccentGreen.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "识别命中",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MelodistColors.AccentGreen,
                    )
                }

                if (offsetSeconds > 0) {
                    Text(
                        text = String.format(Locale.US, "匹配段落: %02d:%02d", (offsetSeconds / 60).toInt(), (offsetSeconds % 60).toInt()),
                        fontSize = 13.sp,
                        color = MelodistColors.TextSecondary,
                    )
                }
            }

            Text(
                text = song.name,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MelodistColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = "${song.singer} · ${song.album}",
                fontSize = 18.sp,
                color = MelodistColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 按钮操作行
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AcrActionButton(
                    text = "立即播放",
                    icon = Icons.Default.PlayArrow,
                    isPrimary = true,
                    focusRequester = primaryActionRequester,
                    onClick = onPlayNow,
                )

                AcrActionButton(
                    text = if (addedToQueueToast) "已加入队列" else "加入队列",
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    isPrimary = false,
                    onClick = {
                        onAddToQueue()
                        addedToQueueToast = true
                    },
                )

                AcrActionButton(
                    text = "重新识别",
                    icon = Icons.Default.Refresh,
                    isPrimary = false,
                    onClick = onRestart,
                )
            }
        }
    }
}

@Composable
private fun AcrFailedContent(
    message: String,
    primaryActionRequester: FocusRequester,
    onRetry: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(100.dp)
                    .background(Color.White.copy(alpha = 0.05f), CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.SearchOff,
                contentDescription = null,
                tint = MelodistColors.TextSecondary,
                modifier = Modifier.size(48.dp),
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "未能识别出曲目",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MelodistColors.TextPrimary,
            )
            Text(
                text = message,
                fontSize = 15.sp,
                color = MelodistColors.TextSecondary,
            )
        }

        AcrActionButton(
            text = "重新识别",
            icon = Icons.Default.Refresh,
            isPrimary = true,
            focusRequester = primaryActionRequester,
            onClick = onRetry,
        )
    }
}

@Composable
private fun AcrActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isPrimary: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val modifier =
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ).focusable(interactionSource = interactionSource)
            .border(
                border =
                    BorderStroke(
                        width = if (isFocused) 2.dp else 1.dp,
                        color =
                            if (isFocused) {
                                MelodistColors.FocusTeal
                            } else if (isPrimary) {
                                MelodistColors.AccentGreen.copy(alpha = 0.5f)
                            } else {
                                MelodistColors.ContainerDarkSecondary
                            },
                    ),
                shape = MelodistShapes.PillCorner,
            ).background(
                color =
                    if (isFocused) {
                        Color.White
                    } else if (isPrimary) {
                        MelodistColors.ContainerDarkSecondary
                    } else {
                        MelodistColors.ContainerDark.copy(alpha = 0.6f)
                    },
                shape = MelodistShapes.PillCorner,
            ).padding(horizontal = 20.dp, vertical = 10.dp)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint =
                if (isFocused) {
                    Color.Black
                } else if (isPrimary) {
                    MelodistColors.AccentGreen
                } else {
                    MelodistColors.TextPrimary
                },
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Normal,
            color =
                if (isFocused) {
                    Color.Black
                } else if (isPrimary) {
                    MelodistColors.AccentGreen
                } else {
                    MelodistColors.TextPrimary
                },
        )
    }
}
