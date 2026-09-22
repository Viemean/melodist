package org.melodist.mobile.ui.recent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
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
import org.melodist.api.RecentAlbumItem
import org.melodist.api.RecentHistoryType
import org.melodist.data.RecentPlaybackManager
import org.melodist.mobile.ui.components.AlbumArtImage

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RecentAlbumsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onAlbumClick: (albumMid: String, albumName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val albums by RecentPlaybackManager.recentAlbumsFlow.collectAsState()
    val isSyncing by RecentPlaybackManager.isSyncingFlow.collectAsState()
    var targetAlbumForAction by remember { mutableStateOf<RecentAlbumItem?>(null) }

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
                        Text("最近播放的专辑", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = scaffoldPadding.calculateTopPadding()),
        ) {
            PullToRefreshBox(
                isRefreshing = isSyncing,
                onRefresh = { RecentPlaybackManager.syncFromCloud(force = true, type = RecentHistoryType.Album) },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (albums.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Rounded.Album,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "暂无最近播放的专辑",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding =
                            PaddingValues(
                                bottom = contentPadding.calculateBottomPadding() + 16.dp,
                            ),
                    ) {
                        item(key = "recent_albums_header") {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    val headerCoverUrl = albums.firstOrNull()?.coverUrl.orEmpty()
                                    AlbumArtImage(
                                        coverUrl = headerCoverUrl,
                                        contentDescription = "最近播放的专辑",
                                        shape = RoundedCornerShape(14.dp),
                                        elevation = 8.dp,
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                                        placeholderIconSize = 48.dp,
                                        placeholderContent = {
                                            Icon(
                                                imageVector = Icons.Rounded.Album,
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
                                            text = "最近播放的专辑",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Text(
                                            text = "${albums.size} 张专辑",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        items(albums, key = { it.albumMid.ifBlank { it.albumId.toString() } }) { album ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { onAlbumClick(album.albumMid, album.albumName) },
                                            onLongClick = { targetAlbumForAction = album },
                                        ).padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AlbumArtImage(
                                    coverUrl = album.coverUrl,
                                    contentDescription = album.albumName,
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
                                        text = album.albumName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val subtitle =
                                        buildString {
                                            if (album.singerName.isNotBlank()) {
                                                append(album.singerName)
                                            }
                                            if (album.songCount > 0) {
                                                if (isNotEmpty()) append(" · ")
                                                append("${album.songCount}首")
                                            }
                                            if (album.listenCnt > 1) {
                                                if (isNotEmpty()) append(" · ")
                                                append("听过 ${album.listenCnt} 次")
                                            }
                                        }
                                    if (subtitle.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = subtitle,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                IconButton(onClick = { targetAlbumForAction = album }) {
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

        targetAlbumForAction?.let { sheetAlbum ->
            ModalBottomSheet(
                onDismissRequest = { targetAlbumForAction = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AlbumArtImage(
                            coverUrl = sheetAlbum.coverUrl,
                            contentDescription = sheetAlbum.albumName,
                            modifier = Modifier.size(52.dp),
                            shape = RoundedCornerShape(8.dp),
                            elevation = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = sheetAlbum.albumName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (sheetAlbum.singerName.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = sheetAlbum.singerName,
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
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    RecentPlaybackManager.removeAlbum(sheetAlbum)
                                    targetAlbumForAction = null
                                }.padding(horizontal = 20.dp, vertical = 14.dp),
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
