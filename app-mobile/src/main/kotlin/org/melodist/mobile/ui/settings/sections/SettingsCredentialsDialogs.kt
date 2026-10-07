package org.melodist.mobile.ui.settings.sections

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.melodist.api.PlaybackCredentials
import org.melodist.api.PlaybackCredentialsManager
import org.melodist.api.UserProfile

@Composable
fun SettingsCredentialsDialogs(
    showPlaybackCredsDialog: Boolean,
    onDismissCredsDialog: () -> Unit,
    userProfile: UserProfile,
    playbackCreds: PlaybackCredentials?,
    isLoggedIn: Boolean,
) {
    val context = LocalContext.current
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var exportedTokenResult by remember { mutableStateOf<String?>(null) }
    var importTokenText by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var importErrorMessage by remember { mutableStateOf<String?>(null) }
    var pendingDowngradeResult by remember {
        mutableStateOf<PlaybackCredentialsManager.ImportValidationResult.DowngradeWarning?>(null)
    }

    if (showPlaybackCredsDialog) {
        AlertDialog(
            onDismissRequest = onDismissCredsDialog,
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text("播放凭证管理") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val isCredsInvalid = PlaybackCredentialsManager.isCredentialInvalid(playbackCreds)
                    Text(
                        text =
                            if (playbackCreds != null) {
                                if (isCredsInvalid) {
                                    "当前状态: 导入的凭证为已失效\n账号: ${playbackCreds.nick.ifBlank {
                                        playbackCreds.uin
                                    }} (UIN: ${playbackCreds.uin})\n该凭证可能已被云端吊销或已过期，请重新导入有效凭证。"
                                } else {
                                    "当前状态: 已启用独立播放凭证\n账号: ${playbackCreds.nick.ifBlank {
                                        playbackCreds.uin
                                    }} (UIN: ${playbackCreds.uin})\n播放与下载将使用该账号权限，歌单与推荐保持使用主账号。"
                                }
                            } else {
                                "当前状态: 未绑定独立凭证\n播放与下载默认使用当前主登录账号。"
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (playbackCreds != null) {
                                if (isCredsInvalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    FilledTonalButton(
                        onClick = {
                            onDismissCredsDialog()
                            exportPassword = ""
                            exportedTokenResult = null
                            showExportDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("导出播放凭证")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    FilledTonalButton(
                        onClick = {
                            onDismissCredsDialog()
                            importTokenText = ""
                            importPassword = ""
                            importErrorMessage = null
                            showImportDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("导入播放凭证")
                    }

                    if (playbackCreds != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                PlaybackCredentialsManager.clearCredentials()
                                onDismissCredsDialog()
                                Toast.makeText(context, "已清除独立凭据，已恢复使用主账号", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("清除独立播放凭证", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissCredsDialog) {
                    Text("关闭")
                }
            },
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text("导出播放凭证") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (!isLoggedIn) {
                        Text(
                            text = "当前尚未登录主账号，请先登录拥有会员权限的账号后再进行凭证导出。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            text = "将导出当前主账号 (${userProfile.nick.ifBlank { userProfile.uin }}) 的鉴权票据。生成的凭证已精简压缩并采用 AES-256-GCM 加密，可供好友或本设备作为独立播放凭据使用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = exportPassword,
                            onValueChange = { exportPassword = it },
                            label = { Text("设置加密密码 (可选)") },
                            placeholder = { Text("留空则采用内置默认密钥") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )

                        if (exportedTokenResult != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "凭证文本 (共 ${exportedTokenResult!!.length} 字符，已自动复制):",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            SelectionContainer {
                                Card(
                                    colors =
                                        CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        ),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = exportedTokenResult!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(10.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (isLoggedIn) {
                    if (exportedTokenResult == null) {
                        TextButton(onClick = {
                            try {
                                val token =
                                    PlaybackCredentialsManager.exportToken(
                                        profile = userProfile,
                                        password = exportPassword.ifBlank { null },
                                    )
                                exportedTokenResult = token
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("PlaybackCredentials", token)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "凭证已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text("生成凭证")
                        }
                    } else {
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("PlaybackCredentials", exportedTokenResult)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "已重新复制到剪贴板", Toast.LENGTH_SHORT).show()
                            showExportDialog = false
                        }) {
                            Text("完成并复制")
                        }
                    }
                } else {
                    TextButton(onClick = { showExportDialog = false }) {
                        Text("确定")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text("导入播放凭证") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "导入后，播放/下载发起的请求将使用该账号的音质权限，而歌单与每日推荐保持使用原有登录账号，互不干扰污染。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = importTokenText,
                        onValueChange = {
                            importTokenText = it
                            importErrorMessage = null
                        },
                        label = { Text("请粘贴播放凭证文本") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = importPassword,
                        onValueChange = {
                            importPassword = it
                            importErrorMessage = null
                        },
                        label = { Text("解密密码 (若导出时设置了密码)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    if (importErrorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = importErrorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cleanText = importTokenText.trim()
                    if (cleanText.isBlank()) {
                        importErrorMessage = "请输入凭证文本"
                        return@TextButton
                    }
                    try {
                        val inspectResult =
                            PlaybackCredentialsManager.inspectToken(
                                tokenText = cleanText,
                                password = importPassword.ifBlank { null },
                            )
                        when (inspectResult) {
                            is PlaybackCredentialsManager.ImportValidationResult.Expired -> {
                                importErrorMessage = "该凭证对应会员已过期，无法导入"
                            }
                            is PlaybackCredentialsManager.ImportValidationResult.NonVip -> {
                                importErrorMessage = "该凭证账号非有效会员账号，无法导入"
                            }
                            is PlaybackCredentialsManager.ImportValidationResult.DowngradeWarning -> {
                                pendingDowngradeResult = inspectResult
                            }
                            is PlaybackCredentialsManager.ImportValidationResult.Success -> {
                                PlaybackCredentialsManager.setCredentials(inspectResult.credentials)
                                showImportDialog = false
                                Toast
                                    .makeText(
                                        context,
                                        "导入成功！已启用账号: ${inspectResult.credentials.nick.ifBlank { inspectResult.credentials.uin }}",
                                        Toast.LENGTH_LONG,
                                    ).show()
                            }
                        }
                    } catch (e: Exception) {
                        importErrorMessage = e.message ?: "导入失败，请检查凭证或密码"
                    }
                }) {
                    Text("确认导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    // 会员级别降级二次确认弹窗
    if (pendingDowngradeResult != null) {
        val warning = pendingDowngradeResult!!
        AlertDialog(
            onDismissRequest = { pendingDowngradeResult = null },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text("会员级别降级提醒") },
            text = {
                Text(
                    "当前登录账号为【${warning.currentTier.displayName}】，导入的凭证为【${warning.importedTier.displayName}】。\n" +
                        "导入后播放权限将按较低凭证生效。是否确认导入？",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        PlaybackCredentialsManager.setCredentials(warning.credentials)
                        pendingDowngradeResult = null
                        showImportDialog = false
                        Toast
                            .makeText(
                                context,
                                "导入成功！已启用账号: ${warning.credentials.nick.ifBlank { warning.credentials.uin }}",
                                Toast.LENGTH_LONG,
                            ).show()
                    },
                ) {
                    Text("仍然导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDowngradeResult = null }) {
                    Text("取消")
                }
            },
        )
    }
}
