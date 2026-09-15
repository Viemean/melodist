package org.melodist.mobile.ui.album

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import org.melodist.data.ArtistAlbumCacheManager
import org.melodist.data.UserLibraryCacheManager
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.mobile.ui.components.CommonSongList
import org.melodist.mobile.ui.navigation.LocalAppNavigation
import org.melodist.model.Album
import org.melodist.model.AlbumDetail
import org.melodist.playback.PlaybackManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumMid: String,
    albumName: String = "",
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val navController = LocalAppNavigation.current
    val scope = rememberCoroutineScope()

    var albumDetail by remember { mutableStateOf<AlbumDetail?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    val displayName = albumDetail?.name ?: albumName.ifBlank { "专辑详情" }
    val artistName = albumDetail?.artist.orEmpty()
    val songs = albumDetail?.songs.orEmpty()
    val coverUrl =
        remember(albumMid) {
            if (albumMid.isNotBlank()) MusicApiService.getAlbumCoverUrl(albumMid) else ""
        }

    suspend fun loadDetail(force: Boolean) {
        if (albumMid.isBlank()) return
        if (force) {
            isRefreshing = true
        } else if (albumDetail == null) {
            isLoading = true
        }
        val detail = ArtistAlbumCacheManager.getAlbumDetail(albumMid, forceRefresh = force)
        if (detail != null) {
            albumDetail = detail
        }
        isLoading = false
        isRefreshing = false
    }

    LaunchedEffect(albumMid) {
        loadDetail(force = false)
    }

    val context = LocalContext.current
    val libraryData by UserLibraryCacheManager.libraryFlow.collectAsState()
    val isFavorite =
        remember(libraryData.favoriteAlbums, albumMid) {
            libraryData.favoriteAlbums.any { it.mid == albumMid }
        }
    val currentAlbum =
        remember(albumDetail, albumMid, displayName, artistName, songs.size, coverUrl) {
            Album(
                id = 0L,
                mid = albumMid,
                title = displayName,
                artist = artistName,
                songCount = songs.size,
                coverUrl = coverUrl,
            )
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!UserSession.isLoggedIn) {
                                Toast.makeText(context, "请先登录", Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            val newFav = !isFavorite
                            UserLibraryCacheManager.onAlbumFavoriteToggled(currentAlbum, newFav)
                            Toast.makeText(
                                context,
                                if (newFav) "已收藏专辑" else "已取消收藏专辑",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (isFavorite) "取消收藏" else "收藏专辑",
                            tint = if (isFavorite) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurface,
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
                onRefresh = {
                    scope.launch {
                        loadDetail(force = true)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (isLoading && songs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                CommonSongList(
                    songs = songs,
                    state = listState,
                    contentPadding =
                        PaddingValues(
                            bottom = contentPadding.calculateBottomPadding() + 16.dp,
                        ),
                    onSongClick = { list, index ->
                        PlaybackManager.setPlaylist(list, startIndex = index)
                    },
                    headerItems = {
                        item(key = "album_header") {
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
                                    AlbumArtImage(
                                        coverUrl = coverUrl,
                                        contentDescription = displayName,
                                        shape = RoundedCornerShape(12.dp),
                                        elevation = 6.dp,
                                        placeholderIconSize = 40.dp,
                                        modifier = Modifier.size(96.dp),
                                    )

                                    Spacer(modifier = Modifier.width(16.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = displayName,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (artistName.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = artistName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier =
                                                    Modifier.clickable {
                                                        // 若歌曲含有歌手 mid，跳转到第一位歌手
                                                        val firstSong = songs.firstOrNull()
                                                        val firstArtist = firstSong?.singerList?.firstOrNull()
                                                        if (firstArtist != null && firstArtist.mid.isNotBlank()) {
                                                            navController.navigateToArtist(firstArtist.mid, firstArtist.name)
                                                        }
                                                    },
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "共收录 ${songs.size} 首单曲",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            if (songs.isNotEmpty()) {
                                                PlaybackManager.setPlaylist(songs, startIndex = 0)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("播放全部")
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            if (songs.isNotEmpty()) {
                                                PlaybackManager.setPlaylist(songs.shuffled(), startIndex = 0)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Shuffle,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("随机播放")
                                    }
                                }
                            }
                        }
                    },
                )
            }
            }
        }
    }
}
