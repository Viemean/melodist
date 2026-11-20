package org.melodist.mobile.ui.playlist

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.deleteSongsFromPlaylist
import org.melodist.api.getPlaylistDetail
import org.melodist.api.getPlaylistSongs
import org.melodist.data.MillionRecommendManager
import org.melodist.data.UserLibraryCacheManager
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.mobile.ui.components.CommonSongList
import org.melodist.mobile.ui.components.MediaDescriptionCard
import org.melodist.mobile.ui.components.MediaInfoBottomSheet
import org.melodist.mobile.ui.components.RotatingCardCoordinator
import org.melodist.mobile.ui.components.SongListDeleteType
import org.melodist.mobile.ui.components.formatMediaCount
import org.melodist.mobile.ui.components.landscapeNestedScroll
import org.melodist.model.CoverScenario
import org.melodist.model.CoverUrlResolver
import org.melodist.model.PlaybackSourceContext
import org.melodist.model.Playlist
import org.melodist.model.Song
import org.melodist.playback.CoverMemoryManager
import org.melodist.playback.PlaybackManager
import org.melodist.playback.QueuePaginationSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlist: Playlist,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiService = remember { MusicApiService() }

    val libraryData by UserLibraryCacheManager.libraryFlow.collectAsState()
    val isFavorite =
        remember(libraryData.playlists, playlist.dirId, playlist.tid) {
            val dissId = if (playlist.tid > 0L) playlist.tid else playlist.dirId
            libraryData.playlists.any {
                (playlist.tid > 0L && it.tid == playlist.tid) ||
                    (playlist.dirId > 0L && it.dirId == playlist.dirId) ||
                    (dissId > 0L && (it.tid == dissId || it.dirId == dissId))
            }
        }
    val canFavorite =
        remember(playlist.isMyFavorite, playlist.isMillionRecommend, playlist.isCreated) {
            !playlist.isMyFavorite && !playlist.isMillionRecommend && !playlist.isCreated
        }

    val cachedInitialSongs =
        remember(playlist.dirId, playlist.tid) {
            if (playlist.isMyFavorite) {
                UserLibraryCacheManager.favoriteSongsFlow.value
            } else if (playlist.isMillionRecommend) {
                MillionRecommendManager.resultFlow.value.songs
            } else {
                UserLibraryCacheManager.getCachedPlaylistSongs(playlist.dirId, playlist.tid).orEmpty()
            }
        }
    var songs by remember(playlist.dirId, playlist.tid) {
        mutableStateOf<List<Song>>(cachedInitialSongs)
    }
    var isLoading by remember(playlist.dirId, playlist.tid) {
        mutableStateOf(
            if (playlist.isMyFavorite) {
                UserLibraryCacheManager.favoriteSongsFlow.value.isEmpty()
            } else if (playlist.isMillionRecommend) {
                MillionRecommendManager.resultFlow.value.songs
                    .isEmpty()
            } else {
                cachedInitialSongs.isEmpty()
            },
        )
    }
    var isLoadingMore by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(!playlist.isMyFavorite && !playlist.isMillionRecommend) }
    var totalCount by remember {
        mutableIntStateOf(
            if (playlist.isMillionRecommend &&
                MillionRecommendManager.resultFlow.value.songs
                    .isNotEmpty()
            ) {
                val num = MillionRecommendManager.resultFlow.value.totalSongNum
                if (num > 0) num else MillionRecommendManager.resultFlow.value.songs.size
            } else {
                playlist.songCount
            },
        )
    }
    var syncJob by remember { mutableStateOf<Job?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var currentPlaylist by remember(playlist) {
        val cached = UserLibraryCacheManager.getCachedPlaylistMetadata(playlist.dirId, playlist.tid)
        val initial =
            if (cached != null) {
                cached.copy(
                    name = playlist.name.ifBlank { cached.name },
                    picUrl = playlist.picUrl.ifBlank { cached.picUrl },
                    songCount = if (playlist.songCount > 0) playlist.songCount else cached.songCount,
                )
            } else {
                playlist
            }
        mutableStateOf(initial)
    }
    var showDescriptionSheet by remember { mutableStateOf(false) }

    // Observe cached favorite songs for the isMyFavorite playlist
    val cachedFavSongs by UserLibraryCacheManager.favoriteSongsFlow.collectAsState()
    val isFavSongsLoading by UserLibraryCacheManager.isFavSongsLoading.collectAsState()
    val millionResult by MillionRecommendManager.resultFlow.collectAsState()
    val isMillionLoading by MillionRecommendManager.isLoadingFlow.collectAsState()

    val listState =
        remember(playlist.dirId, playlist.tid) {
            RotatingCardCoordinator.getOrCreateLazyListState("playlist_${playlist.dirId}_${playlist.tid}")
        }
    val showTopBarTitle by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 200
        }
    }

    DisposableEffect(playlist.picUrl) {
        onDispose {
            val url = playlist.picUrl
            if (url.isNotBlank()) {
                val rawUrl = url.replace(Regex("R[0-9]+x[0-9]+"), "")
                CoverMemoryManager.evictCoverFromMemory(context, rawUrl)
                CoverMemoryManager.evictCoverFromMemory(context, url)
            }
        }
    }

    // Favorite playlist: subscribe to cache flow; fall back to full network load when empty
    if (playlist.isMyFavorite) {
        LaunchedEffect(cachedFavSongs) {
            if (cachedFavSongs.isNotEmpty()) {
                songs = cachedFavSongs
                totalCount = cachedFavSongs.size
                hasMore = false
                isLoading = false
            }
        }
        LaunchedEffect(playlist.dirId) {
            // If cache is expired or empty, silently refresh in background
            if (!UserLibraryCacheManager.isFavoriteSongsCacheValid()) {
                if (cachedFavSongs.isEmpty()) isLoading = true
                withContext(Dispatchers.IO) {
                    UserLibraryCacheManager.loadFavoriteSongs(apiService)
                }
                isLoading = false
            }
        }
    } else if (playlist.isMillionRecommend) {
        // Million recommend playlist: subscribe to million result flow; load when empty
        LaunchedEffect(millionResult) {
            if (millionResult.songs.isNotEmpty()) {
                songs = millionResult.songs
                totalCount = if (millionResult.totalSongNum > 0) millionResult.totalSongNum else millionResult.songs.size
                hasMore = false
                isLoading = false
            }
        }
        LaunchedEffect(playlist.dirId) {
            if (millionResult.songs.isEmpty()) {
                isLoading = true
                withContext(Dispatchers.IO) {
                    MillionRecommendManager.refresh(apiService, forceRefresh = false)
                }
                isLoading = false
            }
        }
    } else {
        // Non-favorite, non-million playlist: cache-first with silent background refresh matching favorites rule
        LaunchedEffect(playlist.dirId, playlist.tid) {
            val cached = UserLibraryCacheManager.getCachedPlaylistSongs(playlist.dirId, playlist.tid)
            if (!cached.isNullOrEmpty()) {
                songs = cached
                totalCount = if (currentPlaylist.songCount > 0) currentPlaylist.songCount else cached.size
                hasMore = false
                isLoading = false
            }

            if (!UserLibraryCacheManager.isPlaylistSongsCacheValid(playlist.dirId, playlist.tid)) {
                if (cached.isNullOrEmpty()) {
                    isLoading = true
                }
                val detailResult =
                    withContext(Dispatchers.IO) {
                        apiService.getPlaylistDetail(
                            dirId = playlist.dirId,
                            tid = playlist.tid,
                            isFav = playlist.isFav,
                            page = 1,
                            pageSize = 100,
                        )
                    }
                if (detailResult != null) {
                    currentPlaylist = detailResult.playlist
                    UserLibraryCacheManager.saveCachedPlaylistMetadata(detailResult.playlist)
                    if (detailResult.songs.isNotEmpty()) {
                        songs = detailResult.songs
                        totalCount = if (detailResult.playlist.songCount > 0) detailResult.playlist.songCount else detailResult.songs.size
                        hasMore = false
                        UserLibraryCacheManager.savePlaylistSongsCache(
                            playlist.dirId,
                            playlist.tid,
                            detailResult.songs,
                            detailResult.songs.size,
                        )
                    }
                } else {
                    val fetched =
                        withContext(Dispatchers.IO) {
                            UserLibraryCacheManager.loadPlaylistSongs(
                                apiService = apiService,
                                dirId = playlist.dirId,
                                tid = playlist.tid,
                                isFav = playlist.isFav,
                                forceRefresh = false,
                                targetTotalCount = playlist.songCount,
                            )
                        }
                    if (fetched.isNotEmpty()) {
                        songs = fetched
                        totalCount = if (currentPlaylist.songCount > 0) currentPlaylist.songCount else fetched.size
                        hasMore = false
                    }
                }
                isLoading = false
            } else {
                scope.launch(Dispatchers.IO) {
                    val detail = apiService.getPlaylistDetail(playlist.dirId, playlist.tid, playlist.isFav, page = 1, pageSize = 1)
                    if (detail != null) {
                        withContext(Dispatchers.Main) {
                            currentPlaylist = detail.playlist
                            UserLibraryCacheManager.saveCachedPlaylistMetadata(detail.playlist)
                        }
                    }
                }
            }
        }
    }

    // Load more for non-favorite playlists (favorite is fully loaded from cache)
    val shouldLoadMore by remember {
        derivedStateOf {
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisibleIndex =
                listState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: 0
            totalItems > 0 && lastVisibleIndex >= totalItems - 4
        }
    }

    val playlistTag =
        remember(playlist.dirId, playlist.tid, playlist.isFav, playlist.isMyFavorite, playlist.isMillionRecommend) {
            if (playlist.isMyFavorite) {
                "favorites"
            } else if (playlist.isMillionRecommend) {
                "million_recommend"
            } else {
                "playlist_${playlist.dirId}_${playlist.tid}"
            }
        }

    val playlistSourceContext =
        remember(playlist.dirId, playlist.tid, playlist.isFav, playlist.isMyFavorite, playlist.isMillionRecommend) {
            val reportId =
                when {
                    playlist.isMyFavorite -> null
                    playlist.isMillionRecommend -> null
                    playlist.tid > 0L -> playlist.tid.toString()
                    playlist.dirId > 0L && playlist.dirId != 201L -> playlist.dirId.toString()
                    else -> null
                }
            reportId?.let { PlaybackSourceContext.Playlist(it) }
        }

    val playlistPaginationSource =
        remember(playlist.dirId, playlist.tid, playlist.isFav, playlist.isMyFavorite, playlist.isMillionRecommend) {
            if (!playlist.isMyFavorite && !playlist.isMillionRecommend) {
                object : QueuePaginationSource {
                    override val hasMore: Boolean get() = hasMore
                    override val isLoadingMore: Boolean get() = isLoadingMore

                    override suspend fun loadMore(): List<Song> {
                        if (playlist.isMyFavorite || playlist.isMillionRecommend || !hasMore || isLoading || isLoadingMore) return emptyList()
                        isLoadingMore = true
                        val nextPage = currentPage + 1
                        val nextSongs =
                            withContext(Dispatchers.IO) {
                                try {
                                    apiService.getPlaylistSongs(
                                        dirId = playlist.dirId,
                                        tid = playlist.tid,
                                        isFav = playlist.isFav,
                                        page = nextPage,
                                        pageSize = 100,
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w("PlaylistDetail", "Failed to load playlist songs: dirId=${playlist.dirId}, page=$nextPage", e)
                                    emptyList()
                                }
                            }
                        val toAdd =
                            if (nextSongs.isNotEmpty()) {
                                hasMore = nextSongs.size >= 100
                                val existingMids = songs.map { it.songMid }.toSet()
                                val filtered = nextSongs.filter { it.songMid.isNotBlank() && !existingMids.contains(it.songMid) }
                                if (filtered.isNotEmpty()) {
                                    songs = songs + filtered
                                }
                                currentPage = nextPage
                                filtered
                            } else {
                                hasMore = false
                                emptyList()
                            }
                        isLoadingMore = false
                        return toAdd
                    }
                }
            } else {
                null
            }
        }

    LaunchedEffect(shouldLoadMore) {
        if (playlist.isMyFavorite || playlist.isMillionRecommend) return@LaunchedEffect
        if (shouldLoadMore && hasMore && !isLoading && !isLoadingMore) {
            scope.launch {
                playlistPaginationSource?.loadMore()
            }
        }
    }

    // Background pipeline to load remaining pages for non-favorite playlists
    fun startBackgroundSyncRemaining() {
        if (playlist.isMyFavorite || playlist.isMillionRecommend) return
        if (!hasMore) return
        if (syncJob?.isActive == true) return

        syncJob =
            scope.launch(Dispatchers.IO) {
                var p = currentPage + 1
                var more = true
                while (more && isActive) {
                    if (PlaybackManager.queueTag.value != playlistTag) return@launch
                    val nextSongs =
                        try {
                            val res =
                                apiService.getPlaylistSongs(
                                    dirId = playlist.dirId,
                                    tid = playlist.tid,
                                    isFav = playlist.isFav,
                                    page = p,
                                    pageSize = 100,
                                )
                            more = res.size >= 100
                            res
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("PlaylistDetail", "Failed in background sync remaining playlist songs: dirId=${playlist.dirId}, page=$p", e)
                            emptyList()
                        }

                    if (nextSongs.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            val existingMids = songs.map { it.songMid }.toSet()
                            val toAdd = nextSongs.filter { it.songMid.isNotBlank() && !existingMids.contains(it.songMid) }
                            if (toAdd.isNotEmpty()) {
                                songs = songs + toAdd
                                PlaybackManager.appendPlaylist(toAdd, targetTag = playlistTag)
                            }
                            currentPage = p
                            hasMore = more
                        }
                        p++
                    } else {
                        more = false
                        withContext(Dispatchers.Main) { hasMore = false }
                    }
                }
            }
    }

    val topBarState =
        org.melodist.mobile.ui.components
            .rememberLandscapeCollapsibleTopBarState()

    Scaffold(
        topBar = {
            org.melodist.mobile.ui.components.LandscapeCollapsibleTopBar(visible = topBarState.isVisible) {
                TopAppBar(
                    title = {
                        AnimatedVisibility(
                            visible = showTopBarTitle,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    actions = {
                        if (canFavorite) {
                            IconButton(
                                onClick = {
                                    if (!UserSession.isLoggedIn) {
                                        Toast.makeText(context, "请先登录", Toast.LENGTH_SHORT).show()
                                        return@IconButton
                                    }
                                    val newFav = !isFavorite
                                    UserLibraryCacheManager.onPlaylistFavoriteToggled(playlist, newFav)
                                    Toast
                                        .makeText(
                                            context,
                                            if (newFav) "已收藏歌单" else "已取消收藏歌单",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                },
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = if (isFavorite) "取消收藏" else "收藏歌单",
                                    tint = if (isFavorite) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurface,
                                )
                            }
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
        val pullRefreshState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing =
                isRefreshing ||
                    (playlist.isMyFavorite && isFavSongsLoading && songs.isNotEmpty()) ||
                    (playlist.isMillionRecommend && isMillionLoading && songs.isNotEmpty()),
            onRefresh = {
                if (playlist.isMyFavorite) {
                    scope.launch {
                        isRefreshing = true
                        withContext(Dispatchers.IO) {
                            UserLibraryCacheManager.loadFavoriteSongs(apiService, forceRefresh = true)
                        }
                        isRefreshing = false
                    }
                } else if (playlist.isMillionRecommend) {
                    scope.launch {
                        isRefreshing = true
                        withContext(Dispatchers.IO) {
                            MillionRecommendManager.refresh(apiService, forceRefresh = true)
                        }
                        isRefreshing = false
                    }
                } else {
                    scope.launch {
                        isRefreshing = true
                        val detailResult =
                            withContext(Dispatchers.IO) {
                                apiService.getPlaylistDetail(
                                    dirId = playlist.dirId,
                                    tid = playlist.tid,
                                    isFav = playlist.isFav,
                                    page = 1,
                                    pageSize = 100,
                                )
                            }
                        if (detailResult != null) {
                            currentPlaylist = detailResult.playlist
                            UserLibraryCacheManager.saveCachedPlaylistMetadata(detailResult.playlist)
                            if (detailResult.songs.isNotEmpty()) {
                                songs = detailResult.songs
                                totalCount = if (detailResult.playlist.songCount > 0) detailResult.playlist.songCount else detailResult.songs.size
                                hasMore = false
                                UserLibraryCacheManager.savePlaylistSongsCache(
                                    playlist.dirId,
                                    playlist.tid,
                                    detailResult.songs,
                                    detailResult.songs.size,
                                )
                            }
                        } else {
                            val fetched =
                                withContext(Dispatchers.IO) {
                                    UserLibraryCacheManager.loadPlaylistSongs(
                                        apiService = apiService,
                                        dirId = playlist.dirId,
                                        tid = playlist.tid,
                                        isFav = playlist.isFav,
                                        forceRefresh = true,
                                        targetTotalCount = playlist.songCount,
                                    )
                                }
                            if (fetched.isNotEmpty()) {
                                songs = fetched
                                totalCount = if (currentPlaylist.songCount > 0) currentPlaylist.songCount else fetched.size
                                hasMore = false
                            }
                        }
                        isLoading = false
                        isRefreshing = false
                    }
                }
            },
            state = pullRefreshState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = scaffoldPadding.calculateTopPadding()),
        ) {
            if (isLoading && songs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                val canDelete = playlist.isMyFavorite || playlist.isCreated
                CommonSongList(
                    songs = songs,
                    state = listState,
                    paginationSource = playlistPaginationSource,
                    deleteType = if (canDelete) SongListDeleteType.PlaylistTrack else SongListDeleteType.None,
                    playlistDirId = playlist.dirId,
                    onDeleteSelected = { deletedSongs ->
                        val deletedMids = deletedSongs.map { it.songMid }.toSet()
                        val success = apiService.deleteSongsFromPlaylist(playlist.dirId, deletedSongs)
                        if (success) {
                            UserLibraryCacheManager.onSongsRemovedFromPlaylist(playlist.dirId, deletedSongs)
                            if (playlist.dirId == 201L) {
                                val currentFavs = PlaybackManager.favoriteSongMids.value.filter { !deletedMids.contains(it) }
                                PlaybackManager.setFavoriteSongMids(currentFavs.toSet())
                            }
                            songs = songs.filterNot { deletedMids.contains(it.songMid) }
                            Toast.makeText(context, "已从歌单移除 ${deletedSongs.size} 首歌曲", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "移除失败", Toast.LENGTH_SHORT).show()
                        }
                    },
                    contentPadding =
                        PaddingValues(
                            bottom = contentPadding.calculateBottomPadding() + 16.dp,
                        ),
                    onSongClick = { list, index ->
                        PlaybackManager.setPlaylist(
                            list,
                            startIndex = index,
                            paginationSource = playlistPaginationSource,
                            queueTag = playlistTag,
                            sourceContext = playlistSourceContext,
                        )
                        startBackgroundSyncRemaining()
                    },
                    headerItems = {
                        item(key = "playlist_header") {
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
                                    val effectiveCoverUrl =
                                        remember(playlist.picUrl, playlist.isMillionRecommend, playlist.isMyFavorite, millionResult.coverUrl, songs) {
                                            playlist.picUrl.ifBlank {
                                                if (playlist.isMillionRecommend) {
                                                    millionResult.coverUrl.ifBlank { songs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty() }
                                                } else {
                                                    songs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty()
                                                }
                                            }
                                        }

                                    val detailCandidates =
                                        remember(effectiveCoverUrl) {
                                            CoverUrlResolver.getCandidates(effectiveCoverUrl, CoverScenario.DETAIL)
                                        }

                                    Box(modifier = Modifier.size(110.dp)) {
                                        AlbumArtImage(
                                            coverUrl = effectiveCoverUrl,
                                            candidates = detailCandidates,
                                            contentDescription = playlist.name,
                                            shape = RoundedCornerShape(14.dp),
                                            elevation = 8.dp,
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                                            placeholderIconSize = 48.dp,
                                            placeholderContent = {
                                                val icon =
                                                    if (playlist.isMyFavorite) {
                                                        Icons.Rounded.Favorite
                                                    } else if (playlist.isMillionRecommend) {
                                                        Icons.Rounded.Whatshot
                                                    } else {
                                                        Icons.Rounded.MusicNote
                                                    }
                                                val tint =
                                                    if (playlist.isMyFavorite) {
                                                        MaterialTheme.colorScheme.error
                                                    } else if (playlist.isMillionRecommend) {
                                                        MaterialTheme.colorScheme.tertiary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    }
                                                Icon(
                                                    imageVector = icon,
                                                    contentDescription = null,
                                                    tint = tint,
                                                    modifier = Modifier.size(48.dp),
                                                )
                                            },
                                            modifier = Modifier.fillMaxSize(),
                                        )

                                        if (playlist.isMyFavorite && effectiveCoverUrl.isNotBlank()) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                                shadowElevation = 2.dp,
                                                modifier =
                                                    Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .padding(6.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Favorite,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier =
                                                        Modifier
                                                            .padding(4.dp)
                                                            .size(16.dp),
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(16.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = currentPlaylist.name.ifBlank { playlist.name },
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )

                                        Spacer(modifier = Modifier.height(6.dp))

                                        val metaInfo =
                                            buildList {
                                                if (currentPlaylist.creator.isNotBlank()) {
                                                    add(currentPlaylist.creator)
                                                }
                                                add("${totalCount}首歌曲")
                                            }.joinToString(" · ")

                                        Text(
                                            text = metaInfo,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )

                                        if (playlist.isCreated) {
                                            Text(
                                                text = "自建歌单",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(top = 2.dp),
                                            )
                                        }
                                    }
                                }

                                val displayDescription =
                                    currentPlaylist.description.ifBlank {
                                        if (playlist.isMillionRecommend) millionResult.description.ifBlank { "每一首歌曲都超过百万收藏 · 每日更新" } else ""
                                    }

                                val hasDescriptionOrDetails =
                                    !playlist.isMyFavorite &&
                                        (
                                            displayDescription.isNotBlank() || currentPlaylist.creator.isNotBlank() || currentPlaylist.tags.isNotEmpty() ||
                                                currentPlaylist.playCount > 0L
                                        )

                                AnimatedVisibility(
                                    visible = hasDescriptionOrDetails,
                                    enter = androidx.compose.animation.expandVertically() + fadeIn(),
                                    exit = androidx.compose.animation.shrinkVertically() + fadeOut(),
                                ) {
                                    Column {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        val summaryText =
                                            displayDescription.trim().ifBlank {
                                                listOfNotNull(
                                                    currentPlaylist.creator.takeIf { it.isNotBlank() }?.let { "作者: $it" },
                                                    currentPlaylist.tags.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                                                    formatMediaCount(currentPlaylist.playCount).takeIf { it.isNotBlank() }?.let { "$it 播放" },
                                                ).joinToString(" · ").ifBlank { "点击查看完整歌单详情" }
                                            }
                                        MediaDescriptionCard(
                                            title = "歌单简介",
                                            description = summaryText,
                                            onClick = { showDescriptionSheet = true },
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
                                                PlaybackManager.setPlaylist(
                                                    songs,
                                                    startIndex = 0,
                                                    paginationSource = playlistPaginationSource,
                                                    queueTag = playlistTag,
                                                    sourceContext = playlistSourceContext,
                                                )
                                                startBackgroundSyncRemaining()
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
                                                    paginationSource = playlistPaginationSource,
                                                    queueTag = playlistTag,
                                                    sourceContext = playlistSourceContext,
                                                )
                                                startBackgroundSyncRemaining()
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

    if (showDescriptionSheet) {
        val displayDescription =
            currentPlaylist.description.ifBlank {
                if (playlist.isMillionRecommend) millionResult.description.ifBlank { "每一首歌曲都超过百万收藏 · 每日更新" } else ""
            }
        val items =
            buildList {
                add("歌单：" to (currentPlaylist.name.ifBlank { playlist.name }))
                if (currentPlaylist.creator.isNotBlank()) {
                    add("作者：" to currentPlaylist.creator)
                }
                if (currentPlaylist.tags.isNotEmpty()) {
                    add("标签：" to currentPlaylist.tags.joinToString(" · "))
                }
                if (currentPlaylist.playCount > 0L) {
                    add("播放量：" to formatMediaCount(currentPlaylist.playCount))
                }
                if (currentPlaylist.favCount > 0L) {
                    add("收藏量：" to formatMediaCount(currentPlaylist.favCount))
                }
            }
        MediaInfoBottomSheet(
            title = "歌单详情",
            items = items,
            description = displayDescription.trim().ifBlank { "暂无歌单简介信息" },
            descriptionLabel = "歌单简介：",
            onDismissRequest = { showDescriptionSheet = false },
        )
    }
}
