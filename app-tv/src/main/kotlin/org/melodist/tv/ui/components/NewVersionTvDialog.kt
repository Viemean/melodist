package org.melodist.tv.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.delay
import org.melodist.data.update.UpdateChecker
import org.melodist.data.update.UpdateResult
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.rememberMonetSurfaceColor
import org.melodist.tv.ui.theme.toMonetContainer

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NewVersionTvDialog(
    newVersion: UpdateResult.NewVersion,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val confirmFocusRequester = remember { FocusRequester() }
    val monetSurfaceColor = rememberMonetSurfaceColor()
    val dialogBackgroundColor =
        remember(monetSurfaceColor) {
            monetSurfaceColor.toMonetContainer(elevation = 0.08f).copy(alpha = 0.95f)
        }

    LaunchedEffect(Unit) {
        delay(150)
        try {
            confirmFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setDimAmount(0.35f)
        }

        Box(
            modifier =
                Modifier
                    .width(560.dp)
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
                            .heightIn(max = 200.dp)
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

                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    Button(
                        onClick = onDismissRequest,
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
                            text = "稍后再说",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Button(
                        onClick = {
                            val downloadUrl = newVersion.downloadUrl ?: "${UpdateChecker.REPO_WEB_URL}/releases"
                            val intent =
                                Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                            }
                            onDismissRequest()
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
                            text = "前往更新",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
