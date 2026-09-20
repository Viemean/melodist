package org.melodist.tv.ui.webdav

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.melodist.model.WebDavItem
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes

/**
 * TV 遥控器导航胶囊按钮
 */
@Composable
fun WebDavNavButton(
    icon: ImageVector,
    text: String,
    isAccent: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val baseBgColor =
        if (isAccent) {
            MelodistColors.AccentGreen.copy(alpha = 0.22f)
        } else {
            Color.White.copy(alpha = 0.12f)
        }
    val baseBorderColor =
        if (isAccent) {
            MelodistColors.AccentGreen.copy(alpha = 0.50f)
        } else {
            Color.White.copy(alpha = 0.18f)
        }
    val baseContentColor =
        if (isAccent) {
            MelodistColors.AccentGreen
        } else {
            MelodistColors.TextPrimary
        }

    Row(
        modifier =
            modifier
                .height(40.dp)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .focusable(interactionSource = interactionSource)
                .border(
                    border =
                        BorderStroke(
                            width = if (isFocused) 2.dp else 1.dp,
                            color = if (isFocused) MelodistColors.FocusTeal else baseBorderColor,
                        ),
                    shape = MelodistShapes.PillCorner,
                ).background(
                    color = if (isFocused) Color.White else baseBgColor,
                    shape = MelodistShapes.PillCorner,
                ).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = if (isFocused) Color.Black else baseContentColor,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            fontSize = 14.sp,
            maxLines = 1,
            softWrap = false,
            color = if (isFocused) Color.Black else baseContentColor,
        )
    }
}

/**
 * 列表首项展示的返回上一级目录 ..
 */
@Composable
fun WebDavParentFolderRow(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .focusable(interactionSource = interactionSource)
                .border(
                    border =
                        BorderStroke(
                            width = if (isFocused) 2.dp else 1.dp,
                            color = if (isFocused) MelodistColors.FocusTeal else Color.White.copy(alpha = 0.08f),
                        ),
                    shape = RoundedCornerShape(10.dp),
                ).background(
                    color = if (isFocused) Color.White else Color.White.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(10.dp),
                ).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = if (isFocused) Color.Black else MelodistColors.AccentGreen,
            modifier = Modifier.size(24.dp),
        )

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = "..  (返回上一级目录)",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isFocused) Color.Black else MelodistColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 目录浏览文件项渲染组件
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WebDavItemRow(
    item: WebDavItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ).focusable(interactionSource = interactionSource)
                .border(
                    border =
                        BorderStroke(
                            width = if (isFocused) 2.dp else 0.dp,
                            color = if (isFocused) MelodistColors.FocusTeal else Color.Transparent,
                        ),
                    shape = RoundedCornerShape(10.dp),
                ).background(
                    color = if (isFocused) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                ).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Default.MusicNote,
            contentDescription = null,
            tint =
                if (isFocused) {
                    Color.Black
                } else if (item.isDirectory) {
                    MelodistColors.AccentGreen
                } else {
                    Color.White
                },
            modifier = Modifier.size(24.dp),
        )

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = item.name,
            fontSize = 15.sp,
            fontWeight = if (item.isDirectory) FontWeight.SemiBold else FontWeight.Medium,
            color = if (isFocused) Color.Black else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (!item.isDirectory && item.contentLength > 0L) {
            val sizeMb = "%.1f MB".format(item.contentLength / (1024.0 * 1024.0))
            Text(
                text = sizeMb,
                fontSize = 13.sp,
                color = if (isFocused) Color.DarkGray else Color.White.copy(alpha = 0.7f),
            )
        } else if (item.isDirectory) {
            Text(
                text = "长按递归扫描",
                fontSize = 12.sp,
                color = if (isFocused) Color.DarkGray else MelodistColors.TextMuted,
            )
        }
    }
}
