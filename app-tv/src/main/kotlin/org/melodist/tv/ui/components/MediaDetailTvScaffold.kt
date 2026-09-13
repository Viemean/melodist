package org.melodist.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.launch
import org.melodist.model.Song
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.rememberTvWindowMetrics

/**
 * 通用媒体详情骨架屏
 * 包含：
 * 1. 左侧封面与说明列；
 * 2. 右侧曲目列表；
 * 3. 左右焦点导航。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MediaDetailTvScaffold(
    surfaceColor: Color,
    headerImageUrl: String,
    headerImageShape: Shape = RoundedCornerShape(6.dp),
    isHeaderImageCircle: Boolean = false,
    albumMid: String = "",
    artistMid: String = "",
    title: String,
    subtitle: String = "",
    metaInfo: String = "",
    description: String = "",
    songs: List<Song>,
    currentPlayingMid: String? = null,
    isLoading: Boolean = false,
    emptyMessage: String = "暂无相关曲目",
    isReturningFromPlayer: Boolean = false,
    onPlayAll: () -> Unit = {},
    onSongClick: (Song) -> Unit = {},
    onSongLongClick: (Song) -> Unit = {},
    onLoadMore: (() -> Unit)? = null,
    isLoadingMore: Boolean = false,
    bottomContent: (@Composable () -> Unit)? = null,
    rightContent: (@Composable () -> Unit)? = null,
) {
    val metrics = rememberTvWindowMetrics()
    val listState = rememberLazyListState()

    val shouldLoadMore =
        remember {
            derivedStateOf {
                val totalItems = listState.layoutInfo.totalItemsCount
                val lastVisibleItem =
                    listState.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.index ?: 0
                totalItems > 0 && lastVisibleItem >= totalItems - 5
            }
        }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value && !isLoadingMore && !isLoading) {
            onLoadMore?.invoke()
        }
    }

    val displayImageUrl =
        headerImageUrl.ifBlank {
            songs.firstOrNull()?.coverUrl.orEmpty()
        }
    val displayAlbumMid = albumMid
    val displayArtistMid = artistMid

    // 左右双向互通焦点指示器
    val playAllFocusRequester = remember { FocusRequester() }
    val firstSongFocusRequester = remember { FocusRequester() }
    var pageTargetFocusIndex by remember { mutableStateOf<Int?>(null) }
    val pageFocusRequester = remember { FocusRequester() }
    var hasRequestedInitialFocus by remember { mutableStateOf(false) }

    LaunchedEffect(isReturningFromPlayer) {
        if (isReturningFromPlayer && songs.isNotEmpty() && !currentPlayingMid.isNullOrBlank()) {
            val idx = songs.indexOfFirst { it.songMid == currentPlayingMid }
            if (idx >= 0) {
                listState.scrollToItem((idx - 2).coerceAtLeast(0))
                pageTargetFocusIndex = idx
                return@LaunchedEffect
            }
        }
        if (!hasRequestedInitialFocus) {
            hasRequestedInitialFocus = true
            playAllFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(pageTargetFocusIndex) {
        val target = pageTargetFocusIndex
        if (target != null) {
            kotlinx.coroutines.delay(60)
            try {
                pageFocusRequester.requestFocus()
            } catch (_: Exception) {
            }
            pageTargetFocusIndex = null
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(surfaceColor)
                .padding(
                    start = metrics.horizontalSafePadding,
                    end = metrics.horizontalSafePadding,
                    top = metrics.verticalSafePadding,
                    bottom = metrics.verticalSafePadding,
                ),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            // ── 左侧面板：封面与信息主控 (封面向下对齐 36dp 与右侧单曲列表顶端对齐) ──
            Column(
                modifier =
                    Modifier
                        .width(240.dp)
                        .fillMaxHeight()
                        .padding(top = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 封面展示
                if (displayImageUrl.isNotBlank() || displayAlbumMid.isNotBlank() || displayArtistMid.isNotBlank()) {
                    MelodistElevatedCover(
                        coverUrl = displayImageUrl,
                        albumMid = displayAlbumMid,
                        artistMid = displayArtistMid,
                        contentDescription = title,
                        shape = headerImageShape,
                        isCircle = isHeaderImageCircle,
                        modifier = Modifier.size(240.dp),
                    )
                } else {
                    Box(
                        modifier =
                            Modifier
                                .size(240.dp)
                                .clip(if (isHeaderImageCircle) CircleShape else headerImageShape)
                                .background(MelodistColors.ContainerDarkSecondary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = title.take(2),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }

                // 标题与副信息 (左对齐)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MelodistColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    val subText =
                        when {
                            subtitle.isNotBlank() && metaInfo.isNotBlank() -> "$subtitle · $metaInfo"
                            subtitle.isNotBlank() -> subtitle
                            metaInfo.isNotBlank() -> metaInfo
                            description.isNotBlank() -> description
                            else -> ""
                        }
                    if (subText.isNotBlank()) {
                        Text(
                            text = subText,
                            fontSize = 13.sp,
                            color = MelodistColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // 播放全部按钮
                Button(
                    onClick = onPlayAll,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .focusRequester(playAllFocusRequester)
                            .focusProperties {
                                right = firstSongFocusRequester
                            },
                    shape =
                        ButtonDefaults.shape(
                            shape = MelodistShapes.CardCorner,
                            focusedShape = MelodistShapes.CardCorner,
                        ),
                    colors =
                        ButtonDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.08f),
                            focusedContainerColor = MelodistColors.AccentGreen,
                            contentColor = Color.White,
                            focusedContentColor = Color.Black,
                        ),
                    border =
                        ButtonDefaults.border(
                            border =
                                Border(
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                    shape = MelodistShapes.CardCorner,
                                ),
                            focusedBorder =
                                Border(
                                    border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                                    shape = MelodistShapes.CardCorner,
                                ),
                        ),
                    scale = ButtonDefaults.scale(focusedScale = 1.0f),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "播放全部",
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "播放全部",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                if (bottomContent != null) {
                    bottomContent()
                }
            }

            // ── 右侧内容区：自定义内容（如选专辑）或曲目列表 ──
            if (rightContent != null) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .padding(top = 28.dp),
                ) {
                    rightContent()
                }
            } else {
                Column(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .padding(top = 28.dp),
                ) {
                    // 表头 (与左侧封面顶部对齐)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "#",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(44.dp),
                        )
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

                    Box(modifier = Modifier.fillMaxSize()) {
                        if (isLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = "正在载入曲目...", color = Color.White, fontSize = 16.sp)
                            }
                        } else if (songs.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = emptyMessage, color = Color.White, fontSize = 16.sp)
                            }
                        } else {
                            val coroutineScope = rememberCoroutineScope()

                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 32.dp),
                            ) {
                                itemsIndexed(
                                    items = songs,
                                    key = { _, song -> song.songMid.ifBlank { song.songId.toString() } },
                                ) { index, itemSong ->
                                    val isFirst = index == 0
                                    val isTarget = index == pageTargetFocusIndex
                                    val isPlaying = currentPlayingMid == itemSong.songMid
                                    TvPlaylistSongItem(
                                        index = index + 1,
                                        song = itemSong,
                                        isPlaying = isPlaying,
                                        leftFocusTarget = playAllFocusRequester,
                                        modifier =
                                            Modifier
                                                .then(
                                                    when {
                                                        isTarget -> Modifier.focusRequester(pageFocusRequester)
                                                        isFirst -> Modifier.focusRequester(firstSongFocusRequester)
                                                        else -> Modifier
                                                    },
                                                ).onPreviewKeyEvent { event ->
                                                    if (event.type == KeyEventType.KeyDown) {
                                                        when (event.key) {
                                                            Key.DirectionRight -> {
                                                                val targetIndex = (index + 8).coerceAtMost(songs.size - 1)
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
                                                },
                                        onClick = { onSongClick(itemSong) },
                                        onLongClick = { onSongLongClick(itemSong) },
                                    )
                                }

                                if (isLoadingMore) {
                                    item(key = "footer_loading_more") {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 14.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "正在载入更多歌曲...",
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
            }
        }
    }
}

/**
 * 与歌单（PlaylistTvScreen）完全一致的单曲条目组件
 * 曲目列表项组件。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPlaylistSongItem(
    index: Int,
    song: Song,
    isPlaying: Boolean,
    leftFocusTarget: FocusRequester? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Card(
        onClick = onClick,
        onLongClick = if (song.canShowArtistAlbumDialog) onLongClick else null,
        modifier =
            modifier
                .fillMaxWidth()
                .height(68.dp)
                .then(
                    if (leftFocusTarget != null) {
                        Modifier.focusProperties {
                            left = leftFocusTarget
                        }
                    } else {
                        Modifier
                    },
                ),
        shape =
            CardDefaults.shape(
                shape = MelodistShapes.ButtonCorner,
                focusedShape = MelodistShapes.ButtonCorner,
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
                        shape = MelodistShapes.ButtonCorner,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                        shape = MelodistShapes.ButtonCorner,
                    ),
            ),
        scale =
            CardDefaults.scale(
                focusedScale = 1.02f,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 序号 (44.dp，与表头对齐)
            Text(
                text = String.format("%02d", index),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (isPlaying) MelodistColors.AccentGreen else LocalContentColor.current,
                modifier = Modifier.width(44.dp),
            )

            // 歌曲 (45%)
            Text(
                text = song.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isPlaying) MelodistColors.AccentGreen else LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(0.45f)
                        .padding(end = 12.dp),
            )

            // 歌手 (28%)
            Text(
                text = song.singer.ifBlank { "群星" },
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = if (isPlaying) MelodistColors.AccentGreen else LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(0.28f)
                        .padding(end = 12.dp),
            )

            // 专辑 (27%)
            Text(
                text = song.album.ifBlank { "单曲" },
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = if (isPlaying) MelodistColors.AccentGreen else LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.27f),
            )
        }
    }
}
