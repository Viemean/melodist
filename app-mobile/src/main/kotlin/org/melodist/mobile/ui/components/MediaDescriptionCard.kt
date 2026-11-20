package org.melodist.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 媒体集合（专辑/歌单）简介预览卡片组件。
 *
 * @param title 卡片标题（如“专辑简介”或“歌单简介”）
 * @param description 预览文本内容（最多截断展示 2 行）
 * @param onClick 点击查看详情的回调
 * @param modifier 外部修饰符
 * @param commentCount 评论数（可选）
 * @param onCommentClick 点击直接拉起评论弹窗的回调（可选）
 */
@Composable
fun MediaDescriptionCard(
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    commentCount: Int? = null,
    onCommentClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onCommentClick != null) {
                        val countText =
                            when {
                                commentCount == null -> ""
                                commentCount > 0 -> formatMediaCount(commentCount.toLong())
                                else -> "0"
                            }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable(onClick = onCommentClick)
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Comment,
                                contentDescription = "评论",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                            if (countText.isNotBlank()) {
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = countText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Text(
                            text = "|",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                    Text(
                        text = "详情 >",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(onClick = onClick)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
            if (description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}
