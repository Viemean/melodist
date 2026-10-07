package org.melodist.mobile.ui.player

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.api.getSimilarSongs
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
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val playlist by PlaybackManager.playlist.collectAsState()
    val currentSong by PlaybackManager.currentSong.collectAsState()
    val isRadioMode by PlaybackManager.isRadioMode.collectAsState()
    val paginationSource by PlaybackManager.paginationSource.collectAsState()
    val isLoadingMoreForQueue by PlaybackManager.isLoadingMoreForQueue.collectAsState()

    var isRecommendingSimilar by remember { mutableStateOf(false) }

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

    val queueNestedScrollConnection =
        remember {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset =
                    if (available.y < 0f) {
                        // 消费未处理的向上滚动增量
                        Offset(0f, available.y)
                    } else {
                        Offset.Zero
                    }
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
                    .then(
                        if (isLandscape) {
                            Modifier
                                .fillMaxHeight()
                                .statusBarsPadding()
                        } else {
                            Modifier.fillMaxHeight(0.75f)
                        },
                    ).navigationBarsPadding(),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = if (isLandscape) 4.dp else 8.dp),
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val targetSong = currentSong
                    val isSimilarActive by PlaybackManager.isSimilarRecommendActive.collectAsState()
                    if (targetSong != null && !targetSong.isLocal && !targetSong.isWebDav && (targetSong.songId > 0L || targetSong.songMid.isNotBlank())) {
                        val containerColor =
                            if (isSimilarActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                            }
                        val contentColor =
                            if (isSimilarActive) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            }

                        Surface(
                            onClick = {
                                if (isRecommendingSimilar) return@Surface
                                if (isSimilarActive) {
                                    val restored = PlaybackManager.restorePlaylistFromSimilar()
                                    if (restored) {
                                        Toast.makeText(context, "已恢复原播放队列", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    isRecommendingSimilar = true
                                    coroutineScope.launch {
                                        try {
                                            val success = PlaybackManager.activateSimilarRecommend(targetSong, openQueue = false)
                                            if (success) {
                                                Toast.makeText(context, "已切换为【${targetSong.name}】的相似推荐队列", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "暂无相似推荐歌曲", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "获取相似推荐失败", Toast.LENGTH_SHORT).show()
                                        } finally {
                                            isRecommendingSimilar = false
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = containerColor,
                            modifier = Modifier.height(32.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (isRecommendingSimilar) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 2.dp,
                                        color = contentColor,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = "相似推荐",
                                        tint = contentColor,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                                Text(
                                    text = "相似推荐",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSimilarActive) FontWeight.Bold else FontWeight.Medium,
                                    color = contentColor,
                                )
                            }
                        }
                    }

                    if (!isRadioMode && playlist.isNotEmpty()) {
                        IconButton(
                            onClick = { showClearConfirmDialog = true },
                            modifier = Modifier.size(36.dp),
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
            }


            HorizontalDivider(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = if (isLandscape) 2.dp else 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            )

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(queueNestedScrollConnection),
            ) {
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
