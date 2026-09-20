package org.melodist.mobile.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.melodist.mobile.ui.components.CommonSongList
import org.melodist.mobile.ui.components.SongActionSheet
import org.melodist.mobile.ui.components.SongListDeleteType
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerQueueBottomSheet(
    onDismissRequest: () -> Unit,
    onNavigate: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val playlist by PlaybackManager.playlist.collectAsState()
    val currentSong by PlaybackManager.currentSong.collectAsState()
    val isRadioMode by PlaybackManager.isRadioMode.collectAsState()
    val paginationSource by PlaybackManager.paginationSource.collectAsState()
    val isLoadingMoreForQueue by PlaybackManager.isLoadingMoreForQueue.collectAsState()

    val activeIndex =
        remember(playlist, currentSong) {
            val found = playlist.indexOfFirst { it.songMid == currentSong?.songMid }
            if (found >= 0) found else 0
        }

    val displayPlaylist =
        remember(playlist, isRadioMode, activeIndex) {
            if (isRadioMode) {
                if (playlist.isEmpty()) {
                    emptyList()
                } else {
                    playlist.take(activeIndex + 2)
                }
            } else {
                playlist
            }
        }

    val listState = rememberLazyListState()
    var actionSongWithIndex by remember { mutableStateOf<Pair<Song, Int>?>(null) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    val shouldLoadMore by remember {
        derivedStateOf {
            if (isRadioMode) return@derivedStateOf false
            val totalCount = listState.layoutInfo.totalItemsCount
            val lastVisibleIndex =
                listState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: 0
            totalCount > 0 && lastVisibleIndex >= totalCount - 3
        }
    }

    LaunchedEffect(shouldLoadMore, paginationSource) {
        if (shouldLoadMore && paginationSource?.hasMore == true && !isLoadingMoreForQueue && paginationSource?.isLoadingMore == false) {
            PlaybackManager.loadMoreForQueue()
        }
    }

    LaunchedEffect(Unit) {
        if (activeIndex in displayPlaylist.indices) {
            listState.scrollToItem(activeIndex)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
                    .navigationBarsPadding(),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isRadioMode) "猜你喜欢电台" else "播放队列",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "(${displayPlaylist.size})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!isRadioMode && playlist.isNotEmpty()) {
                    IconButton(
                        onClick = { showClearConfirmDialog = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "清空队列",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                CommonSongList(
                    songs = displayPlaylist,
                    state = listState,
                    deleteType = if (isRadioMode) SongListDeleteType.None else SongListDeleteType.PlayerQueue,
                    onSongClick = { songs, index ->
                        PlaybackManager.playSong(songs[index])
                    },
                    onMoreClick = { song ->
                        val idx = playlist.indexOfFirst { it.songMid == song.songMid }
                        actionSongWithIndex = Pair(song, idx)
                    },
                    footerItems = {
                        if (!isRadioMode && (isLoadingMoreForQueue || paginationSource?.isLoadingMore == true)) {
                            item(key = "queue_loading_more_indicator") {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }
                    },
                    emptyContent = {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (isRadioMode) "电台曲目加载中" else "播放队列为空",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        }
    }

    actionSongWithIndex?.let { (song, index) ->
        SongActionSheet(
            song = song,
            onDismissRequest = { actionSongWithIndex = null },
            isFromPlayer = true,
            onNavigate = {
                actionSongWithIndex = null
                onDismissRequest()
                onNavigate?.invoke()
            },
            onRemoveFromQueue = {
                if (index != -1) {
                    PlaybackManager.removeFromPlaylist(index)
                }
            },
        )
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("清空播放队列") },
            text = { Text("确定要清空当前的播放队列吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        PlaybackManager.clearPlaylist()
                        showClearConfirmDialog = false
                    },
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirmDialog = false },
                ) {
                    Text("取消")
                }
            },
        )
    }
}
