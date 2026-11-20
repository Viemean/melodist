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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.melodist.api.CommentCountCache
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.getCommentCount
import org.melodist.data.ArtistAlbumCacheManager
import org.melodist.data.UserLibraryCacheManager
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.mobile.ui.components.ArtistSelectDialog
import org.melodist.mobile.ui.components.CommonSongList
import org.melodist.mobile.ui.components.MediaDescriptionCard
import org.melodist.mobile.ui.components.MediaInfoBottomSheet
import org.melodist.mobile.ui.components.RotatingCardCoordinator
import org.melodist.mobile.ui.components.SongCommentsBottomSheet
import org.melodist.mobile.ui.components.landscapeNestedScroll
import org.melodist.mobile.ui.navigation.LocalAppNavigation
import org.melodist.model.Album
import org.melodist.model.AlbumDetail
import org.melodist.model.Artist
import org.melodist.model.CommentTarget
import org.melodist.model.PlaybackSourceContext
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
    val apiService = remember { MusicApiService() }

    val cachedDetail = remember(albumMid) { ArtistAlbumCacheManager.getCachedAlbumDetail(albumMid) }
    var albumDetail by remember(albumMid) { mutableStateOf(cachedDetail) }
    var isLoading by remember(albumMid) { mutableStateOf(cachedDetail == null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showDescriptionSheet by remember { mutableStateOf(false) }
    var showCommentSheet by remember { mutableStateOf(false) }
    var showArtistSelectDialog by remember { mutableStateOf(false) }

    val listState = remember(albumMid) { RotatingCardCoordinator.getOrCreateLazyListState("album_$albumMid") }

    val displayName = albumDetail?.name ?: albumName.ifBlank { "专辑详情" }
    val artistName = albumDetail?.artist.orEmpty()
    val songs = albumDetail?.songs.orEmpty()
    val currentAlbumId = albumDetail?.id ?: songs.firstOrNull()?.albumId ?: 0L
    val albumCommentTarget =
        remember(currentAlbumId, displayName, artistName) {
            if (currentAlbumId > 0L) {
                CommentTarget.Album(
                    albumId = currentAlbumId,
                    albumName = displayName,
                    artistName = artistName,
                )
            } else {
                null
            }
        }

    val initialCommentCount = remember(albumCommentTarget) { albumCommentTarget?.let { CommentCountCache.get(it) } }
    var commentCount by remember(albumCommentTarget) { mutableStateOf(initialCommentCount) }

    LaunchedEffect(albumCommentTarget) {
        val target = albumCommentTarget
        if (target != null) {
            val count = apiService.getCommentCount(target)
            if (count != null && count >= 0) {
                commentCount = count
            }
        }
    }
    val albumArtists =
        remember(songs, artistName) {
            val artistsInSongs = songs.flatMap { it.singerList }.distinctBy { if (it.mid.isNotBlank()) it.mid else it.name }
            val artistNameParts = artistName.split("/", "、", "&", ",").map { it.trim() }.filter { it.isNotBlank() }

            if (artistNameParts.size > 1) {
                artistNameParts.map { partName ->
                    artistsInSongs.find {
                        it.name.equals(partName, ignoreCase = true) ||
                            it.name.contains(partName, ignoreCase = true) ||
                            partName.contains(it.name, ignoreCase = true)
                    } ?: Artist(id = 0L, mid = "", name = partName)
                }
            } else {
                val firstSongArtists = songs.firstOrNull()?.singerList.orEmpty()
                if (firstSongArtists.size > 1) {
                    firstSongArtists
                } else if (firstSongArtists.isNotEmpty()) {
                    firstSongArtists
                } else if (artistName.isNotBlank()) {
                    val matched = artistsInSongs.find { it.name.equals(artistName, ignoreCase = true) }
                    listOf(matched ?: Artist(id = 0L, mid = "", name = artistName))
                } else {
                    emptyList()
                }
            }
        }
    val coverUrl =
        remember(albumMid) {
            if (albumMid.isNotBlank()) MusicApiService.getAlbumCoverUrl(albumMid) else ""
        }

    val context = LocalContext.current

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

    val topBarState =
        org.melodist.mobile.ui.components
            .rememberLandscapeCollapsibleTopBarState()

    Scaffold(
        topBar = {
            org.melodist.mobile.ui.components.LandscapeCollapsibleTopBar(visible = topBarState.isVisible) {
                TopAppBar(
                    title = { Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
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
                                Toast
                                    .makeText(
                                        context,
                                        if (newFav) "已收藏专辑" else "已取消收藏专辑",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            },
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = if (isFavorite) "取消收藏" else "收藏专辑",
                                tint = if (isFavorite) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier =
            modifier
                .fillMaxSize()
                .landscapeNestedScroll(topBarState),
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
                            PlaybackManager.setPlaylist(
                                list,
                                startIndex = index,
                                sourceContext = PlaybackSourceContext.Album(albumMid = albumMid, albumId = currentAlbumId),
                            )
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
                                                            if (albumArtists.size > 1) {
                                                                showArtistSelectDialog = true
                                                            } else {
                                                                val targetArtist = albumArtists.firstOrNull()
                                                                val targetMid = targetArtist?.mid.orEmpty()
                                                                val targetName = targetArtist?.name?.ifBlank { artistName } ?: artistName
                                                                if (targetMid.isNotBlank()) {
                                                                    navController.navigateToArtist(targetMid, targetName)
                                                                } else if (targetArtist != null) {
                                                                    Toast.makeText(context, "暂无歌手详情数据", Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                        },
                                                )
                                            }
                                            val publishDate = albumDetail?.publishDate.orEmpty()
                                            val company = albumDetail?.company.orEmpty()
                                            val metaLine =
                                                listOfNotNull(
                                                    publishDate.takeIf { it.isNotBlank() }?.let { "发行: $it" },
                                                    company.takeIf { it.isNotBlank() },
                                                    "共 ${songs.size} 首歌",
                                                ).joinToString(" · ")

                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = metaLine,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }

                                    val currentDetail = albumDetail
                                    if (currentDetail != null) {
                                        val hasDesc = !currentDetail.description.isNullOrBlank()
                                        val summaryText =
                                            if (hasDesc) {
                                                currentDetail.description.trim()
                                            } else {
                                                listOfNotNull(
                                                    currentDetail.language.takeIf { it.isNotBlank() }?.let { "语言: $it" },
                                                    currentDetail.company.takeIf { it.isNotBlank() }?.let { "唱片公司: $it" },
                                                    currentDetail.albumType.takeIf { it.isNotBlank() }?.let { "唱片类型: $it" },
                                                ).joinToString(" · ").ifBlank { "点击查看完整唱片与发行信息" }
                                            }

                                        Spacer(modifier = Modifier.height(12.dp))
                                        MediaDescriptionCard(
                                            title = if (hasDesc) "专辑简介" else "专辑信息",
                                            description = summaryText,
                                            onClick = { showDescriptionSheet = true },
                                            commentCount = commentCount,
                                            onCommentClick =
                                                if (albumCommentTarget != null) {
                                                    { showCommentSheet = true }
                                                } else {
                                                    null
                                                },
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Button(
                                            onClick = {
                                                if (songs.isNotEmpty()) {
                                                    PlaybackManager.setPlaylist(
                                                        songs,
                                                        startIndex = 0,
                                                        sourceContext = PlaybackSourceContext.Album(albumMid = albumMid, albumId = currentAlbumId),
                                                    )
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.PlayArrow,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("播放全部")
                                        }

                                        FilledTonalButton(
                                            onClick = {
                                                if (songs.isNotEmpty()) {
                                                    PlaybackManager.setPlaylist(
                                                        songs.shuffled(),
                                                        startIndex = 0,
                                                        sourceContext = PlaybackSourceContext.Album(albumMid = albumMid, albumId = currentAlbumId),
                                                    )
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Shuffle,
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

    val activeAlbumDetail = albumDetail
    if (showDescriptionSheet && activeAlbumDetail != null) {
        val items =
            buildList {
                if (activeAlbumDetail.name.isNotBlank()) {
                    add("专辑：" to activeAlbumDetail.name)
                }
                if (activeAlbumDetail.singerList.isNotEmpty()) {
                    activeAlbumDetail.singerList.forEach { singerItem ->
                        add("歌手：" to singerItem)
                    }
                } else if (activeAlbumDetail.artist.isNotBlank()) {
                    add("歌手：" to activeAlbumDetail.artist)
                }
                if (activeAlbumDetail.language.isNotBlank()) {
                    add("语言：" to activeAlbumDetail.language)
                }
                if (activeAlbumDetail.company.isNotBlank()) {
                    add("唱片公司：" to activeAlbumDetail.company)
                }
                if (activeAlbumDetail.albumType.isNotBlank()) {
                    add("唱片类型：" to activeAlbumDetail.albumType)
                }
                if (activeAlbumDetail.publishDate.isNotBlank()) {
                    add("发行时间：" to activeAlbumDetail.publishDate)
                }
            }
        MediaInfoBottomSheet(
            title = "专辑详情",
            items = items,
            description = activeAlbumDetail.description.trim().ifBlank { "暂无官方简介" },
            descriptionLabel = "专辑简介：",
            onDismissRequest = { showDescriptionSheet = false },
        )
    }

    if (showArtistSelectDialog) {
        ArtistSelectDialog(
            artists = albumArtists,
            onDismissRequest = { showArtistSelectDialog = false },
            onArtistSelect = { artist ->
                showArtistSelectDialog = false
                if (artist.mid.isNotBlank()) {
                    navController.navigateToArtist(artist.mid, artist.name)
                } else {
                    Toast.makeText(context, "暂无歌手详情数据", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    val currentTarget = albumCommentTarget
    if (showCommentSheet && currentTarget != null) {
        SongCommentsBottomSheet(
            target = currentTarget,
            onDismissRequest = { showCommentSheet = false },
        )
    }
}
