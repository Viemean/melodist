package org.melodist.mobile.ui.settings.sections

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.melodist.data.AppSettings
import org.melodist.data.AppSettingsManager
import org.melodist.mobile.mcp.McpServerManager
import org.melodist.mobile.ui.components.SettingsClickableRow
import org.melodist.mobile.ui.components.SettingsGroupCard
import org.melodist.mobile.ui.components.SettingsGroupTitle
import org.melodist.mobile.ui.components.SettingsSwitchRow

@Composable
fun SettingsMcpSection(
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    val isRunning by McpServerManager.isServerRunning.collectAsState()
    val serverUrl by McpServerManager.serverUrl.collectAsState()

    var showRegenerateTokenDialog by remember { mutableStateOf(false) }
    var showConnectionGuideDialog by remember { mutableStateOf(false) }

    val fullUrl =
        if (serverUrl.isNotBlank() && settings.mcpAuthToken.isNotBlank()) {
            "$serverUrl/sse?token=${settings.mcpAuthToken}"
        } else {
            serverUrl
        }

    val statusSubtitle =
        when {
            !settings.enableMcpServer -> "允许外部服务控制检索音乐信息"
            isRunning -> "运行中: $serverUrl"
            else -> "正在启动服务..."
        }

    Column(modifier = modifier) {
        SettingsGroupTitle(title = "MCP服务")
        SettingsGroupCard {
            SettingsSwitchRow(
                icon = Icons.Rounded.Hub,
                title = "启用局域网 MCP 服务",
                subtitle = statusSubtitle,
                checked = settings.enableMcpServer,
                onCheckedChange = { AppSettingsManager.setEnableMcpServer(it) },
            )

            if (settings.enableMcpServer) {
                SettingsClickableRow(
                    icon = Icons.Rounded.Link,
                    title = "MCP 连接地址",
                    subtitle =
                        if (isRunning) {
                            "$fullUrl\n(点击查看连接配置指南)"
                        } else {
                            "服务未运行"
                        },
                    onClick = {
                        if (isRunning) {
                            showConnectionGuideDialog = true
                        } else {
                            Toast.makeText(context, "服务未运行，暂无连接地址", Toast.LENGTH_SHORT).show()
                        }
                    },
                )

                SettingsClickableRow(
                    icon = Icons.Rounded.VpnKey,
                    title = "鉴权 Token",
                    subtitle =
                        if (settings.mcpAuthToken.isNotBlank()) {
                            "••••••••${settings.mcpAuthToken.takeLast(4)} (点击重新生成)"
                        } else {
                            "未设置 (点击生成)"
                        },
                    onClick = {
                        showRegenerateTokenDialog = true
                    },
                )

                SettingsSwitchRow(
                    icon = Icons.Rounded.DeleteForever,
                    title = "允许删除内容",
                    subtitle = "开启后允许通过MCP服务取消收藏歌曲",
                    checked = settings.mcpAllowDelete,
                    onCheckedChange = { AppSettingsManager.setMcpAllowDelete(it) },
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showConnectionGuideDialog) {
        val baseUrl = if (serverUrl.isNotBlank()) "$serverUrl/sse" else ""
        val token = settings.mcpAuthToken
        val authHeaderValue = "Bearer $token"

        AlertDialog(
            onDismissRequest = { showConnectionGuideDialog = false },
            title = { Text(text = "MCP 连接配置指南") },
            text = {
                Column {
                    Text(
                        text = "协议类型：在客户端选择 SSE 协议\n",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Text(
                        text = "方式一：完整 URL 直连",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = fullUrl.ifBlank { "服务未运行" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            if (fullUrl.isNotBlank()) {
                                clipboard.setPrimaryClip(ClipData.newPlainText("fullUrl", fullUrl))
                                Toast.makeText(context, "已复制完整 URL", Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) {
                        Text(text = "复制完整 URL")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "方式二：请求头鉴权",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "服务器 URL: $baseUrl",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "请求头: Authorization = $authHeaderValue",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(
                            onClick = {
                                if (baseUrl.isNotBlank()) {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("baseUrl", baseUrl))
                                    Toast.makeText(context, "已复制服务器 URL", Toast.LENGTH_SHORT).show()
                                }
                            },
                        ) {
                            Text(text = "复制基础 URL")
                        }
                        TextButton(
                            onClick = {
                                if (token.isNotBlank()) {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("authHeader", authHeaderValue))
                                    Toast.makeText(context, "已复制请求头值", Toast.LENGTH_SHORT).show()
                                }
                            },
                        ) {
                            Text(text = "复制请求头")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val loopbackUrl =
                        if (settings.mcpAuthToken.isNotBlank()) {
                            "http://127.0.0.1:${settings.mcpServerPort}/sse?token=${settings.mcpAuthToken}"
                        } else {
                            "http://127.0.0.1:${settings.mcpServerPort}/sse"
                        }
                    Text(
                        text = "方式三：手机本机直连",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = loopbackUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            clipboard.setPrimaryClip(ClipData.newPlainText("loopbackUrl", loopbackUrl))
                            Toast.makeText(context, "已复制本地回环 URL", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text(text = "复制本机回环 URL")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConnectionGuideDialog = false }) {
                    Text(text = "关闭")
                }
            },
        )
    }

    if (showRegenerateTokenDialog) {
        AlertDialog(
            onDismissRequest = { showRegenerateTokenDialog = false },
            title = { Text(text = "重新生成 Token") },
            text = { Text(text = "是否重新生成 Token？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        AppSettingsManager.regenerateMcpAuthToken()
                        showRegenerateTokenDialog = false
                        Toast.makeText(context, "Token 已更新", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text(text = "确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateTokenDialog = false }) {
                    Text(text = "取消")
                }
            },
        )
    }
}
