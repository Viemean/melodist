package org.melodist.mobile.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.melodist.api.AddSongResult
import org.melodist.api.MusicApiService
import org.melodist.api.addSongsToPlaylist
import org.melodist.data.UserLibraryCacheManager
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager

/**
 * 通用添加到自建歌单选择器弹窗。
 * 支持传入单曲或多歌曲列表，统一负责选择自建歌单、批量提交网络、
 * 乐观增量更新用户收藏数据、喜欢状态同步以及反馈提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistBottomSheet(
    songs: List<Song>,
    onDismissRequest: () -> Unit,
    onSuccess: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiService = remember { MusicApiService() }

    val userLibraryData by UserLibraryCacheManager.libraryFlow.collectAsState()
    val createdPlaylists =
        remember(userLibraryData) {
            userLibraryData.playlists.filter { it.isCreated || it.isMyFavorite }
        }

    val onlineSongs =
        remember(songs) {
            songs.filter { it.songMid.isNotBlank() && !it.isLocal && !it.isWebDav }
        }

    var isOperating by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = {
            if (!isOperating) onDismissRequest()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
        ) {
            Text(
                text = "选择要添加到的歌单",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            if (createdPlaylists.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无自建歌单，请先在我的收藏页面创建歌单",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                ) {
                    items(createdPlaylists, key = { it.dirId }) { playlist ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isOperating) {
                                        if (onlineSongs.isEmpty()) {
                                            Toast.makeText(context, "所选歌曲中没有可添加的在线音乐", Toast.LENGTH_SHORT).show()
                                            return@clickable
                                        }
                                        isOperating = true
                                        scope.launch {
                                            val result =
                                                apiService.addSongsToPlaylist(
                                                    dirId = playlist.dirId,
                                                    songs = onlineSongs,
                                                )
                                            isOperating = false
                                            when (result) {
                                                AddSongResult.Success -> {
                                                    val msg =
                                                        if (onlineSongs.size == 1) {
                                                            "已添加到《${playlist.name}》"
                                                        } else {
                                                            "已将 ${onlineSongs.size} 首歌曲添加到《${playlist.name}》"
                                                        }
                                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                    UserLibraryCacheManager.onSongsAddedToPlaylist(playlist.dirId, onlineSongs)
                                                    if (playlist.dirId == 201L) {
                                                        PlaybackManager.addFavoriteSongMids(onlineSongs.map { it.songMid })
                                                    }
                                                    onDismissRequest()
                                                    onSuccess?.invoke()
                                                }
                                                AddSongResult.AlreadyExists -> {
                                                    val msg =
                                                        if (onlineSongs.size == 1) {
                                                            "歌单中已存在该歌曲"
                                                        } else {
                                                            "歌单中已存在所选歌曲"
                                                        }
                                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                    onDismissRequest()
                                                    onSuccess?.invoke()
                                                }
                                                AddSongResult.Failed -> {
                                                    Toast.makeText(context, "添加到歌单失败", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }.padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AlbumArtImage(
                                coverUrl = playlist.thumbnailPicUrl,
                                contentDescription = playlist.name,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(44.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${playlist.songCount} 首",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
