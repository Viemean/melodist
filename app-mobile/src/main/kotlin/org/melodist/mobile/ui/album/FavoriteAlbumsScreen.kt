package org.melodist.mobile.ui.album

import android.widget.Toast
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
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.data.UserLibraryCacheManager
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.model.Album

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FavoriteAlbumsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onAlbumClick: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val apiService = remember { MusicApiService() }
    val libraryData by UserLibraryCacheManager.libraryFlow.collectAsState()
    val isLoading by UserLibraryCacheManager.isLoadingFlow.collectAsState()
    val albums = libraryData.favoriteAlbums
    var isRefreshing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var targetAlbumForAction by remember { mutableStateOf<Album?>(null) }
    var showUnfavoriteConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(UserSession.isLoggedIn) {
        if (UserSession.isLoggedIn && albums.isEmpty()) {
            UserLibraryCacheManager.loadLibrary(apiService, forceRefresh = false)
        }
    }

    val onRefresh: () -> Unit = {
        if (UserSession.isLoggedIn) {
            isRefreshing = true
            scope.launch {
                try {
                    UserLibraryCacheManager.loadLibrary(apiService, forceRefresh = true)
                } finally {
                    isRefreshing = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("收藏的专辑", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (isLoading && albums.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (albums.isEmpty()) {
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
                                text = "暂无收藏的专辑",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding =
                            PaddingValues(
                                bottom = contentPadding.calculateBottomPadding() + 16.dp,
                            ),
                    ) {
                        item(key = "favorite_albums_header") {
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
                                    val headerCoverUrl =
                                        albums
                                            .firstOrNull()
                                            ?.let {
                                                it.coverUrl.ifBlank {
                                                    if (it.mid.isNotBlank()) MusicApiService.getAlbumCoverUrl(it.mid) else ""
                                                }
                                            }.orEmpty()

                                    AlbumArtImage(
                                        coverUrl = headerCoverUrl,
                                        contentDescription = "收藏的专辑",
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
                                            text = "收藏的专辑",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Text(
                                            text = "${albums.size}张专辑",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        items(albums, key = { it.mid.ifBlank { it.id.toString() } }) { album ->
                            val coverUrl =
                                remember(album.mid, album.coverUrl) {
                                    album.coverUrl.ifBlank {
                                        if (album.mid.isNotBlank()) MusicApiService.getAlbumCoverUrl(album.mid) else ""
                                    }
                                }

                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { onAlbumClick(album) },
                                            onLongClick = {
                                                targetAlbumForAction = album
                                            },
                                        )
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AlbumArtImage(
                                    coverUrl = coverUrl,
                                    contentDescription = album.name,
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
                                        text = album.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val subtitle =
                                        buildString {
                                            if (album.artist.isNotBlank()) {
                                                append(album.artist)
                                            }
                                            if (album.songCount > 0) {
                                                if (isNotEmpty()) append(" · ")
                                                append("${album.songCount}首")
                                            }
                                        }
                                    if (subtitle.isNotBlank()) {
                                        Text(
                                            text = subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (targetAlbumForAction != null && !showUnfavoriteConfirmDialog) {
            val sheetAlbum = targetAlbumForAction!!
            val sheetCoverUrl =
                remember(sheetAlbum.mid, sheetAlbum.coverUrl) {
                    sheetAlbum.coverUrl.ifBlank {
                        if (sheetAlbum.mid.isNotBlank()) MusicApiService.getAlbumCoverUrl(sheetAlbum.mid) else ""
                    }
                }
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
                            coverUrl = sheetCoverUrl,
                            contentDescription = sheetAlbum.name,
                            modifier = Modifier.size(52.dp),
                            shape = RoundedCornerShape(8.dp),
                            elevation = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = sheetAlbum.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (sheetAlbum.artist.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = sheetAlbum.artist,
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
                                    showUnfavoriteConfirmDialog = true
                                }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FavoriteBorder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "取消收藏",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "从收藏的专辑列表中移除",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (showUnfavoriteConfirmDialog && targetAlbumForAction != null) {
            val albumToUnfav = targetAlbumForAction!!
            AlertDialog(
                onDismissRequest = {
                    showUnfavoriteConfirmDialog = false
                    targetAlbumForAction = null
                },
                title = { Text("取消收藏专辑") },
                text = { Text("确定要取消收藏专辑《${albumToUnfav.name}》吗？") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showUnfavoriteConfirmDialog = false
                            targetAlbumForAction = null
                            UserLibraryCacheManager.onAlbumFavoriteToggled(albumToUnfav, isFavorited = false)
                            Toast.makeText(context, "已取消收藏专辑", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text("确定取消", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showUnfavoriteConfirmDialog = false
                            targetAlbumForAction = null
                        },
                    ) {
                        Text("取消")
                    }
                },
            )
        }
    }
}
