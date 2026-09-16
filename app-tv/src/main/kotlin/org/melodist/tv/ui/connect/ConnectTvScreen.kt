package org.melodist.tv.ui.connect

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import org.melodist.core.connect.model.ConnectDevice
import org.melodist.tv.connect.TvConnectManager
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.rememberTvWindowMetrics

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ConnectTvScreen(
    onBack: () -> Unit = {},
) {
    BackHandler {
        onBack()
    }

    val metrics = rememberTvWindowMetrics()
    val pinCode by TvConnectManager.currentPinCode.collectAsState()
    val qrBitmap by TvConnectManager.qrBitmapFlow.collectAsState()
    val localIp by TvConnectManager.localIpFlow.collectAsState()
    val connectedDevice by TvConnectManager.connectedDevice.collectAsState()
    val pairedDevices by TvConnectManager.pairedDevices.collectAsState()
    val pendingPairRequest by TvConnectManager.pendingPairFlow.collectAsState()

    val refreshButtonRequester = remember { FocusRequester() }
    var showIpConfigDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        refreshButtonRequester.requestFocus()
    }

    // 切换网卡 IP 弹窗
    if (showIpConfigDialog) {
        val availableIps = remember { TvConnectManager.getAvailableIps() }
        val isEmulator = remember { org.melodist.core.connect.util.NetworkUtils.isEmulator() }
        var manualIpText by remember {
            mutableStateOf(if (localIp != "10.0.2.15" && localIp != "127.0.0.1") localIp else "")
        }

        Dialog(onDismissRequest = { showIpConfigDialog = false }) {
            Box(
                modifier = Modifier
                    .width(500.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1E24))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)), RoundedCornerShape(16.dp))
                    .padding(24.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "配置电视端服务 IP",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MelodistColors.TextPrimary,
                    )

                    if (isEmulator) {
                        Text(
                            text = "检测到当前处于模拟器环境（内部 IP: 10.0.2.15），局域网外部手机无法直连。请填写电脑物理网卡 Wi-Fi IP（例如 192.168.110.244）",
                            fontSize = 12.sp,
                            color = MelodistColors.FocusTeal,
                            lineHeight = 16.sp,
                        )
                    } else {
                        Text(
                            text = "当前使用 IP: $localIp (端口: 8765)\n若开启了 TUN 模式或存在多网卡，可手动指定局域网物理 IP",
                            fontSize = 13.sp,
                            color = MelodistColors.TextSecondary,
                        )
                    }

                    // 手动输入 IP 区块
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("手动输入 IP 地址：", fontSize = 13.sp, color = MelodistColors.TextSecondary)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                androidx.compose.foundation.text.BasicTextField(
                                    value = manualIpText,
                                    onValueChange = { manualIpText = it.trim() },
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MelodistColors.FocusTeal),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (manualIpText.isBlank()) {
                                    Text(
                                        "例如 192.168.110.244",
                                        color = MelodistColors.TextMuted,
                                        fontSize = 14.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    if (manualIpText.isNotBlank()) {
                                        TvConnectManager.setCustomIp(manualIpText.trim())
                                        showIpConfigDialog = false
                                    }
                                },
                                colors = ButtonDefaults.colors(
                                    containerColor = MelodistColors.FocusTeal,
                                    focusedContainerColor = Color.White,
                                ),
                            ) {
                                Text("应用", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }

                    if (availableIps.isNotEmpty()) {
                        Text("自动检测到的可用候选网卡：", fontSize = 13.sp, color = MelodistColors.TextSecondary)
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 140.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(availableIps) { ip ->
                                val isSelected = ip == localIp
                                Button(
                                    onClick = {
                                        TvConnectManager.setCustomIp(ip)
                                        showIpConfigDialog = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.colors(
                                        containerColor = if (isSelected) Color(0xFF2E7D32).copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f),
                                        focusedContainerColor = MelodistColors.FocusTeal,
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(ip, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                        if (isSelected) {
                                            Text("当前生效", color = Color(0xFF81C784), fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = {
                                TvConnectManager.setCustomIp(null)
                                showIpConfigDialog = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.08f),
                                focusedContainerColor = MelodistColors.FocusTeal,
                            ),
                        ) {
                            Text("恢复自动选择", fontSize = 13.sp)
                        }
                        Button(
                            onClick = { showIpConfigDialog = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.08f),
                                focusedContainerColor = MelodistColors.FocusTeal,
                            ),
                        ) {
                            Text("关闭", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    pendingPairRequest?.let { req ->
        Dialog(onDismissRequest = { TvConnectManager.rejectPairRequest(req.requestId) }) {
            val acceptRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                acceptRequester.requestFocus()
            }
            Box(
                modifier = Modifier
                    .width(480.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1E24))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)), RoundedCornerShape(16.dp))
                    .padding(28.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "设备配对请求",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MelodistColors.TextPrimary,
                    )
                    Text(
                        text = "设备 \"${req.device.name}\" 请求与此电视配对并接管播控",
                        fontSize = 15.sp,
                        color = MelodistColors.TextSecondary,
                    )
                    if (req.pinCode.isNotBlank()) {
                        Text(
                            text = "配对验证码: ${req.pinCode}",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MelodistColors.QualityGoldText,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Button(
                            onClick = { TvConnectManager.acceptPairRequest(req.requestId) },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(acceptRequester),
                            colors = ButtonDefaults.colors(
                                containerColor = MelodistColors.AccentGreen,
                                focusedContainerColor = Color.White,
                            ),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black)
                                Text("同意配对", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                        Button(
                            onClick = { TvConnectManager.rejectPairRequest(req.requestId) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.1f),
                                focusedContainerColor = MelodistColors.FavoriteRed,
                            ),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White)
                                Text("拒绝", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = metrics.horizontalSafePadding,
                vertical = metrics.verticalSafePadding,
            ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 顶部标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "远程互联与播控",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MelodistColors.TextPrimary,
                )
                Text(
                    text = "通过手机扫描二维码或局域网发现连接，实现双端接力与无缝遥控",
                    fontSize = 14.sp,
                    color = MelodistColors.TextSecondary,
                )
            }
            if (connectedDevice != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2E7D32).copy(alpha = 0.2f))
                        .border(BorderStroke(1.dp, Color(0xFF4CAF50)), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.PhoneAndroid,
                            contentDescription = null,
                            tint = Color(0xFF81C784),
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "已连接: ${connectedDevice?.name}",
                            color = Color(0xFF81C784),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // 双栏布局
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            // 左栏：二维码与配对信息
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(16.dp))
                    .padding(24.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (qrBitmap != null) {
                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                bitmap = qrBitmap!!.asImageBitmap(),
                                contentDescription = "配对二维码",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("正在生成配对二维码...", color = MelodistColors.TextMuted)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "配对码: $pinCode",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MelodistColors.QualityGoldText,
                        )
                        Button(
                            onClick = { TvConnectManager.refreshPinCode() },
                            modifier = Modifier.focusRequester(refreshButtonRequester),
                            colors = ButtonDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.1f),
                                focusedContainerColor = Color.White,
                            ),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, tint = MelodistColors.TextPrimary, modifier = Modifier.size(16.dp))
                                Text("刷新配对码", fontSize = 13.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showIpConfigDialog = true },
                        colors = ButtonDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.06f),
                            focusedContainerColor = Color.White.copy(alpha = 0.2f),
                        ),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "本机 IP: $localIp : 8765",
                                fontSize = 13.sp,
                                color = MelodistColors.TextSecondary,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = "[切换网卡]",
                                fontSize = 12.sp,
                                color = MelodistColors.FocusTeal,
                            )
                        }
                    }
                }
            }

            // 右栏：已配对设备管理
            Box(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(16.dp))
                    .padding(24.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "已配对设备",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MelodistColors.TextPrimary,
                        )
                        if (pairedDevices.isNotEmpty()) {
                            Button(
                                onClick = { TvConnectManager.clearAllPairedDevices() },
                                colors = ButtonDefaults.colors(
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    focusedContainerColor = MelodistColors.FavoriteRed,
                                ),
                            ) {
                                Text("全部解除", fontSize = 12.sp)
                            }
                        }
                    }

                    if (pairedDevices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "暂无已配对设备\n使用手机端打开 Melodist 即可扫码配对",
                                color = MelodistColors.TextMuted,
                                fontSize = 14.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(pairedDevices, key = { it.id }) { device ->
                                PairedDeviceRowItem(
                                    device = device,
                                    isCurrentConnected = connectedDevice?.id == device.id,
                                    onRemove = { TvConnectManager.removePairedDevice(device.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PairedDeviceRowItem(
    device: ConnectDevice,
    isCurrentConnected: Boolean,
    onRemove: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFocused) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f))
            .border(
                BorderStroke(1.dp, if (isFocused) MelodistColors.FocusTeal else Color.White.copy(alpha = 0.06f)),
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.PhoneAndroid,
                contentDescription = null,
                tint = if (isCurrentConnected) Color(0xFF81C784) else MelodistColors.TextSecondary,
                modifier = Modifier.size(22.dp),
            )
            Column {
                Text(
                    text = device.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MelodistColors.TextPrimary,
                )
                Text(
                    text = if (isCurrentConnected) "当前在线连接" else "已离线",
                    fontSize = 12.sp,
                    color = if (isCurrentConnected) Color(0xFF81C784) else MelodistColors.TextMuted,
                )
            }
        }

        Button(
            onClick = onRemove,
            modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
            colors = ButtonDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = MelodistColors.FavoriteRed,
            ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("解除", fontSize = 12.sp)
            }
        }
    }
}
