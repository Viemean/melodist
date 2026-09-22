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
import androidx.compose.runtime.DisposableEffect
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
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.data.ArtistAlbumCacheManager
import org.melodist.data.UserLibraryCacheManager
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.mobile.ui.components.ArtistSelectDialog
import org.melodist.mobile.ui.components.CommonSongList
import org.melodist.mobile.ui.navigation.LocalAppNavigation
import org.melodist.model.Album
import org.melodist.model.AlbumDetail
import org.melodist.model.Artist
import org.melodist.model.PlaybackSourceContext
import org.melodist.playback.CoverMemoryManager
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
    var showDescriptionSheet by remember { mutableStateOf(false) }
    var showArtistSelectDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    val displayName = albumDetail?.name ?: albumName.ifBlank { "专辑详情" }
    val artistName = albumDetail?.artist.orEmpty()
    val songs = albumDetail?.songs.orEmpty()
    val currentAlbumId = albumDetail?.id ?: songs.firstOrNull()?.albumId ?: 0L
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
    DisposableEffect(coverUrl) {
        onDispose {
            if (coverUrl.isNotBlank()) {
                val rawUrl = coverUrl.replace(Regex("R[0-9]+x[0-9]+"), "")
                CoverMemoryManager.evictCoverFromMemory(context, rawUrl)
                CoverMemoryManager.evictCoverFromMemory(context, coverUrl)
            }
        }
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
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clickable { showDescriptionSheet = true },
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    modifier = Modifier.fillMaxWidth(),
                                                ) {
                                                    Text(
                                                        text = if (hasDesc) "专辑简介" else "专辑信息",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    Text(
                                                        text = "详情 >",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Medium,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = summaryText,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    lineHeight = 18.sp,
                                                )
                                            }
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
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showDescriptionSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                // 顶部标题与关闭按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "专辑详情",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                sheetState.hide()
                                showDescriptionSheet = false
                            }
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 两列整齐键值对内容区
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val labelWidth = 84.dp

                    // 专辑：
                    if (activeAlbumDetail.name.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "专辑：",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(labelWidth),
                                lineHeight = 22.sp,
                            )
                            Text(
                                text = activeAlbumDetail.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                lineHeight = 22.sp,
                            )
                        }
                    }

                    // 歌手：多位歌手各占一行
                    if (activeAlbumDetail.singerList.isNotEmpty()) {
                        activeAlbumDetail.singerList.forEach { singerItem ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    text = "歌手：",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(labelWidth),
                                    lineHeight = 22.sp,
                                )
                                Text(
                                    text = singerItem,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    lineHeight = 22.sp,
                                )
                            }
                        }
                    } else if (activeAlbumDetail.artist.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "歌手：",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(labelWidth),
                                lineHeight = 22.sp,
                            )
                            Text(
                                text = activeAlbumDetail.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                lineHeight = 22.sp,
                            )
                        }
                    }

                    // 语言：
                    if (activeAlbumDetail.language.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "语言：",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(labelWidth),
                                lineHeight = 22.sp,
                            )
                            Text(
                                text = activeAlbumDetail.language,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                lineHeight = 22.sp,
                            )
                        }
                    }

                    // 唱片公司：
                    if (activeAlbumDetail.company.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "唱片公司：",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(labelWidth),
                                lineHeight = 22.sp,
                            )
                            Text(
                                text = activeAlbumDetail.company,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                lineHeight = 22.sp,
                            )
                        }
                    }

                    // 唱片类型：
                    if (activeAlbumDetail.albumType.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "唱片类型：",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(labelWidth),
                                lineHeight = 22.sp,
                            )
                            Text(
                                text = activeAlbumDetail.albumType,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                lineHeight = 22.sp,
                            )
                        }
                    }

                    // 发行时间：
                    if (activeAlbumDetail.publishDate.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "发行时间：",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(labelWidth),
                                lineHeight = 22.sp,
                            )
                            Text(
                                text = activeAlbumDetail.publishDate,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                lineHeight = 22.sp,
                            )
                        }
                    }

                    // 专辑简介：
                    if (activeAlbumDetail.description.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "专辑简介：",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(labelWidth),
                                lineHeight = 22.sp,
                            )
                            SelectionContainer(
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = activeAlbumDetail.description.trim(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 22.sp,
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "专辑简介：",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(labelWidth),
                                lineHeight = 22.sp,
                            )
                            Text(
                                text = "暂无官方简介",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.weight(1f),
                                lineHeight = 22.sp,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
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
}
