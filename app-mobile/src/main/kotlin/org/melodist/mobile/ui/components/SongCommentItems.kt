package org.melodist.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.melodist.model.SongComment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class SubCommentsState {
    var isLoading by mutableStateOf(false)
    var isExpanded by mutableStateOf(false)
    val comments = mutableStateListOf<SongComment>()
    var totalCount by mutableIntStateOf(0)
    var hasMore by mutableStateOf(false)
    var lastSeqNo by mutableStateOf("")
    var currentPage by mutableIntStateOf(0)
}

@Composable
internal fun SongCommentItem(
    comment: SongComment,
    modifier: Modifier = Modifier,
    onReplyClick: (SongComment) -> Unit = {},
    onPraiseClick: (SongComment) -> Unit = {},
    onDeleteClick: (SongComment) -> Unit = {},
    onImageClick: ((String) -> Unit)? = null,
    subCommentsState: SubCommentsState? = null,
    onToggleSubComments: (() -> Unit)? = null,
    onLoadMoreSubComments: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = comment.nick,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
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

            Spacer(modifier = Modifier.height(4.dp))

            SelectionContainer {
                Text(
                    text = comment.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 20.sp,
                )
            }

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

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (comment.isSelf) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onDeleteClick(comment) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "删除",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onReplyClick(comment) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Reply,
                            contentDescription = "回复",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "回复",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }

                    val isPraised = comment.isPraised
                    val praiseCount = comment.praiseNum
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onPraiseClick(comment) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ThumbUp,
                            contentDescription = if (isPraised) "取消赞" else "点赞",
                            tint =
                                if (isPraised) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                },
                            modifier = Modifier.size(13.dp),
                        )
                        if (praiseCount > 0) {
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = formatCount(praiseCount),
                                style = MaterialTheme.typography.labelSmall,
                                color =
                                    if (isPraised) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                    },
                            )
                        }
                    }
                }
            }

            val isExpanded = subCommentsState?.isExpanded == true
            val subList = subCommentsState?.comments.orEmpty()
            val subLoading = subCommentsState?.isLoading == true
            val subHasMore = subCommentsState?.hasMore == true

            if (!isExpanded && (comment.replyCnt > 0 || subList.isNotEmpty())) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onToggleSubComments?.invoke() }
                            .padding(vertical = 3.dp, horizontal = 2.dp),
                ) {
                    val count = if (comment.replyCnt > 0) comment.replyCnt else subList.size
                    Text(
                        text = "查看${count}条回复",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else if (isExpanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onToggleSubComments?.invoke() }
                            .padding(vertical = 3.dp, horizontal = 2.dp),
                ) {
                    val count = if (comment.replyCnt > 0) comment.replyCnt else subList.size
                    Text(
                        text = "查看${count}条回复",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (subLoading && subList.isEmpty()) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        subList.forEachIndexed { index, subComment ->
                            if (index > 0) {
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                            SubCommentItem(
                                comment = subComment,
                                onReplyClick = onReplyClick,
                                onPraiseClick = onPraiseClick,
                                onDeleteClick = onDeleteClick,
                                onImageClick = onImageClick,
                            )
                        }

                        if (subLoading && subList.isNotEmpty()) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            if (subHasMore) {
                                Text(
                                    text = "展开更多回复",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier =
                                        Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable { onLoadMoreSubComments?.invoke() }
                                            .padding(vertical = 4.dp, horizontal = 2.dp),
                                )
                            } else {
                                Spacer(modifier = Modifier.width(1.dp))
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { onToggleSubComments?.invoke() }
                                        .padding(vertical = 4.dp, horizontal = 2.dp),
                            ) {
                                Text(
                                    text = "收起",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowUp,
                                    contentDescription = "收起",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SubCommentItem(
    comment: SongComment,
    modifier: Modifier = Modifier,
    onReplyClick: (SongComment) -> Unit = {},
    onPraiseClick: (SongComment) -> Unit = {},
    onDeleteClick: (SongComment) -> Unit = {},
    onImageClick: ((String) -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        if (comment.avatarUrl.isNotBlank()) {
            AsyncImage(
                model = comment.avatarUrl,
                contentDescription = comment.nick,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = comment.nick,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

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
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = metaText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
            }

            Spacer(modifier = Modifier.height(3.dp))

            SelectionContainer {
                Text(
                    text = comment.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 18.sp,
                )
            }

            if (comment.picUrl.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                AsyncImage(
                    model = comment.picUrl,
                    contentDescription = "回复配图",
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .widthIn(max = 180.dp)
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onImageClick?.invoke(comment.picUrl) },
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (comment.isSelf) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { onDeleteClick(comment) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "删除",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onReplyClick(comment) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Reply,
                            contentDescription = "回复",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "回复",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }

                    val isPraised = comment.isPraised
                    val praiseCount = comment.praiseNum
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onPraiseClick(comment) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ThumbUp,
                            contentDescription = if (isPraised) "取消赞" else "点赞",
                            tint =
                                if (isPraised) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                },
                            modifier = Modifier.size(12.dp),
                        )
                        if (praiseCount > 0) {
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = formatCount(praiseCount),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color =
                                    if (isPraised) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun formatCount(count: Int): String =
    when {
        count >= 100_000_000 -> String.format(Locale.getDefault(), "%.1f亿", count / 100_000_000.0)
        count >= 10_000 -> String.format(Locale.getDefault(), "%.1f万", count / 10_000.0)
        else -> count.toString()
    }

internal fun formatTimestamp(timestampSec: Long): String {
    val sdf = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
    return sdf.format(Date(timestampSec * 1000L))
}
