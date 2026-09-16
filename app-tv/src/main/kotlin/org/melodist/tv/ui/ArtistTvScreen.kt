package org.melodist.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.getSingerAlbumList
import org.melodist.api.getSingerSongList
import org.melodist.data.ArtistAlbumCacheManager
import org.melodist.data.FavoriteArtistsManager
import org.melodist.model.Album
import org.melodist.model.ArtistDetail
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.components.MediaDetailTvScaffold
import org.melodist.tv.ui.components.MelodistAsyncImage
import org.melodist.tv.ui.components.SongArtistAlbumDialog
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes

private enum class ArtistSubMode {
    Songs,
    Albums,
}

/**
 * 电视端独立歌手详情界面
 * 支持热门/最新切换、关注/已关注切换、选专辑界面切换，以及单曲和专辑无限分页加载
 */
@Composable
fun ArtistTvScreen(
    artistMid: String,
    artistName: String = "",
    surfaceColor: Color,
    onNavigateToPlayer: () -> Unit = {},
    onNavigateToArtist: (artistMid: String, artistName: String) -> Unit = { _, _ -> },
    onNavigateToAlbum: (albumMid: String, albumName: String) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
) {
    var subMode by remember { mutableStateOf(ArtistSubMode.Songs) }
    var isHotOrder by remember { mutableStateOf(true) }

    var artistDetail by remember { mutableStateOf<ArtistDetail?>(null) }
    var artistSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var totalSongCount by remember { mutableIntStateOf(0) }
    var songPage by remember { mutableIntStateOf(1) }
    var isLoadingSongs by remember { mutableStateOf(true) }
    var isLoadingMoreSongs by remember { mutableStateOf(false) }
    var hasMoreSongs by remember { mutableStateOf(true) }

    // 专辑相关状态
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var totalAlbumCount by remember { mutableIntStateOf(0) }
    var albumPage by remember { mutableIntStateOf(1) }
    var isLoadingAlbums by remember { mutableStateOf(false) }
    var isLoadingMoreAlbums by remember { mutableStateOf(false) }
    var hasMoreAlbums by remember { mutableStateOf(true) }

    var selectedSongForDialog by remember { mutableStateOf<Song?>(null) }

    val currentPlayingSong by PlaybackManager.currentSong.collectAsState()
    val followedMids by FavoriteArtistsManager.followedArtistMids.collectAsState()
    val isFollowed = followedMids.contains(artistMid)

    val scope = rememberCoroutineScope()

    // 加载歌手单曲（根据 isHotOrder 与分页）
    LaunchedEffect(artistMid, isHotOrder) {
        if (artistMid.isNotBlank()) {
            FavoriteArtistsManager.checkStatus(artistMid)
            isLoadingSongs = true
            songPage = 1
            if (artistDetail == null) {
                artistDetail = ArtistAlbumCacheManager.getArtistDetail(artistMid)
            }
            val (songs, total) =
                ArtistAlbumCacheManager.getSingerSongList(
                    singerMid = artistMid,
                    page = 1,
                    pageSize = 30,
                    isHotOrder = isHotOrder,
                )
            artistSongs = songs
            totalSongCount = total
            hasMoreSongs = songs.isNotEmpty() && songs.size < total
            isLoadingSongs = false
        }
    }

    // 切换到专辑模式时懒加载第一页专辑
    LaunchedEffect(subMode, artistMid) {
        if (subMode == ArtistSubMode.Albums && albums.isEmpty() && artistMid.isNotBlank()) {
            isLoadingAlbums = true
            albumPage = 1
            val (albList, total) =
                ArtistAlbumCacheManager.getSingerAlbumList(
                    singerMid = artistMid,
                    page = 1,
                    pageSize = 30,
                )
            albums = albList
            totalAlbumCount = total
            hasMoreAlbums = albList.isNotEmpty() && albList.size < total
            isLoadingAlbums = false
        }
    }

    val avatarUrl =
        remember(artistMid) {
            if (artistMid.isNotBlank()) MusicApiService.getSingerAvatarUrl(artistMid) else ""
        }

    val title = artistDetail?.name ?: artistName.ifBlank { "歌手详情" }
    val subtitle =
        if (subMode == ArtistSubMode.Albums) {
            "专辑作品"
        } else if (isHotOrder) {
            "热门单曲"
        } else {
            "最新单曲"
        }
    val metaInfo =
        if (subMode == ArtistSubMode.Albums) {
            if (totalAlbumCount > 0) "全部专辑 · 共 $totalAlbumCount 张" else ""
        } else {
            if (isHotOrder) {
                if (artistSongs.isNotEmpty()) "热门单曲 TOP ${artistSongs.size}" else ""
            } else {
                if (totalSongCount > 0) {
                    "全部歌曲 · 共 $totalSongCount 首"
                } else if (artistSongs.isNotEmpty()) {
                    "最新单曲 · 共 ${artistSongs.size} 首"
                } else {
                    ""
                }
            }
        }

    val description =
        artistDetail?.brief?.ifBlank {
            if (subMode == ArtistSubMode.Albums) "收录专辑共 $totalAlbumCount 张" else "共收录 $totalSongCount 首歌曲"
        } ?: if (subMode == ArtistSubMode.Albums) "收录专辑共 $totalAlbumCount 张" else "共收录 $totalSongCount 首歌曲"

    MediaDetailTvScaffold(
        surfaceColor = surfaceColor,
        headerImageUrl = avatarUrl,
        artistMid = artistMid,
        isHeaderImageCircle = true,
        title = title,
        subtitle = subtitle,
        metaInfo = metaInfo,
        description = description,
        songs = artistSongs,
        currentPlayingMid = currentPlayingSong?.songMid,
        isLoading = if (subMode == ArtistSubMode.Albums) isLoadingAlbums else isLoadingSongs,
        isLoadingMore = isLoadingMoreSongs,
        emptyMessage = if (subMode == ArtistSubMode.Albums) "暂无相关专辑" else "暂未收录相关单曲",
        onBack = {
            if (subMode == ArtistSubMode.Albums) {
                subMode = ArtistSubMode.Songs
            } else {
                onBack()
            }
        },
        onPlayAll = {
            if (artistSongs.isNotEmpty()) {
                PlaybackManager.setPlaylist(artistSongs, startIndex = 0)
                onNavigateToPlayer()
            }
        },
        onSongClick = { song ->
            val index = artistSongs.indexOfFirst { it.songMid == song.songMid }
            PlaybackManager.setPlaylist(artistSongs, startIndex = if (index >= 0) index else 0)
            onNavigateToPlayer()
        },
        onSongLongClick = { song ->
            if (song.canShowArtistAlbumDialog) {
                selectedSongForDialog = song
            }
        },
        onLoadMore = {
            if (subMode == ArtistSubMode.Songs && !isLoadingMoreSongs && hasMoreSongs && !isLoadingSongs) {
                scope.launch {
                    isLoadingMoreSongs = true
                    val nextPage = songPage + 1
                    val (moreSongs, total) =
                        ArtistAlbumCacheManager.getSingerSongList(
                            singerMid = artistMid,
                            page = nextPage,
                            pageSize = 30,
                            isHotOrder = isHotOrder,
                        )
                    if (moreSongs.isNotEmpty()) {
                        val existingIds = artistSongs.map { it.songMid }.toSet()
                        val filtered = moreSongs.filterNot { existingIds.contains(it.songMid) }
                        artistSongs = artistSongs + filtered
                        songPage = nextPage
                        totalSongCount = total
                    }
                    hasMoreSongs = moreSongs.isNotEmpty() && artistSongs.size < total
                    isLoadingMoreSongs = false
                }
            }
        },
        bottomContent = {
            // “播放全部”下方紧凑并排放置 3 个功能切换按钮
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 按钮 1：最新 / 热门 排序切换
                Button(
                    onClick = {
                        if (subMode == ArtistSubMode.Albums) {
                            subMode = ArtistSubMode.Songs
                        }
                        isHotOrder = !isHotOrder
                    },
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(38.dp),
                    shape =
                        ButtonDefaults.shape(
                            shape = MelodistShapes.CardCorner,
                            focusedShape = MelodistShapes.CardCorner,
                        ),
                    colors =
                        ButtonDefaults.colors(
                            containerColor =
                                if (!isHotOrder &&
                                    subMode == ArtistSubMode.Songs
                                ) {
                                    MelodistColors.AccentGreen.copy(alpha = 0.22f)
                                } else {
                                    Color.White.copy(alpha = 0.08f)
                                },
                            focusedContainerColor = Color.White,
                            contentColor = if (!isHotOrder && subMode == ArtistSubMode.Songs) MelodistColors.AccentGreen else Color.White,
                            focusedContentColor = Color.Black,
                        ),
                    border =
                        ButtonDefaults.border(
                            border =
                                Border(
                                    border =
                                        BorderStroke(
                                            1.dp,
                                            if (!isHotOrder &&
                                                subMode == ArtistSubMode.Songs
                                            ) {
                                                MelodistColors.AccentGreen.copy(alpha = 0.45f)
                                            } else {
                                                Color.White.copy(alpha = 0.15f)
                                            },
                                        ),
                                    shape = MelodistShapes.CardCorner,
                                ),
                            focusedBorder =
                                Border(
                                    border = BorderStroke(2.dp, MelodistColors.FocusTeal),
                                    shape = MelodistShapes.CardCorner,
                                ),
                        ),
                    scale = ButtonDefaults.scale(focusedScale = 1.0f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (isHotOrder) "最新" else "热门",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }

                // 按钮 2：关注 / 已关注 状态切换
                val followBorderColor =
                    if (isFollowed) {
                        MelodistColors.AccentGreen.copy(alpha = 0.45f)
                    } else {
                        Color.White.copy(alpha = 0.15f)
                    }
                Button(
                    onClick = {
                        FavoriteArtistsManager.toggleFollow(artistMid)
                    },
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(38.dp),
                    shape =
                        ButtonDefaults.shape(
                            shape = MelodistShapes.CardCorner,
                            focusedShape = MelodistShapes.CardCorner,
                        ),
                    colors =
                        ButtonDefaults.colors(
                            containerColor =
                                if (isFollowed) {
                                    MelodistColors.AccentGreen.copy(
                                        alpha = 0.22f,
                                    )
                                } else {
                                    Color.White.copy(alpha = 0.08f)
                                },
                            focusedContainerColor = Color.White,
                            contentColor = if (isFollowed) MelodistColors.AccentGreen else Color.White,
                            focusedContentColor = Color.Black,
                        ),
                    border =
                        ButtonDefaults.border(
                            border =
                                Border(
                                    border = BorderStroke(1.dp, followBorderColor),
                                    shape = MelodistShapes.CardCorner,
                                ),
                            focusedBorder =
                                Border(
                                    border = BorderStroke(2.dp, MelodistColors.FocusTeal),
                                    shape = MelodistShapes.CardCorner,
                                ),
                        ),
                    scale = ButtonDefaults.scale(focusedScale = 1.0f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (isFollowed) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                            Text(
                                text = if (isFollowed) "已关注" else "关注",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // 按钮 3：专辑 / 单曲 视图切换
                Button(
                    onClick = {
                        subMode = if (subMode == ArtistSubMode.Albums) ArtistSubMode.Songs else ArtistSubMode.Albums
                    },
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(38.dp),
                    shape =
                        ButtonDefaults.shape(
                            shape = MelodistShapes.CardCorner,
                            focusedShape = MelodistShapes.CardCorner,
                        ),
                    colors =
                        ButtonDefaults.colors(
                            containerColor =
                                if (subMode ==
                                    ArtistSubMode.Albums
                                ) {
                                    MelodistColors.AccentGreen.copy(alpha = 0.22f)
                                } else {
                                    Color.White.copy(alpha = 0.08f)
                                },
                            focusedContainerColor = Color.White,
                            contentColor = if (subMode == ArtistSubMode.Albums) MelodistColors.AccentGreen else Color.White,
                            focusedContentColor = Color.Black,
                        ),
                    border =
                        ButtonDefaults.border(
                            border =
                                Border(
                                    border =
                                        BorderStroke(
                                            1.dp,
                                            if (subMode ==
                                                ArtistSubMode.Albums
                                            ) {
                                                MelodistColors.AccentGreen.copy(alpha = 0.45f)
                                            } else {
                                                Color.White.copy(alpha = 0.15f)
                                            },
                                        ),
                                    shape = MelodistShapes.CardCorner,
                                ),
                            focusedBorder =
                                Border(
                                    border = BorderStroke(2.dp, MelodistColors.FocusTeal),
                                    shape = MelodistShapes.CardCorner,
                                ),
                        ),
                    scale = ButtonDefaults.scale(focusedScale = 1.0f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (subMode == ArtistSubMode.Albums) "单曲" else "专辑",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
        rightContent =
            if (subMode == ArtistSubMode.Albums) {
                {
                    // 选专辑界面：右侧以大屏网格展现该歌手的专辑作品
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize(),
                    ) {
                        // 顶部副标题与状态栏
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "歌手专辑 ($totalAlbumCount 张)",
                                color = Color.White.copy(alpha = 0.90f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (isLoadingAlbums) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = "正在载入专辑...", color = Color.White, fontSize = 16.sp)
                            }
                        } else if (albums.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = "暂无收录专辑", color = Color.White, fontSize = 16.sp)
                            }
                        } else {
                            val albumGridState = rememberLazyGridState()

                            // 触底加载更多专辑
                            val shouldLoadMoreAlbums =
                                remember {
                                    derivedStateOf {
                                        val total = albumGridState.layoutInfo.totalItemsCount
                                        val last =
                                            albumGridState.layoutInfo.visibleItemsInfo
                                                .lastOrNull()
                                                ?.index ?: 0
                                        total > 0 && last >= total - 4
                                    }
                                }

                            LaunchedEffect(shouldLoadMoreAlbums.value) {
                                if (shouldLoadMoreAlbums.value && !isLoadingMoreAlbums && hasMoreAlbums && !isLoadingAlbums) {
                                    scope.launch {
                                        isLoadingMoreAlbums = true
                                        val nextPage = albumPage + 1
                                        val (moreAlb, total) =
                                            ArtistAlbumCacheManager.getSingerAlbumList(
                                                singerMid = artistMid,
                                                page = nextPage,
                                                pageSize = 30,
                                            )
                                        if (moreAlb.isNotEmpty()) {
                                            val existing = albums.map { it.mid }.toSet()
                                            albums = albums + moreAlb.filterNot { existing.contains(it.mid) }
                                            albumPage = nextPage
                                            totalAlbumCount = total
                                        }
                                        hasMoreAlbums = moreAlb.isNotEmpty() && albums.size < total
                                        isLoadingMoreAlbums = false
                                    }
                                }
                            }

                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 150.dp),
                                state = albumGridState,
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 36.dp),
                            ) {
                                itemsIndexed(
                                    items = albums,
                                    key = { _, album -> album.mid.ifBlank { album.id.toString() } },
                                ) { _, album ->
                                    ArtistAlbumGridItem(
                                        album = album,
                                        onClick = {
                                            onNavigateToAlbum(album.mid, album.title)
                                        },
                                    )
                                }

                                if (isLoadingMoreAlbums) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 12.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "正在载入更多专辑...",
                                                color = MelodistColors.TextSecondary,
                                                fontSize = 13.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                null
            },
    )

    // 长按曲目关联资产弹窗
    selectedSongForDialog?.let { song ->
        SongArtistAlbumDialog(
            song = song,
            onDismissRequest = { selectedSongForDialog = null },
            onSelectArtist = { mid, name ->
                selectedSongForDialog = null
                if (mid != artistMid) {
                    onNavigateToArtist(mid, name)
                }
            },
            onSelectAlbum = { mid, name ->
                selectedSongForDialog = null
                onNavigateToAlbum(mid, name)
            },
        )
    }
}

/**
 * 专辑卡片项
 */
@Composable
private fun ArtistAlbumGridItem(
    album: Album,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused },
        shape =
            CardDefaults.shape(
                shape = MelodistShapes.CardCorner,
                focusedShape = MelodistShapes.CardCorner,
            ),
        colors =
            CardDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.05f),
                focusedContainerColor = Color.White,
            ),
        border =
            CardDefaults.border(
                border =
                    Border(
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        shape = MelodistShapes.CardCorner,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                        shape = MelodistShapes.CardCorner,
                    ),
            ),
        scale = CardDefaults.scale(focusedScale = 1.05f),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF141822)),
                contentAlignment = Alignment.Center,
            ) {
                if (album.mid.isNotBlank()) {
                    MelodistAsyncImage(
                        coverUrl = album.coverUrl,
                        albumMid = album.mid,
                        contentDescription = album.title,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Album,
                        contentDescription = null,
                        tint = MelodistColors.TextMuted,
                        modifier = Modifier.size(40.dp),
                    )
                }

                if (album.songCount > 0) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.Black.copy(alpha = 0.65f))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "${album.songCount} 首",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.9f),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = album.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isFocused) Color(0xFF12141A) else MelodistColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (album.artist.isNotBlank()) {
                    Text(
                        text = album.artist,
                        fontSize = 11.sp,
                        color = if (isFocused) Color(0xFF333333) else Color.White.copy(alpha = 0.70f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
