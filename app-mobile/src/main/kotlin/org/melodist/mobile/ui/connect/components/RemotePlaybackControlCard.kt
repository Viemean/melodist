package org.melodist.mobile.ui.connect.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.TvOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.melodist.core.connect.client.MobileConnectionState
import org.melodist.core.connect.model.PlayerStateEvent
import org.melodist.mobile.connect.MobileConnectManager
import org.melodist.mobile.ui.components.getQualityBadgeLabel
import org.melodist.mobile.ui.player.components.formatPlayerTime
import org.melodist.mobile.ui.theme.isAppInDarkTheme

/**
 * 远程控制主卡片组件（包含连接状态条、远端播放曲目行、进度条拖拽与多媒体播放控制器）
 */
@Composable
fun RemotePlaybackControlCard(
    connectionState: MobileConnectionState,
    tvPlayerState: PlayerStateEvent?,
    onRequestScanQr: () -> Unit,
    onRequestManualInput: () -> Unit,
    onRequestTvQualitySheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. 顶部状态栏：设备名称 / 状态 + AOD 按钮 + 断开/扫码按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Icon(
                        imageVector = if (connectionState is MobileConnectionState.Paired) Icons.Rounded.Tv else Icons.Rounded.TvOff,
                        contentDescription = null,
                        tint = if (connectionState is MobileConnectionState.Paired) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = when (connectionState) {
                            is MobileConnectionState.Paired -> connectionState.targetDevice.name
                            is MobileConnectionState.Connecting -> "正在连接电视..."
                            is MobileConnectionState.Connected -> "正在握手认证..."
                            is MobileConnectionState.Reconnecting -> "正在重连 (${connectionState.attempt}/${connectionState.maxAttempts})..."
                            is MobileConnectionState.Error -> "连接失败"
                            else -> "TV 远程控制板 (未连接)"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (connectionState is MobileConnectionState.Paired) {
                        FilledTonalButton(
                            onClick = {
                                MobileConnectManager.triggerTvAod()
                                Toast.makeText(context, "已切换 TV 端 AOD 模式", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DarkMode,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("AOD", fontSize = 11.sp)
                        }
                    }
                    if (connectionState is MobileConnectionState.Paired || connectionState is MobileConnectionState.Reconnecting) {
                        IconButton(
                            onClick = { MobileConnectManager.disconnect() },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = if (connectionState is MobileConnectionState.Reconnecting) "取消重连" else "断开连接",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        FilledTonalButton(
                            onClick = onRequestScanQr,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("扫码", fontSize = 11.sp)
                        }
                    }
                }
            }

            val curSong = tvPlayerState?.currentSong
            if (connectionState is MobileConnectionState.Paired && curSong != null) {
                // 2. 歌曲信息行：支持点击呼起 TV 端播放界面
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            MobileConnectManager.tvOpenPlayer()
                            val isAod = tvPlayerState.isAodActive
                            if (isAod) {
                                Toast.makeText(
                                    context,
                                    "TV 当前处于 AOD 息屏模式，请退出 AOD 模式查看播放界面",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    "已在 TV 打开播放界面",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AsyncImage(
                        model = curSong.coverUrl.ifBlank { curSong.thumbnailCoverUrl },
                        contentDescription = null,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = curSong.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = curSong.singer,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // 3. 进度条与时间显示
                val durationSafe = (tvPlayerState.durationMs).coerceAtLeast(1L)
                val currentPosSafe = (tvPlayerState.positionMs).coerceIn(0L, durationSafe)
                var isDraggingSlider by remember { mutableStateOf(false) }
                var draggingSliderValue by remember { mutableFloatStateOf(0f) }

                val displayPosition = if (isDraggingSlider) {
                    (draggingSliderValue * durationSafe).toLong()
                } else {
                    currentPosSafe
                }
                val progressFraction = (displayPosition.toFloat() / durationSafe).coerceIn(0f, 1f)

                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = progressFraction,
                        onValueChange = {
                            isDraggingSlider = true
                            draggingSliderValue = it
                        },
                        onValueChangeFinished = {
                            isDraggingSlider = false
                            val targetMs = (draggingSliderValue * durationSafe).toLong()
                            MobileConnectManager.tvSeekTo(targetMs)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatPlayerTime(displayPosition),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatPlayerTime(durationSafe),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 4. M3 风格控制按钮栏
                val isDark = isAppInDarkTheme()
                val playPauseContainerColor = if (isDark) Color.White else Color(0xFF1C1B1F)
                val playPauseContentColor = if (isDark) Color(0xFF1C1B1F) else Color.White
                val auxButtonBgColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // 左侧辅助按键：TV 播放循环模式
                    val tvLoopMode = tvPlayerState.loopMode
                    val loopIcon = when (tvLoopMode) {
                        "SingleRepeat" -> Icons.Rounded.RepeatOne
                        "Shuffle" -> Icons.Rounded.Shuffle
                        else -> Icons.Rounded.Repeat
                    }
                    val loopDesc = when (tvLoopMode) {
                        "SingleRepeat" -> "单曲循环"
                        "Shuffle" -> "随机播放"
                        else -> "列表循环"
                    }
                    IconButton(
                        onClick = { MobileConnectManager.tvCycleLoopMode() },
                        modifier = Modifier
                            .size(46.dp)
                            .background(auxButtonBgColor, CircleShape),
                    ) {
                        Icon(
                            imageVector = loopIcon,
                            contentDescription = loopDesc,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    // 上一首
                    IconButton(
                        onClick = { MobileConnectManager.tvPrev() },
                        modifier = Modifier
                            .size(46.dp)
                            .background(auxButtonBgColor, CircleShape),
                    ) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            contentDescription = "上一首",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    // 播放/暂停大按键 (M3 64dp 圆形立体按键)
                    FilledIconButton(
                        onClick = {
                            if (tvPlayerState.isPlaying) {
                                MobileConnectManager.tvPause()
                            } else {
                                MobileConnectManager.tvResume()
                            }
                        },
                        modifier = Modifier
                            .size(64.dp)
                            .shadow(
                                elevation = 4.dp,
                                shape = CircleShape,
                                spotColor = Color.Black.copy(alpha = if (isDark) 0.35f else 0.14f),
                                ambientColor = Color.Black.copy(alpha = 0.08f),
                                clip = false,
                            ),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = playPauseContainerColor,
                            contentColor = playPauseContentColor,
                        ),
                    ) {
                        Icon(
                            imageVector = if (tvPlayerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (tvPlayerState.isPlaying) "暂停" else "播放",
                            modifier = Modifier.size(34.dp),
                        )
                    }

                    // 下一首
                    IconButton(
                        onClick = { MobileConnectManager.tvNext() },
                        modifier = Modifier
                            .size(46.dp)
                            .background(auxButtonBgColor, CircleShape),
                    ) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = "下一首",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    // 右侧：音质切换按钮
                    val currentTvTier = curSong.currentTier
                    val isTvLocalOrWebDav = curSong.isLocal || curSong.isWebDav || !curSong.localFilePath.isNullOrBlank()
                    Surface(
                        onClick = { if (!isTvLocalOrWebDav) onRequestTvQualitySheet() },
                        shape = CircleShape,
                        color = auxButtonBgColor,
                        modifier = Modifier.size(46.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = getQualityBadgeLabel(currentTvTier),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            } else if (connectionState is MobileConnectionState.Paired) {
                // 已连接但无曲目播放
                Text(
                    text = "TV 端当前暂无播放曲目，可通过手机选歌点播或接力",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                // 未连接状态下的引导卡片
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "未连接电视设备，靠近同一 Wi-Fi 即可自动连接，或在下方设备列表中手动配对。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onRequestScanQr,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("扫码配对")
                        }
                        OutlinedButton(
                            onClick = onRequestManualInput,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("手动连接")
                        }
                    }
                }
            }
        }
    }
}
