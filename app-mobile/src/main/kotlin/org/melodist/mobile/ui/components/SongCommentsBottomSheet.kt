package org.melodist.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.api.getSongComments
import org.melodist.model.Song
import org.melodist.model.SongComment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongCommentsBottomSheet(
    song: Song,
    onDismissRequest: () -> Unit,
) {
    // 锁定打开评论弹窗时的目标歌曲，防止后台自动切歌导致当前阅读的评论区被意外刷新
    val targetSong = remember { song }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val apiService = remember { MusicApiService() }

    val hotComments = remember { mutableStateListOf<SongComment>() }
    val normalComments = remember { mutableStateListOf<SongComment>() }
    val seenCommentIds = remember { mutableSetOf<String>() }
    var previewImageUrl by remember { mutableStateOf<String?>(null) }
    var totalCommentCount by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableIntStateOf(0) }
    var lastCommentSeqNo by remember { mutableStateOf("") }
    var hasMore by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }
    var isHotCommentsExpanded by remember { mutableStateOf(false) }

    fun loadComments(
        page: Int,
        isInitial: Boolean,
    ) {
        if (isInitial) {
            isLoading = true
            isError = false
            lastCommentSeqNo = ""
            isHotCommentsExpanded = false
        } else {
            if (isLoading || isLoadingMore || !hasMore) return
            isLoadingMore = true
        }
        scope.launch {
            try {
                val pageResult =
                    apiService.getSongComments(
                        songId = targetSong.songId,
                        songMid = targetSong.songMid,
                        pageNum = page,
                        pageSize = 25,
                        lastCommentSeqNo = if (isInitial) "" else lastCommentSeqNo,
                    )
                if (pageResult != null) {
                    if (isInitial) {
                        seenCommentIds.clear()
                        hotComments.clear()
                        pageResult.hotComments.forEach { hot ->
                            seenCommentIds.add(hot.commentId)
                            hotComments.add(hot)
                        }
                        normalComments.clear()
                        val initialNormal = pageResult.comments.filter { seenCommentIds.add(it.commentId) }
                        normalComments.addAll(initialNormal)
                        totalCommentCount = pageResult.totalCount
                        hasMore = pageResult.hasMore
                        lastCommentSeqNo = pageResult.lastSeqNo
                    } else {
                        if (pageResult.comments.isEmpty()) {
                            hasMore = false
                        } else {
                            val newComments = pageResult.comments.filter { seenCommentIds.add(it.commentId) }
                            if (newComments.isEmpty()) {
                                hasMore = false
                            } else {
                                normalComments.addAll(newComments)
                                hasMore = pageResult.hasMore
                                if (pageResult.lastSeqNo.isNotBlank()) {
                                    lastCommentSeqNo = pageResult.lastSeqNo
                                }
                            }
                        }
                    }
                    currentPage = page
                    isError = false
                } else {
                    if (isInitial) {
                        isError = true
                    } else {
                        hasMore = false
                    }
                }
            } catch (_: Exception) {
                if (isInitial) {
                    isError = true
                } else {
                    hasMore = false
                }
            } finally {
                isLoading = false
                isLoadingMore = false
            }
        }
    }

    LaunchedEffect(targetSong.songMid, targetSong.songId) {
        loadComments(page = 0, isInitial = true)
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .navigationBarsPadding(),
        ) {
            // 顶栏：标题与评论数统计（无冗余关闭按钮，支持返回键与下拉关闭）
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "歌曲评论",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (totalCommentCount > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(${formatCount(totalCommentCount)})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${targetSong.name} · ${targetSong.singer.ifBlank { "未知歌手" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )

            // 内容区
            when {
                isLoading -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                isError -> {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "加载评论失败，请检查网络",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(onClick = { loadComments(page = 0, isInitial = true) }) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("点击重试")
                        }
                    }
                }
                hotComments.isEmpty() && normalComments.isEmpty() -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "暂无评论，快去留下第一条评论吧",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    val listState = rememberLazyListState()
                    val showScrollToTop by remember {
                        derivedStateOf {
                            listState.firstVisibleItemIndex > 3
                        }
                    }

                    // 滑动到底部自动加载下一页（采用 snapshotFlow 精准监听，避免重组循环振荡）
                    LaunchedEffect(listState, hasMore) {
                        snapshotFlow {
                            val layoutInfo = listState.layoutInfo
                            val totalItems = layoutInfo.totalItemsCount
                            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            hasMore && !isLoading && !isLoadingMore && totalItems > 0 && lastVisibleItemIndex >= totalItems - 2
                        }.distinctUntilChanged()
                            .collect { shouldLoad ->
                                if (shouldLoad) {
                                    loadComments(page = currentPage + 1, isInitial = false)
                                }
                            }
                    }

                    // 拦截底部未消费的向上滑动手势，阻止冒泡到父级 ModalBottomSheet 导致窗口被过度上拉或抖动
                    val listNestedScrollConnection =
                        remember {
                            object : NestedScrollConnection {
                                override fun onPostScroll(
                                    consumed: Offset,
                                    available: Offset,
                                    source: NestedScrollSource,
                                ): Offset =
                                    if (available.y < 0f) {
                                        Offset(0f, available.y)
                                    } else {
                                        Offset.Zero
                                    }
                            }
                        }

                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .nestedScroll(listNestedScrollConnection),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                        if (hotComments.isNotEmpty()) {
                            val hasMoreHot = hotComments.size > 3
                            val displayedHot =
                                if (isHotCommentsExpanded || !hasMoreHot) {
                                    hotComments
                                } else {
                                    hotComments.take(3)
                                }

                            item(key = "header_hot") {
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp, bottom = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "精彩评论",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    if (hasMoreHot) {
                                        Row(
                                            modifier =
                                                Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .clickable { isHotCommentsExpanded = !isHotCommentsExpanded }
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = if (isHotCommentsExpanded) "收起" else "共 ${hotComments.size} 条",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Icon(
                                                imageVector =
                                                    if (isHotCommentsExpanded) {
                                                        Icons.Rounded.KeyboardArrowUp
                                                    } else {
                                                        Icons.Rounded.KeyboardArrowDown
                                                    },
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }

                            items(
                                items = displayedHot,
                                key = { "hot_${it.commentId}" },
                            ) { comment ->
                                SongCommentItem(
                                    comment = comment,
                                    onImageClick = { previewImageUrl = it },
                                )
                            }

                            if (hasMoreHot) {
                                item(key = "hot_toggle_footer") {
                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                                .clickable { isHotCommentsExpanded = !isHotCommentsExpanded }
                                                .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text =
                                                    if (isHotCommentsExpanded) {
                                                        "收起精彩评论"
                                                    } else {
                                                        "展开更多精彩评论 (还有 ${hotComments.size - 3} 条)"
                                                    },
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                imageVector =
                                                    if (isHotCommentsExpanded) {
                                                        Icons.Rounded.KeyboardArrowUp
                                                    } else {
                                                        Icons.Rounded.KeyboardArrowDown
                                                    },
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (normalComments.isNotEmpty()) {
                            item(key = "header_normal") {
                                Text(
                                    text = "最新评论",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                )
                            }
                            items(
                                items = normalComments,
                                key = { "normal_${it.commentId}" },
                            ) { comment ->
                                SongCommentItem(
                                    comment = comment,
                                    onImageClick = { previewImageUrl = it },
                                )
                            }
                        }

                        item(key = "footer_loading") {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else if (!hasMore) {
                                    Text(
                                        text = "已显示全部评论",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = showScrollToTop,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 20.dp, bottom = 20.dp),
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                scope.launch {
                                    listState.animateScrollToItem(0)
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = CircleShape,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowUp,
                                contentDescription = "返回顶部",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
        }
    }

    // 评论配图全屏大图预览（复用 FullScreenImageViewer，支持手势缩放、规格信息与保存至相册）
    previewImageUrl?.let { imgUrl ->
        FullScreenImageViewer(
            imageUrl = imgUrl,
            title = "保存评论图片",
            subTitle = "${targetSong.name} · 评论配图",
            filePrefix = "comment_${targetSong.name}",
            saveTipText = "是否保存当前评论图片到系统相册？",
            onDismissRequest = { previewImageUrl = null },
        )
    }
}

@Composable
private fun SongCommentItem(
    comment: SongComment,
    modifier: Modifier = Modifier,
    onImageClick: ((String) -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        // 用户头像
        if (comment.avatarUrl.isNotBlank()) {
            AsyncImage(
                model = comment.avatarUrl,
                contentDescription = comment.nick,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // 用户名与点赞
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Text(
                        text = comment.nick,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (comment.isHot) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = "热评",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }

                if (comment.praiseNum > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ThumbUp,
                            contentDescription = "点赞",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formatCount(comment.praiseNum),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // 时间与属地
            val timeText = if (comment.timeSec > 0L) formatTimestamp(comment.timeSec) else ""
            val locText = comment.location.ifBlank { "" }
            val metaText =
                when {
                    timeText.isNotBlank() && locText.isNotBlank() -> "$timeText · 来自$locText"
                    timeText.isNotBlank() -> timeText
                    locText.isNotBlank() -> "来自$locText"
                    else -> ""
                }
            if (metaText.isNotBlank()) {
                Text(
                    text = metaText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 正文：长按选择复制
            SelectionContainer {
                Text(
                    text = comment.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 20.sp,
                )
            }

            // 评论配图
            if (comment.picUrl.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                AsyncImage(
                    model = comment.picUrl,
                    contentDescription = "评论图片",
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .widthIn(max = 220.dp)
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onImageClick?.invoke(comment.picUrl) },
                )
            }
        }
    }
}

private fun formatCount(count: Int): String =
    when {
        count >= 100_000_000 -> "%.1f亿".format(count / 100_000_000.0)
        count >= 10_000 -> "%.1f万".format(count / 10_000.0)
        else -> count.toString()
    }

private fun formatTimestamp(timestampSec: Long): String =
    try {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sdf.format(Date(timestampSec * 1000L))
    } catch (_: Exception) {
        ""
    }
