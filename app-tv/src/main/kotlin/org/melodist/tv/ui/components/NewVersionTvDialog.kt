package org.melodist.tv.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.melodist.data.update.AppUpdateDownloader
import org.melodist.data.update.UpdateChecker
import org.melodist.data.update.UpdateDownloadState
import org.melodist.data.update.UpdateResult
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.rememberMonetSurfaceColor
import org.melodist.tv.ui.theme.toMonetContainer
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NewVersionTvDialog(
    newVersion: UpdateResult.NewVersion,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val confirmFocusRequester = remember { FocusRequester() }
    val monetSurfaceColor = rememberMonetSurfaceColor()
    val dialogBackgroundColor =
        remember(monetSurfaceColor) {
            monetSurfaceColor.toMonetContainer(elevation = 0.08f).copy(alpha = 0.95f)
        }

    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadState by remember { mutableStateOf<UpdateDownloadState>(UpdateDownloadState.Idle) }

    LaunchedEffect(Unit) {
        delay(150)
        try {
            confirmFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(downloadState) {
        if (downloadState is UpdateDownloadState.Completed) {
            try {
                confirmFocusRequester.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    fun startDownload(downloadUrl: String) {
        downloadJob?.cancel()
        downloadJob =
            coroutineScope.launch {
                AppUpdateDownloader
                    .downloadApk(context, downloadUrl, newVersion.tagName)
                    .collect { state ->
                        downloadState = state
                        if (state is UpdateDownloadState.Completed) {
                            AppUpdateDownloader.installApk(context, state.apkFile)
                        }
                    }
            }
    }

    Dialog(
        onDismissRequest = {
            downloadJob?.cancel()
            onDismissRequest()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setDimAmount(0.35f)
        }

        Box(
            modifier =
                Modifier
                    .width(580.dp)
                    .wrapContentHeight()
                    .clip(MelodistShapes.DialogCorner)
                    .background(dialogBackgroundColor)
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), MelodistShapes.DialogCorner)
                    .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 标题栏
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.SystemUpdateAlt,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = "发现新版本 ${newVersion.tagName}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }

                // 更新日志
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .clip(MelodistShapes.ButtonCorner)
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "更新日志:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    Text(
                        text = newVersion.releaseNotes.ifBlank { "本次更新包含功能优化与问题修复。" },
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.90f),
                        lineHeight = 20.sp,
                    )
                }

                // 下载状态与进度条
                when (val state = downloadState) {
                    is UpdateDownloadState.Downloading -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (state.totalBytes > 0) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(Color.White.copy(alpha = 0.12f)),
                                ) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(state.progress.coerceIn(0f, 1f))
                                                .background(Color.White),
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val downloadedMb = state.bytesDownloaded.toDouble() / (1024 * 1024)
                                    val totalMb = state.totalBytes.toDouble() / (1024 * 1024)
                                    val percent = (state.progress * 100).toInt()
                                    Text(
                                        text = "正在下载: %.1f MB / %.1f MB".format(Locale.CHINA, downloadedMb, totalMb),
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.70f),
                                    )
                                    Text(
                                        text = "$percent%",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                    )
                                }
                            } else {
                                val downloadedMb = state.bytesDownloaded.toDouble() / (1024 * 1024)
                                Text(
                                    text = "正在下载: %.1f MB".format(Locale.CHINA, downloadedMb),
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.70f),
                                )
                            }
                        }
                    }
                    is UpdateDownloadState.Completed -> {
                        Text(
                            text = "安装包已下载完毕，按确认键直接调起系统安装器。",
                            fontSize = 12.sp,
                            color = MelodistColors.AccentGreen,
                        )
                    }
                    is UpdateDownloadState.Error -> {
                        Text(
                            text = "下载失败: ${state.message}",
                            fontSize = 12.sp,
                            color = Color(0xFFEF4444),
                        )
                    }
                    is UpdateDownloadState.Idle -> {}
                }

                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    Button(
                        onClick = {
                            downloadJob?.cancel()
                            onDismissRequest()
                        },
                        shape =
                            ButtonDefaults.shape(
                                shape = MelodistShapes.ButtonCorner,
                                focusedShape = MelodistShapes.ButtonCorner,
                            ),
                        colors =
                            ButtonDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.08f),
                                focusedContainerColor = Color.White,
                                contentColor = Color.White,
                                focusedContentColor = Color.Black,
                            ),
                        border =
                            ButtonDefaults.border(
                                border =
                                    Border(
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                        shape = MelodistShapes.ButtonCorner,
                                    ),
                                focusedBorder = Border.None,
                            ),
                    ) {
                        Text(
                            text = if (downloadState is UpdateDownloadState.Downloading) "取消下载" else "稍后再说",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    when (val state = downloadState) {
                        is UpdateDownloadState.Completed -> {
                            Button(
                                onClick = {
                                    AppUpdateDownloader.installApk(context, state.apkFile)
                                },
                                modifier = Modifier.focusRequester(confirmFocusRequester),
                                shape =
                                    ButtonDefaults.shape(
                                        shape = MelodistShapes.ButtonCorner,
                                        focusedShape = MelodistShapes.ButtonCorner,
                                    ),
                                colors =
                                    ButtonDefaults.colors(
                                        containerColor = Color.White.copy(alpha = 0.25f),
                                        focusedContainerColor = Color.White,
                                        contentColor = Color.White,
                                        focusedContentColor = Color.Black,
                                    ),
                                border =
                                    ButtonDefaults.border(
                                        border =
                                            Border(
                                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                                                shape = MelodistShapes.ButtonCorner,
                                            ),
                                        focusedBorder = Border.None,
                                    ),
                            ) {
                                Text(
                                    text = "立即安装",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        is UpdateDownloadState.Downloading -> {
                            Button(
                                onClick = {},
                                enabled = false,
                                shape =
                                    ButtonDefaults.shape(
                                        shape = MelodistShapes.ButtonCorner,
                                        focusedShape = MelodistShapes.ButtonCorner,
                                    ),
                                colors =
                                    ButtonDefaults.colors(
                                        containerColor = Color.White.copy(alpha = 0.05f),
                                        disabledContainerColor = Color.White.copy(alpha = 0.05f),
                                        contentColor = Color.White.copy(alpha = 0.40f),
                                        disabledContentColor = Color.White.copy(alpha = 0.40f),
                                    ),
                            ) {
                                Text(
                                    text = "下载中...",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        is UpdateDownloadState.Error -> {
                            Button(
                                onClick = {
                                    val url = newVersion.downloadUrl
                                    if (!url.isNullOrBlank()) {
                                        startDownload(url)
                                    }
                                },
                                modifier = Modifier.focusRequester(confirmFocusRequester),
                                shape =
                                    ButtonDefaults.shape(
                                        shape = MelodistShapes.ButtonCorner,
                                        focusedShape = MelodistShapes.ButtonCorner,
                                    ),
                                colors =
                                    ButtonDefaults.colors(
                                        containerColor = Color.White.copy(alpha = 0.18f),
                                        focusedContainerColor = Color.White,
                                        contentColor = Color.White,
                                        focusedContentColor = Color.Black,
                                    ),
                                border =
                                    ButtonDefaults.border(
                                        border =
                                            Border(
                                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                                                shape = MelodistShapes.ButtonCorner,
                                            ),
                                        focusedBorder = Border.None,
                                    ),
                            ) {
                                Text(
                                    text = "重试下载",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        is UpdateDownloadState.Idle -> {
                            Button(
                                onClick = {
                                    val downloadUrl = newVersion.downloadUrl
                                    if (!downloadUrl.isNullOrBlank()) {
                                        startDownload(downloadUrl)
                                    } else {
                                        val fallbackUrl = "${UpdateChecker.REPO_WEB_URL}/releases"
                                        val intent =
                                            Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        try {
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                        }
                                        onDismissRequest()
                                    }
                                },
                                modifier = Modifier.focusRequester(confirmFocusRequester),
                                shape =
                                    ButtonDefaults.shape(
                                        shape = MelodistShapes.ButtonCorner,
                                        focusedShape = MelodistShapes.ButtonCorner,
                                    ),
                                colors =
                                    ButtonDefaults.colors(
                                        containerColor = Color.White.copy(alpha = 0.18f),
                                        focusedContainerColor = Color.White,
                                        contentColor = Color.White,
                                        focusedContentColor = Color.Black,
                                    ),
                                border =
                                    ButtonDefaults.border(
                                        border =
                                            Border(
                                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                                                shape = MelodistShapes.ButtonCorner,
                                            ),
                                        focusedBorder = Border.None,
                                    ),
                            ) {
                                Text(
                                    text = if (!newVersion.downloadUrl.isNullOrBlank()) "立即更新" else "前往网页",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
