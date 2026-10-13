package org.melodist.mobile.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.melodist.model.Song
import org.melodist.playback.ListenTogetherManager

/**
 * 一起听房间状态与交互底部弹窗。
 *
 * @param song 当前播放或目标曲目
 * @param onDismissRequest 弹窗收起回调
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ListenTogetherBottomSheet(
    song: Song,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val currentRoom by ListenTogetherManager.currentRoom.collectAsState()
    val isJoining by ListenTogetherManager.isJoining.collectAsState()
    val spaceStats by ListenTogetherManager.spaceStats.collectAsState()

    var isCreatingRoom by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
        ) {
            // 头部标题与当前房间状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Headphones,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = "一起听",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (currentRoom != null) {
                    FilledTonalButton(
                        onClick = {
                            ListenTogetherManager.leaveRoom()
                            Toast.makeText(context, "已退出一起听", Toast.LENGTH_SHORT).show()
                        },
                        colors =
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("退出房间", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 当前正在播放的曲目概览
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumArtImage(
                        coverUrl = song.thumbnailCoverUrl,
                        candidates = song.thumbnailCandidates,
                        songMid = song.songMid,
                        contentDescription = song.name,
                        shape = RoundedCornerShape(10.dp),
                        elevation = 2.dp,
                        placeholderIconSize = 22.dp,
                        modifier = Modifier.size(48.dp),
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = song.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val subtitleText =
                            if (song.album.isNotBlank()) {
                                "${song.singer.ifBlank { "未知歌手" }} • ${song.album}"
                            } else {
                                song.singer.ifBlank { "未知歌手" }
                            }
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 房间连接状态提示
            val activeRoom = currentRoom
            if (activeRoom != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    copyToClipboard(context, activeRoom.showId)
                                    Toast.makeText(context, "已复制房间号: ${activeRoom.showId}", Toast.LENGTH_SHORT).show()
                                },
                            ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "已连接一起听房间",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "房间号: ${activeRoom.showId} · ${activeRoom.usersCount} 人在线同步",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val stats = spaceStats
                if (stats != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = "与好友一起听",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TogetherStatItem(
                                    value = stats.listenMinutes.toString(),
                                    label = "一起听歌(分钟)",
                                )
                                TogetherStatItem(
                                    value = stats.listenSongsCount.toString(),
                                    label = "一起听过歌曲",
                                )
                                TogetherStatItem(
                                    value = stats.favoriteSongsCount.toString(),
                                    label = "共同收藏歌曲",
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "分享链接给好友或粘贴链接加入，即可双端实时同步播放进度与歌单。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 底部操作按钮组：创建房间 / 复制链接、输入链接
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 创建房间 / 复制链接按钮
                FilledTonalButton(
                    onClick = {
                        coroutineScope.launch {
                            val room = currentRoom
                            if (room != null) {
                                copyToClipboard(context, room.shareUrl)
                                Toast.makeText(context, "已复制一起听链接", Toast.LENGTH_SHORT).show()
                            } else {
                                isCreatingRoom = true
                                val result = ListenTogetherManager.startRoom(song)
                                isCreatingRoom = false
                                result
                                    .onSuccess { newRoom ->
                                        copyToClipboard(context, newRoom.shareUrl)
                                        Toast.makeText(context, "已创建房间并复制链接", Toast.LENGTH_SHORT).show()
                                    }.onFailure {
                                        Toast.makeText(context, "创建房间失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                    }
                            }
                        }
                    },
                    enabled = !isCreatingRoom,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (isCreatingRoom) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("创建中...")
                    } else if (currentRoom != null) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("复制链接")
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("创建房间")
                    }
                }

                // 输入链接按钮
                Button(
                    onClick = {
                        showJoinDialog = true
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("输入链接")
                }
            }
        }
    }

    var isResolvingUrl by remember { mutableStateOf(false) }

    if (showJoinDialog) {
        JoinRoomDialog(
            isJoining = isJoining || isResolvingUrl,
            onDismissRequest = {
                if (!isResolvingUrl && !isJoining) {
                    showJoinDialog = false
                }
            },
            onConfirmJoin = { inputUrl ->
                coroutineScope.launch {
                    isResolvingUrl = true
                    try {
                        val (showId, encUin, songId) = ListenTogetherManager.resolveRoomTarget(inputUrl)
                        if (showId.isBlank()) {
                            Toast.makeText(context, "未识别到有效的一起听链接或房间号", Toast.LENGTH_SHORT).show()
                        } else if (ListenTogetherManager.isSelfCreatedRoom(showId, encUin)) {
                            Toast.makeText(context, "请不要输入自己创建的房间", Toast.LENGTH_SHORT).show()
                        } else {
                            val result = ListenTogetherManager.joinRoom(showId, encUin, songId)
                            result
                                .onSuccess {
                                    Toast.makeText(context, "成功加入一起听", Toast.LENGTH_SHORT).show()
                                    showJoinDialog = false
                                    onDismissRequest()
                                }.onFailure {
                                    Toast.makeText(context, "加入失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                        }
                    } finally {
                        isResolvingUrl = false
                    }
                }
            },
        )
    }
}

/**
 * 手动输入或粘贴一起听链接加入对话框。
 */
@Composable
private fun JoinRoomDialog(
    isJoining: Boolean,
    onDismissRequest: () -> Unit,
    onConfirmJoin: (String) -> Unit,
) {
    val context = LocalContext.current
    var inputUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text("输入一起听链接")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "粘贴好友分享的一起听落地页链接、短链或房间号：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = inputUrl,
                    onValueChange = { inputUrl = it },
                    placeholder = { Text("落地页链接 / 短链 / 房间号") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = false,
                    maxLines = 3,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val text =
                                    clip
                                        .getItemAt(0)
                                        .text
                                        ?.toString()
                                        .orEmpty()
                                if (text.isNotBlank()) {
                                    inputUrl = text
                                }
                            }
                        },
                    ) {
                        Text("从剪贴板粘贴")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmJoin(inputUrl.trim()) },
                enabled = inputUrl.isNotBlank() && !isJoining,
            ) {
                if (isJoining) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("加入")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                enabled = !isJoining,
            ) {
                Text("取消")
            }
        },
    )
}

/**
 * 写入剪贴板。
 */
private fun copyToClipboard(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("ListenTogetherLink", text)
    clipboard.setPrimaryClip(clip)
}

/**
 * 静态解析一起听分享链接或原始房间号。
 *
 * @return Triple(showId, encUin, songId)
 */
fun parseListenTogetherUrl(rawText: String): Triple<String, String, Long> {
    if (rawText.isBlank()) return Triple("", "", 0L)

    var showId = ""
    var encUin = ""
    var songId = 0L

    try {
        if (rawText.contains("http://") || rawText.contains("https://")) {
            val uri = Uri.parse(rawText)
            showId = uri.getQueryParameter("showid").orEmpty()
            encUin = uri.getQueryParameter("share_encrypt_uin")?.ifBlank { null }
                ?: uri.getQueryParameter("hosteuin").orEmpty()
            songId = uri.getQueryParameter("songid")?.toLongOrNull() ?: 0L
        }

        if (showId.isBlank()) {
            val match = Regex("LUGC[0-9a-zA-Z]+").find(rawText)
            if (match != null) {
                showId = match.value
            }
        }
    } catch (_: Exception) {
    }

    return Triple(showId, encUin, songId)
}

/**
 * 一起听好友空间统计数据项。
 */
@Composable
private fun TogetherStatItem(
    value: String,
    label: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
