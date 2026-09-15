package org.melodist.mobile.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.melodist.api.AddSongResult
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.addSongsToPlaylist
import org.melodist.api.deleteSongsFromPlaylist
import org.melodist.data.LocalMusicManager
import org.melodist.data.RecentPlaybackManager
import org.melodist.data.UserLibraryCacheManager
import org.melodist.data.WebDavManager
import org.melodist.data.download.DownloadManager
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
    var isOperating by remember { mutableStateOf(false) }

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

        AnimatedVisibility(
            visible = isFloatingVisible,
            enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.8f),
            exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.8f),
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .imePadding()
                    .padding(end = 16.dp, bottom = effectiveBottomPadding),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                // 1. 最上方：展开式胶囊过滤搜索条 / 过滤按钮
                // 若处于多选模式，只有此前已开启过滤或有过滤关键词时才保留
                if (!isMultiSelectMode || isFilterExpanded || filterQuery.isNotEmpty()) {
                    if (isFilterExpanded) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shadowElevation = 4.dp,
                            tonalElevation = 4.dp,
                            modifier =
                                Modifier
                                    .height(40.dp)
                                    .widthIn(min = 180.dp, max = 240.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                BasicTextField(
                                    value = filterQuery,
                                    onValueChange = {
                                        filterQuery = it
                                        keepAliveTrigger++
                                    },
                                    singleLine = true,
                                    textStyle =
                                        MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                        ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                    decorationBox = { innerTextField ->
                                        Box(
                                            modifier =
                                                Modifier
                                                    .weight(1f)
                                                    .padding(horizontal = 6.dp),
                                            contentAlignment = Alignment.CenterStart,
                                        ) {
                                            if (filterQuery.isEmpty()) {
                                                Text(
                                                    text = "过滤曲目...",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                )
                                            }
                                            innerTextField()
                                        }
                                    },
                                    modifier = Modifier.weight(1f).focusRequester(filterFocusRequester),
                                )
                                if (filterQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            filterQuery = ""
                                            keepAliveTrigger++
                                        },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "清空过滤",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(15.dp),
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        isFilterExpanded = false
                                        focusManager.clearFocus()
                                        keepAliveTrigger++
                                    },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "收起过滤",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    } else {
                        SmallFloatingActionButton(
                            onClick = {
                                isFilterExpanded = true
                                keepAliveTrigger++
                            },
                            containerColor =
                                if (filterQuery.isNotEmpty()) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                            contentColor =
                                if (filterQuery.isNotEmpty()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            elevation =
                                FloatingActionButtonDefaults.elevation(
                                    defaultElevation = 3.dp,
                                    pressedElevation = 6.dp,
                                ),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = "过滤歌曲",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                if (isMultiSelectMode) {
                    // 全选 / 取消全选按钮
                    SmallFloatingActionButton(
                        onClick = {
                            keepAliveTrigger++
                            if (isAllSelected) {
                                clearSelection()
                            } else {
                                selectAll()
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.primary,
                        elevation =
                            FloatingActionButtonDefaults.elevation(
                                defaultElevation = 3.dp,
                                pressedElevation = 6.dp,
                            ),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = if (isAllSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                            contentDescription = if (isAllSelected) "取消全选" else "全选",
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // 下载按钮（具有二次确认，在本地音乐、WebDAV、下载管理中不显示）
                    if (enableDownload) {
                        SmallFloatingActionButton(
                            onClick = {
                                keepAliveTrigger++
                                if (selectedMids.isEmpty()) {
                                    Toast.makeText(context, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                                } else {
                                    showBatchDownloadDialog = true
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.primary,
                            elevation =
                                FloatingActionButtonDefaults.elevation(
                                    defaultElevation = 3.dp,
                                    pressedElevation = 6.dp,
                                ),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "批量下载",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    // 删除按钮（具有二次确认，在每日推荐、歌手、专辑、搜索结果中不显示）
                    if (deleteType != SongListDeleteType.None) {
                        SmallFloatingActionButton(
                            onClick = {
                                keepAliveTrigger++
                                if (selectedMids.isEmpty()) {
                                    Toast.makeText(context, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                                } else {
                                    showBatchDeleteDialog = true
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            elevation =
                                FloatingActionButtonDefaults.elevation(
                                    defaultElevation = 3.dp,
                                    pressedElevation = 6.dp,
                                ),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "批量删除",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    // 添加按钮（二级菜单）
                    SmallFloatingActionButton(
                        onClick = {
                            keepAliveTrigger++
                            if (selectedMids.isEmpty()) {
                                Toast.makeText(context, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                            } else {
                                showAddToMenuDialog = true
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.primary,
                        elevation =
                            FloatingActionButtonDefaults.elevation(
                                defaultElevation = 3.dp,
                                pressedElevation = 6.dp,
                            ),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = "批量添加",
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // 退出多选按钮
                    SmallFloatingActionButton(
                        onClick = {
                            exitMultiSelect()
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        elevation =
                            FloatingActionButtonDefaults.elevation(
                                defaultElevation = 3.dp,
                                pressedElevation = 6.dp,
                            ),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "退出多选",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    // 非多选模式：快速返回顶部
                    SmallFloatingActionButton(
                        onClick = {
                            keepAliveTrigger++
                            scope.launch {
                                state.animateScrollToItem(0)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.primary,
                        elevation =
                            FloatingActionButtonDefaults.elevation(
                                defaultElevation = 3.dp,
                                pressedElevation = 6.dp,
                            ),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "返回顶部",
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // 非多选模式：定位到当前播放音乐
                    SmallFloatingActionButton(
                        onClick = {
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
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.primary,
                        elevation =
                            FloatingActionButtonDefaults.elevation(
                                defaultElevation = 3.dp,
                                pressedElevation = 6.dp,
                            ),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = "定位当前在播",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        // 单曲更多操作弹窗
        internalActionSong?.let { song ->
            SongActionSheet(
                song = song,
                onDismissRequest = { internalActionSong = null },
                onDeleteLocalFile = onDeleteLocalFile,
            )
        }

        // 批量下载二次确认弹窗
        if (showBatchDownloadDialog) {
            val selectedSongs = selectedMids.values.toList()
            val validDownloadSongs = selectedSongs.filter { it.songMid.isNotBlank() && !it.isLocal && !it.isWebDav }
            AlertDialog(
                onDismissRequest = { showBatchDownloadDialog = false },
                title = { Text("批量下载确认") },
                text = {
                    Text("确定将选中的 ${validDownloadSongs.size} 首在线歌曲加入下载队列吗？")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showBatchDownloadDialog = false
                            if (!DownloadManager.hasStoragePermission(context)) {
                                DownloadManager.requestStoragePermission(context)
                            }
                            var count = 0
                            for (song in validDownloadSongs) {
                                DownloadManager.downloadSong(song)
                                count++
                            }
                            Toast.makeText(context, "已将 $count 首歌曲加入下载队列", Toast.LENGTH_SHORT).show()
                            exitMultiSelect()
                        },
                    ) {
                        Text("确定下载")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBatchDownloadDialog = false }) {
                        Text("取消")
                    }
                },
            )
        }

        // 批量删除二次确认弹窗
        if (showBatchDeleteDialog) {
            val selectedSongs = selectedMids.values.toList()
            val count = selectedSongs.size
            val deleteMessage =
                when (deleteType) {
                    SongListDeleteType.PlaylistTrack -> "确定从歌单中移除选中的 $count 首歌曲吗？"
                    SongListDeleteType.LocalFile -> "确定删除选中的 $count 个本地歌曲文件吗？此操作不可撤销。"
                    SongListDeleteType.WebDavFile -> "确定从 WebDAV 曲库中移除选中的 $count 首歌曲吗？"
                    SongListDeleteType.RecentHistory -> "确定删除选中的 $count 条播放历史记录吗？"
                    SongListDeleteType.PlayerQueue -> "确定从当前播放队列中移除选中的 $count 首歌曲吗？"
                    SongListDeleteType.None -> ""
                }

            AlertDialog(
                onDismissRequest = { if (!isOperating) showBatchDeleteDialog = false },
                title = { Text("删除确认") },
                text = { Text(deleteMessage) },
                confirmButton = {
                    TextButton(
                        enabled = !isOperating,
                        onClick = {
                            isOperating = true
                            scope.launch {
                                try {
                                    when (deleteType) {
                                        SongListDeleteType.PlaylistTrack -> {
                                            if (onDeleteSelected != null) {
                                                onDeleteSelected(selectedSongs)
                                            } else if (playlistDirId > 0L) {
                                                val success = apiService.deleteSongsFromPlaylist(playlistDirId, selectedSongs)
                                                if (success) {
                                                    UserLibraryCacheManager.onSongsRemovedFromPlaylist(playlistDirId, selectedSongs)
                                                    if (playlistDirId == 201L) {
                                                        val mids = selectedSongs.map { it.songMid }.toSet()
                                                        val currentFavs = PlaybackManager.favoriteSongMids.value.filter { !mids.contains(it) }
                                                        PlaybackManager.setFavoriteSongMids(currentFavs.toSet())
                                                    }
                                                    Toast.makeText(context, "已从歌单移除 $count 首歌曲", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "移除失败", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                        SongListDeleteType.LocalFile -> {
                                            LocalMusicManager.deleteSongs(selectedSongs, context)
                                            onDeleteSelected?.invoke(selectedSongs)
                                            Toast.makeText(context, "已删除本地文件", Toast.LENGTH_SHORT).show()
                                        }
                                        SongListDeleteType.WebDavFile -> {
                                            WebDavManager.removeSongsFromCache(selectedSongs)
                                            onDeleteSelected?.invoke(selectedSongs)
                                            Toast.makeText(context, "已从曲库移除", Toast.LENGTH_SHORT).show()
                                        }
                                        SongListDeleteType.RecentHistory -> {
                                            RecentPlaybackManager.removeSongs(selectedSongs)
                                            onDeleteSelected?.invoke(selectedSongs)
                                            Toast.makeText(context, "已删除历史记录", Toast.LENGTH_SHORT).show()
                                        }
                                        SongListDeleteType.PlayerQueue -> {
                                            PlaybackManager.removeFromPlaylist(selectedSongs)
                                            onDeleteSelected?.invoke(selectedSongs)
                                            Toast.makeText(context, "已从播放队列移除", Toast.LENGTH_SHORT).show()
                                        }
                                        SongListDeleteType.None -> {}
                                    }
                                } finally {
                                    isOperating = false
                                    showBatchDeleteDialog = false
                                    exitMultiSelect()
                                }
                            }
                        },
                    ) {
                        Text("确认删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !isOperating,
                        onClick = { showBatchDeleteDialog = false },
                    ) {
                        Text("取消")
                    }
                },
            )
        }

        // 添加操作二级菜单
        if (showAddToMenuDialog) {
            val selectedSongs = selectedMids.values.toList()
            val onlineSongs = selectedSongs.filter { it.songMid.isNotBlank() && !it.isLocal && !it.isWebDav }
            ModalBottomSheet(
                onDismissRequest = { showAddToMenuDialog = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp),
                ) {
                    Text(
                        text = "批量添加 (${selectedSongs.size}首)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )

                    // 1. 添加到队列（通用）
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showAddToMenuDialog = false
                                    PlaybackManager.appendPlaylist(selectedSongs)
                                    Toast.makeText(context, "已将 ${selectedSongs.size} 首歌曲添加到播放队列", Toast.LENGTH_SHORT).show()
                                    exitMultiSelect()
                                }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "添加到播放队列",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }

                    // 2. 添加到歌单（仅在线歌曲可用）
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showAddToMenuDialog = false
                                    if (onlineSongs.isEmpty()) {
                                        Toast.makeText(context, "所选歌曲中没有可添加的在线音乐", Toast.LENGTH_SHORT).show()
                                    } else {
                                        showAddToPlaylistDialog = true
                                    }
                                }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = null,
                            tint =
                                if (onlineSongs.isNotEmpty()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                },
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "添加到歌单",
                                style = MaterialTheme.typography.bodyLarge,
                                color =
                                    if (onlineSongs.isNotEmpty()) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    },
                            )
                            if (onlineSongs.size < selectedSongs.size) {
                                Text(
                                    text = "包含 ${onlineSongs.size} 首在线歌曲",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 添加到自建歌单选择器
        if (showAddToPlaylistDialog) {
            val selectedSongs = selectedMids.values.toList()
            val onlineSongs = selectedSongs.filter { it.songMid.isNotBlank() && !it.isLocal && !it.isWebDav }
            AddToPlaylistBottomSheet(
                songs = onlineSongs,
                onDismissRequest = { showAddToPlaylistDialog = false },
                onSuccess = { exitMultiSelect() },
            )
        }
    }
}
