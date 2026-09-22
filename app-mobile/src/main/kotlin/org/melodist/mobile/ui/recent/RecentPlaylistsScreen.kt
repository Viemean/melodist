package org.melodist.mobile.ui.recent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.melodist.api.RecentHistoryType
import org.melodist.api.RecentPlaylistItem
import org.melodist.data.RecentPlaybackManager
import org.melodist.data.UserLibraryCacheManager
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.model.Playlist

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RecentPlaylistsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playlists by RecentPlaybackManager.recentPlaylistsFlow.collectAsState()
    val library by UserLibraryCacheManager.libraryFlow.collectAsState()
    val isSyncing by RecentPlaybackManager.isSyncingFlow.collectAsState()
    var targetPlaylistForAction by remember { mutableStateOf<RecentPlaylistItem?>(null) }

    val displayPlaylists = remember(playlists) {
        playlists.filterNot { item ->
            val t = item.title
            t.contains("30首") || t.contains("每日30") || t.contains("红心雷达") || t.contains("猜你喜欢")
        }
    }

    val listState = rememberLazyListState()
    val showTopBarTitle by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 200
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AnimatedVisibility(
                        visible = showTopBarTitle,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Text("最近播放的歌单", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier.fillMaxSize(),
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = scaffoldPadding.calculateTopPadding()),
        ) {
            PullToRefreshBox(
                isRefreshing = isSyncing,
                onRefresh = { RecentPlaybackManager.syncFromCloud(force = true, type = RecentHistoryType.Playlist) },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (displayPlaylists.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "暂无最近播放的歌单",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            bottom = contentPadding.calculateBottomPadding() + 16.dp,
                        ),
                    ) {
                        item(key = "recent_playlists_header") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    val headerCoverUrl = displayPlaylists.firstOrNull()?.coverUrl.orEmpty()
                                    AlbumArtImage(
                                        coverUrl = headerCoverUrl,
                                        contentDescription = "最近播放的歌单",
                                        shape = RoundedCornerShape(14.dp),
                                        elevation = 8.dp,
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                                        placeholderIconSize = 48.dp,
                                        placeholderContent = {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(48.dp),
                                            )
                                        },
                                        modifier = Modifier.size(110.dp),
                                    )

                                    Spacer(modifier = Modifier.width(16.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "最近播放的歌单",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Text(
                                            text = "${displayPlaylists.size} 个歌单",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        items(displayPlaylists, key = { it.tid }) { item ->
                            val matchedPlaylist = remember(item, library.playlists) {
                                library.playlists.firstOrNull {
                                    (item.tid > 0L && (it.tid == item.tid || it.dirId == item.tid)) ||
                                        (it.name.isNotBlank() && it.name == item.title)
                                }
                            }
                            val playlistModel = remember(item, matchedPlaylist) {
                                if (matchedPlaylist != null) {
                                    matchedPlaylist.copy(
                                        picUrl = item.coverUrl.ifBlank { matchedPlaylist.picUrl },
                                        songCount = if (item.songCount > 0) item.songCount else matchedPlaylist.songCount,
                                    )
                                } else {
                                    Playlist(
                                        dirId = item.tid,
                                        tid = item.tid,
                                        name = item.title,
                                        picUrl = item.coverUrl,
                                        songCount = item.songCount,
                                        isFav = true,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { onPlaylistClick(playlistModel) },
                                        onLongClick = { targetPlaylistForAction = item },
                                    )
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AlbumArtImage(
                                    coverUrl = item.coverUrl,
                                    contentDescription = item.title,
                                    modifier = Modifier.size(56.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    elevation = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val subtitle = buildString {
                                        if (item.creatorNick.isNotBlank()) {
                                            append("by ${item.creatorNick} · ")
                                        }
                                        append("${item.songCount} 首")
                                        if (item.listenCnt > 1) {
                                            append(" · 听过 ${item.listenCnt} 次")
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = { targetPlaylistForAction = item }) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreVert,
                                        contentDescription = "更多操作",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        targetPlaylistForAction?.let { sheetPlaylist ->
            ModalBottomSheet(
                onDismissRequest = { targetPlaylistForAction = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AlbumArtImage(
                            coverUrl = sheetPlaylist.coverUrl,
                            contentDescription = sheetPlaylist.title,
                            modifier = Modifier.size(52.dp),
                            shape = RoundedCornerShape(8.dp),
                            elevation = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = sheetPlaylist.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (sheetPlaylist.creatorNick.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "by ${sheetPlaylist.creatorNick} · ${sheetPlaylist.songCount} 首",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                RecentPlaybackManager.removePlaylist(sheetPlaylist)
                                targetPlaylistForAction = null
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "从最近播放中移除",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
