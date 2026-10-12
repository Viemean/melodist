package org.melodist.mobile.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.api.createPlaylist
import org.melodist.data.UserLibraryCacheManager

/**
 * 通用新建歌单对话框。
 *
 * @param onDismissRequest 关闭对话框时的回调
 * @param title 对话框标题文本
 * @param confirmButtonText 确认按钮文本
 * @param onCreated 创建成功后的回调函数，参数为新建歌单的 dirId 与名称，返回值表示是否关闭对话框
 */
@Composable
fun CreatePlaylistDialog(
    onDismissRequest: () -> Unit,
    title: String = "新建歌单",
    confirmButtonText: String = "创建",
    onCreated: (suspend (dirId: Long, name: String) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiService = remember { MusicApiService() }

    var newPlaylistName by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (!isCreating) {
                onDismissRequest()
            }
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("歌单名称") },
                    placeholder = { Text("请输入歌单名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isCreating,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = newPlaylistName.isNotBlank() && !isCreating,
                onClick = {
                    val name = newPlaylistName.trim()
                    isCreating = true
                    scope.launch {
                        val (success, dirId, msg) = apiService.createPlaylist(name)
                        if (success) {
                            UserLibraryCacheManager.loadLibrary(apiService, forceRefresh = true)
                            val shouldDismiss =
                                if (onCreated != null) {
                                    onCreated(dirId, name)
                                } else {
                                    Toast.makeText(context, "歌单创建成功", Toast.LENGTH_SHORT).show()
                                    true
                                }
                            isCreating = false
                            if (shouldDismiss) {
                                onDismissRequest()
                            }
                        } else {
                            isCreating = false
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            ) {
                Text(confirmButtonText)
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isCreating,
                onClick = onDismissRequest,
            ) {
                Text("取消")
            }
        },
    )
}
