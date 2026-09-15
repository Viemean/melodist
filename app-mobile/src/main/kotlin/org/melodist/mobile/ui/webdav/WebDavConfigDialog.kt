package org.melodist.mobile.ui.webdav

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.melodist.api.WebDavService
import org.melodist.model.WebDavServer

@Composable
fun WebDavConfigDialog(
    initialServer: WebDavServer,
    onDismiss: () -> Unit,
    onSave: (WebDavServer) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val webDavService = remember { WebDavService() }

    var name by remember { mutableStateOf(initialServer.name) }
    var url by remember { mutableStateOf(initialServer.url) }
    var username by remember { mutableStateOf(initialServer.username) }
    var password by remember { mutableStateOf(initialServer.password) }
    var rootPath by remember { mutableStateOf(initialServer.rootPath.ifBlank { "/" }) }
    var trustSelfSigned by remember { mutableStateOf(initialServer.trustSelfSigned) }

    var isTesting by remember { mutableStateOf(false) }
    var testResultMsg by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialServer.id.isNotBlank()) "编辑 WebDAV 服务器" else "添加 WebDAV 服务器")
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("服务器名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("服务器地址 (如 http://192.168.1.5:5005)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名 (可选)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码 (可选)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = rootPath,
                    onValueChange = { rootPath = it },
                    label = { Text("根路径 (默认 /)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("信任自签名证书", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = trustSelfSigned,
                        onCheckedChange = { trustSelfSigned = it },
                    )
                }

                if (testResultMsg != null) {
                    Text(
                        text = testResultMsg.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val temp =
                            initialServer.copy(
                                name = name,
                                url = url.trim(),
                                username = username.trim(),
                                password = password,
                                rootPath = rootPath.trim().ifBlank { "/" },
                                trustSelfSigned = trustSelfSigned,
                            )
                        isTesting = true
                        testResultMsg = null
                        scope.launch {
                            try {
                                val (ok, errorMsg) = webDavService.testConnection(temp)
                                testSuccess = ok
                                testResultMsg = if (ok) "连接成功" else "连接失败: $errorMsg"
                            } catch (e: Exception) {
                                testSuccess = false
                                testResultMsg = "连接异常: ${e.message}"
                            } finally {
                                isTesting = false
                            }
                        }
                    },
                    enabled = url.isNotBlank() && !isTesting,
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("测试连接")
                    }
                }

                Button(
                    onClick = {
                        if (url.isBlank()) {
                            Toast.makeText(context, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val updated =
                            initialServer.copy(
                                id = initialServer.id.ifBlank { "server_${System.currentTimeMillis()}" },
                                name = name.ifBlank { url },
                                url = url.trim(),
                                username = username.trim(),
                                password = password,
                                rootPath = rootPath.trim().ifBlank { "/" },
                                trustSelfSigned = trustSelfSigned,
                            )
                        onSave(updated)
                    },
                    enabled = url.isNotBlank(),
                ) {
                    Text("保存")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
