package org.melodist.mobile.ui.connect

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.melodist.core.connect.client.MobileConnectionState
import org.melodist.core.connect.model.ConnectDevice
import org.melodist.core.connect.model.RemoteControlMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.melodist.api.probeSongQualities
import org.melodist.mobile.connect.MobileConnectManager
import org.melodist.mobile.ui.player.components.formatPlayerTime

@Composable
fun RemoteControlMobileScreen(
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val connectionState by MobileConnectManager.connectionState.collectAsState()
    val discoveredDevices by MobileConnectManager.discoveredDevices.collectAsState()
    val pairedDevices by MobileConnectManager.pairedDevices.collectAsState()
    val localMute by MobileConnectManager.localMute.collectAsState()
    val tvOfflineProxy by MobileConnectManager.tvOfflineProxy.collectAsState()
    val remoteControlMode by MobileConnectManager.remoteControlMode.collectAsState()
    val tvPlayerState by MobileConnectManager.tvPlayerState.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    var showQrScanner by remember { mutableStateOf(false) }
    var showManualInputDialog by remember { mutableStateOf(false) }
    var showTvQualitySheet by remember { mutableStateOf(false) }
    var manualHost by remember { mutableStateOf("") }
    var manualPin by remember { mutableStateOf("") }
    var tvProbedQualityOptions by remember { mutableStateOf<List<org.melodist.model.QualityOption>>(emptyList()) }
    var isTvProbingQuality by remember { mutableStateOf(false) }

    var selectedDeviceForPin by remember { mutableStateOf<ConnectDevice?>(null) }
    var pinInput by remember { mutableStateOf("") }

    val curTvSong = tvPlayerState?.currentSong
    LaunchedEffect(showTvQualitySheet, curTvSong?.songMid) {
        if (showTvQualitySheet && curTvSong != null) {
            val isLocalOrWebDav = curTvSong.isLocal || curTvSong.isWebDav
            if (!isLocalOrWebDav) {
                isTvProbingQuality = true
                try {
                    val probed = withContext<List<org.melodist.model.QualityOption>>(Dispatchers.IO) {
                        org.melodist.api.MusicApiService().probeSongQualities(curTvSong.songMid, curTvSong.mediaMid)
                    }
                    tvProbedQualityOptions = probed
                } catch (_: Exception) {
                } finally {
                    isTvProbingQuality = false
                }
            } else {
                tvProbedQualityOptions = emptyList()
                isTvProbingQuality = false
            }
        }
    }

    LaunchedEffect(Unit) {
        MobileConnectManager.startDiscovery()
    }

    // 扫码弹窗
    if (showQrScanner) {
        QrScannerDialog(
            onDismissRequest = { showQrScanner = false },
            onQrDecoded = { qrText ->
                val ok = MobileConnectManager.connectByQrJson(qrText)
                if (ok) {
                    android.widget.Toast.makeText(context, "已识别电视配置，正在握手连接...", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    // 若是普通 IP 或文本，填入手动框
                    val trimmed = qrText.trim()
                    if (trimmed.contains(".") && !trimmed.contains("{")) {
                        manualHost = trimmed.substringBefore(":")
                        showManualInputDialog = true
                    } else {
                        android.widget.Toast.makeText(context, "二维码内容不符合配对规范", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    // 手动输入配对码弹窗
    if (showManualInputDialog) {
        AlertDialog(
            onDismissRequest = { showManualInputDialog = false },
            title = { Text("手动连接电视") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it },
                        label = { Text("电视 IP 地址 (例如 192.168.1.100)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = manualPin,
                        onValueChange = { manualPin = it },
                        label = { Text("6位配对码 (选填)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (manualHost.isNotBlank()) {
                            val dev = ConnectDevice(
                                id = "manual_${manualHost.replace(".", "_")}",
                                name = "Melodist TV ($manualHost)",
                                type = org.melodist.core.connect.model.DeviceType.TV,
                                host = manualHost.trim(),
                                port = 8765,
                            )
                            MobileConnectManager.connectTo(dev, manualPin.trim())
                            showManualInputDialog = false
                        }
                    },
                ) {
                    Text("连接")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualInputDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    // 针对局域网设备输入 PIN 弹窗
    selectedDeviceForPin?.let { dev ->
        AlertDialog(
            onDismissRequest = { selectedDeviceForPin = null },
            title = { Text("输入配对码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请输入电视屏幕上显示的 6 位配对码：", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 6) pinInput = it },
                        label = { Text("配对码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        MobileConnectManager.connectTo(dev, pinInput.trim())
                        selectedDeviceForPin = null
                        pinInput = ""
                    },
                ) {
                    Text("确定连接")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedDeviceForPin = null }) {
                    Text("取消")
                }
            },
        )
    }

    // TV 端音质切换弹窗（与 Android 播放界面统一组件与规格）
    if (showTvQualitySheet) {
        val curSong = tvPlayerState?.currentSong
        val curTier = curSong?.currentTier ?: org.melodist.model.AudioQualityTier.SQ
        val availTiers = curSong?.availableTiers?.toSet() ?: emptySet()
        org.melodist.mobile.ui.components.AudioQualityBottomSheet(
            currentTier = curTier,
            availableTiers = availTiers,
            probedQualityOptions = tvProbedQualityOptions,
            songDurationSec = curSong?.durationSeconds ?: 0,
            isProbing = isTvProbingQuality,
            targetSong = curSong,
            enforceCellularRestriction = false,
            onSelectTier = { selectedTier ->
                MobileConnectManager.tvSwitchTier(selectedTier)
                showTvQualitySheet = false
            },
            onDismissRequest = { showTvQualitySheet = false },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 80.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. TV 远程控制板 (置于最顶部)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // 顶部状态栏：设备名称 / 状态 + AOD 按钮 + 断开按钮
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
                                imageVector = if (connectionState is MobileConnectionState.Paired) Icons.Filled.Tv else Icons.Filled.TvOff,
                                contentDescription = null,
                                tint = if (connectionState is MobileConnectionState.Paired) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                text = when (connectionState) {
                                    is MobileConnectionState.Paired -> (connectionState as MobileConnectionState.Paired).targetDevice.name
                                    is MobileConnectionState.Connecting -> "正在连接电视..."
                                    is MobileConnectionState.Connected -> "正在握手认证..."
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
                                        android.widget.Toast.makeText(context, "已切换 TV 端 AOD 模式", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(30.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DarkMode,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("AOD", fontSize = 11.sp)
                                }
                                IconButton(
                                    onClick = { MobileConnectManager.disconnect() },
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "断开连接",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            } else {
                                FilledTonalButton(
                                    onClick = { showQrScanner = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(30.dp),
                                ) {
                                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("扫码", fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    val curSong = tvPlayerState?.currentSong
                    if (connectionState is MobileConnectionState.Paired && curSong != null) {
                        // 歌曲信息行：支持点击打开 TV 端播放界面
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    MobileConnectManager.tvOpenPlayer()
                                    val isAod = tvPlayerState?.isAodActive == true
                                    if (isAod) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "TV 当前处于 AOD 息屏模式，请退出 AOD 模式查看播放界面",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    } else {
                                        android.widget.Toast.makeText(
                                            context,
                                            "已在 TV 打开播放界面",
                                            android.widget.Toast.LENGTH_SHORT,
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

                        // 进度条与时间显示
                        val durationSafe = (tvPlayerState?.durationMs ?: 0L).coerceAtLeast(1L)
                        val currentPosSafe = (tvPlayerState?.positionMs ?: 0L).coerceIn(0L, durationSafe)
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

                        // M3 风格控制按钮栏
                        val isDark = isSystemInDarkTheme()
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
                            val tvLoopMode = tvPlayerState?.loopMode.orEmpty()
                            val loopIcon = when (tvLoopMode) {
                                "SingleRepeat" -> Icons.Filled.RepeatOne
                                "Shuffle" -> Icons.Filled.Shuffle
                                else -> Icons.Filled.Repeat
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
                                    Icons.Filled.SkipPrevious,
                                    contentDescription = "上一首",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(24.dp),
                                )
                            }

                            // 播放/暂停大按键 (M3 64dp 圆形立体按键)
                            FilledIconButton(
                                onClick = {
                                    if (tvPlayerState?.isPlaying == true) {
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
                                    imageVector = if (tvPlayerState?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (tvPlayerState?.isPlaying == true) "暂停" else "播放",
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
                                    Icons.Filled.SkipNext,
                                    contentDescription = "下一首",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(24.dp),
                                )
                            }

                            // 右侧：音质切换按钮（点击弹出音质选择弹窗）
                            val currentTvTier = curSong.currentTier
                            Surface(
                                onClick = { showTvQualitySheet = true },
                                shape = CircleShape,
                                color = auxButtonBgColor,
                                modifier = Modifier.size(46.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = org.melodist.mobile.ui.components.getQualityBadgeLabel(currentTvTier),
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
                        // 未连接状态下的提示与操作
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
                                    onClick = { showQrScanner = true },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("扫码配对 TV")
                                }
                                OutlinedButton(
                                    onClick = { showManualInputDialog = true },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("手动连接")
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. 联动特性开关面板
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "互联偏好设置",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )

                    // 模式选择
                    Text(
                        text = "联动控制模式",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val isTakeover = remoteControlMode == RemoteControlMode.TAKEOVER
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    MobileConnectManager.setRemoteControlMode(RemoteControlMode.TAKEOVER)
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isTakeover) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = if (isTakeover) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    RadioButton(
                                        selected = isTakeover,
                                        onClick = { MobileConnectManager.setRemoteControlMode(RemoteControlMode.TAKEOVER) },
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        text = "全面接管",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isTakeover) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "选歌、歌单、切音质直接调度在 TV 播放",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    color = if (isTakeover) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        val isBrowse = remoteControlMode == RemoteControlMode.BROWSE
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    MobileConnectManager.setRemoteControlMode(RemoteControlMode.BROWSE)
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isBrowse) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = if (isBrowse) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    RadioButton(
                                        selected = isBrowse,
                                        onClick = { MobileConnectManager.setRemoteControlMode(RemoteControlMode.BROWSE) },
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        text = "浏览模式",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isBrowse) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "手机本地播放，点击接力或菜单时在 TV 播放",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    color = if (isBrowse) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // 本地静音开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("本地静音功能", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "开启后手机仅充当遥控板不发声，避免双端回声重叠",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = localMute,
                            onCheckedChange = { MobileConnectManager.setLocalMute(it) },
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // TV 离线模式开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("TV 离线中转模式", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "开启后 TV 端无需直连公网，全部音频流与元数据经由手机代理中转",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = tvOfflineProxy,
                            onCheckedChange = { MobileConnectManager.setTvOfflineProxy(it) },
                        )
                    }
                }
            }
        }


        // 4. 局域网发现与配对设备列表
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "局域网设备与配对",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showQrScanner = true }) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("扫码", fontSize = 12.sp)
                    }
                    TextButton(onClick = { MobileConnectManager.startDiscovery() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("刷新", fontSize = 12.sp)
                    }
                    TextButton(onClick = { showManualInputDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("手动", fontSize = 12.sp)
                    }
                }
            }
        }

        if (discoveredDevices.isEmpty() && pairedDevices.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "正在扫描局域网 TV 设备...\n请确保电视与手机处于相同 Wi-Fi 网络",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        if (discoveredDevices.isNotEmpty()) {
            item {
                Text(
                    text = "发现附近设备 (${discoveredDevices.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            items(discoveredDevices, key = { it.id }) { device ->
                val isConnected = (connectionState as? MobileConnectionState.Paired)?.targetDevice?.id == device.id
                Surface(
                    onClick = {
                        if (!isConnected) {
                            selectedDeviceForPin = device
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Filled.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text(device.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("${device.host}:${device.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (isConnected) {
                            Text("已连接", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        } else {
                            FilledTonalButton(
                                onClick = { selectedDeviceForPin = device },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text("连接", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        if (pairedDevices.isNotEmpty()) {
            item {
                Text(
                    text = "已记忆配对历史 (${pairedDevices.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(pairedDevices, key = { "paired_${it.id}" }) { device ->
                val liveDevice = discoveredDevices.find { it.id == device.id }
                val target = liveDevice ?: device
                val isConnected = (connectionState as? MobileConnectionState.Paired)?.targetDevice?.id == device.id

                Surface(
                    onClick = {
                        if (!isConnected) {
                            MobileConnectManager.connectTo(target)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isConnected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = if (isConnected) Icons.Filled.Tv else Icons.Filled.History,
                                contentDescription = null,
                                tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column {
                                Text(
                                    text = target.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isConnected) FontWeight.Bold else FontWeight.Medium,
                                )
                                Text(
                                    text = if (isConnected) {
                                        "当前已连接"
                                    } else {
                                        "地址: ${target.host.ifBlank { "局域网已记忆" }}:${target.port}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!isConnected) {
                                FilledTonalButton(
                                    onClick = { MobileConnectManager.connectTo(target) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text("连接", fontSize = 12.sp)
                                }
                            }
                            IconButton(
                                onClick = { MobileConnectManager.removePairedDevice(device.id) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "删除记录", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
