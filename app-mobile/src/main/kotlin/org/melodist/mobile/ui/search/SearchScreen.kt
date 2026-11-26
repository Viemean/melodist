package org.melodist.mobile.ui.search

import android.util.Log
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.getSearchSuggestions
import org.melodist.api.search
import org.melodist.api.searchAlbums
import org.melodist.api.searchArtists
import org.melodist.api.searchPlaylists
import org.melodist.data.SearchKeywordHistoryManager
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.mobile.ui.components.CommonSongList
import org.melodist.mobile.ui.navigation.LocalAppNavigation
import org.melodist.model.Album
import org.melodist.model.Artist
import org.melodist.model.Playlist
import org.melodist.model.Song
import org.melodist.playback.QueuePaginationSource

enum class SearchTab(
    val title: String,
) {
    Song("歌曲"),
    Playlist("歌单"),
    Album("专辑"),
    Artist("歌手"),
}

class SearchScreenState {
    val queryState = TextFieldState()
    var query by mutableStateOf("")
    var currentTab by mutableStateOf(SearchTab.Song)
    var historyKeywords by mutableStateOf(SearchKeywordHistoryManager.getKeywords())

    val songListState =
        androidx.compose.foundation.lazy
            .LazyListState()
    val playlistListState =
        androidx.compose.foundation.lazy
            .LazyListState()
    val albumListState =
        androidx.compose.foundation.lazy
            .LazyListState()
    val artistListState =
        androidx.compose.foundation.lazy
            .LazyListState()

    var songResults by mutableStateOf<List<Song>>(emptyList())
    var playlistResults by mutableStateOf<List<Playlist>>(emptyList())
    var albumResults by mutableStateOf<List<Album>>(emptyList())
    var artistResults by mutableStateOf<List<Artist>>(emptyList())

    var songPage by mutableIntStateOf(1)
    var playlistPage by mutableIntStateOf(1)
    var albumPage by mutableIntStateOf(1)
    var artistPage by mutableIntStateOf(1)

    var hasMoreSongs by mutableStateOf(true)
    var hasMorePlaylists by mutableStateOf(true)
    var hasMoreAlbums by mutableStateOf(true)
    var hasMoreArtists by mutableStateOf(true)

    var isLoadingMoreSongs by mutableStateOf(false)
    var isLoadingMorePlaylists by mutableStateOf(false)
    var isLoadingMoreAlbums by mutableStateOf(false)
    var isLoadingMoreArtists by mutableStateOf(false)

    var activeQuery by mutableStateOf("")
    var loadedTabs by mutableStateOf<Set<SearchTab>>(emptySet())
    var isLoading by mutableStateOf(false)
    var hasSearched by mutableStateOf(false)
    var suggestions by mutableStateOf<List<String>>(emptyList())
    var showSuggestions by mutableStateOf(false)
    var focusAndSelectOnEntry by mutableStateOf(false)

    fun refreshHistoryKeywords() {
        historyKeywords = SearchKeywordHistoryManager.getKeywords()
    }

    fun clearSearch() {
        query = ""
        queryState.clearText()
        hasSearched = false
        activeQuery = ""
        suggestions = emptyList()
        showSuggestions = false
        songResults = emptyList()
        playlistResults = emptyList()
        albumResults = emptyList()
        artistResults = emptyList()
        loadedTabs = emptySet()
        songPage = 1
        playlistPage = 1
        albumPage = 1
        artistPage = 1
        hasMoreSongs = true
        hasMorePlaylists = true
        hasMoreAlbums = true
        hasMoreArtists = true
        isLoadingMoreSongs = false
        isLoadingMorePlaylists = false
        isLoadingMoreAlbums = false
        isLoadingMoreArtists = false
    }

    fun reset() {
        clearSearch()
        currentTab = SearchTab.Song
    }
}

private class SearchSongPaginationSource(
    private val searchState: SearchScreenState,
    private val apiService: MusicApiService,
    private val targetQuery: String,
) : QueuePaginationSource {
    override val hasMore: Boolean
        get() = searchState.hasMoreSongs && searchState.activeQuery == targetQuery

    override val isLoadingMore: Boolean
        get() = searchState.isLoadingMoreSongs

    override suspend fun loadMore(): List<Song> {
        if (searchState.isLoadingMoreSongs ||
            !searchState.hasMoreSongs ||
            searchState.isLoading ||
            targetQuery.isBlank() ||
            searchState.activeQuery != targetQuery
        ) {
            return emptyList()
        }
        searchState.isLoadingMoreSongs = true
        val nextPage = searchState.songPage + 1
        val nextList =
            withContext(Dispatchers.IO) {
                try {
                    apiService.search(targetQuery, page = nextPage, pageSize = 30)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("SearchScreen", "Failed to load more songs for query: $targetQuery, page: $nextPage", e)
                    emptyList()
                }
            }
        val newUnique =
            if (nextList.isEmpty()) {
                searchState.hasMoreSongs = false
                emptyList()
            } else {
                val existingMids = searchState.songResults.map { it.songMid }.toSet()
                val filtered = nextList.filter { it.songMid !in existingMids }
                if (filtered.isEmpty() || nextList.size < 30) {
                    searchState.hasMoreSongs = false
                }
                filtered
            }
        if (newUnique.isNotEmpty()) {
            searchState.songResults = searchState.songResults + newUnique
            searchState.songPage = nextPage
        }
        searchState.isLoadingMoreSongs = false
        return newUnique
    }
}

@OptIn(ExperimentalLayoutApi::class, FlowPreview::class)
@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    onBack: (() -> Unit)? = null,
    searchState: SearchScreenState = remember { SearchScreenState() },
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val apiService = remember { MusicApiService() }
    val navController = LocalAppNavigation.current

    LaunchedEffect(searchState.focusAndSelectOnEntry) {
        if (searchState.focusAndSelectOnEntry || (!searchState.hasSearched && searchState.query.isBlank())) {
            kotlinx.coroutines.delay(120)
            try {
                if (searchState.queryState.text.isNotEmpty()) {
                    searchState.queryState.edit { selectAll() }
                }
                focusRequester.requestFocus()
                keyboardController?.show()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("SearchTab", "Operation failed", e)
            } finally {
                searchState.focusAndSelectOnEntry = false
            }
        }
    }

    LaunchedEffect(searchState.queryState) {
        snapshotFlow { searchState.queryState.text.toString() }
            .collect {
                searchState.query = it
            }
    }

    LaunchedEffect(searchState.queryState) {
        snapshotFlow {
            searchState.queryState.text
                .toString()
                .trim()
        }.debounce(250L)
            .distinctUntilChanged()
            .collectLatest { trimmed ->
                if (trimmed.isEmpty() || trimmed == searchState.activeQuery) {
                    searchState.suggestions = emptyList()
                    searchState.showSuggestions = false
                } else {
                    val result =
                        withContext(Dispatchers.IO) {
                            apiService.getSearchSuggestions(trimmed)
                        }
                    val currentTrimmed =
                        searchState.queryState.text
                            .toString()
                            .trim()
                    if (currentTrimmed == trimmed && trimmed != searchState.activeQuery) {
                        searchState.suggestions = result
                        searchState.showSuggestions = result.isNotEmpty()
                    }
                }
            }
    }

    var query by searchState::query
    var currentTab by searchState::currentTab
    var historyKeywords by searchState::historyKeywords

    val songListState = searchState.songListState
    val playlistListState = searchState.playlistListState
    val albumListState = searchState.albumListState
    val artistListState = searchState.artistListState

    var songResults by searchState::songResults
    var playlistResults by searchState::playlistResults
    var albumResults by searchState::albumResults
    var artistResults by searchState::artistResults

    var songPage by searchState::songPage
    var playlistPage by searchState::playlistPage
    var albumPage by searchState::albumPage
    var artistPage by searchState::artistPage

    var hasMoreSongs by searchState::hasMoreSongs
    var hasMorePlaylists by searchState::hasMorePlaylists
    var hasMoreAlbums by searchState::hasMoreAlbums
    var hasMoreArtists by searchState::hasMoreArtists

    var isLoadingMoreSongs by searchState::isLoadingMoreSongs
    var isLoadingMorePlaylists by searchState::isLoadingMorePlaylists
    var isLoadingMoreAlbums by searchState::isLoadingMoreAlbums
    var isLoadingMoreArtists by searchState::isLoadingMoreArtists

    var activeQuery by searchState::activeQuery
    var loadedTabs by searchState::loadedTabs
    var isLoading by searchState::isLoading
    var hasSearched by searchState::hasSearched

    fun executeSearch(
        targetKeyword: String,
        targetTab: SearchTab,
    ) {
        val trimmed = targetKeyword.trim()
        if (trimmed.isBlank()) return

        focusManager.clearFocus()
        searchState.showSuggestions = false
        searchState.suggestions = emptyList()
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
            artistPage = 1
            hasMoreSongs = true
            hasMorePlaylists = true
            hasMoreAlbums = true
            hasMoreArtists = true
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

                        SearchTab.Artist -> {
                            artistPage = 1
                            val list = apiService.searchArtists(trimmed, page = 1, pageSize = 30)
                            artistResults = list
                            hasMoreArtists = list.size >= 30
                        }
                    }
                    loadedTabs = loadedTabs + targetTab
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("SearchScreen", "Search failed for query: $trimmed, tab: $targetTab", e)
                }
            }
            isLoading = false
        }
    }

    val searchQueuePaginationSource =
        remember(activeQuery, searchState, apiService) {
            if (activeQuery.isNotBlank()) {
                SearchSongPaginationSource(
                    searchState = searchState,
                    apiService = apiService,
                    targetQuery = activeQuery,
                )
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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("SearchScreen", "Failed to load more playlists for query: $activeQuery, page: $nextPage", e)
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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("SearchScreen", "Failed to load more albums for query: $activeQuery, page: $nextPage", e)
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

    fun loadMoreArtists() {
        if (isLoadingMoreArtists || !hasMoreArtists || isLoading || activeQuery.isBlank()) return
        scope.launch {
            isLoadingMoreArtists = true
            val nextPage = artistPage + 1
            val nextList =
                withContext(Dispatchers.IO) {
                    try {
                        apiService.searchArtists(activeQuery, page = nextPage, pageSize = 30)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("SearchScreen", "Failed to load more artists for query: $activeQuery, page: $nextPage", e)
                        emptyList()
                    }
                }
            if (nextList.isEmpty()) {
                hasMoreArtists = false
            } else {
                val existingMids = artistResults.map { it.mid.ifBlank { it.id.toString() } }.toSet()
                val newUnique = nextList.filter { it.mid.ifBlank { it.id.toString() } !in existingMids }
                if (newUnique.isEmpty() || nextList.size < 30) {
                    hasMoreArtists = false
                }
                artistResults = artistResults + newUnique
                artistPage = nextPage
            }
            isLoadingMoreArtists = false
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

    LaunchedEffect(artistListState, hasMoreArtists, isLoadingMoreArtists, isLoading) {
        snapshotFlow {
            val layoutInfo = artistListState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleItemIndex >= totalItems - 4
        }.collect { shouldLoadMore ->
            if (shouldLoadMore && hasMoreArtists && !isLoadingMoreArtists && !isLoading && activeQuery.isNotBlank()) {
                loadMoreArtists()
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                            state = searchState.queryState,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 6.dp)
                                    .focusRequester(focusRequester),
                            lineLimits = TextFieldLineLimits.SingleLine,
                            textStyle =
                                MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            onKeyboardAction = {
                                executeSearch(searchState.queryState.text.toString(), currentTab)
                            },
                            decorator = { innerTextField ->
                                if (searchState.queryState.text.isEmpty()) {
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
                                    searchState.clearSearch()
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

            if (searchState.showSuggestions && searchState.suggestions.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 4.dp,
                            bottom = contentPadding.calculateBottomPadding() + 16.dp,
                        ),
                ) {
                    itemsIndexed(
                        items = searchState.suggestions,
                        key = { index, item -> "${index}_$item" },
                    ) { _, suggestion ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        searchState.queryState.setTextAndPlaceCursorAtEnd(suggestion)
                                        executeSearch(suggestion, currentTab)
                                    }.padding(horizontal = 12.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else if (!hasSearched) {
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
                                                searchState.queryState.setTextAndPlaceCursorAtEnd(keyword)
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
                            text = "输入歌曲名、歌手、歌单或专辑开始搜索",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            } else {
                val pagerState =
                    rememberPagerState(
                        initialPage = currentTab.ordinal,
                        pageCount = { SearchTab.entries.size },
                    )

                LaunchedEffect(pagerState.settledPage, activeQuery) {
                    val settledTab = SearchTab.entries[pagerState.settledPage]
                    currentTab = settledTab
                    if (activeQuery.isNotBlank() && settledTab !in loadedTabs) {
                        executeSearch(activeQuery, settledTab)
                    }
                }

                LaunchedEffect(currentTab) {
                    if (pagerState.currentPage != currentTab.ordinal) {
                        pagerState.animateScrollToPage(currentTab.ordinal)
                    }
                }

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
                            selected = pagerState.currentPage == tab.ordinal,
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(tab.ordinal)
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

                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                ) { page ->
                    val tab = SearchTab.entries[page]
                    if ((isLoading && currentTab == tab) || (activeQuery.isNotBlank() && tab !in loadedTabs)) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        when (tab) {
                            SearchTab.Song -> {
                                if (songResults.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
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
                                        modifier = Modifier.fillMaxSize(),
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
                                                    val hintSuffix = if (hasMoreSongs) " · 下滑加载更多" else ""
                                                    Text(
                                                        text = "共找到 ${songResults.size} 首歌曲$hintSuffix",
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
                                        modifier = Modifier.fillMaxSize(),
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
                                        modifier = Modifier.fillMaxSize(),
                                        state = playlistListState,
                                        contentPadding =
                                            PaddingValues(
                                                top = 4.dp,
                                                bottom = contentPadding.calculateBottomPadding() + 16.dp,
                                            ),
                                    ) {
                                        item(key = "search_playlists_header") {
                                            Row(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                val hintSuffix = if (hasMorePlaylists) " · 下滑加载更多" else ""
                                                Text(
                                                    text = "共找到 ${playlistResults.size} 个歌单$hintSuffix",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
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
                                        modifier = Modifier.fillMaxSize(),
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
                                        modifier = Modifier.fillMaxSize(),
                                        state = albumListState,
                                        contentPadding =
                                            PaddingValues(
                                                top = 4.dp,
                                                bottom = contentPadding.calculateBottomPadding() + 16.dp,
                                            ),
                                    ) {
                                        item(key = "search_albums_header") {
                                            Row(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                val hintSuffix = if (hasMoreAlbums) " · 下滑加载更多" else ""
                                                Text(
                                                    text = "共找到 ${albumResults.size} 张专辑$hintSuffix",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
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

                            SearchTab.Artist -> {
                                if (artistResults.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "未找到相关歌手",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        state = artistListState,
                                        contentPadding =
                                            PaddingValues(
                                                top = 4.dp,
                                                bottom = contentPadding.calculateBottomPadding() + 16.dp,
                                            ),
                                    ) {
                                        item(key = "search_artists_header") {
                                            Row(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                val hintSuffix = if (hasMoreArtists) " · 下滑加载更多" else ""
                                                Text(
                                                    text = "共找到 ${artistResults.size} 位歌手$hintSuffix",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        itemsIndexed(
                                            artistResults,
                                            key = { _, artist -> artist.mid.ifBlank { artist.id.toString() } },
                                        ) { _, artist ->
                                            Row(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .clickable { navController.navigateToArtist(artist.mid, artist.name) }
                                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                AlbumArtImage(
                                                    coverUrl = artist.avatarUrl,
                                                    contentDescription = artist.name,
                                                    shape = CircleShape,
                                                    modifier = Modifier.size(52.dp),
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = artist.name,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    val subText =
                                                        buildString {
                                                            if (artist.songCount > 0) append("${artist.songCount} 首歌")
                                                            if (artist.albumCount > 0) {
                                                                if (isNotEmpty()) append(" · ")
                                                                append("${artist.albumCount} 张专辑")
                                                            }
                                                        }
                                                    Text(
                                                        text = subText.ifBlank { "歌手" },
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                        }
                                        if (isLoadingMoreArtists) {
                                            item(key = "artist_load_more_indicator") {
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
}
