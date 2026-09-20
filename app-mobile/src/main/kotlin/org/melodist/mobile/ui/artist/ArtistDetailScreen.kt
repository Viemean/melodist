package org.melodist.mobile.ui.artist

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.data.ArtistAlbumCacheManager
import org.melodist.data.FavoriteArtistsManager
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.mobile.ui.components.CommonSongList
import org.melodist.mobile.ui.navigation.LocalAppNavigation
import org.melodist.model.Album
import org.melodist.model.ArtistDetail
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import org.melodist.playback.QueuePaginationSource

enum class ArtistContentTab {
    Songs,
    Albums,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    artistMid: String,
    artistName: String = "",
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val navController = LocalAppNavigation.current
    val scope = rememberCoroutineScope()

    val followedMids by FavoriteArtistsManager.followedArtistMids.collectAsState()
    val isFollowed = followedMids.contains(artistMid)

    var contentTab by remember { mutableStateOf(ArtistContentTab.Songs) }
    var isHotOrder by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }

    var artistDetail by remember { mutableStateOf<ArtistDetail?>(null) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isLoadingSongs by remember { mutableStateOf(true) }
    var isLoadingMoreSongs by remember { mutableStateOf(false) }
    var currentSongPage by remember { mutableIntStateOf(1) }
    var totalSongs by remember { mutableIntStateOf(0) }
    var hasMoreSongs by remember { mutableStateOf(false) }

    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var isLoadingAlbums by remember { mutableStateOf(false) }
    var isLoadingMoreAlbums by remember { mutableStateOf(false) }
    var currentAlbumPage by remember { mutableIntStateOf(1) }
    var totalAlbums by remember { mutableIntStateOf(0) }
    var hasMoreAlbums by remember { mutableStateOf(false) }

    var isBriefExpanded by remember { mutableStateOf(false) }

    val songListState = rememberLazyListState()
    val albumListState = rememberLazyListState()

    val displayName = artistDetail?.name ?: artistName.ifBlank { "歌手详情" }
    val avatarUrl =
        remember(artistMid) {
            MusicApiService.getSingerAvatarUrl(artistMid)
        }

    suspend fun refreshData() {
        if (artistMid.isBlank()) return
        isRefreshing = true
        val detail = ArtistAlbumCacheManager.getArtistDetail(artistMid, forceRefresh = true)
        if (detail != null) {
            artistDetail = detail
        }
        if (contentTab == ArtistContentTab.Songs) {
            val (firstPageSongs, total) =
                ArtistAlbumCacheManager.getSingerSongList(
                    singerMid = artistMid,
                    page = 1,
                    pageSize = 30,
                    isHotOrder = isHotOrder,
                    forceRefresh = true,
                )
            songs = firstPageSongs
            totalSongs = total
            hasMoreSongs = firstPageSongs.isNotEmpty() && firstPageSongs.size < total
            currentSongPage = 1
        } else {
            val (firstPageAlbums, total) =
                ArtistAlbumCacheManager.getSingerAlbumList(
                    singerMid = artistMid,
                    page = 1,
                    pageSize = 30,
                    forceRefresh = true,
                )
            albums = firstPageAlbums
            totalAlbums = total
            hasMoreAlbums = firstPageAlbums.isNotEmpty() && firstPageAlbums.size < total
            currentAlbumPage = 1
        }
        isRefreshing = false
    }

    LaunchedEffect(artistMid) {
        if (artistMid.isNotBlank()) {
            FavoriteArtistsManager.checkStatus(artistMid)
            val detail = ArtistAlbumCacheManager.getArtistDetail(artistMid)
            if (detail != null) {
                artistDetail = detail
            }
        }
    }

    LaunchedEffect(artistMid, isHotOrder) {
        if (artistMid.isNotBlank()) {
            isLoadingSongs = true
            currentSongPage = 1
            val (firstPageSongs, total) =
                ArtistAlbumCacheManager.getSingerSongList(
                    singerMid = artistMid,
                    page = 1,
                    pageSize = 30,
                    isHotOrder = isHotOrder,
                )
            songs = firstPageSongs
            totalSongs = total
            hasMoreSongs = firstPageSongs.isNotEmpty() && firstPageSongs.size < total
            isLoadingSongs = false
        }
    }

    LaunchedEffect(artistMid, contentTab) {
        if (artistMid.isNotBlank() && albums.isEmpty()) {
            if (contentTab == ArtistContentTab.Albums) {
                isLoadingAlbums = true
            }
            val (firstPageAlbums, total) =
                ArtistAlbumCacheManager.getSingerAlbumList(
                    singerMid = artistMid,
                    page = 1,
                    pageSize = 30,
                )
            albums = firstPageAlbums
            totalAlbums = total
            hasMoreAlbums = firstPageAlbums.isNotEmpty() && firstPageAlbums.size < total
            currentAlbumPage = 1
            isLoadingAlbums = false
        }
    }

    val shouldLoadMoreSongs by remember {
        derivedStateOf {
            val totalCount = songListState.layoutInfo.totalItemsCount
            val lastVisibleIndex =
                songListState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: 0
            totalCount > 0 && lastVisibleIndex >= totalCount - 5
        }
    }

    val artistSongPaginationSource =
        remember(artistMid, isHotOrder) {
            if (artistMid.isNotBlank()) {
                object : QueuePaginationSource {
                    override val hasMore: Boolean get() = hasMoreSongs
                    override val isLoadingMore: Boolean get() = isLoadingMoreSongs

                    override suspend fun loadMore(): List<Song> {
                        if (isLoadingMoreSongs || isLoadingSongs || !hasMoreSongs || artistMid.isBlank()) return emptyList()
                        isLoadingMoreSongs = true
                        val nextPage = currentSongPage + 1
                        val (nextPageSongs, total) =
                            ArtistAlbumCacheManager.getSingerSongList(
                                singerMid = artistMid,
                                page = nextPage,
                                pageSize = 30,
                                isHotOrder = isHotOrder,
                            )
                        val newUnique =
                            if (nextPageSongs.isNotEmpty()) {
                                val existingMids = songs.map { it.songMid }.toSet()
                                val filtered = nextPageSongs.filter { it.songMid !in existingMids }
                                songs = songs + filtered
                                currentSongPage = nextPage
                                totalSongs = total
                                filtered
                            } else {
                                emptyList()
                            }
                        hasMoreSongs = nextPageSongs.isNotEmpty() && songs.size < total
                        isLoadingMoreSongs = false
                        return newUnique
                    }
                }
            } else {
                null
            }
        }

    LaunchedEffect(shouldLoadMoreSongs) {
        if (shouldLoadMoreSongs && contentTab == ArtistContentTab.Songs && !isLoadingMoreSongs && !isLoadingSongs && hasMoreSongs && artistMid.isNotBlank()) {
            scope.launch {
                artistSongPaginationSource?.loadMore()
            }
        }
    }

    val shouldLoadMoreAlbums by remember {
        derivedStateOf {
            val totalCount = albumListState.layoutInfo.totalItemsCount
            val lastVisibleIndex =
                albumListState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: 0
            totalCount > 0 && lastVisibleIndex >= totalCount - 5
        }
    }

    LaunchedEffect(shouldLoadMoreAlbums) {
        if (shouldLoadMoreAlbums &&
            contentTab == ArtistContentTab.Albums &&
            !isLoadingMoreAlbums &&
            !isLoadingAlbums &&
            hasMoreAlbums &&
            artistMid.isNotBlank()
        ) {
            scope.launch {
                isLoadingMoreAlbums = true
                val nextPage = currentAlbumPage + 1
                val (nextPageAlbums, total) =
                    ArtistAlbumCacheManager.getSingerAlbumList(
                        singerMid = artistMid,
                        page = nextPage,
                        pageSize = 30,
                    )
                if (nextPageAlbums.isNotEmpty()) {
                    val existingMids = albums.map { it.mid }.toSet()
                    val filtered = nextPageAlbums.filter { it.mid !in existingMids }
                    albums = albums + filtered
                    currentAlbumPage = nextPage
                    totalAlbums = total
                }
                hasMoreAlbums = nextPageAlbums.isNotEmpty() && albums.size < total
                isLoadingMoreAlbums = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("歌手页面", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                onRefresh = {
                    scope.launch {
                        refreshData()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                when (contentTab) {
                    ArtistContentTab.Songs -> {
                        if (isLoadingSongs && songs.isEmpty()) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                ArtistHeader(
                                    avatarUrl = avatarUrl,
                                    displayName = displayName,
                                    totalSongs = totalSongs,
                                    songCount = songs.size,
                                    brief = artistDetail?.brief.orEmpty(),
                                    isBriefExpanded = isBriefExpanded,
                                    onToggleBrief = { isBriefExpanded = !isBriefExpanded },
                                    contentTab = contentTab,
                                    onTabChange = { contentTab = it },
                                    isHotOrder = isHotOrder,
                                    onOrderChange = { isHotOrder = it },
                                    totalAlbums = totalAlbums,
                                    isFollowed = isFollowed,
                                    onToggleFollow = { FavoriteArtistsManager.toggleFollow(artistMid) },
                                )
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        } else {
                            CommonSongList(
                                songs = songs,
                                state = songListState,
                                paginationSource = artistSongPaginationSource,
                                contentPadding =
                                    PaddingValues(
                                        bottom = contentPadding.calculateBottomPadding() + 16.dp,
                                    ),
                                onSongClick = { list, index ->
                                    PlaybackManager.setPlaylist(
                                        list,
                                        startIndex = index,
                                        paginationSource = artistSongPaginationSource,
                                    )
                                },
                                headerItems = {
                                    item(key = "artist_header") {
                                        ArtistHeader(
                                            avatarUrl = avatarUrl,
                                            displayName = displayName,
                                            totalSongs = totalSongs,
                                            songCount = songs.size,
                                            brief = artistDetail?.brief.orEmpty(),
                                            isBriefExpanded = isBriefExpanded,
                                            onToggleBrief = { isBriefExpanded = !isBriefExpanded },
                                            contentTab = contentTab,
                                            onTabChange = { contentTab = it },
                                            isHotOrder = isHotOrder,
                                            onOrderChange = { isHotOrder = it },
                                            totalAlbums = totalAlbums,
                                            isFollowed = isFollowed,
                                            onToggleFollow = { FavoriteArtistsManager.toggleFollow(artistMid) },
                                        )
                                    }
                                },
                                footerItems = {
                                    if (isLoadingMoreSongs) {
                                        item(key = "loading_more_songs") {
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(16.dp),
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
                            )
                        }
                    }
                    ArtistContentTab.Albums -> {
                        if (isLoadingAlbums && albums.isEmpty()) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                ArtistHeader(
                                    avatarUrl = avatarUrl,
                                    displayName = displayName,
                                    totalSongs = totalSongs,
                                    songCount = songs.size,
                                    brief = artistDetail?.brief.orEmpty(),
                                    isBriefExpanded = isBriefExpanded,
                                    onToggleBrief = { isBriefExpanded = !isBriefExpanded },
                                    contentTab = contentTab,
                                    onTabChange = { contentTab = it },
                                    isHotOrder = isHotOrder,
                                    onOrderChange = { isHotOrder = it },
                                    totalAlbums = totalAlbums,
                                    isFollowed = isFollowed,
                                    onToggleFollow = { FavoriteArtistsManager.toggleFollow(artistMid) },
                                )
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        } else {
                            LazyColumn(
                                state = albumListState,
                                contentPadding =
                                    PaddingValues(
                                        bottom = contentPadding.calculateBottomPadding() + 16.dp,
                                    ),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                item(key = "artist_header") {
                                    ArtistHeader(
                                        avatarUrl = avatarUrl,
                                        displayName = displayName,
                                        totalSongs = totalSongs,
                                        songCount = songs.size,
                                        brief = artistDetail?.brief.orEmpty(),
                                        isBriefExpanded = isBriefExpanded,
                                        onToggleBrief = { isBriefExpanded = !isBriefExpanded },
                                        contentTab = contentTab,
                                        onTabChange = { contentTab = it },
                                        isHotOrder = isHotOrder,
                                        onOrderChange = { isHotOrder = it },
                                        totalAlbums = totalAlbums,
                                        isFollowed = isFollowed,
                                        onToggleFollow = { FavoriteArtistsManager.toggleFollow(artistMid) },
                                    )
                                }
                                if (albums.isEmpty()) {
                                    item(key = "empty_albums") {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 48.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "暂无专辑",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                } else {
                                    items(
                                        count = albums.size,
                                        key = { albums[it].mid.ifBlank { albums[it].id.toString() } },
                                    ) { index ->
                                        val album = albums[index]
                                        AlbumListItem(
                                            album = album,
                                            onClick = {
                                                navController.navigateToAlbum(album.mid, album.name)
                                            },
                                        )
                                    }
                                    if (isLoadingMoreAlbums) {
                                        item(key = "loading_more_albums") {
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(16.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(24.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistHeader(
    avatarUrl: String,
    displayName: String,
    totalSongs: Int,
    songCount: Int,
    brief: String,
    isBriefExpanded: Boolean,
    onToggleBrief: () -> Unit,
    contentTab: ArtistContentTab,
    onTabChange: (ArtistContentTab) -> Unit,
    isHotOrder: Boolean,
    onOrderChange: (Boolean) -> Unit,
    totalAlbums: Int,
    isFollowed: Boolean,
    onToggleFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (avatarUrl.isNotBlank()) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = displayName,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .size(80.dp)
                            .clip(CircleShape),
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                val countText =
                    buildString {
                        val count = if (totalSongs > 0) totalSongs else songCount
                        append("共收录 $count 首单曲")
                        if (totalAlbums > 0) {
                            append(" · $totalAlbums 张专辑")
                        }
                    }
                Text(
                    text = countText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (brief.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = brief,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                maxLines = if (isBriefExpanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onToggleBrief),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = contentTab == ArtistContentTab.Songs,
                    onClick = {
                        if (contentTab == ArtistContentTab.Songs) {
                            onOrderChange(!isHotOrder)
                        } else {
                            onTabChange(ArtistContentTab.Songs)
                        }
                    },
                    label = { Text(if (isHotOrder) "热门" else "最新") },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.SwapVert,
                            contentDescription = "切换排序",
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
                FilterChip(
                    selected = contentTab == ArtistContentTab.Albums,
                    onClick = {
                        onTabChange(ArtistContentTab.Albums)
                    },
                    label = {
                        Text("专辑")
                    },
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isFollowed) {
                FilledTonalButton(
                    onClick = onToggleFollow,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("已关注", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                OutlinedButton(
                    onClick = onToggleFollow,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("关注", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun AlbumListItem(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coverUrl =
        remember(album.mid) {
            album.coverUrl.ifBlank {
                if (album.mid.isNotBlank()) MusicApiService.getAlbumCoverUrl(album.mid) else ""
            }
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
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
