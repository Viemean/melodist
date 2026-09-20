package org.melodist.mobile.ui.auth

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.melodist.api.LoginApiService
import org.melodist.api.MusicApiService
import org.melodist.api.QrStatus
import org.melodist.api.UserSession
import org.melodist.api.refreshCurrentUserProfile
import org.melodist.data.UserSessionManager
import java.util.Locale

enum class MobileLoginChannel(
    val label: String,
    val appName: String,
) {
    QQ("QQ 扫码", "手机 QQ"),
    WeChat("微信扫码", "手机微信"),
}

@Composable
fun MobileLoginDialog(
    onDismissRequest: () -> Unit,
    onLoginSuccess: () -> Unit,
) {
    val context = LocalContext.current
    val userProfile by UserSession.profileFlow.collectAsState()
    val isLoggedIn = UserSession.isLoggedIn

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 顶栏关闭与标题
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (isLoggedIn) "账号信息" else "扫码登录",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isLoggedIn) {
                    // 已登录状态
                    val avatarUrl = userProfile.effectiveAvatarUrl
                    Box(
                        modifier =
                            Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (avatarUrl.isNotBlank()) {
                            AsyncImage(
                                model = avatarUrl,
                                contentDescription = "头像",
                                modifier = Modifier.size(72.dp),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = userProfile.nick.ifBlank { "已登录用户" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "UIN: ${userProfile.uin}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(0.9f),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                UserSession.clear()
                                UserSessionManager.save(context)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("切换账号")
                        }
                        OutlinedButton(
                            onClick = {
                                UserSession.clear()
                                UserSessionManager.save(context)
                                onDismissRequest()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("退出登录", color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    // 未登录扫码流程
                    var selectedChannel by remember { mutableStateOf(MobileLoginChannel.QQ) }
                    val loginService = remember { LoginApiService() }
                    var refreshTrigger by remember(selectedChannel) { mutableIntStateOf(0) }
                    var remainingSeconds by remember(selectedChannel, refreshTrigger) { mutableIntStateOf(180) }
                    var qrImageBitmap by remember(selectedChannel, refreshTrigger) { mutableStateOf<ImageBitmap?>(null) }
                    var statusText by remember(selectedChannel, refreshTrigger) { mutableStateOf("正在获取二维码...") }
                    var isPolling by remember(selectedChannel, refreshTrigger) { mutableStateOf(false) }

                    fun refreshQr() {
                        refreshTrigger++
                    }

                    // 渠道切换 Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        MobileLoginChannel.entries.forEach { channel ->
                            FilterChip(
                                selected = selectedChannel == channel,
                                onClick = {
                                    if (selectedChannel != channel) {
                                        selectedChannel = channel
                                    }
                                },
                                label = { Text(channel.label) },
                                colors =
                                    FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 倒计时
                    LaunchedEffect(selectedChannel, refreshTrigger) {
                        remainingSeconds = 180
                        while (remainingSeconds > 0) {
                            delay(1000L)
                            remainingSeconds--
                        }
                        refreshQr()
                    }

                    // 拉取与轮询二维码
                    LaunchedEffect(selectedChannel, refreshTrigger) {
                        isPolling = true
                        qrImageBitmap = null
                        statusText = "正在生成登录二维码..."

                        try {
                            when (selectedChannel) {
                                MobileLoginChannel.QQ -> {
                                    val qrInfo = loginService.fetchQqQrCode()
                                    val bitmap = BitmapFactory.decodeByteArray(qrInfo.imageBytes, 0, qrInfo.imageBytes.size)
                                    if (bitmap != null) {
                                        qrImageBitmap = bitmap.asImageBitmap()
                                        statusText = "请使用 ${selectedChannel.appName} 扫码"
                                    }

                                    while (isPolling) {
                                        delay(1500L)
                                        val poll = loginService.pollQqQrStatus(qrInfo.identifier)
                                        when (poll.status) {
                                            QrStatus.Waiting -> {
                                                statusText = "请使用 ${selectedChannel.appName} 扫码"
                                            }
                                            QrStatus.Confirming -> {
                                                statusText = "已扫码，请在手机端确认授权"
                                            }
                                            QrStatus.Success -> {
                                                statusText = "登录成功"
                                                try {
                                                    MusicApiService().refreshCurrentUserProfile()
                                                } catch (_: Exception) {
                                                }
                                                UserSessionManager.save(context)
                                                isPolling = false
                                                onLoginSuccess()
                                                break
                                            }
                                            QrStatus.Expired -> {
                                                statusText = "二维码已过期，点击刷新"
                                                isPolling = false
                                                break
                                            }
                                            QrStatus.Canceled -> {
                                                statusText = "已取消授权"
                                                isPolling = false
                                                break
                                            }
                                            QrStatus.Error -> {
                                                statusText = poll.message
                                            }
                                        }
                                    }
                                }
                                MobileLoginChannel.WeChat -> {
                                    val qrInfo = loginService.fetchWeChatQrCode()
                                    val bitmap = BitmapFactory.decodeByteArray(qrInfo.imageBytes, 0, qrInfo.imageBytes.size)
                                    if (bitmap != null) {
                                        qrImageBitmap = bitmap.asImageBitmap()
                                        statusText = "请使用 ${selectedChannel.appName} 扫码"
                                    }

                                    while (isPolling) {
                                        delay(1500L)
                                        val poll = loginService.pollWeChatQrStatus(qrInfo.identifier)
                                        when (poll.status) {
                                            QrStatus.Waiting -> {
                                                statusText = "请使用 ${selectedChannel.appName} 扫码"
                                            }
                                            QrStatus.Confirming -> {
                                                statusText = "已扫码，请在手机端确认授权"
                                            }
                                            QrStatus.Success -> {
                                                statusText = "登录成功"
                                                try {
                                                    MusicApiService().refreshCurrentUserProfile()
                                                } catch (_: Exception) {
                                                }
                                                UserSessionManager.save(context)
                                                isPolling = false
                                                onLoginSuccess()
                                                break
                                            }
                                            QrStatus.Expired -> {
                                                statusText = "二维码已过期，点击刷新"
                                                isPolling = false
                                                break
                                            }
                                            QrStatus.Canceled -> {
                                                statusText = "已取消授权"
                                                isPolling = false
                                                break
                                            }
                                            QrStatus.Error -> {
                                                statusText = poll.message
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            statusText = "拉取二维码失败: ${e.message ?: "网络异常"}"
                        }
                    }

                    // 二维码展示卡片
                    Box(
                        modifier =
                            Modifier
                                .size(190.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .padding(8.dp)
                                .clickable {
                                    if (!isPolling) refreshQr()
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        val bitmap = qrImageBitmap
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "登录二维码",
                                filterQuality = FilterQuality.None,
                                modifier = Modifier.size(174.dp),
                            )
                        } else {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    val minutes = remainingSeconds / 60
                    val seconds = remainingSeconds % 60
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "有效时间 ${String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = { refreshQr() },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "刷新二维码",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
