package org.melodist.tv.ui.webdav

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import org.melodist.api.WebDavService
import org.melodist.model.WebDavServer
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes

/**
 * WebDAV 服务器配置/添加弹窗
 */
@Composable
fun WebDavServerConfigDialog(
    initialServer: WebDavServer,
    onSave: (WebDavServer) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialServer.name) }
    var url by remember { mutableStateOf(initialServer.url) }
    var username by remember { mutableStateOf(initialServer.username) }
    var password by remember { mutableStateOf(initialServer.password) }
    var rootPath by remember { mutableStateOf(initialServer.rootPath.ifBlank { "/" }) }
    var trustSelfSigned by remember { mutableStateOf(initialServer.trustSelfSigned) }

    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    val webDavService = remember { WebDavService() }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier =
                Modifier
                    .width(680.dp)
                    .clip(MelodistShapes.CardCorner)
                    .background(MelodistColors.ContainerDark)
                    .border(BorderStroke(2.dp, MelodistColors.FocusTeal.copy(alpha = 0.6f)), MelodistShapes.CardCorner)
                    .padding(28.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = if (initialServer.id.isNotBlank()) "编辑 WebDAV 服务器" else "添加 WebDAV 服务器",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MelodistColors.TextPrimary,
                )

                WebDavInputField(
                    label = "名称",
                    value = name,
                    keyboardType = KeyboardType.Text,
                    onValueChange = { name = it },
                )
                WebDavInputField(
                    label = "地址",
                    value = url,
                    keyboardType = KeyboardType.Uri,
                    onValueChange = { url = it },
                )
                WebDavInputField(
                    label = "账号",
                    value = username,
                    keyboardType = KeyboardType.Ascii,
                    onValueChange = { username = it },
                )
                WebDavInputField(
                    label = "密码",
                    value = password,
                    isPassword = true,
                    keyboardType = KeyboardType.Password,
                    onValueChange = { password = it },
                )
                WebDavInputField(
                    label = "路径",
                    value = rootPath,
                    keyboardType = KeyboardType.Uri,
                    onValueChange = { rootPath = it },
                )

                if (testStatus != null) {
                    Text(
                        text = testStatus ?: "",
                        fontSize = 13.sp,
                        color = if (testStatus?.contains("成功") == true) MelodistColors.AccentGreen else Color.Red,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (initialServer.id.isNotBlank()) {
                        WebDavNavButton(
                            icon = Icons.Default.Delete,
                            text = "删除服务",
                            onClick = { onDelete(initialServer.id) },
                        )
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WebDavNavButton(
                            icon = Icons.Default.NetworkCheck,
                            text = if (isTesting) "测试中..." else "测试连接",
                            onClick = {
                                if (url.isBlank()) {
                                    testStatus = "请输入服务器地址"
                                    return@WebDavNavButton
                                }
                                isTesting = true
                                testStatus = "正在测试连接..."
                                scope.launch {
                                    val temp =
                                        initialServer.copy(
                                            name = name,
                                            url = url,
                                            username = username,
                                            password = password,
                                            rootPath = rootPath,
                                            trustSelfSigned = trustSelfSigned,
                                        )
                                    val (ok, msg) = webDavService.testConnection(temp)
                                    testStatus = msg
                                    isTesting = false
                                }
                            },
                        )

                        WebDavNavButton(
                            icon = Icons.Default.Close,
                            text = "取消",
                            onClick = onDismiss,
                        )

                        WebDavNavButton(
                            icon = Icons.Default.Check,
                            text = "保存",
                            isAccent = true,
                            onClick = {
                                val updated =
                                    initialServer.copy(
                                        name = name.ifBlank { "我的 WebDAV" },
                                        url = url,
                                        username = username,
                                        password = password,
                                        rootPath = rootPath.ifBlank { "/" },
                                        trustSelfSigned = trustSelfSigned,
                                    )
                                onSave(updated)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 适配 TV 焦点的文本输入框
 */
@Composable
fun WebDavInputField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(MelodistColors.ContainerDarkSecondary.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = if (isFocused) MelodistColors.FocusTeal else Color.White.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                ).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MelodistColors.TextSecondary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(64.dp),
        )

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = keyboardType,
                    autoCorrectEnabled = false,
                ),
            textStyle =
                TextStyle(
                    color = Color.White,
                    fontSize = 14.sp,
                ),
            cursorBrush = SolidColor(MelodistColors.AccentGreen),
            interactionSource = interactionSource,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 切换 WebDAV 服务器选择弹窗
 */
@Composable
fun WebDavServerSelectDialog(
    servers: List<WebDavServer>,
    activeServerId: String,
    onSelect: (WebDavServer) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier =
                Modifier
                    .width(520.dp)
                    .clip(MelodistShapes.CardCorner)
                    .background(MelodistColors.ContainerDark)
                    .border(BorderStroke(2.dp, MelodistColors.FocusTeal.copy(alpha = 0.6f)), MelodistShapes.CardCorner)
                    .padding(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "切换 WebDAV 服务器",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MelodistColors.TextPrimary,
                )

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(servers) { _, s ->
                        val isCurrent = s.id == activeServerId
                        WebDavNavButton(
                            icon = if (isCurrent) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            text = "${s.name} (${s.url})",
                            isAccent = isCurrent,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onSelect(s) },
                        )
                    }
                }
            }
        }
    }
}
