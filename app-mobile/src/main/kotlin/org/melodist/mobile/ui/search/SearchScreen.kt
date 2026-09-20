package org.melodist.mobile.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.search
import org.melodist.api.searchAlbums
import org.melodist.api.searchPlaylists
import org.melodist.data.SearchKeywordHistoryManager
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.mobile.ui.components.CommonSongList
import org.melodist.mobile.ui.navigation.LocalAppNavigation
import org.melodist.model.Album
import org.melodist.model.Playlist
import org.melodist.model.Song
import org.melodist.playback.QueuePaginationSource

enum class SearchTab(
    val title: String,
) {
    Song("歌曲"),
    Playlist("歌单"),
    Album("专辑"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val apiService = remember { MusicApiService() }
    val navController = LocalAppNavigation.current

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }

    var query by remember { mutableStateOf("") }
    var currentTab by remember { mutableStateOf(SearchTab.Song) }
    var historyKeywords by remember { mutableStateOf(SearchKeywordHistoryManager.getKeywords()) }

    val songListState = rememberLazyListState()
    val playlistListState = rememberLazyListState()
    val albumListState = rememberLazyListState()

    var songResults by remember { mutableStateOf<List<Song>>(emptyList()) }
    var playlistResults by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var albumResults by remember { mutableStateOf<List<Album>>(emptyList()) }

    var songPage by remember { mutableIntStateOf(1) }
    var playlistPage by remember { mutableIntStateOf(1) }
    var albumPage by remember { mutableIntStateOf(1) }

    var hasMoreSongs by remember { mutableStateOf(true) }
    var hasMorePlaylists by remember { mutableStateOf(true) }
    var hasMoreAlbums by remember { mutableStateOf(true) }

    var isLoadingMoreSongs by remember { mutableStateOf(false) }
    var isLoadingMorePlaylists by remember { mutableStateOf(false) }
    var isLoadingMoreAlbums by remember { mutableStateOf(false) }

    var activeQuery by remember { mutableStateOf("") }
    var loadedTabs by remember { mutableStateOf<Set<SearchTab>>(emptySet()) }
    var isLoading by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }

    fun executeSearch(
        targetKeyword: String,
        targetTab: SearchTab,
    ) {
        val trimmed = targetKeyword.trim()
        if (trimmed.isBlank()) return

        focusManager.clearFocus()
        SearchKeywordHistoryManager.recordKeyword(trimmed)
        historyKeywords = SearchKeywordHistoryManager.getKeywords()

        val isNewQuery = trimmed != activeQuery
        if (isNewQuery) {
            activeQuery = trimmed
            songResults = emptyList()
            playlistResults = emptyList()
            albumResults = emptyList()
            loadedTabs = emptySet()
            hasSearched = true

            songPage = 1
            playlistPage = 1
            albumPage = 1
            hasMoreSongs = true
            hasMorePlaylists = true
            hasMoreAlbums = true
        }

        currentTab = targetTab
        if (targetTab in loadedTabs) return

        scope.launch {
            isLoading = true
            hasSearched = true
            withContext(Dispatchers.IO) {
                try {
                    when (targetTab) {
                        SearchTab.Song -> {
                            songPage = 1
                            val list = apiService.search(trimmed, page = 1, pageSize = 30)
                            songResults = list
                            hasMoreSongs = list.size >= 30
                        }
                        SearchTab.Playlist -> {
                            playlistPage = 1
                            val list = apiService.searchPlaylists(trimmed, page = 1, pageSize = 30)
                            playlistResults = list
                            hasMorePlaylists = list.size >= 30
                        }
                        SearchTab.Album -> {
                            albumPage = 1
                            val list = apiService.searchAlbums(trimmed, page = 1, pageSize = 30)
                            albumResults = list
                            hasMoreAlbums = list.size >= 30
                        }
                    }
                    loadedTabs = loadedTabs + targetTab
                } catch (_: Exception) {
                }
            }
            isLoading = false
        }
    }

    val searchQueuePaginationSource =
        remember(activeQuery) {
            if (activeQuery.isNotBlank()) {
                object : QueuePaginationSource {
                    override val hasMore: Boolean get() = hasMoreSongs
                    override val isLoadingMore: Boolean get() = isLoadingMoreSongs

                    override suspend fun loadMore(): List<Song> {
                        if (isLoadingMoreSongs || !hasMoreSongs || isLoading || activeQuery.isBlank()) return emptyList()
                        isLoadingMoreSongs = true
                        val nextPage = songPage + 1
                        val nextList =
                            withContext(Dispatchers.IO) {
                                try {
                                    apiService.search(activeQuery, page = nextPage, pageSize = 30)
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            }
                        val newUnique =
                            if (nextList.isEmpty()) {
                                hasMoreSongs = false
                                emptyList()
                            } else {
                                val existingMids = songResults.map { it.songMid }.toSet()
                                val filtered = nextList.filter { it.songMid !in existingMids }
                                if (filtered.isEmpty() || nextList.size < 30) {
                                    hasMoreSongs = false
                                }
                                filtered
                            }
                        if (newUnique.isNotEmpty()) {
                            songResults = songResults + newUnique
                            songPage = nextPage
                        }
                        isLoadingMoreSongs = false
                        return newUnique
                    }
                }
            } else {
                null
            }
        }

    fun loadMoreSongs() {
        if (isLoadingMoreSongs || !hasMoreSongs || isLoading || activeQuery.isBlank()) return
        scope.launch {
            searchQueuePaginationSource?.loadMore()
        }
    }

    fun loadMorePlaylists() {
        if (isLoadingMorePlaylists || !hasMorePlaylists || isLoading || activeQuery.isBlank()) return
        scope.launch {
            isLoadingMorePlaylists = true
            val nextPage = playlistPage + 1
            val nextList =
                withContext(Dispatchers.IO) {
                    try {
                        apiService.searchPlaylists(activeQuery, page = nextPage, pageSize = 30)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            if (nextList.isEmpty()) {
                hasMorePlaylists = false
            } else {
                val existingIds = playlistResults.map { it.dirId }.toSet()
                val newUnique = nextList.filter { it.dirId !in existingIds }
                if (newUnique.isEmpty() || nextList.size < 30) {
                    hasMorePlaylists = false
                }
                playlistResults = playlistResults + newUnique
                playlistPage = nextPage
            }
            isLoadingMorePlaylists = false
        }
    }

    fun loadMoreAlbums() {
        if (isLoadingMoreAlbums || !hasMoreAlbums || isLoading || activeQuery.isBlank()) return
        scope.launch {
            isLoadingMoreAlbums = true
            val nextPage = albumPage + 1
            val nextList =
                withContext(Dispatchers.IO) {
                    try {
                        apiService.searchAlbums(activeQuery, page = nextPage, pageSize = 30)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            if (nextList.isEmpty()) {
                hasMoreAlbums = false
            } else {
                val existingMids = albumResults.map { it.mid.ifBlank { it.id.toString() } }.toSet()
                val newUnique = nextList.filter { it.mid.ifBlank { it.id.toString() } !in existingMids }
                if (newUnique.isEmpty() || nextList.size < 30) {
                    hasMoreAlbums = false
                }
                albumResults = albumResults + newUnique
                albumPage = nextPage
            }
            isLoadingMoreAlbums = false
        }
    }

    LaunchedEffect(songListState, hasMoreSongs, isLoadingMoreSongs, isLoading) {
        snapshotFlow {
            val layoutInfo = songListState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleItemIndex >= totalItems - 4
        }.collect { shouldLoadMore ->
            if (shouldLoadMore && hasMoreSongs && !isLoadingMoreSongs && !isLoading && activeQuery.isNotBlank()) {
                loadMoreSongs()
            }
        }
    }

    LaunchedEffect(playlistListState, hasMorePlaylists, isLoadingMorePlaylists, isLoading) {
        snapshotFlow {
            val layoutInfo = playlistListState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleItemIndex >= totalItems - 4
        }.collect { shouldLoadMore ->
            if (shouldLoadMore && hasMorePlaylists && !isLoadingMorePlaylists && !isLoading && activeQuery.isNotBlank()) {
                loadMorePlaylists()
            }
        }
    }

    LaunchedEffect(albumListState, hasMoreAlbums, isLoadingMoreAlbums, isLoading) {
        snapshotFlow {
            val layoutInfo = albumListState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleItemIndex >= totalItems - 4
        }.collect { shouldLoadMore ->
            if (shouldLoadMore && hasMoreAlbums && !isLoadingMoreAlbums && !isLoading && activeQuery.isNotBlank()) {
                loadMoreAlbums()
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部 M3 胶囊搜索栏（与主界面胶囊形态严格对齐）
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border =
                        BorderStroke(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        ),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onBack != null) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "返回",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.width(10.dp))
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = "搜索",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 6.dp)
                                    .focusRequester(focusRequester),
                            singleLine = true,
                            textStyle =
                                MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions =
                                KeyboardActions(
                                    onSearch = { executeSearch(query, currentTab) },
                                ),
                            decorationBox = { innerTextField ->
                                if (query.isEmpty()) {
                                    Text(
                                        text = "搜索歌曲、歌手或专辑...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                }
                                innerTextField()
                            },
                        )

                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    query = ""
                                    hasSearched = false
                                    activeQuery = ""
                                    loadedTabs = emptySet()
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Clear,
                                    contentDescription = "清除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                }
            }

            if (!hasSearched) {
                // 未发起搜索时展示搜索历史
                if (historyKeywords.isNotEmpty()) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "搜索历史",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            IconButton(
                                onClick = {
                                    SearchKeywordHistoryManager.clearKeywords()
                                    historyKeywords = emptyList()
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "清除搜索记录",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            historyKeywords.forEach { keyword ->
                                Surface(
                                    modifier =
                                        Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .clickable {
                                                query = keyword
                                                executeSearch(keyword, SearchTab.Song)
                                            },
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Text(
                                        text = keyword,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "输入歌曲名、歌单或专辑开始搜索",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            } else {
                // 搜索结果顶部 Tab 选择栏（类似主页 FilterChip）
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SearchTab.entries.forEach { tab ->
                        FilterChip(
                            selected = currentTab == tab,
                            onClick = {
                                currentTab = tab
                                if (activeQuery.isNotBlank() && tab !in loadedTabs) {
                                    executeSearch(activeQuery, tab)
                                }
                            },
                            label = { Text(tab.title) },
                            shape = RoundedCornerShape(8.dp),
                            colors =
                                FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                        )
                    }
                }

                if (isLoading) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    when (currentTab) {
                        SearchTab.Song -> {
                            if (songResults.isEmpty()) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "未找到相关歌曲",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                CommonSongList(
                                    songs = songResults,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    state = songListState,
                                    paginationSource = searchQueuePaginationSource,
                                    contentPadding =
                                        PaddingValues(
                                            top = 4.dp,
                                            bottom = contentPadding.calculateBottomPadding() + 16.dp,
                                        ),
                                    headerItems = {
                                        item(key = "search_results_header") {
                                            Row(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    text = "共找到 ${songResults.size} 首歌曲",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    },
                                    footerItems = {
                                        if (isLoadingMoreSongs) {
                                            item(key = "song_load_more_indicator") {
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
                                )
                            }
                        }
                        SearchTab.Playlist -> {
                            if (playlistResults.isEmpty()) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "未找到相关歌单",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    state = playlistListState,
                                    contentPadding =
                                        PaddingValues(
                                            top = 4.dp,
                                            bottom = contentPadding.calculateBottomPadding() + 16.dp,
                                        ),
                                ) {
                                    itemsIndexed(playlistResults, key = { _, pl -> pl.dirId }) { _, playlist ->
                                        Row(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clickable { navController.navigateToPlaylist(playlist) }
                                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            AlbumArtImage(
                                                coverUrl = playlist.thumbnailPicUrl,
                                                contentDescription = playlist.name,
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.size(52.dp),
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = playlist.name,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "${playlist.songCount} 首歌曲",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                    }
                                    if (isLoadingMorePlaylists) {
                                        item(key = "playlist_load_more_indicator") {
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
                                }
                            }
                        }
                        SearchTab.Album -> {
                            if (albumResults.isEmpty()) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "未找到相关专辑",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    state = albumListState,
                                    contentPadding =
                                        PaddingValues(
                                            top = 4.dp,
                                            bottom = contentPadding.calculateBottomPadding() + 16.dp,
                                        ),
                                ) {
                                    itemsIndexed(albumResults, key = { _, album -> album.mid.ifBlank { album.id.toString() } }) { _, album ->
                                        Row(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clickable { navController.navigateToAlbum(album.mid, album.name) }
                                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            AlbumArtImage(
                                                coverUrl = album.coverUrl,
                                                contentDescription = album.name,
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.size(52.dp),
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = album.name,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                val subText =
                                                    buildString {
                                                        if (album.artist.isNotBlank()) append(album.artist)
                                                        if (album.songCount > 0) {
                                                            if (isNotEmpty()) append(" · ")
                                                            append("${album.songCount} 首")
                                                        }
                                                    }
                                                Text(
                                                    text = subText.ifBlank { "专辑" },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                    }
                                    if (isLoadingMoreAlbums) {
                                        item(key = "album_load_more_indicator") {
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
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
