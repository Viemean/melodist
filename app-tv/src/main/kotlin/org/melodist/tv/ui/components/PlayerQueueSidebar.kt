package org.melodist.tv.ui.components

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.rememberTvWindowMetrics

private const val SCROLL_PAGE_STEP = 8

/**
 * 播放队列侧边栏抽屉组件
 * - 纯透明平铺列表，仅展示歌曲名称（无任何多余格子底色）
 * - 获焦呈现白底黑字高对比度焦点框
 * - 顶部小巧翻页按钮 ↔︎ 点击直接跳一页（连续滚动 LazyColumn）
 * - 支持曲目项左右键快速跳页
 */
@Composable
fun PlayerQueueSidebar(
    playlist: List<Song>,
    currentSong: Song?,
    surfaceColor: Color,
    isOpen: Boolean,
    onSelectSong: (Song) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isOpen) {
        BackHandler {
            onDismiss()
        }
    }

    val metrics = rememberTvWindowMetrics()
    val sidebarWidth = (metrics.screenWidthDp * 0.30f).coerceIn(380.dp, 500.dp)

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstRequester = remember { FocusRequester() }

    val paginationSource by PlaybackManager.paginationSource.collectAsState()
    val isLoadingMoreForQueue by PlaybackManager.isLoadingMoreForQueue.collectAsState()
    val isRadioMode by PlaybackManager.isRadioMode.collectAsState()

    val shouldLoadMore by remember {
        derivedStateOf {
            if (isRadioMode) return@derivedStateOf false
            val totalCount = listState.layoutInfo.totalItemsCount
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalCount > 0 && lastVisibleIndex >= totalCount - 4
        }
    }

    LaunchedEffect(shouldLoadMore, paginationSource, isLoadingMoreForQueue) {
        if (shouldLoadMore && paginationSource?.hasMore == true && !isLoadingMoreForQueue && paginationSource?.isLoadingMore == false) {
            PlaybackManager.loadMoreForQueue()
        }
    }

    // 打开时自动定位至当前在播歌曲位置
    LaunchedEffect(isOpen) {
        if (isOpen && playlist.isNotEmpty()) {
            val currentIndex = playlist.indexOfFirst { it.songMid == currentSong?.songMid }
            if (currentIndex >= 0) {
                listState.scrollToItem(maxOf(0, currentIndex - 2))
            }
            kotlinx.coroutines.delay(120)
            try {
                firstRequester.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    AnimatedVisibility(
        visible = isOpen,
        enter =
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(durationMillis = 260),
            ) + fadeIn(animationSpec = tween(260)),
        exit =
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(durationMillis = 220),
            ) + fadeOut(animationSpec = tween(220)),
        modifier = modifier,
    ) {
        Box(
            modifier =
                Modifier
                    .width(sidebarWidth)
                    .fillMaxHeight()
                    .shadow(
                        elevation = 20.dp,
                        ambientColor = Color.Black.copy(alpha = 0.45f),
                        spotColor = Color.Black.copy(alpha = 0.65f),
                        clip = false,
                    ).background(surfaceColor)
                    .border(
                        BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                    ).padding(horizontal = 20.dp, vertical = metrics.verticalSafePadding),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 仅保留翻页按钮 ↔︎，点击直接跳一页
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvNavIconButton(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "上一页",
                        modifier = Modifier.focusRequester(firstRequester),
                        onClick = {
                            scope.launch {
                                val target = maxOf(0, listState.firstVisibleItemIndex - SCROLL_PAGE_STEP)
                                listState.animateScrollToItem(target)
                            }
                        },
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    TvNavIconButton(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "下一页",
                        onClick = {
                            scope.launch {
                                val target =
                                    minOf(
                                        maxOf(0, playlist.size - 1),
                                        listState.firstVisibleItemIndex + SCROLL_PAGE_STEP,
                                    )
                                listState.animateScrollToItem(target)
                            }
                        },
                    )
                }

                // 歌曲列表（纯透明平铺，只有歌曲名字）
                if (playlist.isEmpty()) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "播放队列为空",
                            fontSize = 15.sp,
                            color = MelodistColors.TextMuted,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        itemsIndexed(playlist, key = { index, item -> "${item.songMid}_$index" }) { index, song ->
                            val isCurrentPlaying = song.songMid == currentSong?.songMid
                            val isFirst = index == 0

                            TvQueueSongRow(
                                index = index + 1,
                                song = song,
                                isPlaying = isCurrentPlaying,
                                modifier = Modifier,
                                onClick = { onSelectSong(song) },
                                onPageLeft = {
                                    scope.launch {
                                        val target = maxOf(0, listState.firstVisibleItemIndex - SCROLL_PAGE_STEP)
                                        listState.animateScrollToItem(target)
                                    }
                                },
                                onPageRight = {
                                    scope.launch {
                                        val target =
                                            minOf(
                                                maxOf(0, playlist.size - 1),
                                                listState.firstVisibleItemIndex + SCROLL_PAGE_STEP,
                                            )
                                        listState.animateScrollToItem(target)
                                    }
                                },
                            )
                        }

                        if (isLoadingMoreForQueue) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "正在加载更多曲目...",
                                        color = Color.White.copy(alpha = 0.5f),
                                        fontSize = 12.sp,
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvNavIconButton(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Button(
        onClick = onClick,
        modifier =
            modifier
                .size(36.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) { onClick() },
        interactionSource = interactionSource,
        shape =
            ButtonDefaults.shape(
                shape = MelodistShapes.ButtonCorner,
                focusedShape = MelodistShapes.ButtonCorner,
            ),
        colors =
            ButtonDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.White,
                contentColor = MelodistColors.TextPrimary,
                focusedContentColor = Color.Black,
            ),
        border =
            ButtonDefaults.border(
                border =
                    Border(
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                        shape = MelodistShapes.ButtonCorner,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(2.dp, MelodistColors.FocusTeal),
                        shape = MelodistShapes.ButtonCorner,
                    ),
            ),
        scale =
            ButtonDefaults.scale(
                focusedScale = 1.08f,
            ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvQueueSongRow(
    index: Int,
    song: Song,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPageLeft: () -> Unit,
    onPageRight: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Button(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) { onClick() }
                .onKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                onPageLeft()
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                onPageRight()
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                },
        interactionSource = interactionSource,
        shape =
            ButtonDefaults.shape(
                shape = MelodistShapes.ButtonCorner,
                focusedShape = MelodistShapes.ButtonCorner,
            ),
        colors =
            ButtonDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.White,
                contentColor = if (isPlaying) MelodistColors.AccentGreen else MelodistColors.TextPrimary,
                focusedContentColor = Color.Black,
            ),
        border =
            ButtonDefaults.border(
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
            ButtonDefaults.scale(
                focusedScale = 1.02f,
            ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 序号或在播图标
            Box(
                modifier = Modifier.width(26.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (isPlaying) {
                    Icon(
                        imageVector = Icons.Filled.Equalizer,
                        contentDescription = "正在播放",
                        modifier = Modifier.size(17.dp),
                    )
                } else {
                    Text(
                        text = String.format("%02d", index),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MelodistColors.TextSecondary,
                    )
                }
            }

            // 仅展示歌曲名称
            Text(
                text = song.name,
                fontSize = 15.sp,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
