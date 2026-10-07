package org.melodist.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.getSimilarSongs
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimilarSongsBottomSheet(
    song: Song,
    onDismissRequest: () -> Unit,
) {
    val targetSong = remember { song }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val apiService = remember { MusicApiService() }

    val similarSongs = remember { mutableStateListOf<Song>() }
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    fun loadSimilarSongs() {
        isLoading = true
        isError = false
        scope.launch {
            try {
                val songs =
                    withContext(Dispatchers.IO) {
                        apiService.getSimilarSongs(
                            songId = targetSong.songId,
                            songMid = targetSong.songMid,
                        )
                    }
                similarSongs.clear()
                similarSongs.addAll(songs)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                isError = true
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(targetSong.songMid, targetSong.songId) {
        loadSimilarSongs()
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val maxSheetHeight = (configuration.screenHeightDp * 0.72f).dp

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight)
                    .navigationBarsPadding(),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "相似推荐",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (similarSongs.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "(${similarSongs.size})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${targetSong.name} · ${targetSong.singer.ifBlank { "未知歌手" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (similarSongs.isNotEmpty() && !isLoading) {
                    FilledTonalButton(
                        onClick = {
                            val playlist = listOf(targetSong) + similarSongs.filter { it.songMid != targetSong.songMid }
                            PlaybackManager.setPlaylist(playlist, startIndex = 0)
                            onDismissRequest()
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "播放全部",
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "播放全部",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )

            when {
                isLoading -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                isError -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "获取相似推荐失败",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = { loadSimilarSongs() },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("重试")
                            }
                        }
                    }
                }

                similarSongs.isEmpty() -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "暂无相似推荐歌曲",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    CommonSongList(
                        songs = similarSongs,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    )
                }
            }
        }
    }
}
