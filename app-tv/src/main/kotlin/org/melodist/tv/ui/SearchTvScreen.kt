package org.melodist.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.data.SearchKeywordHistoryManager
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.components.MelodistAsyncImage
import org.melodist.tv.ui.components.TvOnScreenKeyboard
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.rememberTvWindowMetrics

internal object SearchStateHolder {
    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<Song>>(emptyList())
    var hasSearched by mutableStateOf(false)
    var currentPage by mutableIntStateOf(1)
    var hasMore by mutableStateOf(true)

    fun reset() {
        searchQuery = ""
        searchResults = emptyList()
        hasSearched = false
        currentPage = 1
        hasMore = true
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchTvScreen(
    surfaceColor: Color,
    onNavigateToPlayer: () -> Unit = {},
    onNavigateToArtist: (String, String) -> Unit = { _, _ -> },
    onNavigateToAlbum: (String, String) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    val metrics = rememberTvWindowMetrics()
    val scope = rememberCoroutineScope()
    val apiService = remember { MusicApiService() }

    var actionSong by remember { mutableStateOf<Song?>(null) }
    var searchQuery by SearchStateHolder::searchQuery
    var searchResults by SearchStateHolder::searchResults
    var hasSearched by SearchStateHolder::hasSearched
    var currentPage by SearchStateHolder::currentPage
    var hasMore by SearchStateHolder::hasMore

    var isSearching by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var historyKeywords by remember { mutableStateOf(SearchKeywordHistoryManager.getKeywords()) }

    var searchJob by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()

    fun performSearch(queryText: String) {
        val trimmed = queryText.trim()
        if (trimmed.length < 2) {
            searchResults = emptyList()
            hasSearched = false
            currentPage = 1
            hasMore = true
            return
        }

        searchJob?.cancel()
        searchJob =
            scope.launch {
                isSearching = true
                hasSearched = true
                currentPage = 1
                hasMore = true
                try {
                    val songs = apiService.search(trimmed, page = 1, pageSize = 50)
                    searchResults = songs
                    hasMore = songs.size >= 50
                } catch (_: Exception) {
                    searchResults = emptyList()
                    hasMore = false
                } finally {
                    isSearching = false
                }
            }
    }

    fun loadMoreResults() {
        val trimmed = searchQuery.trim()
        if (isLoadingMore || isSearching || !hasMore || trimmed.length < 2) return

        isLoadingMore = true
        scope.launch {
            try {
                val nextPage = currentPage + 1
                val newSongs = apiService.search(trimmed, page = nextPage, pageSize = 50)
                if (newSongs.isNotEmpty()) {
                    searchResults = searchResults + newSongs
                    currentPage = nextPage
                    hasMore = newSongs.size >= 50
                } else {
                    hasMore = false
                }
            } catch (_: Exception) {
                hasMore = false
            } finally {
                isLoadingMore = false
            }
        }
    }

    // 焦点分配
    val firstKeyRequester = remember { FocusRequester() }
    val fKeyRequester = remember { FocusRequester() }
    val resultListRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (!hasSearched) {
            firstKeyRequester.requestFocus()
        }
    }

    BackHandler {
        onBack()
    }

    val currentPlayingSong by PlaybackManager.currentSong.collectAsState()

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(surfaceColor)
                .padding(
                    horizontal = metrics.horizontalSafePadding,
                    vertical = metrics.verticalSafePadding,
                ).onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        val unicode = event.nativeKeyEvent.unicodeChar
                        if (unicode in 32..126) {
                            searchQuery += unicode.toChar()
                            searchJob?.cancel()
                            if (searchQuery.trim().length >= 2) {
                                searchJob =
                                    scope.launch {
                                        delay(500)
                                        performSearch(searchQuery)
                                    }
                            } else {
                                hasSearched = false
                                searchResults = emptyList()
                            }
                            true
                        } else if (event.key == Key.Backspace) {
                            if (searchQuery.isNotEmpty()) {
                                searchQuery = searchQuery.dropLast(1)
                                searchJob?.cancel()
                                if (searchQuery.trim().length >= 2) {
                                    searchJob =
                                        scope.launch {
                                            delay(500)
                                            performSearch(searchQuery)
                                        }
                                } else {
                                    hasSearched = false
                                    searchResults = emptyList()
                                }
                            }
                            true
                        } else if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                            if (searchQuery.trim().length >= 2) {
                                performSearch(searchQuery)
                            }
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                },
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            // 左侧分栏：导航头 + 搜索输入栏 + 虚拟键盘 (约 410dp 宽度)
            Column(
                modifier =
                    Modifier
                        .width(410.dp)
                        .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 顶部返回按钮与标题
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SearchBackButton(onClick = onBack)
                    Text(
                        text = "曲库搜索",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MelodistColors.TextPrimary,
                    )
                }

                // 输入框预览
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(Color.White.copy(alpha = 0.10f), MelodistShapes.PillCorner)
                            .border(1.dp, Color.White.copy(alpha = 0.18f), MelodistShapes.PillCorner)
                            .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MelodistColors.AccentGreen,
                            modifier = Modifier.size(20.dp),
                        )

                        Text(
                            text = if (searchQuery.isEmpty()) "输入歌曲/歌手/拼音首字母..." else searchQuery,
                            fontSize = 15.sp,
                            color = if (searchQuery.isEmpty()) Color.White.copy(alpha = 0.75f) else MelodistColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )

                        if (searchQuery.isNotEmpty()) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(24.dp)
                                        .background(Color.White.copy(alpha = 0.1f), CircleShape)
                                        .clickable {
                                            searchQuery = ""
                                            searchResults = emptyList()
                                            hasSearched = false
                                        },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "清空",
                                    tint = MelodistColors.TextSecondary,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }

                // 专为遥控器设计的字母/数字网格虚拟键盘
                TvOnScreenKeyboard(
                    firstKeyRequester = firstKeyRequester,
                    fKeyRequester = fKeyRequester,
                    rightFocusRequester = resultListRequester,
                    onCharacterInput = { ch ->
                        searchQuery += ch
                        searchJob?.cancel()
                        if (searchQuery.trim().length >= 2) {
                            searchJob =
                                scope.launch {
                                    delay(500)
                                    performSearch(searchQuery)
                                }
                        } else {
                            hasSearched = false
                            searchResults = emptyList()
                        }
                    },
                    onBackspace = {
                        if (searchQuery.isNotEmpty()) {
                            searchQuery = searchQuery.dropLast(1)
                            searchJob?.cancel()
                            if (searchQuery.trim().length >= 2) {
                                searchJob =
                                    scope.launch {
                                        delay(500)
                                        performSearch(searchQuery)
                                    }
                            } else {
                                hasSearched = false
                                searchResults = emptyList()
                            }
                        }
                    },
                    onClear = {
                        searchQuery = ""
                        searchResults = emptyList()
                        hasSearched = false
                    },
                    onSearch = {
                        if (searchQuery.trim().length >= 2) {
                            performSearch(searchQuery)
                        }
                    },
                )
            }

            // 右侧分栏：搜索结果或搜索关键词记录
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val isShowingKeywords = !hasSearched || searchQuery.trim().length < 2

                // 表头
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text =
                            if (isShowingKeywords) {
                                if (historyKeywords.isNotEmpty()) "搜索关键词记录 (${historyKeywords.size} 条)" else "搜索关键词记录"
                            } else {
                                "搜索结果 (${searchResults.size} 首)"
                            },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MelodistColors.TextPrimary,
                    )

                    if (isSearching) {
                        Text(
                            text = "正在检索曲库...",
                            fontSize = 13.sp,
                            color = MelodistColors.AccentGreen,
                        )
                    } else if (isShowingKeywords && historyKeywords.isNotEmpty()) {
                        ClearHistoryButton(
                            onClick = {
                                SearchKeywordHistoryManager.clearKeywords()
                                historyKeywords = emptyList()
                            },
                            onLeft = {
                                fKeyRequester.requestFocus()
                            },
                        )
                    }
                }

                // 右侧主体：关键词记录 / 搜索结果
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                ) {
                    if (isSearching && searchResults.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "正在搜索中...",
                                fontSize = 16.sp,
                                color = MelodistColors.TextSecondary,
                            )
                        }
                    } else if (!isShowingKeywords && searchResults.isEmpty() && !isSearching) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SearchOff,
                                    contentDescription = null,
                                    tint = MelodistColors.TextMuted,
                                    modifier = Modifier.size(48.dp),
                                )
                                Text(
                                    text = "未找到与 \"$searchQuery\" 相关的歌曲",
                                    fontSize = 15.sp,
                                    color = MelodistColors.TextSecondary,
                                )
                            }
                        }
                    } else if (isShowingKeywords) {
                        if (historyKeywords.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = null,
                                        tint = MelodistColors.AccentGreen.copy(alpha = 0.6f),
                                        modifier = Modifier.size(54.dp),
                                    )
                                    Text(
                                        text = "暂无搜索关键词记录，搜索后播放歌曲将记录关键词",
                                        fontSize = 15.sp,
                                        color = MelodistColors.TextSecondary,
                                    )
                                }
                            }
                        } else {
                            // 历史关键词网格展示
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .focusRequester(resultListRequester),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(historyKeywords) { keyword ->
                                    KeywordChip(
                                        keyword = keyword,
                                        onSelect = {
                                            searchQuery = keyword
                                            performSearch(keyword)
                                        },
                                        onLeft = {
                                            fKeyRequester.requestFocus()
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        // 搜索结果歌曲列表
                        LazyColumn(
                            state = listState,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .focusRequester(resultListRequester)
                                    .onPreviewKeyEvent { keyEvent ->
                                        if (keyEvent.type == KeyEventType.KeyDown) {
                                            when (keyEvent.key) {
                                                // 按 ← 回到键盘第一行最右侧 F 按键
                                                Key.DirectionLeft -> {
                                                    fKeyRequester.requestFocus()
                                                    true
                                                }
                                                // 按 → 快速向下翻页
                                                Key.DirectionRight -> {
                                                    val step = 7
                                                    val target =
                                                        (listState.firstVisibleItemIndex + step).coerceAtMost(
                                                            searchResults.size - 1,
                                                        )
                                                    scope.launch {
                                                        listState.animateScrollToItem(target)
                                                    }
                                                    true
                                                }
                                                else -> false
                                            }
                                        } else {
                                            false
                                        }
                                    },
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            itemsIndexed(searchResults) { index, song ->
                                val isPlaying = currentPlayingSong?.songMid == song.songMid
                                SearchResultSongItem(
                                    index = index + 1,
                                    song = song,
                                    isPlaying = isPlaying,
                                    onPlay = {
                                        // 仅当用户搜索后点击播放单曲时，记录当前的搜索关键词
                                        if (searchQuery.trim().length >= 2) {
                                            SearchKeywordHistoryManager.recordKeyword(searchQuery.trim())
                                            historyKeywords = SearchKeywordHistoryManager.getKeywords()
                                        }

                                        PlaybackManager.setPlaylist(searchResults, startIndex = index)
                                        onNavigateToPlayer()
                                    },
                                    onLongClick = {
                                        if (song.canShowArtistAlbumDialog) {
                                            actionSong = song
                                        }
                                    },
                                )

                                // 触底加载更多
                                if (index >= searchResults.size - 3 && hasMore && !isLoadingMore && !isSearching) {
                                    LaunchedEffect(index) {
                                        loadMoreResults()
                                    }
                                }
                            }

                            // 底部加载状态
                            item {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isLoadingMore) {
                                        Text(
                                            text = "正在载入更多搜索结果...",
                                            color = MelodistColors.AccentGreen,
                                            fontSize = 14.sp,
                                        )
                                    } else if (!hasMore) {
                                        Text(
                                            text = "已展示全部 ${searchResults.size} 条搜索结果",
                                            color = MelodistColors.TextMuted,
                                            fontSize = 13.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 歌曲关联资产弹窗（长按呼出）
        actionSong?.let { song ->
            org.melodist.tv.ui.components.SongArtistAlbumDialog(
                song = song,
                onDismissRequest = { actionSong = null },
                onSelectArtist = { mid, name ->
                    onNavigateToArtist(mid, name)
                },
                onSelectAlbum = { mid, name ->
                    onNavigateToAlbum(mid, name)
                },
            )
        }
    }
}

@Composable
private fun KeywordChip(
    keyword: String,
    onSelect: () -> Unit,
    onLeft: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(44.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onSelect,
                ).focusable(interactionSource = interactionSource)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                        onLeft()
                        true
                    } else {
                        false
                    }
                }.border(
                    border =
                        BorderStroke(
                            width = if (isFocused) 2.dp else 1.dp,
                            color = if (isFocused) MelodistColors.FocusTeal else Color.White.copy(alpha = 0.18f),
                        ),
                    shape = MelodistShapes.PillCorner,
                ).background(
                    color = if (isFocused) Color.White else Color.White.copy(alpha = 0.12f),
                    shape = MelodistShapes.PillCorner,
                ).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = if (isFocused) Color.Black else MelodistColors.AccentGreen,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = keyword,
            fontSize = 14.sp,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
            color = if (isFocused) Color.Black else MelodistColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ClearHistoryButton(
    onClick: () -> Unit,
    onLeft: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier =
            Modifier
                .height(36.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).focusable(interactionSource = interactionSource)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                        onLeft()
                        true
                    } else {
                        false
                    }
                }.border(
                    border =
                        BorderStroke(
                            width = if (isFocused) 2.dp else 1.dp,
                            color = if (isFocused) MelodistColors.FocusTeal else Color.White.copy(alpha = 0.18f),
                        ),
                    shape = MelodistShapes.PillCorner,
                ).background(
                    color = if (isFocused) Color.White else Color.White.copy(alpha = 0.12f),
                    shape = MelodistShapes.PillCorner,
                ).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Default.DeleteOutline,
            contentDescription = "清空历史",
            tint = if (isFocused) Color.Black else MelodistColors.TextPrimary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "清空记录",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (isFocused) Color.Black else MelodistColors.TextPrimary,
        )
    }
}

@Composable
private fun SearchBackButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier =
            Modifier
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).focusable(interactionSource = interactionSource)
                .border(
                    border =
                        BorderStroke(
                            width = if (isFocused) 2.dp else 1.dp,
                            color = if (isFocused) MelodistColors.FocusTeal else Color.White.copy(alpha = 0.18f),
                        ),
                    shape = MelodistShapes.PillCorner,
                ).background(
                    color = if (isFocused) Color.White else Color.White.copy(alpha = 0.12f),
                    shape = MelodistShapes.PillCorner,
                ).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = if (isFocused) Color.Black else MelodistColors.TextPrimary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "返回",
            fontSize = 14.sp,
            color = if (isFocused) Color.Black else MelodistColors.TextPrimary,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchResultSongItem(
    index: Int,
    song: Song,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Card(
        onClick = onPlay,
        onLongClick = onLongClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(52.dp),
        shape =
            CardDefaults.shape(
                shape = RoundedCornerShape(8.dp),
                focusedShape = RoundedCornerShape(8.dp),
            ),
        colors =
            CardDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.White,
                contentColor = if (isPlaying) MelodistColors.AccentGreen else Color.White,
                focusedContentColor = Color.Black,
            ),
        border =
            CardDefaults.border(
                border =
                    Border(
                        border = BorderStroke(0.dp, Color.Transparent),
                        shape = RoundedCornerShape(8.dp),
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                        shape = RoundedCornerShape(8.dp),
                    ),
            ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 序号
            Text(
                text = String.format("%02d", index),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (isPlaying) MelodistColors.AccentGreen else LocalContentColor.current,
                modifier = Modifier.width(36.dp),
            )

            // 封面小图
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
            ) {
                MelodistAsyncImage(
                    coverUrl = song.coverUrl,
                    albumMid = song.albumMid,
                    contentDescription = null,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // 歌名
            Text(
                text = song.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isPlaying) MelodistColors.AccentGreen else LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.45f),
            )

            // 歌手
            Text(
                text = song.singer,
                fontSize = 14.sp,
                color = LocalContentColor.current.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.32f),
            )

            // 专辑
            Text(
                text = song.album,
                fontSize = 13.sp,
                color = LocalContentColor.current.copy(alpha = 0.70f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.23f),
            )
        }
    }
}
