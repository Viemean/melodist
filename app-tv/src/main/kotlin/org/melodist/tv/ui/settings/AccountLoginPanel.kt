package org.melodist.tv.ui.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.melodist.api.LoginApiService
import org.melodist.api.QrStatus
import org.melodist.api.UserSession
import org.melodist.data.UserSessionManager
import org.melodist.tv.ui.theme.LocalMonetSurface
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.toMonetContainer
import java.util.Locale
import kotlin.random.Random

enum class LoginChannel(
    val label: String,
    val appName: String,
) {
    QQ("QQ登录", "手机 QQ"),
    WeChat("微信登录", "手机微信"),
    QQMusic("QQ音乐", "QQ 音乐"),
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AccountLoginPanel(
    selectedChannel: LoginChannel,
    onChannelSelected: (LoginChannel) -> Unit,
    menuRequester: FocusRequester? = null,
) {
    val context = LocalContext.current
    val currentUserProfile by UserSession.profileFlow.collectAsState()
    val isLoggedIn =
        (currentUserProfile.uin.isNotBlank() || currentUserProfile.nick.isNotBlank()) &&
            (currentUserProfile.musicKey.isNotBlank() || currentUserProfile.cookies.isNotEmpty())

    val surfaceColor = LocalMonetSurface.current
    val itemContainerBg = surfaceColor.toMonetContainer(0.08f)

    if (isLoggedIn) {
        // 已登录界面
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(itemContainerBg),
                contentAlignment = Alignment.Center,
            ) {
                if (currentUserProfile.avatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = currentUserProfile.avatarUrl,
                        contentDescription = "Avatar",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "User",
                        tint = MelodistColors.AccentGreen,
                        modifier = Modifier.size(52.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = currentUserProfile.nick.ifBlank { "已登录用户" },
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "UIN: ${currentUserProfile.uin.ifBlank { "已授权" }} · 授权状态有效",
                fontSize = 15.sp,
                color = Color.White,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    UserSessionManager.clear(context)
                },
                modifier = Modifier.then(if (menuRequester != null) Modifier.focusProperties { left = menuRequester } else Modifier),
                shape =
                    ButtonDefaults.shape(
                        shape = MelodistShapes.ButtonCorner,
                        focusedShape = MelodistShapes.ButtonCorner,
                    ),
                colors =
                    ButtonDefaults.colors(
                        containerColor = itemContainerBg,
                        focusedContainerColor = Color.White,
                        contentColor = Color.White,
                        focusedContentColor = Color.Black,
                    ),
                border =
                    ButtonDefaults.border(
                        border =
                            Border(
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                                shape = MelodistShapes.ButtonCorner,
                            ),
                        focusedBorder =
                            Border(
                                border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                                shape = MelodistShapes.ButtonCorner,
                            ),
                    ),
                scale = ButtonDefaults.scale(focusedScale = 1.08f),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "退出登录 / 切换账号",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        return
    }

    val loginService = remember { LoginApiService() }
    var refreshTrigger by remember(selectedChannel) { mutableIntStateOf(0) }
    var remainingSeconds by remember(selectedChannel, refreshTrigger) { mutableIntStateOf(180) }
    var qrImageBitmap by remember(selectedChannel, refreshTrigger) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var statusText by remember(selectedChannel, refreshTrigger) { mutableStateOf("正在获取二维码...") }
    var isPolling by remember(selectedChannel, refreshTrigger) { mutableStateOf(false) }

    fun refreshQr() {
        refreshTrigger++
    }

    // 倒计时
    LaunchedEffect(selectedChannel, refreshTrigger) {
        while (remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
        }
        refreshQr()
    }

    // 真实拉取二维码与轮询流程
    LaunchedEffect(selectedChannel, refreshTrigger) {
        isPolling = true
        qrImageBitmap = null
        statusText = "正在生成登录二维码..."

        try {
            when (selectedChannel) {
                LoginChannel.QQ, LoginChannel.QQMusic -> {
                    val qrInfo = loginService.fetchQqQrCode()
                    val bitmap = BitmapFactory.decodeByteArray(qrInfo.imageBytes, 0, qrInfo.imageBytes.size)
                    if (bitmap != null) {
                        qrImageBitmap = bitmap.asImageBitmap()
                        statusText = "请使用手机 QQ 扫描二维码"
                    }

                    while (isPolling) {
                        delay(1500L)
                        val poll = loginService.pollQqQrStatus(qrInfo.identifier)
                        when (poll.status) {
                            QrStatus.Waiting -> {
                                statusText = "请使用手机 QQ 扫描二维码"
                            }
                            QrStatus.Confirming -> {
                                statusText = "已扫码，请在手机端确认授权"
                            }
                            QrStatus.Success -> {
                                statusText = "授权成功，正在同步登录态..."
                                UserSessionManager.save(context)
                                isPolling = false
                                break
                            }
                            QrStatus.Expired -> {
                                statusText = "二维码已过期，正在自动刷新..."
                                refreshQr()
                                break
                            }
                            QrStatus.Canceled -> {
                                statusText = "用户取消授权"
                                isPolling = false
                                break
                            }
                            QrStatus.Error -> {
                                statusText = poll.message
                            }
                        }
                    }
                }
                LoginChannel.WeChat -> {
                    val qrInfo = loginService.fetchWeChatQrCode()
                    val bitmap = BitmapFactory.decodeByteArray(qrInfo.imageBytes, 0, qrInfo.imageBytes.size)
                    if (bitmap != null) {
                        qrImageBitmap = bitmap.asImageBitmap()
                        statusText = "请使用微信扫描二维码"
                    }

                    while (isPolling) {
                        delay(1500L)
                        val poll = loginService.pollWeChatQrStatus(qrInfo.identifier)
                        when (poll.status) {
                            QrStatus.Waiting -> {
                                statusText = "请使用微信扫描二维码"
                            }
                            QrStatus.Confirming -> {
                                statusText = "已扫码，请在手机端确认授权"
                            }
                            QrStatus.Success -> {
                                statusText = "授权成功，正在同步登录态..."
                                UserSessionManager.save(context)
                                isPolling = false
                                break
                            }
                            QrStatus.Expired -> {
                                statusText = "二维码已过期，正在自动刷新..."
                                refreshQr()
                                break
                            }
                            QrStatus.Canceled -> {
                                statusText = "用户取消授权"
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

    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val timerText = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // 顶部横向 3 个登录渠道切换按钮
        Row(
            modifier = Modifier.width(420.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoginChannel.entries.forEachIndexed { index, channel ->
                val isSelected = channel == selectedChannel
                val btnModifier =
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .then(if (index == 0 && menuRequester != null) Modifier.focusProperties { left = menuRequester } else Modifier)

                Button(
                    onClick = { onChannelSelected(channel) },
                    modifier = btnModifier,
                    shape =
                        ButtonDefaults.shape(
                            shape = MelodistShapes.PillCorner,
                            focusedShape = MelodistShapes.PillCorner,
                        ),
                    colors =
                        ButtonDefaults.colors(
                            containerColor = if (isSelected) MelodistColors.AccentGreen else itemContainerBg,
                            focusedContainerColor = Color.White,
                            contentColor = if (isSelected) Color.Black else Color.White,
                            focusedContentColor = Color.Black,
                        ),
                    border =
                        ButtonDefaults.border(
                            border =
                                Border(
                                    border =
                                        BorderStroke(
                                            1.dp,
                                            if (isSelected) MelodistColors.AccentGreen else Color.White.copy(alpha = 0.10f),
                                        ),
                                    shape = MelodistShapes.PillCorner,
                                ),
                            focusedBorder =
                                Border(
                                    border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                                    shape = MelodistShapes.PillCorner,
                                ),
                        ),
                    scale = ButtonDefaults.scale(focusedScale = 1.05f),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = channel.label,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 登录二维码卡片
        Box(
            modifier =
                Modifier
                    .size(280.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qrImageBitmap != null) {
                Image(
                    bitmap = qrImageBitmap!!,
                    contentDescription = "登录二维码",
                    modifier = Modifier.fillMaxSize(),
                    filterQuality = FilterQuality.None,
                )
            } else {
                QrCodeCanvas(
                    seed = selectedChannel.ordinal + 42,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 状态文字与倒计时
        Text(
            text = statusText,
            fontSize = 15.sp,
            color = Color.White,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "二维码剩余有效时间：$timerText",
            fontSize = 13.sp,
            color = Color.White,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 底部刷新按钮
        Button(
            onClick = { refreshQr() },
            modifier = Modifier.then(if (menuRequester != null) Modifier.focusProperties { left = menuRequester } else Modifier),
            shape =
                ButtonDefaults.shape(
                    shape = MelodistShapes.ButtonCorner,
                    focusedShape = MelodistShapes.ButtonCorner,
                ),
            colors =
                ButtonDefaults.colors(
                    containerColor = itemContainerBg,
                    focusedContainerColor = Color.White,
                    contentColor = Color.White,
                    focusedContentColor = Color.Black,
                ),
            border =
                ButtonDefaults.border(
                    border =
                        Border(
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                            shape = MelodistShapes.ButtonCorner,
                        ),
                    focusedBorder =
                        Border(
                            border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                            shape = MelodistShapes.ButtonCorner,
                        ),
                ),
            scale = ButtonDefaults.scale(focusedScale = 1.08f),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "刷新",
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "手动刷新二维码",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun QrCodeCanvas(
    seed: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val gridSize = 25
        val cellSize = size.width / gridSize
        val rand = Random(seed)

        val matrix = Array(gridSize) { BooleanArray(gridSize) { rand.nextBoolean() } }

        fun markFinder(
            ox: Int,
            oy: Int,
        ) {
            for (r in 0 until 7) {
                for (c in 0 until 7) {
                    val isBorder = r == 0 || r == 6 || c == 0 || c == 6
                    val isCenter = r in 2..4 && c in 2..4
                    matrix[oy + r][ox + c] = isBorder || isCenter
                }
            }
        }

        markFinder(0, 0)
        markFinder(gridSize - 7, 0)
        markFinder(0, gridSize - 7)

        for (r in 0 until gridSize) {
            for (c in 0 until gridSize) {
                if (matrix[r][c]) {
                    drawRect(
                        color = Color(0xFF111719),
                        topLeft = Offset(c * cellSize, r * cellSize),
                        size = Size(cellSize, cellSize),
                    )
                }
            }
        }
    }
}
