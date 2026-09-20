package org.melodist.mobile.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.mobile.ui.components.songlist.SongListBatchDialogs
import org.melodist.mobile.ui.components.songlist.SongListFloatingActions
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import org.melodist.playback.QueuePaginationSource

enum class SongListDeleteType {
    None,
    PlaylistTrack,
    LocalFile,
    WebDavFile,
    RecentHistory,
    PlayerQueue,
}

/**
 * 通用歌曲列表组件。
 * 统一管理歌单、每日推荐、搜索结果、歌手与专辑详情中的歌曲行渲染、
 * 播放状态高亮、即时过滤、多选批量操作以及单曲更多操作弹窗 (SongActionSheet)。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CommonSongList(
    songs: List<Song>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    state: LazyListState = rememberLazyListState(),
    headerItems: (LazyListScope.() -> Unit)? = null,
    footerItems: (LazyListScope.() -> Unit)? = null,
    emptyContent: (@Composable () -> Unit)? = null,
    showLocalBadge: Boolean = true,
    showWebDavBadge: Boolean = true,
    paginationSource: QueuePaginationSource? = null,
    deleteType: SongListDeleteType = SongListDeleteType.None,
    enableDownload: Boolean = true,
    playlistDirId: Long = 0L,
    onDeleteSelected: (suspend (List<Song>) -> Unit)? = null,
    onSongClick: ((songs: List<Song>, index: Int) -> Unit)? = null,
    onMoreClick: ((Song) -> Unit)? = null,
    onDeleteLocalFile: ((Song) -> Unit)? = null,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val apiService = remember { MusicApiService() }

    val rawCurrentSong by PlaybackManager.currentSong.collectAsState()
    val rawIsPlaying by PlaybackManager.isPlaying.collectAsState()
    val currentPlayingMid by remember {
        derivedStateOf {
            if (rawIsPlaying) rawCurrentSong?.songMid.orEmpty() else ""
        }
    }

    var internalActionSong by remember { mutableStateOf<Song?>(null) }
    var isFloatingButtonsVisible by remember { mutableStateOf(false) }
    var keepAliveTrigger by remember { mutableIntStateOf(0) }

    var isFilterExpanded by remember { mutableStateOf(false) }
    var filterQuery by remember { mutableStateOf("") }
    val filterFocusRequester = remember { FocusRequester() }

    // 多选状态
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedMids = remember { mutableStateMapOf<String, Song>() }

    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showBatchDownloadDialog by remember { mutableStateOf(false) }
    var showAddToMenuDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isFilterExpanded) {
        if (isFilterExpanded) {
            delay(120)
            try {
                filterFocusRequester.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    val displaySongs by remember(songs, filterQuery) {
        derivedStateOf {
            val q = filterQuery.trim().lowercase()
            if (q.isEmpty()) {
                songs
            } else {
                songs.filter { song ->
                    song.name.lowercase().contains(q) ||
                        song.singer.lowercase().contains(q) ||
                        song.album.lowercase().contains(q)
                }
            }
        }
    }

    val isAllSelected =
        remember(displaySongs, selectedMids.size) {
            displaySongs.isNotEmpty() && displaySongs.all {
                selectedMids.containsKey(it.songMid.ifBlank { it.songId.toString() })
            }
        }

    fun toggleSongSelection(song: Song) {
        val key = song.songMid.ifBlank { song.songId.toString() }
        if (selectedMids.containsKey(key)) {
            selectedMids.remove(key)
        } else {
            selectedMids[key] = song
        }
    }

    fun selectAll() {
        displaySongs.forEach { song ->
            val key = song.songMid.ifBlank { song.songId.toString() }
            selectedMids[key] = song
        }
    }

    fun clearSelection() {
        selectedMids.clear()
    }

    fun exitMultiSelect() {
        selectedMids.clear()
        isMultiSelectMode = false
    }

    LaunchedEffect(state.isScrollInProgress, keepAliveTrigger) {
        if (state.isScrollInProgress) {
            isFloatingButtonsVisible = true
        } else if (isFloatingButtonsVisible) {
            delay(3000L)
            isFloatingButtonsVisible = false
        }
    }

    val handleSongClick =
        remember(displaySongs, onSongClick, paginationSource) {
            { index: Int ->
                if (onSongClick != null) {
                    onSongClick(displaySongs, index)
                } else {
                    PlaybackManager.setPlaylist(displaySongs, startIndex = index, paginationSource = paginationSource)
                }
            }
        }

    val handleMoreClick =
        remember(onMoreClick) {
            { song: Song ->
                if (onMoreClick != null) {
                    onMoreClick(song)
                } else {
                    internalActionSong = song
                }
            }
        }

    Box(modifier = modifier.fillMaxSize()) {
        if (songs.isEmpty() && headerItems == null && emptyContent != null) {
            emptyContent()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = state,
                contentPadding = contentPadding,
            ) {
                headerItems?.invoke(this)

                if (songs.isEmpty() && emptyContent != null) {
                    item(key = "empty_placeholder") {
                        emptyContent()
                    }
                } else if (displaySongs.isEmpty() && filterQuery.isNotBlank()) {
                    item(key = "filter_empty_placeholder") {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "未找到与 \"$filterQuery\" 相关的歌曲",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                }

                itemsIndexed(
                    items = displaySongs,
                    key = { _, song -> song.songMid.ifBlank { song.songId.toString() } },
                    contentType = { _, _ -> "song_row" },
                ) { index, song ->
                    val songKey = song.songMid.ifBlank { song.songId.toString() }
                    val isSelected = selectedMids.containsKey(songKey)
                    SongItemRow(
                        song = song,
                        isPlayingThis = song.songMid.isNotBlank() && song.songMid == currentPlayingMid,
                        highlightQuery = filterQuery,
                        showLocalBadge = showLocalBadge,
                        showWebDavBadge = showWebDavBadge,
                        isMultiSelectMode = isMultiSelectMode,
                        isSelected = isSelected,
                        onClick = { handleSongClick(index) },
                        onSelectToggle = { toggleSongSelection(song) },
                        onLongClick = {
                            if (!isMultiSelectMode) {
                                isMultiSelectMode = true
                                selectedMids[songKey] = song
                            }
                        },
                        onMoreClick = { handleMoreClick(song) },
                    )
                }

                footerItems?.invoke(this)
            }
        }

        val bottomPadding = contentPadding.calculateBottomPadding()
        val isImeVisible = WindowInsets.isImeVisible
        val effectiveBottomPadding = if (isImeVisible) 16.dp else bottomPadding.coerceAtLeast(16.dp)
        val isFloatingVisible =
            (songs.isNotEmpty() && isFloatingButtonsVisible) || isFilterExpanded || filterQuery.isNotEmpty() || isMultiSelectMode

        SongListFloatingActions(
            visible = isFloatingVisible,
            bottomPadding = effectiveBottomPadding,
            isFilterExpanded = isFilterExpanded,
            filterQuery = filterQuery,
            filterFocusRequester = filterFocusRequester,
            onFilterQueryChange = {
                filterQuery = it
                keepAliveTrigger++
            },
            onExpandFilter = {
                isFilterExpanded = true
                keepAliveTrigger++
            },
            onCollapseFilter = {
                isFilterExpanded = false
                filterQuery = ""
                focusManager.clearFocus()
                keepAliveTrigger++
            },
            onClearFilter = {
                filterQuery = ""
                keepAliveTrigger++
            },
            isMultiSelectMode = isMultiSelectMode,
            isAllSelected = isAllSelected,
            enableDownload = enableDownload,
            deleteType = deleteType,
            onToggleSelectAll = {
                keepAliveTrigger++
                if (isAllSelected) {
                    clearSelection()
                } else {
                    selectAll()
                }
            },
            onBatchDownloadClick = {
                keepAliveTrigger++
                showBatchDownloadDialog = true
            },
            onBatchDeleteClick = {
                keepAliveTrigger++
                showBatchDeleteDialog = true
            },
            onBatchAddClick = {
                keepAliveTrigger++
                showAddToMenuDialog = true
            },
            onExitMultiSelect = {
                keepAliveTrigger++
                exitMultiSelect()
            },
            onScrollToTop = {
                keepAliveTrigger++
                scope.launch {
                    state.animateScrollToItem(0)
                }
            },
            onLocateCurrentPlaying = {
                keepAliveTrigger++
                val currentSongMid = PlaybackManager.currentSong.value?.songMid
                val activeIndex =
                    if (currentSongMid.isNullOrBlank()) {
                        -1
                    } else {
                        displaySongs.indexOfFirst { it.songMid == currentSongMid }
                    }

                if (activeIndex != -1) {
                    val headerCount = (state.layoutInfo.totalItemsCount - displaySongs.size).coerceAtLeast(0)
                    scope.launch {
                        state.animateScrollToItem(headerCount + activeIndex)
                    }
                } else {
                    val msg =
                        if (filterQuery.isNotEmpty()) {
                            "当前在播歌曲不在过滤结果中"
                        } else {
                            "当前在播歌曲不在列表中"
                        }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd),
        )

        // 单曲更多操作弹窗
        internalActionSong?.let { song ->
            SongActionSheet(
                song = song,
                onDismissRequest = { internalActionSong = null },
                onDeleteLocalFile = onDeleteLocalFile,
            )
        }

        // 批量操作弹窗群组
        SongListBatchDialogs(
            showBatchDownloadDialog = showBatchDownloadDialog,
            onDismissBatchDownload = { showBatchDownloadDialog = false },
            showBatchDeleteDialog = showBatchDeleteDialog,
            onDismissBatchDelete = { showBatchDeleteDialog = false },
            showAddToMenuDialog = showAddToMenuDialog,
            onDismissAddToMenu = { showAddToMenuDialog = false },
            showAddToPlaylistDialog = showAddToPlaylistDialog,
            onDismissAddToPlaylist = { showAddToPlaylistDialog = false },
            onOpenAddToPlaylist = { showAddToPlaylistDialog = true },
            selectedSongs = selectedMids.values.toList(),
            deleteType = deleteType,
            playlistDirId = playlistDirId,
            apiService = apiService,
            onDeleteSelected = onDeleteSelected,
            onExitMultiSelect = { exitMultiSelect() },
        )
    }
}
