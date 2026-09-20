package org.melodist.tv.ui.playlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.melodist.api.UserSession
import org.melodist.model.Album
import org.melodist.model.Playlist
import org.melodist.model.Song
import org.melodist.tv.ui.components.TvPlaylistSongItem
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes

/**
 * TV 端歌单曲目列表面板组件，包含分类筛选 Chips、表头、未登录/加载中/空列表占位、
 * 具备分页跳跃功能的 LazyColumn 曲目行以及触底加载更多监听。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaylistTvTrackList(
    visible: Boolean,
    categoryId: String,
    userPlaylists: List<Playlist>,
    selectedPlaylistIndex: Int,
    onSelectPlaylistIndex: (Int) -> Unit,
    userAlbums: List<Album>,
    selectedAlbumIndex: Int,
    onSelectAlbumIndex: (Int) -> Unit,
    isLoading: Boolean,
    playlistSongs: List<Song>,
    listState: LazyListState,
    currentSong: Song?,
    dynamicReturnTargetIndex: Int,
    returnTargetIndex: Int,
    returnSongRequester: FocusRequester,
    firstSongRequester: FocusRequester,
    playAllRequester: FocusRequester,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onFocusSongChanged: (index: Int) -> Unit,
    onSongClick: (song: Song, index: Int) -> Unit,
    onSongLongClick: (song: Song) -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()

    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxSize(),
        enter = fadeIn(tween(durationMillis = 250)) + slideInHorizontally(tween(durationMillis = 250)) { -30 },
        exit = fadeOut(tween(durationMillis = 200)) + slideOutHorizontally(tween(durationMillis = 200)) { -30 },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // 1. 歌单 / 收藏专辑横向筛选 Chips
            PlaylistFilterChipsBar(
                categoryId = categoryId,
                userPlaylists = userPlaylists,
                selectedPlaylistIndex = selectedPlaylistIndex,
                onSelectPlaylistIndex = onSelectPlaylistIndex,
                userAlbums = userAlbums,
                selectedAlbumIndex = selectedAlbumIndex,
                onSelectAlbumIndex = onSelectAlbumIndex,
            )

            // 2. 表头 (与左侧封面顶部对齐)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "#", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
                Text(
                    text = "歌曲",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(0.45f),
                )
                Text(
                    text = "歌手",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(0.28f),
                )
                Text(
                    text = "专辑",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(0.27f),
                )
            }

            if (!UserSession.isLoggedIn) {
                // 3. 未登录引导卡片
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Filled.AccountCircle,
                        contentDescription = "未登录",
                        tint = MelodistColors.TextMuted,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "尚未登录 QQ 音乐账号",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MelodistColors.TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "登录后即可同步云端喜欢、收藏歌单、每日推荐与雷达专属曲库",
                        fontSize = 14.sp,
                        color = MelodistColors.TextMuted,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onNavigateToSettings,
                        shape = ButtonDefaults.shape(MelodistShapes.PillCorner),
                        colors =
                            ButtonDefaults.colors(
                                containerColor = MelodistColors.AccentGreen,
                                focusedContainerColor = Color.White,
                                contentColor = Color.Black,
                                focusedContentColor = Color.Black,
                            ),
                        border =
                            ButtonDefaults.border(
                                focusedBorder =
                                    Border(
                                        border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                                        shape = MelodistShapes.PillCorner,
                                    ),
                            ),
                        scale = ButtonDefaults.scale(focusedScale = 1.06f),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "前往设置扫码登录",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            } else if (isLoading) {
                // 4. 加载中占位
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "正在同步曲目列表...",
                        color = MelodistColors.TextSecondary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            } else if (playlistSongs.isEmpty()) {
                // 5. 空列表占位
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "曲目列表中暂无歌曲",
                        color = MelodistColors.TextMuted,
                        fontSize = 16.sp,
                    )
                }
            } else {
                // 6. 滚动加载更多监听
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

                LaunchedEffect(shouldLoadMore) {
                    if (shouldLoadMore && hasMore && !isLoading && !isLoadingMore && UserSession.isLoggedIn) {
                        onLoadMore()
                    }
                }

                var pageTargetFocusIndex by remember { mutableStateOf<Int?>(null) }
                val pageFocusRequester = remember { FocusRequester() }

                LaunchedEffect(pageTargetFocusIndex) {
                    val target = pageTargetFocusIndex
                    if (target != null) {
                        delay(60)
                        try {
                            pageFocusRequester.requestFocus()
                        } catch (_: Exception) {
                        }
                        pageTargetFocusIndex = null
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    itemsIndexed(
                        items = playlistSongs,
                        key = { index, song -> song.songMid.ifBlank { "${song.songId}_$index" } },
                    ) { index, song ->
                        val isFirst = index == 0
                        val isTarget = index == pageTargetFocusIndex
                        val effectiveReturnIndex = if (dynamicReturnTargetIndex >= 0) dynamicReturnTargetIndex else returnTargetIndex
                        val isReturnTarget = effectiveReturnIndex >= 0 && index == effectiveReturnIndex
                        val isCurrentPlaying =
                            (currentSong?.songMid == song.songMid && song.songMid.isNotBlank()) ||
                                (song.songId != 0L && currentSong?.songId == song.songId)
                        val itemModifier =
                            Modifier
                                .fillMaxWidth()
                                .height(68.dp)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        onFocusSongChanged(index)
                                        PlaylistScreenCache.lastFocusedIndex = index
                                    }
                                }.then(
                                    when {
                                        isReturnTarget -> Modifier.focusRequester(returnSongRequester)
                                        isTarget -> Modifier.focusRequester(pageFocusRequester)
                                        isFirst -> Modifier.focusRequester(firstSongRequester)
                                        else -> Modifier
                                    },
                                ).focusProperties {
                                    left = playAllRequester
                                }.onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        when (event.key) {
                                            Key.DirectionRight -> {
                                                val targetIndex = (index + 8).coerceAtMost(playlistSongs.size - 1)
                                                if (targetIndex > index) {
                                                    pageTargetFocusIndex = targetIndex
                                                    coroutineScope.launch {
                                                        listState.animateScrollToItem(targetIndex)
                                                    }
                                                    true
                                                } else {
                                                    false
                                                }
                                            }
                                            Key.DirectionLeft -> {
                                                if (index >= 8) {
                                                    val targetIndex = (index - 8).coerceAtLeast(0)
                                                    pageTargetFocusIndex = targetIndex
                                                    coroutineScope.launch {
                                                        listState.animateScrollToItem(targetIndex)
                                                    }
                                                    true
                                                } else {
                                                    false
                                                }
                                            }
                                            else -> false
                                        }
                                    } else {
                                        false
                                    }
                                }

                        TvPlaylistSongItem(
                            index = index + 1,
                            song = song,
                            isPlaying = isCurrentPlaying,
                            modifier = itemModifier,
                            onClick = { onSongClick(song, index) },
                            onLongClick = { onSongLongClick(song) },
                        )
                    }

                    if (isLoadingMore) {
                        item {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "正在载入更多曲目...",
                                    color = MelodistColors.TextMuted,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
